package ceui.pixiv.ui.detail

import android.content.Context
import android.graphics.LinearGradient
import android.graphics.Shader
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView
import ceui.lisa.R
import kotlin.math.cos
import kotlin.math.sin

class GradientTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatTextView(context, attrs, defStyleAttr) {

    var gradientStartColor: Int = 0
        set(value) { field = value; shader = null; invalidate() }
    var gradientEndColor: Int = 0
        set(value) { field = value; shader = null; invalidate() }
    var gradientAngle: Float = 135f
        set(value) { field = value; shader = null; invalidate() }

    private var shader: Shader? = null

    init {
        attrs?.let {
            val ta = context.obtainStyledAttributes(it, R.styleable.GradientTextView)
            gradientStartColor = ta.getColor(R.styleable.GradientTextView_gtv_startColor, 0)
            gradientEndColor = ta.getColor(R.styleable.GradientTextView_gtv_endColor, 0)
            gradientAngle = ta.getFloat(R.styleable.GradientTextView_gtv_angle, 135f)
            ta.recycle()
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (changed) {
            shader = null
        }
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        if (gradientStartColor != 0 && gradientEndColor != 0 && width > 0 && height > 0) {
            if (shader == null) {
                val rad = Math.toRadians(gradientAngle.toDouble())
                val x1 = (width * (1 - cos(rad)) / 2).toFloat()
                val y1 = (height * (1 + sin(rad)) / 2).toFloat()
                val x2 = (width * (1 + cos(rad)) / 2).toFloat()
                val y2 = (height * (1 - sin(rad)) / 2).toFloat()
                shader = LinearGradient(
                    x1, y1, x2, y2,
                    gradientStartColor, gradientEndColor,
                    Shader.TileMode.CLAMP
                )
            }
            paint.shader = shader
        }
        super.onDraw(canvas)
    }
}
