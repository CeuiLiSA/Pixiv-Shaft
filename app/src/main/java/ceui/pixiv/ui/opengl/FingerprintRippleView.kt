package ceui.pixiv.ui.opengl


import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.view.MotionEvent

class FingerprintRippleView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    private val renderer: RippleRenderer

    init {
        setEGLContextClientVersion(2)
        renderer = RippleRenderer(context)
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
            renderer.setTouch(event.x, height - event.y) // Flip Y
        }
        return true
    }
}