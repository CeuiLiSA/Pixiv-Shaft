package ceui.pixiv.ui.filter

import android.graphics.Bitmap
import android.os.SystemClock
import timber.log.Timber

/**
 * 滤镜的像素内核（`libshaft_filter.so`，见 `app/src/main/cpp/shaft_image_filter.cpp`）。
 *
 * 3D LUT 四面体插值 + 暗角 + 颗粒在同一遍里完成，整数定点、多核分行带。
 * 预览与导出都调它，所以看到的就是存下来的。调用方负责在后台线程调用。
 */
object NativeImageFilter {

    init {
        System.loadLibrary("shaft_filter")
    }

    /**
     * 把 [state] 从 [src] 渲染到 [dst]（可以是同一张，原地改写）。两张须同尺寸 ARGB_8888，[dst] 须可变。
     * [lut] 允许调用方传入已经算好的 LUT（[FilterLut.forState] / [FilterLut.forPreset]），省一次采样；
     * [vignette] / [grain] 默认取 [state] 的，单独比颜色时可以置 0。
     * 失败抛 [IllegalStateException]；native 在动像素前完成全部校验，不会留下半张图。
     */
    fun render(
        src: Bitmap,
        dst: Bitmap,
        state: FilterState,
        lut: FloatArray? = FilterLut.forState(state),
        vignette: Float = state.vignette(),
        grain: Float = state.grain(),
    ) {
        require(src.config == Bitmap.Config.ARGB_8888 && dst.config == Bitmap.Config.ARGB_8888) {
            "filter needs ARGB_8888, got ${src.config} → ${dst.config}"
        }
        require(dst.isMutable) { "filter writes pixels; dst must be mutable" }
        require(src.width == dst.width && src.height == dst.height) { "src/dst size mismatch" }
        val start = SystemClock.elapsedRealtimeNanos()
        val code = nativeRender(src, dst, lut, FilterLut.SIZE, FilterLut.vignette(vignette), grain)
        check(code == 0) { "native filter failed: $code" }
        if (src.width * src.height > LOG_PIXELS) {
            Timber.d(
                "[filter] %dx%d preset=%s in %.1fms",
                src.width, src.height, state.preset.key, (SystemClock.elapsedRealtimeNanos() - start) / 1e6,
            )
        }
    }

    /** 只给大图（导出）打耗时，预览每拖一下就渲一次，打出来是刷屏。 */
    private const val LOG_PIXELS = 4_000_000

    /** 返回 0 成功；−1 参数不对，−2 位图格式 / 尺寸不对，−3 锁定像素失败。 */
    private external fun nativeRender(
        src: Bitmap,
        dst: Bitmap,
        lut: FloatArray?,
        lutSize: Int,
        vignetteLut: FloatArray?,
        grain: Float,
    ): Int
}
