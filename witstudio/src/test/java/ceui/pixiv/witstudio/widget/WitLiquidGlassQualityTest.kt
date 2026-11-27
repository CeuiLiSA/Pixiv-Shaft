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
        // Platter alpha stays 1.0 throughout morph to keep solid fluid body without dirty translucent artifacts.
        assertEquals(1f, WitLiquidGlassMenu.computePlatterAlpha(0f, isBorderless = true), 0.001f)
        assertEquals(1f, WitLiquidGlassMenu.computePlatterAlpha(1.00f, isBorderless = true), 0.001f)
        assertEquals(1f, WitLiquidGlassMenu.computePlatterAlpha(0f, isBorderless = false), 0.001f)

        // Elevation rises smoothly from zero.
        assertEquals(0f, WitLiquidGlassMenu.computeElevationFraction(0f, isBorderless = true), 0.001f)
        assertEquals(0f, WitLiquidGlassMenu.computeElevationFraction(0.12f, isBorderless = true), 0.001f)
        assertEquals(1f, WitLiquidGlassMenu.computeElevationFraction(0.82f, isBorderless = true), 0.001f)
        assertEquals(1f, WitLiquidGlassMenu.computeElevationFraction(1.00f, isBorderless = true), 0.001f)
        assertEquals(1f, WitLiquidGlassMenu.computeElevationFraction(0f, isBorderless = false), 0.001f)

        // Rim reflection stroke vanishes completely on borderless anchors when returning to bar.
        assertEquals(0f, WitLiquidGlassMenu.computeRimAlpha(0f, isBorderless = true), 0.001f)
        assertEquals(0f, WitLiquidGlassMenu.computeRimAlpha(0.08f, isBorderless = true), 0.001f)
        assertEquals(1f, WitLiquidGlassMenu.computeRimAlpha(0.84f, isBorderless = true), 0.001f)
        assertEquals(1f, WitLiquidGlassMenu.computeRimAlpha(1.00f, isBorderless = true), 0.001f)
        assertEquals(1f, WitLiquidGlassMenu.computeRimAlpha(0f, isBorderless = false), 0.001f)
    }

    @Test
    fun borderlessAnchorSurfaceFillTransitionsFromToolbarToCardFill() {
        val toolbarColor = 0xFF1E88E5.toInt() // Blue (toolbar/primary)
        val cardColor = 0xFFFFFFFF.toInt()    // White (cardFill)

        // Frame 0 & pull start: exactly toolbar color to seamlessly merge into the bar
        val fill0 = WitLiquidGlassMenu.computeSurfaceFill(0f, toolbarColor, cardColor, isBorderless = true)
        assertEquals(toolbarColor, fill0)
        val fill06 = WitLiquidGlassMenu.computeSurfaceFill(0.06f, toolbarColor, cardColor, isBorderless = true)
        assertEquals(toolbarColor, fill06)

        // Fully evolved at p >= 0.88: target card fill
        val fill88 = WitLiquidGlassMenu.computeSurfaceFill(0.88f, toolbarColor, cardColor, isBorderless = true)
        assertEquals(cardColor, fill88)

        val fill100 = WitLiquidGlassMenu.computeSurfaceFill(1.0f, toolbarColor, cardColor, isBorderless = true)
        assertEquals(cardColor, fill100)

        // Non-borderless anchor starts and stays at card fill
        val normalFill = WitLiquidGlassMenu.computeSurfaceFill(0f, toolbarColor, cardColor, isBorderless = false)
        assertEquals(cardColor, normalFill)

        // Mid-transition blend calculation check: sqrt(0.5 * 255^2) ≈ 180 (0xB4) for brightness-preserving gamma blend
        val black = 0xFF000000.toInt()
        val white = 0xFFFFFFFF.toInt()
        val midBlend = WitLiquidGlassMenu.blendColors(black, white, 0.5f)
        assertEquals(0xFFB4B4B4.toInt(), midBlend)
    }

    @Test
    fun weightedDerivedStartColorBlendsCardFillWithPrimaryWeight() {
        val cardFill = 0xFF161622.toInt()
        val primary = 0xFF686BDD.toInt()
        val expectedNight = WitLiquidGlassMenu.blendColors(cardFill, primary, 0.70f)
        val expectedDay = WitLiquidGlassMenu.blendColors(cardFill, primary, 0.80f)
        // Day blend has 80% primary radiance while night has 70% primary radiance
        val rNight = (expectedNight ushr 16) and 0xFF
        val rDay = (expectedDay ushr 16) and 0xFF
        assertTrue("Day (80%) should have more primary component than Night (70%)", rDay > rNight)
    }

    @Test
    fun borderlessClosingShrinkCollapsesSmoothlyToZero() {
        // Closing on borderless anchor collapses to 0 to eliminate the awkward ~48dp blob before disappearing
        assertEquals(0f, WitLiquidGlassMenu.computeCloseShrink(0f, closing = true, isBorderless = true), 0.001f)
        assertEquals(1f, WitLiquidGlassMenu.computeCloseShrink(0.16f, closing = true, isBorderless = true), 0.001f)
        assertEquals(1f, WitLiquidGlassMenu.computeCloseShrink(1.0f, closing = true, isBorderless = true), 0.001f)

        // Non-borderless or opening stays at 1f
        assertEquals(1f, WitLiquidGlassMenu.computeCloseShrink(0f, closing = false, isBorderless = true), 0.001f)
        assertEquals(1f, WitLiquidGlassMenu.computeCloseShrink(0f, closing = true, isBorderless = false), 0.001f)
    }

    @Test
    fun borderlessAlphaTransitionsWithoutSuddenHardCuts() {
        // Closing on borderless fades out completely before cleanup at p=0
        assertEquals(0f, WitLiquidGlassMenu.computeAnimAlpha(0f, closing = true, isBorderless = true), 0.001f)
        assertEquals(1f, WitLiquidGlassMenu.computeAnimAlpha(0.12f, closing = true, isBorderless = true), 0.001f)
        assertEquals(1f, WitLiquidGlassMenu.computeAnimAlpha(1.0f, closing = true, isBorderless = true), 0.001f)

        // Opening on borderless fades in quickly to eliminate single-frame flash
        assertEquals(0f, WitLiquidGlassMenu.computeAnimAlpha(0f, closing = false, isBorderless = true), 0.001f)
        assertEquals(1f, WitLiquidGlassMenu.computeAnimAlpha(0.06f, closing = false, isBorderless = true), 0.001f)
    }

    @Test
    fun centerProgressAnchorsToButtonCenterAtPZero() {
        // At p=0, centerProg is 0f so curCx == anchorCenterX (perfect alignment with 3-dots, no screen-edge bias)
        assertEquals(0f, WitLiquidGlassMenu.computeCenterProgress(0f, closing = false), 0.001f)
        assertEquals(0f, WitLiquidGlassMenu.computeCenterProgress(0f, closing = true), 0.001f)
        // At p=1, centerProg is 1f so curCx == w * 0.5f (fills laid-out width)
        assertEquals(1f, WitLiquidGlassMenu.computeCenterProgress(1f, closing = false), 0.001f)
        assertEquals(1f, WitLiquidGlassMenu.computeCenterProgress(1f, closing = true), 0.001f)
        // During closing, horizontal convergence happens earlier so droplet aligns with 3-dots before fade-out
        val progClosing20 = WitLiquidGlassMenu.computeCenterProgress(0.20f, closing = true)
        assertTrue("Closing progress at 0.20 must be <= 0.15 for early alignment, got $progClosing20", progClosing20 <= 0.15f)
    }

    @Test
    fun borderlessAnchorIconDissolvesAndReemergesSmoothly() {
        // At p=0, echo icon is 100% visible and 1:1 scale (perfect overlay with 3-dots button)
        val dissolve0 = WitLiquidGlassMenu.smootherstep(0.02f, 0.32f, 0f)
        assertEquals(0f, dissolve0, 0.001f)

        // At p=0.02, still completely visible
        val dissolve02 = WitLiquidGlassMenu.smootherstep(0.02f, 0.32f, 0.02f)
        assertEquals(0f, dissolve02, 0.001f)

        // Mid-way dissolution into fluid
        val dissolveMid = WitLiquidGlassMenu.smootherstep(0.02f, 0.32f, 0.17f)
        assertEquals(0.5f, dissolveMid, 0.05f)

        // Fully dissolved at p >= 0.32
        val dissolve32 = WitLiquidGlassMenu.smootherstep(0.02f, 0.32f, 0.32f)
        assertEquals(1f, dissolve32, 0.001f)
    }

    @Test
    fun borderlessAnchorSurfaceFillTwoStageTransitionsWithSlowCloseTail() {
        val toolbarColor = 0xFF1E88E5.toInt() // Blue (toolbar)
        val derivedColor = 0xFF64B5F6.toInt() // Light Blue (theme derived)
        val cardColor = 0xFF161622.toInt()    // Dark Slate (cardFill)

        // 1. Opening: at p=0 strictly toolbar color
        assertEquals(toolbarColor, WitLiquidGlassMenu.computeSurfaceFill(0f, toolbarColor, derivedColor, cardColor, true, closing = false))

        // Opening: at p=0.12 swiftly reaches derived color to build visual contrast against button background
        assertEquals(derivedColor, WitLiquidGlassMenu.computeSurfaceFill(0.12f, toolbarColor, derivedColor, cardColor, true, closing = false))

        // Opening: at p >= 0.85 smoothly evolves into target cardFill via C² smootherstep
        assertEquals(cardColor, WitLiquidGlassMenu.computeSurfaceFill(0.85f, toolbarColor, derivedColor, cardColor, true, closing = false))
        assertEquals(cardColor, WitLiquidGlassMenu.computeSurfaceFill(1.0f, toolbarColor, derivedColor, cardColor, true, closing = false))

        // 2. Closing: smoothly returns to derived color
        assertEquals(derivedColor, WitLiquidGlassMenu.computeSurfaceFill(0.22f, toolbarColor, derivedColor, cardColor, true, closing = true))

        // Closing: the last tail (0..0.22) eases out slowly (quadratic decay) without abrupt snapping
        val slowMidP = 0.11f // half-way through the 0..0.22 tail
        val midColor = WitLiquidGlassMenu.computeSurfaceFill(slowMidP, toolbarColor, derivedColor, cardColor, true, closing = true)
        // u = 0.5, u^2 = 0.25 (closer to toolbarColor, moving slower near zero)
        val expectedMid = WitLiquidGlassMenu.blendColors(toolbarColor, derivedColor, 0.25f)
        assertEquals(expectedMid, midColor)

        // At p=0, perfectly merges into toolbarColor
        assertEquals(toolbarColor, WitLiquidGlassMenu.computeSurfaceFill(0f, toolbarColor, derivedColor, cardColor, true, closing = true))
    }

    @Test
    fun submenuDslBuildsHierarchyCorrectly() {
        val builder = WitLiquidGlassMenu.Builder()
        builder.item("Action 1") {}
        builder.submenu("Submenu A") {
            item("Sub Action 1") {}
            item("Sub Action 2") {}
        }
        val entries = builder.buildEntries()
        assertEquals(2, entries.size)
        assertTrue(entries[0] is WitLiquidGlassMenu.Entry.Action)
        val submenu = entries[1] as WitLiquidGlassMenu.Entry.Submenu
        assertEquals("Submenu A", submenu.label)
        assertEquals(2, submenu.entries.size)
        assertEquals("Sub Action 1", (submenu.entries[0] as WitLiquidGlassMenu.Entry.Action).label)
        assertEquals("Sub Action 2", (submenu.entries[1] as WitLiquidGlassMenu.Entry.Action).label)
    }

    @Test
    fun scrimAlphaDefaultsToZeroUnlessDimBackgroundEnabled() {
        val defaultBuilder = WitLiquidGlassMenu.Builder()
        assertFalse(defaultBuilder.dimBackground)
        assertFalse(defaultBuilder.scrim)

        val scrimBuilder = WitLiquidGlassMenu.Builder().apply { scrim = true }
        assertTrue(scrimBuilder.dimBackground)
        assertTrue(scrimBuilder.scrim)

        // Default behavior (dimBackground = false): alpha is always 0
        assertEquals(0, WitLiquidGlassMenu.computeScrimAlpha(0f, isDark = true, dimBackground = false))
        assertEquals(0, WitLiquidGlassMenu.computeScrimAlpha(0.55f, isDark = true, dimBackground = false))
        assertEquals(0, WitLiquidGlassMenu.computeScrimAlpha(1f, isDark = true, dimBackground = false))
        assertEquals(0, WitLiquidGlassMenu.computeScrimAlpha(1f, isDark = false, dimBackground = false))

        // When dimBackground is explicitly enabled: alpha smoothly ramps up to 38 (dark) or 22 (light)
        assertEquals(0, WitLiquidGlassMenu.computeScrimAlpha(0f, isDark = true, dimBackground = true))
        assertEquals(38, WitLiquidGlassMenu.computeScrimAlpha(0.55f, isDark = true, dimBackground = true))
        assertEquals(38, WitLiquidGlassMenu.computeScrimAlpha(1f, isDark = true, dimBackground = true))
        assertEquals(22, WitLiquidGlassMenu.computeScrimAlpha(0.55f, isDark = false, dimBackground = true))
        assertEquals(22, WitLiquidGlassMenu.computeScrimAlpha(1f, isDark = false, dimBackground = true))
    }

    @Test
    fun menuDefaultsToRegularMaterialAndNeverDimsLightModeAutomatically() {
        // Menus always carry text rows and default to REGULAR even under ULTRA quality.
        assertEquals(
            WitLiquidGlassView.Material.REGULAR,
            WitLiquidGlassMenu.resolveMenuMaterial(explicitMaterial = null, anchorMaterial = null),
        )
        assertEquals(
            WitLiquidGlassView.Material.CLEAR,
            WitLiquidGlassMenu.resolveMenuMaterial(
                explicitMaterial = WitLiquidGlassView.Material.CLEAR,
                anchorMaterial = null,
            ),
        )
        assertEquals(
            WitLiquidGlassView.Material.CLEAR,
            WitLiquidGlassMenu.resolveMenuMaterial(
                explicitMaterial = null,
                anchorMaterial = WitLiquidGlassView.Material.CLEAR,
            ),
        )

        // REGULAR never auto-dims; explicit CLEAR only auto-dims for dark tone (white text), never light tone (dark text).
        assertEquals(
            0f,
            WitLiquidGlassMenu.resolveMenuClearDimming(null, null, WitLiquidGlassView.Material.REGULAR, isDark = false),
            0.0001f,
        )
        assertEquals(
            0f,
            WitLiquidGlassMenu.resolveMenuClearDimming(null, null, WitLiquidGlassView.Material.REGULAR, isDark = true),
            0.0001f,
        )
        assertEquals(
            0f,
            WitLiquidGlassMenu.resolveMenuClearDimming(null, null, WitLiquidGlassView.Material.CLEAR, isDark = false),
            0.0001f,
        )
        assertEquals(
            0.18f,
            WitLiquidGlassMenu.resolveMenuClearDimming(null, null, WitLiquidGlassView.Material.CLEAR, isDark = true),
            0.0001f,
        )

        // Default light panel frost tint must stay bright and opaque enough (>= 0x80 alpha) for dark text over dark art.
        val lightAlpha = (WitLiquidGlassMenu.DEFAULT_LIGHT_PANEL_TINT ushr 24) and 0xFF
        val lightRed = (WitLiquidGlassMenu.DEFAULT_LIGHT_PANEL_TINT ushr 16) and 0xFF
        val lightGreen = (WitLiquidGlassMenu.DEFAULT_LIGHT_PANEL_TINT ushr 8) and 0xFF
        val lightBlue = WitLiquidGlassMenu.DEFAULT_LIGHT_PANEL_TINT and 0xFF
        assertTrue("Light panel tint alpha should be >= 128, got $lightAlpha", lightAlpha >= 128)
        assertTrue("Light panel tint RGB should stay near-white", lightRed >= 240 && lightGreen >= 240 && lightBlue >= 240)
    }

    @Test
    fun singleRowMenuUsesCompactFullCapsuleMetricsAndConcentricHighlight() {
        val singleAction = listOf<WitLiquidGlassMenu.Entry>(
            WitLiquidGlassMenu.Entry.Action(label = "Report", destructive = true) {},
        )
        val singleWithDivider = listOf(
            WitLiquidGlassMenu.Entry.Action(label = "Report", destructive = true) {},
            WitLiquidGlassMenu.Entry.Divider,
        )
        val singleToggle = listOf<WitLiquidGlassMenu.Entry>(
            WitLiquidGlassMenu.Entry.Toggle(label = "Pin", isChecked = { false }, onToggle = {}),
        )
        val multiAction = listOf<WitLiquidGlassMenu.Entry>(
            WitLiquidGlassMenu.Entry.Action(label = "Action 1") {},
            WitLiquidGlassMenu.Entry.Action(label = "Action 2") {},
        )

        val single = WitLiquidGlassMenu.resolveLayoutMetrics(singleAction)
        assertTrue(single.isSingleRow)
        assertEquals(single, WitLiquidGlassMenu.resolveLayoutMetrics(singleWithDivider))
        assertEquals(single, WitLiquidGlassMenu.resolveLayoutMetrics(singleToggle))

        // Compact single-row capsule: 4dp + 48dp + 4dp = 56dp height (keeps >= 48dp touch target),
        // flush with anchor top (0dp rise), sleek 4:1 capsule width (224dp), and generous leading inset.
        assertEquals(4, single.containerPadVDp)
        assertEquals(48, single.rowMinHeightDp)
        assertEquals(56, single.containerPadVDp * 2 + single.rowMinHeightDp)
        assertEquals(56, single.minMenuHeightDp)
        assertEquals(0, single.topRiseOverAnchorDp)
        assertEquals(224, single.minWidthDp)
        assertEquals(4, single.rowHorizontalMarginDp)
        assertEquals(22, single.rowPadHDp)
        assertEquals(10, single.rowPadVDp)

        // Concentric capsule highlight: 28dp outer capsule radius - 4dp uniform inset = 24dp inner radius (= 48dp / 2).
        val density = 3f
        val singleOuterRadiusPx = 28f * density
        val singleHighlightRadiusPx = WitLiquidGlassMenu.computeHighlightRadiusPx(
            singleOuterRadiusPx,
            single.rowHorizontalMarginDp * density,
        )
        assertEquals(24f * density, singleHighlightRadiusPx, 0.001f)
        assertEquals(0f, WitLiquidGlassMenu.computeHighlightRadiusPx(2f, 8f), 0.001f)

        // Multi-row metrics stay completely unchanged.
        val multi = WitLiquidGlassMenu.resolveLayoutMetrics(multiAction)
        assertFalse(multi.isSingleRow)
        assertEquals(212, multi.minWidthDp)
        assertEquals(2, multi.topRiseOverAnchorDp)
        assertEquals(10, multi.containerPadVDp)
        assertEquals(8, multi.rowHorizontalMarginDp)
        assertEquals(14, multi.rowPadHDp)
        assertEquals(12, multi.rowPadVDp)
        assertEquals(50, multi.rowMinHeightDp)
        assertEquals(
            24f * density,
            WitLiquidGlassMenu.computeHighlightRadiusPx(32f * density, multi.rowHorizontalMarginDp * density),
            0.001f,
        )
    }
}
