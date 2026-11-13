package ceui.pixiv.ui.home

import android.app.Application
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import ceui.lisa.R
import ceui.lisa.databinding.RecyIllustStaggerBinding
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.math.max

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RecmdCardHeightTest {
    @Test
    fun cardHeightMatchesImageAndActionMinimum() {
        val app = RuntimeEnvironment.getApplication()
        app.setTheme(R.style.AppTheme)
        val density = app.resources.displayMetrics.density
        val four = (4 * density + 0.5f).toInt()
        val forty = (40 * density + 0.5f).toInt()
        val fortyEight = (48 * density + 0.5f).toInt()
        val parent = FrameLayout(app)
        val binding = RecyIllustStaggerBinding.inflate(LayoutInflater.from(app), parent, false)
        binding.badgeRow.visibility = View.GONE
        for (width in listOf(
            (180 * density).toInt(),
            (100 * density).toInt(),
            (80 * density).toInt()
        )) {
            for (ratio in listOf(0.6f, 1f, 2f)) {
                for (like in listOf(false, true)) {
                    for (snapshot in listOf(false, true)) {
                        binding.illustImage.setHeightRatio(ratio)
                        binding.likeButton.visibility = if (like) View.VISIBLE else View.GONE
                        binding.snapshotButton.visibility =
                            if (snapshot) View.VISIBLE else View.GONE
                        binding.root.measure(
                            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                        )
                        val imageHeight = (width * ratio).toInt()
                        val actionsHeight = when {
                            like && snapshot && width < fortyEight + forty + 3 * four -> fortyEight + forty + 2 * four
                            snapshot -> fortyEight + 2 * four
                            like -> forty + 2 * four
                            else -> 0
                        }
                        val expected = max(imageHeight, actionsHeight)
                        assertEquals(
                            "width=$width ratio=$ratio like=$like snapshot=$snapshot",
                            expected,
                            binding.root.measuredHeight
                        )
                    }
                }
            }
        }
    }
}