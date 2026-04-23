package ceui.pixiv.ui.novel

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.activities.UActivity
import ceui.lisa.activities.VActivity
import ceui.lisa.core.Container
import ceui.lisa.core.PageData
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.lisa.databinding.ItemBigReadButtonBinding
import ceui.lisa.models.IllustsBean
import ceui.lisa.utils.Params
import ceui.loxia.Client
import ceui.loxia.Novel
import ceui.loxia.ObjectPool
import ceui.pixiv.ui.common.FitsSystemWindowFragment
import ceui.pixiv.ui.common.ListMode
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.constructVM
import ceui.pixiv.ui.common.setUpRefreshState
import ceui.pixiv.ui.common.shareNovel
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.ui.novel.reader.NovelTextCache
import ceui.pixiv.ui.novel.reader.export.ExportFormat
import ceui.pixiv.ui.novel.reader.export.ExportResult
import ceui.pixiv.ui.novel.reader.export.NovelExportManager
import ceui.pixiv.ui.novel.reader.paginate.ContentParser
import ceui.pixiv.ui.novel.reader.ui.ExportSheet
import ceui.pixiv.utils.setOnClick
import com.hjq.toast.ToastUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID


class NovelTextFragment : PixivFragment(R.layout.fragment_pixiv_list), FitsSystemWindowFragment,
    NovelSeriesActionReceiver, NovelActionsReceiver {

    private val binding by viewBinding(FragmentPixivListBinding::bind)
    private val novelId: Long by lazy { arguments?.getLong(Params.NOVEL_ID, 0L) ?: 0L }
    private val textModel by constructVM({ novelId }) { id ->
        NovelTextViewModel(id)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpRefreshState(binding, textModel, ListMode.VERTICAL)
        binding.toolbarLayout.root.visibility = View.GONE
        // 用户反馈：详情页背景图（模糊封面）干扰前景文字。改 v3_bg（白天/夜间自动适配）。
        binding.pageBackground.setBackgroundColor(
            androidx.core.content.ContextCompat.getColor(requireContext(), R.color.v3_bg),
        )
        // setUpRefreshState 会因为 FitsSystemWindowFragment 默认打开顶部黑色渐变，
        // 在纯色背景下变成顶上一道脏黑带。详情页不需要，关掉。
        binding.topShadow.isVisible = false

        binding.listView.clipToPadding = false

        val bottomView = ItemBigReadButtonBinding.inflate(layoutInflater)
        binding.bottomCovered.isVisible = true
        binding.bottomCovered.addView(bottomView.root)
        bottomView.btnRead.setOnClick {
            val ctx = requireContext()
            val intent = Intent(ctx, TemplateActivity::class.java).apply {
                putExtra(TemplateActivity.EXTRA_FRAGMENT, "小说正文")
                putExtra(Params.NOVEL_ID, novelId)
            }
            ctx.startActivity(intent)
        }

        // 任务 #1：移除顶部右上角的评论/分享/下载/复制链接悬浮按钮，
        // 功能全部下沉到列表内的 NovelActionsHolder（任务 #4）。
        // Edge-to-edge safe area: TemplateActivity draws behind the status bar
        // / display cutout, so both the top of the list need to clear systemBars.top.
        // Remove the toolbar's insets listener first — setUpToolbar sets one
        // on binding.toolbarLayout.root that calls content.updatePadding(0,0,0,bottom),
        // resetting our top padding to 0. The toolbar is GONE anyway.
        ViewCompat.setOnApplyWindowInsetsListener(binding.toolbarLayout.root, null)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.listView.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)

        ObjectPool.get<Novel>(novelId).observe(viewLifecycleOwner) { novel ->
            if (novel != null) {
                runOnceWithinFragmentLifecycle("visit-novel-${novelId}") {
                    val bean = Shaft.sGson.fromJson(Shaft.sGson.toJson(novel), ceui.lisa.models.NovelBean::class.java)
                    ceui.lisa.utils.PixivOperate.insertNovelViewHistory(bean)
                }
            }
        }
    }

    // ─── NovelActionsReceiver 任务 #4 ──────────────────────────────────────

    override fun onClickShareNovel(sender: View, novelId: Long) {
        // 分享菜单里已经包含"复制链接"，所以不再需要独立的复制链接按钮。
        val novel = ObjectPool.get<Novel>(novelId).value
        if (novel != null) {
            shareNovel(novel)
        } else {
            // 冷启动直接点下来时 LiveData 可能还没到位，兜底再拉一次。
            viewLifecycleOwner.lifecycleScope.launch {
                val fresh = runCatching { Client.appApi.getNovel(novelId).novel }
                    .getOrNull()?.also { ObjectPool.update(it) }
                if (fresh != null) shareNovel(fresh)
            }
        }
    }

    override fun onClickNovelComments(sender: View, novelId: Long) {
        val intent = Intent(requireContext(), TemplateActivity::class.java).apply {
            putExtra(TemplateActivity.EXTRA_FRAGMENT, "相关评论")
            putExtra(Params.NOVEL_ID, novelId.toInt())
        }
        startActivity(intent)
    }

    override fun onClickDownloadNovel(sender: View, novelId: Long) {
        // 单击：使用设置里预设的默认导出格式静默下载；若未设置则弹出格式选择。
        val defaultFormat = Shaft.sSettings.defaultNovelExportFormat
        val format = ExportFormat.entries.firstOrNull { it.name == defaultFormat }
        if (format != null) {
            executeExport(format)
        } else {
            showExportSheet()
        }
    }

    override fun onLongClickDownloadNovel(sender: View, novelId: Long) {
        // 长按：不看默认设置，始终弹格式选择，让用户临时选一次。
        showExportSheet()
    }

    /**
     * 详情页外置的导出入口（issue #842 提案）——避免用户为了导出 TXT/MD/EPUB/PDF
     * 还得先进入正文页点「更多→导出」。
     *
     * 数据来源优先级：
     * 1. [NovelTextCache]（[NovelTextViewModel] 初始化时会后台预热）
     * 2. 未命中就现拉 novel 接口 + novel_text + tokenize 一次
     */
    private fun showExportSheet() {
        ExportSheet().apply {
            configure { format -> executeExport(format) }
        }.show(childFragmentManager, ExportSheet.TAG)
    }

    private fun executeExport(format: ExportFormat) {
        val appContext = requireContext().applicationContext
        ToastUtils.show(getString(R.string.msg_export_start, getString(format.displayNameResId)))
        viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching {
                val novel = ObjectPool.get<Novel>(novelId).value
                    ?: Client.appApi.getNovel(novelId).novel?.also { ObjectPool.update(it) }
                val cached = NovelTextCache.get(novelId)
                val web = cached?.webNovel ?: withContext(Dispatchers.IO) {
                    val html = Client.appApi.getNovelText(novelId).string()
                    ceui.lisa.fragments.WebNovelParser.parsePixivObject(html)?.novel
                } ?: error("invalid web novel")
                val tokens = cached?.tokens ?: withContext(Dispatchers.IO) {
                    ContentParser.tokenize(web)
                }
                if (cached == null) {
                    NovelTextCache.put(novelId, NovelTextCache.Entry(web, tokens))
                }
                NovelExportManager.export(
                    context = appContext,
                    format = format,
                    novel = novel,
                    webNovel = web,
                    tokens = tokens,
                )
            }.getOrElse { ExportResult.Failure(it.message ?: "导出失败", it) }
            when (result) {
                is ExportResult.Success -> ToastUtils.show(appContext.getString(R.string.msg_export_success, result.fileName))
                is ExportResult.Failure -> ToastUtils.show(appContext.getString(R.string.msg_export_fail, result.message))
            }
        }
    }

    // Classic 分支没有 NavController，把所有 PixivFragment 里走 pushFragment 的
    // action receiver 方法一律改走 Intent → 对应 Activity。
    override fun onClickUser(id: Long) {
        val intent = Intent(requireContext(), UActivity::class.java).apply {
            putExtra(Params.USER_ID, id.toInt())
        }
        startActivity(intent)
    }

    override fun onClickNovel(novelId: Long) {
        val intent = Intent(requireContext(), TemplateActivity::class.java).apply {
            putExtra(TemplateActivity.EXTRA_FRAGMENT, "小说详情")
            putExtra(Params.NOVEL_ID, novelId)
        }
        startActivity(intent)
    }

    override fun onClickIllust(illustId: Long) {
        viewLifecycleOwner.lifecycleScope.launch {
            val illust = runCatching { Client.appApi.getIllust(illustId).illust }
                .getOrNull() ?: return@launch
            val gson = Shaft.sGson
            val bean = gson.fromJson(gson.toJson(illust), IllustsBean::class.java)
            val uuid = UUID.randomUUID().toString()
            val pageData = PageData(uuid, null, listOf(bean))
            Container.get().addPageToMap(pageData)
            val intent = Intent(requireContext(), VActivity::class.java).apply {
                putExtra(Params.POSITION, 0)
                putExtra(Params.PAGE_UUID, uuid)
            }
            startActivity(intent)
        }
    }

    companion object {
        fun newInstance(novelId: Long): NovelTextFragment = NovelTextFragment().apply {
            arguments = Bundle().apply { putLong(Params.NOVEL_ID, novelId) }
        }
    }
}
