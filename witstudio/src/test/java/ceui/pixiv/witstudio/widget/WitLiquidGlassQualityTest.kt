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
}