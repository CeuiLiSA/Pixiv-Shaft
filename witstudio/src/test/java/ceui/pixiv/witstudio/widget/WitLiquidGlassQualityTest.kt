package ceui.pixiv.witstudio.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WitLiquidGlassQualityTest {

    @Test
    fun defaultQualityIsNone() {
        assertEquals(WitLiquidGlassQuality.NONE, WitLiquidGlassQuality.defaultQuality)
    }

    @Test
    fun isLiveEnabledOnlyForStandardAndUltra() {
        assertFalse(WitLiquidGlassQuality.NONE.isLiveEnabled)
        assertTrue(WitLiquidGlassQuality.STANDARD.isLiveEnabled)
        assertTrue(WitLiquidGlassQuality.ULTRA.isLiveEnabled)
    }

    @Test
    fun fromOrdinalResolvesCorrectlyWithSafeFallback() {
        assertEquals(WitLiquidGlassQuality.NONE, WitLiquidGlassQuality.fromOrdinal(0))
        assertEquals(WitLiquidGlassQuality.STANDARD, WitLiquidGlassQuality.fromOrdinal(1))
        assertEquals(WitLiquidGlassQuality.ULTRA, WitLiquidGlassQuality.fromOrdinal(2))
        assertEquals(WitLiquidGlassQuality.NONE, WitLiquidGlassQuality.fromOrdinal(-1))
        assertEquals(WitLiquidGlassQuality.NONE, WitLiquidGlassQuality.fromOrdinal(99))
    }

    @Test
    fun borderlessAnchorPlatterCondensesSmoothlyFromZero() {
        // Frame 0: zero platter alpha and zero elevation to avoid flash on borderless icons.
        assertEquals(0f, WitLiquidGlassMenu.computePlatterAlpha(0f, isBorderless = true), 0.001f)
        assertEquals(0f, WitLiquidGlassMenu.computeElevationFraction(0f, isBorderless = true), 0.001f)

        // Mid-growth: smoothly condensing out of nowhere.
        val midAlpha = WitLiquidGlassMenu.computePlatterAlpha(0.14f, isBorderless = true)
        assertTrue(midAlpha in 0.40f..0.60f)

        // Fully condensed once teardrop forms: 1.0.
        assertEquals(1f, WitLiquidGlassMenu.computePlatterAlpha(0.28f, isBorderless = true), 0.001f)
        assertEquals(1f, WitLiquidGlassMenu.computePlatterAlpha(1.00f, isBorderless = true), 0.001f)

        assertEquals(1f, WitLiquidGlassMenu.computeElevationFraction(0.38f, isBorderless = true), 0.001f)
        assertEquals(1f, WitLiquidGlassMenu.computeElevationFraction(1.00f, isBorderless = true), 0.001f)

        // Glass orbs with backgrounds remain 100% visible throughout morph.
        assertEquals(1f, WitLiquidGlassMenu.computePlatterAlpha(0f, isBorderless = false), 0.001f)
        assertEquals(1f, WitLiquidGlassMenu.computeElevationFraction(0f, isBorderless = false), 0.001f)
    }
}