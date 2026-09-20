package ceui.pixiv.ui.common

import androidx.recyclerview.widget.RecyclerView

/**
 * Bounce the row at [adapterPos] once it has a ViewHolder. Right after a submitList or a scroll
 * the holder may not be laid out yet (layout is next frame), so this retries on a short delay;
 * callers size [triesLeft] to cover whatever they queued in front of it, e.g. a smooth scroll.
 * A detached list (the Fragment's view is gone) ends the retries.
 */
fun RecyclerView.highlightItemAt(
    adapterPos: Int,
    triesLeft: Int,
    retryDelayMs: Long = 100L,
    scale: Float = 1.05f,
    durationMs: Long = 200L,
) {
    if (!isAttachedToWindow) return
    val holder = findViewHolderForAdapterPosition(adapterPos)
    if (holder == null) {
        if (triesLeft > 0) {
            postDelayed(
                { highlightItemAt(adapterPos, triesLeft - 1, retryDelayMs, scale, durationMs) },
                retryDelayMs,
            )
        }
        return
    }
    val target = holder.itemView
    target.animate().cancel()
    target.scaleX = 1f
    target.scaleY = 1f
    target.animate().scaleX(scale).scaleY(scale).setDuration(durationMs)
        .withEndAction { target.animate().scaleX(1f).scaleY(1f).setDuration(durationMs).start() }
        .start()
}
