package ceui.pixiv.ui.settings

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.annotation.ColorInt

/**
 * 自定义主题色 picker 的两个手绘控件（issue #1014）：[SaturationValueView] 饱和度/明度方块 +
 * [HueSliderView] 色相条。合起来就是 Material 3 色彩选择器的标准布局（HSV 方块 + 色相滑条 +
 * HEX 输入框，输入框在 sheet 的 layout 里）。
 *
 * 为什么手绘：Material Components for Android 到今天也没有官方 color picker 控件，M3 的色彩
 * 选择器只存在于设计规范里；仓里已有的 `com.jaredrummler:colorpicker`（小说阅读器背景/文字色在用）
 * 是老 AppCompat 样式的对话框，和设置页这套 MD3-E 视觉对不上。所以按 MD3-E 的形状语言自己画：
 * 大圆角（方块 20dp、色相条全圆角）、白色描边手柄、无阴影。
 *
 * 两个控件都只吐 HSV 分量，颜色的合成/落库归 [CustomThemeColorSheet]。
 */

/** 手柄、描边等共用的一点点绘制常量。 */
private const val THUMB_RING_DP = 3f
private const val THUMB_RADIUS_DP = 11f

/**
 * 饱和度（左 0 → 右 1）/ 明度（上 1 → 下 0）方块。色相由外部通过 [hue] 灌入。
 */
class SaturationValueView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** 当前 s/v 变化的回调（拖动中持续触发）。 */
    var onSaturationValueChanged: ((saturation: Float, value: Float) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val cornerRadius = 20f * density

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = THUMB_RING_DP * density
        color = Color.WHITE
    }
    private val thumbHairline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = 0x33000000
    }
    private val rect = RectF()

    var hue: Float = 0f
        set(value) {
            field = value
            rebuildBaseShader()
            invalidate()
        }

    var saturation: Float = 1f
        private set
    var value: Float = 1f
        private set

    /** 外部（HEX 输入 / 初始值）直接设定 s/v，不回调，避免和输入框互相打架。 */
    fun setSaturationValue(saturation: Float, value: Float) {
        this.saturation = saturation.coerceIn(0f, 1f)
        this.value = value.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rect.set(0f, 0f, w.toFloat(), h.toFloat())
        rebuildBaseShader()
        whitePaint.shader = LinearGradient(
            0f, 0f, w.toFloat(), 0f, Color.WHITE, 0x00FFFFFF, Shader.TileMode.CLAMP
        )
        blackPaint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(), 0x00000000, Color.BLACK, Shader.TileMode.CLAMP
        )
    }

    private fun rebuildBaseShader() {
        basePaint.color = Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
    }

    override fun onDraw(canvas: Canvas) {
        // 三层叠出 HSV 方块：纯色相底 → 横向白到透明（饱和度）→ 纵向透明到黑（明度）。
        // 每层都按同一个圆角画，省掉一次 clipPath（clip 不抗锯齿，圆角会有毛边）。
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, basePaint)
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, whitePaint)
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, blackPaint)

        val cx = saturation * width
        val cy = (1f - value) * height
        val r = THUMB_RADIUS_DP * density
        // 手柄贴边时会被画出界，往里收半个手柄，保证整圈白环都在方块内
        val clampedX = cx.coerceIn(r, width - r)
        val clampedY = cy.coerceIn(r, height - r)
        thumbFill.color = Color.HSVToColor(floatArrayOf(hue, saturation, value))
        canvas.drawCircle(clampedX, clampedY, r, thumbFill)
        canvas.drawCircle(clampedX, clampedY, r - THUMB_RING_DP * density / 2f, thumbRing)
        canvas.drawCircle(clampedX, clampedY, r, thumbHairline)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                // sheet 里外面套着 NestedScrollView + BottomSheet 的竖向拖动，不拦住的话
                // 竖着拖（调明度）会被当成拖 sheet，方块直接没法用
                parent?.requestDisallowInterceptTouchEvent(true)
                saturation = (event.x / width).coerceIn(0f, 1f)
                value = (1f - event.y / height).coerceIn(0f, 1f)
                invalidate()
                onSaturationValueChanged?.invoke(saturation, value)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }
}

/** 色相条：全圆角彩虹轨道 + MD3-E 白色胶囊手柄。 */
class HueSliderView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var onHueChanged: ((hue: Float) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val handleHairline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = 0x33000000
    }
    private val trackRect = RectF()
    private val handleRect = RectF()

    var hue: Float = 0f
        private set

    /** 外部设定色相，不回调。 */
    fun setHueSilently(hue: Float) {
        this.hue = hue.coerceIn(0f, 360f)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 轨道左右各留出半个手柄宽，手柄推到两端时不出界
        val inset = HANDLE_WIDTH_DP * density / 2f
        trackRect.set(inset, 0f, w - inset, h.toFloat())
        val colors = IntArray(HUE_STOPS) { i ->
            Color.HSVToColor(floatArrayOf(i * 360f / (HUE_STOPS - 1), 1f, 1f))
        }
        trackPaint.shader = LinearGradient(
            trackRect.left, 0f, trackRect.right, 0f, colors, null, Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        val trackRadius = trackRect.height() / 2f
        canvas.drawRoundRect(trackRect, trackRadius, trackRadius, trackPaint)

        val cx = trackRect.left + hue / 360f * trackRect.width()
        val halfW = HANDLE_WIDTH_DP * density / 2f
        val overhang = 4f * density
        handleRect.set(cx - halfW, -overhang, cx + halfW, height + overhang)
        val handleRadius = halfW
        canvas.drawRoundRect(handleRect, handleRadius, handleRadius, handleFill)
        canvas.drawRoundRect(handleRect, handleRadius, handleRadius, handleHairline)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val ratio = ((event.x - trackRect.left) / trackRect.width()).coerceIn(0f, 1f)
                hue = ratio * 360f
                invalidate()
                onHueChanged?.invoke(hue)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    private companion object {
        const val HANDLE_WIDTH_DP = 9f

        /** 彩虹渐变的采样点数；13 段（每 30°）足够平滑，再多肉眼看不出。 */
        const val HUE_STOPS = 13
    }
}

/** 色块上叠文字时用：底色偏亮就用深字，偏暗就用白字。 */
@ColorInt
internal fun contrastingTextColor(@ColorInt background: Int): Int =
    if (androidx.core.graphics.ColorUtils.calculateLuminance(background) > 0.5) 0xFF1A1A2E.toInt()
    else Color.WHITE
