package ceui.pixiv.ui.comic.reader

import androidx.fragment.app.activityViewModels
import androidx.lifecycle.ViewModel
import ceui.lisa.utils.GlideUrlChild
import ceui.pixiv.widgets.PageThumbsSheet

/**
 * Activity-scoped ViewModel：宿主 Fragment 进入 reader 时把当前 illust 的页面 URL 列表
 * 写进来；ThumbsSheet 通过 [activityViewModels] 拉取，避免把 200 条 URL 走 Bundle 引发
 * TransactionTooLargeException（与 #820
 * [ceui.lisa.fragments.RecmdUserHandoff] 同类问题）。
 */
class ComicReaderPagesProvider : ViewModel() {
    var pages: List<ComicReaderV3ViewModel.ComicPage> = emptyList()
    var currentIndex: Int = 0
    var title: String = ""
}

class ComicThumbsSheet : PageThumbsSheet() {

    private val provider by activityViewModels<ComicReaderPagesProvider>()
    private val eventBus by activityViewModels<ComicReaderEventBus>()

    // issue #865: 走 GlideUrlChild 统一带 Pixiv 头 + 图片域名重写(Pixiv/pixiv.cat/自定义),
    // 不再手搓裸 GlideUrl 绕过加速代理。
    override fun thumbModels(): List<Any> = provider.pages.map { GlideUrlChild(it.previewUrl) }

    override fun currentIndex(): Int = provider.currentIndex

    override fun sheetTitle(): String = provider.title

    override fun onPagePicked(index: Int) {
        eventBus.post(ComicReaderEventBus.Event.JumpToPage(index))
    }

    companion object { const val TAG = "ComicThumbsSheet" }
}
