package ceui.pixiv.witstudio.widget.internal

import android.util.Log
import kotlin.math.ceil

/**
 * Counts padded pixels before a view enters HWUI's offscreen filter pipeline.
 * The budget is per host and applies to shader and blur surfaces alike.
 */
internal object WitLiquidGlassBudget {
    internal const val HOST_PIXELS = 2_000_000L
    private const val MAX_PATCH_PIXELS = 512_000L
    private const val MAX_PATCH_EDGE = 2_048

    internal fun inset(density: Float): Int = ceil(30f * density).toInt()

    internal fun patchPixels(width: Int, height: Int, density: Float, canvasEdge: Int): Long {
        if (width <= 0 || height <= 0) return 0
        val padding = 2L * inset(density)
        val paddedWidth = width.toLong() + padding
        val paddedHeight = height.toLong() + padding
        val edge = minOf(MAX_PATCH_EDGE, canvasEdge)
        if (paddedWidth > edge || paddedHeight > edge) return 0
        return (paddedWidth * paddedHeight).takeIf { it <= MAX_PATCH_PIXELS } ?: 0
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
