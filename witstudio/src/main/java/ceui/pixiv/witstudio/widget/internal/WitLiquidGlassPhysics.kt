package ceui.pixiv.witstudio.widget.internal

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * Shared 60fps reference-video calibrated Liquid Glass spring physics for all of `witstudio`.
 *
 * Calibrated from frame-by-frame measurements of iOS 26 Liquid Glass (`1290×2796 @3x`, `16.67ms/frame`):
 * - **Press swell**: `ω = 17.6 rad/s (k = 310), ζ = 0.65` -> `+6.8%` peak overshoot at `~225ms`,
 *   settles cleanly at `~350ms` (RMSE `0.0013`).
 * - **Release rebound**: `ω = 16.4 rad/s (k = 269), ζ = 0.61` -> `-8.9%` undershoot dip at `~230ms`,
 *   rebounds cleanly to `1.000` by `~350ms` (RMSE `0.0009`).
 * - **Droplet bloom / contraction**: lift press `k = 280, ζ = 0.72`; lift release `k = 310, ζ = 0.79`
 *   (`~290ms` crisp settle, RMSE `0.70px`).
 * - **Horizontal scrub / snap**: drag `k = 620, ζ = 0.86`; tap glide `k = 220, ζ = 0.82`;
 *   release center snap `ω = 12.8 rad/s (k = 164), ζ = 0.89` (`~350ms`, RMSE `0.43px`).
 * - **Area-conserving liquid stretch & rubber-band edge pull**: shared by both general
 *   [ceui.pixiv.witstudio.widget.WitLiquidGlassView] surfaces and
 *   [ceui.pixiv.witstudio.widget.WitLiquidGlassTabBar].
 */
internal object WitLiquidGlassPhysics {
    // ── Primary press & release rebound springs (1:1 fitted to 60fps reference video) ──
    internal const val PRESS_STIFFNESS: Float = 310f
    internal const val PRESS_DAMPING: Float = 0.65f
    internal const val RELEASE_STIFFNESS: Float = 269f
    internal const val RELEASE_DAMPING: Float = 0.61f

    /** Steady-state uniform press scale for a full-width (~360-390dp) floating glass bar (video: 1.0372-1.040x). */
    internal const val WIDE_BAR_PRESS_SCALE: Float = 1.040f

    /** Uniform vertical press scale matching the reference video's aspect-preserving bar expansion. */
    internal const val WIDE_BAR_PRESS_SCALE_Y: Float = 1.040f

    /** Liquid-droplet bloom scale for compact (<= 56dp) circular glass controls. */
    internal const val COMPACT_CONTROL_PRESS_SCALE: Float = 1.18f

    /** Minimum visual bloom window for quick taps (zero click delay). */
    internal const val MIN_CONTROL_TAP_BLOOM_MS: Long = 85L
    internal const val MIN_TAB_TAP_BLOOM_MS: Long = 120L

    // ── Directional liquid pull & area-conserving stretch (shared across all glass surfaces) ──
    internal const val PULL_ACTIVE_STIFFNESS: Float = 420f
    internal const val PULL_ACTIVE_DAMPING: Float = 0.80f
    internal const val PULL_MAX_SHIFT_DP: Float = 2.5f
    internal const val PULL_STRETCH_MAX: Float = 0.036f
    internal const val PULL_ORTHO_SQUISH_MAX: Float = 0.028f

    // ── Tab loupe droplet springs ──
    internal const val LIFT_PRESS_STIFFNESS: Float = 280f
    internal const val LIFT_PRESS_DAMPING: Float = 0.72f
    internal const val LIFT_RELEASE_STIFFNESS: Float = 310f
    internal const val LIFT_RELEASE_DAMPING: Float = 0.79f

    internal const val TRACK_DRAG_STIFFNESS: Float = 620f
    internal const val TRACK_DRAG_DAMPING: Float = 0.86f
    internal const val TRACK_TAP_STIFFNESS: Float = 220f
    internal const val TRACK_TAP_DAMPING: Float = 0.82f
    internal const val TRACK_RELEASE_STIFFNESS: Float = 164f
    internal const val TRACK_RELEASE_DAMPING: Float = 0.89f

    internal const val VEL_STRETCH_STIFFNESS: Float = 220f
    internal const val VEL_STRETCH_DAMPING: Float = 0.72f
    internal const val VEL_NORM_DP_PER_SEC: Float = 920f
    internal const val VEL_STRETCH_X_MAX: Float = 0.165f
    internal const val VEL_SQUASH_Y_MAX: Float = 0.180f

    internal const val WALL_SQUISH_STIFFNESS: Float = 340f
    internal const val WALL_SQUISH_DAMPING: Float = 0.78f
    internal const val MAX_OVER_TRAVEL_DP: Float = 14f
    internal const val OVER_DRAG_SCALE_DP: Float = 36f
    internal const val WALL_SQUASH_X_MAX: Float = 0.045f
    internal const val WALL_BULGE_Y_MAX: Float = 0.055f
    internal const val BAR_PULL_STRETCH_X_MAX: Float = 0.008f

    internal const val MAX_PHYSICS_SUBSTEP_SEC: Float = 1f / 240f
    internal const val MAX_FRAME_DT_SEC: Float = 0.05f

    /**
     * Size-adaptive steady press scale:
     * - Full-width bars (`>= 340dp`): `1.0372×` (exact reference outer bar scale)
     * - Medium multi-action capsules (`~140–200dp`, e.g. `fab_bar`): `~1.09–1.13×`
     * - Compact circular glass buttons (`<= 56dp`): `1.18×` (juicy water-droplet bloom)
     */
    internal fun computePressScaleTarget(widthPx: Int, heightPx: Int, density: Float): Float {
        val d = density.coerceAtLeast(0.5f)
        val maxSideDp = maxOf(widthPx, heightPx) / d
        val t = ((340f - maxSideDp) / (340f - 56f)).coerceIn(0f, 1f)
        // Quadratic-smooth curve so medium 160-200dp pills keep a substantial ~1.09-1.12x bloom.
        val smooth = t * t * (3f - 2f * t)
        val weighted = 0.35f * t + 0.65f * smooth
        return WIDE_BAR_PRESS_SCALE + (COMPACT_CONTROL_PRESS_SCALE - WIDE_BAR_PRESS_SCALE) * weighted
    }

    /** Hyperbolic rubber-band response mapping raw offset (px) to bounded unit pull `[-1, 1]`. */
    internal fun rubberBandUnit(offsetPx: Float, scalePx: Float): Float {
        val safeScale = scalePx.coerceAtLeast(1f)
        val a = abs(offsetPx)
        if (a <= 0.001f) return 0f
        return sign(offsetPx) * (1f - 1f / (1f + a / safeScale))
    }

    internal data class SpringState(
        var value: Float = 0f,
        var velocity: Float = 0f,
    ) {
        fun snap(target: Float) {
            value = target
            velocity = 0f
        }

        fun step(target: Float, stiffness: Float, dampingRatio: Float, dt: Float) {
            val omega = sqrt(stiffness)
            val damping = 2f * dampingRatio * omega
            val accel = stiffness * (target - value) - damping * velocity
            velocity += accel * dt
            value += velocity * dt
        }

        fun isSettled(target: Float, valueEps: Float, velEps: Float): Boolean =
            abs(target - value) <= valueEps && abs(velocity) <= velEps
    }

    /**
     * Unified interactive press-bloom, directional jelly pull, and elastic release-rebound physics
     * shared by every interactive [ceui.pixiv.witstudio.widget.WitLiquidGlassView].
     */
    internal class ControlPressPhysics {
        /** Normalized press expansion `[0 -> 1]` with reference `+6.8%` press overshoot & `-8.9%` release undershoot. */
        val swell = SpringState(0f, 0f)
        /** Normalized directional pull along X and Y in `[-1, 1]`. */
        val pullX = SpringState(0f, 0f)
        val pullY = SpringState(0f, 0f)

        var pressed: Boolean = false
        var targetPullX: Float = 0f
        var targetPullY: Float = 0f

        fun reset() {
            swell.snap(0f)
            pullX.snap(0f)
            pullY.snap(0f)
            pressed = false
            targetPullX = 0f
            targetPullY = 0f
        }

        fun step(dtSec: Float): Boolean {
            val totalDt = dtSec.coerceIn(0f, MAX_FRAME_DT_SEC)
            if (totalDt <= 0f) return !isSettled()

            val steps = max(1, (totalDt / MAX_PHYSICS_SUBSTEP_SEC).toInt() + 1)
            val subDt = totalDt / steps

            val targetSwell = if (pressed) 1f else 0f
            val swellStiff = if (pressed) PRESS_STIFFNESS else RELEASE_STIFFNESS
            val swellDamp = if (pressed) PRESS_DAMPING else RELEASE_DAMPING

            val pullStiff = if (pressed) PULL_ACTIVE_STIFFNESS else RELEASE_STIFFNESS
            val pullDamp = if (pressed) PULL_ACTIVE_DAMPING else RELEASE_DAMPING
            val tx = if (pressed) targetPullX else 0f
            val ty = if (pressed) targetPullY else 0f

            repeat(steps) {
                swell.step(targetSwell, swellStiff, swellDamp, subDt)
                pullX.step(tx, pullStiff, pullDamp, subDt)
                pullY.step(ty, pullStiff, pullDamp, subDt)
            }

            if (isSettled()) {
                swell.snap(targetSwell)
                pullX.snap(tx)
                pullY.snap(ty)
                return false
            }
            return true
        }

        fun isSettled(): Boolean {
            val targetSwell = if (pressed) 1f else 0f
            val tx = if (pressed) targetPullX else 0f
            val ty = if (pressed) targetPullY else 0f
            return swell.isSettled(targetSwell, 0.006f, 0.095f) &&
                pullX.isSettled(tx, 0.006f, 0.095f) &&
                pullY.isSettled(ty, 0.006f, 0.095f)
        }

        fun evaluate(widthPx: Int, heightPx: Int, density: Float): ControlTransform {
            val out = MutableControlTransform()
            evaluateInto(widthPx, heightPx, density, out)
            return ControlTransform(
                scaleX = out.scaleX,
                scaleY = out.scaleY,
                shiftX = out.shiftX,
                shiftY = out.shiftY,
                press = out.press,
                stretch = out.stretch,
            )
        }

        fun evaluateInto(widthPx: Int, heightPx: Int, density: Float, out: MutableControlTransform) {
            val baseDelta = computePressScaleTarget(widthPx, heightPx, density) - 1f
            val s = swell.value
            val uniform = 1f + baseDelta * s

            val px = pullX.value.coerceIn(-1.15f, 1.15f)
            val py = pullY.value.coerceIn(-1.15f, 1.15f)
            val ax = abs(px)
            val ay = abs(py)

            // Area-conserving directional jelly stretch along pull axis + orthogonal squish.
            val stretchFactorX = 1f + PULL_STRETCH_MAX * ax - PULL_ORTHO_SQUISH_MAX * ay
            val stretchFactorY = 1f + PULL_STRETCH_MAX * ay - PULL_ORTHO_SQUISH_MAX * ax

            val maxShiftPx = PULL_MAX_SHIFT_DP * density.coerceAtLeast(0.5f)
            out.scaleX = (uniform * stretchFactorX).coerceIn(0.90f, 1.30f)
            out.scaleY = (uniform * stretchFactorY).coerceIn(0.90f, 1.30f)
            out.shiftX = px * maxShiftPx
            out.shiftY = py * maxShiftPx
            out.press = s.coerceIn(0f, 1f)
            out.stretch = s
        }
    }

    internal class MutableControlTransform(
        var scaleX: Float = 1f,
        var scaleY: Float = 1f,
        var shiftX: Float = 0f,
        var shiftY: Float = 0f,
        var press: Float = 0f,
        var stretch: Float = 0f,
    )

    internal data class ControlTransform(
        val scaleX: Float,
        val scaleY: Float,
        val shiftX: Float,
        val shiftY: Float,
        val press: Float,
        val stretch: Float,
    )
}
