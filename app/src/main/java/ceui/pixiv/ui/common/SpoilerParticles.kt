package ceui.pixiv.ui.common

import androidx.core.view.isVisible
import ceui.pixiv.widget.SpoilerParticleView

/** 粒子层淡入淡出时长：跟着 Glide 的 crossfade（默认 300ms）走，图和粒子一起变。 */
private const val SPOILER_FADE_IN_MS = 260L
private const val SPOILER_FADE_OUT_MS = 200L

/**
 * 粒子层的显隐。插画卡（[staggerIllustRenderer]）和小说卡（[NovelFeedFragment]）共用，
 * 所以放在中立文件里 —— 搁在某一边的 renderer 里，改那张卡的人不会知道自己在动另一张。
 *
 * [animate] 只在「用户刚点了屏蔽/取消屏蔽」时为 true——全量绑定（含复用、滚动回来）一律硬切，
 * 否则每张滑上屏的卡都要白白播一遍淡入。
 *
 * 淡入淡出用 View 的 alpha 而不是 SpoilerEffect2 的 alpha 形参：粒子纹理是所有卡片共享的一张
 * GPU 输出，形参 alpha 是**绘制时**参数，得在每帧 onDraw 里传新值（要么自己起 ValueAnimator
 * 推 invalidate，要么让 view 每帧重绘）；View.alpha 由渲染管线直接处理，一行搞定且不多画一帧。
 *
 * 注意：本函数只管「这一刻画不画」。holder 被 detach 后必须停逐帧模拟、re-attach 后必须开回来，
 * 那是 renderer 的 attach / detach 钩子的事，两个钩子必须成对出现 —— RecyclerView 的
 * mCachedViews 里的 holder 只 detach、不重新 onBind，少了 attach 那半边粒子就再也不动了。
 */
internal fun renderSpoilerParticles(
    view: SpoilerParticleView,
    show: Boolean,
    animate: Boolean,
) {
    view.animate().cancel()
    if (show) {
        // 已经稳定显示中就别再动它（局部重绑可能在粒子本来就常驻的首页推荐上触发）
        if (view.isVisible && view.alpha == 1f) {
            view.setParticleAnimationRunning(true)
            return
        }
        view.alpha = if (animate) 0f else 1f
        view.isVisible = true
        view.setParticleAnimationRunning(true)
        if (animate) {
            view.animate().alpha(1f).setDuration(SPOILER_FADE_IN_MS).start()
        }
    } else {
        if (animate && view.isVisible) {
            view.animate()
                .alpha(0f)
                .setDuration(SPOILER_FADE_OUT_MS)
                .withEndAction {
                    view.isVisible = false
                    view.alpha = 1f
                    view.setParticleAnimationRunning(false)
                }
                .start()
        } else {
            view.isVisible = false
            view.alpha = 1f
            view.setParticleAnimationRunning(false)
        }
    }
}
