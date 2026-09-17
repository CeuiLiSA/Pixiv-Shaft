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
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.lifecycleScope
import ceui.lisa.R
import ceui.pixiv.session.SessionManager
import ceui.pixiv.witstudio.dialog.*
import ceui.pixiv.witstudio.theme.V3Palette
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
        val palette = V3Palette.from(ctx)
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
                column.addView(label(getString(R.string.plaza_block_notice)))
                status = label("").apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
                column.addView(status)
                return ScrollView(ctx).apply { addView(column) }
            }
        }
        builder.setTitle(getString(R.string.plaza_block_user))
        cancel = WitDialogAction(getString(R.string.cancel)) { _, _ -> dismiss() }
        submit = WitDialogAction(getString(R.string.plaza_block_user)) { _, _ -> model.submit() }
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
        status?.text = when {
            state.busy -> getString(R.string.plaza_report_sending)
            state.error != null -> state.error.resolve(requireContext())
            else -> ""
        }
    }

    override fun onDestroyView() {
        status = null; submit = null; cancel = null
        super.onDestroyView()
    }
}
