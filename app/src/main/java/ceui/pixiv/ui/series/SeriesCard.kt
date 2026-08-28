package ceui.pixiv.ui.series

import android.view.View
import ceui.lisa.databinding.CellSeriesV3Binding
import ceui.lisa.utils.GlideUtil
import ceui.pixiv.witstudio.theme.V3Palette
import com.bumptech.glide.RequestManager

/**
 * 系列卡([ceui.lisa.R.layout.cell_series_v3])的显示模型 —— 追更列表(pixiv `WatchlistSeries`)
 * 与系列榜(shaft-api-v2 `SeriesRankFeedItem`)各自映射成这一份,卡片本身不认识任何数据源。
 *
 * @param countText     话数 chip 文案(如「12话」)。
 * @param subtitle      chip 右侧副文案:追更 = 「更新于 2026-08-12」;榜单 = 「累计收藏 2.0M」。
 * @param subtitleAccent true 用主题强调色([V3Palette.textAccent]),false 用 v3_text_3。
 * @param actionText    右下动作按钮文案(「查看最新话 / 阅读最新话」);null 不显示按钮。
 * @param rank          榜单名次(1 起),null 不显示徽标。
 * @param maskText      非 null = 被屏蔽 / 下架的占位条目:只显示这句话,其余全部收起。
 */
data class SeriesCardModel(
    val title: String,
    val coverUrl: String?,
    val countText: String,
    val subtitle: String,
    val subtitleAccent: Boolean,
    val authorName: String,
    val authorHeadUrl: String?,
    val actionText: String? = null,
    val rank: Int? = null,
    val maskText: String? = null,
)

object SeriesCard {

    /**
     * 一次性样式:主题色相关的东西(名次徽标底 / 动作按钮 pill)在 create 阶段注入一次,
     * bind 只改文案与可见性。[V3Palette.from] 每卡算一次成本可忽略(纯颜色运算)。
     */
    fun setup(b: CellSeriesV3Binding) {
        val palette = V3Palette.from(b.root.context)
        val d = b.root.resources.displayMetrics.density
        b.rankBadge.background = palette.pillPrimary(999f * d)
        b.rankBadge.setTextColor(palette.onPrimary)
        palette.applyUnfollowBtn(b.action) // V3 secondary pill:描边 + 次级文字色
        b.subtitle.tag = palette.textAccent
    }

    fun bind(b: CellSeriesV3Binding, m: SeriesCardModel, glide: RequestManager) {
        val mask = m.maskText
        if (mask != null) {
            // 屏蔽态:保住卡高(封面 INVISIBLE 而非 GONE),其余文案清空、按钮与头像藏起来。
            b.title.text = mask
            b.countChip.visibility = View.GONE
            b.subtitle.text = ""
            b.author.text = ""
            b.userHead.visibility = View.INVISIBLE
            b.action.visibility = View.GONE
            b.rankBadge.visibility = View.GONE
            b.cover.visibility = View.INVISIBLE
            glide.clear(b.cover)
            glide.clear(b.userHead)
            return
        }
        b.title.text = m.title
        b.countChip.visibility = View.VISIBLE
        b.countChip.text = m.countText
        b.subtitle.text = m.subtitle
        b.subtitle.setTextColor(
            if (m.subtitleAccent) b.subtitle.tag as Int
            else b.subtitle.context.getColor(ceui.lisa.R.color.v3_text_3)
        )
        b.author.text = m.authorName
        b.userHead.visibility = View.VISIBLE
        b.cover.visibility = View.VISIBLE
        if (m.actionText != null) {
            b.action.visibility = View.VISIBLE
            b.action.text = m.actionText
        } else {
            b.action.visibility = View.GONE
        }
        if (m.rank != null) {
            b.rankBadge.visibility = View.VISIBLE
            b.rankBadge.text = "#${m.rank}"
        } else {
            b.rankBadge.visibility = View.GONE
        }
        // URL 为空时 GlideUtil.getUrl 回 null,Glide 走 fallback 留底色占位,不抛。
        glide.load(GlideUtil.getUrl(m.coverUrl)).into(b.cover)
        glide.load(GlideUtil.getUrl(m.authorHeadUrl)).into(b.userHead)
    }
}
