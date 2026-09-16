package ceui.pixiv.plaza.ui

import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.os.bundleOf
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import ceui.lisa.R
import ceui.pixiv.plaza.*
import ceui.pixiv.session.SessionManager
import ceui.pixiv.witstudio.dialog.*
import ceui.pixiv.witstudio.theme.V3Palette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

internal fun Context.showPlazaModeration(postId: Long, uid: Long, mode: String) {
    var ctx: Context = this
    while (ctx is ContextWrapper && ctx !is FragmentActivity) ctx = ctx.baseContext
    val manager = (ctx as? FragmentActivity)?.supportFragmentManager ?: return
    if (manager.isStateSaved || manager.findFragmentByTag("plaza-moderation") != null) return
    PlazaModerationDialog().apply {
        arguments = bundleOf("postId" to postId, "targetUid" to uid, "mode" to mode)
    }.show(manager, "plaza-moderation")
}

internal data class ModerationState(
    val busy: Boolean = false,
    val error: PlazaMessage? = null,
    val done: Boolean = false,
    val receiptId: Long? = null,
    val receiptStatus: String = "pending",
    val blocks: List<PlazaBlockedUser> = emptyList(),
)

internal class PlazaModerationModel @JvmOverloads constructor(
    private val saved: SavedStateHandle,
    private val api: PlazaApi = PlazaRepository.api,
    private val currentUid: () -> Long = { SessionManager.loggedInUid },
    private val safetyChanged: (Long) -> Unit = PlazaRepository::safetyChanged,
) : ViewModel() {
    private fun requireAccount() {
        if (owner <= 0 || owner != currentUid()) throw PlazaFailure(PlazaMessage(R.string.plaza_account_changed))
    }
    private val owner = saved.get<Long>("owner") ?: currentUid().also { saved["owner"] = it }
    val mode = saved.get<String>("mode") ?: "post"
    val state = MutableStateFlow(ModerationState())
    var reason: String
        get() = saved["reason"] ?: ""
        set(value) { saved["reason"] = value }
    var details: String
        get() = saved["details"] ?: ""
        set(value) { saved["details"] = value }

    fun submit(unblockUid: Long? = null) {
        if (state.value.busy || state.value.done) return
        if (mode in listOf("post", "user") &&
            (reason.isEmpty() || (reason == "other" && details.isBlank()))) {
            state.value = state.value.copy(error = PlazaMessage(R.string.plaza_report_required))
            return
        }
        state.value = state.value.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                PlazaRepository.mutate {
                    requireAccount()
                    when (mode) {
                        "blocks" -> {
                            if (unblockUid != null) {
                                try {
                                    check(api.unblock(unblockUid).ok)
                                } finally {
                                    // A lost response or account switch does not undo a server write.
                                    safetyChanged(owner)
                                }
                                requireAccount()
                            }
                            val users = api.blocks().items
                            requireAccount()
                            state.value = state.value.copy(blocks = users)
                        }
                        "block" -> {
                            val target = checkNotNull(saved.get<Long>("targetUid"))
                            try {
                                check(api.block(target).ok)
                            } finally {
                                safetyChanged(owner)
                            }
                            requireAccount()
                            state.value = state.value.copy(done = true)
                        }
                        else -> {
                            val receipt = api.report(checkNotNull(saved.get<Long>("postId")),
                                PlazaReportRequest(mode, reason, details.trim()))
                            requireAccount()
                            check(receipt.id > 0)
                            state.value = state.value.copy(done = true, receiptId = receipt.id, receiptStatus = receipt.status)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state.value = state.value.copy(error = plazaError(e))
            } finally {
                state.value = state.value.copy(busy = false)
            }
        }
    }
}

/** The ViewModel owns submissions; rotating the dialog cannot duplicate or lose an accepted report. */
class PlazaModerationDialog : DialogFragment() {
    private val model: PlazaModerationModel by viewModels()
    private var status: TextView? = null
    private var fields: LinearLayout? = null
    private var submit: WitDialogAction? = null
    private var cancel: WitDialogAction? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val ctx = requireContext()
        val palette = V3Palette.from(ctx)
        val report = model.mode in listOf("post", "user")
        val title = when (model.mode) {
            "user" -> R.string.plaza_report_user
            "block" -> R.string.plaza_block_user
            "blocks" -> R.string.plaza_blocked_users
            else -> R.string.plaza_report_post
        }
        val builder = object : WitDialog.CustomDialogBuilder(ctx) {
            override fun onCreateContent(dialog: WitDialog, parent: WitDialogView, context: Context): View {
                val column = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(ctx.dp(24), ctx.dp(12), ctx.dp(24), ctx.dp(16))
                }
                fun label(text: String) = TextView(ctx).apply {
                    this.text = text
                    textSize = 14f
                    typeface = androidx.core.content.res.ResourcesCompat.getFont(ctx, R.font.montserrat_regular)
                    setTextColor(palette.textSecondary)
                    setLineSpacing(ctx.dp(4).toFloat(), 1f)
                }
                column.addView(label(getString(if (report) R.string.plaza_report_notice else
                    if (model.mode == "block") R.string.plaza_block_notice else R.string.plaza_blocks_notice)))
                val content = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
                fields = content
                column.addView(content)
                if (report) {
                    val reasons = listOf("child_safety", "sexual", "violence", "hate", "harassment", "privacy", "spam", "illegal", "other")
                    val labels = resources.getStringArray(R.array.plaza_report_reasons)
                    val group = RadioGroup(ctx)
                    reasons.forEachIndexed { i, key ->
                        group.addView(RadioButton(ctx).apply {
                            id = i + 1
                            text = labels[i]
                            textSize = 14f
                            typeface = androidx.core.content.res.ResourcesCompat.getFont(ctx, R.font.montserrat_regular)
                            minHeight = ctx.dp(48)
                            setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.v3_text_1))
                            buttonTintList = android.content.res.ColorStateList(
                                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                                intArrayOf(palette.primary, palette.textSecondary))
                        })
                    }
                    group.check(reasons.indexOf(model.reason).let { if (it < 0) -1 else it + 1 })
                    group.setOnCheckedChangeListener { _, id -> model.reason = reasons[id - 1] }
                    content.addView(group)
                    content.addView(AppCompatEditText(ctx).apply {
                        hint = getString(R.string.plaza_report_details)
                        contentDescription = hint
                        textSize = 14f
                        typeface = androidx.core.content.res.ResourcesCompat.getFont(ctx, R.font.montserrat_regular)
                        setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.v3_text_1))
                        setHintTextColor(palette.textSecondary)
                        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                        filters = arrayOf(InputFilter.LengthFilter(1000))
                        minLines = 2
                        maxLines = 5
                        setText(model.details)
                        doAfterTextChanged { model.details = it?.toString().orEmpty() }
                    })
                }
                status = label("").apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
                column.addView(status)
                return ScrollView(ctx).apply { addView(column) }
            }
        }
        builder.setTitle(getString(title))
        cancel = WitDialogAction(getString(R.string.cancel)) { _, _ -> dismiss() }
        submit = WitDialogAction(getString(if (report) R.string.plaza_report_submit else
            if (model.mode == "blocks") R.string.plaza_retry else R.string.plaza_block_user)) { _, _ -> model.submit() }
        builder.addAction(cancel).addAction(submit)
        return builder.create()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                model.state.collect { state ->
                    if (state.done && !state.busy && dialog?.isShowing == true) {
                        val message = state.receiptId?.let { id ->
                            val resource = if (state.receiptStatus == "pending")
                                R.string.plaza_report_success else R.string.plaza_report_reviewed
                            getString(resource, id)
                        } ?: getString(R.string.plaza_block_success)
                        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
                        dismiss()
                    }
                    render(state)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        render(model.state.value)
        if (model.mode == "blocks") model.submit()
    }

    private fun render(state: ModerationState) {
        isCancelable = !state.busy
        cancel?.setEnabled(!state.busy)
        submit?.setEnabled(!state.busy && !state.done)
        status?.text = when {
            state.busy -> getString(R.string.plaza_report_sending)
            state.error != null -> state.error.resolve(requireContext())
            model.mode == "blocks" && state.blocks.isEmpty() -> getString(R.string.plaza_blocks_empty)
            else -> ""
        }
        fun enable(view: View) {
            view.isEnabled = !state.busy
            if (view is android.view.ViewGroup) for (i in 0 until view.childCount) enable(view.getChildAt(i))
        }
        fields?.let(::enable)
        if (model.mode == "blocks") fields?.let { column ->
            column.removeAllViews()
            state.blocks.forEach { user ->
                column.addView(requireContext().pillButton(getString(R.string.plaza_unblock_user, user.displayName), false) {
                    model.submit(user.uid)
                }.apply { isEnabled = !state.busy })
            }
        }
    }

    override fun onDestroyView() {
        status = null; fields = null; submit = null; cancel = null
        super.onDestroyView()
    }
}
