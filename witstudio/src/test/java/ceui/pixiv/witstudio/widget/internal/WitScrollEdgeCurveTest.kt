package ceui.pixiv.witstudio.widget.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WitScrollEdgeCurveTest {

    @Test
    fun blurAndScrimCurvesAreZeroAtBoundaryMonotonicAndInflectionFree() {
        val cases = listOf(
            // Status-bar-only (App Store Today style): ~44dp status bar + 44dp soft extension
            44f to 44f,
            // Floating header bar (Discover / Dynamic style): ~98dp chrome + 20dp soft tail
            98f to 20f,
            // Tall floating Toolbar + TabLayout (Recommend style): ~140dp chrome + 20dp soft tail
            140f to 20f,
        )

        for ((anchorH, featherH) in cases) {
            val totalH = anchorH + featherH
            assertEquals(1f, WitScrollEdgeCurve.evaluateBlurNorm(0f, anchorH, featherH), 0.0001f)
            assertEquals(0f, WitScrollEdgeCurve.evaluateBlurNorm(totalH, anchorH, featherH), 0.0001f)
            assertEquals(0f, WitScrollEdgeCurve.evaluateBlurNorm(totalH + 20f, anchorH, featherH), 0.0001f)

            assertEquals(1f, WitScrollEdgeCurve.evaluateScrimNorm(0f, anchorH, featherH), 0.0001f)
            assertEquals(0f, WitScrollEdgeCurve.evaluateScrimNorm(totalH, anchorH, featherH), 0.0001f)
            assertEquals(0f, WitScrollEdgeCurve.evaluateScrimNorm(totalH + 20f, anchorH, featherH), 0.0001f)

            // Monotonically non-increasing AND strictly convex (second difference >= 0, zero mid-band cliff).
            val steps = 200
            val blurs = FloatArray(steps + 1)
            val scrims = FloatArray(steps + 1)
            for (i in 0..steps) {
                val dist = totalH * i / steps.toFloat()
                blurs[i] = WitScrollEdgeCurve.evaluateBlurNorm(dist, anchorH, featherH)
                scrims[i] = WitScrollEdgeCurve.evaluateScrimNorm(dist, anchorH, featherH)
                if (i > 0) {
                    assertTrue(blurs[i] <= blurs[i - 1] + 1e-6f)
                    assertTrue(scrims[i] <= scrims[i - 1] + 1e-6f)
                }
                if (i >= 2) {
                    // Convexity: slope magnitude decreases smoothly toward bottom boundary -> zero harsh step.
                    val d1 = blurs[i - 2] - blurs[i - 1]
                    val d2 = blurs[i - 1] - blurs[i]
                    assertTrue("Blur slope must decrease smoothly toward boundary ($d2 > $d1)", d2 <= d1 + 1e-5f)
                }
            }

            // Near the bottom boundary (last 10% of total height), blur stays gossamer (< 0.02 -> < 0.36dp).
            val nearEdgeDist = totalH * 0.90f
            assertTrue(
                "Blur at 90% height must stay ultra-subtle (< 0.02), got ${WitScrollEdgeCurve.evaluateBlurNorm(nearEdgeDist, anchorH, featherH)}",
                WitScrollEdgeCurve.evaluateBlurNorm(nearEdgeDist, anchorH, featherH) < 0.02f,
            )
            assertTrue(
                "Scrim at 90% height must stay ultra-subtle (< 0.025), got ${WitScrollEdgeCurve.evaluateScrimNorm(nearEdgeDist, anchorH, featherH)}",
                WitScrollEdgeCurve.evaluateScrimNorm(nearEdgeDist, anchorH, featherH) < 0.025f,
            )
        }
    }

    @Test
    fun pyramidCompositeMatchesPhysicalGaussianRadiusWithZeroGhosting() {
        for (step in 0..100) {
            val blurNorm = step / 100f
            val weights = FloatArray(WitScrollEdgeCurve.PYRAMID_LEVELS + 1)
            weights[0] = 1f // L0 (sharp scene)
            for (layer in 0 until WitScrollEdgeCurve.PYRAMID_LEVELS) {
                val a = WitScrollEdgeCurve.evaluateLayerAlpha(blurNorm, layer)
                for (prev in 0..layer) {
                    weights[prev] *= (1f - a)
                }
                weights[layer + 1] = a
            }
            assertEquals(1f, weights.sum(), 1e-5f)

            // Weighted effective radius equals exact blurNorm (physical-space linear interpolation!).
            var blendedRatio = 0f
            for (layer in 0 until WitScrollEdgeCurve.PYRAMID_LEVELS) {
                blendedRatio += weights[layer + 1] * WitScrollEdgeCurve.PYRAMID_RADIUS_RATIOS[layer]
            }
            assertEquals(blurNorm, blendedRatio, 1e-4f)

            // Past L1 (1.0dp / 18dp = 0.0556), sharp L0 is 100% replaced (zero sharp-over-blur ghosting).
            if (blurNorm >= WitScrollEdgeCurve.PYRAMID_RADIUS_RATIOS[0]) {
                assertEquals(0f, weights[0], 1e-5f)
            }
            val activeLevels = weights.count { it > 1e-5f }
            assertTrue("At most 2 adjacent levels active at blurNorm=$blurNorm, got $activeLevels", activeLevels <= 2)
        }
    }

    @Test
    fun effectiveSigmaProgressesLinearlyInRadiusSpace() {
        assertEquals(0f, WitScrollEdgeCurve.evaluateEffectiveSigmaDp(0f, 18f), 0.001f)
        assertEquals(4.5f, WitScrollEdgeCurve.evaluateEffectiveSigmaDp(0.25f, 18f), 0.001f)
        assertEquals(9.0f, WitScrollEdgeCurve.evaluateEffectiveSigmaDp(0.50f, 18f), 0.001f)
        assertEquals(18.0f, WitScrollEdgeCurve.evaluateEffectiveSigmaDp(1.00f, 18f), 0.001f)
    }

    @Test
    fun pyramidBudgetFitsPhoneQhdAndWideTabletWithinGpuLimits() {
        // 1080p phone (3x density), 140dp tall top scroll edge -> 5-level pyramid stays compact (< 650K px).
        val phoneUltra = WitScrollEdgeCurve.planPyramid(1080, 420, 3f, 4096, ultra = true)
        assertEquals(5, phoneUltra.levels.size)
        assertTrue("Phone ULTRA pixels (${phoneUltra.totalPixels}) should stay compact", phoneUltra.totalPixels in 150_000L..650_000L)

        // Standard quality uses a single compact pass.
        val phoneStd = WitScrollEdgeCurve.planPyramid(1080, 420, 3f, 4096, ultra = false)
        assertEquals(1, phoneStd.levels.size)
        assertTrue(phoneStd.totalPixels in 40_000L..160_000L)

        // Wide landscape tablet (2560px wide, 2x density) fits cleanly under 2048px edge limit & budget.
        val tabletUltra = WitScrollEdgeCurve.planPyramid(2560, 280, 2f, 4096, ultra = true)
        assertEquals(5, tabletUltra.levels.size)
        for (level in tabletUltra.levels) {
            assertTrue(level.scaledWidth <= 2048)
            assertTrue(level.scaledHeight <= 2048)
        }
        assertTrue(tabletUltra.totalPixels in 1L..WitLiquidGlassBudget.HOST_PIXELS)

        assertEquals(0L, WitScrollEdgeCurve.planPyramid(0, 300, 3f, 4096, ultra = true).totalPixels)
        assertEquals(0L, WitScrollEdgeCurve.planPyramid(1080, 0, 3f, 4096, ultra = true).totalPixels)
        assertEquals(0L, WitScrollEdgeCurve.planPyramid(1080, 300, 3f, 0, ultra = true).totalPixels)
    }

    @Test
    fun fallbackAndStandardStopsEndCleanlyAtZeroAlpha() {
        val (fbPos, fbAlphas) = WitScrollEdgeCurve.buildFallbackAlphaStops(
            anchorH = 264f,
            featherH = 72f,
            isDark = true,
            opaqueHeaderFallback = true,
            scrimStrength = 1f,
        )
        assertEquals(0f, fbPos.first(), 0.0001f)
        assertEquals(1f, fbPos.last(), 0.0001f)
        assertTrue(fbAlphas.first() >= 245)
        // Two-row header (Toolbar + TabLayout, e.g. 144dp + 18dp feather): second row at ~83% of anchorH
        // must remain strongly opaque (>= 240/255) in NONE/low-RAM fallback so tabs stay legible over sharp art.
        val (_, twoRowFbAlphas) = WitScrollEdgeCurve.buildFallbackAlphaStops(
            anchorH = 144f,
            featherH = 18f,
            isDark = true,
            opaqueHeaderFallback = true,
            scrimStrength = 1f,
            sampleCount = 13,
        )
        // index 9 of 13 -> frac = 9/12 = 0.75 -> dist = 121.5dp (84.4% of 144dp anchorH, TabLayout centerline)
        assertTrue("Second-row tab fallback alpha (${twoRowFbAlphas[9]}) must protect legibility", twoRowFbAlphas[9] >= 240)
        assertEquals(0, fbAlphas.last())

        val (maskPos, maskAlphas) = WitScrollEdgeCurve.buildStandardBlurMaskStops(264f, 72f)
        assertEquals(0f, maskPos.first(), 0.0001f)
        assertEquals(1f, maskPos.last(), 0.0001f)
        assertEquals(255, maskAlphas.first())
        assertEquals(0, maskAlphas.last())

        val (_, veilAlphas) = WitScrollEdgeCurve.buildStandardVeilStops(264f, 72f, isDark = true, scrimStrength = 1f)
        assertTrue(veilAlphas.first() in 175..195)
        assertEquals(0, veilAlphas.last())
    }
}
