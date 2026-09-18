package ceui.pixiv.ui.referral

import android.app.Application
import android.app.Activity
import android.content.Context
import android.graphics.Rect
import android.os.Looper
import android.os.LocaleList
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.ScrollView
import ceui.lisa.R
import ceui.pixiv.shaftapi.ReferralRulesDto
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class ReferralPageViewTest {
    private class Actions : ReferralPageActions {
        var openedCard: Long? = null
        var selectedCampaign: String? = null
        var retried = false
        override fun back() = Unit
        override fun filter(filter: ReferralFilter) = Unit
        override fun retry() { retried = true }
        override fun open(kind: ReferralSheetKind, task: ReferralTask?, cardId: Long?) { openedCard = cardId }
        override fun campaign(campaign: String) { selectedCampaign = campaign }
        override fun toggleTheme() = Unit
    }

    private fun context(fontScale: Float = 1f): Context {
        val app = RuntimeEnvironment.getApplication()
        val config = android.content.res.Configuration(app.resources.configuration).apply { this.fontScale = fontScale }
        return ContextThemeWrapper(app.createConfigurationContext(config), androidx.appcompat.R.style.Theme_AppCompat)
    }
    private fun View.labels(): List<TextView> = when (this) {
        is TextView -> listOf(this)
        is ViewGroup -> (0 until childCount).flatMap { getChildAt(it).labels() }
        else -> emptyList()
    }
    private fun state(snapshot: ReferralSnapshot, dark: Boolean = false, error: ReferralFailure? = null) =
        ReferralUiState(snapshot, ReferralFilter.ALL, dark, null, false, error)
    private fun ReferralPageView.layoutAt(width: Int) {
        val pixels = (width * resources.displayMetrics.density).toInt()
        measure(View.MeasureSpec.makeMeasureSpec(pixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY))
        layout(0, 0, measuredWidth, measuredHeight)
    }

    @Test fun closedHistoricalCampaignShowsTasksAndWalletTogetherAndActivatesTheChosenCard() {
        for (dark in listOf(false, true)) {
            val ctx = context()
            val actions = Actions()
            val page = ReferralPageView(ctx, actions)
            val now = System.currentTimeMillis()
            val snapshot = ReferralSnapshot(campaign = "old", campaigns = listOf("new", "old"), enabled = false,
                tasks = listOf(ReferralTaskView(ReferralTask.INVITE, ReferralStatus.CLAIMED, 1, 1, "pro", 7, true, null, null)),
                cards = listOf(10L, 20L).map { ReferralCard(it, ReferralTask.INVITE, "pro", 7, now, now + 86_400_000) })
            page.layoutAt(if (dark) 800 else 320)
            page.render(state(snapshot, dark))
            page.layoutAt(if (dark) 800 else 320)
            val activate = ctx.getString(R.string.referral_activate, 7, "PRO")
            val buttons = page.labels().filter { it.text.toString() == activate }
            assertEquals(2, buttons.size)
            buttons[1].performClick()
            assertEquals(20L, actions.openedCard)
            assertEquals(1, page.labels().count { it.text.toString().trim() == ctx.getString(R.string.referral_tasks) })
            assertEquals(1, page.labels().count { it.text.toString().trim() == ctx.getString(R.string.referral_wallet) })
            assertFalse(page.labels().any { it.text.toString() == ctx.getString(R.string.referral_open_wallet) })
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
        page.render(state(snapshot))
        page.layoutAt(320)
        assertTrue(page.labels().any { it.text.toString() == ctx.getString(R.string.referral_wallet_empty_desc, 60) })
        assertTrue(page.labels().any { it.text.toString() == ctx.getString(R.string.referral_expiry_hint, 60) })
    }

    @Test fun welcomeCardIsVisibleAlongsideTasksAcrossFiltersAndActivationUpdates() {
        for (width in listOf(320, 800)) for (dark in listOf(false, true)) for (fontScale in listOf(1f, 1.5f, 2f)) {
            val ctx = context(fontScale)
            val actions = Actions()
            val page = ReferralPageView(ctx, actions)
            page.layoutAt(width)
            val now = System.currentTimeMillis()
            val card = ReferralCard(42, ReferralTask.WELCOME, "pro", 7, now, now + 86_400_000)
            val snapshot = ReferralSnapshot(enabled = true,
                tasks = listOf(ReferralTaskView(ReferralTask.INVITE, ReferralStatus.NEW, 0, 1, "pro", 7, true, null, null)),
                cards = listOf(card))
            for (filter in ReferralFilter.entries) {
                page.render(state(snapshot, dark).copy(filter = filter))
                page.layoutAt(width)
                val wallet = page.labels().single { it.text.toString().trim() == ctx.getString(R.string.referral_wallet) }
                val tasks = page.labels().single { it.text.toString().trim() == ctx.getString(R.string.referral_tasks) }
                fun bounds(view: View) = Rect(0, 0, view.width, view.height).also { page.offsetDescendantRectToMyCoords(view, it) }
                if (width == 320) assertTrue(bounds(wallet).bottom < bounds(tasks).top)
                else assertTrue(bounds(wallet).left > bounds(tasks).right)
                val activate = page.labels().single { it.text.toString() == ctx.getString(R.string.referral_activate, 7, "PRO") }
                val parent = activate.parent as ViewGroup
                assertTrue("Activation action clipped at width=$width fontScale=$fontScale", activate.right <= parent.width - parent.paddingRight)
                assertTrue(activate.height >= activate.layout.height + activate.compoundPaddingTop + activate.compoundPaddingBottom)
                activate.performClick()
                assertEquals(42L, actions.openedCard)
            }
            page.render(state(snapshot.copy(cards = listOf(card.copy(activatedAt = now)), activeUntil = now + 7 * 86_400_000L), dark))
            assertFalse(page.labels().any { it.text.toString() == ctx.getString(R.string.referral_activate, 7, "PRO") })
            assertTrue(page.labels().any { it.text.toString() == ctx.getString(R.string.referral_card_active, ReferralPageView.date(now)) })
            assertTrue(page.labels().any { it.text.toString().trim() == ctx.getString(R.string.referral_tasks) })
        }
    }

    @Test fun viewingAClaimedRewardScrollsWithinTheSamePage() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            activity.setTheme(androidx.appcompat.R.style.Theme_AppCompat)
            val page = ReferralPageView(activity, Actions())
            activity.setContentView(page)
            val now = System.currentTimeMillis()
            page.render(state(ReferralSnapshot(enabled = true,
                tasks = listOf(ReferralTaskView(ReferralTask.INVITE, ReferralStatus.CLAIMED, 1, 1, "pro", 7, true, null, null)),
                cards = listOf(ReferralCard(42, ReferralTask.INVITE, "pro", 7, now, now + 86_400_000)))))
            shadowOf(Looper.getMainLooper()).idle()
            val wallet = page.labels().single { it.text.toString().trim() == activity.getString(R.string.referral_wallet) }
            page.labels().single { it.text.toString() == activity.getString(R.string.referral_see_reward) }.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(page.findViewById<ScrollView>(R.id.referral_scroll).scrollY > 0)
            assertSame(wallet, page.labels().single { it.text.toString().trim() == activity.getString(R.string.referral_wallet) })
            assertTrue(page.labels().any { it.text.toString().trim() == activity.getString(R.string.referral_tasks) })
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun walletNavigationSurvivesARefreshBeforeTheNextLayout() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            activity.setTheme(androidx.appcompat.R.style.Theme_AppCompat)
            val page = ReferralPageView(activity, Actions())
            activity.setContentView(page)
            val now = System.currentTimeMillis()
            val state = state(ReferralSnapshot(enabled = true,
                tasks = listOf(ReferralTaskView(ReferralTask.INVITE, ReferralStatus.CLAIMED, 1, 1, "pro", 7, true, null, null)),
                cards = listOf(ReferralCard(42, ReferralTask.INVITE, "pro", 7, now, now + 86_400_000))))
            page.render(state)
            shadowOf(Looper.getMainLooper()).idle()
            for (refreshFirst in listOf(true, false)) {
                page.findViewById<ScrollView>(R.id.referral_scroll).scrollTo(0, 0)
                if (refreshFirst) page.render(state)
                page.showWallet()
                if (!refreshFirst) page.render(state)
                shadowOf(Looper.getMainLooper()).idle()
                val scroll = page.findViewById<ScrollView>(R.id.referral_scroll)
                val wallet = page.labels().single { it.text.toString().trim() == activity.getString(R.string.referral_wallet) }
                val bounds = Rect(0, 0, wallet.width, wallet.height)
                scroll.offsetDescendantRectToMyCoords(wallet, bounds)
                assertEquals("Wallet not aligned after refreshFirst=$refreshFirst", scroll.paddingTop, bounds.top - scroll.scrollY)
            }
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun walletHeadingAndCountFitTheWideSidebarWithLongTranslations() {
        for (locale in listOf("en", "ru", "tr", "ja", "ko", "zh-TW", "zh-CN")) {
            val app = RuntimeEnvironment.getApplication()
            val config = android.content.res.Configuration(app.resources.configuration).apply {
                setLocales(LocaleList.forLanguageTags(locale)); fontScale = 2f
            }
            val ctx = ContextThemeWrapper(app.createConfigurationContext(config), androidx.appcompat.R.style.Theme_AppCompat)
            val page = ReferralPageView(ctx, Actions())
            page.layoutAt(800)
            page.render(state(ReferralSnapshot(enabled = true,
                tasks = listOf(ReferralTaskView(ReferralTask.INVITE, ReferralStatus.NEW, 0, 1, "pro", 7, true, null, null)))))
            page.layoutAt(800)
            val heading = page.labels().single { it.text.toString().trim() == ctx.getString(R.string.referral_wallet) }
            val row = heading.parent as ViewGroup
            val count = row.getChildAt(1) as TextView
            assertTrue("$locale: card count is clipped", count.width > 0 && count.right <= row.width)
            assertTrue("$locale: title is clipped", heading.width > 0 && heading.layout.getEllipsisCount(heading.lineCount - 1) == 0)
        }
    }
}
