package ceui.pixiv.ui.opengl

import android.content.Context
import android.graphics.PixelFormat
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout

class FingerprintRippleOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val glView = FingerprintRippleView(context).apply {
        holder.setFormat(PixelFormat.TRANSLUCENT)
        setZOrderOnTop(true)
        layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT
        )
    }

    private var unlockListener: (() -> Unit)? = null

    init {
        setWillNotDraw(true)
        addView(glView)
        isClickable = true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            unlockListener?.invoke()
        }
        return super.onTouchEvent(event)
    }

    fun setOnUnlockListener(listener: () -> Unit) {
        unlockListener = listener
    }
}