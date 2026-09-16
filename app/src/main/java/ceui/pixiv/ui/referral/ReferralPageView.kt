package ceui.pixiv.ui.referral

import android.content.Context
import android.graphics.Color
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.TextViewCompat
import ceui.lisa.R
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

internal interface ReferralPageActions {
    fun back()
    fun tab(tab: ReferralTab)
    fun filter(filter: ReferralFilter)
    fun open(kind: ReferralSheetKind, task: ReferralTask? = null)
    fun toggleTheme()
}

/** Native layout corresponding to mockup/referral-plan; no web rendering or bitmap UI. */
internal class ReferralPageView(context: Context, private val actions: ReferralPageActions) : FrameLayout(context) {
    private val scroll = ScrollView(context).apply {
        isFillViewport = true; isVerticalScrollBarEnabled = false; clipToPadding = false
        id = R.id.referral_scroll
    }
    private val contentHost = FrameLayout(context)
    private val bottom = LinearLayout(context).apply { gravity = Gravity.CENTER; orientation = LinearLayout.HORIZONTAL }
    private val statusScrim = View(context)
    private var lastState: ReferralUiState? = null
    private var ui: ReferralUi? = null
    private var systemTop = 0
    private var systemBottom = 0
    private var sectionsY = 0
    private var lastWide = false

    init {
        scroll.addView(contentHost, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(bottom, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        addView(statusScrim, LayoutParams(LayoutParams.MATCH_PARENT, 0, Gravity.TOP))
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            systemTop = bars.top; systemBottom = bars.bottom
            setPadding(bars.left, 0, bars.right, 0)
            applyInsets()
            insets
        }
    }

    private fun applyInsets() {
        val u = ui ?: return
        scroll.setPadding(0, systemTop, 0, u.dp(96) + systemBottom)
        bottom.setPadding(u.dp(15), u.dp(10), u.dp(15), u.dp(10) + systemBottom)
        statusScrim.layoutParams.height = systemTop
        statusScrim.requestLayout()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val wide = w / resources.displayMetrics.density >= 760
        if (wide != lastWide) post { lastState?.let(::render) }
    }

    fun render(state: ReferralUiState) {
        val oldScroll = scroll.scrollY
        val changedTab = lastState?.tab != null && lastState?.tab != state.tab
        lastState = state
        val colors = ReferralColors(context, state.darkOverride, state.accentOverride)
        val u = ReferralUi(context, colors)
        ui = u
        val available = (if (width > 0) width else resources.displayMetrics.widthPixels) / resources.displayMetrics.density
        val wide = available >= 760
        lastWide = wide
        setBackgroundColor(colors.bg); statusScrim.setBackgroundColor(colors.bg)
        bottom.background = u.shape(ColorUtils.setAlphaComponent(colors.bg, 245), 0f, colors.line)
        contentHost.removeAllViews()
        val page = u.column()
        contentHost.addView(page, LayoutParams(if (available > 1180) u.dp(1180) else LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        u.add(page, topBar(u), height = u.dp(64))
        val body = u.column().apply { setPadding(u.dp(if (wide) 36 else 20), u.dp(18), u.dp(if (wide) 36 else 20), 0) }
        u.add(page, body)
        u.add(body, heading(u))
        u.add(body, hero(u, wide), top = 23)

        val main = if (wide) u.row().apply { gravity = Gravity.TOP } else u.column()
        u.add(body, main, top = 29)
        val tasks = u.column()
        if (wide) u.add(main, tasks, width = 0, weight = 1f) else u.add(main, tasks)
        if (state.tab == ReferralTab.TASKS) {
            sectionHeader(u, tasks, u.s(R.string.referral_tasks), u.s(R.string.referral_tasks_subtitle), "04")
            u.add(tasks, filters(u, state), top = 19)
            val visible = ReferralTask.entries.filter { task -> when (state.filter) {
                ReferralFilter.ALL -> true
                ReferralFilter.READY -> state.snapshot.status(task) == ReferralStatus.READY
                ReferralFilter.PROGRESS -> state.snapshot.status(task) in listOf(ReferralStatus.PROGRESS, ReferralStatus.PENDING, ReferralStatus.REJECTED)
            } }
            if (visible.isEmpty()) u.add(tasks, empty(u,
                if (state.filter == ReferralFilter.READY) R.string.referral_empty_ready else R.string.referral_empty_progress,
                if (state.filter == ReferralFilter.READY) R.string.referral_empty_ready_desc else R.string.referral_empty_progress_desc,
            ) { actions.filter(ReferralFilter.ALL) }, top = 13)
            visible.forEach { u.add(tasks, taskCard(u, it, state.snapshot), top = 12) }
        } else {
            sectionHeader(u, tasks, u.s(R.string.referral_wallet), u.s(R.string.referral_wallet_subtitle), state.snapshot.cards.size.toString().padStart(2, '0'))
            val now = System.currentTimeMillis()
            if (state.snapshot.activeUntil > now) u.add(tasks,
                note(u, u.s(R.string.referral_active_until, date(state.snapshot.activeUntil))), top = 20)
            if (state.snapshot.cards.isEmpty()) u.add(tasks, empty(u,
                R.string.referral_wallet_empty, R.string.referral_wallet_empty_desc,
            ) { actions.tab(ReferralTab.TASKS) }, top = 24)
            state.snapshot.cards.forEach { u.add(tasks, walletCard(u, it), top = 16) }
        }
        u.add(tasks, u.text(R.string.referral_gentle, 10f, color = colors.muted).apply {
            gravity = Gravity.CENTER; setPadding(0, u.dp(8), 0, u.dp(8))
            setCompoundDrawablesRelativeWithIntrinsicBounds(u.icon(ReferralIcon.HEART, colors.muted, 13), null, null, null)
            compoundDrawablePadding = u.dp(6)
        }, top = 16)
        val side = u.column()
        if (wide) {
            u.add(main, side, width = u.dp(288))
            (side.layoutParams as LinearLayout.LayoutParams).marginStart = u.dp(30)
        } else u.add(main, side, top = 20)
        u.add(side, summary(u, state.snapshot))
        u.add(side, journey(u), top = 28)
        u.add(body, footer(u), top = 24)
        buildBottom(u, state)
        applyInsets()
        body.post {
            sectionsY = body.top + main.top
            scroll.scrollTo(0, if (changedTab) sectionsY else oldScroll)
        }
        ViewCompat.requestApplyInsets(this)
    }

    private fun topBar(u: ReferralUi) = u.row().apply {
        setPadding(u.dp(6), 0, u.dp(10), 0)
        u.add(this, u.iconButton(ReferralIcon.BACK, R.string.referral_back, actions::back), width = u.dp(48), height = u.dp(48))
        u.add(this, u.text("Pixiv-Shaft", 12f, 500, u.colors.muted), width = 0, weight = 1f)
        u.add(this, u.text(R.string.referral_preview, 9f, 400, u.colors.muted), width = LayoutParams.WRAP_CONTENT)
        u.add(this, u.iconButton(ReferralIcon.MOON, R.string.referral_switch_theme, actions::toggleTheme), width = u.dp(48), height = u.dp(48))
    }

    private fun heading(u: ReferralUi) = u.column().apply {
        val top = FrameLayout(context)
        top.addView(u.text(R.string.referral_eyebrow, 8f, 700, u.colors.primary).apply { letterSpacing = .18f }, LayoutParams(LayoutParams.WRAP_CONTENT, u.dp(18), Gravity.CENTER_VERTICAL))
        top.addView(u.button(R.string.referral_rules, outline = true, icon = ReferralIcon.ARROW) { actions.open(ReferralSheetKind.RULES) }.apply {
            background = null; textSize = 10f; setPadding(u.dp(8), 0, 0, 0)
        }, LayoutParams(LayoutParams.WRAP_CONTENT, u.dp(48), Gravity.END or Gravity.CENTER_VERTICAL))
        u.add(this, top, height = u.dp(18))
        u.add(this, u.heading(u.text(u.s(R.string.referral_heading) + " ✳", 28f, 700)), top = 9)
        u.add(this, u.text(R.string.referral_intro, 12f, color = u.colors.muted), top = 10)
    }

    private fun hero(u: ReferralUi, wide: Boolean): View {
        val frame = FrameLayout(context).apply { background = u.shape(u.colors.hero, 26f); clipToOutline = true }
        frame.addView(ReferralHeroArtView(context, u), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val copy = u.column().apply { setPadding(u.dp(if (wide) 36 else 24), u.dp(26), u.dp(24), u.dp(25)) }
        val tag = u.text("•  " + u.s(R.string.referral_hero_tag), 9f, 500, u.colors.onTint).apply {
            setPadding(u.dp(11), u.dp(7), u.dp(11), u.dp(7)); background = u.shape(u.colors.bg, 999f)
        }
        u.add(copy, tag, width = LayoutParams.WRAP_CONTENT)
        u.add(copy, u.heading(u.text(R.string.referral_hero_title, if (wide) 36f else 31f, 800)).apply {
            setLineSpacing(0f, 1.15f)
            TextViewCompat.setLineHeight(this, (textSize * 1.4f).roundToInt())
        }, top = 15)
        val narrow = resources.configuration.fontScale <= 1.1f && !wide
        u.add(copy, u.text(R.string.referral_hero_body, 12f, color = u.colors.onHero).apply {
            TextViewCompat.setLineHeight(this, (textSize * 1.95f).roundToInt())
        }, width = if (narrow) u.dp(185) else LayoutParams.MATCH_PARENT, top = 13)
        u.add(copy, u.button(R.string.referral_invite_action, primary = true, icon = ReferralIcon.ARROW) { actions.open(ReferralSheetKind.INVITE) },
            width = LayoutParams.WRAP_CONTENT, top = 24)
        u.add(copy, u.text(R.string.referral_hero_note, 12f, color = u.colors.onHero).apply {
            TextViewCompat.setLineHeight(this, (textSize * 1.7f).roundToInt())
        }, width = if (narrow) u.dp(185) else LayoutParams.MATCH_PARENT, top = 12)
        // At large font sizes keep the text readable; decoration remains a separate noninteractive layer.
        if (resources.configuration.fontScale > 1.1f) frame.getChildAt(0).alpha = .13f
        frame.addView(copy, LayoutParams(if (wide) u.dp(430) else LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        return frame
    }

    private fun sectionHeader(u: ReferralUi, parent: LinearLayout, title: String, subtitle: String, count: String) {
        val row = u.row()
        u.add(row, u.heading(u.text("$title  ", 19f, 700)), width = LayoutParams.WRAP_CONTENT)
        u.add(row, u.text(count, 12f, 400, u.colors.muted), width = LayoutParams.WRAP_CONTENT)
        u.add(parent, row)
        u.add(parent, u.text(subtitle, 12f, color = u.colors.muted), top = 7)
    }

    private fun filters(u: ReferralUi, state: ReferralUiState): View {
        val host = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
        val row = u.row().apply { setPadding(u.dp(5), u.dp(5), u.dp(5), u.dp(5)); background = u.shape(u.colors.surface2, 999f) }
        ReferralFilter.entries.forEach { filter ->
            val selected = state.filter == filter
            val label = when (filter) {
                ReferralFilter.ALL -> u.s(R.string.referral_all) + "  4"
                ReferralFilter.PROGRESS -> u.s(R.string.referral_progress)
                ReferralFilter.READY -> u.s(R.string.referral_ready) + "  ${state.snapshot.claimable}"
            }
            u.add(row, u.button(label) { actions.filter(filter) }.apply {
                setTextColor(if (selected) u.colors.ink else u.colors.muted)
                background = u.shape(if (selected) u.colors.surface else Color.TRANSPARENT, 999f)
                isSelected = selected
                setPadding(u.dp(14), u.dp(8), u.dp(14), u.dp(8))
            }, width = LayoutParams.WRAP_CONTENT)
        }
        host.addView(row); return host
    }

    private fun taskCard(u: ReferralUi, task: ReferralTask, state: ReferralSnapshot): View {
        val info = task.copy(); val status = state.status(task)
        val card = card(u)
        val top = u.row().apply { gravity = Gravity.TOP }
        val iconColors = when (task) {
            ReferralTask.INVITE -> u.colors.green to u.colors.greenBg
            ReferralTask.TUTORIAL -> u.colors.peach to u.colors.peachBg
            ReferralTask.CIRCLE -> u.colors.blue to u.colors.blueBg
            else -> u.colors.onTint to u.colors.tint
        }
        u.add(top, u.text("", color = iconColors.first).apply {
            background = u.shape(iconColors.second, if (task == ReferralTask.INVITE) 999f else 14f)
            setPadding(u.dp(10), u.dp(10), u.dp(10), u.dp(10))
            setCompoundDrawablesRelativeWithIntrinsicBounds(u.icon(info.icon, iconColors.first, 20), null, null, null)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }, width = u.dp(40), height = u.dp(40))
        val text = u.column()
        u.add(top, text, width = 0, weight = 1f)
        (text.layoutParams as LinearLayout.LayoutParams).marginStart = u.dp(11)
        u.add(text, u.text(u.s(info.category) + " · " + u.s(status.label), 11f, 400,
            if (status == ReferralStatus.READY) u.colors.green else u.colors.muted))
        u.add(text, u.text(info.title, 15f, 600), top = 6)
        u.add(text, u.text(info.description, 12f, color = u.colors.muted).apply {
            TextViewCompat.setLineHeight(this, (textSize * 1.8f).roundToInt())
        }, top = 7)
        u.add(top, u.text(u.s(R.string.referral_days, task.days), 12f, 700, u.colors.onTint).apply {
            background = u.shape(u.colors.surface2, 10f); setPadding(u.dp(7), u.dp(7), u.dp(7), u.dp(7))
        }, width = LayoutParams.WRAP_CONTENT)
        u.add(card, top)
        val foot = if (resources.configuration.fontScale > 1.3f) u.column() else u.row()
        val progress = u.column()
        val caption = when (status) {
            ReferralStatus.PENDING -> R.string.referral_status_pending
            ReferralStatus.REJECTED -> R.string.referral_status_rejected
            ReferralStatus.CLAIMED -> R.string.referral_status_claimed
            else -> info.caption
        }
        val progressLabel = u.row()
        u.add(progressLabel, u.text(caption, 11f, color = u.colors.muted), width = 0, weight = 1f)
        u.add(progressLabel, u.text(u.s(R.string.referral_progress_format, state.progress(task), task.target), 11f), width = LayoutParams.WRAP_CONTENT)
        u.add(progress, progressLabel)
        val track = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = task.target; this.progress = state.progress(task)
            progressTintList = android.content.res.ColorStateList.valueOf(if (status == ReferralStatus.READY) u.colors.green else u.colors.primary)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(u.colors.surface3)
            contentDescription = u.s(caption) + " " + u.s(R.string.referral_progress_format, state.progress(task), task.target)
        }
        u.add(progress, track, height = u.dp(4), top = 8)
        val horizontal = foot.orientation == LinearLayout.HORIZONTAL
        u.add(foot, progress, width = if (horizontal) 0 else LayoutParams.MATCH_PARENT, weight = if (horizontal) 1f else 0f)
        val action = when (status) {
            ReferralStatus.READY -> R.string.referral_claim
            ReferralStatus.CLAIMED -> R.string.referral_see_reward
            ReferralStatus.PENDING -> R.string.referral_see_review
            ReferralStatus.REJECTED -> R.string.referral_fix
            ReferralStatus.PROGRESS -> R.string.referral_see_progress
            else -> R.string.referral_see_task
        }
        val button = u.button(action, primary = status == ReferralStatus.READY,
            icon = if (status == ReferralStatus.READY) ReferralIcon.TICKET else ReferralIcon.ARROW) {
            if (status == ReferralStatus.CLAIMED) actions.tab(ReferralTab.WALLET)
            else actions.open(if (status == ReferralStatus.READY) ReferralSheetKind.CLAIM else ReferralSheetKind.TASK, task)
        }
        button.contentDescription = u.s(action) + "：" + u.s(info.title)
        u.add(foot, button, width = LayoutParams.WRAP_CONTENT, top = if (horizontal) 0 else 12)
        if (horizontal) (button.layoutParams as LinearLayout.LayoutParams).marginStart = u.dp(12)
        u.add(card, foot, top = 17)
        return card
    }

    private fun card(u: ReferralUi) = u.column().apply {
        background = u.shape(u.colors.surface, 22f, u.colors.line)
        setPadding(u.dp(16), u.dp(18), u.dp(16), u.dp(18))
    }

    private fun summary(u: ReferralUi, state: ReferralSnapshot) = card(u).apply {
        setPadding(u.dp(22), u.dp(22), u.dp(22), u.dp(22))
        u.add(this, u.text(R.string.referral_summary, 14f, 600))
        val number = u.row().apply { gravity = Gravity.BOTTOM }
        u.add(number, u.text(state.earnedDays.toString(), 55f, 600).apply { fontFeatureSettings = "tnum" }, width = LayoutParams.WRAP_CONTENT)
        u.add(number, u.text("  " + u.s(R.string.referral_earned), 12f, color = u.colors.muted), width = LayoutParams.WRAP_CONTENT)
        u.add(this, number, top = 18)
        val stats = u.row()
        listOf(state.effective to R.string.referral_effective,
            state.unused(System.currentTimeMillis()) to R.string.referral_unused,
            state.claimable to R.string.referral_unclaimed).forEach { (count, title) ->
            val col = u.column()
            u.add(col, u.text(count.toString(), 19f, 500))
            u.add(col, u.text(title, 11f, color = u.colors.muted), top = 7)
            u.add(stats, col, width = 0, weight = 1f)
        }
        u.add(this, stats, top = 18)
        u.add(this, u.button(R.string.referral_open_wallet, outline = true, icon = ReferralIcon.ARROW) { actions.tab(ReferralTab.WALLET) }, top = 23)
        u.add(this, u.text(R.string.referral_expiry_hint, 11f, color = u.colors.muted).apply { gravity = Gravity.CENTER }, top = 13)
    }

    private fun journey(u: ReferralUi) = u.column().apply {
        setPadding(u.dp(10), 0, u.dp(10), 0)
        u.add(this, u.text(R.string.referral_journey_eyebrow, 8f, 700, u.colors.primary).apply { letterSpacing = .1f })
        u.add(this, u.heading(u.text(R.string.referral_journey_title, 21f, 700)), top = 10)
        listOf(R.string.referral_step1 to R.string.referral_step1_desc, R.string.referral_step2 to R.string.referral_step2_desc,
            R.string.referral_step3 to R.string.referral_step3_desc).forEachIndexed { index, (title, desc) ->
            val row = u.row().apply { gravity = Gravity.TOP }
            u.add(row, u.text("0${index + 1}", 10f, 500, u.colors.primary).apply {
                gravity = Gravity.CENTER; background = u.shape(u.colors.bg, 999f, u.colors.line)
            }, width = u.dp(29), height = u.dp(29))
            val content = u.column()
            u.add(content, u.text(title, 13f, 600))
            u.add(content, u.text(desc, 12f, color = u.colors.muted), top = 6)
            u.add(row, content, width = 0, weight = 1f)
            (content.layoutParams as LinearLayout.LayoutParams).marginStart = u.dp(13)
            u.add(this, row, top = 24)
        }
        u.add(this, u.button(R.string.referral_materials, outline = true, icon = ReferralIcon.ARROW) { actions.open(ReferralSheetKind.MATERIALS) }.apply { background = null }, top = 16)
    }

    private fun footer(u: ReferralUi) = u.column().apply {
        u.add(this, View(context).apply { setBackgroundColor(u.colors.line) }, height = u.dp(1))
        u.add(this, u.text(R.string.referral_footer, 10f, color = u.colors.muted).apply { gravity = Gravity.CENTER }, top = 22)
        u.add(this, u.button(R.string.referral_demo_console, outline = true) { actions.open(ReferralSheetKind.DEMO) }.apply { background = null }, top = 8)
    }

    private fun empty(u: ReferralUi, title: Int, description: Int, action: () -> Unit) = card(u).apply {
        setPadding(u.dp(24), u.dp(36), u.dp(24), u.dp(36))
        u.add(this, u.text("✧", 40f, 400, u.colors.primary).apply { gravity = Gravity.CENTER })
        u.add(this, u.text(title, 16f, 600).apply { gravity = Gravity.CENTER }, top = 12)
        u.add(this, u.text(description, 13f, color = u.colors.muted).apply { gravity = Gravity.CENTER }, top = 10)
        u.add(this, u.button(R.string.referral_go_tasks, primary = true, action = action), top = 20)
    }

    private fun note(u: ReferralUi, value: String) = u.text(value, 12f, color = u.colors.muted).apply {
        background = u.shape(u.colors.surface2, 16f); setPadding(u.dp(16), u.dp(16), u.dp(16), u.dp(16))
    }

    private fun walletCard(u: ReferralUi, card: ReferralCard) = card(u).apply {
        val now = System.currentTimeMillis()
        u.add(this, u.text(if (card.task.days == 7) R.string.referral_card_week else R.string.referral_card_month, 18f, 600))
        u.add(this, u.text(u.s(R.string.referral_card_from, u.s(card.task.copy().title)), 12f, color = u.colors.muted), top = 10)
        val caption = when {
            card.activatedAt > 0 -> u.s(R.string.referral_card_active, date(card.activatedAt))
            card.expiresAt <= now -> u.s(R.string.referral_card_expired)
            else -> u.s(R.string.referral_card_deadline, date(card.expiresAt))
        }
        u.add(this, u.text(caption, 12f, color = u.colors.muted), top = 10)
        if (card.activatedAt == 0L && card.expiresAt > now) u.add(this,
            u.button(u.s(R.string.referral_activate, card.task.days), primary = true) { actions.open(ReferralSheetKind.ACTIVATE, card.task) }, top = 16)
    }

    private fun buildBottom(u: ReferralUi, state: ReferralUiState) {
        bottom.removeAllViews()
        listOf(ReferralTab.TASKS to R.string.referral_nav_tasks, ReferralTab.WALLET to R.string.referral_wallet).forEach { (tab, label) ->
            val selected = tab == state.tab
            val button = u.button(label) { actions.tab(tab) }.apply {
                isSelected = selected
                background = u.shape(if (selected) u.colors.tint else Color.TRANSPARENT, 999f)
                setTextColor(if (selected) u.colors.onTint else u.colors.muted)
                setCompoundDrawablesRelativeWithIntrinsicBounds(u.icon(if (tab == ReferralTab.TASKS) ReferralIcon.SPARK else ReferralIcon.TICKET, currentTextColor, 19), null, null, null)
                compoundDrawablePadding = u.dp(8)
            }
            u.add(bottom, button, width = LayoutParams.WRAP_CONTENT)
            if (tab == ReferralTab.TASKS) u.add(bottom, View(context), width = u.dp(20), height = 1)
        }
    }

    companion object {
        fun date(time: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(time))
    }
}
