package ceui.pixiv.widgets

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
        val drawable: Drawable?,
        val minWidth: Int,
        val minHeight: Int
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
            compoundDrawables.firstOrNull(),
            minWidth,
            minHeight
        )

        val circleWidth = progressWidth.roundToInt()
        val circleHeight = progressWidth.roundToInt()

        // 转圈期间文字被清空,按钮只剩一个圆圈当 compound drawable,wrap_content 的按钮会从
        // "文字高"塌成"圆圈高"(评论区「查看回复」胶囊肉眼可见地缩一下)。这里直接把点击前的
        // 测量结果锁成 min 尺寸,loading 前后占位严格一致 —— 不用 padding 去凑:空 TextView 的
        // Layout 仍占一行行高,padding 凑出来的高度会比原来高出「行高 - 圆圈高」那么多。
        minWidth = width
        minHeight = height
        // 横向 padding 还是要重算:左侧 compound drawable 是钉在 paddingLeft 上画的,不重算圆圈
        // 会贴着左边而不是居中。drawablePadding 也要扣 —— 只要挂着 drawable,TextView 就会把它
        // 算进宽度。纵向不用管,drawable 本来就在 vspace 里居中。
        val hPadding = ((width - circleWidth - compoundDrawablePadding) / 2).coerceAtLeast(0)

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
        // min 尺寸必须还原成 XML 里的值(cell_comment 显式写了 0dp,而 AppCompat 按钮默认
        // 带 88dp x 48dp),否则复用的 view 会被上一次转圈的尺寸卡住
        minWidth = stored.minWidth
        minHeight = stored.minHeight
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