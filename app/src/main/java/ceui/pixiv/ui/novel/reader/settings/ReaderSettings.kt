package ceui.pixiv.ui.novel.reader.settings

import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Paint
import android.text.TextPaint
import android.util.TypedValue
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import ceui.lisa.activities.Shaft
import ceui.pixiv.ui.novel.reader.model.FlipMode
import ceui.pixiv.ui.novel.reader.model.ReadingDirection
import ceui.pixiv.ui.novel.reader.model.ImagePlacement
import ceui.pixiv.ui.novel.reader.model.ImageScaleMode
import ceui.pixiv.ui.novel.reader.model.NovelIllustSource
import ceui.pixiv.ui.novel.reader.model.ScreenOrientation
import ceui.pixiv.ui.novel.reader.paginate.TextMeasurer
import ceui.pixiv.ui.novel.reader.paginate.TypefaceProvider
import com.tencent.mmkv.MMKV

/**
 * Persistent reader settings. Backed by its own MMKV instance so it stays
 * isolated from the rest of the app. All mutations publish to [changes] so
 * UI can react immediately and the paginator can re-layout.
 */
object ReaderSettings {

    private const val MMKV_ID = "novel_reader_v3"

    private val store: MMKV by lazy { MMKV.mmkvWithID(MMKV_ID) }

    private val _changes = MutableLiveData<ChangeEvent>()
    val changes: LiveData<ChangeEvent> = _changes

    private fun emit(event: ChangeEvent) {
        _changes.postValue(event)
    }

    sealed class ChangeEvent {
        object Layout : ChangeEvent()
        object Theme : ChangeEvent()
        object Brightness : ChangeEvent()
        object Flip : ChangeEvent()
        object Tts : ChangeEvent()
        object Interaction : ChangeEvent()
        object Image : ChangeEvent()
        object Reminder : ChangeEvent()
        object IllustMix : ChangeEvent()
    }

    // ---------- Typography ----------
    var fontSizeSp: Int
        get() = store.decodeInt(K_FONT_SIZE, 18).coerceIn(FONT_SIZE_MIN, FONT_SIZE_MAX)
        set(value) {
            store.encode(K_FONT_SIZE, value.coerceIn(FONT_SIZE_MIN, FONT_SIZE_MAX))
            emit(ChangeEvent.Layout)
        }

    var lineSpacing: Float
        get() = store.decodeFloat(K_LINE_SPACING, 1.6f).coerceIn(1.0f, 2.8f)
        set(value) {
            store.encode(K_LINE_SPACING, value.coerceIn(1.0f, 2.8f))
            emit(ChangeEvent.Layout)
        }

    var paragraphSpacingLines: Float
        get() {
            if (!store.containsKey(K_PARAGRAPH_SPACING_LINES)) {
                // The old value counted font bounding boxes, not rendered body lines.
                // Convert once using the saved typography; subsequent font/line-spacing
                // changes must use the new unit rather than re-convert the old value.
                // A user who only changed line spacing/font still used the old implicit
                // 0.8 default. Only a completely empty settings store is a fresh install.
                val lines = if (store.count() > 0L) {
                    val context = Shaft.getContext()
                    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                        typeface = TypefaceProvider.resolve(context, fontId, fontWeight, boldText)
                        textSize = TypedValue.applyDimension(
                            TypedValue.COMPLEX_UNIT_SP, fontSizeSp.toFloat(),
                            context.resources.displayMetrics,
                        )
                        isFakeBoldText = boldText && fontWeight < 500
                    }
                    val fm = paint.fontMetrics
                    store.decodeFloat(K_PARAGRAPH_SPACING, 0.8f).coerceIn(0f, 2.5f) *
                        (fm.bottom - fm.top) / TextMeasurer.lineHeightPx(paint, lineSpacing)
                } else {
                    // Approximately the old default at the default 1.6 body line spacing.
                    0.6f
                }
                store.encode(K_PARAGRAPH_SPACING_LINES, lines.coerceIn(0f, 2.5f))
            }
            return store.decodeFloat(K_PARAGRAPH_SPACING_LINES, 0.6f).coerceIn(0f, 2.5f)
        }
        set(value) {
            store.encode(K_PARAGRAPH_SPACING_LINES, value.coerceIn(0f, 2.5f))
            emit(ChangeEvent.Layout)
        }

    var horizontalMarginDp: Int
        get() = store.decodeInt(K_H_MARGIN, 20).coerceIn(0, 64)
        set(value) {
            store.encode(K_H_MARGIN, value.coerceIn(0, 64))
            emit(ChangeEvent.Layout)
        }

    var verticalMarginDp: Int
        get() = store.decodeInt(K_V_MARGIN, 24).coerceIn(0, 96)
        set(value) {
            store.encode(K_V_MARGIN, value.coerceIn(0, 96))
            emit(ChangeEvent.Layout)
        }

    var firstLineIndent: Int
        get() = store.decodeInt(K_INDENT, 2).coerceIn(0, 4)
        set(value) {
            store.encode(K_INDENT, value.coerceIn(0, 4))
            emit(ChangeEvent.Layout)
        }

    var letterSpacing: Float
        get() = store.decodeFloat(K_LETTER_SPACING, 0f).coerceIn(-0.05f, 0.25f)
        set(value) {
            store.encode(K_LETTER_SPACING, value.coerceIn(-0.05f, 0.25f))
            emit(ChangeEvent.Layout)
        }

    var boldText: Boolean
        get() = store.decodeBool(K_BOLD, false)
        set(value) {
            store.encode(K_BOLD, value)
            emit(ChangeEvent.Layout)
        }

    var fontId: String
        get() = store.decodeString(K_FONT_ID, PresetFonts.SYSTEM.id) ?: PresetFonts.SYSTEM.id
        set(value) {
            store.encode(K_FONT_ID, value)
            emit(ChangeEvent.Layout)
        }

    var fontWeight: Int
        get() = store.decodeInt(K_FONT_WEIGHT, 400).coerceIn(100, 900)
        set(value) {
            store.encode(K_FONT_WEIGHT, value.coerceIn(100, 900))
            emit(ChangeEvent.Layout)
        }

    // ---------- Theme ----------
    var themeId: String
        get() = store.decodeString(K_THEME_ID, ReaderTheme.KRAFT.id) ?: ReaderTheme.KRAFT.id
        set(value) {
            store.encode(K_THEME_ID, value)
            emit(ChangeEvent.Theme)
        }

    var customThemeId: Int
        get() = store.decodeInt(K_CUSTOM_THEME_ID, 0)
        set(value) {
            store.encode(K_CUSTOM_THEME_ID, value)
            emit(ChangeEvent.Theme)
        }

    var followSystemDarkMode: Boolean
        get() = store.decodeBool(K_FOLLOW_DARK, false)
        set(value) {
            val was = followSystemDarkMode
            // 值没变就直接返回：不写、不 emit。面板回刷开关时会用 isChecked 回调回来，
            // 少了这道闸就会多一次无意义的 Theme 事件。
            if (was == value) return
            writeFollowSilently(value)
            // 打开跟随的那一刻把用户当前配色记成浅色记忆；开关本身不碰 themeId。
            if (value) lightThemeMemoryId = lightPickOrFallback(themeId)
            emit(ChangeEvent.Theme)
        }

    /** 静默写跟随标志（不 emit）。同一次用户操作要改多个字段时，emit 统一放到最后。 */
    private fun writeFollowSilently(value: Boolean) {
        store.encode(K_FOLLOW_DARK, value)
    }

    /**
     * 跟随系统暗色时，系统处于浅色侧要用的「浅色记忆」配色。持久化在独立 MMKV key
     * `r_light_theme_memory`，与 [themeId] 相互独立：拨动跟随开关只决定「用不用
     * themeId」，不修改它。
     *
     * 未记过（旧版本存量）时以当前 [themeId] 兜底，行为与不开跟随一致。
     */
    var lightThemeMemoryId: String
        get() = lightPickOrFallback(store.decodeString(K_LIGHT_THEME_MEMORY, null) ?: themeId)
        private set(value) {
            store.encode(K_LIGHT_THEME_MEMORY, value)
        }

    /** 有效浅色配色：解析出深色预设（夜间 / 炭黑）或无效 id 时降级牛皮纸。 */
    private fun lightPickOrFallback(id: String?): String =
        if (id != null && ReaderTheme.findPresetById(id)?.isDark == false) id else ReaderTheme.KRAFT.id

    /**
     * 系统当前是否处于深色。
     *
     * **必须读 [Resources.getSystem()]，不能读任何 Context 的 resources。** AppCompat 的
     * `setDefaultNightMode` / `setLocalNightMode` 会把日夜位写进 Activity（以及 Application，
     * 见 `AppLocales.localeOnlyOverride` 的说明）的 Resources —— 读 Context 拿到的是「app 主题
     * 模式」的结果，不是系统设置：应用级主题模式选「深色」时系统明明是浅色，Context 的 uiMode
     * 也是 NIGHT_YES，阅读器就会错误地切成夜间；反之选「浅色」时永远不跟随。
     */
    private fun isSystemDark(): Boolean =
        (Resources.getSystem().configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    /**
     * 有效阅读主题：不开跟随用用户选的 [themeId]；开了则由系统决定 ——
     * 系统深色 → 夜间，系统浅色 → [lightThemeMemoryId]。
     */
    fun effectiveTheme(): ReaderTheme {
        val preset = when {
            !followSystemDarkMode -> ReaderTheme.findPresetById(themeId) ?: ReaderTheme.KRAFT
            isSystemDark() -> ReaderTheme.NIGHT
            else -> ReaderTheme.findPresetById(lightThemeMemoryId) ?: ReaderTheme.KRAFT
        }
        val color = customTextColor(preset.id) ?: return preset
        return preset.copy(textColor = color, chapterTitleColor = color)
    }

    /** 每种阅读配色独立记忆字色，避免浅色背景的深字带入夜间模式。 */
    fun customTextColor(presetId: String): Int? {
        val key = K_TEXT_COLOR_PREFIX + presetId
        return if (store.containsKey(key)) store.decodeInt(key, 0) else null
    }

    /** 传入打开取色器时的配色 id，系统日夜切换后也不会误写另一套配色。null 恢复默认。 */
    fun setTextColor(presetId: String, color: Int?) {
        val key = K_TEXT_COLOR_PREFIX + presetId
        if (color == null) store.removeValueForKey(key) else store.encode(key, color or 0xFF000000.toInt())
        emit(ChangeEvent.Theme)
    }

    /** 这次选择会不会真的上屏：只有「跟随开启 + 系统浅色 + 选的是浅色预设」才会。 */
    private fun pickTakesEffect(id: String): Boolean =
        followSystemDarkMode && !isSystemDark() &&
            ReaderTheme.findPresetById(id)?.isDark == false

    /**
     * 用户显式选配色的唯一入口（设置面板色块、底栏日夜快捷键都走这里）。
     *
     * - 选择**会**生效时（跟随开启 + 系统浅色 + 浅色预设）：留在跟随内，同步刷新浅色记忆；
     * - 选择**不会**生效时（跟随开启但系统深色，或选了夜间 / 炭黑）：视为一次显式手动覆盖，
     *   退出跟随，让这次选择立刻上屏 —— 否则色环会回弹到生效主题，控件变成「点了没反应」。
     *
     * 退出跟随走 [writeFollowSilently]：emit 统一由最后的 [themeId] 赋值发出，
     * 否则会先用旧 themeId 渲染一帧。
     */
    fun onThemePicked(id: String) {
        // 点的是当前已经生效的配色（例如跟随 + 系统深色下点「夜间」）：不该顺手关掉跟随，
        // 也不该动记忆，按无操作处理。
        if (id == effectiveTheme().id) return
        val takesEffect = pickTakesEffect(id)
        if (followSystemDarkMode && !takesEffect) writeFollowSilently(false)
        if (takesEffect) lightThemeMemoryId = id
        themeId = id
    }

    var backgroundImagePath: String?
        get() = store.decodeString(K_BG_IMAGE, null)
        set(value) {
            if (value == null) store.removeValueForKey(K_BG_IMAGE) else store.encode(K_BG_IMAGE, value)
            emit(ChangeEvent.Theme)
        }

    // ---------- Brightness ----------
    var useSystemBrightness: Boolean
        get() = store.decodeBool(K_SYS_BRIGHTNESS, true)
        set(value) {
            store.encode(K_SYS_BRIGHTNESS, value)
            emit(ChangeEvent.Brightness)
        }

    var customBrightness: Float
        get() = store.decodeFloat(K_BRIGHTNESS, 0.5f).coerceIn(0.01f, 1f)
        set(value) {
            store.encode(K_BRIGHTNESS, value.coerceIn(0.01f, 1f))
            emit(ChangeEvent.Brightness)
        }

    var warmFilterStrength: Float
        get() = store.decodeFloat(K_WARM_FILTER, 0f).coerceIn(0f, 0.6f)
        set(value) {
            store.encode(K_WARM_FILTER, value.coerceIn(0f, 0.6f))
            emit(ChangeEvent.Brightness)
        }

    // ---------- Reading direction + Flip ----------
    var readingDirection: ReadingDirection
        get() = runCatching {
            ReadingDirection.valueOf(store.decodeString(K_READING_DIR, ReadingDirection.Horizontal.name) ?: ReadingDirection.Horizontal.name)
        }.getOrDefault(ReadingDirection.Horizontal)
        set(value) {
            store.encode(K_READING_DIR, value.name)
            emit(ChangeEvent.Flip)
        }

    var flipMode: FlipMode
        get() = runCatching {
            FlipMode.valueOf(store.decodeString(K_FLIP_MODE, FlipMode.Simulation.name) ?: FlipMode.Simulation.name)
        }.getOrDefault(FlipMode.Simulation)
        set(value) {
            store.encode(K_FLIP_MODE, value.name)
            emit(ChangeEvent.Flip)
        }

    var volumeKeyFlip: Boolean
        get() = store.decodeBool(K_VOLUME_FLIP, true)
        set(value) {
            store.encode(K_VOLUME_FLIP, value)
            emit(ChangeEvent.Interaction)
        }

    var tapZoneReversed: Boolean
        get() = store.decodeBool(K_TAP_REVERSED, false)
        set(value) {
            store.encode(K_TAP_REVERSED, value)
            emit(ChangeEvent.Interaction)
        }

    var autoPageIntervalSec: Int
        get() = store.decodeInt(K_AUTO_PAGE_INTERVAL, 15).coerceIn(5, 60)
        set(value) {
            store.encode(K_AUTO_PAGE_INTERVAL, value.coerceIn(5, 60))
            emit(ChangeEvent.Interaction)
        }

    // ---------- Display ----------
    var screenOrientation: ScreenOrientation
        get() = runCatching {
            ScreenOrientation.valueOf(store.decodeString(K_ORIENTATION, ScreenOrientation.Auto.name) ?: ScreenOrientation.Auto.name)
        }.getOrDefault(ScreenOrientation.Auto)
        set(value) {
            store.encode(K_ORIENTATION, value.name)
            emit(ChangeEvent.Interaction)
        }

    var immersive: Boolean
        get() = store.decodeBool(K_IMMERSIVE, true)
        set(value) {
            store.encode(K_IMMERSIVE, value)
            emit(ChangeEvent.Interaction)
        }

    var keepScreenOn: Boolean
        get() = store.decodeBool(K_KEEP_SCREEN_ON, true)
        set(value) {
            store.encode(K_KEEP_SCREEN_ON, value)
            emit(ChangeEvent.Interaction)
        }

    var showTopProgress: Boolean
        get() = store.decodeBool(K_SHOW_TOP_PROGRESS, true)
        set(value) {
            store.encode(K_SHOW_TOP_PROGRESS, value)
            emit(ChangeEvent.Layout)
        }

    var showBottomProgress: Boolean
        get() = store.decodeBool(K_SHOW_BOTTOM_PROGRESS, true)
        set(value) {
            store.encode(K_SHOW_BOTTOM_PROGRESS, value)
            emit(ChangeEvent.Layout)
        }

    // ---------- Image ----------
    var imagePlacement: ImagePlacement
        get() = runCatching {
            ImagePlacement.valueOf(store.decodeString(K_IMG_PLACEMENT, ImagePlacement.Center.name) ?: ImagePlacement.Center.name)
        }.getOrDefault(ImagePlacement.Center)
        set(value) {
            store.encode(K_IMG_PLACEMENT, value.name)
            emit(ChangeEvent.Image)
        }

    var imageScaleMode: ImageScaleMode
        get() = runCatching {
            ImageScaleMode.valueOf(store.decodeString(K_IMG_SCALE, ImageScaleMode.Fit.name) ?: ImageScaleMode.Fit.name)
        }.getOrDefault(ImageScaleMode.Fit)
        set(value) {
            store.encode(K_IMG_SCALE, value.name)
            emit(ChangeEvent.Image)
        }

    var preloadImageAhead: Int
        get() = store.decodeInt(K_PRELOAD_AHEAD, 2).coerceIn(0, 8)
        set(value) {
            store.encode(K_PRELOAD_AHEAD, value.coerceIn(0, 8))
            emit(ChangeEvent.Image)
        }

    /**
     * 正文自动混排插画的取材来源（issue #999），默认不混排。
     * 不进 [Snapshot]：变更走专属 [ChangeEvent.IllustMix]，由阅读页直接触发
     * 重排版 + 纵向重绑，不依赖 pushStyleAndGeometryIfReady 的快照去重。
     */
    var illustMixSource: NovelIllustSource
        get() = runCatching {
            NovelIllustSource.valueOf(store.decodeString(K_ILLUST_MIX_SOURCE, NovelIllustSource.None.name) ?: NovelIllustSource.None.name)
        }.getOrDefault(NovelIllustSource.None)
        set(value) {
            store.encode(K_ILLUST_MIX_SOURCE, value.name)
            emit(ChangeEvent.IllustMix)
        }

    // ---------- TTS ----------
    var ttsHighlight: Boolean
        get() = store.decodeBool(K_TTS_HIGHLIGHT, true)
        set(value) {
            store.encode(K_TTS_HIGHLIGHT, value)
            emit(ChangeEvent.Tts)
        }

    var ttsAutoPage: Boolean
        get() = store.decodeBool(K_TTS_AUTO_PAGE, false)
        set(value) {
            store.encode(K_TTS_AUTO_PAGE, value)
            emit(ChangeEvent.Tts)
        }

    var ttsDoubleTap: Boolean
        get() = store.decodeBool(K_TTS_DOUBLE_TAP, false)
        set(value) {
            store.encode(K_TTS_DOUBLE_TAP, value)
            emit(ChangeEvent.Tts)
        }

    var ttsShowPageAction: Boolean
        get() = store.decodeBool(K_TTS_PAGE_ACTION, true)
        set(value) {
            store.encode(K_TTS_PAGE_ACTION, value)
            emit(ChangeEvent.Tts)
        }

    var ttsSpeed: Float
        get() = store.decodeFloat(K_TTS_SPEED, 1f).coerceIn(0.5f, 2.0f)
        set(value) {
            store.encode(K_TTS_SPEED, value.coerceIn(0.5f, 2.0f))
            emit(ChangeEvent.Tts)
        }

    var ttsPitch: Float
        get() = store.decodeFloat(K_TTS_PITCH, 1f).coerceIn(0.5f, 2.0f)
        set(value) {
            store.encode(K_TTS_PITCH, value.coerceIn(0.5f, 2.0f))
            emit(ChangeEvent.Tts)
        }

    var ttsEngine: String?
        get() = store.decodeString(K_TTS_ENGINE, null)
        set(value) {
            if (value == null) store.removeValueForKey(K_TTS_ENGINE) else store.encode(K_TTS_ENGINE, value)
            emit(ChangeEvent.Tts)
        }

    var ttsVoice: String?
        get() = store.decodeString(K_TTS_VOICE, null)
        set(value) {
            if (value == null) store.removeValueForKey(K_TTS_VOICE) else store.encode(K_TTS_VOICE, value)
            emit(ChangeEvent.Tts)
        }

    var ttsSleepTimerMinutes: Int
        get() = store.decodeInt(K_TTS_SLEEP, 0)
        set(value) {
            store.encode(K_TTS_SLEEP, value)
            emit(ChangeEvent.Tts)
        }

    // ---------- Search ----------
    /** Remember the last input, including an explicit clear, across reader sessions. */
    var lastSearchQuery: String
        get() = store.decodeString(K_LAST_SEARCH_QUERY, "").orEmpty()
        set(value) {
            store.encode(K_LAST_SEARCH_QUERY, value)
        }

    // ---------- Misc ----------
    var eyeBreakReminderMinutes: Int
        get() = store.decodeInt(K_EYE_REMIND, 30)
        set(value) {
            store.encode(K_EYE_REMIND, value)
            emit(ChangeEvent.Reminder)
        }

    var touchLocked: Boolean
        get() = store.decodeBool(K_TOUCH_LOCKED, false)
        set(value) {
            store.encode(K_TOUCH_LOCKED, value)
            emit(ChangeEvent.Interaction)
        }

    var showDebugOverlay: Boolean
        get() = store.decodeBool(K_DEBUG_OVERLAY, false)
        set(value) {
            store.encode(K_DEBUG_OVERLAY, value)
            emit(ChangeEvent.Layout)
        }

    var readingSpeedCharPerMin: Int
        get() = store.decodeInt(K_READING_SPEED, 400).coerceIn(50, 1500)
        set(value) {
            store.encode(K_READING_SPEED, value.coerceIn(50, 1500))
        }

    /** Emit a synthetic change event so observers can force a refresh. */
    fun notifyLayoutChanged() = emit(ChangeEvent.Layout)

    fun snapshot(): Snapshot = Snapshot(
        fontSizeSp = fontSizeSp,
        lineSpacing = lineSpacing,
        paragraphSpacingLines = paragraphSpacingLines,
        horizontalMarginDp = horizontalMarginDp,
        verticalMarginDp = verticalMarginDp,
        firstLineIndent = firstLineIndent,
        letterSpacing = letterSpacing,
        boldText = boldText,
        fontId = fontId,
        fontWeight = fontWeight,
        themeId = themeId,
        customThemeId = customThemeId,
        followSystemDarkMode = followSystemDarkMode,
        lightThemeMemoryId = lightThemeMemoryId,
        backgroundImagePath = backgroundImagePath,
        flipMode = flipMode,
        imagePlacement = imagePlacement,
        imageScaleMode = imageScaleMode,
        customTextColor = customTextColor(effectiveTheme().id),
    )

    data class Snapshot(
        val fontSizeSp: Int,
        val lineSpacing: Float,
        val paragraphSpacingLines: Float,
        val horizontalMarginDp: Int,
        val verticalMarginDp: Int,
        val firstLineIndent: Int,
        val letterSpacing: Float,
        val boldText: Boolean,
        val fontId: String,
        val fontWeight: Int,
        val themeId: String,
        val customThemeId: Int,
        val followSystemDarkMode: Boolean,
        val lightThemeMemoryId: String,
        val backgroundImagePath: String?,
        val flipMode: FlipMode,
        val imagePlacement: ImagePlacement,
        val imageScaleMode: ImageScaleMode,
        // The paged reader deduplicates style updates by Snapshot equality, including color-only edits.
        val customTextColor: Int? = null,
    )

    const val FONT_SIZE_MIN = 12
    const val FONT_SIZE_MAX = 36

    private const val K_FONT_SIZE = "r_font_size"
    private const val K_LINE_SPACING = "r_line_spacing"
    private const val K_PARAGRAPH_SPACING = "r_paragraph_spacing"
    private const val K_PARAGRAPH_SPACING_LINES = "r_paragraph_spacing_lines"
    private const val K_H_MARGIN = "r_h_margin"
    private const val K_V_MARGIN = "r_v_margin"
    private const val K_INDENT = "r_indent"
    private const val K_LETTER_SPACING = "r_letter_spacing"
    private const val K_BOLD = "r_bold"
    private const val K_FONT_ID = "r_font_id"
    private const val K_FONT_WEIGHT = "r_font_weight"
    private const val K_THEME_ID = "r_theme_id"
    private const val K_TEXT_COLOR_PREFIX = "r_text_color_"
    private const val K_CUSTOM_THEME_ID = "r_custom_theme_id"
    private const val K_FOLLOW_DARK = "r_follow_dark"
    private const val K_LIGHT_THEME_MEMORY = "r_light_theme_memory"
    private const val K_BG_IMAGE = "r_bg_image"
    private const val K_SYS_BRIGHTNESS = "r_sys_brightness"
    private const val K_BRIGHTNESS = "r_brightness"
    private const val K_WARM_FILTER = "r_warm_filter"
    private const val K_READING_DIR = "r_reading_direction"
    private const val K_FLIP_MODE = "r_flip_mode"
    private const val K_VOLUME_FLIP = "r_volume_flip"
    private const val K_TAP_REVERSED = "r_tap_reversed"
    private const val K_AUTO_PAGE_INTERVAL = "r_auto_page_interval"
    private const val K_ORIENTATION = "r_orientation"
    private const val K_IMMERSIVE = "r_immersive"
    private const val K_KEEP_SCREEN_ON = "r_keep_screen_on"
    private const val K_SHOW_TOP_PROGRESS = "r_show_top_progress"
    private const val K_SHOW_BOTTOM_PROGRESS = "r_show_bottom_progress"
    private const val K_IMG_PLACEMENT = "r_img_placement"
    private const val K_IMG_SCALE = "r_img_scale"
    private const val K_PRELOAD_AHEAD = "r_preload_ahead"
    private const val K_ILLUST_MIX_SOURCE = "r_illust_mix_source"
    private const val K_TTS_HIGHLIGHT = "r_tts_highlight"
    private const val K_TTS_AUTO_PAGE = "r_tts_auto_page"
    private const val K_TTS_DOUBLE_TAP = "r_tts_double_tap"
    private const val K_TTS_PAGE_ACTION = "r_tts_page_action"
    private const val K_TTS_SPEED = "r_tts_speed"
    private const val K_TTS_PITCH = "r_tts_pitch"
    private const val K_TTS_ENGINE = "r_tts_engine"
    private const val K_TTS_VOICE = "r_tts_voice"
    private const val K_TTS_SLEEP = "r_tts_sleep"
    private const val K_LAST_SEARCH_QUERY = "r_last_search_query"
    private const val K_EYE_REMIND = "r_eye_remind"
    private const val K_TOUCH_LOCKED = "r_touch_locked"
    private const val K_DEBUG_OVERLAY = "r_debug_overlay"
    private const val K_READING_SPEED = "r_reading_speed"
}
