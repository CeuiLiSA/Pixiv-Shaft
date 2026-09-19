package ceui.pixiv.ui.referral

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.AppCompatCheckBox
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.os.bundleOf
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import ceui.lisa.R
import ceui.pixiv.ui.search.v3.V3BottomSheetBase
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

internal enum class ReferralSheetKind {
    TASK, CLAIM, CLAIMED, ACTIVATE, ACTIVATED, INVITE, INVITE_PROGRESS,
    FORM, SUBMITTED, RULES, MATERIALS,

    /** 新人填邀请码。分享链路上最关键、也最容易掉人的一步。 */
    BIND, BOUND,
}

/**
 * 推介页的各种弹窗。
 *
 * **所有会改变奖励的动作都是一次网络请求**，成功与否由服务端说了算：领取、激活、投稿、
 * 绑定都走 [ReferralPlanViewModel] 的挂起方法，拿到 null 才算成，拿到 [ReferralFailure]
 * 就把那句话显示在原地。没有乐观更新 —— 这一页对应的是真的 PRO 天数。
 */
class ReferralPlanSheet : V3BottomSheetBase() {
    private val model by viewModels<ReferralPlanViewModel>({ requireParentFragment() })
    override val maxHeightFraction = .92f
    private var root: FrameLayout? = null
    private var urlField: EditText? = null
    private var descriptionField: EditText? = null
    private var confirmField: AppCompatCheckBox? = null
    private var codeField: EditText? = null
    private var draftUrl: String? = null
    private var draftDescription: String? = null
    private var draftCode: String? = null
    private var draftConfirmed = false
    /** 一次动作在飞。按钮期间禁用，避免重复领取 / 重复激活打到服务端去。 */
    private var busy = false
    private val kind get() = ReferralSheetKind.valueOf(requireArguments().getString("kind")!!)
    private val task get() = requireArguments().getString("task")?.let(ReferralTask::valueOf) ?: ReferralTask.INVITE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        draftUrl = savedInstanceState?.getString("url")
        draftDescription = savedInstanceState?.getString("description")
        draftCode = savedInstanceState?.getString("code") ?: requireArguments().getString("code")
        draftConfirmed = savedInstanceState?.getBoolean("confirmed") ?: false
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        FrameLayout(requireContext()).also { root = it; render() }

    override fun onSaveInstanceState(outState: Bundle) {
        captureDraft()
        outState.putString("url", draftUrl)
        outState.putString("description", draftDescription)
        outState.putString("code", draftCode)
        outState.putBoolean("confirmed", draftConfirmed)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroyView() {
        captureDraft()
        root = null; urlField = null; descriptionField = null; confirmField = null; codeField = null
        super.onDestroyView()
    }

    private fun captureDraft() {
        urlField?.let { draftUrl = it.text.toString() }
        descriptionField?.let { draftDescription = it.text.toString() }
        confirmField?.let { draftConfirmed = it.isChecked }
        codeField?.let { draftCode = it.text.toString() }
    }

    private fun go(next: ReferralSheetKind, nextTask: ReferralTask = task) {
        captureDraft()
        requireArguments().putString("kind", next.name)
        requireArguments().putString("task", nextTask.name)
        render()
    }

    private fun render() {
        val host = root ?: return
        urlField = null; descriptionField = null; confirmField = null; codeField = null
        val state = model.value
        val snapshot = state.snapshot
        val u = ReferralUi(requireContext(), ReferralColors(requireContext(), state.darkOverride, state.accentOverride))
        host.removeAllViews()
        host.background = u.shape(u.colors.surface, 30f).apply {
            cornerRadii = floatArrayOf(u.dp(30f), u.dp(30f), u.dp(30f), u.dp(30f), 0f, 0f, 0f, 0f)
        }
        val content = u.column().apply { setPadding(u.dp(24), u.dp(10), u.dp(24), u.dp(24)) }
        val scroll = ScrollView(requireContext()).apply { isFillViewport = false; addView(content) }
        host.addView(scroll, FrameLayout.LayoutParams(-1, -2))
        val handle = View(requireContext()).apply {
            background = u.shape(u.colors.line, 4f)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        content.addView(handle, LinearLayout.LayoutParams(u.dp(34), u.dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        val close = u.iconButton(ReferralIcon.CLOSE, R.string.referral_close) { dismiss() }
        content.addView(close, LinearLayout.LayoutParams(u.dp(48), u.dp(48)).apply { gravity = Gravity.END })

        fun title(id: Int, vararg args: Any) { u.add(content, u.heading(u.text(u.s(id, *args), 25f, 700))) }
        fun body(id: Int, vararg args: Any) {
            u.add(content, u.text(u.s(id, *args), 13f, 400, u.colors.muted).apply { setLineSpacing(0f, 1.7f) }, top = 14)
        }
        fun bodyText(value: CharSequence) {
            u.add(content, u.text(value, 13f, 400, u.colors.muted).apply { setLineSpacing(0f, 1.7f) }, top = 14)
        }
        fun action(id: Int, primary: Boolean = false, block: () -> Unit): TextView =
            u.button(id, primary = primary, action = block).also { u.add(content, it, top = 14) }
        fun notice() { body(R.string.referral_preview_notice) }

        // 失败时就地显示那一句话。用 live region，读屏也能读到 —— 这条消息往往是
        // 「你不是新用户」这种需要用户真正读懂的拒绝，不能只闪一个 Toast。
        val error = u.text("", 12f, 500, u.colors.danger).apply {
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        fun fail(failure: ReferralFailure) {
            error.text = u.s(failure.messageRes)
            error.visibility = View.VISIBLE
            error.announceForAccessibility(error.text)
        }
        /** 跑一个会改变奖励的动作：期间禁用按钮，成功进下一屏，失败原地说明。 */
        fun run(button: TextView, next: () -> Unit, block: suspend () -> ReferralFailure?) {
            if (busy) return
            busy = true
            button.isEnabled = false
            error.visibility = View.GONE
            viewLifecycleOwner.lifecycleScope.launch {
                // busy 必须在 finally 里放：这个 scope 在 onDestroyView 时会被取消，而
                // Fragment 实例本身在旋转屏幕时是活下来的。请求在飞的时候转一下屏，
                // 协程被取消、busy 停在 true，重建后的弹窗里每个按钮都按不动了。
                val failure = try {
                    block()
                } finally {
                    busy = false
                }
                if (view == null) return@launch
                button.isEnabled = true
                if (failure == null) next() else fail(failure)
            }
        }

        fun reward(days: Int, tier: String) {
            val card = u.column().apply {
                background = u.shape(u.colors.hero, 24f)
                setPadding(u.dp(24), u.dp(22), u.dp(24), u.dp(22))
            }
            u.add(card, u.text("$tier / EXPERIENCE PASS", 10f, 600, u.colors.onHero))
            val number = u.row()
            u.add(number, u.text(days.toString(), 58f, 800, u.colors.ink), width = -2)
            u.add(number, u.text(R.string.referral_day_unit, 15f, 600, u.colors.onHero).apply { setPadding(u.dp(10), 0, 0, 0) }, width = -2)
            u.add(card, number, top = 10)
            u.add(card, u.text(
                u.s(if (days <= 7) R.string.referral_card_week else R.string.referral_card_month, tier),
                17f, 600,
            ), top = 6)
            u.add(card, u.text(R.string.referral_card_hint, 11f, 400, u.colors.onHero), top = 14)
            u.add(content, card, top = 22)
        }
        fun wallet() { (parentFragment as? ReferralPlanFragment)?.showWallet(); dismiss() }

        when (kind) {
            ReferralSheetKind.TASK -> {
                val copy = task.copy()
                val view = snapshot.view(task)
                val status = view?.status ?: ReferralStatus.NEW
                title(copy.title); body(copy.description)
                u.add(content, u.text(
                    u.s(R.string.referral_days_pro, view?.days ?: task.days, planLabel(view?.plan)),
                    24f, 700, u.colors.primary,
                ), top = 20)
                if (status == ReferralStatus.PENDING) body(R.string.referral_review_note)
                if (status == ReferralStatus.REJECTED) {
                    body(R.string.referral_rejected_note)
                    // 审核员写的那句话原样显示。没有理由的退回等于让用户重猜一遍，
                    // 所以服务端本来就拒绝空理由的退回；这里只是把它摆出来。
                    view?.reviewNote?.let { bodyText(it) }
                }
                if (view?.enabled == false) body(R.string.referral_task_disabled)
                u.add(content, u.text(R.string.referral_how, 15f, 600), top = 22)
                val ruleArgs = if (task == ReferralTask.CIRCLE) snapshot.retainRuleArgs else snapshot.ruleArgs
                u.s(copy.steps, *ruleArgs).split('\n').forEachIndexed { index, step ->
                    u.add(content, u.text("${index + 1}. $step", 13f).apply { setLineSpacing(0f, 1.7f) }, top = 12)
                }
                body(copy.condition)
                val invites = task == ReferralTask.INVITE || task == ReferralTask.CIRCLE
                when {
                    status == ReferralStatus.READY -> action(R.string.referral_claim, true) { go(ReferralSheetKind.CLAIM) }
                    status == ReferralStatus.CLAIMED -> action(R.string.referral_go_wallet, true) { wallet() }
                    status == ReferralStatus.PENDING -> action(R.string.referral_ok, true) { dismiss() }
                    view?.enabled == false -> action(R.string.referral_ok, true) { dismiss() }
                    !snapshot.enabled -> action(R.string.referral_ok, true) { dismiss() }
                    else -> action(if (invites) R.string.referral_invite_action else R.string.referral_submit, true) {
                        go(if (invites) ReferralSheetKind.INVITE else ReferralSheetKind.FORM)
                    }
                }
            }

            ReferralSheetKind.CLAIM -> {
                val view = snapshot.view(task)
                val days = view?.days ?: task.days
                val tier = planLabel(view?.plan)
                title(R.string.referral_claim_title)
                body(R.string.referral_claim_desc, u.s(task.copy().title), days, tier)
                reward(days, tier)
                val button = action(R.string.referral_claim_confirm, true) {}
                button.setOnClickListener {
                    run(button, { go(ReferralSheetKind.CLAIMED) }) { model.claim(task) }
                }
                u.add(content, error, top = 12)
                notice()
            }

            ReferralSheetKind.CLAIMED -> {
                title(R.string.referral_claim_success)
                val card = snapshot.card(task)
                val tier = planLabel(card?.plan ?: snapshot.view(task)?.plan)
                body(R.string.referral_claim_success_note, card?.days ?: task.days, tier, date(card?.expiresAt ?: 0))
                reward(card?.days ?: task.days, tier)
                action(R.string.referral_go_wallet, true) { wallet() }
                action(R.string.referral_continue) { dismiss() }
            }

            ReferralSheetKind.ACTIVATE -> {
                val card = snapshot.cards.firstOrNull { it.id == requireArguments().getLong("cardId") }
                val tier = planLabel(card?.plan)
                title(R.string.referral_activate_title, card?.days ?: task.days, tier)
                reward(card?.days ?: task.days, tier)
                body(R.string.referral_activate_desc,
                    date(maxOf(System.currentTimeMillis(), snapshot.activeUntil) + (card?.days ?: task.days) * DAY_MS))
                val button = action(R.string.referral_activate_confirm, true) {}
                button.setOnClickListener {
                    val id = card?.id
                    if (id == null) {
                        unavailable()
                    } else {
                        run(button, { go(ReferralSheetKind.ACTIVATED) }) { model.activate(id) }
                    }
                }
                u.add(content, error, top = 12)
                action(R.string.referral_later) { dismiss() }
            }

            ReferralSheetKind.ACTIVATED -> {
                val card = snapshot.cards.firstOrNull { it.id == requireArguments().getLong("cardId") }
                val tier = planLabel(card?.plan)
                title(R.string.referral_activate_success)
                body(R.string.referral_activate_success_desc, card?.days ?: task.days, tier, date(snapshot.activeUntil))
                reward(card?.days ?: task.days, tier)
                action(R.string.referral_ok, true) { dismiss() }
            }

            ReferralSheetKind.INVITE -> {
                title(R.string.referral_invite_title); body(R.string.referral_invite_lead)
                u.add(content, u.text(R.string.referral_invite_benefit, 24f, 700, u.colors.primary), top = 24)
                body(R.string.referral_invite_condition, *snapshot.ruleArgs)
                val code = snapshot.code
                val link = snapshot.inviteUrl
                // 活动结束后服务端不再发邀请码（也不该再有人被邀请进来）。那句「把下面的
                // 邀请码发给朋友」这时候指向一个不存在的东西，得换成实话。
                body(if (code == null) R.string.referral_closed_banner else R.string.referral_invite_unavailable)
                if (code != null) {
                    // 邀请码印成大字等宽 —— 它要被人念出来、手抄进另一台手机。
                    u.add(content, u.text(R.string.referral_code_label, 12f, 600), top = 24)
                    u.add(content, u.text(code, 30f, 700, u.colors.primary).apply {
                        typeface = android.graphics.Typeface.MONOSPACE
                        letterSpacing = .14f
                        background = u.shape(u.colors.tint, 18f)
                        gravity = Gravity.CENTER
                        setPadding(u.dp(16), u.dp(14), u.dp(16), u.dp(14))
                        setTextIsSelectable(true)
                    }, top = 8)
                }
                val share = link?.let { u.s(R.string.referral_invite_share_text, it, code.orEmpty()) }
                if (share != null) {
                    action(R.string.referral_copy_invite, true) { copyText(share) }
                    action(R.string.referral_share_app) {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, share)
                        runCatching { startActivity(Intent.createChooser(send, u.s(R.string.referral_share_app))) }
                            .onFailure { unavailable() }
                    }
                    action(R.string.referral_copy_link) { copyText(link) }
                }
                action(R.string.referral_invite_progress) { go(ReferralSheetKind.INVITE_PROGRESS) }
            }

            ReferralSheetKind.INVITE_PROGRESS -> {
                title(R.string.referral_invite_progress_title)
                body(R.string.referral_invite_progress_desc, snapshot.effective, snapshot.retained)
                action(R.string.referral_invite_action, true) { go(ReferralSheetKind.INVITE) }
            }

            ReferralSheetKind.BIND -> {
                title(R.string.referral_bind_title); body(R.string.referral_bind_lead)
                val field = AppCompatEditText(requireContext()).apply {
                    textSize = 22f
                    typeface = android.graphics.Typeface.MONOSPACE
                    letterSpacing = .14f
                    setTextColor(u.colors.ink); setHintTextColor(u.colors.muted)
                    background = u.shape(u.colors.surface2, 16f, u.colors.line)
                    setPadding(u.dp(16), u.dp(14), u.dp(16), u.dp(14))
                    minHeight = u.dp(60)
                    gravity = Gravity.CENTER
                    // 邀请码只有大写字母和数字，键盘就该直接给这一套。
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                    setSingleLine(true)
                    filters = arrayOf(InputFilter.LengthFilter(16), InputFilter.AllCaps())
                    setHint(R.string.referral_bind_hint)
                    contentDescription = u.s(R.string.referral_bind_field)
                    setText(draftCode.orEmpty())
                }
                codeField = field
                u.add(content, u.text(R.string.referral_bind_field, 12f, 600), top = 20)
                u.add(content, field, top = 8)
                // 落地页进页面时就把码写进了剪贴板，所以这里绝大多数情况下是一键完成。
                // 但自己的码不给粘：分享过一次之后剪贴板里躺着的就是它，填进去只会
                // 换来一句 self_referral。
                clipboardCode()?.takeIf { it != snapshot.code }?.let { pasted ->
                    action(R.string.referral_bind_paste) { field.setText(pasted) }
                }
                u.add(content, error, top = 12)
                val button = action(R.string.referral_bind_confirm, true) {}
                button.setOnClickListener {
                    captureDraft()
                    val code = draftCode.orEmpty().trim()
                    run(button, { go(ReferralSheetKind.BOUND) }) { model.bind(code) }
                }
                notice()
            }

            ReferralSheetKind.BOUND -> {
                title(R.string.referral_bind_success); body(R.string.referral_bind_success_desc, *snapshot.ruleArgs)
                snapshot.inviterUid?.let { bodyText(u.s(R.string.referral_bound_already, it)) }
                action(R.string.referral_ok, true) { dismiss() }
            }

            ReferralSheetKind.FORM -> {
                title(R.string.referral_form_title); body(R.string.referral_form_lead)
                val previous = snapshot.view(task)
                fun field(label: Int, hint: Int, multiline: Boolean, value: String): EditText {
                    u.add(content, u.text(label, 12f, 600), top = 20)
                    return AppCompatEditText(requireContext()).apply {
                        textSize = 14f; typeface = u.font(400); setTextColor(u.colors.ink); setHintTextColor(u.colors.muted)
                        background = u.shape(u.colors.surface2, 16f, u.colors.line)
                        setPadding(u.dp(16), u.dp(14), u.dp(16), u.dp(14)); minHeight = u.dp(56)
                        inputType = if (multiline) {
                            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                        } else {
                            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                        }
                        if (multiline) { minLines = 4; gravity = Gravity.TOP } else setSingleLine(true)
                        filters = arrayOf(InputFilter.LengthFilter(if (multiline) 1000 else 2000))
                        setHint(hint); setText(value); contentDescription = u.s(label)
                        u.add(content, this, top = 8)
                    }
                }
                urlField = field(R.string.referral_form_url, R.string.referral_form_url_hint, false, draftUrl ?: previous?.submittedUrl.orEmpty())
                descriptionField = field(R.string.referral_form_desc, R.string.referral_form_desc_hint, true, draftDescription.orEmpty())
                confirmField = AppCompatCheckBox(requireContext()).apply {
                    text = u.s(R.string.referral_form_confirm); textSize = 12f; typeface = u.font(400); setTextColor(u.colors.muted)
                    buttonTintList = android.content.res.ColorStateList.valueOf(u.colors.primary)
                    minHeight = u.dp(48); isChecked = draftConfirmed; u.add(content, this, top = 14)
                }
                u.add(content, error, top = 12)
                val button = action(R.string.referral_submit_review, true) {}
                button.setOnClickListener {
                    captureDraft()
                    // 勾选确认是本地的一道闸（服务端不认这个字段）：它是用户对「原创、
                    // 附了下载入口、注明了奖励关系」的声明，审核依据的就是这三条。
                    if (!draftConfirmed) {
                        error.text = u.s(R.string.referral_form_invalid)
                        error.visibility = View.VISIBLE
                        error.announceForAccessibility(error.text)
                        return@setOnClickListener
                    }
                    run(button, {
                        (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                            .hideSoftInputFromWindow(host.windowToken, 0)
                        go(ReferralSheetKind.SUBMITTED)
                    }) { model.submit(task, draftUrl.orEmpty(), draftDescription.orEmpty()) }
                }
                notice()
            }

            ReferralSheetKind.SUBMITTED -> {
                title(R.string.referral_received); body(R.string.referral_submitted_note)
                action(R.string.referral_ok, true) { dismiss() }
            }

            ReferralSheetKind.RULES -> { title(R.string.referral_rules_title); body(R.string.referral_rules_body, *snapshot.ruleArgs); notice() }

            ReferralSheetKind.MATERIALS -> {
                title(R.string.referral_material_title); body(R.string.referral_material_lead)
                listOf(
                    R.string.referral_material1 to R.string.referral_material1_desc,
                    R.string.referral_material2 to R.string.referral_material2_desc,
                    R.string.referral_material3 to R.string.referral_material3_desc,
                ).forEach { (heading, description) ->
                    u.add(content, u.text(heading, 15f, 600), top = 24); body(description)
                }
                action(R.string.referral_copy_template, true) { copyText(u.s(R.string.referral_template)) }
            }
        }
    }

    /** 剪贴板里那串看起来像邀请码的东西。落地页在用户点开时就写进去了。 */
    private fun clipboardCode(): String? {
        val clip = (requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
            ?.primaryClip?.takeIf { it.itemCount > 0 } ?: return null
        val text = runCatching { clip.getItemAt(0).coerceToText(requireContext()).toString() }.getOrNull()
        return text?.let(::parseReferralCode)
    }

    private fun copyText(value: CharSequence) {
        (requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText(getString(R.string.referral_title), value))
        Toast.makeText(requireContext(), R.string.referral_copy_success, Toast.LENGTH_SHORT).show()
    }

    private fun unavailable() {
        Toast.makeText(requireContext(), R.string.referral_action_unavailable, Toast.LENGTH_SHORT).show()
        dismiss()
    }

    private fun date(time: Long) = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(time))

    companion object {
        internal const val DAY_MS = 86_400_000L

        internal fun newInstance(kind: ReferralSheetKind, task: ReferralTask?, code: String? = null, cardId: Long? = null) =
            ReferralPlanSheet().apply {
                arguments = bundleOf("kind" to kind.name, "task" to task?.name, "code" to code, "cardId" to cardId)
            }
    }
}

/**
 * 从一段文字里认出邀请码：整条邀请链接、带空格、小写都认。
 *
 * 字母表里没有 0/O/1/I/L，所以抄错的那一位落不进任何合法码 —— 认不出来就是认不出来，
 * 不去猜。猜错的代价是把奖励记到另一个人头上。规则和服务端 `normalizeReferralCode`
 * 一致；这里只是提前挡掉明显不合法的输入，最终仍以服务端为准。
 */
internal fun parseReferralCode(raw: String?): String? {
    if (raw.isNullOrBlank() || raw.length > 200) return null
    var text = raw.trim().uppercase()
    Regex("/I/([0-9A-Z]+)").find(text)?.let { text = it.groupValues[1] }
    text = text.replace(Regex("[\\s-]"), "")
    return text.takeIf { Regex("^[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{8}$").matches(it) }
}
