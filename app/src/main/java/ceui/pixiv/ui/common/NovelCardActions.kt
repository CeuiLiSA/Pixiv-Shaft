package ceui.pixiv.ui.common

import ceui.pixiv.widgets.ProgressIndicator

/**
 * 小说卡片点击 / 多选契约。原本内联在已删除的 NovelCardHolder.kt(CommonAdapter/ListItemHolder 框架),
 * feeds 框架下的 NovelSeries / NovelText 等页面仍实现这些接口,故随 holder 系统清理搬到独立文件
 * (包名保持 ceui.pixiv.ui.common,现有 import 不变)。
 */
interface NovelActionReceiver {
    fun onClickNovel(novelId: Long)
    fun onClickBookmarkNovel(sender: ProgressIndicator, novelId: Long)
    fun visitNovelById(novelId: Long)
}

interface NovelMultiSelectReceiver {
    fun isNovelMultiSelectMode(): Boolean
    fun isNovelSelected(novelId: Long): Boolean
    fun onToggleNovelSelection(novelId: Long)
}
