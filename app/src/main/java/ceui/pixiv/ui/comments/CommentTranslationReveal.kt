package ceui.pixiv.ui.comments

import android.animation.Animator
import android.animation.ValueAnimator
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.TextView
import androidx.core.animation.doOnEnd
import androidx.core.view.isVisible
import androidx.core.view.marginEnd
import androidx.core.view.marginStart
import androidx.core.view.updateLayoutParams
import ceui.lisa.R

/**
 * 评论 cell 内嵌译文块(hairline + 译文)的绑定与展开 / 收起动画。
 *
 * 译文本体住在不可变的 [CommentFeedItem.translations] 里,经 mutateItems 原地重绑到 cell,
 * 这里只管「怎么画」:
 * - 刚译完那一次 `reveal = true`:块高度从 0 长到自然高度、同时淡入;之后滚出滚回的重绑
 *   `reveal = false` 直接按终态摆好——不重播、不重复测量;
 * - 动画只改这一个块的 `layoutParams.height`,cell 跟着自然长高;每帧一次 requestLayout,
 *   260ms 十几帧,开销与列表长度无关;
 * - 动画实例挂在块的 tag 上([R.id.comment_translation_animator]),回收 / 换绑先取消,
 *   不会有动画跑在已被复用的 view 上。
 */
internal fun bindCommentTranslation(
    block: View,
    textView: TextView,
    translation: String?,
    reveal: Boolean,
) {
    if (translation == null) {
        cancelCommentTranslationAnimation(block)
        block.isVisible = false
        return
    }
    val running = block.getTag(R.id.comment_translation_animator) as? Animator
    if (running?.isRunning == true && block.isVisible && textView.text.toString() == translation) {
        // 展开 / 收起进行中又被同内容重绑(别的评论刚译完触发了 mutateItems):让它跑完
        return
    }
    cancelCommentTranslationAnimation(block)
    textView.text = translation
    val width = revealWidthOf(block)
    if (!reveal || width <= 0) {
        block.isVisible = true
        return
    }
    // 先把高度钉在 0 再置 VISIBLE:RecyclerView 这一轮 layout 量出来的 cell 高度不含译文块,
    // 下面的条目不会被 ItemAnimator 一次性挤下去,而是跟着我们的动画逐帧下移
    block.alpha = 0f
    block.updateLayoutParams { height = 0 }
    block.isVisible = true
    block.measure(
        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
    )
    val target = block.measuredHeight
    if (target <= 0) {
        finishStatic(block)
        return
    }
    startHeightAnimation(block, from = 0, to = target, fadeIn = true) { finishStatic(block) }
}

/**
 * 「隐藏译文」:先把可见的译文块收起来,收完再 [onCollapsed](调用方在这里才把译文从
 * feed item 里摘掉 → 重绑成 gone)。块本来就不可见时直接回调。中途被回收 / 取消也会回调,
 * 保证数据侧的摘除不会丢。
 */
internal fun collapseCommentTranslation(block: View, onCollapsed: () -> Unit) {
    if (!block.isVisible || block.height <= 0) {
        onCollapsed()
        return
    }
    cancelCommentTranslationAnimation(block)
    startHeightAnimation(block, from = block.height, to = 0, fadeIn = false) {
        block.isVisible = false
        finishStatic(block)
        onCollapsed()
    }
}

/** 回收 / 换绑前调用:取消进行中的动画(其 end 回调会把 layoutParams / alpha 复位)。 */
internal fun cancelCommentTranslationAnimation(block: View) {
    (block.getTag(R.id.comment_translation_animator) as? Animator)?.cancel()
}

private fun startHeightAnimation(
    block: View,
    from: Int,
    to: Int,
    fadeIn: Boolean,
    onEnd: () -> Unit,
) {
    val animator = ValueAnimator.ofInt(from, to).apply {
        duration = REVEAL_DURATION_MS
        interpolator = REVEAL_INTERPOLATOR
        addUpdateListener { anim ->
            block.updateLayoutParams { height = anim.animatedValue as Int }
            val fraction = anim.animatedFraction
            block.alpha = if (fadeIn) fraction else 1f - fraction
        }
        doOnEnd {
            block.setTag(R.id.comment_translation_animator, null)
            onEnd()
        }
    }
    block.setTag(R.id.comment_translation_animator, animator)
    animator.start()
}

/** 终态:高度交还 wrap_content、alpha 归 1。可见性由调用方决定。 */
private fun finishStatic(block: View) {
    block.alpha = 1f
    block.updateLayoutParams { height = ViewGroup.LayoutParams.WRAP_CONTENT }
}

/** 块在父容器里能铺到的宽度(父内宽减自身 margin);父还没量过(=0)就放弃动画、静态展示。 */
private fun revealWidthOf(block: View): Int {
    val parent = block.parent as? ViewGroup ?: return 0
    val inner = parent.width - parent.paddingLeft - parent.paddingRight
    return inner - block.marginStart - block.marginEnd
}

private const val REVEAL_DURATION_MS = 260L

/** Material 标准 fast-out-slow-in,平台 PathInterpolator 免依赖。 */
private val REVEAL_INTERPOLATOR = PathInterpolator(0.4f, 0f, 0.2f, 1f)
