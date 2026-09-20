package ceui.pixiv.plaza.ui

import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.lifecycleScope
import ceui.lisa.R
import ceui.pixiv.session.SessionManager
import ceui.pixiv.witstudio.dialog.*
import ceui.pixiv.witstudio.theme.*
import kotlinx.coroutines.launch

internal fun Context.showPlazaModeration(postId: Long, uid: Long, mode: String) {
    if (mode == "post" || mode == "user" || mode == "blocks") {
        startActivity(android.content.Intent(this, ceui.lisa.activities.TemplateActivity::class.java).apply {
            putExtra(ceui.lisa.activities.TemplateActivity.EXTRA_FRAGMENT,
                if (mode == "blocks") ceui.pixiv.ui.navigation.TemplateRoute.PLAZA_BLOCKS.key
                else ceui.pixiv.ui.navigation.TemplateRoute.PLAZA_REPORT.key)
            putExtra("postId", postId)
            putExtra("targetUid", uid)
            putExtra("mode", mode)
            putExtra("owner", SessionManager.loggedInUid)
        })
        return
    }
    var ctx: Context = this
    while (ctx is ContextWrapper && ctx !is FragmentActivity) ctx = ctx.baseContext
    val manager = (ctx as? FragmentActivity)?.supportFragmentManager ?: return
    if (manager.isStateSaved || manager.findFragmentByTag("plaza-moderation") != null) return
    PlazaModerationDialog().apply {
        arguments = bundleOf("postId" to postId, "targetUid" to uid, "mode" to mode)
    }.show(manager, "plaza-moderation")
}

/** Blocking confirmation; reports and blocked users have their own full pages. */
class PlazaModerationDialog : DialogFragment() {
    private val model: PlazaModerationModel by viewModels()
    private var status: TextView? = null
    private var submit: WitDialogAction? = null
    private var cancel: WitDialogAction? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val ctx = requireContext()
        val builder = object : WitDialog.CustomDialogBuilder(ctx) {
            // 与举报页同一套 V3 语言：图标容器交代动作类别，说明和状态共用次要文字色。
            override fun onCreateContent(dialog: WitDialog, parent: WitDialogView, context: Context): View {
                val column = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(ctx.dp(24), ctx.dp(8), ctx.dp(24), ctx.dp(16))
                }
                column.addView(
                    ctx.iconTile(R.drawable.ic_not_interested_black_24dp),
                    LinearLayout.LayoutParams(ctx.dp(48), ctx.dp(48)),
                )
                column.addView(
                    ctx.label(getString(R.string.plaza_block_notice), 14f, 400, ctx.color(R.color.v3_text_2))
                        .apply { lineHeightRatio(1.7f) },
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(16) },
                )
                status = ctx.label("", 13f, 500, ctx.color(R.color.v3_text_2)).apply {
                    lineHeightRatio(1.5f)
                    accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                }
                column.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(10) })
                return ScrollView(ctx).apply { addView(column) }
            }
        }
        builder.setTitle(getString(R.string.plaza_block_user))
        cancel = WitDialogAction(getString(R.string.cancel)) { _, _ -> dismiss() }
        submit = WitDialogAction(getString(R.string.plaza_block_user)) { _, _ -> model.submit() }
            .prop(WitDialogAction.ACTION_PROP_POSITIVE)
        builder.addAction(cancel).addAction(submit)
        return builder.create()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                model.state.collect { state ->
                    if (state.done && !state.busy && dialog?.isShowing == true) {
                        Toast.makeText(requireContext(), R.string.plaza_block_success, Toast.LENGTH_LONG).show()
                        dismiss()
                    }
                    render(state)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // An older APK may have saved an open block-list dialog in its activity state.
        if (model.mode == "blocks") {
            requireContext().showPlazaModeration(0L, 0L, "blocks")
            dismiss()
            return
        }
        render(model.state.value)
    }

    private fun render(state: ModerationState) {
        isCancelable = !state.busy
        cancel?.setEnabled(!state.busy)
        submit?.setEnabled(!state.busy && !state.done)
        status?.setTextColor(
            if (state.error != null) requireContext().color(R.color.v3_danger)
            else requireContext().color(R.color.v3_text_2)
        )
        status?.text = when {
            state.busy -> getString(R.string.plaza_report_sending)
            state.error != null -> state.error.resolve(requireContext())
            else -> ""
        }
        status?.isVisible = !status?.text.isNullOrEmpty()
    }

    override fun onDestroyView() {
        status = null; submit = null; cancel = null
        super.onDestroyView()
    }
}
