package ceui.pixiv.witstudio.widget

import ceui.pixiv.witstudio.widget.internal.WitLiquidGlassPhysics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class WitLiquidGlassTabBarPhysicsTest {

    private fun capsuleArea(w: Float, h: Float): Float {
        val r = minOf(w, h) * 0.5f
        val straight = (w - h).coerceAtLeast(0f)
        return h * straight + (PI * r * r).toFloat()
    }

    @Test
    fun restingMetricsFitInsideBarAcrossThreeFourAndFiveTabs() {
        val d = 3f
        // Reference video 4-tab geometry: 387.9dp x 62.7dp bar
        val refW = 387.9f * d
        val refH = 62.7f * d
        val refCenters = floatArrayOf(56.6f * d, 147.8f * d, 239.4f * d, 331.3f * d)
        val refMetrics = WitLiquidGlassTabBar.computeMetrics(refW, refH, refCenters, d)
        assertTrue(refMetrics.valid)
        // Resting pill height ~53dp (video: 53.0dp) and width ~104dp (video: 105.7dp)
        assertTrue("Expected restH ~53dp, got ${refMetrics.restH / d}", refMetrics.restH / d in 51f..55.5f)
        assertTrue("Expected restW ~100..108dp, got ${refMetrics.restW / d}", refMetrics.restW / d in 98f..108f)
        // Held droplet screen height ~75dp (sleek iOS 26 1.15x ratio: protrudes ~4.9dp per side)
        val heldScreenH = refMetrics.heldH * WitLiquidGlassTabBar.BAR_PRESS_SCALE_Y / d
        assertTrue("Expected held screen H ~72..78dp, got $heldScreenH", heldScreenH in 72f..78f)
        // Held droplet screen width ~124dp (video: 122.7dp) and concentric with bar left/right ends
        val heldScreenW = refMetrics.heldW * WitLiquidGlassTabBar.BAR_PRESS_SCALE / d
        assertTrue("Expected held screen W ~118..132dp, got $heldScreenW", heldScreenW in 118f..132f)
        val protrudeV = (refMetrics.heldH - refMetrics.barH) * 0.5f
        val protrudeRight = (refMetrics.maxCx + refMetrics.heldW * 0.5f) - refMetrics.barW
        assertEquals(
            "Droplet right end protrusion should match vertical protrusion for concentric corners",
            protrudeV,
            protrudeRight,
            1.5f * d,
        )

        // Compact phone 360dp wide (328dp bar) with 3, 4, and 5 tabs never clips past bar edges at rest.
        val compactW = 328f * d
        val compactH = 60f * d
        for (tabCount in listOf(3, 4, 5)) {
            val slot = compactW / tabCount
            val centers = FloatArray(tabCount) { slot * (it + 0.5f) }
            val m = WitLiquidGlassTabBar.computeMetrics(compactW, compactH, centers, d)
            assertTrue("Expected valid metrics for $tabCount tabs", m.valid)
            assertTrue("Left edge stays inside bar for $tabCount tabs", m.minCx - m.restW * 0.5f >= 0f)
            assertTrue("Right edge stays inside bar for $tabCount tabs", m.maxCx + m.restW * 0.5f <= compactW)
            assertTrue("Rest pill maintains stadium aspect for $tabCount tabs", m.restW >= m.restH)
        }
    }

    @Test
    fun pressExpansionMatchesReferenceOuterBarOvershootAndDropletBloom() {
        val d = 3f
        val barW = 1164.1f
        val barH = 188.0f
        val centers = floatArrayOf(170f, 443f, 718f, 994f)
        val metrics = WitLiquidGlassTabBar.computeMetrics(barW, barH, centers, d)
        val physics = WitLiquidGlassTabBar.TabLoupePhysics()
        physics.snapToRest(centers[2])

        // Trigger press on tab 2
        physics.pressed = true
        var peakBarScale = 1f
        var peakBarMs = 0
        var scaleAt150Ms = 1f
        var dropScreenHAt150Ms = 0f
        var dropScreenHAt350Ms = 0f
        var scaleAt400Ms = 1f

        val dt = 1f / 60f
        for (frame in 1..25) {
            physics.step(dt, d)
            val ms = (frame * 1000f / 60f).toInt()
            val g = physics.evaluateGeometry(metrics)
            if (g.barScaleY > peakBarScale) {
                peakBarScale = g.barScaleY
                peakBarMs = ms
            }
            if (frame == 9) { // 150ms
                scaleAt150Ms = g.barScaleY
                dropScreenHAt150Ms = g.height * g.barScaleY
            }
            if (frame == 21) { // 350ms
                dropScreenHAt350Ms = g.height * g.barScaleY
            }
            if (frame == 24) { // 400ms
                scaleAt400Ms = g.barScaleY
            }
        }

        // Exact reference press spring: k=310, zeta=0.65 -> +6.8% overshoot (~1.0427) peaking ~225ms
        assertTrue("Expected barScale ~1.033..1.044 at 150ms, got $scaleAt150Ms", scaleAt150Ms in 1.033f..1.044f)
        assertTrue("Expected barScale overshoot 1.041..1.045, got $peakBarScale", peakBarScale in 1.041f..1.045f)
        assertTrue("Expected peak around 180..260ms, got ${peakBarMs}ms", peakBarMs in 180..260)
        assertEquals(WitLiquidGlassTabBar.BAR_PRESS_SCALE, scaleAt400Ms, 0.001f)

        // Droplet blooms from ~160px resting -> ~205+px by 150ms -> ~225px held steady (1.15x * 1.04x)
        assertTrue("Expected droplet height >198px by 150ms, got $dropScreenHAt150Ms", dropScreenHAt150Ms in 198f..240f)
        assertTrue("Expected held droplet height ~218..232px at 350ms, got $dropScreenHAt350Ms", dropScreenHAt350Ms in 218f..232f)
    }

    @Test
    fun releaseContractionMatchesReferenceUndershootReboundAndCenterSnap() {
        val d = 3f
        val barW = 1164.1f
        val barH = 188.0f
        val centers = floatArrayOf(170f, 443f, 718f, 994f)
        val metrics = WitLiquidGlassTabBar.computeMetrics(barW, barH, centers, d)
        val physics = WitLiquidGlassTabBar.TabLoupePhysics()

        // Start from settled held state slightly offset from tab 2 center (matching video Release #2: -39px offset)
        physics.snapToRest(centers[2] - 39f)
        physics.barScale.snap(WitLiquidGlassTabBar.BAR_PRESS_SCALE)
        physics.lift.snap(1f)
        physics.pressed = false
        physics.dragging = false
        physics.targetCx = centers[2]

        var minBarScale = 1f
        var minBarMs = 0
        var dropScreenHAt100Ms = 0f
        var dropScreenHAt283Ms = 0f
        var cxErrAt350Ms = 999f
        var runningAfter600Ms = true

        val dt = 1f / 60f
        for (frame in 1..40) {
            val keepGoing = physics.step(dt, d)
            val ms = (frame * 1000f / 60f).toInt()
            val g = physics.evaluateGeometry(metrics)
            if (g.barScaleY < minBarScale) {
                minBarScale = g.barScaleY
                minBarMs = ms
            }
            if (frame == 6) { // 100ms
                dropScreenHAt100Ms = g.height * g.barScaleY
            }
            if (frame == 17) { // 283ms
                dropScreenHAt283Ms = g.height * g.barScaleY
            }
            if (frame == 21) { // 350ms
                cxErrAt350Ms = kotlin.math.abs(g.cx - centers[2])
            }
            if (frame == 36) { // 600ms
                runningAfter600Ms = keepGoing
            }
        }

        // Exact reference release spring: k=269, zeta=0.61 -> -8.9% undershoot (~0.9964) around ~230ms
        assertTrue("Expected barScale undershoot 0.9945..0.9980, got $minBarScale", minBarScale in 0.9945f..0.9980f)
        assertTrue("Expected undershoot around 190..270ms, got ${minBarMs}ms", minBarMs in 190..270)
        assertTrue("Expected droplet height ~176..216px at 100ms, got $dropScreenHAt100Ms", dropScreenHAt100Ms in 176f..216f)
        assertEquals(metrics.restH, dropScreenHAt283Ms, 5.0f)
        assertTrue("Expected center settled within 2.5px by 350ms, got $cxErrAt350Ms", cxErrAt350Ms <= 2.5f)
        assertFalse("Expected spring loop fully settled by 600ms", runningAfter600Ms)
        assertEquals(1f, physics.barScale.value, 0.001f)
        assertEquals(0f, physics.lift.value, 0.001f)
    }

    @Test
    fun fastDragSquashesHeightStretchesWidthWithAreaConservationAndEdgeOverDragBulges() {
        val d = 3f
        val barW = 1164.1f
        val barH = 188.0f
        val centers = floatArrayOf(170f, 443f, 718f, 994f)
        val metrics = WitLiquidGlassTabBar.computeMetrics(barW, barH, centers, d)
        val physics = WitLiquidGlassTabBar.TabLoupePhysics()

        physics.snapToRest(centers[2])
        physics.barScale.snap(WitLiquidGlassTabBar.BAR_PRESS_SCALE)
        physics.lift.snap(1f)
        physics.pressed = true
        physics.dragging = true
        val stillGeom = physics.evaluateGeometry(metrics)
        val stillArea = capsuleArea(stillGeom.width, stillGeom.height)

        val dt = 1f / 60f
        var minDragH = stillGeom.height
        var maxDragW = stillGeom.width
        var areaRatioAtPeakStretch = 1f
        for (frame in 1..15) {
            physics.targetCx = (centers[2] - frame * 36f).coerceAtLeast(centers[0])
            physics.step(dt, d)
            val g = physics.evaluateGeometry(metrics)
            if (g.height < minDragH) {
                minDragH = g.height
                maxDragW = g.width
                areaRatioAtPeakStretch = capsuleArea(g.width, g.height) / stillArea
            }
        }
        assertTrue("Expected fast drag width > still width ($maxDragW vs ${stillGeom.width})", maxDragW > stillGeom.width * 1.08f)
        assertTrue("Expected fast drag height < still height ($minDragH vs ${stillGeom.height})", minDragH < stillGeom.height * 0.90f)
        assertTrue("Expected capsule area conserved within +/-8%, got $areaRatioAtPeakStretch", areaRatioAtPeakStretch in 0.92f..1.08f)

        val edge = WitLiquidGlassTabBar.computeEdgeOverDrag(centers[0] - 90f * d, metrics.minCx, metrics.maxCx, d)
        assertTrue("Over-drag should allow bounded overhang past first tab", edge.overShiftPx < -5f * d && edge.overShiftPx >= -14f * d)
        assertTrue("Wall squish pressure should engage", edge.wallSquish > 0.5f)
        physics.targetCx = edge.targetCx
        physics.targetWallSquish = edge.wallSquish
        physics.targetBarShiftX = -WitLiquidGlassTabBar.BAR_PULL_SHIFT_MAX_DP * d * edge.wallSquish
        physics.targetBarPullStretchX = WitLiquidGlassTabBar.BAR_PULL_STRETCH_X_MAX * edge.wallSquish
        repeat(35) { physics.step(dt, d) }
        val wallGeom = physics.evaluateGeometry(metrics)
        assertTrue("Wall squish should bulge height taller than still held (${wallGeom.height} vs ${stillGeom.height})", wallGeom.height > stillGeom.height * 1.02f)
        assertTrue("Wall squish should compress width narrower than still held (${wallGeom.width} vs ${stillGeom.width})", wallGeom.width < stillGeom.width * 0.985f)
        assertTrue("Outer bar should pull slightly leftward (${wallGeom.barShiftX}px)", wallGeom.barShiftX < -3f)
    }

    @Test
    fun glassViewAdaptivePressScaleMatchesWideBarMediumPillAndCompactButtonCalibration() {
        val d = 3f
        val wideBarScale = WitLiquidGlassView.computePressScaleTarget((388 * d).toInt(), (63 * d).toInt(), d)
        val dockScale = WitLiquidGlassView.computePressScaleTarget((192 * d).toInt(), (48 * d).toInt(), d)
        val circleScale = WitLiquidGlassView.computePressScaleTarget((48 * d).toInt(), (48 * d).toInt(), d)

        assertEquals(1.040f, wideBarScale, 0.0005f)
        assertTrue("Expected mid-size dock scale ~1.09..1.14x, got $dockScale", dockScale in 1.088f..1.140f)
        assertEquals(1.18f, circleScale, 0.0005f)
    }

    @Test
    fun sharedControlPressPhysicsMatchesReferenceOvershootDirectionalPullAndReleaseRebound() {
        val d = 3f
        val w = (48 * d).toInt()
        val h = (48 * d).toInt()
        val ctrl = WitLiquidGlassPhysics.ControlPressPhysics()
        val dt = 1f / 60f

        // 1. Press bloom on 48dp circular glass button -> exact +6.8% overshoot above 1.18x steady
        ctrl.pressed = true
        var peakScale = 1f
        var peakMs = 0
        for (frame in 1..25) {
            ctrl.step(dt)
            val xf = ctrl.evaluate(w, h, d)
            if (xf.scaleX > peakScale) {
                peakScale = xf.scaleX
                peakMs = (frame * 1000f / 60f).toInt()
            }
        }
        val steadyPressed = ctrl.evaluate(w, h, d)
        assertTrue("Expected press overshoot > 1.188x, got $peakScale", peakScale in 1.188f..1.202f)
        assertTrue("Expected peak around 180..260ms, got ${peakMs}ms", peakMs in 180..260)
        assertEquals(1.18f, steadyPressed.scaleX, 0.004f)

        // 2. Horizontal directional pull -> stretches X, squishes Y, shifts gently right
        ctrl.targetPullX = 0.85f
        ctrl.targetPullY = 0f
        repeat(20) { ctrl.step(dt) }
        val pulled = ctrl.evaluate(w, h, d)
        assertTrue("Pull right should elongate scaleX (${pulled.scaleX} > ${steadyPressed.scaleX})", pulled.scaleX > steadyPressed.scaleX * 1.045f)
        assertTrue("Pull right should squish scaleY (${pulled.scaleY} < ${steadyPressed.scaleY})", pulled.scaleY < steadyPressed.scaleY * 0.965f)
        assertTrue("Pull right should shift tangibly right (${pulled.shiftX}px)", pulled.shiftX in 9f..14f)

        // 3. Release -> exact -8.9% elastic undershoot below 0.992x and settle cleanly back to 1.000 / 0 shift
        ctrl.pressed = false
        ctrl.targetPullX = 0f
        ctrl.targetPullY = 0f
        var minScale = 1f
        var runningAfter600Ms = true
        for (frame in 1..40) {
            val running = ctrl.step(dt)
            val xf = ctrl.evaluate(w, h, d)
            if (xf.scaleY < minScale) minScale = xf.scaleY
            if (frame == 36) runningAfter600Ms = running
        }
        val settled = ctrl.evaluate(w, h, d)
        assertTrue("Expected elastic release undershoot below 0.992x, got $minScale", minScale in 0.974f..0.992f)
        assertFalse("Expected control physics settled by 600ms", runningAfter600Ms)
        assertEquals(1f, settled.scaleX, 0.001f)
        assertEquals(1f, settled.scaleY, 0.001f)
        assertEquals(0f, settled.shiftX, 0.05f)
    }

    @Test
    fun stationaryHoldSettlesFrameLoopAtActiveTargetsAndResumesOnMoveOrRelease() {
        val d = 3f
        val centers = floatArrayOf(170f, 443f, 718f, 994f)
        val dt = 1f / 60f

        // 1. TabLoupePhysics stops ticking once held press converges (no 120fps stationary busy-loop),
        //    preserving full held lift/scale instead of snapping back to rest.
        val tabPhysics = WitLiquidGlassTabBar.TabLoupePhysics()
        tabPhysics.snapToRest(centers[1])
        tabPhysics.pressed = true
        var tabRunning = true
        repeat(45) { tabRunning = tabPhysics.step(dt, d) }
        assertFalse("Expected held TabLoupePhysics to settle within 750ms", tabRunning)
        assertTrue(tabPhysics.isSettled())
        assertEquals(WitLiquidGlassTabBar.BAR_PRESS_SCALE, tabPhysics.barScale.value, 0.0005f)
        assertEquals(1f, tabPhysics.lift.value, 0.0005f)

        // Dragging wakes physics cleanly and converges again at the new target.
        tabPhysics.dragging = true
        tabPhysics.targetCx = centers[2]
        assertFalse("Dragging to new tab must unsettle physics", tabPhysics.isSettled())
        assertTrue(tabPhysics.step(dt, d))
        repeat(45) { tabRunning = tabPhysics.step(dt, d) }
        assertFalse(tabRunning)
        assertEquals(centers[2], tabPhysics.dropCx.value, 0.5f)
        assertEquals(1f, tabPhysics.lift.value, 0.0005f)

        // Release unsettles again and returns cleanly to rest (1.0 / 0.0).
        tabPhysics.pressed = false
        tabPhysics.dragging = false
        assertFalse(tabPhysics.isSettled())
        repeat(45) { tabRunning = tabPhysics.step(dt, d) }
        assertFalse(tabRunning)
        assertEquals(1f, tabPhysics.barScale.value, 0.0005f)
        assertEquals(0f, tabPhysics.lift.value, 0.0005f)

        // 2. ControlPressPhysics also settles cleanly while pressed stationary and wakes on pull/release.
        val ctrl = WitLiquidGlassPhysics.ControlPressPhysics()
        ctrl.pressed = true
        var ctrlRunning = true
        repeat(45) { ctrlRunning = ctrl.step(dt) }
        assertFalse("Expected held ControlPressPhysics to settle within 750ms", ctrlRunning)
        assertTrue(ctrl.isSettled())
        assertEquals(1f, ctrl.swell.value, 0.0005f)

        ctrl.targetPullX = 0.7f
        assertFalse("Directional pull must unsettle held control physics", ctrl.isSettled())
        repeat(35) { ctrlRunning = ctrl.step(dt) }
        assertFalse(ctrlRunning)
        assertEquals(0.7f, ctrl.pullX.value, 0.001f)

        // Returning inside deadzone (targetPullX = 0f) unsettles and centers back while still pressed.
        ctrl.targetPullX = 0f
        assertFalse(ctrl.isSettled())
        repeat(35) { ctrlRunning = ctrl.step(dt) }
        assertFalse(ctrlRunning)
        assertEquals(0f, ctrl.pullX.value, 0.001f)
        assertEquals(1f, ctrl.swell.value, 0.0005f)
    }

    @Test
    fun suddenDragStopProducesLiquidJellyBrakingReboundAndVerticalPullSettlesCleanly() {
        val d = 3f
        val barW = 1164.1f
        val barH = 188.0f
        val centers = floatArrayOf(170f, 443f, 718f, 994f)
        val metrics = WitLiquidGlassTabBar.computeMetrics(barW, barH, centers, d)
        val physics = WitLiquidGlassTabBar.TabLoupePhysics()

        physics.snapToRest(centers[3])
        physics.barScale.snap(WitLiquidGlassTabBar.BAR_PRESS_SCALE)
        physics.lift.snap(1f)
        physics.pressed = true
        physics.dragging = true
        val stillGeom = physics.evaluateGeometry(metrics)
        val dt = 1f / 60f

        // Moderate thumb drag speed (~420dp/s) already produces clear horizontal liquid stretch.
        var peakStretchDuringDrag = 0f
        for (frame in 1..12) {
            physics.targetCx = (centers[3] - frame * 21f).coerceAtLeast(centers[1])
            physics.step(dt, d)
            val g = physics.evaluateGeometry(metrics)
            if (g.velocityStretch > peakStretchDuringDrag) {
                peakStretchDuringDrag = g.velocityStretch
            }
        }
        assertTrue(
            "Expected normal drag speed to engage >=0.45 stretch, got $peakStretchDuringDrag",
            peakStretchDuringDrag >= 0.45f,
        )

        // Sudden stop on target tab -> underdamped jelly braking rebounds vertically plumper than still held.
        val stopX = physics.targetCx
        var minVelStretch = 0f
        var maxReboundH = stillGeom.height
        for (frame in 1..30) {
            physics.targetCx = stopX
            physics.step(dt, d)
            val g = physics.evaluateGeometry(metrics)
            if (g.velocityStretch < minVelStretch) minVelStretch = g.velocityStretch
            if (g.height > maxReboundH) maxReboundH = g.height
        }
        assertTrue("Expected negative jelly braking rebound, got $minVelStretch", minVelStretch < -0.02f)
        assertTrue(
            "Expected braking plump height > still height ($maxReboundH vs ${stillGeom.height})",
            maxReboundH > stillGeom.height * 1.004f,
        )

        // Vertical damped bar pull engages during press and settles cleanly to 0 on release.
        physics.targetBarShiftY = WitLiquidGlassTabBar.BAR_PULL_SHIFT_Y_MAX_DP * d
        repeat(20) { physics.step(dt, d) }
        val pulledGeom = physics.evaluateGeometry(metrics)
        assertTrue("Expected vertical bar pull > 3px, got ${pulledGeom.barShiftY}", pulledGeom.barShiftY > 3f)

        physics.pressed = false
        physics.dragging = false
        physics.targetBarShiftY = 0f
        repeat(45) { physics.step(dt, d) }
        val settled = physics.evaluateGeometry(metrics)
        assertEquals(0f, settled.barShiftY, 0.1f)
    }

    @Test
    fun crossBarTapGlideReachesDestinationQuicklyWithVelocityStretch() {
        val d = 3f
        val barW = 1164.1f
        val barH = 188.0f
        val centers = floatArrayOf(170f, 443f, 718f, 994f)
        val metrics = WitLiquidGlassTabBar.computeMetrics(barW, barH, centers, d)
        val physics = WitLiquidGlassTabBar.TabLoupePhysics()

        // Tap from Tab 0 across to Tab 3
        physics.snapToRest(centers[0])
        physics.pressed = true
        physics.dragging = false
        physics.targetCx = centers[3]

        val dt = 1f / 60f
        var peakStretch = 0f
        for (frame in 1..12) { // 200ms
            physics.step(dt, d)
            val g = physics.evaluateGeometry(metrics)
            if (g.velocityStretch > peakStretch) peakStretch = g.velocityStretch
        }
        val distRemaining = kotlin.math.abs(physics.dropCx.value - centers[3])
        assertTrue("Expected tap glide to produce visible liquid stretch, got $peakStretch", peakStretch > 0.35f)
        assertTrue("Expected tap glide to approach Tab 3 within 200ms (remaining ${distRemaining}px)", distRemaining < metrics.restW * 0.45f)
    }
}
