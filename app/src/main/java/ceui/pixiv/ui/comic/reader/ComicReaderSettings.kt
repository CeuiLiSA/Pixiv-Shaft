package ceui.pixiv.ui.comic.reader

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.tencent.mmkv.MMKV

/**
 * 漫画 reader 的持久化设置。独立 MMKV namespace 与小说 reader 隔离。
 * 任何字段写入后会通过 [changes] 通知 UI 即时刷新。
 */
object ComicReaderSettings {

    private const val MMKV_ID = "comic_reader_v3"
    private val store: MMKV by lazy { MMKV.mmkvWithID(MMKV_ID) }

    private val _changes = MutableLiveData<ChangeEvent>()
    val changes: LiveData<ChangeEvent> = _changes

    private fun emit(e: ChangeEvent) = _changes.postValue(e)

    sealed class ChangeEvent {
        object Layout : ChangeEvent()
        object Theme : ChangeEvent()
        object Brightness : ChangeEvent()
        object Interaction : ChangeEvent()
        object Image : ChangeEvent()
    }

    enum class ReadingMode { Paged, Webtoon }
    enum class PageDirection { LTR, RTL }
    enum class FitMode { FitWidth, FitScreen, FitOriginal }

    var readingMode: ReadingMode
        get() = runCatching {
            ReadingMode.valueOf(store.decodeString(K_MODE, ReadingMode.Paged.name) ?: ReadingMode.Paged.name)
        }.getOrDefault(ReadingMode.Paged)
        set(value) { store.encode(K_MODE, value.name); emit(ChangeEvent.Layout) }

    var pageDirection: PageDirection
        get() = runCatching {
            PageDirection.valueOf(store.decodeString(K_DIRECTION, PageDirection.LTR.name) ?: PageDirection.LTR.name)
        }.getOrDefault(PageDirection.LTR)
        set(value) { store.encode(K_DIRECTION, value.name); emit(ChangeEvent.Layout) }

    var fitMode: FitMode
        get() = runCatching {
            FitMode.valueOf(store.decodeString(K_FIT, FitMode.FitWidth.name) ?: FitMode.FitWidth.name)
        }.getOrDefault(FitMode.FitWidth)
        set(value) { store.encode(K_FIT, value.name); emit(ChangeEvent.Image) }

    var backgroundDark: Boolean
        get() = store.decodeBool(K_BG_DARK, true)
        set(value) { store.encode(K_BG_DARK, value); emit(ChangeEvent.Theme) }

    var useSystemBrightness: Boolean
        get() = store.decodeBool(K_SYS_BRIGHTNESS, true)
        set(value) { store.encode(K_SYS_BRIGHTNESS, value); emit(ChangeEvent.Brightness) }

    var customBrightness: Float
        get() = store.decodeFloat(K_BRIGHTNESS, 0.5f).coerceIn(0.01f, 1f)
        set(value) { store.encode(K_BRIGHTNESS, value.coerceIn(0.01f, 1f)); emit(ChangeEvent.Brightness) }

    var keepScreenOn: Boolean
        get() = store.decodeBool(K_KEEP_SCREEN_ON, true)
        set(value) { store.encode(K_KEEP_SCREEN_ON, value); emit(ChangeEvent.Interaction) }

    var immersive: Boolean
        get() = store.decodeBool(K_IMMERSIVE, true)
        set(value) { store.encode(K_IMMERSIVE, value); emit(ChangeEvent.Interaction) }

    var tapZoneReversed: Boolean
        get() = store.decodeBool(K_TAP_REVERSED, false)
        set(value) { store.encode(K_TAP_REVERSED, value); emit(ChangeEvent.Interaction) }

    var volumeKeyFlip: Boolean
        get() = store.decodeBool(K_VOLUME_FLIP, true)
        set(value) { store.encode(K_VOLUME_FLIP, value); emit(ChangeEvent.Interaction) }

    var preloadAhead: Int
        get() = store.decodeInt(K_PRELOAD, 2).coerceIn(0, 8)
        set(value) { store.encode(K_PRELOAD, value.coerceIn(0, 8)); emit(ChangeEvent.Image) }

    var showPageNumber: Boolean
        get() = store.decodeBool(K_PAGE_NUM, true)
        set(value) { store.encode(K_PAGE_NUM, value); emit(ChangeEvent.Layout) }

    var loadOriginal: Boolean
        get() = store.decodeBool(K_LOAD_ORIGINAL, false)
        set(value) { store.encode(K_LOAD_ORIGINAL, value); emit(ChangeEvent.Image) }

    var doubleTapZoomLevel: Float
        get() = store.decodeFloat(K_DBLTAP_ZOOM, 2.5f).coerceIn(1.5f, 5f)
        set(value) { store.encode(K_DBLTAP_ZOOM, value.coerceIn(1.5f, 5f)); emit(ChangeEvent.Image) }

    /** 漫画通常右翻页（RTL）；Pixiv 多页插画一般 LTR。提供一键切换。 */
    fun toggleDirection() {
        pageDirection = if (pageDirection == PageDirection.LTR) PageDirection.RTL else PageDirection.LTR
    }

    fun snapshot(): Snapshot = Snapshot(
        readingMode = readingMode,
        pageDirection = pageDirection,
        fitMode = fitMode,
        backgroundDark = backgroundDark,
        showPageNumber = showPageNumber,
        loadOriginal = loadOriginal,
    )

    data class Snapshot(
        val readingMode: ReadingMode,
        val pageDirection: PageDirection,
        val fitMode: FitMode,
        val backgroundDark: Boolean,
        val showPageNumber: Boolean,
        val loadOriginal: Boolean,
    )

    private const val K_MODE = "c_mode"
    private const val K_DIRECTION = "c_direction"
    private const val K_FIT = "c_fit"
    private const val K_BG_DARK = "c_bg_dark"
    private const val K_SYS_BRIGHTNESS = "c_sys_brightness"
    private const val K_BRIGHTNESS = "c_brightness"
    private const val K_KEEP_SCREEN_ON = "c_keep_screen_on"
    private const val K_IMMERSIVE = "c_immersive"
    private const val K_TAP_REVERSED = "c_tap_reversed"
    private const val K_VOLUME_FLIP = "c_volume_flip"
    private const val K_PRELOAD = "c_preload"
    private const val K_PAGE_NUM = "c_page_num"
    private const val K_LOAD_ORIGINAL = "c_load_original"
    private const val K_DBLTAP_ZOOM = "c_dbltap_zoom"
}
