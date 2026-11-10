package ceui.pixiv.ui.detail

import android.os.Parcelable
import ceui.pixiv.feeds.FeedItem
import kotlinx.parcelize.Parcelize

/**
 * 视图重建（旋屏、折叠屏开合、分屏改尺寸）前「用户正在看哪」，经 savedInstanceState 带到新视图。
 *
 * 作品图有两种摆法：列表里逐页往下排（手机；竖屏平板开了「多图自动展开」），或钉在
 * [ArtworkTabletStage] 里翻页、列表只剩信息区块。VM 跨重建存活，
 * [ArtworkV3Fragment] 的 reconcilePageItems 会按新排版增删页条目；而 RecyclerView 自己按**下标**
 * 恢复滚动位置，页条目一增一删，下标就错开页数那么多格 —— 从第 6 页转到横屏，信息栏落在
 * 详情面板，再转回来落在第 4 页。所以这里记「看的是什么」，不记「第几格」：
 *
 * - [page]：正在看的作品页（0 基）。舞台排版恒有；列表排版只在页条目压在最上面时有，
 *   滑到信息区为 -1。
 * - [infoKey] / [infoOffset]：正在读的信息区块（[readingKey] + 顶边相对列表 paddingTop 的偏移，
 *   与 feeds 显隐锚点同一口径）。列表排版滑到信息区时有；舞台排版只在信息栏被滑离顶部时有
 *   —— 停在顶部说明在看图。
 * - [staged]：记下时作品图在不在舞台里。新视图同一种摆法时列表形状没变，RecyclerView 按下标
 *   恢复本来就对，不接管（见 [landingIn]）。
 */
@Parcelize
internal data class ArtworkReadingAnchor(
    val staged: Boolean,
    val page: Int,
    val infoKey: String?,
    val infoOffset: Int,
) : Parcelable {

    /**
     * 新视图首批数据提交后列表该落在哪。
     *
     * @param stagedNow 新视图作品图在不在舞台里
     * @param indexOfKey 新列表里某个 [readingKey] 的下标，没有给 -1
     */
    fun landingIn(stagedNow: Boolean, indexOfKey: (String) -> Int): ArtworkListLanding {
        if (stagedNow == staged) return ArtworkListLanding.Keep
        val info = infoKey?.let(indexOfKey) ?: -1
        return when {
            info >= 0 -> ArtworkListLanding.Item(info, infoOffset)
            // 舞台 → 列表：信息栏停在顶部，用户在看图，列表落到舞台上那一页
            !stagedNow && page > 0 -> ArtworkListLanding.Page(page)
            // 列表 → 舞台：页交给舞台（ArtworkTabletStage 的 initialPage），信息栏从标题开始。
            // 记下的区块新列表里没有（如还没懒载回来的相关作品）也退回顶部
            else -> ArtworkListLanding.Item(0, 0)
        }
    }
}

/** [ArtworkReadingAnchor.landingIn] 的结果。 */
internal sealed interface ArtworkListLanding {
    /** 摆法没变：交给 RecyclerView 按下标恢复。 */
    data object Keep : ArtworkListLanding

    /** 第 [index] 条顶边落在列表 paddingTop 下 [offset] 处。 */
    data class Item(val index: Int, val offset: Int) : ArtworkListLanding

    /** 跳到作品第 [page] 页；还被折着就先展开（ArtworkV3Fragment.jumpToPage）。 */
    data class Page(val page: Int) : ArtworkListLanding
}

/** 跨重建认条目的键：类型 + feedKey，与 feeds 内部的条目身份同一口径（那个是 internal 的）。 */
internal val FeedItem.readingKey: String
    get() = "${javaClass.name}#$feedKey"
