package ceui.pixiv.ui.share

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class ArtworkPosterRendererInstrumentedTest {

    @Test
    fun portraitArtwork_rendersReferenceCardAndPreview() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val artwork = Bitmap.createBitmap(1_000, 1_500, Bitmap.Config.ARGB_8888)
        Canvas(artwork).apply {
            drawRect(
                0f, 0f, artwork.width.toFloat(), artwork.height.toFloat(),
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(
                        0f, 0f, artwork.width.toFloat(), artwork.height.toFloat(),
                        intArrayOf(Color.rgb(255, 190, 203), Color.rgb(111, 151, 235)),
                        null, Shader.TileMode.CLAMP,
                    )
                },
            )
            drawCircle(500f, 620f, 280f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(255, 239, 220)
            })
            drawCircle(405f, 570f, 32f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY })
            drawCircle(595f, 570f, 32f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY })
        }
        val avatar = Bitmap.createBitmap(180, 180, Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawColor(Color.rgb(92, 145, 239))
        }
        val result = ArtworkPosterRenderer.render(
            artwork = artwork,
            avatar = avatar,
            metadata = ArtworkPosterMetadata(
                authorName = "デュイ ☕",
                authorHandle = "xdeyuix",
                authorId = 123456L,
                title = "ナナリ ❤️",
                artworkId = 98765432L,
                createdAt = "2025-07-11T17:47:00+09:00",
                pageCount = 1,
                viewCount = 3_108,
                bookmarkCount = 52_000,
            ),
            locale = Locale.SIMPLIFIED_CHINESE,
            typography = ArtworkPosterTypography.from(context),
        )

        try {
            assertEquals(1_440, result.width)
            assertTrue(result.height in 2_300..2_500)
            // 外围暖灰、卡片纯白、图片中心非白：三层结构都实际画到了预期位置。
            assertTrue(Color.red(result.getPixel(20, 20)) in 235..250)
            assertEquals(Color.WHITE, result.getPixel(190, 200))
            assertTrue(result.getPixel(720, 1_200) != Color.WHITE)
            // 留一张由生产渲染器生成的真图，供开发机 adb pull 后目视审版。
            val preview = File(context.getExternalFilesDir(null), "artwork-poster-preview.png")
            FileOutputStream(preview).use {
                assertTrue(result.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            artwork.recycle()
            avatar.recycle()
            result.recycle()
        }
    }

    @Test
    fun countFormatting_matchesReferenceRhythm() {
        assertEquals("3108", ArtworkPosterRenderer.formatCount(3_108, Locale.SIMPLIFIED_CHINESE))
        assertEquals("5.2万", ArtworkPosterRenderer.formatCount(52_000, Locale.SIMPLIFIED_CHINESE))
        assertEquals("52K", ArtworkPosterRenderer.formatCount(52_000, Locale.US))
    }
}
