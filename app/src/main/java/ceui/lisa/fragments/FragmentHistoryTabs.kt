package ceui.lisa.fragments

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.MenuItemCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentPagerAdapter
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.viewpager.widget.ViewPager
import ceui.lisa.R
import ceui.lisa.activities.BaseActivity
import ceui.lisa.database.AppDatabase
import ceui.lisa.databinding.ViewpagerWithTablayoutBinding
import ceui.lisa.download.IllustDownload
import ceui.lisa.interfaces.Callback
import ceui.lisa.utils.Common
import ceui.pixiv.api.Client
import ceui.pixiv.ui.history.CloudHistoryConsent
import ceui.pixiv.db.HistoryBackfill
import ceui.pixiv.db.RecordType
import ceui.pixiv.session.SessionManager
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.ui.history.BrowseHistoryBackup
import timber.log.Timber
import ceui.pixiv.witstudio.dialog.WitDialog
import ceui.pixiv.witstudio.dialog.WitDialogAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tab wrapper for the browsing-history page.
 * Three tabs: 插画/漫画 (type=0) | 小说 (type=1) | 用户 (GeneralEntity)
 *
 * 顶部 toolbar 上挂一个 SearchView + 一键清空按钮 (R.menu.history_v3)。
 * 搜索输入 debounce 200ms 后写入 [HistorySearchSharedViewModel.query]，三个
 * 子 tab 各自 collect 后切换数据源 (DAO LIKE)。一键清空走 QMUI 二次确认弹窗，
 * 删除按钮红色 (ACTION_PROP_NEGATIVE)，确认后清掉 illust_table 全部历史 +
 * general_table 里 VIEW_USER_HISTORY 行，再让现存子 tab 重新拉 DAO (#886)。
 */
class FragmentHistoryTabs : Fragment(R.layout.viewpager_with_tablayout) {

    private val binding by viewBinding(ViewpagerWithTablayoutBinding::bind)
    private val searchVm: HistorySearchSharedViewModel by activityViewModels()

    private var searchDebounceJob: Job? = null

    // —— 多选删除(contextual toolbar)——
    // 一次只有当前可见 tab 进选择态:host 把 toolbar 换成 ✕/已选 N 项/全选/删除,
    // 子 tab(SelectableHistoryTab)管自己列表选中态。切 tab/返回即退出。
    private var inSelectionMode = false
    private var activeSelectionTab: SelectableHistoryTab? = null
    private var selectedCountObserver: Observer<Int>? = null
    private lateinit var selectionBackCallback: OnBackPressedCallback

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.title = " "
        binding.toolbarTitle.text = getString(R.string.view_history)
        binding.toolbar.setNavigationOnClickListener { activity?.finish() }

        val tabs = listOf(
            getString(R.string.string_246) to 0,    // 插画/漫画
            getString(R.string.string_237) to 1,    // 小说
            getString(R.string.tab_user) to -1,     // 用户
        )

        val fragments = tabs.map { (_, type) ->
            if (type >= 0) {
                FragmentHistoryList.newInstance(type)
            } else {
                FragmentHistoryUserList()
            }
        }

        binding.viewPager.adapter = object : FragmentPagerAdapter(
            childFragmentManager, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT
        ) {
            override fun getItem(position: Int): Fragment = fragments[position]
            override fun getCount(): Int = tabs.size
            override fun getPageTitle(position: Int): CharSequence = tabs[position].first
        }
        binding.tabLayout.setupWithViewPager(binding.viewPager)

        setupSearchMenu()

        // 选择态返回键:优先退选择态而不是关页。enabled 只在选择态为 true。
        // 注册在 setupSearchMenu(搜索收起 callback)之后 → 选择态优先级更高;两者
        // 不会同时 enabled(进选择态前会收起搜索框)。
        selectionBackCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                exitSelectionMode()
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, selectionBackCallback)

        // 切 tab 时退出选择态:选中态是 per-tab 的,跨 tab 操作语义不清。
        binding.viewPager.addOnPageChangeListener(object : ViewPager.SimpleOnPageChangeListener() {
            override fun onPageSelected(position: Int) {
                if (inSelectionMode) exitSelectionMode()
            }
        })

        // 云同步同意框挪到这里:用户点进浏览历史页才问一次「是否把记录存到云端」,
        // 而不是一进首页就弹(见 issue #889)。已弹过/未登录则什么都不做。
        // 选 KEEP 后读取会切到云端,所以选完要重刷一下子 tab。
        view.post { activity?.let { CloudHistoryConsent.maybeShowConsent(it) { reloadAllTabs() } } }
        // 存量本地历史回填(#989):同意框弹出之前就开着同步的老用户,persist 不会再触发,
        // 打开历史页是他们唯一稳定的补触发点。自带「每设备一次 + 正在跑不重入」门槛。
        HistoryBackfill.maybeSchedule()
    }

    /** 让现存的子 tab 各自重走 VM.loadFirst(),按当前 useRemote() 重读数据源。 */
    private fun reloadAllTabs() {
        // 可能从后台协程(导入/清空成功后)回调,此时 fragment 已 detach 则 childFragmentManager 会抛。
        if (!isAdded) return
        childFragmentManager.fragments.forEach { child ->
            when (child) {
                is FragmentHistoryList -> child.reloadFromDao()
                is FragmentHistoryUserList -> child.reloadFromDao()
            }
        }
    }

    /**
     * inflate R.menu.history_v3 到 toolbar，SearchView 输入 debounce 200ms 后
     * 写入共享 query；delete 按钮触发一键清空弹窗 (#886)。
     */
    private fun setupSearchMenu() {
        binding.toolbar.inflateMenu(R.menu.history_v3)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_delete -> { showClearAllDialog(); true }
                R.id.action_export_history -> { exportHistory(); true }
                R.id.action_import_history -> { pickImportFile(); true }
                R.id.action_multi_select -> { enterSelectionMode(); true }
                R.id.action_select_all_toggle -> { activeSelectionTab?.toggleSelectAll(); refreshSelectionToolbar(); true }
                R.id.action_delete_selected -> { confirmDeleteSelected(); true }
                else -> false
            }
        }
        val searchItem: MenuItem = binding.toolbar.menu.findItem(R.id.action_search) ?: return
        val searchView = MenuItemCompat.getActionView(searchItem) as? SearchView ?: return
        searchView.queryHint = getString(R.string.history_search_hint)
        searchView.maxWidth = Int.MAX_VALUE
        // 展开搜索框后,优先用返回手势/键收起搜索框,不直接退出 activity。
        // callback.isEnabled 在 expand/collapse listener 里切。
        val backCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                if (searchItem.isActionViewExpanded) searchItem.collapseActionView()
                isEnabled = false
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)
        // toolbar 背景 = ?attr/colorPrimary（用户主题色,中等亮度紫/蓝/绿等），
        // SearchView 默认 text/hint 是 ?attr/textColorPrimary/Hint，在浅色 day
        // theme 下解出来是黑/深灰,叠在彩色 toolbar 上完全看不清。强制白字 +
        // 70% 白 hint,跟周围的 navigationIcon (ic_arrow_back_white_shadow) 一致。
        searchView.findViewById<EditText>(androidx.appcompat.R.id.search_src_text)?.apply {
            setTextColor(Color.WHITE)
            setHintTextColor(0xB3FFFFFF.toInt())
        }

        MenuItemCompat.setOnActionExpandListener(searchItem, object : MenuItemCompat.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem): Boolean {
                searchVm.setQuery("")
                backCallback.isEnabled = true
                return true
            }

            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                searchDebounceJob?.cancel()
                searchVm.setQuery(null)
                backCallback.isEnabled = false
                return true
            }
        })

        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                searchVm.setQuery(query.orEmpty().trim())
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                searchDebounceJob?.cancel()
                searchDebounceJob = viewLifecycleOwner.lifecycleScope.launch {
                    delay(200)
                    searchVm.setQuery(newText.orEmpty().trim())
                }
                return true
            }
        })
    }

    // —— 多选删除 contextual toolbar —— //

    /** 当前可见的子 tab(按 FragmentPagerAdapter 的 tag 取活实例,旋转后也准)。 */
    private fun currentSelectableTab(): SelectableHistoryTab? {
        val pos = binding.viewPager.currentItem
        val tag = "android:switcher:${binding.viewPager.id}:$pos"
        return childFragmentManager.findFragmentByTag(tag) as? SelectableHistoryTab
    }

    /** 进选择态:让当前 tab 显示勾选框,toolbar 换成 ✕/已选 N 项/全选/删除。 */
    private fun enterSelectionMode() {
        if (inSelectionMode) return
        val tab = currentSelectableTab() ?: return
        if (!tab.hasItems()) {
            Common.showToast(getString(R.string.string_254))
            return
        }
        // 收起搜索框(防止选择态跟展开的 SearchView 抢 toolbar)。
        binding.toolbar.menu.findItem(R.id.action_search)?.collapseActionView()
        inSelectionMode = true
        activeSelectionTab = tab
        tab.enterSelectionMode()
        val obs = Observer<Int> { refreshSelectionToolbar() }
        tab.selectedCount.observe(viewLifecycleOwner, obs)
        selectedCountObserver = obs
        applySelectionToolbar()
        selectionBackCallback.isEnabled = true
    }

    /** 退选择态:清当前 tab 选中,toolbar 还原成普通态。 */
    private fun exitSelectionMode() {
        if (!inSelectionMode) return
        inSelectionMode = false
        selectionBackCallback.isEnabled = false
        selectedCountObserver?.let { activeSelectionTab?.selectedCount?.removeObserver(it) }
        selectedCountObserver = null
        activeSelectionTab?.exitSelectionMode()
        activeSelectionTab = null
        restoreNormalToolbar()
    }

    private fun applySelectionToolbar() {
        val menu = binding.toolbar.menu
        menu.setGroupVisible(R.id.group_normal, false)
        menu.setGroupVisible(R.id.group_selection, true)
        binding.toolbar.navigationIcon =
            ContextCompat.getDrawable(requireContext(), R.drawable.ic_close_black_24dp)
                ?.mutate()?.apply { setTint(Color.WHITE) }
        binding.toolbar.setNavigationOnClickListener { exitSelectionMode() }
        refreshSelectionToolbar()
    }

    private fun restoreNormalToolbar() {
        val menu = binding.toolbar.menu
        menu.setGroupVisible(R.id.group_selection, false)
        menu.setGroupVisible(R.id.group_normal, true)
        binding.toolbarTitle.text = getString(R.string.view_history)
        binding.toolbar.navigationIcon =
            ContextCompat.getDrawable(requireContext(), R.drawable.ic_arrow_back_white_shadow)
        binding.toolbar.setNavigationOnClickListener { activity?.finish() }
    }

    /** 刷 toolbar 标题(已选 N 项)+ 删除键可用态 + 全选/取消全选图标。 */
    private fun refreshSelectionToolbar() {
        val tab = activeSelectionTab ?: return
        val count = tab.selectedCount.value ?: 0
        binding.toolbarTitle.text = getString(R.string.history_selected_count, count)
        val menu = binding.toolbar.menu
        menu.findItem(R.id.action_delete_selected)?.isEnabled = count > 0
        val allSelected = tab.isAllSelected()
        menu.findItem(R.id.action_select_all_toggle)?.apply {
            setIcon(if (allSelected) R.drawable.ic_deselect_24 else R.drawable.ic_select_all_24)
            setTitle(if (allSelected) R.string.bulk_select_clear_all else R.string.bulk_select_select_all)
        }
        tintSelectionIcons()
    }

    /** 选择态菜单图标强制白色:彩色 toolbar 上 ic_select_all/ic_delete 默认色看不清。 */
    private fun tintSelectionIcons() {
        val menu = binding.toolbar.menu
        intArrayOf(R.id.action_select_all_toggle, R.id.action_delete_selected).forEach { id ->
            menu.findItem(id)?.let { item ->
                item.icon = item.icon?.mutate()?.also { it.setTint(Color.WHITE) }
            }
        }
    }

    private fun confirmDeleteSelected() {
        val tab = activeSelectionTab ?: return
        val count = tab.selectedCount.value ?: 0
        if (count == 0) return
        val act = activity ?: return
        WitDialog.MessageDialogBuilder(act)
            .setTitle(R.string.string_143)
            .setMessage(getString(R.string.history_multi_delete_message, count))
            .addAction(R.string.string_142) { d, _ -> d.dismiss() }
            .addAction(0, R.string.string_141, WitDialogAction.ACTION_PROP_NEGATIVE) { d, _ ->
                d.dismiss()
                tab.deleteSelected { deleted ->
                    // 批量删 N 条要逐条打远端,耗时;期间宿主 view 若被销毁(旋转等),
                    // 回调里碰 binding(exitSelectionMode→restoreNormalToolbar)会崩。
                    // 对齐子 fragment 的 withBinding 防护:view 没了就只让 VM 自己收尾。
                    if (view == null) return@deleteSelected
                    if (deleted > 0) {
                        Common.showToast(getString(R.string.history_multi_delete_done, deleted))
                    }
                    exitSelectionMode()
                }
            }
            .show()
    }

    /**
     * 二次确认弹窗 → 清空 illust_table 全部历史 + general_table 里
     * VIEW_USER_HISTORY 行，然后让现存子 fragment 重新拉 DAO。删除键红色。
     */
    private fun showClearAllDialog() {
        val act = activity ?: return
        WitDialog.MessageDialogBuilder(act)
            .setTitle(R.string.string_143)
            .setMessage(R.string.clear_browse_history_message)
            .addAction(R.string.string_142) { d, _ -> d.dismiss() }
            .addAction(0, R.string.string_141, WitDialogAction.ACTION_PROP_NEGATIVE) { d, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        val db = AppDatabase.getAppDatabase(act)
                        db.downloadDao().deleteAllHistory()
                        db.generalDao().deleteAllByRecordType(RecordType.VIEW_USER_HISTORY)
                        // 历史列表已读远端,清空也要清远端(全部类型)。
                        val uid = SessionManager.loggedInUid
                        if (uid > 0L) {
                            runCatching { Client.pixshaft.clearHistory(uid, null) }
                                .onFailure { Timber.e(it, "remote history clear failed") }
                        }
                    }
                    reloadAllTabs()
                    Common.showToast(act.getString(R.string.string_220))
                    d.dismiss()
                }
            }
            .show()
    }

    /**
     * 导出全部本地浏览历史(插画/漫画/小说/用户)到 Backup 桶模板目录（默认 Download/ShaftBackups，
     * 文件名由 Backup 桶模板决定）。读库在 IO,落盘复用 [IllustDownload.downloadBackupFile]
     * (走 MediaStore + 分享同设置页备份)。
     */
    private fun exportHistory() {
        val act = activity as? BaseActivity<*> ?: return
        val appContext = act.applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val hasData = withContext(Dispatchers.IO) {
                BrowseHistoryBackup.hasAnythingToExport(appContext)
            }
            if (!hasData) {
                Common.showToast(act.getString(R.string.view_history_export_empty))
                return@launch
            }
            // fileWriter 流式导出:分页读库逐条直写文件,不再全量 toJson 成巨型
            // String(大历史库 OOM,#981);落盘链路本身也已在 IO 线程。
            val exported = AtomicInteger()
            IllustDownload.downloadBackupFile(
                act, BROWSE_HISTORY_FILE_NAME,
                Callback<File> { file ->
                    exported.set(BrowseHistoryBackup.exportToFile(appContext, file))
                },
                Callback<Uri> {
                    Common.showToast(act.getString(R.string.view_history_export_success, exported.get()))
                },
            )
        }
    }

    /** 选一个备份文件导入。对齐屏蔽记录(FragmentViewPager)的 SAF OpenDocument 姿势。 */
    private fun pickImportFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val initialUri =
                    "content://com.android.externalstorage.documents/document/primary:Download".toUri()
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri)
            }
        }
        startActivityForResult(intent, REQUEST_CODE_IMPORT_HISTORY)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CODE_IMPORT_HISTORY || resultCode != Activity.RESULT_OK) return
        val uri = data?.data
        // 抓住此刻(onActivityResult 必然 attached)的 context 做后续 getString / contentResolver。
        // IO 读文件期间宿主 Activity 可能被系统销毁(低内存/不保留活动),fragment detach 后
        // requireContext() 会抛 ISE——尤其 catch 里的报错 toast 自己再崩一次(Crashlytics)。
        val ctx = context ?: return
        if (uri == null) {
            Common.showToast(ctx.getString(R.string.view_history_import_no_file))
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val json = withContext(Dispatchers.IO) {
                    ctx.contentResolver.openInputStream(uri)
                        ?.use { it.readBytes().toString(Charsets.UTF_8) }
                }
                if (json.isNullOrBlank()) {
                    Common.showToast(ctx.getString(R.string.view_history_import_invalid))
                    return@launch
                }
                val imported = BrowseHistoryBackup.importFromJson(ctx, json)
                if (imported == 0) {
                    Common.showToast(ctx.getString(R.string.view_history_import_invalid))
                    return@launch
                }
                reloadAllTabs()
                Common.showToast(ctx.getString(R.string.view_history_import_success, imported))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "browse history import failed")
                Common.showToast(ctx.getString(R.string.view_history_import_failed, e.message ?: ""))
            }
        }
    }

    companion object {
        private const val BROWSE_HISTORY_FILE_NAME = "Shaft-BrowseHistory.json"
        private const val REQUEST_CODE_IMPORT_HISTORY = 20083
    }
}
