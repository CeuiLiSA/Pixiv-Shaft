package ceui.pixiv.ui.novel.reader.settings

import android.app.Application
import android.graphics.Paint
import android.text.TextPaint
import android.util.TypedValue
import ceui.lisa.activities.Shaft
import ceui.pixiv.ui.novel.reader.paginate.TextMeasurer
import ceui.pixiv.ui.novel.reader.paginate.TypefaceProvider
import com.tencent.mmkv.MMKV
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = Application::class,
    shadows = [ReaderParagraphSpacingMigrationTest.MemoryMMKV::class],
    instrumentedPackages = ["com.tencent.mmkv"])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderParagraphSpacingMigrationTest {
    @Before fun setUp() {
        MemoryMMKV.values.clear()
        ReflectionHelpers.setStaticField(Shaft::class.java, "sContext", RuntimeEnvironment.getApplication())
    }

    @Test fun `fresh settings use the new default and explicit zero stays zero`() {
        assertEquals(0.6f, ReaderSettings.paragraphSpacingLines, 0f)
        ReaderSettings.paragraphSpacingLines = 0f
        assertEquals(0f, ReaderSettings.paragraphSpacingLines, 0f)
    }

    @Test fun `old values keep their pixel gap and migrate only once`() {
        val context = RuntimeEnvironment.getApplication()
        for (font in listOf("system", "preset_serif", "preset_monospace")) {
            for (gap in listOf(0f, 0.1f, 0.8f, 2.5f)) {
                MemoryMMKV.values.clear()
                MemoryMMKV.values.putAll(mapOf(
                    "r_paragraph_spacing" to gap, "r_line_spacing" to 1.6f,
                    "r_font_size" to 24, "r_font_id" to font,
                ))
                val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    typeface = TypefaceProvider.resolve(context, font, 400, false)
                    textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 24f, context.resources.displayMetrics)
                }
                val fm = paint.fontMetrics
                val oldPixels = ((fm.bottom - fm.top) * gap).roundToInt()
                val migrated = ReaderSettings.paragraphSpacingLines
                assertEquals("font=$font gap=$gap", oldPixels,
                    (migrated * TextMeasurer.lineHeightPx(paint, 1.6f)).roundToInt())

                // Simulate reopening after changing typography. Re-conversion would alter the value.
                MemoryMMKV.values["r_font_size"] = 36
                MemoryMMKV.values["r_line_spacing"] = 2.8f
                assertEquals(migrated, ReaderSettings.paragraphSpacingLines, 0f)
                ReaderSettings.paragraphSpacingLines = 1f
                assertEquals(1f, ReaderSettings.paragraphSpacingLines, 0f)
            }
        }
    }

    @Test fun `converted values remain within the slider range`() {
        MemoryMMKV.values.putAll(mapOf("r_paragraph_spacing" to 2.5f, "r_line_spacing" to 1f))
        val migrated = ReaderSettings.paragraphSpacingLines
        assertEquals(migrated.coerceIn(0f, 2.5f), migrated, 0f)
    }

    @Test fun `saved typography also migrates an implicit old paragraph default`() {
        val context = RuntimeEnvironment.getApplication()
        for (spacing in listOf(1f, 1.6f, 2.8f)) {
            MemoryMMKV.values.clear()
            // Never touched paragraph spacing: the old reader still used 0.8 font boxes.
            MemoryMMKV.values["r_line_spacing"] = spacing
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 18f, context.resources.displayMetrics)
            }
            val fm = paint.fontMetrics
            val oldPixels = ((fm.bottom - fm.top) * 0.8f).roundToInt()
            val migrated = ReaderSettings.paragraphSpacingLines
            assertEquals("implicit default, line spacing=$spacing", oldPixels,
                (migrated * TextMeasurer.lineHeightPx(paint, spacing)).roundToInt())
            MemoryMMKV.values["r_line_spacing"] = 1.2f
            assertEquals(migrated, ReaderSettings.paragraphSpacingLines, 0f)
        }
    }

    /** Replace only MMKV's JNI storage; exercise the real ReaderSettings migration. */
    @Implements(MMKV::class, isInAndroidSdk = false)
    class MemoryMMKV {
        @Implementation fun count(): Long = values.size.toLong()
        @Implementation fun containsKey(key: String): Boolean = values.containsKey(key)
        @Implementation fun decodeFloat(key: String, defaultValue: Float): Float = values[key] as? Float ?: defaultValue
        @Implementation fun decodeInt(key: String, defaultValue: Int): Int = values[key] as? Int ?: defaultValue
        @Implementation fun decodeBool(key: String, defaultValue: Boolean): Boolean = values[key] as? Boolean ?: defaultValue
        @Implementation fun decodeString(key: String, defaultValue: String?): String? = values[key] as? String ?: defaultValue
        @Implementation fun encode(key: String, value: Float): Boolean {
            values[key] = value
            return true
        }
        @Implementation fun encode(key: String, value: String): Boolean {
            values[key] = value
            return true
        }
        @Implementation fun encode(key: String, value: Int): Boolean {
            values[key] = value
            return true
        }
        @Implementation fun encode(key: String, value: Long): Boolean {
            values[key] = value
            return true
        }

        companion object {
            val values = mutableMapOf<String, Any>()
            @JvmStatic @Implementation fun mmkvWithID(id: String): MMKV = ReflectionHelpers.callConstructor(
                MMKV::class.java, ClassParameter.from(Long::class.javaPrimitiveType, 0L),
            )
        }
    }
}
