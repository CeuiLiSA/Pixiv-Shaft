package ceui.pixiv.ui.settings

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.withClip
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.widget.NestedScrollView
import ceui.lisa.R
import ceui.pixiv.ui.common.IllustListLayout
import ceui.pixiv.ui.common.JUSTIFIED_ROW_HEIGHT_FACTOR
import ceui.pixiv.ui.common.packJustifiedRows
import ceui.pixiv.witstudio.dialog.WitBottomSheet
import ceui.pixiv.witstudio.theme.V3Palette
import ceui.pixiv.witstudio.theme.card
import ceui.pixiv.witstudio.theme.color
import ceui.pixiv.witstudio.theme.dp
import ceui.pixiv.witstudio.theme.dpF
import ceui.pixiv.witstudio.theme.label
import ceui.pixiv.witstudio.theme.lineHeightRatio
import ceui.pixiv.witstudio.theme.motionEnabled
import ceui.pixiv.witstudio.theme.pressScale
import ceui.pixiv.witstudio.theme.ripple
import ceui.pixiv.witstudio.theme.shape

/**
 * 「插画列表布局」选择器（#1214）：2×2 张选项卡，每张上半部是该布局的示意预览 ——
 * 四张用**同一组**示例作品（同样的比例、同样的色块顺序），差别只剩排布本身，一眼能比出效果。
 *
 * 只负责选，存设置由调用方做（同 [CustomThemeColorSheet]）。
 */
object IllustListLayoutPicker {

    fun interface OnPicked {
        fun onPicked(layout: IllustListLayout)
    }

    @JvmStatic
    fun show(context: Context, onPicked: OnPicked) {
        val sheet = WitBottomSheet(context)
        // 跟着每次点选走，而不是只记打开时的值：收起前那 180ms 里连点两张，要以最后一张为准
        var selected = IllustListLayout.current()
        val palette = V3Palette.from(context)
        val cards = mutableListOf<Pair<IllustListLayout, LinearLayout>>()

        fun render(selected: IllustListLayout) {
            cards.forEach { (layout, card) -> card.renderSelected(layout == selected, palette) }
        }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(20), context.dp(4), context.dp(20), context.dp(20))
            addView(context.label(context.getString(R.string.illust_list_layout), 20f, 700).apply {
                ViewCompat.setAccessibilityHeading(this, true)
            })
            addView(
                context.label(
                    context.getString(R.string.illust_list_layout_sheet_desc),
                    13f,
                    400,
                    context.color(ceui.pixiv.witstudio.R.color.wit_text_2),
                ).apply { lineHeightRatio(1.45f) },
                LinearLayout.LayoutParams(-1, -2).apply {
                    topMargin = context.dp(6)
                    bottomMargin = context.dp(18)
                },
            )
            IllustListLayout.values().toList().chunked(2).forEachIndexed { rowIndex, rowLayouts ->
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    isBaselineAligned = false
                }
                rowLayouts.forEachIndexed { index, layout ->
                    val card = optionCard(context, layout) { cardView, picked ->
                        val changed = picked != selected
                        if (changed) {
                            selected = picked
                            render(picked)
                            onPicked.onPicked(picked)
                        }
                        // 选中态先落一拍再收起，让人看到选的是哪张
                        if (motionEnabled() && changed) {
                            cardView.postDelayed({ sheet.dismiss() }, 180)
                        } else {
                            sheet.dismiss()
                        }
                    }
                    cards += layout to card
                    row.addView(card, LinearLayout.LayoutParams(0, -1, 1f).apply {
                        if (index > 0) marginStart = context.dp(12)
                    })
                }
                addView(row, LinearLayout.LayoutParams(-1, -2).apply {
                    if (rowIndex > 0) topMargin = context.dp(12)
                })
            }
        }
        render(selected)

        // 字体放大时内容可能超高，交给 sheet 内部滚动
        val scroll = NestedScrollView(context).apply {
            isFillViewport = true
            addView(content)
        }
        sheet.setSheetContent(scroll)
        sheet.show()
    }

    private fun optionCard(
        context: Context,
        layout: IllustListLayout,
        onClick: (View, IllustListLayout) -> Unit,
    ): LinearLayout {
        val title = context.getString(layout.titleRes)
        val desc = context.getString(layout.descRes)
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(8), context.dp(8), context.dp(8), context.dp(14))
            minimumHeight = context.dp(48)
            isClickable = true
            isFocusable = true
            contentDescription = "$title, $desc"
            addView(
                IllustLayoutPreviewView(context, layout).apply {
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LinearLayout.LayoutParams(-1, -2),
            )
            addView(
                context.label(title, 15f, 600),
                LinearLayout.LayoutParams(-1, -2).apply {
                    topMargin = context.dp(12)
                    marginStart = context.dp(6)
                    marginEnd = context.dp(6)
                },
            )
            addView(
                context.label(desc, 12f, 400, context.color(ceui.pixiv.witstudio.R.color.wit_text_2)),
                LinearLayout.LayoutParams(-1, -2).apply {
                    topMargin = context.dp(4)
                    marginStart = context.dp(6)
                    marginEnd = context.dp(6)
                },
            )
            pressScale()
            setOnClickListener { onClick(it, layout) }
            // 读屏按单选项朗读「已选中 / 未选中」
            ViewCompat.setAccessibilityDelegate(this, object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(
                    host: View,
                    info: AccessibilityNodeInfoCompat,
                ) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = RadioButton::class.java.name
                    info.isCheckable = true
                    info.isChecked = host.isSelected
                }
            })
        }
    }

    /** 选中 = 主题浅底 + 2dp 主题描边 + 预览角上的勾；未选中 = 普通 22dp 内容卡。 */
    private fun LinearLayout.renderSelected(selected: Boolean, palette: V3Palette) {
        isSelected = selected
        val radius = context.dpF(22f)
        val content = if (selected) {
            shape(
                radius,
                ColorUtils.compositeColors(palette.alpha10, palette.cardFill),
                palette.textAccent,
                context.dp(2),
            )
        } else {
            context.card(22)
        }
        background = context.ripple(content, shape(radius, android.graphics.Color.WHITE))
        (getChildAt(0) as? IllustLayoutPreviewView)?.checked = selected
    }
}

/**
 * 某种列表布局的示意图：圆角「屏幕」里按该布局排一组示例作品色块，底部渐隐暗示还能往下滚。
 *
 * 排布规则与真实列表一致（瀑布流落最短列、方格 1:1、齐行按比例分宽同行等高、单列全宽）。
 * 多列预览固定 3 列：小卡片里 2 列只排得下两三块，四种布局的差别反而看不出来。
 * 色块是主题色及邻近色相派生的几档渐变，不是固定色，换主题、日夜模式都跟着变。
 */
internal class IllustLayoutPreviewView(
    context: Context,
    private val layout: IllustListLayout,
) : View(context) {

    var checked: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val palette = V3Palette.from(context)

    @ColorInt
    private val screenColor = context.color(ceui.pixiv.witstudio.R.color.wit_bg)
    private val columns = 3

    private val inset = context.dpF(7f)
    private val gap = context.dpF(3f)
    private val tileRadius = context.dpF(4f)
    private val screenRadius = context.dpF(14f)

    private var tilePaints: List<Paint> = emptyList()
    private val fadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.primary }
    private val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dpF(1.8f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = palette.onPrimary
    }
    private val clipPath = Path()
    private val checkPath = Path()
    private val screen = RectF()
    private var tiles: List<RectF> = emptyList()

    /** 示例作品的高宽比，四种预览共用同一组。 */
    private val sampleRatios = floatArrayOf(
        1.42f, 0.74f, 1.0f, 1.6f, 0.68f, 1.25f, 0.9f, 1.5f,
        0.72f, 1.18f, 1.34f, 0.8f, 1.1f, 0.66f, 1.45f, 0.95f,
    )

    /**
     * 色块：从页面底色朝主题色（及其邻近色相）混合出的几档深浅，像一屏不同的作品。
     * 每块是「深 → 浅」两档的斜向渐变；夜间底色接近纯黑，混合比例整体抬高，否则一片发闷。
     */
    private val tonePairs: List<Pair<Int, Int>> = run {
        val hueShifts = floatArrayOf(0f, -22f, 16f, 34f, -10f, 24f, -32f, 8f)
        val strengths = floatArrayOf(0.50f, 0.30f, 0.64f, 0.40f, 0.56f, 0.26f, 0.46f, 0.60f)
        val lift = if (palette.isDark) 0.22f else 0f
        hueShifts.indices.map { i ->
            val hue = shiftHue(palette.primary, hueShifts[i])
            val strength = (strengths[i] + lift).coerceAtMost(0.9f)
            ColorUtils.blendARGB(screenColor, hue, strength) to
                    ColorUtils.blendARGB(screenColor, hue, strength * 0.62f)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(width, (width * 0.96f).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        screen.set(0f, 0f, w.toFloat(), h.toFloat())
        clipPath.reset()
        clipPath.addRoundRect(screen, screenRadius, screenRadius, Path.Direction.CW)
        tiles = layoutTiles(w - 2 * inset, h.toFloat())
        tilePaints = tiles.mapIndexed { index, rect ->
            val (deep, light) = tonePairs[index % tonePairs.size]
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    rect.left, rect.bottom, rect.right, rect.top,
                    deep, light, Shader.TileMode.CLAMP,
                )
            }
        }
        fadePaint.shader = LinearGradient(
            0f, h * 0.68f, 0f, h.toFloat(),
            ColorUtils.setAlphaComponent(screenColor, 0), screenColor,
            Shader.TileMode.CLAMP,
        )
        val size = context.dpF(20f)
        val cx = w - inset - size / 2
        val cy = inset + size / 2
        checkPath.reset()
        checkPath.moveTo(cx - size * 0.22f, cy + size * 0.01f)
        checkPath.lineTo(cx - size * 0.05f, cy + size * 0.17f)
        checkPath.lineTo(cx + size * 0.24f, cy - size * 0.15f)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.withClip(clipPath) {
            drawColor(screenColor)
            tiles.forEachIndexed { index, rect ->
                drawRoundRect(rect, tileRadius, tileRadius, tilePaints[index])
            }
            drawRect(0f, height * 0.68f, width.toFloat(), height.toFloat(), fadePaint)
        }
        if (checked) {
            val size = context.dpF(20f)
            canvas.drawCircle(width - inset - size / 2, inset + size / 2, size / 2, badgePaint)
            canvas.drawPath(checkPath, checkPaint)
        }
    }

    /** 按 [layout] 排出色块，直到铺过预览底边；坐标已含 [inset]。 */
    private fun layoutTiles(contentWidth: Float, height: Float): List<RectF> {
        if (contentWidth <= 0f) return emptyList()
        val result = mutableListOf<RectF>()
        val columnWidth = (contentWidth - gap * (columns - 1)) / columns
        fun ratio(i: Int) = sampleRatios[i % sampleRatios.size]
        var i = 0
        when (layout) {
            IllustListLayout.MASONRY, IllustListLayout.GRID -> {
                val bottoms = FloatArray(columns) { inset }
                while (bottoms.any { it < height } && i < 64) {
                    val column = bottoms.indices.minByOrNull { bottoms[it] } ?: 0
                    val left = inset + column * (columnWidth + gap)
                    val tileHeight =
                        if (layout == IllustListLayout.GRID) columnWidth else columnWidth * ratio(i)
                    result += RectF(left, bottoms[column], left + columnWidth, bottoms[column] + tileHeight)
                    bottoms[column] += tileHeight + gap
                    i++
                }
            }
            IllustListLayout.JUSTIFIED -> {
                // 与真实列表同一套分行（packJustifiedRows / 同一目标行高系数），预览才不会和实际效果两样。
                // 每块左右各让 gap/2：把内容宽补上一个 gap，行首行尾那半格正好落进 inset 里
                val count = 40
                val aspects = FloatArray(count) { 1f / ratio(it) }
                val spans = packJustifiedRows(
                    aspects,
                    BooleanArray(count),
                    (contentWidth + gap).toInt(),
                    gap.toInt(),
                    columnWidth * JUSTIFIED_ROW_HEIGHT_FACTOR,
                )
                var top = inset
                var left = inset - gap / 2
                var rowWidth = 0
                var rowHeight = 0f
                while (i < count && top < height) {
                    val tileWidth = spans[i] - gap.toInt()
                    if (rowWidth == 0) rowHeight = tileWidth / aspects[i]
                    result += RectF(left + gap / 2, top, left + gap / 2 + tileWidth, top + rowHeight)
                    left += spans[i]
                    rowWidth += spans[i]
                    i++
                    if (rowWidth >= (contentWidth + gap).toInt()) {
                        top += rowHeight + gap
                        left = inset - gap / 2
                        rowWidth = 0
                    }
                }
            }
            IllustListLayout.SINGLE_COLUMN -> {
                var top = inset
                while (top < height && i < 64) {
                    // 预览里压扁一点，才看得出「一张接一张」而不是一整块
                    val tileHeight = contentWidth * (ratio(i) * 0.5f).coerceIn(0.36f, 0.62f)
                    result += RectF(inset, top, inset + contentWidth, top + tileHeight)
                    top += tileHeight + gap
                    i++
                }
            }
        }
        return result
    }

    private fun shiftHue(@ColorInt color: Int, degrees: Float): Int {
        if (degrees == 0f) return color
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color, hsl)
        hsl[0] = (hsl[0] + degrees + 360f) % 360f
        return ColorUtils.HSLToColor(hsl)
    }
}
