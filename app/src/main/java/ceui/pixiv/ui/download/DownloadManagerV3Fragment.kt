package ceui.pixiv.ui.download

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.view.MenuItemCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import ceui.lisa.R
import ceui.lisa.core.Manager
import ceui.lisa.utils.Common
import ceui.pixiv.ui.bulk.QueueDownloadManager
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * V3 设计哲学的下载管理页：单页面 3 tab 容器。
 *
 * Tab 0: 批量队列  · count → 持久化 download_queue 中 PENDING+DOWNLOADING
 * Tab 1: 正在下载  · count → Manager.content 内存队列大小
 * Tab 2: 已完成    · count → illust_download_table 分组后的实际卡片数
 *                            (由 [DoneListV3Fragment] 回填到 sharedVm.doneCardCount)
 *                            —— 历史上从 download_queue.SUCCESS 取,跟列表数据源
 *                            是两张表,Auto Backup 部分还原后会出现 "1944 / 空列表"。
 *
 * 数字直接追加到 tab 文字后面，避免单独占一行。
 */
class DownloadManagerV3Fragment : Fragment() {

    private val sharedVm: DownloadManagerSharedViewModel by activityViewModels()

    private var tabs: TabLayout? = null
    private var pager: ViewPager2? = null
    private var pageCallback: ViewPager2.OnPageChangeCallback? = null

    /** 「已完成」tab 的导入本地下载流程（issue #953）。 */
    private val importFlow by lazy { ImportLocalDownloadsFlow(this) }

    /**
     * SAF 选目录的 launcher。`registerForActivityResult` 必须在 fragment 到达 STARTED
     * 之前注册,所以只能挂在字段初始化上,不能等点菜单时再建。
     */
    private val importPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val uri = result.data?.data ?: return@registerForActivityResult
        importFlow.onTreePicked(uri)
    }

    private fun launchImportPicker(intent: Intent) {
        try {
            importPickerLauncher.launch(intent)
        } catch (e: Exception) {
            Common.showToast(getString(R.string.dlmgr_import_pick_failed, e.message ?: ""))
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_download_manager_v3, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val toolbar = view.findViewById<Toolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        val pager = view.findViewById<ViewPager2>(R.id.viewPager).also { this.pager = it }
        tabs = view.findViewById<TabLayout>(R.id.tabLayout)

        pager.adapter = TabsAdapter(this)
        pager.offscreenPageLimit = 2

        TabLayoutMediator(tabs!!, pager) { tab, pos ->
            tab.text = baseLabel(pos)
        }.attach()

        // toolbar 右侧 menu —— 2 个 action 按 tab 分发可见性:
        //   - 导出 (action_export):pos 0 (批量队列) / pos 2 (已完成),「正在下载」
        //     是瞬态进度页没稳定快照可导,隐藏避免误点。点击 emit 到 SharedVM,
        //     由当前可见的子 fragment collect 后跑 [DownloadExportLinks]。
        //   - 暂停/继续切换 (action_pause_toggle):pos 1 (正在下载) 专属,
        //     icon + title 由 QueueDownloadManager.pausedFlow 动态切换。原本是
        //     Active fragment 卡片底部 btn1/btn2,搬上来后 statusHeader (btn3) 占
        //     一半行宽,「正在 N · 等待 M · 暂停 K · 失败 L · 1.2 MB/s」不再换行。
        //     pause/resume 是 Manager + QueueDownloadManager 全局 singleton 动作,
        //     host 直接调,不绕 SharedVM (Active fragment 不需要参与)。
        toolbar.inflateMenu(R.menu.menu_download_manager)
        val exportItem = toolbar.menu.findItem(R.id.action_export)
        val pauseToggleItem = toolbar.menu.findItem(R.id.action_pause_toggle)
        val searchItem = toolbar.menu.findItem(R.id.action_search)
        val importItem = toolbar.menu.findItem(R.id.action_import)
        setupDoneSearch(searchItem)
        // 必须清 iconTintList — Toolbar 默认会拿 colorControlNormal 强行覆盖
        // menu icon 的颜色,把 vector 自带的 fillColor=v3_text_1 压成淡色
        // (浅色主题下跟白底融合看不见)。同 BulkSelectV3.refreshSelectToggleIcon
        // 末尾对 select_toggle 做的处理。setIcon() 后 iconTintList 仍是 null
        // (MenuItem property 跟 drawable 解耦),不需要反复重设。
        // 走 MenuItemCompat —— MenuItem.setIconTintList 是 API 26 才进 framework
        // 的接口方法,直接 item.iconTintList= 在 API 24/25 会 NoSuchMethodError;
        // compat 走 SupportMenuItem (AppCompat Toolbar 的 menu item 都是) 全版本安全。
        exportItem?.let { MenuItemCompat.setIconTintList(it, null) }
        pauseToggleItem?.let { MenuItemCompat.setIconTintList(it, null) }
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_export -> {
                    sharedVm.requestExport(pager.currentItem)
                    true
                }
                R.id.action_pause_toggle -> {
                    // 读当前 pausedFlow 决定方向:暂停态 → 继续;运行态 → 暂停。
                    // 不存第二份 UI 状态,跟 host 的 icon/title 联动用同一个 source of truth。
                    if (QueueDownloadManager.isPaused()) {
                        Manager.get().startAll()
                        QueueDownloadManager.resume()
                    } else {
                        Manager.get().stopAll()
                        QueueDownloadManager.pause()
                    }
                    true
                }
                // issue #953:扫描用户授权的目录，把旧版下载但新版认不出来的图片
                // 补成下载记录。全程只读所选目录、不改不删文件,写库也只增不覆盖。
                R.id.action_import -> {
                    importFlow.start { intent -> launchImportPicker(intent) }
                    true
                }
                else -> false
            }
        }
        fun applyMenuVisibility(pos: Int) {
            exportItem?.isVisible = pos == 0 || pos == 2
            pauseToggleItem?.isVisible = pos == 1
            searchItem?.isVisible = pos == 2
            importItem?.isVisible = pos == 2
            // tab 切走时强制收起搜索框 + 清空 query，避免被遗留的 isIconified=false
            // 在其它 tab 出现时夹带一份过滤态。
            if (pos != 2 && searchItem?.isActionViewExpanded == true) {
                searchItem.collapseActionView()
            }
        }
        applyMenuVisibility(pager.currentItem)
        pageCallback = object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                applyMenuVisibility(position)
            }
        }.also { pager.registerOnPageChangeCallback(it) }

        // pausedFlow → 切 pause_toggle 的 icon + title。StateFlow 自带初始值,
        // 第一次 collect 就立刻把按钮渲染成当前真实状态 (避免冷启 icon 跟
        // 实际暂停态错位)。distinctUntilChanged 隐含 (StateFlow 不会重发同值)。
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                QueueDownloadManager.pausedFlow.collect { paused ->
                    if (paused) {
                        pauseToggleItem?.setIcon(R.drawable.ic_v3_resume_all_24)
                        pauseToggleItem?.setTitle(R.string.dlmgr_active_action_resume_all)
                    } else {
                        pauseToggleItem?.setIcon(R.drawable.ic_v3_pause_all_24)
                        pauseToggleItem?.setTitle(R.string.dlmgr_active_action_pause_all)
                    }
                }
            }
        }

        // 实时刷新 tab 文案末尾的数字
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                sharedVm.snapshots().collect { s ->
                    setTabCount(0, s.queuePending + s.queueDownloading)
                    setTabCount(1, s.activeCount)
                }
            }
        }
        // tab 2 数字独立 collect:数据源是 DoneListV3Fragment 回填的分组卡片数,
        // 跟列表用同一份数据,杜绝 "1944 / 空列表" 那种错位。
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                sharedVm.doneCardCount.collect { setTabCount(2, it) }
            }
        }
    }

    override fun onDestroyView() {
        pageCallback?.let { pager?.unregisterOnPageChangeCallback(it) }
        pageCallback = null
        pager = null
        tabs = null
        searchDebounceJob?.cancel()
        searchDebounceJob = null
        super.onDestroyView()
    }

    /** Debounce 句柄 — 200ms 跟 history 页保持一致体感。 */
    private var searchDebounceJob: Job? = null

    /**
     * 接 Done tab 的 SearchView：expand 时把 query 设成空串（vs null）告诉
     * DoneListV3Fragment "已进入搜索模式但未输入"，collapse 时还原为 null。
     */
    private fun setupDoneSearch(item: MenuItem?) {
        if (item == null) return
        val sv = MenuItemCompat.getActionView(item) as? SearchView ?: return
        sv.queryHint = getString(R.string.dlmgr_done_search_hint)
        sv.maxWidth = Int.MAX_VALUE
        // toolbar 背景 = @color/v3_bg（day 浅 / night 深 自适应），SearchView 默认
        // text/hint 是 ?attr/textColorPrimary/Hint，没 themeOverlay 时多半解出来
        // 是 day 主题的黑色 — dark mode 下叠在深 v3_bg 上看不清。改用 v3_text_1
        // / v3_text_3（跟 toolbar 标题同一套自适应色）。
        sv.findViewById<EditText>(androidx.appcompat.R.id.search_src_text)?.apply {
            setTextColor(ContextCompat.getColor(requireContext(), R.color.v3_text_1))
            setHintTextColor(ContextCompat.getColor(requireContext(), R.color.v3_text_3))
        }

        // 展开搜索框后,返回手势优先收起搜索框,不直接退出。
        val backCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                if (item.isActionViewExpanded) item.collapseActionView()
                isEnabled = false
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)

        MenuItemCompat.setOnActionExpandListener(item, object : MenuItemCompat.OnActionExpandListener {
            override fun onMenuItemActionExpand(menuItem: MenuItem): Boolean {
                sharedVm.setDoneSearchQuery("")
                backCallback.isEnabled = true
                return true
            }

            override fun onMenuItemActionCollapse(menuItem: MenuItem): Boolean {
                searchDebounceJob?.cancel()
                sharedVm.setDoneSearchQuery(null)
                backCallback.isEnabled = false
                return true
            }
        })

        sv.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                sharedVm.setDoneSearchQuery(query.orEmpty().trim())
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                searchDebounceJob?.cancel()
                searchDebounceJob = viewLifecycleOwner.lifecycleScope.launch {
                    delay(200)
                    sharedVm.setDoneSearchQuery(newText.orEmpty().trim())
                }
                return true
            }
        })
    }

    private fun baseLabel(pos: Int): String = when (pos) {
        0 -> getString(R.string.dlmgr_tab_queue)
        1 -> getString(R.string.dlmgr_tab_active)
        2 -> getString(R.string.dlmgr_tab_done)
        else -> ""
    }

    private fun setTabCount(pos: Int, count: Int) {
        val t = tabs?.getTabAt(pos) ?: return
        val base = baseLabel(pos)
        t.text = if (count > 0) "$base  $count" else base
    }

    private class TabsAdapter(host: Fragment) : FragmentStateAdapter(host) {
        override fun getItemCount(): Int = 3
        override fun createFragment(position: Int): Fragment = when (position) {
            0 -> QueueListV3Fragment()
            1 -> ActiveListV3Fragment()
            2 -> DoneListV3Fragment()
            else -> error("unreachable: $position")
        }
    }
}
