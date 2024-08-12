package ceui.loxia

import android.animation.AnimatorInflater
import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.Gravity
import androidx.swiperefreshlayout.widget.CircularProgressDrawable
import ceui.lisa.R
import kotlin.math.roundToInt

interface Progressable {
    var isProgressing: Boolean
}

class ProgressImageButton(context: Context, attrs: AttributeSet?, defStyle: Int) :
    androidx.appcompat.widget.AppCompatImageButton(context, attrs, defStyle), Progressable {

    data class OriginalState(
        val drawable: Drawable?,
        val isClickable: Boolean
    )

    private var originalState: OriginalState? = null

    var preferSize: Int? = null

    override var isProgressing: Boolean = false
        set(value) {
            if (value == field) {
                return
            }

            if (value) {
                originalState = OriginalState(drawable, isClickable)

                val progressDrawable = CircularProgressDrawable(context).apply {
                    setColorSchemeColors(Color.WHITE)
                    strokeCap = Paint.Cap.ROUND
                    strokeWidth = progressStrokeWidth
                    centerRadius = (preferSize?.toFloat()?.div(2)) ?: (progressWidth / 2)
                }

                progressDrawable.start()

                isClickable = false
                setImageDrawable(progressDrawable)


            } else {
                (drawable as? CircularProgressDrawable)?.stop()

                require(originalState != null)
                originalState?.let {
                    isClickable = it.isClickable
                    setImageDrawable(it.drawable)
                }
            }
            field = value
        }

    fun showProgress(progress: Boolean) {
        isProgressing = progress
    }

    private val progressStrokeWidth: Float
    private val progressWidth: Float

    constructor(context: Context) : this(context, null)

    constructor(context: Context, attrs: AttributeSet?) : this(context, attrs, 0)

    init {

        val ta = context.obtainStyledAttributes(attrs, R.styleable.ProgressImageButton)
        progressStrokeWidth = ta.getDimension(
            R.styleable.ProgressImageButton_pib_progress_stroke_width,
            resources.getDimension(R.dimen.middle_progress_width)
        )
        progressWidth = ta.getDimension(
            R.styleable.ProgressImageButton_pib_progress_width,
            resources.getDimension(R.dimen.middle_progress_radius) * 2
        )
        ta.recycle()

        stateListAnimator =
            AnimatorInflater.loadStateListAnimator(context, R.animator.button_press_alpha)
    }

}

class ProgressTextButton(context: Context, attrs: AttributeSet?, defStyle: Int) :
    androidx.appcompat.widget.AppCompatButton(context, attrs, defStyle), Drawable.Callback,
    Progressable {

    data class OriginalState(
        val padding: Rect,
        val text: String,
        val isClickable: Boolean,
        val drawable: Drawable?
    )

    private var originalState: OriginalState? = null

    override var isProgressing: Boolean = false
        set(value) {
            if (value == field) {
                return
            }

            if (value) {
                originalState = OriginalState(
                    Rect(paddingLeft, paddingTop, paddingRight, paddingBottom),
                    text.toString(),
                    isClickable,
                    compoundDrawables.firstOrNull()
                )

                val circleWidth = progressWidth.roundToInt()
                val circleHeight = progressWidth.roundToInt()

                val hPadding = (width - circleWidth) / 2
                val vPadding = (height - circleHeight - paddingTop - paddingBottom) / 2

                isClickable = false
                text = null

                val drawable = CircularProgressDrawable(context).apply {
                    setColorSchemeColors(Color.WHITE)
                    strokeCap = Paint.Cap.ROUND
                    strokeWidth = progressStrokeWidth
                    centerRadius = progressWidth / 2
                }

                setPadding(hPadding, vPadding, hPadding, vPadding)
                drawable.bounds = Rect(0, 0, circleWidth, circleHeight)

                drawable.callback = this
                setCompoundDrawables(drawable, null, null, null)
                drawable.start()
            } else {
                require(originalState != null)
                setCompoundDrawables(originalState?.drawable, null, null, null)
                originalState?.let {
                    text = it.text
                    isClickable = it.isClickable
                    setPadding(it.padding.left, it.padding.top, it.padding.right, it.padding.bottom)
                }
            }

            field = value
        }

    private val progressStrokeWidth: Float
    private val progressWidth: Float

    constructor(context: Context) : this(context, null)

    constructor(context: Context, attrs: AttributeSet?) : this(context, attrs, 0)

    init {
        val ta = context.obtainStyledAttributes(attrs, R.styleable.ProgressTextButton)
        progressStrokeWidth = ta.getDimension(
            R.styleable.ProgressTextButton_ptb_progress_stroke_width,
            resources.getDimension(R.dimen.middle_progress_width)
        )
        progressWidth = ta.getDimension(
            R.styleable.ProgressTextButton_ptb_progress_width,
            resources.getDimension(R.dimen.middle_progress_radius) * 2
        )
        ta.recycle()

        gravity = Gravity.CENTER
        stateListAnimator =
            AnimatorInflater.loadStateListAnimator(context, R.animator.button_press_alpha)
    }

    fun showProgress() {
        isProgressing = true
    }

    fun hideProgress() {
        isProgressing = false
    }

    override fun invalidateDrawable(who: Drawable) {
        invalidate()
    }

    override fun unscheduleDrawable(who: Drawable, what: Runnable) {

    }

    override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {

    }
}