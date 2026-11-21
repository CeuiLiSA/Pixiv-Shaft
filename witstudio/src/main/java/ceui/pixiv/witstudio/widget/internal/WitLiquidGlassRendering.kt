package ceui.pixiv.witstudio.widget.internal

import android.util.Log
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Counts padded pixels before a view enters HWUI's offscreen filter pipeline.
 * The budget is per host and applies to shader and blur surfaces alike.
 */
internal object WitLiquidGlassBudget {
    internal const val HOST_PIXELS = 2_000_000L
    private const val MAX_PATCH_PIXELS = 1_000_000L
    private const val MAX_PANEL_PATCH_PIXELS = 1_300_000L
    private const val MAX_PATCH_EDGE = 2_048
    private const val MIN_PANEL_SCALE = 0.55f

    internal data class PatchPlan(val pixels: Long, val scale: Float)

    internal fun inset(density: Float): Int = ceil(30f * density).toInt()

    internal fun patchPixels(width: Int, height: Int, density: Float, canvasEdge: Int): Long {
        if (width <= 0 || height <= 0 || canvasEdge <= 0) return 0
        val padding = 2L * inset(density)
        val paddedWidth = width.toLong() + padding
        val paddedHeight = height.toLong() + padding
        val edge = minOf(MAX_PATCH_EDGE, canvasEdge)
        if (paddedWidth > edge || paddedHeight > edge) return 0
        return (paddedWidth * paddedHeight).takeIf { it <= MAX_PATCH_PIXELS } ?: 0
    }

    /**
     * Plans the offscreen patch for either a standard control or a transient floating panel (such as
     * a Liquid Glass menu). Panels have a wider pixel allowance and gracefully downscale on high-DPI
     * displays so live refraction stays active without exceeding [MAX_PANEL_PATCH_PIXELS] or [MAX_PATCH_EDGE].
     */
    internal fun planPatch(
        width: Int,
        height: Int,
        density: Float,
        canvasEdge: Int,
        largeSurface: Boolean,
    ): PatchPlan {
        if (width <= 0 || height <= 0 || canvasEdge <= 0) return PatchPlan(0L, 1f)
        val maxBudget = if (largeSurface) MAX_PANEL_PATCH_PIXELS else MAX_PATCH_PIXELS
        if (!largeSurface && patchPixels(width, height, density, canvasEdge) > 0) return PatchPlan(patchPixels(width, height, density, canvasEdge), 1f)

        val edge = minOf(MAX_PATCH_EDGE, canvasEdge)
        val rawPadding = 2L * inset(density)
        val rawW = width.toLong() + rawPadding
        val rawH = height.toLong() + rawPadding
        // Guard against unreasonably huge inputs (e.g., Int.MAX_VALUE) before multiplying.
        val maxRawEdge = (edge / MIN_PANEL_SCALE).toLong()
        if (rawW > maxRawEdge || rawH > maxRawEdge) return PatchPlan(0L, 1f)

        var scale = 1f
        if (rawW > edge) scale = minOf(scale, edge.toFloat() / rawW.toFloat())
        if (rawH > edge) scale = minOf(scale, edge.toFloat() / rawH.toFloat())
        val rawPixels = rawW * rawH
        if (rawPixels > maxBudget) {
            scale = minOf(scale, sqrt(maxBudget.toDouble() / rawPixels.toDouble()).toFloat())
        }
        if (scale < MIN_PANEL_SCALE) return PatchPlan(0L, 1f)

        repeat(3) {
            val scaledPadding = 2L * inset(density * scale)
            val sw = ceil(width * scale).toLong().coerceAtLeast(1L) + scaledPadding
            val sh = ceil(height * scale).toLong().coerceAtLeast(1L) + scaledPadding
            val pixels = sw * sh
            if (sw <= edge && sh <= edge && pixels <= maxBudget) {
                return PatchPlan(pixels, scale)
            }
            scale *= 0.96f
            if (scale < MIN_PANEL_SCALE) return PatchPlan(0L, 1f)
        }
        return PatchPlan(0L, 1f)
    }
}

/** A failing graphics backend is skipped for the rest of the process. Used on the UI thread. */
internal enum class WitLiquidGlassBackend {
    BACKDROP,
    REFRACTION;

    internal var available: Boolean = true
        private set

    internal inline fun <T> attempt(block: () -> T): T? {
        if (!available || !BACKDROP.available) return null
        try {
            return block()
        } catch (failure: RuntimeException) {
            disable(failure)
        } catch (failure: LinkageError) {
            disable(failure)
        } catch (failure: OutOfMemoryError) {
            disable(failure)
        }
        return null
    }

    private fun disable(failure: Throwable) {
        // Blur uses another offscreen layer too. After allocation failure, use the direct canvas.
        if (failure is OutOfMemoryError) {
            BACKDROP.available = false
            Log.w("WitLiquidGlass", "Backdrop disabled after an allocation failure")
        } else {
            available = false
            Log.w("WitLiquidGlass", "${name} disabled after a rendering failure", failure)
        }
    }
}
