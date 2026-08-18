package ceui.lisa.fragments

import com.github.panpf.zoomimage.util.IntSizeCompat
import com.github.panpf.zoomimage.zoom.ContentScaleCompat
import com.github.panpf.zoomimage.zoom.ScalesCalculator
import kotlin.math.round

/**
 * 基于库默认 [ScalesCalculator.Dynamic] 计算三档，但修正 mediumScale，
 * 确保库原生 switchScale 从最小档双击时不会跳过中间档。
 *
 * 库原生 calculateNextStepScaleWithRatio 的比较会先把数值 format(1)（四舍五入到 0.1），
 * 所以不能只按原始浮点阈值修正，必须用同样的 format(1) 判断。
 */
internal class NoSkipThreeStepScalesCalculator(
    private val delegate: ScalesCalculator = ScalesCalculator.Dynamic,
) : ScalesCalculator {

    override fun calculate(
        containerSize: IntSizeCompat,
        contentSize: IntSizeCompat,
        contentOriginSize: IntSizeCompat,
        contentScale: ContentScaleCompat,
        minScale: Float,
        initialScale: Float,
    ): ScalesCalculator.Result {
        val result = delegate.calculate(
            containerSize = containerSize,
            contentSize = contentSize,
            contentOriginSize = contentOriginSize,
            contentScale = contentScale,
            minScale = minScale,
            initialScale = initialScale,
        )
        val safeMedium = findSafeMedium(
            minScale = result.minScale,
            originalMedium = result.mediumScale,
            maxScale = result.maxScale,
        )
        return ScalesCalculator.Result(result.minScale, safeMedium, result.maxScale)
    }

    companion object {
        /** 复刻 ZoomImage calculateNextStepScaleWithRatio 的默认 deltaRatio。 */
        private const val LIBRARY_DELTA_RATIO = 0.35f

        /**
         * 从 originalMedium 开始向上微调，直到库原生「从 min 出发」的跳档判断会选中 medium。
         * 使用与库一致的 format(1) 比较，避免因四舍五入到 0.1 后仍然跳档。
         */
        internal fun findSafeMedium(
            minScale: Float,
            originalMedium: Float,
            maxScale: Float,
        ): Float {
            var safe = originalMedium.coerceAtMost(maxScale)
            repeat(1000) {
                if (safe >= maxScale) return maxScale
                val formattedMedium = format1(safe)
                val formattedCurrent = format1(
                    minScale + LIBRARY_DELTA_RATIO * (maxScale - safe)
                )
                if (formattedMedium > formattedCurrent) return safe
                safe += 0.01f
            }
            return safe.coerceAtMost(maxScale)
        }

        /** 与库内部 Float.format(1) 保持一致：四舍五入到 1 位小数。 */
        internal fun format1(value: Float): Float =
            (round(value * 10.0) / 10.0).toFloat()
    }
}
