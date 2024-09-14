package ceui.pixiv.widgets

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import ceui.lisa.R
import ceui.lisa.utils.Common
import ceui.pixiv.utils.ShapedDrawables
import kotlin.math.floor
import kotlin.math.roundToInt


class SlidingCursorView(context: Context, attrs: AttributeSet?, defStyle: Int): View(context, attrs, defStyle) {
    constructor(context: Context) : this(context, null, 0)

    constructor(context: Context, attrs: AttributeSet?): this(context, attrs, 0)

    private var cursor: Drawable

    private var _cursorHeight: Float
    private var _cursorWidth: Float

    private val cursorColor: Int

    init {
        val ta = context.obtainStyledAttributes(attrs, R.styleable.SlidingCursorView)
        _cursorWidth = ta.getDimension(R.styleable.SlidingCursorView_scv_cursor_width, 0F)
        _cursorHeight = ta.getDimension(R.styleable.SlidingCursorView_scv_cursor_height, 0F)
        cursorColor = ta.getColor(R.styleable.SlidingCursorView_scv_cursor_color, Color.WHITE)

        cursor = ShapedDrawables.getRoundedRect(_cursorHeight / 2, 0F, Color.TRANSPARENT, cursorColor)

        ta.recycle()
    }

    var cursorHeight
        get() = _cursorHeight
        set(value) {
            _cursorHeight = value
            cursor = ShapedDrawables.getRoundedRect(_cursorHeight / 2, 0F, Color.TRANSPARENT, cursorColor)
            invalidate()
        }

    private var _focusIndex: Float = 0F
    var focusIndex
        get() = _focusIndex
        set(value) {
            _focusIndex = value
            val position = floor(_focusIndex + 0.5F).roundToInt()

            invalidate()

            onFocusIndexChangeListeners.forEach {
                it(value, position)
            }
        }

    private val onFocusIndexChangeListeners = mutableListOf<(Float, Int) -> Unit>()

    fun addFocusIndexChangeListener(listener: (Float, Int) -> Unit) {
        onFocusIndexChangeListeners.add(listener)
    }

    fun setCursorColor(color: Int) {
        cursor = ShapedDrawables.getRoundedRect(_cursorHeight / 2, 0F, Color.TRANSPARENT, color)
        invalidate()
    }

    fun setCursorWidth(size: Int) {
        _cursorWidth = size.toFloat()
        invalidate()
    }

    fun setCursorHeight(size: Int) {
        _cursorHeight = size.toFloat()
        invalidate()
    }

    var getLeftAndWidthForPosition: ((Int)->Pair<Int, Int>)? = null

    override fun draw(c: Canvas) {
        super.draw(c)

        val leftAndWidthBlock = getLeftAndWidthForPosition ?: return

        val position = floor(focusIndex + 0.5F).roundToInt()

        val leftAndWidth = leftAndWidthBlock(position)
        val cellLeft = leftAndWidth.first
        val cellWidth = leftAndWidth.second

        val cursorCenter = cellLeft + (_focusIndex - (position - 0.5F)) * cellWidth

        Common.showLog("sadsdas2 cellWidth: ${cellWidth}, cursorCenter: ${cursorCenter}")

        cursor.bounds = Rect((cursorCenter - _cursorWidth / 2F).roundToInt(), height - cursorHeight.roundToInt(), (cursorCenter + _cursorWidth / 2F).roundToInt(), height)

        cursor.draw(c)
    }


    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var w = _cursorWidth.roundToInt()
        var h = _cursorHeight.roundToInt()

        w += paddingLeft + paddingRight
        h += paddingTop + paddingBottom
        w = w.coerceAtLeast(suggestedMinimumWidth)
        h = h.coerceAtLeast(suggestedMinimumHeight)
        val widthSize = resolveSizeAndState(w, widthMeasureSpec, 0)
        val heightSize = resolveSizeAndState(h, heightMeasureSpec, 0)

        setMeasuredDimension(widthSize, heightSize)
    }
}