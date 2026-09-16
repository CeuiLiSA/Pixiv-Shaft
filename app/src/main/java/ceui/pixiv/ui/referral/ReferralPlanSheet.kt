package ceui.pixiv.ui.referral

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.widget.AppCompatCheckBox
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.os.bundleOf
import androidx.fragment.app.viewModels
import ceui.lisa.R
import ceui.pixiv.ui.search.v3.V3BottomSheetBase
import java.text.DateFormat
import java.util.Date

internal enum class ReferralSheetKind { TASK, CLAIM, CLAIMED, ACTIVATE, ACTIVATED, INVITE, INVITE_PROGRESS, FORM, SUBMITTED, RULES, MATERIALS, DEMO, RESET }

/** All mutations belong to the parent's isolated demo model; recreation carries only arguments/drafts. */
class ReferralPlanSheet : V3BottomSheetBase() {
    private val model by viewModels<ReferralPlanViewModel>({ requireParentFragment() })
    override val maxHeightFraction = .92f
    private var root: FrameLayout? = null
    private var urlField: EditText? = null
    private var descriptionField: EditText? = null
    private var confirmField: AppCompatCheckBox? = null
    private var draftUrl: String? = null
    private var draftDescription: String? = null
    private var draftConfirmed = false
    private val kind get() = ReferralSheetKind.valueOf(requireArguments().getString("kind")!!)
    private val task get() = requireArguments().getString("task")?.let(ReferralTask::valueOf) ?: ReferralTask.INVITE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        draftUrl = savedInstanceState?.getString("url")
        draftDescription = savedInstanceState?.getString("description")
        draftConfirmed = savedInstanceState?.getBoolean("confirmed") ?: false
    }
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        FrameLayout(requireContext()).also { root = it; render() }
    override fun onSaveInstanceState(outState: Bundle) {
        captureDraft()
        outState.putString("url", draftUrl); outState.putString("description", draftDescription)
        outState.putBoolean("confirmed", draftConfirmed)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroyView() { captureDraft(); root = null; urlField = null; descriptionField = null; confirmField = null; super.onDestroyView() }
    private fun captureDraft() {
        urlField?.let { draftUrl = it.text.toString() }
        descriptionField?.let { draftDescription = it.text.toString() }
        confirmField?.let { draftConfirmed = it.isChecked }
    }
    private fun go(next: ReferralSheetKind, nextTask: ReferralTask = task) {
        captureDraft()
        requireArguments().putString("kind", next.name)
        requireArguments().putString("task", nextTask.name)
        render()
    }
    private fun render() {
        val host = root ?: return
        urlField = null; descriptionField = null; confirmField = null
        val state = model.value
        val u = ReferralUi(requireContext(), ReferralColors(requireContext(), state.darkOverride, state.accentOverride))
        host.removeAllViews()
        host.background = u.shape(u.colors.surface, 30f).apply {
            cornerRadii = floatArrayOf(u.dp(30f), u.dp(30f), u.dp(30f), u.dp(30f), 0f, 0f, 0f, 0f)
        }
        val content = u.column().apply { setPadding(u.dp(24), u.dp(10), u.dp(24), u.dp(24)) }
        val scroll = ScrollView(requireContext()).apply { isFillViewport = false; addView(content) }
        host.addView(scroll, FrameLayout.LayoutParams(-1, -2))
        val handle = View(requireContext()).apply { background = u.shape(u.colors.line, 4f); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        content.addView(handle, LinearLayout.LayoutParams(u.dp(34), u.dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        val close = u.iconButton(ReferralIcon.CLOSE, R.string.referral_close) { dismiss() }
        content.addView(close, LinearLayout.LayoutParams(u.dp(48), u.dp(48)).apply { gravity = Gravity.END })
        fun title(id: Int, vararg args: Any) { u.add(content, u.heading(u.text(u.s(id, *args), 25f, 700))) }
        fun body(id: Int, vararg args: Any) { u.add(content, u.text(u.s(id, *args), 13f, 400, u.colors.muted).apply { setLineSpacing(0f, 1.7f) }, top = 14) }
        fun action(id: Int, primary: Boolean = false, block: () -> Unit) { u.add(content, u.button(id, primary = primary, action = block), top = 14) }
        fun notice() { body(R.string.referral_preview_notice) }
        fun reward() {
            val card = u.column().apply { background = u.shape(u.colors.hero, 24f); setPadding(u.dp(24), u.dp(22), u.dp(24), u.dp(22)) }
            u.add(card, u.text("PRO / EXPERIENCE PASS", 10f, 600, u.colors.onHero))
            val number = u.row()
            u.add(number, u.text(task.days.toString(), 58f, 800, u.colors.ink), width = -2)
            u.add(number, u.text(R.string.referral_day_unit, 15f, 600, u.colors.onHero).apply { setPadding(u.dp(10), 0, 0, 0) }, width = -2)
            u.add(card, number, top = 10)
            u.add(card, u.text(if (task.days == 7) R.string.referral_card_week else R.string.referral_card_month, 17f, 600), top = 6)
            u.add(card, u.text(R.string.referral_card_hint, 11f, 400, u.colors.onHero), top = 14)
            u.add(content, card, top = 22)
        }
        fun wallet() { model.tab(ReferralTab.WALLET); dismiss() }
        when (kind) {
            ReferralSheetKind.TASK -> {
                val copy = task.copy(); val status = state.snapshot.status(task)
                title(copy.title); body(copy.description)
                u.add(content, u.text(u.s(R.string.referral_days_pro, task.days), 24f, 700, u.colors.primary), top = 20)
                if (status == ReferralStatus.PENDING) body(R.string.referral_review_note)
                if (status == ReferralStatus.REJECTED) body(R.string.referral_rejected_note)
                u.add(content, u.text(R.string.referral_how, 15f, 600), top = 22)
                u.s(copy.steps).split('\n').forEachIndexed { index, step ->
                    u.add(content, u.text("${index + 1}. $step", 13f).apply { setLineSpacing(0f, 1.7f) }, top = 12)
                }
                body(copy.condition)
                when (status) {
                    ReferralStatus.READY -> action(R.string.referral_claim, true) { go(ReferralSheetKind.CLAIM) }
                    ReferralStatus.CLAIMED -> action(R.string.referral_go_wallet, true) { wallet() }
                    ReferralStatus.PENDING -> action(R.string.referral_ok, true) { dismiss() }
                    else -> action(if (task == ReferralTask.INVITE || task == ReferralTask.CIRCLE) R.string.referral_invite_action else R.string.referral_submit, true) {
                        go(if (task == ReferralTask.INVITE || task == ReferralTask.CIRCLE) ReferralSheetKind.INVITE else ReferralSheetKind.FORM)
                    }
                }
            }
            ReferralSheetKind.CLAIM -> {
                title(R.string.referral_claim_title); body(R.string.referral_claim_desc, u.s(task.copy().title), task.days); reward()
                action(R.string.referral_claim_confirm, true) {
                    if (model.claim(task)) go(ReferralSheetKind.CLAIMED) else unavailable()
                }; notice()
            }
            ReferralSheetKind.CLAIMED -> {
                title(R.string.referral_claim_success)
                val card = state.snapshot.cards.firstOrNull { it.task == task }
                body(R.string.referral_claim_success_note, task.days, date(card?.expiresAt ?: 0))
                reward(); action(R.string.referral_go_wallet, true) { wallet() }
                action(R.string.referral_continue) { dismiss() }
            }
            ReferralSheetKind.ACTIVATE -> {
                title(R.string.referral_activate_title, task.days); reward()
                body(R.string.referral_activate_desc, date(maxOf(System.currentTimeMillis(), state.snapshot.activeUntil) + task.days * ReferralDemoRepository.DAY))
                action(R.string.referral_activate_confirm, true) { if (model.activate(task)) go(ReferralSheetKind.ACTIVATED) else unavailable() }
                action(R.string.referral_later) { dismiss() }
            }
            ReferralSheetKind.ACTIVATED -> {
                title(R.string.referral_activate_success); body(R.string.referral_activate_success_desc, task.days, date(state.snapshot.activeUntil))
                reward(); action(R.string.referral_ok, true) { dismiss() }
            }
            ReferralSheetKind.INVITE -> {
                title(R.string.referral_invite_title); body(R.string.referral_invite_lead)
                u.add(content, u.text(R.string.referral_invite_benefit, 24f, 700, u.colors.primary), top = 24)
                body(R.string.referral_invite_condition); body(R.string.referral_invite_unavailable)
                action(R.string.referral_copy_invite, true) { copy(R.string.referral_invite_text) }
                action(R.string.referral_share_app) {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, u.s(R.string.referral_share_text))
                    runCatching { startActivity(Intent.createChooser(send, u.s(R.string.referral_share_app))) }.onFailure { unavailable() }
                }
                action(R.string.referral_invite_progress) { go(ReferralSheetKind.INVITE_PROGRESS) }
            }
            ReferralSheetKind.INVITE_PROGRESS -> {
                title(R.string.referral_invite_progress_title)
                body(R.string.referral_invite_progress_desc, state.snapshot.effective, state.snapshot.retained)
                action(R.string.referral_invite_action, true) { go(ReferralSheetKind.INVITE) }
                action(R.string.referral_demo_console) { go(ReferralSheetKind.DEMO) }
            }
            ReferralSheetKind.FORM -> {
                title(R.string.referral_form_title); body(R.string.referral_form_lead)
                val previous = state.snapshot.submissions[task]
                fun field(label: Int, hint: Int, multiline: Boolean, value: String): EditText {
                    u.add(content, u.text(label, 12f, 600), top = 20)
                    return AppCompatEditText(requireContext()).apply {
                        textSize = 14f; typeface = u.font(400); setTextColor(u.colors.ink); setHintTextColor(u.colors.muted)
                        background = u.shape(u.colors.surface2, 16f, u.colors.line)
                        setPadding(u.dp(16), u.dp(14), u.dp(16), u.dp(14)); minHeight = u.dp(56)
                        inputType = if (multiline) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                        if (multiline) { minLines = 4; gravity = Gravity.TOP } else setSingleLine(true)
                        filters = arrayOf(InputFilter.LengthFilter(if (multiline) 1000 else 2000))
                        setHint(hint); setText(value); contentDescription = u.s(label)
                        u.add(content, this, top = 8)
                    }
                }
                urlField = field(R.string.referral_form_url, R.string.referral_form_url_hint, false, draftUrl ?: previous?.url.orEmpty())
                descriptionField = field(R.string.referral_form_desc, R.string.referral_form_desc_hint, true, draftDescription ?: previous?.description.orEmpty())
                confirmField = AppCompatCheckBox(requireContext()).apply {
                    text = u.s(R.string.referral_form_confirm); textSize = 12f; typeface = u.font(400); setTextColor(u.colors.muted)
                    buttonTintList = android.content.res.ColorStateList.valueOf(u.colors.primary)
                    minHeight = u.dp(48); isChecked = draftConfirmed; u.add(content, this, top = 14)
                }
                val error = u.text(R.string.referral_form_invalid, 12f, 500, u.colors.danger).apply { visibility = View.GONE; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
                u.add(content, error, top = 12)
                action(R.string.referral_submit_review, true) {
                    captureDraft()
                    if (model.submit(task, draftUrl.orEmpty(), draftDescription.orEmpty(), draftConfirmed)) {
                        (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(host.windowToken, 0)
                        go(ReferralSheetKind.SUBMITTED)
                    } else { error.visibility = View.VISIBLE; error.announceForAccessibility(error.text) }
                }; notice()
            }
            ReferralSheetKind.SUBMITTED -> { title(R.string.referral_received); body(R.string.referral_submitted_note); action(R.string.referral_ok, true) { dismiss() } }
            ReferralSheetKind.RULES -> { title(R.string.referral_rules_title); body(R.string.referral_rules_body); notice() }
            ReferralSheetKind.MATERIALS -> {
                title(R.string.referral_material_title); body(R.string.referral_material_lead)
                listOf(R.string.referral_material1 to R.string.referral_material1_desc, R.string.referral_material2 to R.string.referral_material2_desc, R.string.referral_material3 to R.string.referral_material3_desc).forEach { (heading, description) ->
                    u.add(content, u.text(heading, 15f, 600), top = 24); body(description)
                }
                action(R.string.referral_copy_template, true) { copy(R.string.referral_template) }
            }
            ReferralSheetKind.DEMO -> {
                title(R.string.referral_demo_title); notice()
                body(R.string.referral_invite_progress_desc, state.snapshot.effective, state.snapshot.retained)
                action(R.string.referral_demo_add_invite) { model.addInvite(); render() }
                action(R.string.referral_demo_add_retained) { model.addRetained(); render() }
                listOf(ReferralTask.RECOMMEND, ReferralTask.TUTORIAL).forEach { item ->
                    u.add(content, u.text("${u.s(item.copy().title)} · ${u.s(state.snapshot.status(item).label)}", 14f, 600), top = 22)
                    val row = u.row()
                    listOf(true, false).forEach { approved ->
                        val button = u.button(if (approved) R.string.referral_demo_approve else R.string.referral_demo_reject) { model.review(item, approved); render() }
                        button.isEnabled = state.snapshot.status(item) == ReferralStatus.PENDING
                        button.alpha = if (button.isEnabled) 1f else .4f
                        u.add(row, button, width = 0, weight = 1f)
                    }; u.add(content, row, top = 10)
                }
                body(R.string.referral_demo_palette)
                listOf(R.string.referral_theme_system to null, R.string.referral_theme_reference to Color.parseColor("#6A4BC5"), R.string.referral_theme_rose to Color.parseColor("#A44266"), R.string.referral_theme_teal to Color.parseColor("#256C65")).forEach { (label, accent) ->
                    action(label) { model.appearance(if (accent == null) null else state.darkOverride, accent); render() }
                }
                action(R.string.referral_demo_reset) { go(ReferralSheetKind.RESET) }
            }
            ReferralSheetKind.RESET -> {
                title(R.string.referral_demo_reset_title); body(R.string.referral_demo_reset_desc)
                action(R.string.referral_demo_reset_confirm, true) { model.reset(); dismiss() }
                action(R.string.referral_cancel) { go(ReferralSheetKind.DEMO) }
            }
        }
    }
    private fun copy(id: Int) {
        (requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(getString(R.string.referral_title), getString(id)))
        Toast.makeText(requireContext(), R.string.referral_copy_success, Toast.LENGTH_SHORT).show()
    }
    private fun unavailable() { Toast.makeText(requireContext(), R.string.referral_action_unavailable, Toast.LENGTH_SHORT).show(); dismiss() }
    private fun date(time: Long) = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(time))
    companion object {
        internal fun newInstance(kind: ReferralSheetKind, task: ReferralTask?) = ReferralPlanSheet().apply {
            arguments = bundleOf("kind" to kind.name, "task" to task?.name)
        }
    }
}
