package ceui.pixiv.plaza.ui

import android.content.Context
import android.util.AttributeSet
import ceui.pixiv.feeds.FeedSkeletonView
import ceui.pixiv.feeds.SkeletonBlock

/**
 * First-screen skeleton for the plaza feed, on the shared [FeedSkeletonView] shimmer engine.
 *
 * Draws the *contents* of stacked edge-to-edge post rows at the real row geometry (16dp side
 * padding, 14dp vertical padding, 48dp avatar, two body lines, a 16:9 image on every other row,
 * a row of 32dp reaction pills) so the real rows replace it almost in place. Fills whatever
 * height it is given.
 */
internal class PlazaSkeletonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FeedSkeletonView(context, attrs) {

    private val gutter = 0f
    private val pad = 16f * density
    private val cardGap = 0f
    private val avatar = 48f * density
    private val avatarGap = 12f * density
    private val nameW = 120f * density
    private val nameH = 14f * density
    private val timeW = 72f * density
    private val timeH = 11f * density
    private val lineH = 14f * density
    private val lineGap = 8f * density
    private val pillH = 32f * density
    private val pillW = 56f * density
    private val pillGap = 8f * density
    private val corner = 4f * density
    private val imageCorner = 16f * density

    override fun buildBlocks(w: Float, h: Float, out: MutableList<SkeletonBlock>) {
        val left = gutter + pad
        val right = w - gutter - pad
        val contentW = right - left
        if (contentW <= 0f) return
        var top = cardGap
        var index = 0
        while (top < h) {
            var y = top + pad
            out.add(block(left, y, avatar, avatar, avatar / 2f))
            out.add(block(left + avatar + avatarGap, y + 8f * density, nameW, nameH, corner))
            out.add(block(left + avatar + avatarGap, y + avatar - timeH - 8f * density, timeW, timeH, corner))
            y += avatar + 14f * density
            out.add(block(left, y, contentW, lineH, corner))
            y += lineH + lineGap
            out.add(block(left, y, contentW * 0.62f, lineH, corner))
            y += lineH + 12f * density
            if (index % 2 == 0) {
                val imageH = (contentW * 9f / 16f).coerceAtMost(220f * density)
                out.add(block(left, y, contentW, imageH, imageCorner))
                y += imageH + 12f * density
            }
            var x = left
            repeat(3) {
                out.add(block(x, y, pillW, pillH, pillH / 2f))
                x += pillW + pillGap
            }
            y += pillH + pad
            top = y + cardGap
            index++
        }
    }
}
