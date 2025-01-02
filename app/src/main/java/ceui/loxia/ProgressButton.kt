package ceui.loxia

import android.animation.AnimatorInflater
import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.Gravity
import androidx.core.content.res.ResourcesCompat
import androidx.swiperefreshlayout.widget.CircularProgressDrawable
import ceui.lisa.R
import kotlin.math.roundToInt


class ProgressImageButton(context: Context, attrs: AttributeSet?, defStyle: Int) :
    androidx.appcompat.widget.AppCompatImageButton(context, attrs, defStyle), ProgressIndicator {

    data class OriginalState(
        val drawable: Drawable?,
        val isClickable: Boolean
    )

    private var originalState: OriginalState? = null

    var preferSize: Int? = null


    private val progressStrokeWidth: Float
    private val progressWidth: Float

    private var isAnimationRunning = false
    private var pendingTarget: Drawable? = null

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

    override fun setImageResource(resId: Int) {
        if (isAnimationRunning) {
            pendingTarget = ResourcesCompat.getDrawable(resources, resId, context.theme)
        } else {
            super.setImageResource(resId)
        }
    }

    override fun showProgress() {
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
        isAnimationRunning = true
    }

    override fun hideProgress() {
        isAnimationRunning = false
        (drawable as? CircularProgressDrawable)?.stop()

        originalState?.let {
            isClickable = it.isClickable
            setImageDrawable(pendingTarget ?: it.drawable)
            pendingTarget = null
        }
    }

}


class ProgressTextButton(context: Context, attrs: AttributeSet?, defStyle: Int) :
    androidx.appcompat.widget.AppCompatButton(context, attrs, defStyle), Drawable.Callback, ProgressIndicator {

    data class OriginalState(
        val padding: Rect,
        val text: String,
        val isClickable: Boolean,
        val drawable: Drawable?
    )

    private var originalState: OriginalState? = null

    private var pendingTarget: String? = null
    private var color: Int = Color.WHITE


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

    private var isAnimationRunning = false

    override fun showProgress() {
        originalState = OriginalState(
            Rect(paddingLeft, paddingTop, paddingRight, paddingBottom),
            text.toString(),
            isClickable,
            compoundDrawables.firstOrNull()
        )

        val circleWidth = progressWidth.roundToInt()
        val circleHeight = progressWidth.roundToInt()

        val hPadding = (width - circleWidth) / 2
        (height - circleHeight - paddingTop - paddingBottom) / 2

        isClickable = false

        val drawable = CircularProgressDrawable(context).apply {
            setColorSchemeColors(color)
            strokeCap = Paint.Cap.ROUND
            strokeWidth = progressStrokeWidth
            centerRadius = progressWidth / 2
        }

        text = null
        setPadding(hPadding, 0, hPadding, 0)
        drawable.bounds = Rect(0, 0, circleWidth, circleHeight)
        drawable.callback = this
        setCompoundDrawables(drawable, null, null, null)
        drawable.start()
        isAnimationRunning = true
    }


    override fun hideProgress() {
        isAnimationRunning = false
        val stored = originalState?.copy() ?: return
        originalState = null // 清空状态
        // 恢复原始状态
        setCompoundDrawables(stored.drawable, null, null, null)
        text = pendingTarget ?: stored.text // 恢复文字
        pendingTarget = null
        isClickable = stored.isClickable
        setPadding(
            stored.padding.left,
            stored.padding.top,
            stored.padding.right,
            stored.padding.bottom
        )
    }

    override fun setText(text: CharSequence?, type: BufferType?) {
        if (isAnimationRunning) {
            pendingTarget = text?.toString()
        } else {
            super.setText(text, type)
        }
    }

    override fun invalidateDrawable(who: Drawable) {
        invalidate()
    }

    override fun unscheduleDrawable(who: Drawable, what: Runnable) {

    }

    override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {

    }
}