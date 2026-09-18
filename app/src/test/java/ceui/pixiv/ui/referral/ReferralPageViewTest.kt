package ceui.pixiv.ui.referral

import android.app.Application
import android.content.Context
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import ceui.lisa.R
import ceui.pixiv.shaftapi.ReferralRulesDto
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class ReferralPageViewTest {
    private class Actions : ReferralPageActions {
        var openedCard: Long? = null
        var selectedCampaign: String? = null
        var selectedTab: ReferralTab? = null
        var retried = false
        override fun back() = Unit
        override fun tab(tab: ReferralTab) { selectedTab = tab }
        override fun filter(filter: ReferralFilter) = Unit
        override fun retry() { retried = true }
        override fun open(kind: ReferralSheetKind, task: ReferralTask?, cardId: Long?) { openedCard = cardId }
        override fun campaign(campaign: String) { selectedCampaign = campaign }
        override fun toggleTheme() = Unit
    }

    private fun context(): Context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), androidx.appcompat.R.style.Theme_AppCompat)
    private fun View.labels(): List<TextView> = when (this) {
        is TextView -> listOf(this)
        is ViewGroup -> (0 until childCount).flatMap { getChildAt(it).labels() }
        else -> emptyList()
    }
    private fun state(snapshot: ReferralSnapshot, tab: ReferralTab = ReferralTab.TASKS, dark: Boolean = false, error: ReferralFailure? = null) =
        ReferralUiState(snapshot, tab, ReferralFilter.ALL, dark, null, false, error)
    private fun ReferralPageView.layoutAt(width: Int) {
        val pixels = (width * resources.displayMetrics.density).toInt()
        measure(View.MeasureSpec.makeMeasureSpec(pixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY))
        layout(0, 0, measuredWidth, measuredHeight)
    }

    @Test fun closedHistoricalWalletCanActivateTheChosenCardAndReturnToTasks() {
        for (dark in listOf(false, true)) {
            val ctx = context()
            val actions = Actions()
            val page = ReferralPageView(ctx, actions)
            val now = System.currentTimeMillis()
            val snapshot = ReferralSnapshot(campaign = "old", campaigns = listOf("new", "old"), enabled = false,
                tasks = listOf(ReferralTaskView(ReferralTask.INVITE, ReferralStatus.CLAIMED, 1, 1, "pro", 7, true, null, null)),
                cards = listOf(10L, 20L).map { ReferralCard(it, ReferralTask.INVITE, "pro", 7, now, now + 86_400_000) })
            page.render(state(snapshot, ReferralTab.WALLET, dark))
            page.layoutAt(if (dark) 800 else 320)
            val activate = ctx.getString(R.string.referral_activate, 7, "PRO")
            val buttons = page.labels().filter { it.text.toString() == activate }
            assertEquals(2, buttons.size)
            buttons[1].performClick()
            assertEquals(20L, actions.openedCard)
            page.labels().single { it.text.toString() == ctx.getString(R.string.referral_go_tasks) }.performClick()
            assertEquals(ReferralTab.TASKS, actions.selectedTab)
            page.labels().single { it.text.toString() == ctx.getString(R.string.referral_campaign, "new") }.performClick()
            assertEquals("new", actions.selectedCampaign)
        }
    }

    @Test fun loginRecoveryAndServerRuleCopyRenderWithLargeFonts() {
        val base = context()
        val config = android.content.res.Configuration(base.resources.configuration).apply { fontScale = 1.5f }
        val ctx = ContextThemeWrapper(base.createConfigurationContext(config), androidx.appcompat.R.style.Theme_AppCompat)
        val actions = Actions()
        val page = ReferralPageView(ctx, actions)
        page.render(state(ReferralSnapshot(), error = ReferralFailure(ReferralPlanViewModel.LOGIN_REQUIRED, R.string.referral_login_required)))
        page.labels().single { it.text.toString() == ctx.getString(R.string.go_to_login) }.performClick()
        assertTrue(actions.retried)
        val snapshot = ReferralSnapshot(enabled = true, rules = ReferralRulesDto(cardValidDays = 60),
            tasks = listOf(ReferralTaskView(ReferralTask.INVITE, ReferralStatus.NEW, 0, 1, "pro", 7, true, null, null)))
        page.render(state(snapshot, ReferralTab.WALLET))
        page.layoutAt(320)
        assertTrue(page.labels().any { it.text.toString() == ctx.getString(R.string.referral_wallet_empty_desc, 60) })
        assertTrue(page.labels().any { it.text.toString() == ctx.getString(R.string.referral_expiry_hint, 60) })
    }
}
