package ceui.pixiv.ui.filter

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.random.Random

/**
 * native 滤镜内核对拍：同一组参数，C++ 的「定点四面体查表」与 Kotlin 浮点逐像素直算的结果应在 LUT 量化误差内；
 * 透明像素的反乘 / 乘回不能把颜色带偏；顺带打一条 24MP 全分辨率导出的耗时（logcat tag ImageFilterBench）。
 */
@RunWith(AndroidJUnit4::class)
class NativeImageFilterInstrumentedTest {

    private fun randomBitmap(w: Int, h: Int, seed: Int, alpha: Boolean = false): Bitmap {
        val rnd = Random(seed)
        val pixels = IntArray(w * h) {
            val a = if (alpha) rnd.nextInt(1, 256) else 255
            Color.argb(a, rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256))
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888).copy(Bitmap.Config.ARGB_8888, true)
    }

    /** 像插画的测试图：大面积平滑渐变 + 少量噪点。纯随机噪点会让查表和分支预测双双失效，不代表真实作品。 */
    private fun illustrationLike(w: Int, h: Int): Bitmap {
        val rnd = Random(11)
        val pixels = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val n = rnd.nextInt(-6, 7)
            Color.rgb(
                (x * 255 / w + n).coerceIn(0, 255),
                (y * 255 / h + n).coerceIn(0, 255),
                ((x + y) * 255 / (w + h) + 40 - n).coerceIn(0, 255),
            )
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888).copy(Bitmap.Config.ARGB_8888, true)
    }

    @Test
    fun lutKernelMatchesFloatReferenceForEveryPreset() {
        val src = randomBitmap(61, 47, seed = 7)
        val dst = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val c = FloatArray(3)
        for (preset in FilterPreset.entries) {
            // 只比颜色：暗角 / 颗粒是空间效果，单独测
            val state = FilterState(preset = preset)
            NativeImageFilter.render(src, dst, state, FilterLut.forPreset(preset), vignette = 0f, grain = 0f)
            var worst = 0
            for (y in 0 until src.height) for (x in 0 until src.width) {
                val p = src.getPixel(x, y)
                c[0] = Color.red(p) / 255f; c[1] = Color.green(p) / 255f; c[2] = Color.blue(p) / 255f
                preset.ops().forEach { it.apply(c) }
                val q = dst.getPixel(x, y)
                worst = maxOf(
                    worst,
                    abs(Color.red(q) - (c[0].coerceIn(0f, 1f) * 255f).toInt()),
                    abs(Color.green(q) - (c[1].coerceIn(0f, 1f) * 255f).toInt()),
                    abs(Color.blue(q) - (c[2].coerceIn(0f, 1f) * 255f).toInt()),
                )
                assertEquals(255, Color.alpha(q))
            }
            // 33³ 网格对曲线类滤镜的插值误差在几个色阶之内，肉眼不可辨
            assertTrue("${preset.name} worst diff $worst", worst <= 4)
        }
    }

    @Test
    fun translucentPixelsSurviveIdentityRoundTrip() {
        val src = randomBitmap(40, 40, seed = 3, alpha = true)
        val copy = src.copy(Bitmap.Config.ARGB_8888, true)
        // 恒等 LUT：强制走一遍反乘 / 查表 / 乘回，颜色应该原样回来
        NativeImageFilter.render(copy, copy, FilterState(), FilterLut.identity)
        for (y in 0 until 40) for (x in 0 until 40) {
            val a = src.getPixel(x, y)
            val b = copy.getPixel(x, y)
            assertEquals(Color.alpha(a), Color.alpha(b))
            // getPixel 返回的是反乘后的颜色，低 alpha 时本身就有量化误差
            val tol = if (Color.alpha(a) < 32) 12 else 3
            assertTrue("($x,$y) $a → $b", abs(Color.red(a) - Color.red(b)) <= tol)
            assertTrue(abs(Color.blue(a) - Color.blue(b)) <= tol)
        }
    }

    @Test
    fun vignetteDarkensCornersNotCentre() {
        val bmp = Bitmap.createBitmap(201, 101, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(200, 200, 200)) }
        NativeImageFilter.render(bmp, bmp, FilterState().with(FilterAdjust.VIGNETTE, 100))
        assertEquals(200, Color.red(bmp.getPixel(100, 50)))
        assertTrue(Color.red(bmp.getPixel(0, 0)) < 70)
    }

    @Test
    fun benchmarkFullResolutionExport() {
        val big = illustrationLike(4000, 6000)
        val state = FilterState(preset = FilterPreset.RETRO_35).with(FilterAdjust.CONTRAST, 20)
        val lut = FilterLut.forState(state)
        NativeImageFilter.render(big, big, state, lut) // 预热
        val runs = 5
        val times = LongArray(runs) {
            val t = SystemClock.elapsedRealtimeNanos()
            NativeImageFilter.render(big, big, state, lut)
            SystemClock.elapsedRealtimeNanos() - t
        }.sorted()
        val lutTimes = LongArray(20) {
            val t = SystemClock.elapsedRealtimeNanos()
            FilterLut.forState(state.with(FilterAdjust.CONTRAST, 20 + it))
            SystemClock.elapsedRealtimeNanos() - t
        }.sorted()
        val preview = illustrationLike(1080, 1440)
        val renderOnly = Bitmap.createBitmap(preview.width, preview.height, Bitmap.Config.ARGB_8888)
        val renderTimes = LongArray(20) {
            val t = SystemClock.elapsedRealtimeNanos()
            NativeImageFilter.render(preview, renderOnly, state, lut, vignette = 0f, grain = 0f)
            SystemClock.elapsedRealtimeNanos() - t
        }.sorted()
        val draft = illustrationLike(540, 720)
        val draftOut = Bitmap.createBitmap(draft.width, draft.height, Bitmap.Config.ARGB_8888)
        val draftTimes = LongArray(20) {
            val t = SystemClock.elapsedRealtimeNanos()
            NativeImageFilter.render(draft, draftOut, state, FilterLut.forState(state))
            SystemClock.elapsedRealtimeNanos() - t
        }.sorted()
        Log.i(
            "ImageFilterBench",
            "LUT build median=%.2fms | 1.6MP render (LUT only) median=%.2fms | 0.4MP draft (incl. LUT) median=%.2fms".format(
                lutTimes[10] / 1e6, renderTimes[10] / 1e6, draftTimes[10] / 1e6,
            ),
        )
        val out = Bitmap.createBitmap(preview.width, preview.height, Bitmap.Config.ARGB_8888)
        val previewTimes = LongArray(20) {
            val t = SystemClock.elapsedRealtimeNanos()
            NativeImageFilter.render(preview, out, state, FilterLut.forState(state))
            SystemClock.elapsedRealtimeNanos() - t
        }.sorted()
        Log.i(
            "ImageFilterBench",
            "24MP export median=%.1fms min=%.1fms | 1.6MP preview (incl. LUT build) median=%.2fms".format(
                times[runs / 2] / 1e6, times[0] / 1e6, previewTimes[10] / 1e6,
            ),
        )
        big.recycle()
    }
}
