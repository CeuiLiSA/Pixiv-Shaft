package ceui.pixiv.ui.detail

import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.view.isVisible
import ceui.pixiv.utils.ppppx

/**
 * 「作品详情」(插画/漫画 V3)和「作品档案」(小说)共用的折叠面板展开/收起:
 * [content] 是被折的内容块,[arrow] 是标题行右侧的 ▾。
 * [animate] = false 用于绑定时按已有状态还原(滚走再滚回不放动画)。
 */
internal fun applyDetailPanelExpanded(content: View, arrow: View, expanded: Boolean, animate: Boolean) {
    if (!animate) {
        content.isVisible = expanded
        arrow.rotation = if (expanded) 0f else 180f
        return
    }
    if (expanded) {
        content.alpha = 0f; content.translationY = -12.ppppx.toFloat(); content.isVisible = true
        content.animate().alpha(1f).translationY(0f).setDuration(350)
            .setInterpolator(DecelerateInterpolator(2f)).start()
        arrow.animate().rotation(0f).setDuration(300).start()
    } else {
        content.animate().alpha(0f).translationY(-12.ppppx.toFloat()).setDuration(250)
            .setInterpolator(DecelerateInterpolator(2f))
            .withEndAction { content.isVisible = false; content.translationY = 0f }.start()
        arrow.animate().rotation(180f).setDuration(300).start()
    }
}
