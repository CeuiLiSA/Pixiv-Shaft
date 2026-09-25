package ceui.pixiv.ui.translate

import android.app.ActivityManager
import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import ceui.pixiv.ui.upscale.OcrTextRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 译图按原分辨率回填:OCR 的 mask 分辨率低于底图时仍要擦干净,底图不因 OCR 上限被压缩。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MangaRenderResolutionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `low resolution mask erases glyph on full resolution bitmap`() {
        // 200x200 白底,中间 40x40 黑块当「字」
        val full = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
            for (y in 80 until 120) for (x in 80 until 120) setPixel(x, y, Color.BLACK)
        }
        // OCR 在 1/2 降采样图上跑:mask 100x100,字在 40..60
        val mask = TextMask(100, 100, ByteArray(100 * 100).also { data ->
            for (y in 40 until 60) for (x in 40 until 60) data[y * 100 + x] = 1
        })
        val region = OcrTextRegion(
            text = "字", cx = 100f, cy = 100f, width = 40f, height = 40f,
            angle = 0f, orientation = 0, prob = 1f,
            corners = listOf(80f to 80f, 120f to 80f, 120f to 120f, 80f to 120f),
        )

        TextEraser.eraseText(full, listOf(region), mask, pxScale = 2f)

        for (y in 80 until 120) for (x in 80 until 120) {
            assertEquals("($x,$y) should be erased", Color.WHITE, full.getPixel(x, y))
        }
    }

    @Test
    fun `render base keeps original resolution beyond ocr limit`() {
        val w = 1200
        val h = MangaPageTranslatePipeline.LAYOUT_REFERENCE_SHORT_SIDE * 2 + 400
        val file = File(tmp.root, "page.png")
        Bitmap.createBitmap(h, w, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
            .also { bmp -> file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }

        val base = checkNotNull(MangaPageTranslatePipeline.decodeRenderBase(file, AMPLE_BUDGET))

        assertEquals(1, base.sample)
        assertTrue("擦字要原地做", base.bitmap.isMutable)
        assertEquals(h, base.bitmap.width)
        assertEquals(w, base.bitmap.height)
        // 短边 1200 在参考分辨率以内:像素常量不缩放,排版与原来一致
        assertEquals(1f, base.pxScale)
    }

    @Test
    fun `render base scales layout constants for dense pages`() {
        val short = MangaPageTranslatePipeline.LAYOUT_REFERENCE_SHORT_SIDE + 100
        val file = File(tmp.root, "dense.png")
        Bitmap.createBitmap(short, short + 10, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
            .also { bmp -> file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }

        val base = checkNotNull(MangaPageTranslatePipeline.decodeRenderBase(file, AMPLE_BUDGET))

        assertEquals(1, base.sample)
        assertEquals(short, base.bitmap.width)
        // 旧实现会把它降采样一半;现在原分辨率出图,像素常量相应 ×2
        assertEquals(2f, base.pxScale)
    }

    @Test
    fun `render base downsamples only when page exceeds memory budget`() {
        val file = File(tmp.root, "tight.png")
        Bitmap.createBitmap(1000, 1400, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
            .also { bmp -> file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }

        // 预算只够 1/4 面积 → 降一档到 500x700,像素常量跟着 ×0.5
        val base = checkNotNull(MangaPageTranslatePipeline.decodeRenderBase(file, 4L * 500 * 700))

        assertEquals(2, base.sample)
        assertEquals(500, base.bitmap.width)
        assertEquals(0.5f, base.pxScale)
    }

    @Test
    fun `translated page keeps original dimensions and carries translation`() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app.getSystemService(ActivityManager::class.java)!!).setMemoryInfo(
            ActivityManager.MemoryInfo().apply { availMem = 2L shl 30; threshold = 256L shl 20 }
        )
        // 短边 2600 > 2400:旧实现会降到 1500x1300 再出图
        val w = 3000
        val h = 2600
        val file = File(tmp.root, "orig.png")
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
            for (y in 1200 until 1400) for (x in 1400 until 1600) setPixel(x, y, Color.BLACK)
        }.also { bmp -> file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        val region = OcrTextRegion(
            text = "原文", cx = 1500f, cy = 1300f, width = 200f, height = 200f,
            angle = 0f, orientation = 0, prob = 1f,
            corners = listOf(1400f to 1200f, 1600f to 1200f, 1600f to 1400f, 1400f to 1400f),
        )

        val out = MangaPageTranslatePipeline.renderTranslated(
            app, file, pageIndex = 0, regions = listOf(region), translations = mapOf(0 to "译文"), textMask = null,
        )

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(out.absolutePath, bounds)
        assertEquals("image/png", bounds.outMimeType)
        assertEquals(w, bounds.outWidth)
        assertEquals(h, bounds.outHeight)
        val result = BitmapFactory.decodeFile(out.absolutePath)
        // 原文黑块被擦,气泡里画上了译文(有非白像素),气泡外原样
        assertEquals(Color.WHITE, result.getPixel(1402, 1202))
        var ink = 0
        for (y in 1200 until 1400) for (x in 1400 until 1600) if (result.getPixel(x, y) != Color.WHITE) ink++
        assertTrue("translation should be drawn inside bubble", ink > 0)
        assertEquals(Color.WHITE, result.getPixel(10, 10))
    }

    private companion object {
        const val AMPLE_BUDGET = 1L shl 32
    }
}
