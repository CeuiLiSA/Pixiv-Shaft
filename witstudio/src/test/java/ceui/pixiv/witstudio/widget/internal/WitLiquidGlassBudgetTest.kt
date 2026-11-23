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
        assertTrue(nativePlan.pixels in 512_001L..WitLiquidGlassBudget.MAX_PANEL_PATCH_PIXELS)

        // 232dp x 440dp on a 3.5x QHD display downscales cleanly instead of dropping live refraction.
        val scaledPlan = WitLiquidGlassBudget.planPatch(812, 1540, 3.5f, 4096, largeSurface = true)
        assertTrue(scaledPlan.scale in 0.55f..0.99f)
        assertTrue(scaledPlan.pixels in 1L..WitLiquidGlassBudget.MAX_PANEL_PATCH_PIXELS)

        // Small canvasEdge still forces downscaling or rejection on largeSurface.
        val tightCanvasPlan = WitLiquidGlassBudget.planPatch(700, 1200, 2f, 1024, largeSurface = true)
        assertTrue(tightCanvasPlan.scale in 0.55f..0.99f)
        assertTrue(tightCanvasPlan.pixels in 1L..WitLiquidGlassBudget.MAX_PANEL_PATCH_PIXELS)

        // Enormous full-screen views are still rejected.
        val rejected = WitLiquidGlassBudget.planPatch(Int.MAX_VALUE, Int.MAX_VALUE, 3f, 4096, largeSurface = true)
        assertEquals(0L, rejected.pixels)

        // Two stacked panels (main menu + 1.16x submenu) both fit inside HOST_PIXELS.
        assertTrue(WitLiquidGlassBudget.MAX_PANEL_PATCH_PIXELS * 2L <= WitLiquidGlassBudget.HOST_PIXELS)
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
        assertEquals(0f, rest.translateX, 0.001f)
        assertEquals(0f, rest.translateY, 0.001f)
        assertEquals(0f, rest.left, 0.001f)
        assertEquals(0f, rest.top, 0.001f)
        assertEquals(w, rest.right, 0.001f)
        assertEquals(h, rest.bottom, 0.001f)
        assertEquals(radius, rest.cornerRadius, 0.001f)

        // Pulling down shifts downward, stretches bottom further than top moves, and narrows width.
        val pullDown = WitLiquidGlassMenu.computeDragDeform(w, h, radius, density, 0f, 160f * density, 1f, margin)
        val downW = pullDown.right - pullDown.left
        val downH = pullDown.bottom - pullDown.top
        assertTrue(pullDown.translateY > 8f * density)
        assertTrue(downH > h)
        assertTrue(downW < w)
        assertTrue((pullDown.bottom - h) > -pullDown.top)

        // Extreme cross-screen drag still stays strictly inside +/- patchMargin.
        val extreme = WitLiquidGlassMenu.computeDragDeform(w, h, radius, density, -2000f * density, 2000f * density, 1f, margin)
        assertTrue(extreme.left >= -margin - 0.01f)
        assertTrue(extreme.right <= w + margin + 0.01f)
        assertTrue(extreme.top >= -margin - 0.01f)
        assertTrue(extreme.bottom <= h + margin + 0.01f)
    }

    @Test
    fun calibratedOpenMorphMatchesReferenceMilestonesAndStaysWithinPatchMargin() {
        val d = 3f
        val w = 250f * d
        val h = 237.67f * d
        val aw = 44f * d
        val ah = 44f * d
        val al = w - aw
        val at = 2f * d
        val endR = 32f * d
        val margin = 26f * d

        assertEquals(520L, WitLiquidGlassMenu.OPEN_DURATION_MS)
        assertEquals(500L, WitLiquidGlassMenu.CLOSE_DURATION_MS)

        // Frame 0 (0ms): exact anchor orb, content hidden, echo dots at 100% centered on anchor.
        val f0 = WitLiquidGlassMenu.evaluateOpenMorph(0f, w, h, al, at, aw, ah, endR, d, true, true, margin)
        assertEquals(al, f0.bodyLeft, 0.5f)
        assertEquals(at, f0.bodyTop, 0.5f)
        assertEquals(w, f0.bodyRight, 0.5f)
        assertEquals(at + ah, f0.bodyBottom, 0.5f)
        assertEquals(0f, f0.contentAlpha, 0.001f)
        assertEquals(1f, f0.echoAlpha, 0.001f)
        assertEquals(al + aw * 0.5f, f0.echoCenterX, 0.5f)
        assertEquals(at + ah * 0.5f, f0.echoCenterY, 0.5f)

        // ~40ms (p = 40/520): diagonal teardrop launch pulls right edge inward (~10..15dp) with orb bridge active.
        val f40 = WitLiquidGlassMenu.evaluateOpenMorph(40f / 520f, w, h, al, at, aw, ah, endR, d, true, true, margin)
        val rightPullDp = (w - f40.bodyRight) / d
        assertTrue("Expected right-edge inward pull 8..18dp at 40ms, got $rightPullDp", rightPullDp in 8f..18f)
        assertTrue("Expected teardrop metaball orb active at 40ms", f40.orbRadius > 5f * d && f40.orbBlendPx > 1f)
        assertEquals(0f, f40.contentAlpha, 0.001f)

        // ~100ms (p = 100/520): peak downward top sag (~38..50dp) while body holds full capsule roundness.
        val f100 = WitLiquidGlassMenu.evaluateOpenMorph(100f / 520f, w, h, al, at, aw, ah, endR, d, true, true, margin)
        val topSagDp = f100.bodyTop / d
        assertTrue("Expected peak top sag 36..52dp at 100ms, got $topSagDp", topSagDp in 36f..52f)
        assertTrue("Expected echo dots completely dissolved by 100ms", f100.echoAlpha <= 0.001f)

        // ~184ms: bottom overshoots below settled h (~10..22dp) while staying strictly within patchMargin.
        val f184 = WitLiquidGlassMenu.evaluateOpenMorph(184f / 520f, w, h, al, at, aw, ah, endR, d, true, true, margin)
        val bottomOverDp = (f184.bodyBottom - h) / d
        assertTrue("Expected bottom overshoot 10..22dp at 184ms, got $bottomOverDp", bottomOverDp in 10f..22f)
        assertTrue("Expected bevel refraction active around 184ms", f184.contentRefract > 0.25f)

        // ~250ms: width/height overshoot (~1.5..4.5%), content 100% alpha and razor sharp (0dp blur).
        val f250 = WitLiquidGlassMenu.evaluateOpenMorph(250f / 520f, w, h, al, at, aw, ah, endR, d, true, true, margin)
        assertTrue("Expected width overshoot at 250ms", (f250.bodyRight - f250.bodyLeft) > w * 1.015f)
        assertTrue("Expected height overshoot at 250ms", (f250.bodyBottom - f250.bodyTop) > h * 1.01f)
        assertEquals(1f, f250.contentAlpha, 0.001f)
        assertEquals(0f, f250.contentBlurDp, 0.01f)

        // Frame 1 (520ms): settled exactly at [0, 0, w, h] with endR, sharp content, zero orb.
        val fEnd = WitLiquidGlassMenu.evaluateOpenMorph(1f, w, h, al, at, aw, ah, endR, d, true, true, margin)
        assertEquals(0f, fEnd.bodyLeft, 0.5f)
        assertEquals(0f, fEnd.bodyTop, 0.5f)
        assertEquals(w, fEnd.bodyRight, 0.5f)
        assertEquals(h, fEnd.bodyBottom, 0.5f)
        assertEquals(endR, fEnd.bodyRadius, 0.5f)
        assertEquals(1f, fEnd.contentAlpha, 0.001f)
        assertEquals(0f, fEnd.contentBlurDp, 0.001f)
        assertEquals(0f, fEnd.contentRefract, 0.001f)
        assertEquals(0f, fEnd.orbBlendPx, 0.001f)

        // Verify patchMargin safety across tall (480dp) and tiny (72dp) menus in all 4 directions.
        for (testH in listOf(72f * d, 238f * d, 480f * d)) {
            for (onRight in listOf(true, false)) {
                for (down in listOf(true, false)) {
                    val aL = if (onRight) w - aw else 0f
                    val aT = if (down) 2f * d else testH - ah - 2f * d
                    for (step in 0..50) {
                        val p = step / 50f
                        val of = WitLiquidGlassMenu.evaluateOpenMorph(p, w, testH, aL, aT, aw, ah, endR, d, onRight, down, margin)
                        assertTrue(of.bodyLeft >= -margin - 0.5f)
                        assertTrue(of.bodyRight <= w + margin + 0.5f)
                        assertTrue(of.bodyTop >= -margin - 0.5f)
                        assertTrue(of.bodyBottom <= testH + margin + 0.5f)
                    }
                }
            }
        }
    }

    @Test
    fun calibratedCloseMorphDissolvesContentFastFormsConcaveMetaballAndRecoilsOrb() {
        val d = 3f
        val w = 250f * d
        val h = 237.67f * d
        val aw = 44f * d
        val ah = 44f * d
        val al = w - aw
        val at = 2f * d
        val acy = at + ah * 0.5f
        val endR = 32f * d
        val margin = 26f * d

        // 0ms: exact settled menu.
        val c0 = WitLiquidGlassMenu.evaluateCloseMorph(0f, w, h, al, at, aw, ah, endR, d, true, true, margin)
        assertEquals(0f, c0.bodyLeft, 0.5f)
        assertEquals(0f, c0.bodyTop, 0.5f)
        assertEquals(w, c0.bodyRight, 0.5f)
        assertEquals(h, c0.bodyBottom, 0.5f)
        assertEquals(1f, c0.contentAlpha, 0.001f)
        assertEquals(0f, c0.echoAlpha, 0.001f)

        // ~50ms: menu dips downward while content rapidly blurs & dissolves.
        val c50 = WitLiquidGlassMenu.evaluateCloseMorph(50f / 500f, w, h, al, at, aw, ah, endR, d, true, true, margin)
        assertTrue("Expected downward top drop at 50ms, got ${c50.bodyTop / d}dp", c50.bodyTop / d > 25f)
        assertTrue("Expected content nearly gone by 50ms, got ${c50.contentAlpha}", c50.contentAlpha < 0.20f)

        // ~67ms: content 100% dissolved into pure empty glass bubble.
        val c67 = WitLiquidGlassMenu.evaluateCloseMorph(67f / 500f, w, h, al, at, aw, ah, endR, d, true, true, margin)
        assertEquals(0f, c67.contentAlpha, 0.001f)

        // ~185ms: classic concave gourd/peanut metaball bridge active, echo dots re-emerging inside moving orb!
        val c185 = WitLiquidGlassMenu.evaluateCloseMorph(185f / 500f, w, h, al, at, aw, ah, endR, d, true, true, margin)
        assertTrue("Expected metaball orb active at 185ms", c185.orbRadius > 12f * d && c185.orbBlendPx > 15f * d)
        assertTrue("Expected echo dots emerging at 185ms, got ${c185.echoAlpha}", c185.echoAlpha in 0.10f..0.60f)
        assertEquals(c185.orbX, c185.echoCenterX, 0.5f)
        assertEquals(c185.orbY, c185.echoCenterY, 0.5f)

        // ~320ms: belly fully absorbed into promoted circular orb, overshot upward past anchor rest (-3..-8dp).
        val c320 = WitLiquidGlassMenu.evaluateCloseMorph(320f / 500f, w, h, al, at, aw, ah, endR, d, true, true, margin)
        assertEquals(0f, c320.orbBlendPx, 0.001f)
        assertEquals(1f, c320.echoAlpha, 0.001f)
        val orbCy = (c320.bodyTop + c320.bodyBottom) * 0.5f
        val upwardRecoilDp = (acy - orbCy) / d
        assertTrue("Expected upward orb recoil 2..9dp at 320ms, got $upwardRecoilDp", upwardRecoilDp in 2f..9f)

        // 500ms: settled cleanly back at exact anchor rest.
        val cEnd = WitLiquidGlassMenu.evaluateCloseMorph(1f, w, h, al, at, aw, ah, endR, d, true, true, margin)
        assertEquals(al, cEnd.bodyLeft, 0.5f)
        assertEquals(at, cEnd.bodyTop, 0.5f)
        assertEquals(w, cEnd.bodyRight, 0.5f)
        assertEquals(at + ah, cEnd.bodyBottom, 0.5f)
        assertEquals(1f, cEnd.echoAlpha, 0.001f)
        assertEquals(al + aw * 0.5f, cEnd.echoCenterX, 0.5f)
        assertEquals(acy, cEnd.echoCenterY, 0.5f)
    }
}
