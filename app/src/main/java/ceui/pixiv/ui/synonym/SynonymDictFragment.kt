package ceui.pixiv.ui.synonym

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.appcompat.widget.Toolbar
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.R
import ceui.lisa.activities.BaseActivity
import ceui.lisa.activities.SearchActivity
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.database.AppDatabase
import ceui.lisa.download.IllustDownload
import ceui.lisa.interfaces.Callback
import ceui.lisa.utils.Common
import ceui.lisa.utils.Params
import ceui.pixiv.witstudio.theme.V3Palette
import ceui.pixiv.session.SessionManager
import com.blankj.utilcode.util.BarUtils
import ceui.pixiv.witstudio.dialog.WitDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import ceui.pixiv.ui.navigation.TemplateRoute

/**
 * 同义词词典管理页（issue #904），承载于 TemplateActivity「同义词词典」。
 *
 * - 树形列表：目标标签行 + 缩进同义词行（含备注），默认折叠（issue #905），
 *   点目标行右侧「▸ N 个同义词」展开/收起
 * - 搜索：同时匹配目标标签 / 同义词 / 备注，命中词高亮，保持从属缩进，命中项自动展开
 * - 长按：目标标签 / 同义词的增删改（走 [SynonymOperate] 共享 QMUI 弹窗）
 * - 单击跳转：目标标签 → 我的收藏（按同名标签过滤）；同义词 → 搜索页
 * - 顶部切换：插画/小说 + 公开/私人，决定目标标签的跳转目的地
 * - Toolbar 菜单：新建目标标签 / 导出 / 导入（合并导入，JSON 格式兼容预生成词典）
 *
 * 数据归 [SynonymDictViewModel]，本类只渲染 + 转发点击。
 */
class SynonymDictFragment : Fragment(R.layout.fragment_synonym_dict) {

    private val viewModel by viewModels<SynonymDictViewModel>()
    private lateinit var adapter: DictAdapter
    private val searchDebounce = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onDestroyView() {
        searchDebounce.removeCallbacksAndMessages(null)
        super.onDestroyView()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<Toolbar>(R.id.toolbar).apply {
            // EdgeToEdge host：状态栏 inset 走 runtime padding，不用 fitsSystemWindows。
            updatePadding(top = BarUtils.getStatusBarHeight())
            setNavigationOnClickListener { activity?.finish() }
            // 菜单：新建目标标签 / 导入内置词典 / 导出 / 导入 / 清空词典
            menu.add(getString(R.string.synonym_new_target)).setOnMenuItemClickListener {
                SynonymOperate.showCreateTargetDialog(requireContext()); true
            }
            menu.add(getString(R.string.synonym_import_builtin)).setOnMenuItemClickListener {
                confirmImportBuiltinDict(); true
            }
            menu.add(getString(R.string.synonym_export_builtin_raw)).setOnMenuItemClickListener {
                exportBuiltinRaw(); true
            }
            menu.add(getString(R.string.synonym_export)).setOnMenuItemClickListener {
                exportDict(); true
            }
            menu.add(getString(R.string.synonym_import)).setOnMenuItemClickListener {
                pickImportFile(); true
            }
            menu.add(getString(R.string.synonym_clear_all)).setOnMenuItemClickListener {
                confirmClearAll(); true
            }
        }

        val recyclerView = view.findViewById<RecyclerView>(R.id.recycler_view)
        val emptyView = view.findViewById<TextView>(R.id.empty_view)
        val countText = view.findViewById<TextView>(R.id.count_text)
        val searchInput = view.findViewById<EditText>(R.id.search_input)
        val toggleWorkType = view.findViewById<TextView>(R.id.toggle_work_type)
        val toggleStarType = view.findViewById<TextView>(R.id.toggle_star_type)

        adapter = DictAdapter()
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                // 防抖：大词典下每个字符全量过滤+重建列表会卡，停止输入 300ms 后才触发
                val query = s?.toString().orEmpty()
                searchDebounce.removeCallbacksAndMessages(null)
                searchDebounce.postDelayed({ viewModel.searchQuery.value = query }, 300)
            }
        })

        // 跳转目标切换：插画/漫画 ←→ 小说
        toggleWorkType.setOnClickListener {
            viewModel.jumpToNovel.value = !(viewModel.jumpToNovel.value ?: false)
        }
        viewModel.jumpToNovel.observe(viewLifecycleOwner) { toNovel ->
            toggleWorkType.text = getString(
                if (toNovel) R.string.synonym_jump_type_novel else R.string.synonym_jump_type_illust
            )
        }
        // 跳转目标切换：公开 ←→ 私人
        toggleStarType.setOnClickListener {
            viewModel.jumpToPrivate.value = !(viewModel.jumpToPrivate.value ?: false)
        }
        viewModel.jumpToPrivate.observe(viewLifecycleOwner) { toPrivate ->
            toggleStarType.text = getString(
                if (toPrivate) R.string.string_392 else R.string.string_391
            )
        }

        viewModel.displayItems.observe(viewLifecycleOwner) { items ->
            adapter.submit(items.orEmpty())
            val isEmpty = items.isNullOrEmpty() &&
                    viewModel.searchQuery.value.isNullOrEmpty()
            emptyView.visibility = if (isEmpty) View.VISIBLE else View.GONE
        }
        viewModel.totalCount.observe(viewLifecycleOwner) { (targets, synonyms) ->
            countText.text = getString(R.string.synonym_target_count, targets) + " · " +
                    getString(R.string.synonym_synonym_count, synonyms)
        }
    }

    // ────────────────────────────────────────────────────────────────
    // 单击跳转
    // ────────────────────────────────────────────────────────────────

    /** 目标标签 → 我的收藏页（按同名收藏标签过滤）。issue：跳转到按标签筛选的对应标签页面 */
    private fun jumpToCollection(targetName: String) {
        val toNovel = viewModel.jumpToNovel.value ?: false
        val starType = if (viewModel.jumpToPrivate.value == true) {
            Params.TYPE_PRIVATE
        } else {
            Params.TYPE_PUBLIC
        }
        val intent = Intent(requireContext(), TemplateActivity::class.java).apply {
            putExtra(TemplateActivity.EXTRA_FRAGMENT, (if (toNovel) TemplateRoute.NOVEL_BOOKMARKS else TemplateRoute.ILLUST_BOOKMARKS).key)
            putExtra(Params.USER_ID, SessionManager.loggedInUid)
            putExtra(Params.STAR_TYPE, starType)
            putExtra(Params.KEY_WORD, targetName)
        }
        startActivity(intent)
    }

    /** 同义词 → 搜索页（issue：点击原文搜索原文；备注的搜索在长按菜单里） */
    private fun jumpToSearch(keyword: String) {
        val toNovel = viewModel.jumpToNovel.value ?: false
        val intent = Intent(requireContext(), SearchActivity::class.java).apply {
            putExtra(Params.KEY_WORD, keyword)
            putExtra(Params.INDEX, if (toNovel) 1 else 0)
        }
        startActivity(intent)
    }

    // ────────────────────────────────────────────────────────────────
    // 内置词典
    // ────────────────────────────────────────────────────────────────

    /**
     * 导入内置词典（issue #904）：assets 里预生成的多语言同义词组
     * （从 34 万作品标签统计 + LLM 分类合并产出，内容按渠道差异化）。
     * 合并导入：不会覆盖用户已有的词典内容。
     */
    private fun confirmImportBuiltinDict() {
        WitDialog.MessageDialogBuilder(requireContext())
            .setTitle(getString(R.string.synonym_import_builtin))
            .setMessage(getString(R.string.synonym_import_builtin_confirm))
            .addAction(getString(R.string.cancel)) { d, _ -> d.dismiss() }
            .addAction(getString(R.string.sure)) { d, _ ->
                d.dismiss()
                doImportBuiltinDict()
            }
            .create()
            .show()
    }

    private fun doImportBuiltinDict() {
        // 协程外先捕获 application context —— IO 块执行期间 Fragment 可能已 detach，
        // 届时块内 requireContext() 会抛 IllegalStateException
        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val (targets, synonyms) = withContext(Dispatchers.IO) {
                    SynonymBuiltinDict.importNow(appContext)
                }
                Common.showToast(getString(R.string.synonym_import_success, targets, synonyms))
            } catch (e: Exception) {
                Timber.e(e, "builtin synonym dict import failed")
                Common.showToast(getString(R.string.synonym_import_failed, e.message ?: ""))
            }
        }
    }

    /** 清空词典（退路）：用户不想要内置词典/想重新开始时一键清空。清空后不会再自动导入。 */
    private fun confirmClearAll() {
        val appContext = requireContext().applicationContext
        // 同步 COUNT（毫秒级，本库允许主线程查询）：不依赖 totalCount LiveData 的发射时序，
        // 避免数据未加载完时弹出「清空 0 个目标标签」的误导文案
        val targetCount = AppDatabase.getAppDatabase(appContext).synonymDao().countTargets()
        if (targetCount == 0) {
            Common.showToast(getString(R.string.synonym_dict_empty))
            return
        }
        WitDialog.MessageDialogBuilder(requireContext())
            .setTitle(getString(R.string.synonym_clear_all))
            .setMessage(getString(R.string.synonym_clear_all_confirm, targetCount))
            .addAction(getString(R.string.cancel)) { d, _ -> d.dismiss() }
            .addAction(getString(R.string.synonym_delete)) { d, _ ->
                d.dismiss()
                viewLifecycleOwner.lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        AppDatabase.getAppDatabase(appContext).synonymDao().clearAll()
                        // 清空后保持「已导入」标记 —— 这是用户的主动选择，不要在下次启动时偷偷导回来
                        SynonymBuiltinDict.markImported()
                    }
                    Common.showToast(getString(R.string.operate_success))
                }
            }
            .create()
            .show()
    }

    // ────────────────────────────────────────────────────────────────
    // 导出 / 导入
    // ────────────────────────────────────────────────────────────────

    /**
     * issue #910：导出内置词典原件 —— 直接复制 assets 里的 synonym_dict_builtin.json（带全部备注），
     * 不是遍历 DB 输出。用户拿它本地搜索标签含义 / 找回误并进自建目标的内置目标 / 留底。
     */
    private fun exportBuiltinRaw() {
        val act = activity as? BaseActivity<*> ?: return
        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val json = try {
                withContext(Dispatchers.IO) { SynonymBuiltinDict.readRawAsset(appContext) }
            } catch (e: Exception) {
                Timber.e(e, "read builtin synonym dict asset failed")
                Common.showToast(getString(R.string.synonym_import_failed, e.message ?: ""))
                return@launch
            }
            IllustDownload.downloadBackupFile(
                act, SynonymBuiltinDict.ASSET_NAME, json,
                Callback<Uri> {
                    // act.getString:回调是异步回主线程的,fragment 可能已 detach
                    Common.showToast(act.getString(R.string.synonym_export_builtin_raw_success))
                },
            )
        }
    }

    private fun exportDict() {
        val act = activity as? BaseActivity<*> ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val (json, count) = withContext(Dispatchers.IO) {
                SynonymDictBackup.exportToJson(act)
            }
            if (count == 0) {
                Common.showToast(getString(R.string.synonym_dict_empty))
                return@launch
            }
            IllustDownload.downloadBackupFile(
                act, SynonymDictBackup.FILE_NAME, json,
                Callback<Uri> {
                    Common.showToast(act.getString(R.string.synonym_export_success, count))
                },
            )
        }
    }

    /** 选词典 JSON 导入。对齐浏览历史导入（FragmentHistoryTabs）的 SAF OpenDocument 姿势。 */
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
        startActivityForResult(intent, REQUEST_CODE_IMPORT_DICT)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CODE_IMPORT_DICT || resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // 大小防护：词典 JSON 正常不超过几 MB，选错超大文件直接整读内存会 OOM 闪退
                val fileSize = withContext(Dispatchers.IO) {
                    requireContext().contentResolver.openFileDescriptor(uri, "r")
                        ?.use { it.statSize } ?: 0L
                }
                if (fileSize > MAX_IMPORT_FILE_BYTES) {
                    Common.showToast(
                        getString(R.string.synonym_import_failed, "file too large (>10MB)")
                    )
                    return@launch
                }
                val json = withContext(Dispatchers.IO) {
                    requireContext().contentResolver.openInputStream(uri)
                        ?.use { it.readBytes().toString(Charsets.UTF_8) }
                }
                if (json.isNullOrBlank()) {
                    Common.showToast(getString(R.string.synonym_import_failed, "empty file"))
                    return@launch
                }
                val (targets, synonyms) = withContext(Dispatchers.IO) {
                    SynonymDictBackup.importFromJson(requireContext(), json)
                }
                Common.showToast(getString(R.string.synonym_import_success, targets, synonyms))
            } catch (e: Exception) {
                Timber.e(e, "synonym dict import failed")
                Common.showToast(getString(R.string.synonym_import_failed, e.message ?: ""))
            }
        }
    }

    // ────────────────────────────────────────────────────────────────
    // 树形列表 Adapter
    // ────────────────────────────────────────────────────────────────

    private inner class DictAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private var items: List<SynonymDictViewModel.DictItem> = emptyList()
        private val palette by lazy { V3Palette.from(requireContext()) }
        /** 折叠态箭头 / 备注的中性灰，从 v3_text_3 取（日夜自适配） */
        private val textMuted by lazy {
            ContextCompat.getColor(requireContext(), R.color.v3_text_3)
        }

        /** 圆角主题色强调条（目标行左侧条 / 同义词行导轨共用） */
        private fun accentPill(): GradientDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 999f
            setColor(palette.textAccent)
        }

        /** 计数徽章底：主题色淡胶囊（alpha15，比 tagCountBg 稍强，展开态卡片底上也能拎出胶囊感） */
        private fun countBadgeBg(): GradientDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 999f
            setColor(palette.alpha15)
        }

        /** 展开态卡片底色：淡主题色填充 + 主题色细描边，圆角对齐 v3_glass_surface(20dp) */
        private fun expandedCardBg(): GradientDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 20f * resources.displayMetrics.density
            setColor(palette.alpha08)
            setStroke(1, palette.alpha15)
        }

        fun submit(newItems: List<SynonymDictViewModel.DictItem>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size

        override fun getItemViewType(position: Int): Int = when (items[position]) {
            is SynonymDictViewModel.DictItem.Target -> TYPE_TARGET
            is SynonymDictViewModel.DictItem.Synonym -> TYPE_SYNONYM
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == TYPE_TARGET) {
                TargetVH(inflater.inflate(R.layout.item_synonym_target, parent, false))
            } else {
                SynonymVH(inflater.inflate(R.layout.item_synonym_tag, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is SynonymDictViewModel.DictItem.Target -> (holder as TargetVH).bind(item)
                is SynonymDictViewModel.DictItem.Synonym -> (holder as SynonymVH).bind(item)
            }
        }

        /** 搜索词高亮（issue：搜索后单词高亮） */
        private fun highlight(text: String): CharSequence {
            val query = viewModel.searchQuery.value?.trim().orEmpty()
            if (query.isEmpty()) return text
            val start = text.indexOf(query, ignoreCase = true)
            if (start < 0) return text
            return SpannableString(text).apply {
                setSpan(
                    ForegroundColorSpan(palette.textAccent),
                    start, start + query.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }

        private inner class TargetVH(view: View) : RecyclerView.ViewHolder(view) {
            private val accentBar = view.findViewById<View>(R.id.accent_bar)
            private val nameView = view.findViewById<TextView>(R.id.target_name)
            private val countView = view.findViewById<TextView>(R.id.synonym_count)
            private val chevron = view.findViewById<ImageView>(R.id.expand_chevron)
            private val toggleZone = view.findViewById<View>(R.id.toggle_zone)

            /** 上一次绑定的目标 id：同一行重绑（= 用户点了展开/收起）才给箭头转场动画，
             *  回收复用到别的行则直接定位，避免滚动时无意义旋转 */
            private var boundTargetId: Long = -1L

            fun bind(item: SynonymDictViewModel.DictItem.Target) {
                val sameRow = boundTargetId == item.entity.id
                boundTargetId = item.entity.id
                val expanded = item.expanded

                nameView.text = highlight(item.entity.name)

                // 计数徽章：主题色淡底胶囊 + 主题色文字
                countView.text = getString(R.string.synonym_synonym_count, item.synonymCount)
                countView.background = countBadgeBg()
                countView.setTextColor(palette.textAccent)

                // 左侧强调条：折叠淡、展开亮
                accentBar.background = accentPill()
                accentBar.alpha = if (expanded) 1f else 0.5f

                // 展开态：卡片淡主题色高亮；折叠态：中性玻璃面
                if (expanded) {
                    itemView.background = expandedCardBg()
                } else {
                    itemView.setBackgroundResource(R.drawable.v3_glass_surface)
                }

                // 箭头：折叠指向右「›」，展开旋转 90° 朝下「⌄」；展开态染主题色
                chevron.imageTintList =
                    ColorStateList.valueOf(if (expanded) palette.textAccent else textMuted)
                val targetRotation = if (expanded) 90f else 0f
                chevron.animate().cancel()
                if (sameRow && chevron.rotation != targetRotation) {
                    chevron.animate()
                        .rotation(targetRotation)
                        .setDuration(260)
                        .setInterpolator(OvershootInterpolator(1.4f))
                        .start()
                } else {
                    chevron.rotation = targetRotation
                }

                // issue #905：右侧计数+箭头是展开/收起热区；行主体单击跳收藏页（保持 #904 行为）
                toggleZone.setOnClickListener { viewModel.toggleExpanded(item.entity.id) }
                itemView.setOnClickListener { jumpToCollection(item.entity.name) }
                itemView.setOnLongClickListener {
                    SynonymOperate.showTargetMenu(requireContext(), item.entity, item.synonymCount)
                    true
                }
            }
        }

        private inner class SynonymVH(view: View) : RecyclerView.ViewHolder(view) {
            private val rail = view.findViewById<View>(R.id.synonym_rail)
            private val pill = view.findViewById<View>(R.id.synonym_pill)
            private val nameView = view.findViewById<TextView>(R.id.synonym_name)
            private val remarkView = view.findViewById<TextView>(R.id.synonym_remark)

            fun bind(item: SynonymDictViewModel.DictItem.Synonym) {
                nameView.text = highlight(item.entity.name)
                val remark = item.entity.remark
                if (remark.isNullOrBlank()) {
                    remarkView.text = ""
                } else {
                    remarkView.text = highlight(remark)
                }
                // 主题色细导轨，低透明把同一目标下的同义词串成一条竖线
                rail.background = accentPill()
                rail.alpha = 0.35f
                pill.setOnClickListener { jumpToSearch(item.entity.name) }
                pill.setOnLongClickListener {
                    SynonymOperate.showSynonymMenu(requireContext(), item.entity)
                    true
                }
            }
        }
    }

    companion object {
        private const val TYPE_TARGET = 0
        private const val TYPE_SYNONYM = 1
        private const val REQUEST_CODE_IMPORT_DICT = 20091
        // 词典 JSON 正常 1-3MB（数千组规模）；上限取 10MB，再大就是选错文件了，
        // 整读进内存（byte[] + String + Gson 对象树三份峰值）会 OOM
        private const val MAX_IMPORT_FILE_BYTES = 10L * 1024 * 1024
    }
}
