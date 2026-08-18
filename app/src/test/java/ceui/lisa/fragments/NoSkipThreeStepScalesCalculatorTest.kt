package ceui.lisa.fragments

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.round

class NoSkipThreeStepScalesCalculatorTest {

    @Test
    fun `findSafeMedium prevents skipping middle from min`() {
        val cases = listOf(
            Triple(1f, 3f, 9f),
            Triple(0.5f, 2f, 8f),
            Triple(1f, 4f, 16f),
            Triple(2f, 6f, 18f),
            Triple(1f, 3f, 6f),
        )
        for ((min, originalMedium, max) in cases) {
            val safe = NoSkipThreeStepScalesCalculator.findSafeMedium(
                minScale = min,
                originalMedium = originalMedium,
                maxScale = max,
            )
            val next = nextStepScale(floatArrayOf(min, safe, max), min)
            assertEquals(
                "min=$min originalMedium=$originalMedium max=$max should not skip, safe=$safe",
                safe, next, 1e-6f,
            )
            assertTrue("safe medium should not be smaller than original", safe >= originalMedium)
            assertTrue("safe medium should stay below max", safe < max)
        }
    }

    @Test
    fun `format1 matches library one-decimal rounding`() {
        assertEquals(3.1f, NoSkipThreeStepScalesCalculator.format1(3.14f), 0.0001f)
        assertEquals(3.2f, NoSkipThreeStepScalesCalculator.format1(3.15f), 0.0001f)
    }

    private fun format1(value: Float): Float =
        (round(value * 10.0) / 10.0).toFloat()

    /** 复刻 ZoomImage calculateNextStepScaleWithRatio（含 format(1) 比较）。 */
    private fun nextStepScale(
        stepScales: FloatArray,
        currentScale: Float,
        deltaRatio: Float = 0.35f,
    ): Float {
        if (stepScales.isEmpty()) return currentScale
        if (stepScales.size > 1) {
            stepScales.forEachIndexed { index, scale ->
                val delta = if (index < stepScales.lastIndex) {
                    (stepScales[index + 1] - scale) * deltaRatio
                } else {
                    (scale - stepScales[index - 1]) * deltaRatio
                }
                if (format1(scale) > format1(currentScale + delta)) return scale
            }
        }
        return stepScales.first()
    }
}
