package ceui.pixiv.witstudio.widget.internal

import org.junit.Assert.assertEquals
import org.junit.Test

class WitLiquidGlassBudgetTest {
    @Test
    fun offscreenAreaIncludesSamplingPaddingOnAllSides() {
        assertEquals(340_200L, WitLiquidGlassBudget.patchPixels(765, 180, 3f, 4096))
    }

    @Test
    fun rejectsOversizedTexturesBeforeRecording() {
        assertEquals(0L, WitLiquidGlassBudget.patchPixels(1900, 1, 3f, 4096))
        assertEquals(0L, WitLiquidGlassBudget.patchPixels(1280, 900, 2f, 4096))
        assertEquals(0L, WitLiquidGlassBudget.patchPixels(Int.MAX_VALUE, Int.MAX_VALUE, 3f, 4096))
    }

    @Test
    fun honorsCanvasLimitAndSkipsEmptyViews() {
        assertEquals(60_000L, WitLiquidGlassBudget.patchPixels(540, 40, 1f, 600))
        assertEquals(0L, WitLiquidGlassBudget.patchPixels(541, 40, 1f, 600))
        assertEquals(0L, WitLiquidGlassBudget.patchPixels(0, 60, 3f, 4096))
    }
}
