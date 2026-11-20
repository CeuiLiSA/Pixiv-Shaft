package ceui.pixiv.witstudio.widget.internal

import ceui.pixiv.witstudio.widget.WitLiquidGlassMenu
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun panelBudgetKeepsNativeScaleForTypicalMenusAndDownscalesHighDpi() {
        // 232dp x 400dp on a 2.625x display fits at full native resolution.
        val nativePlan = WitLiquidGlassBudget.planPatch(609, 1050, 2.625f, 4096, largeSurface = true)
        assertEquals(1f, nativePlan.scale, 0.0001f)
        assertTrue(nativePlan.pixels in 512_001L..1_300_000L)

        // 232dp x 440dp on a 3.5x QHD display downscales cleanly instead of dropping live refraction.
        val scaledPlan = WitLiquidGlassBudget.planPatch(812, 1540, 3.5f, 4096, largeSurface = true)
        assertTrue(scaledPlan.scale in 0.55f..0.99f)
        assertTrue(scaledPlan.pixels in 1L..1_300_000L)

        // Small canvasEdge still forces downscaling or rejection on largeSurface.
        val tightCanvasPlan = WitLiquidGlassBudget.planPatch(700, 1200, 2f, 1024, largeSurface = true)
        assertTrue(tightCanvasPlan.scale in 0.55f..0.99f)
        assertTrue(tightCanvasPlan.pixels in 1L..1_300_000L)

        // Enormous full-screen views are still rejected.
        val rejected = WitLiquidGlassBudget.planPatch(Int.MAX_VALUE, Int.MAX_VALUE, 3f, 4096, largeSurface = true)
        assertEquals(0L, rejected.pixels)
    }

    @Test
    fun morphSpanClampingPreservesTopRightCalibrationAndPreventsMidScreenPatchClip() {
        val margin = 26f
        // Normal top-right tall menu peak (curT=20.6, curH=484.74, h=480) stays unclamped.
        val normalTop = WitLiquidGlassMenu.clampMorphStart(20.6f, 484.74f, 480f, margin)
        assertEquals(20.6f, normalTop, 0.0001f)

        // Clamped mid-screen anchor (rawT=118.3, curH=440, h=450) stays inside h + margin.
        val midScreenTop = WitLiquidGlassMenu.clampMorphStart(118.3f, 440f, 450f, margin)
        assertEquals(36f, midScreenTop, 0.0001f)
        assertTrue(midScreenTop + 440f <= 450f + margin + 0.001f)

        // Expanding-up overshoot past -margin clamps cleanly at -margin.
        val upwardTop = WitLiquidGlassMenu.clampMorphStart(-48f, 440f, 450f, margin)
        assertEquals(-margin, upwardTop, 0.0001f)
    }

    @Test
    fun dragDeformIsIdentityAtRestStretchesWithVolumeConservationAndStaysWithinPatchMargin() {
        val density = 3f
        val w = 260f * density
        val h = 380f * density
        val radius = 30f * density
        val margin = 26f * density

        val rest = WitLiquidGlassMenu.computeDragDeform(w, h, radius, density, 0f, 0f, 0f, margin)
        assertEquals(0f, rest.left, 0.001f)
        assertEquals(0f, rest.top, 0.001f)
        assertEquals(w, rest.right, 0.001f)
        assertEquals(h, rest.bottom, 0.001f)
        assertEquals(radius, rest.cornerRadius, 0.001f)

        // Pulling down stretches bottom further than top moves and narrows width slightly.
        val pullDown = WitLiquidGlassMenu.computeDragDeform(w, h, radius, density, 0f, 160f * density, 1f, margin)
        val downW = pullDown.right - pullDown.left
        val downH = pullDown.bottom - pullDown.top
        assertTrue(downH > h)
        assertTrue(downW < w)
        assertTrue((pullDown.bottom - h) > pullDown.top)

        // Extreme cross-screen drag still stays strictly inside +/- patchMargin.
        val extreme = WitLiquidGlassMenu.computeDragDeform(w, h, radius, density, -2000f * density, 2000f * density, 1f, margin)
        assertTrue(extreme.left >= -margin - 0.01f)
        assertTrue(extreme.right <= w + margin + 0.01f)
        assertTrue(extreme.top >= -margin - 0.01f)
        assertTrue(extreme.bottom <= h + margin + 0.01f)
    }
}
