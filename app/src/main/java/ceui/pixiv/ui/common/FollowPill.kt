package ceui.pixiv.ui.common

import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.widget.TextView
import ceui.lisa.R
import ceui.lisa.activities.followedLabelRes
import ceui.pixiv.witstudio.theme.V3Palette
import ceui.pixiv.witstudio.theme.dp
import ceui.pixiv.witstudio.theme.hairlinePx
import ceui.pixiv.witstudio.theme.ripple
import ceui.pixiv.witstudio.theme.shape

/**
 * 关注胶囊，同画师主页：未关注 = 主题实色 +「关注」；已关注 = 浅色胶囊 +「已关注 / 悄悄关注中」
 * （可见性见 [followedLabelRes]）。用户卡列表和小说详情的作者行共用这一份，两处同一套 V3Palette 口径。
 *
 * 底只在关注态翻转时换 —— 列表里这是 fling 帧路径，复用的格子大多和上一张卡同态，不必每次新建 drawable。
 * 文案每次都刷：只改了可见性（公开 ↔ 悄悄）时关注态没变，但字要变。
 */
fun TextView.renderFollowPill(palette: V3Palette, userId: Long, followed: Boolean) {
    text = context.getString(if (followed) followedLabelRes(userId) else R.string.follow)
    if (getTag(R.id.user_card_follow_state) == followed) return
    setTag(R.id.user_card_follow_state, followed)
    background = followPillBackground(palette, followed)
    setTextColor(if (followed) palette.textSecondary else palette.onPrimary)
}

/**
 * 48dp 热区里画 36dp 可见胶囊：上下各 6dp 透明沿。⚠️ InsetDrawable 默认把 inset 报成 View
 * 内边距，会覆盖布局里的 padding（标签流踩过，见 `WitTagFlowView`），所以把 getPadding 置空。
 */
private fun TextView.followPillBackground(palette: V3Palette, followed: Boolean): Drawable {
    val ctx = context
    val edge = ctx.dp(6)
    val fill = if (followed) palette.pillSecondary(999f, ctx.hairlinePx()) else palette.pillPrimary(999f)
    return ctx.ripple(insetPill(fill, edge), insetPill(shape(999f, Color.WHITE), edge))
}

private fun insetPill(pill: Drawable, edge: Int): Drawable =
    object : InsetDrawable(pill, 0, edge, 0, edge) {
        override fun getPadding(padding: Rect): Boolean {
            padding.setEmpty()
            return false
        }
    }
