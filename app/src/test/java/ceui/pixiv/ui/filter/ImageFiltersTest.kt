package ceui.pixiv.ui.filter

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 滤镜颜色模型的纯 JVM 校验。native 内核只负责「查表 + 暗角 + 颗粒」，颜色的对错全在这里定：
 * LUT 采得对、强度插值对、各滤镜不越界、暗角曲线单调，预览与导出就一致。
 */
class ImageFiltersTest {

    private val n = FilterLut.SIZE

    private fun at(lut: FloatArray, r: Int, g: Int, b: Int): FloatArray {
        val i = ((b * n + g) * n + r) * 3
        return floatArrayOf(lut[i], lut[i + 1], lut[i + 2])
    }

    @Test
    fun `identity LUT maps each grid point to itself, r varying fastest`() {
        val id = FilterLut.identity
        assertEquals(n * n * n * 3, id.size)
        assertArrayEquals(floatArrayOf(1f, 0f, 0f), at(id, n - 1, 0, 0), 1e-6f)
        assertArrayEquals(floatArrayOf(0f, 0f, 1f), at(id, 0, 0, n - 1), 1e-6f)
        assertArrayEquals(floatArrayOf(.5f, .25f, .75f), at(id, 16, 8, 24), 1e-6f)
    }

    @Test
    fun `untouched state needs no colour work`() {
        assertNull(FilterLut.forState(FilterState()))
        assertNull(FilterLut.forState(FilterState(preset = FilterPreset.CREAM, intensity = 0)))
        assertTrue(FilterState(preset = FilterPreset.ORIGINAL, intensity = 50).colorIsIdentity)
        assertEquals(0f, FilterState().vignette())
        assertEquals(0f, FilterState().grain())
    }

    @Test
    fun `strength interpolates between original and full preset`() {
        val full = FilterLut.forState(FilterState(preset = FilterPreset.TEAL_ORANGE, intensity = 100))!!
        val half = FilterLut.forState(FilterState(preset = FilterPreset.TEAL_ORANGE, intensity = 50))!!
        val id = FilterLut.identity
        for (i in full.indices step 997) {
            assertEquals(id[i] + (full[i] - id[i]) / 2f, half[i], 1e-5f)
        }
        // 滤镜自带的暗角随强度一起缩放
        assertEquals(FilterPreset.TEAL_ORANGE.vignette / 2f, FilterState(FilterPreset.TEAL_ORANGE, 50).vignette(), 1e-6f)
    }

    @Test
    fun `every preset stays in range and actually changes the image`() {
        for (preset in FilterPreset.entries) {
            val lut = FilterLut.forPreset(preset)
            if (preset == FilterPreset.ORIGINAL) {
                assertNull(lut)
                continue
            }
            assertNotNull(preset.name, lut)
            assertTrue(preset.name, lut!!.all { it in 0f..1f })
            val maxDelta = lut.indices.maxOf { kotlin.math.abs(lut[it] - FilterLut.identity[it]) }
            assertTrue("${preset.name} is a no-op", maxDelta > 0.03f)
        }
    }

    @Test
    fun `black and white presets have no colour left`() {
        for (preset in listOf(FilterPreset.MONO, FilterPreset.NOIR)) {
            val lut = FilterLut.forPreset(preset)!!
            for (i in lut.indices step 3) {
                assertEquals(preset.name, lut[i], lut[i + 1], 1e-5f)
                assertEquals(preset.name, lut[i], lut[i + 2], 1e-5f)
            }
        }
    }

    @Test
    fun `preset keys are unique and round-trip`() {
        val keys = FilterPreset.entries.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        FilterPreset.entries.forEach { assertEquals(it, FilterPreset.fromKey(it.key)) }
        assertEquals(FilterPreset.ORIGINAL, FilterPreset.fromKey("removed_preset"))
    }

    @Test
    fun `adjustments move colours the way their names say`() {
        fun mid(state: FilterState): FloatArray = at(FilterLut.forState(state)!!, 16, 16, 16)
        val base = FilterState()
        assertTrue(mid(base.with(FilterAdjust.BRIGHTNESS, 50))[0] > .5f)
        assertTrue(mid(base.with(FilterAdjust.BRIGHTNESS, -50))[0] < .5f)
        val warm = mid(base.with(FilterAdjust.WARMTH, 80))
        assertTrue(warm[0] > warm[2])
        val cool = mid(base.with(FilterAdjust.WARMTH, -80))
        assertTrue(cool[2] > cool[0])
        // 褪色托起黑位
        val black = at(FilterLut.forState(base.with(FilterAdjust.FADE, 100))!!, 0, 0, 0)
        assertTrue(black[0] > .1f)
        // 去饱和到底就是灰
        val red = at(FilterLut.forState(base.with(FilterAdjust.SATURATION, -100))!!, n - 1, 0, 0)
        assertEquals(red[0], red[1], 1e-5f)
        // 暗角 / 颗粒不进颜色 LUT
        assertNull(FilterLut.forState(base.with(FilterAdjust.VIGNETTE, 60).with(FilterAdjust.GRAIN, 60)))
    }

    @Test
    fun `file tag names identical images alike and different images apart`() {
        val cream = FilterState(preset = FilterPreset.CREAM)
        assertEquals("cream", cream.fileTag())
        val brighter = cream.with(FilterAdjust.BRIGHTNESS, 20)
        assertTrue(brighter.fileTag().matches(Regex("cream_[0-9a-f]{6}")))
        // 同一组参数永远同名（「跳过已存在」可以放心跳）
        assertEquals(brighter.fileTag(), FilterState(preset = FilterPreset.CREAM).with(FilterAdjust.BRIGHTNESS, 20).fileTag())
        // 参数不同就不同名（「替换」不会盖掉另一张）
        val tags = setOf(
            cream.fileTag(), brighter.fileTag(), cream.with(FilterAdjust.BRIGHTNESS, 21).fileTag(),
            cream.copy(intensity = 60).fileTag(), cream.with(FilterAdjust.CONTRAST, 20).fileTag(),
        )
        assertEquals(5, tags.size)
        // 原图的强度残值不影响命名
        assertEquals("original", FilterState(intensity = 40).fileTag())
    }

    @Test
    fun `curve passes through its control points without overshoot`() {
        val c = Curve.of(0, 20, 64, 70, 128, 140, 255, 250)
        assertEquals(20 / 255f, c.eval(0f), 1e-4f)
        assertEquals(140 / 255f, c.eval(128 / 255f), 1e-3f)
        assertEquals(250 / 255f, c.eval(1f), 1e-4f)
        var last = -1f
        for (i in 0..255) {
            val v = c.eval(i / 255f)
            assertTrue("not monotone at $i", v >= last - 1e-6f)
            last = v
        }
    }

    @Test
    fun `vignette is untouched in the centre and darkest in the corners`() {
        val lut = FilterLut.vignette(1f)!!
        assertEquals(FilterLut.VIGNETTE_LUT_SIZE + 1, lut.size)
        assertEquals(1f, lut[0], 0f)
        for (i in 1 until lut.size) assertTrue(lut[i] <= lut[i - 1] + 1e-6f)
        assertEquals(1f - 0.72f, lut.last(), 1e-4f)
        assertNull(FilterLut.vignette(0f))
    }
}
