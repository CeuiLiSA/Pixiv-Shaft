package ceui.pixiv.ui.novel

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import androidx.core.content.withStyledAttributes
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.withClip
import androidx.core.view.isVisible
import ceui.lisa.R
import ceui.pixiv.witstudio.theme.v3Font
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * 纯颜色计算与对比度校验，无 View 依赖（JVM 单测覆盖十档预设 + 亮黄/纯白/纯黑双模）。
 *
 * 每一组前景色都按合成后的实际背景色（轨道底 + 进度填充最深处）做 WCAG AA（≥ 4.5:1）校正；
 * 底部微型进度轨按非文字图形对照（≥ 2.6:1）校正，确保浅色主题配盛夏黄（#FEE65E）也能清楚辨认。
 */
internal object NovelReadingProgressColors {

    data class Palette(
        @ColorInt val trackBg: Int,
        @ColorInt val fillStart: Int,
        @ColorInt val fillEnd: Int,
        @ColorInt val railTrack: Int,
        @ColorInt val railStart: Int,
        @ColorInt val railEnd: Int,
        @ColorInt val frontier: Int,
        @ColorInt val border: Int,
        @ColorInt val textColor: Int,
        @ColorInt val ringTrack: Int,
        @ColorInt val doneTrackBg: Int,
        @ColorInt val doneFillStart: Int,
        @ColorInt val doneFillEnd: Int,
        @ColorInt val doneRailStart: Int,
        @ColorInt val doneRailEnd: Int,
        @ColorInt val doneBorder: Int,
        @ColorInt val doneTextColor: Int,
        @ColorInt val doneBadgeFill: Int,
        @ColorInt val doneBadgeCheck: Int,
        @ColorInt val glassBg: Int,
        @ColorInt val glassFillStart: Int,
        @ColorInt val glassFillEnd: Int,
        @ColorInt val glassBorder: Int,
        @ColorInt val glassRingTrack: Int,
        @ColorInt val glassRingArc: Int,
        @ColorInt val glassDoneFillStart: Int,
        @ColorInt val glassDoneFillEnd: Int,
        @ColorInt val glassDoneBorder: Int,
        @ColorInt val glassDoneBadgeFill: Int,
        @ColorInt val glassDoneBadgeCheck: Int,
    )

    private class CacheEntry(
        val primary: Int,
        val isDark: Boolean,
        val pageBg: Int,
        val greenBase: Int,
        val palette: Palette,
    )

    @Volatile
    private var cached: CacheEntry? = null

    fun resolve(
        @ColorInt primary: Int,
        isDark: Boolean,
        @ColorInt pageBg: Int,
        @ColorInt greenBase: Int,
    ): Palette {
        val opaquePrimary = primary or (0xFF shl 24)
        val opaquePageBg = pageBg or (0xFF shl 24)
        val opaqueGreen = greenBase or (0xFF shl 24)
        cached?.let { entry ->
            if (entry.primary == opaquePrimary &&
                entry.isDark == isDark &&
                entry.pageBg == opaquePageBg &&
                entry.greenBase == opaqueGreen
            ) {
                return entry.palette
            }
        }

        val shiftedPrimary = hueShift(opaquePrimary, 26f)
        val shiftedGreen = hueShift(opaqueGreen, 14f)

        // 进行中（1–99%）：主题染色柔和底 + 渐进液体填充 + 底部细高亮轨
        val trackBg = ColorUtils.compositeColors(
            withAlpha(opaquePrimary, if (isDark) 0.10f else 0.065f),
            opaquePageBg,
        )
        val fillStart = withAlpha(opaquePrimary, if (isDark) 0.16f else 0.09f)
        val fillEnd = withAlpha(shiftedPrimary, if (isDark) 0.26f else 0.16f)
        val worstFillBg = ColorUtils.compositeColors(fillEnd, trackBg)

        val baseText = if (isDark) ensureLightEnough(opaquePrimary, 0.72f) else ensureDarkEnough(opaquePrimary, 0.36f)
        val textColor = ensureContrastAgainstBoth(baseText, worstFillBg, trackBg, goLighter = isDark, minRatio = 4.5)

        val railStart = ensureContrastAgainst(opaquePrimary, trackBg, goLighter = isDark, minRatio = 2.6)
        val railEnd = ensureContrastAgainst(shiftedPrimary, trackBg, goLighter = isDark, minRatio = 2.6)
        val railTrack = withAlpha(railStart, if (isDark) 0.16f else 0.11f)
        val frontier = withAlpha(railEnd, if (isDark) 0.48f else 0.34f)
        val border = withAlpha(railStart, if (isDark) 0.24f else 0.16f)
        val ringTrack = withAlpha(textColor, if (isDark) 0.26f else 0.22f)

        // 已读完（100%）：语义翡翠绿（不随主题色漂移）
        val doneTrackBg = ColorUtils.compositeColors(
            withAlpha(opaqueGreen, if (isDark) 0.12f else 0.08f),
            opaquePageBg,
        )
        val doneFillStart = withAlpha(opaqueGreen, if (isDark) 0.16f else 0.10f)
        val doneFillEnd = withAlpha(shiftedGreen, if (isDark) 0.26f else 0.18f)
        val doneWorstFillBg = ColorUtils.compositeColors(doneFillEnd, doneTrackBg)

        val doneTextColor = ensureContrastAgainstBoth(opaqueGreen, doneWorstFillBg, doneTrackBg, goLighter = isDark, minRatio = 4.5)
        val doneRailStart = ensureContrastAgainst(opaqueGreen, doneTrackBg, goLighter = isDark, minRatio = 2.8)
        val doneRailEnd = ensureContrastAgainst(shiftedGreen, doneTrackBg, goLighter = isDark, minRatio = 2.8)
        val doneBorder = withAlpha(doneRailStart, if (isDark) 0.28f else 0.20f)
        val doneBadgeFill = doneTextColor
        val doneBadgeCheck = if (ColorUtils.calculateLuminance(doneBadgeFill) < 0.45) {
            0xFFFFFFFF.toInt()
        } else {
            0xFF0A1410.toInt()
        }

        // 毛玻璃胶囊（叠在排行榜封面上，日夜统一恒暗半透底 + 亮色微光环）
        val glassBg = 0x8F000000.toInt()
        val glassRefOpaque = 0xFF141418.toInt()
        val glassRingArc = ensureContrastAgainst(
            ensureLightEnough(opaquePrimary, 0.70f),
            glassRefOpaque,
            goLighter = true,
            minRatio = 4.5,
        )
        val glassFillStart = withAlpha(glassRingArc, 0.20f)
        val glassFillEnd = withAlpha(hueShift(glassRingArc, 24f), 0.34f)
        val glassBorder = 0x2EFFFFFF
        val glassRingTrack = 0x45FFFFFF
        val glassDoneGreen = 0xFF2DD4A8.toInt()
        val glassDoneFillStart = withAlpha(glassDoneGreen, 0.22f)
        val glassDoneFillEnd = withAlpha(hueShift(glassDoneGreen, 12f), 0.36f)
        val glassDoneBorder = withAlpha(glassDoneGreen, 0.48f)
        val glassDoneBadgeCheck = 0xFF081410.toInt()

        val palette = Palette(
            trackBg = trackBg,
            fillStart = fillStart,
            fillEnd = fillEnd,
            railTrack = railTrack,
            railStart = railStart,
            railEnd = railEnd,
            frontier = frontier,
            border = border,
            textColor = textColor,
            ringTrack = ringTrack,
            doneTrackBg = doneTrackBg,
            doneFillStart = doneFillStart,
            doneFillEnd = doneFillEnd,
            doneRailStart = doneRailStart,
            doneRailEnd = doneRailEnd,
            doneBorder = doneBorder,
            doneTextColor = doneTextColor,
            doneBadgeFill = doneBadgeFill,
            doneBadgeCheck = doneBadgeCheck,
            glassBg = glassBg,
            glassFillStart = glassFillStart,
            glassFillEnd = glassFillEnd,
            glassBorder = glassBorder,
            glassRingTrack = glassRingTrack,
            glassRingArc = glassRingArc,
            glassDoneFillStart = glassDoneFillStart,
            glassDoneFillEnd = glassDoneFillEnd,
            glassDoneBorder = glassDoneBorder,
            glassDoneBadgeFill = glassDoneGreen,
            glassDoneBadgeCheck = glassDoneBadgeCheck,
        )
        cached = CacheEntry(opaquePrimary, isDark, opaquePageBg, opaqueGreen, palette)
        return palette
    }

    @ColorInt
    private fun withAlpha(@ColorInt color: Int, alpha: Float): Int =
        ColorUtils.setAlphaComponent(color, (alpha.coerceIn(0f, 1f) * 255f).toInt())

    @ColorInt
    private fun hueShift(@ColorInt color: Int, degrees: Float): Int {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color, hsl)
        hsl[0] = (hsl[0] + degrees + 360f) % 360f
        return ColorUtils.HSLToColor(hsl)
    }

    @ColorInt
    private fun ensureLightEnough(@ColorInt color: Int, minL: Float): Int {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color, hsl)
        if (hsl[2] < minL) hsl[2] = minL
        return ColorUtils.HSLToColor(hsl)
    }

    @ColorInt
    private fun ensureDarkEnough(@ColorInt color: Int, maxL: Float): Int {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color, hsl)
        if (hsl[2] > maxL) hsl[2] = maxL
        return ColorUtils.HSLToColor(hsl)
    }

    @ColorInt
    private fun ensureContrastAgainstBoth(
        @ColorInt fg: Int,
        @ColorInt bg1: Int,
        @ColorInt bg2: Int,
        goLighter: Boolean,
        minRatio: Double,
    ): Int {
        val step1 = ensureContrastAgainst(fg, bg1, goLighter, minRatio)
        return ensureContrastAgainst(step1, bg2, goLighter, minRatio)
    }

    @ColorInt
    private fun ensureContrastAgainst(
        @ColorInt fg: Int,
        @ColorInt bg: Int,
        goLighter: Boolean,
        minRatio: Double,
    ): Int {
        val opaqueBg = bg or (0xFF shl 24)
        if (ColorUtils.calculateContrast(fg, opaqueBg) >= minRatio) return fg
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(fg, hsl)
        var result = fg
        repeat(50) {
            hsl[2] = (hsl[2] + if (goLighter) 0.02f else -0.02f).coerceIn(0f, 1f)
            result = ColorUtils.HSLToColor(hsl)
            if (ColorUtils.calculateContrast(result, opaqueBg) >= minRatio) return result
            if (hsl[2] <= 0f || hsl[2] >= 1f) {
                val whiteContrast = ColorUtils.calculateContrast(Color.WHITE, opaqueBg)
                val blackContrast = ColorUtils.calculateContrast(Color.BLACK, opaqueBg)
                return if (whiteContrast >= blackContrast) Color.WHITE else Color.BLACK
            }
        }
        return result
    }
}

/**
 * 小说列表卡片阅读进度指示器（单 View 纯 Canvas 自绘，零子 View、零 `onDraw` 分配）。
 *
 * 两种视觉形态，共用同一套进度状态与主题色派生：
 * - [STYLE_RIBBON]（主力小说卡 `recy_novel.xml`）：全宽胶囊条，包含柔和主题染色底、渐进液体填充、
 *   2dp 渐变底部高亮轨、抗锯齿进度圆环（100% 切语义绿实心对勾）、状态文案与右侧 `tnum` 百分比。
 * - [STYLE_GLASS_PILL]（推荐页排行榜横滑卡 `recy_rank_novel_horizontal.xml`）：左上角紧致毛玻璃
 *   胶囊，半透暗底 + 液体微光填充 + 进度圆环/绿勾 + 纯白 `tnum` 百分比。
 */
class NovelReadingProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var styleMode: Int = STYLE_RIBBON
    private var percent: Int? = null
    private var palette: NovelReadingProgressColors.Palette? = null
    private val tmpTypedValue = TypedValue()

    private val density = resources.displayMetrics.density
    private val ribbonMinHeightPx = dp(24f)
    private val ribbonPadStartPx = dp(8f)
    private val ribbonPadEndPx = dp(9.5f)
    private val ribbonIconGapPx = dp(5.5f)
    private val ribbonLabelPctGapPx = dp(8f)
    private val ribbonRailHeightPx = dp(2.2f)
    private val ribbonFrontierWidthPx = dp(1.2f)
    private val glassMinHeightPx = dp(20f)
    private val glassPadStartPx = dp(6.5f)
    private val glassPadEndPx = dp(7.5f)
    private val glassIconGapPx = dp(4f)
    private val strokeWidthPx = max(1f, dp(0.75f))
    private val minFillSliverPx = dp(5f)

    private val pillRect = RectF()
    private val ringRect = RectF()
    private val clipPath = Path()
    private val checkPath = Path()

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val frontierPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val railTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val railFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthPx
    }
    private val ringTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val ringArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val checkBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val checkMarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = cachedMediumFont(context)
    }
    private val percentPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = cachedBoldFont(context)
        fontFeatureSettings = "tnum"
        letterSpacing = 0.01f
    }

    // 几何与 Shader 缓存键：只有尺寸 / 完成态 / 主题 / 方向变化时才重建 Path 与 Gradient
    private var builtWidth = -1
    private var builtHeight = -1
    private var builtCompleted = false
    private var builtPalette: NovelReadingProgressColors.Palette? = null
    private var builtRtl = false
    private var builtStyle = -1

    // 文案与截断缓存（零 onDraw 分配）
    private var currentStrings: LocaleStrings? = null
    private var percentText: String = ""
    private var labelText: String = ""
    private var cachedEllipsizedLabel: String = ""
    private var cachedEllipsizeSource: String = ""
    private var cachedEllipsizeAvail: Float = -1f
    private var cachedEllipsizeTextSize: Float = -1f

    private var lastScaledLabelPx = -1f
    private var lastScaledPercentPx = -1f

    init {
        isClickable = false
        isFocusable = false
        if (attrs != null) {
            context.withStyledAttributes(attrs, R.styleable.NovelReadingProgressView, defStyleAttr, 0) {
                styleMode = getInt(R.styleable.NovelReadingProgressView_nrp_style, STYLE_RIBBON)
            }
        }
        syncTextSizes()
        if (isInEditMode) {
            setProgress(64)
        }
    }

    /** 切换外观风格（一般由 XML `app:nrp_style` 声明，代码亦可切换）。 */
    fun setStyleMode(mode: Int) {
        if (styleMode == mode) return
        styleMode = mode
        builtWidth = -1
        syncTextSizes()
        requestLayout()
        invalidate()
    }

    /**
     * 设置阅读进度（1–100）；传 null 或 ≤ 0 时隐藏自身（`GONE`），不占布局与绘制耗时。
     * 相同进度与主题的重复调用直接短路返回，零 relayout / invalidate。
     */
    fun setProgress(value: Int?) {
        val clamped = value?.takeIf { it > 0 }?.coerceAtMost(100)
        if (clamped == null) {
            if (percent != null || visibility != GONE) {
                percent = null
                contentDescription = null
                visibility = GONE
            }
            return
        }
        val nextPalette = resolvePalette(context, tmpTypedValue)
        val strings = localeStrings(context)
        val sizeChanged = syncTextSizes()
        val sameState = percent == clamped &&
            palette == nextPalette &&
            currentStrings === strings &&
            isVisible &&
            !sizeChanged
        if (sameState) return

        val wasGone = !isVisible
        val prevWidth = if (styleMode == STYLE_GLASS_PILL) measuredWidth else 0

        percent = clamped
        palette = nextPalette
        currentStrings = strings
        percentText = strings.percentText(clamped)
        labelText = if (clamped >= 100) strings.doneLabel else strings.inProgressLabel
        contentDescription = strings.contentDescription(clamped)

        if (wasGone) {
            visibility = VISIBLE
        }
        val glassWidthChanged = styleMode == STYLE_GLASS_PILL &&
            !wasGone &&
            prevWidth > 0 &&
            measureGlassWidth() != prevWidth
        if (sizeChanged || glassWidthChanged) {
            requestLayout()
        }
        invalidate()
    }

    /** 供测试或外部读取当前展示的百分比（未读为 null）。 */
    fun currentPercent(): Int? = percent

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        val p = percent ?: return
        val strings = localeStrings(context)
        palette = resolvePalette(context, tmpTypedValue)
        currentStrings = strings
        percentText = strings.percentText(p)
        labelText = if (p >= 100) strings.doneLabel else strings.inProgressLabel
        contentDescription = strings.contentDescription(p)
        builtWidth = -1
        if (syncTextSizes() || styleMode == STYLE_GLASS_PILL) {
            requestLayout()
        }
        invalidate()
    }

    override fun onRtlPropertiesChanged(layoutDirection: Int) {
        super.onRtlPropertiesChanged(layoutDirection)
        builtWidth = -1
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        syncTextSizes()
        val labelFm = labelPaint.fontMetrics
        val pctFm = percentPaint.fontMetrics
        val textH = max(labelFm.descent - labelFm.ascent, pctFm.descent - pctFm.ascent)

        if (styleMode == STYLE_GLASS_PILL) {
            val desiredH = max(glassMinHeightPx, ceil(pctFm.descent - pctFm.ascent) + dp(6f)).toInt()
            val desiredW = measureGlassWidth()
            setMeasuredDimension(
                resolveSize(desiredW, widthMeasureSpec),
                resolveSize(desiredH, heightMeasureSpec),
            )
            return
        }

        val desiredH = max(ribbonMinHeightPx, ceil(textH) + dp(9f)).toInt()
        val wMode = MeasureSpec.getMode(widthMeasureSpec)
        val wSize = MeasureSpec.getSize(widthMeasureSpec)
        val measuredW = when (wMode) {
            MeasureSpec.EXACTLY -> wSize
            MeasureSpec.AT_MOST -> wSize
            else -> {
                val iconD = iconDiameter(textH)
                val labelW = if (labelText.isNotEmpty()) labelPaint.measureText(labelText) else 0f
                val pctW = if (percentText.isNotEmpty()) percentPaint.measureText(percentText) else 0f
                ceil(
                    ribbonPadStartPx + iconD + ribbonIconGapPx + labelW +
                        ribbonLabelPctGapPx + pctW + ribbonPadEndPx
                ).toInt()
            }
        }
        setMeasuredDimension(measuredW, resolveSize(desiredH, heightMeasureSpec))
    }

    private fun measureGlassWidth(): Int {
        val pctFm = percentPaint.fontMetrics
        val textH = pctFm.descent - pctFm.ascent
        val iconD = glassIconDiameter(textH)
        val pctW = if (percentText.isNotEmpty()) percentPaint.measureText(percentText) else 0f
        return ceil(glassPadStartPx + iconD + glassIconGapPx + pctW + glassPadEndPx).toInt()
    }

    override fun onDraw(canvas: Canvas) {
        val p = percent ?: return
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return
        val pal = palette ?: resolvePalette(context, tmpTypedValue).also { palette = it }
        val completed = p >= 100
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL

        rebuildGeometryIfNeeded(w, h, completed, pal, rtl)

        if (styleMode == STYLE_GLASS_PILL) {
            drawGlassPill(canvas, w.toFloat(), h.toFloat(), p, completed, pal, rtl)
        } else {
            drawRibbon(canvas, w.toFloat(), h.toFloat(), p, completed, pal, rtl)
        }
    }

    private fun drawRibbon(
        canvas: Canvas,
        w: Float,
        h: Float,
        p: Int,
        completed: Boolean,
        pal: NovelReadingProgressColors.Palette,
        rtl: Boolean,
    ) {
        val radius = h * 0.5f

        // 1. 胶囊底轨
        trackPaint.color = if (completed) pal.doneTrackBg else pal.trackBg
        canvas.drawRoundRect(pillRect, radius, radius, trackPaint)

        // 2. 裁剪在胶囊轮廓内的液体进度填充 + 前沿微光 + 2dp 渐变底部高亮轨
        val fillFraction = p / 100f
        val fillSpan = if (completed) w else max(minFillSliverPx, w * fillFraction).coerceAtMost(w)
        val fillLeft = if (rtl) w - fillSpan else 0f
        val fillRight = if (rtl) w else fillSpan

        canvas.withClip(clipPath) {
            drawRect(fillLeft, 0f, fillRight, h, fillPaint)

            // 底部未读微轨 + 已读高亮渐变轨
            val railTop = h - ribbonRailHeightPx
            if (!completed) {
                railTrackPaint.color = pal.railTrack
                drawRect(0f, railTop, w, h, railTrackPaint)
            }
            drawRect(fillLeft, railTop, fillRight, h, railFillPaint)

            // 进行中（2–98%）的前沿细光边，增强进度分界辨识度
            if (p in 2..98) {
                val edgeX = if (rtl) fillLeft else fillRight - ribbonFrontierWidthPx
                drawRect(edgeX, 0f, edgeX + ribbonFrontierWidthPx, railTop, frontierPaint)
            }
        }

        // 3. 外圈 0.75dp 精致描边
        strokePaint.color = if (completed) pal.doneBorder else pal.border
        canvas.drawRoundRect(pillRect, radius, radius, strokePaint)

        // 4. 内容层（视觉垂直居中略微避让底部 2dp 轨）
        val centerY = (h - ribbonRailHeightPx * 0.35f) * 0.5f
        val fgColor = if (completed) pal.doneTextColor else pal.textColor
        labelPaint.color = fgColor
        percentPaint.color = fgColor

        val pctFm = percentPaint.fontMetrics
        val labelFm = labelPaint.fontMetrics
        val textH = max(labelFm.descent - labelFm.ascent, pctFm.descent - pctFm.ascent)
        val iconD = iconDiameter(textH)
        val iconR = iconD * 0.5f
        val pctW = percentPaint.measureText(percentText)

        // 图标中心 X 与百分比起点 X（支持 RTL 镜像）
        val iconCx = if (rtl) w - ribbonPadStartPx - iconR else ribbonPadStartPx + iconR
        drawStatusIcon(
            canvas = canvas,
            cx = iconCx,
            cy = centerY,
            radius = iconR,
            percent = p,
            completed = completed,
            ringTrackColor = pal.ringTrack,
            ringArcColor = fgColor,
            badgeFillColor = pal.doneBadgeFill,
            badgeCheckColor = pal.doneBadgeCheck,
        )

        val pctBaseline = centerY - (pctFm.ascent + pctFm.descent) * 0.5f
        val pctX = if (rtl) ribbonPadEndPx else w - ribbonPadEndPx - pctW
        canvas.drawText(percentText, pctX, pctBaseline, percentPaint)

        // 标签：可用宽度足够时完整画；极窄屏 + 2 倍大字号时自动截断或退让，绝不压盖百分比
        val availLabelW = w - ribbonPadStartPx - iconD - ribbonIconGapPx -
            ribbonLabelPctGapPx - pctW - ribbonPadEndPx
        if (availLabelW >= dp(14f) && labelText.isNotEmpty()) {
            val drawnLabel = ellipsizeLabelCached(labelText, availLabelW)
            if (drawnLabel.isNotEmpty()) {
                val labelBaseline = centerY - (labelFm.ascent + labelFm.descent) * 0.5f
                val labelX = if (rtl) {
                    w - ribbonPadStartPx - iconD - ribbonIconGapPx - labelPaint.measureText(drawnLabel)
                } else {
                    ribbonPadStartPx + iconD + ribbonIconGapPx
                }
                canvas.drawText(drawnLabel, labelX, labelBaseline, labelPaint)
            }
        }
    }

    private fun drawGlassPill(
        canvas: Canvas,
        w: Float,
        h: Float,
        p: Int,
        completed: Boolean,
        pal: NovelReadingProgressColors.Palette,
        rtl: Boolean,
    ) {
        val radius = h * 0.5f

        // 1. 恒暗毛玻璃底
        trackPaint.color = pal.glassBg
        canvas.drawRoundRect(pillRect, radius, radius, trackPaint)

        // 2. 胶囊内部柔和进度着色层
        val fillSpan = if (completed) w else max(minFillSliverPx, w * (p / 100f)).coerceAtMost(w)
        val fillLeft = if (rtl) w - fillSpan else 0f
        val fillRight = if (rtl) w else fillSpan
        canvas.withClip(clipPath) {
            drawRect(fillLeft, 0f, fillRight, h, fillPaint)
        }

        // 3. 玻璃高光描边
        strokePaint.color = if (completed) pal.glassDoneBorder else pal.glassBorder
        canvas.drawRoundRect(pillRect, radius, radius, strokePaint)

        // 4. 图标 + 纯白 tnum 百分比
        val centerY = h * 0.5f
        val pctFm = percentPaint.fontMetrics
        val iconD = glassIconDiameter(pctFm.descent - pctFm.ascent)
        val iconR = iconD * 0.5f
        val iconCx = if (rtl) w - glassPadStartPx - iconR else glassPadStartPx + iconR

        drawStatusIcon(
            canvas = canvas,
            cx = iconCx,
            cy = centerY,
            radius = iconR,
            percent = p,
            completed = completed,
            ringTrackColor = pal.glassRingTrack,
            ringArcColor = pal.glassRingArc,
            badgeFillColor = pal.glassDoneBadgeFill,
            badgeCheckColor = pal.glassDoneBadgeCheck,
        )

        percentPaint.color = Color.WHITE
        val pctBaseline = centerY - (pctFm.ascent + pctFm.descent) * 0.5f
        val pctW = percentPaint.measureText(percentText)
        val pctX = if (rtl) {
            w - glassPadStartPx - iconD - glassIconGapPx - pctW
        } else {
            glassPadStartPx + iconD + glassIconGapPx
        }
        canvas.drawText(percentText, pctX, pctBaseline, percentPaint)
    }

    private fun drawStatusIcon(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        percent: Int,
        completed: Boolean,
        @ColorInt ringTrackColor: Int,
        @ColorInt ringArcColor: Int,
        @ColorInt badgeFillColor: Int,
        @ColorInt badgeCheckColor: Int,
    ) {
        if (completed) {
            checkBgPaint.color = badgeFillColor
            canvas.drawCircle(cx, cy, radius, checkBgPaint)
            checkPath.reset()
            checkPath.moveTo(cx - radius * 0.34f, cy + radius * 0.03f)
            checkPath.lineTo(cx - radius * 0.08f, cy + radius * 0.29f)
            checkPath.lineTo(cx + radius * 0.36f, cy - radius * 0.25f)
            checkMarkPaint.color = badgeCheckColor
            checkMarkPaint.strokeWidth = max(dp(1.5f), radius * 0.26f)
            canvas.drawPath(checkPath, checkMarkPaint)
        } else {
            val ringStroke = max(dp(1.5f), radius * 0.28f)
            val arcRadius = radius - ringStroke * 0.5f
            ringTrackPaint.color = ringTrackColor
            ringTrackPaint.strokeWidth = ringStroke
            canvas.drawCircle(cx, cy, arcRadius, ringTrackPaint)

            ringArcPaint.color = ringArcColor
            ringArcPaint.strokeWidth = ringStroke
            ringRect.set(cx - arcRadius, cy - arcRadius, cx + arcRadius, cy + arcRadius)
            val sweep = max(18f, percent * 3.6f)
            canvas.drawArc(ringRect, -90f, sweep, false, ringArcPaint)
        }
    }

    private fun rebuildGeometryIfNeeded(
        w: Int,
        h: Int,
        completed: Boolean,
        pal: NovelReadingProgressColors.Palette,
        rtl: Boolean,
    ) {
        if (builtWidth == w &&
            builtHeight == h &&
            builtCompleted == completed &&
            builtPalette === pal &&
            builtRtl == rtl &&
            builtStyle == styleMode
        ) {
            return
        }
        builtWidth = w
        builtHeight = h
        builtCompleted = completed
        builtPalette = pal
        builtRtl = rtl
        builtStyle = styleMode

        val wf = w.toFloat()
        val hf = h.toFloat()
        val halfStroke = strokeWidthPx * 0.5f
        val radius = hf * 0.5f

        pillRect.set(halfStroke, halfStroke, wf - halfStroke, hf - halfStroke)
        clipPath.reset()
        clipPath.addRoundRect(pillRect, radius, radius, Path.Direction.CW)

        val x0 = if (rtl) wf else 0f
        val x1 = if (rtl) 0f else wf

        if (styleMode == STYLE_GLASS_PILL) {
            val start = if (completed) pal.glassDoneFillStart else pal.glassFillStart
            val end = if (completed) pal.glassDoneFillEnd else pal.glassFillEnd
            fillPaint.shader = LinearGradient(x0, 0f, x1, 0f, start, end, Shader.TileMode.CLAMP)
        } else {
            val fillStart = if (completed) pal.doneFillStart else pal.fillStart
            val fillEnd = if (completed) pal.doneFillEnd else pal.fillEnd
            fillPaint.shader = LinearGradient(x0, 0f, x1, 0f, fillStart, fillEnd, Shader.TileMode.CLAMP)

            val railStart = if (completed) pal.doneRailStart else pal.railStart
            val railEnd = if (completed) pal.doneRailEnd else pal.railEnd
            railFillPaint.shader = LinearGradient(x0, 0f, x1, 0f, railStart, railEnd, Shader.TileMode.CLAMP)

            frontierPaint.shader = LinearGradient(
                0f, 0f, 0f, hf - ribbonRailHeightPx,
                Color.TRANSPARENT, pal.frontier,
                Shader.TileMode.CLAMP,
            )
        }
    }

    private fun ellipsizeLabelCached(source: String, availWidth: Float): String {
        val textSize = labelPaint.textSize
        if (cachedEllipsizeSource == source &&
            cachedEllipsizeAvail == availWidth &&
            cachedEllipsizeTextSize == textSize
        ) {
            return cachedEllipsizedLabel
        }
        val result = if (labelPaint.measureText(source) <= availWidth) {
            source
        } else {
            TextUtils.ellipsize(source, labelPaint, availWidth, TextUtils.TruncateAt.END).toString()
        }
        cachedEllipsizeSource = source
        cachedEllipsizeAvail = availWidth
        cachedEllipsizeTextSize = textSize
        cachedEllipsizedLabel = result
        return result
    }

    private fun syncTextSizes(): Boolean {
        val dm = resources.displayMetrics
        val labelSp = 11f
        val pctSp = if (styleMode == STYLE_GLASS_PILL) 10.5f else 11.5f
        val targetLabel = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, labelSp, dm)
        val targetPct = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, pctSp, dm)
        if (targetLabel == lastScaledLabelPx && targetPct == lastScaledPercentPx) return false
        lastScaledLabelPx = targetLabel
        lastScaledPercentPx = targetPct
        labelPaint.textSize = targetLabel
        percentPaint.textSize = targetPct
        cachedEllipsizeTextSize = -1f
        return true
    }

    private fun iconDiameter(textHeight: Float): Float =
        min(dp(20f), max(dp(12.5f), textHeight * 0.85f))

    private fun glassIconDiameter(textHeight: Float): Float =
        min(dp(16f), max(dp(10.5f), textHeight * 0.80f))

    private fun dp(value: Float): Float = value * density

    private class LocaleStrings(
        val tag: String,
        private val locale: Locale,
        val inProgressLabel: String,
        val doneLabel: String,
        private val percentPattern: String,
        private val progressCdPattern: String,
        private val doneCd: String,
    ) {
        private val percentCache = arrayOfNulls<String>(101)
        private val cdCache = arrayOfNulls<String>(101)

        fun percentText(percent: Int): String {
            val idx = percent.coerceIn(1, 100)
            percentCache[idx]?.let { return it }
            return String.format(locale, percentPattern, idx).also { percentCache[idx] = it }
        }

        fun contentDescription(percent: Int): String {
            val idx = percent.coerceIn(1, 100)
            cdCache[idx]?.let { return it }
            val text = if (idx >= 100) doneCd else String.format(locale, progressCdPattern, idx)
            return text.also { cdCache[idx] = it }
        }
    }

    companion object {
        const val STYLE_RIBBON = 0
        const val STYLE_GLASS_PILL = 1

        @Volatile
        private var mediumFont: Typeface? = null

        @Volatile
        private var boldFont: Typeface? = null

        @Volatile
        private var cachedStrings: LocaleStrings? = null

        private fun cachedMediumFont(context: Context): Typeface =
            mediumFont ?: context.v3Font(500).also { mediumFont = it }

        private fun cachedBoldFont(context: Context): Typeface =
            boldFont ?: context.v3Font(700).also { boldFont = it }

        private fun localeStrings(context: Context): LocaleStrings {
            val locales = context.resources.configuration.locales
            val locale = if (locales.isEmpty) Locale.getDefault() else locales[0]
            val tag = if (locales.isEmpty) locale.toLanguageTag() else locales.toLanguageTags()
            cachedStrings?.let { if (it.tag == tag) return it }
            return LocaleStrings(
                tag = tag,
                locale = locale,
                inProgressLabel = context.getString(R.string.novel_card_read_progress_label),
                doneLabel = context.getString(R.string.novel_card_read_done_label),
                percentPattern = context.getString(R.string.novel_card_read_percent),
                progressCdPattern = context.getString(R.string.novel_card_read_progress_cd),
                doneCd = context.getString(R.string.novel_card_read_done_cd),
            ).also { cachedStrings = it }
        }

        private fun resolvePalette(context: Context, outValue: TypedValue): NovelReadingProgressColors.Palette {
            context.theme.resolveAttribute(androidx.appcompat.R.attr.colorPrimary, outValue, true)
            val primary = outValue.data or (0xFF shl 24)
            val isDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            val pageBg = ContextCompat.getColor(context, R.color.v3_bg)
            val greenBase = ContextCompat.getColor(context, R.color.v3_green)
            return NovelReadingProgressColors.resolve(
                primary = primary,
                isDark = isDark,
                pageBg = pageBg,
                greenBase = greenBase,
            )
        }
    }
}
