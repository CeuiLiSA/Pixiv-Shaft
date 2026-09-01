package ceui.pixiv.ui.common

import ceui.pixiv.api.model.Illust
import ceui.pixiv.widgets.ProgressIndicator

/**
 * 插画卡片点击契约。原本内联在已删除的 IllustCardHolder.kt(CommonAdapter/ListItemHolder 框架),
 * 但 feeds 框架下的 IllustSeries / NovelText 等页面仍实现这些接口做点击/收藏/跳转,
 * 故随 holder 系统清理搬到独立文件(包名保持 ceui.pixiv.ui.common,现有 import 不变)。
 */
interface IllustCardActionReceiver {
    fun onClickIllustCard(illust: Illust)
    fun onClickBookmarkIllust(sender: ProgressIndicator, illustId: Long)
    fun visitIllustById(illustId: Long)
}

interface IllustIdActionReceiver {
    fun onClickIllust(illustId: Long)
}
