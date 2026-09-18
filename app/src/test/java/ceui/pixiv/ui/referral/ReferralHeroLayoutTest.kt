package ceui.pixiv.ui.referral

import android.app.Application
import android.content.res.Configuration
import android.graphics.Rect
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class ReferralHeroLayoutTest {
    private val actions = object : ReferralPageActions {
        override fun back() = Unit
        override fun tab(tab: ReferralTab) = Unit
        override fun filter(filter: ReferralFilter) = Unit
        override fun open(kind: ReferralSheetKind, task: ReferralTask?, cardId: Long?) = Unit
        override fun campaign(campaign: String) = Unit
        override fun toggleTheme() = Unit
        override fun retry() = Unit
    }

    private fun View.descendants(): List<View> = listOf(this) +
        if (this is ViewGroup) (0 until childCount).flatMap { getChildAt(it).descendants() } else emptyList()

    @Test fun artworkFitsItsHeroWithoutCoveringTextAtNarrowWideAndLargeFontSizes() {
        for (widthDp in listOf(320, 412, 800)) for (fontScale in listOf(1f, 1.5f, 2f)) {
            val app = RuntimeEnvironment.getApplication()
            val config = Configuration(app.resources.configuration).apply { this.fontScale = fontScale }
            val context = ContextThemeWrapper(app.createConfigurationContext(config), androidx.appcompat.R.style.Theme_AppCompat)
            val page = ReferralPageView(context, actions)
            val width = (widthDp * context.resources.displayMetrics.density).toInt()
            fun measure() {
                page.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY))
                page.layout(0, 0, page.measuredWidth, page.measuredHeight)
            }
            measure()
            page.render(ReferralUiState(
                ReferralSnapshot(enabled = true, inviteUrl = "https://example.com/invite",
                    tasks = listOf(ReferralTaskView(ReferralTask.INVITE, ReferralStatus.NEW, 0, 1, "pro", 7, true, null, null))),
                ReferralTab.TASKS, ReferralFilter.ALL, widthDp == 800, null, false, null))
            measure()
            val art = page.descendants().filterIsInstance<ReferralHeroArtView>().single()
            val hero = generateSequence(art.parent as ViewGroup) { it.parent as? ViewGroup }
                .first { it.background != null }
            fun bounds(view: View) = Rect(0, 0, view.width, view.height).also { hero.offsetDescendantRectToMyCoords(view, it) }
            val artBounds = bounds(art)
            val scenario = "width=$widthDp, fontScale=$fontScale"
            assertTrue(scenario, art.width > 0 && art.height > 0)
            assertTrue(scenario, Rect(0, 0, hero.width, hero.height).contains(artBounds))
            hero.descendants().filterIsInstance<TextView>().forEach { text ->
                assertFalse("$scenario: ${text.text}", Rect.intersects(artBounds, bounds(text)))
            }
        }
    }
}
