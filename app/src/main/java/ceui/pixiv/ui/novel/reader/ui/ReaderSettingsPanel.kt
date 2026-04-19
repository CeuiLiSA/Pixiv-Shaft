package ceui.pixiv.ui.novel.reader.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import ceui.pixiv.ui.novel.reader.model.FlipMode
import ceui.pixiv.ui.novel.reader.model.ImagePlacement
import ceui.pixiv.ui.novel.reader.model.ImageScaleMode
import ceui.pixiv.ui.novel.reader.model.ScreenOrientation
import ceui.pixiv.ui.novel.reader.paginate.TypefaceProvider
import ceui.pixiv.ui.novel.reader.settings.PresetFonts
import ceui.pixiv.ui.novel.reader.settings.ReaderFont
import ceui.pixiv.ui.novel.reader.settings.ReaderSettings
import ceui.pixiv.ui.novel.reader.settings.ReaderTheme
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * BottomSheet panel exposing every live-tunable reader setting: typography,
 * theme, flip mode, screen, and image handling. Each control writes directly
 * to [ReaderSettings]; change-events propagate via [ReaderSettings.changes] so
 * the host Fragment re-paginates / re-themes without tight coupling here.
 *
 * Built programmatically (vs. massive XML) so adding new rows is one line in
 * [buildTypographySection] / [buildThemeSection] / etc.
 */
class ReaderSettingsPanel : BottomSheetDialogFragment() {

    private val refreshers = mutableListOf<() -> Unit>()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return BottomSheetDialog(requireContext()).apply {
            behavior.skipCollapsed = true
            behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val ctx = requireContext()
        val scroll = ScrollView(ctx).apply {
            isFillViewport = true
            setPadding(0, dp(ctx, 8f), 0, dp(ctx, 24f))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        scroll.addView(root)

        root.addView(handleIndicator(ctx))
        root.addView(titleRow(ctx, "阅读器设置"))

        root.addView(buildTypographySection(ctx))
        root.addView(buildThemeSection(ctx))
        root.addView(buildFlipSection(ctx))
        root.addView(buildScreenSection(ctx))
        root.addView(buildImageSection(ctx))

        refreshers.forEach { it() }
        return scroll
    }

    // ---- Sections ---------------------------------------------------------

    private fun buildTypographySection(ctx: Context): View {
        val section = sectionContainer(ctx, "排版")
        section.addView(
            intSliderRow(ctx, label = "字号", min = ReaderSettings.FONT_SIZE_MIN, max = ReaderSettings.FONT_SIZE_MAX,
                initial = ReaderSettings.fontSizeSp, suffix = "sp") { ReaderSettings.fontSizeSp = it },
        )
        section.addView(
            floatSliderRow(ctx, label = "行距", min = 1.0f, max = 2.8f, steps = 18,
                initial = ReaderSettings.lineSpacing) { ReaderSettings.lineSpacing = it },
        )
        section.addView(
            floatSliderRow(ctx, label = "段间距", min = 0f, max = 2.5f, steps = 25,
                initial = ReaderSettings.paragraphSpacingLines, suffix = "行") { ReaderSettings.paragraphSpacingLines = it },
        )
        section.addView(
            intSliderRow(ctx, label = "左右边距", min = 0, max = 64,
                initial = ReaderSettings.horizontalMarginDp, suffix = "dp") { ReaderSettings.horizontalMarginDp = it },
        )
        section.addView(
            intSliderRow(ctx, label = "上下边距", min = 0, max = 96,
                initial = ReaderSettings.verticalMarginDp, suffix = "dp") { ReaderSettings.verticalMarginDp = it },
        )
        section.addView(
            intSegmentedRow(ctx, label = "首行缩进", options = listOf("无" to 0, "1字" to 1, "2字" to 2, "3字" to 3, "4字" to 4),
                current = ReaderSettings.firstLineIndent) { ReaderSettings.firstLineIndent = it },
        )
        section.addView(
            floatSliderRow(ctx, label = "字间距", min = -0.05f, max = 0.25f, steps = 30,
                initial = ReaderSettings.letterSpacing, digits = 2) { ReaderSettings.letterSpacing = it },
        )
        section.addView(
            switchRow(ctx, label = "加粗", current = ReaderSettings.boldText) { ReaderSettings.boldText = it },
        )
        section.addView(buildFontPickerRow(ctx))
        section.addView(
            intSliderRow(ctx, label = "字重", min = 100, max = 900,
                initial = ReaderSettings.fontWeight) { ReaderSettings.fontWeight = it },
        )
        return section
    }

    private fun buildThemeSection(ctx: Context): View {
        val section = sectionContainer(ctx, "主题")
        section.addView(buildThemePickerRow(ctx))
        section.addView(
            switchRow(ctx, label = "跟随系统暗色", current = ReaderSettings.followSystemDarkMode) {
                ReaderSettings.followSystemDarkMode = it
            },
        )
        section.addView(
            switchRow(ctx, label = "屏幕亮度跟随系统", current = ReaderSettings.useSystemBrightness) {
                ReaderSettings.useSystemBrightness = it
            },
        )
        section.addView(
            floatSliderRow(ctx, label = "自定义亮度", min = 0.01f, max = 1f, steps = 99,
                initial = ReaderSettings.customBrightness, digits = 2) { ReaderSettings.customBrightness = it },
        )
        section.addView(
            floatSliderRow(ctx, label = "暖色滤镜", min = 0f, max = 0.6f, steps = 60,
                initial = ReaderSettings.warmFilterStrength, digits = 2) { ReaderSettings.warmFilterStrength = it },
        )
        return section
    }

    private fun buildFlipSection(ctx: Context): View {
        val section = sectionContainer(ctx, "翻页")
        section.addView(
            enumSegmentedRow(ctx, label = "翻页动画",
                options = listOf("仿真" to FlipMode.Simulation, "覆盖" to FlipMode.Cover, "平移" to FlipMode.Slide, "无" to FlipMode.None),
                current = ReaderSettings.flipMode) { ReaderSettings.flipMode = it },
        )
        section.addView(
            switchRow(ctx, label = "音量键翻页", current = ReaderSettings.volumeKeyFlip) {
                ReaderSettings.volumeKeyFlip = it
            },
        )
        section.addView(
            switchRow(ctx, label = "反转左右点击区", current = ReaderSettings.tapZoneReversed) {
                ReaderSettings.tapZoneReversed = it
            },
        )
        section.addView(
            intSliderRow(ctx, label = "自动翻页间隔", min = 5, max = 60,
                initial = ReaderSettings.autoPageIntervalSec, suffix = "秒") { ReaderSettings.autoPageIntervalSec = it },
        )
        return section
    }

    private fun buildScreenSection(ctx: Context): View {
        val section = sectionContainer(ctx, "屏幕")
        section.addView(
            enumSegmentedRow(ctx, label = "方向",
                options = listOf("自动" to ScreenOrientation.Auto, "竖屏" to ScreenOrientation.Portrait, "横屏" to ScreenOrientation.Landscape),
                current = ReaderSettings.screenOrientation) { ReaderSettings.screenOrientation = it },
        )
        section.addView(
            switchRow(ctx, label = "沉浸式（隐藏状态栏）", current = ReaderSettings.immersive) {
                ReaderSettings.immersive = it
            },
        )
        section.addView(
            switchRow(ctx, label = "屏幕常亮", current = ReaderSettings.keepScreenOn) {
                ReaderSettings.keepScreenOn = it
            },
        )
        section.addView(
            switchRow(ctx, label = "显示顶部进度条", current = ReaderSettings.showTopProgress) {
                ReaderSettings.showTopProgress = it
            },
        )
        section.addView(
            switchRow(ctx, label = "显示底部进度条", current = ReaderSettings.showBottomProgress) {
                ReaderSettings.showBottomProgress = it
            },
        )
        section.addView(
            switchRow(ctx, label = "锁定触控（防误触）", current = ReaderSettings.touchLocked) {
                ReaderSettings.touchLocked = it
            },
        )
        section.addView(
            intSliderRow(ctx, label = "护眼提醒", min = 0, max = 120,
                initial = ReaderSettings.eyeBreakReminderMinutes, suffix = "分钟") { ReaderSettings.eyeBreakReminderMinutes = it },
        )
        return section
    }

    private fun buildImageSection(ctx: Context): View {
        val section = sectionContainer(ctx, "图片")
        section.addView(
            enumSegmentedRow(ctx, label = "垂直位置",
                options = listOf("顶部" to ImagePlacement.Top, "居中" to ImagePlacement.Center, "底部" to ImagePlacement.Bottom),
                current = ReaderSettings.imagePlacement) { ReaderSettings.imagePlacement = it },
        )
        section.addView(
            enumSegmentedRow(ctx, label = "缩放模式",
                options = listOf("适应" to ImageScaleMode.Fit, "填充" to ImageScaleMode.Fill, "原始" to ImageScaleMode.Original),
                current = ReaderSettings.imageScaleMode) { ReaderSettings.imageScaleMode = it },
        )
        section.addView(
            intSliderRow(ctx, label = "预加载图片", min = 0, max = 8,
                initial = ReaderSettings.preloadImageAhead, suffix = "页") { ReaderSettings.preloadImageAhead = it },
        )
        return section
    }

    // ---- Row builders -----------------------------------------------------

    private fun sectionContainer(ctx: Context, title: String): LinearLayout {
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 16f), dp(ctx, 12f), dp(ctx, 16f), dp(ctx, 12f))
        }
        val header = TextView(ctx).apply {
            text = title
            setTextColor(0xFF5B6EFF.toInt())
            setTypeface(typeface, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(ctx, 8f), 0, dp(ctx, 6f))
        }
        container.addView(header)
        return container
    }

    private fun handleIndicator(ctx: Context): View {
        val holder = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(ctx, 24f),
            )
        }
        val indicator = View(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(ctx, 2f).toFloat()
                setColor(0x33000000)
            }
            layoutParams = FrameLayout.LayoutParams(dp(ctx, 40f), dp(ctx, 4f), Gravity.CENTER)
        }
        holder.addView(indicator)
        return holder
    }

    private fun titleRow(ctx: Context, title: String): View {
        return TextView(ctx).apply {
            text = title
            setTextColor(Color.BLACK)
            setTypeface(typeface, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setPadding(dp(ctx, 16f), dp(ctx, 4f), dp(ctx, 16f), dp(ctx, 8f))
        }
    }

    private fun intSliderRow(
        ctx: Context,
        label: String,
        min: Int,
        max: Int,
        initial: Int,
        suffix: String = "",
        onChange: (Int) -> Unit,
    ): View {
        val row = horizontalRow(ctx)
        val labelView = rowLabel(ctx, label)
        val seek = SeekBar(ctx).apply {
            this.max = max - min
            this.progress = (initial - min).coerceIn(0, this.max)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply { weight = 1f }
        }
        val valueView = TextView(ctx).apply {
            minWidth = dp(ctx, 52f)
            gravity = Gravity.END
            setPadding(dp(ctx, 8f), 0, 0, 0)
        }
        fun render() {
            valueView.text = "${seek.progress + min}$suffix"
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, progress: Int, fromUser: Boolean) {
                render()
                if (fromUser) onChange(seek.progress + min)
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        row.addView(labelView)
        row.addView(seek)
        row.addView(valueView)
        refreshers += ::render
        render()
        return row
    }

    private fun floatSliderRow(
        ctx: Context,
        label: String,
        min: Float,
        max: Float,
        steps: Int,
        initial: Float,
        suffix: String = "",
        digits: Int = 1,
        onChange: (Float) -> Unit,
    ): View {
        val row = horizontalRow(ctx)
        val labelView = rowLabel(ctx, label)
        val seek = SeekBar(ctx).apply {
            this.max = steps
            val normalized = ((initial - min) / (max - min)).coerceIn(0f, 1f)
            this.progress = (normalized * steps).toInt()
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply { weight = 1f }
        }
        val valueView = TextView(ctx).apply {
            minWidth = dp(ctx, 52f)
            gravity = Gravity.END
            setPadding(dp(ctx, 8f), 0, 0, 0)
        }
        fun value(): Float = min + (seek.progress.toFloat() / steps) * (max - min)
        fun render() {
            val format = if (digits == 0) "%.0f" else "%.${digits}f"
            valueView.text = String.format(format, value()) + suffix
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, progress: Int, fromUser: Boolean) {
                render()
                if (fromUser) onChange(value())
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        row.addView(labelView)
        row.addView(seek)
        row.addView(valueView)
        refreshers += ::render
        render()
        return row
    }

    private fun switchRow(ctx: Context, label: String, current: Boolean, onChange: (Boolean) -> Unit): View {
        val row = horizontalRow(ctx)
        val labelView = rowLabel(ctx, label).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply { weight = 1f }
        }
        val switch = Switch(ctx).apply {
            isChecked = current
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }
        row.addView(labelView)
        row.addView(switch)
        return row
    }

    private fun intSegmentedRow(
        ctx: Context,
        label: String,
        options: List<Pair<String, Int>>,
        current: Int,
        onChange: (Int) -> Unit,
    ): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(ctx, 6f), 0, dp(ctx, 6f))
        }
        row.addView(rowLabel(ctx, label))
        row.addView(buildSegmentedButtons(ctx, options, current, onChange))
        return row
    }

    private fun <T> enumSegmentedRow(
        ctx: Context,
        label: String,
        options: List<Pair<String, T>>,
        current: T,
        onChange: (T) -> Unit,
    ): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(ctx, 6f), 0, dp(ctx, 6f))
        }
        row.addView(rowLabel(ctx, label))
        row.addView(buildSegmentedButtons(ctx, options, current, onChange))
        return row
    }

    private fun <T> buildSegmentedButtons(
        ctx: Context,
        options: List<Pair<String, T>>,
        current: T,
        onChange: (T) -> Unit,
    ): LinearLayout {
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 38f))
            setPadding(0, dp(ctx, 4f), 0, 0)
        }
        val buttons = options.map { (text, value) ->
            Button(ctx).apply {
                this.text = text
                isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT).apply {
                    weight = 1f
                    marginEnd = dp(ctx, 4f)
                }
                tag = value
                setOnClickListener {
                    @Suppress("UNCHECKED_CAST")
                    val v = tag as T
                    onChange(v)
                    applySegmentSelection(container, v)
                }
            }
        }
        buttons.forEach { container.addView(it) }
        applySegmentSelection(container, current)
        return container
    }

    private fun <T> applySegmentSelection(container: LinearLayout, selected: T) {
        for (i in 0 until container.childCount) {
            val b = container.getChildAt(i) as Button
            val isSelected = b.tag == selected
            b.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(container.context, 6f).toFloat()
                setColor(if (isSelected) 0xFF5B6EFF.toInt() else 0x1A000000)
            }
            b.setTextColor(if (isSelected) Color.WHITE else Color.BLACK)
        }
    }

    // ---- Theme picker -----------------------------------------------------

    private fun buildThemePickerRow(ctx: Context): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(ctx, 6f), 0, dp(ctx, 6f))
        }
        row.addView(rowLabel(ctx, "主题"))
        val scroll = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false }
        val inner = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        scroll.addView(inner)
        val size = dp(ctx, 56f)
        val selectedId = ReaderSettings.themeId
        val items = mutableListOf<View>()
        ReaderTheme.PRESETS.forEach { theme ->
            val swatch = FrameLayout(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(size, size + dp(ctx, 18f)).apply {
                    marginEnd = dp(ctx, 12f)
                }
            }
            val circle = View(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(size, size)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(theme.backgroundColor)
                    setStroke(
                        dp(ctx, 2f),
                        if (theme.id == selectedId) 0xFF5B6EFF.toInt() else 0x22000000,
                    )
                }
            }
            val text = TextView(ctx).apply {
                this.text = theme.displayName
                setTextColor(Color.BLACK)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
                )
            }
            swatch.addView(circle)
            swatch.addView(text)
            swatch.setOnClickListener {
                ReaderSettings.themeId = theme.id
                // Repaint all swatches with new selection border.
                items.forEach { v ->
                    val c = (v as FrameLayout).getChildAt(0)
                    val bg = c.background as GradientDrawable
                    val id = v.tag as String
                    bg.setStroke(dp(ctx, 2f), if (id == theme.id) 0xFF5B6EFF.toInt() else 0x22000000)
                }
            }
            swatch.tag = theme.id
            items += swatch
            inner.addView(swatch)
        }
        row.addView(scroll)
        return row
    }

    // ---- Font picker ------------------------------------------------------

    private fun buildFontPickerRow(ctx: Context): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(ctx, 6f), 0, dp(ctx, 6f))
        }
        row.addView(rowLabel(ctx, "字体"))
        val scroll = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false }
        val inner = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        scroll.addView(inner)
        // Custom fonts (when DAO is wired) are appended after the built-ins.
        val fonts: List<ReaderFont> = PresetFonts.BUILT_IN
        val items = mutableListOf<View>()
        fonts.forEach { font ->
            val button = TextView(ctx).apply {
                text = font.displayName
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(dp(ctx, 14f), dp(ctx, 8f), dp(ctx, 14f), dp(ctx, 8f))
                typeface = TypefaceProvider.resolve(ctx, font.id, 400, false)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(ctx, 8f) }
                tag = font.id
            }
            applyFontButtonBg(button, ReaderSettings.fontId == font.id)
            button.setOnClickListener {
                ReaderSettings.fontId = font.id
                items.forEach { v ->
                    val tv = v as TextView
                    applyFontButtonBg(tv, tv.tag == font.id)
                }
            }
            items += button
            inner.addView(button)
        }
        row.addView(scroll)
        return row
    }

    private fun applyFontButtonBg(view: TextView, selected: Boolean) {
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(view.context, 6f).toFloat()
            setColor(if (selected) 0xFF5B6EFF.toInt() else 0x1A000000)
        }
        view.setTextColor(if (selected) Color.WHITE else Color.BLACK)
    }

    // ---- Primitives -------------------------------------------------------

    private fun horizontalRow(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(ctx, 8f), 0, dp(ctx, 8f))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun rowLabel(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        setTextColor(Color.BLACK)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        minWidth = dp(ctx, 88f)
    }

    private fun dp(ctx: Context, value: Float): Int =
        (value * ctx.resources.displayMetrics.density).toInt()

    companion object {
        const val TAG = "ReaderSettingsPanel"
    }
}
