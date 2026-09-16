package ceui.pixiv.plaza.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout

/** Rebuild children after layout, when the actual card width is available. */
class PlazaIllustGrid
@JvmOverloads
constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    var onMeasured: ((Int) -> Unit)? = null
    private val rebind = Runnable { if (width > 0) onMeasured?.invoke(width) }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && w != oldw) {
            removeCallbacks(rebind)
            post(rebind)
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(rebind)
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        removeCallbacks(rebind)
        post(rebind)
    }
}
