package ceui.pixiv.ui.settings

import android.text.InputType
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import ceui.lisa.BuildConfig
import ceui.lisa.R
import ceui.lisa.utils.Common
import ceui.pixiv.auth.DebugAppAuthorization
import ceui.pixiv.auth.DebugAppGrant
import ceui.pixiv.auth.DebugAuthorizationException
import ceui.pixiv.witstudio.dialog.WitDialog
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

object DebugAppAuthorizationSettings {
    @JvmStatic
    fun bind(fragment: Fragment, row: View, status: TextView) {
        if (!BuildConfig.DEBUG) return
        row.visibility = View.VISIBLE
        val owner = fragment.viewLifecycleOwner
        val context = row.context
        var confirmed = true

        fun render(grant: DebugAppGrant?) {
            val state = when {
                grant == null -> R.string.app_authorization_none
                grant.currentState == "expired" -> R.string.app_authorization_expired
                grant.currentState == "revoked" -> R.string.app_authorization_revoked
                !confirmed -> R.string.app_authorization_unconfirmed
                else -> R.string.app_authorization_active
            }
            val text = context.getString(state)
            val format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            status.text = when {
                grant == null -> text
                state == R.string.app_authorization_unconfirmed -> context.getString(
                    R.string.app_authorization_status_checked, text, format.format(Date(grant.checkedAt)),
                )
                else -> context.getString(
                    R.string.app_authorization_status_deadline, text, format.format(Date(grant.expiresAt)),
                )
            }
        }

        fun submit(action: suspend () -> Unit) {
            row.isEnabled = false
            owner.lifecycleScope.launch {
                try {
                    action()
                    confirmed = true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    val message = when ((error as? DebugAuthorizationException)?.code) {
                        "uid_forbidden" -> R.string.app_authorization_wrong_account
                        "debug_key_invalid", "debug_key_used" -> R.string.app_authorization_invalid_key
                        "debug_activation_expired" -> R.string.app_authorization_expired
                        "debug_activation_revoked" -> R.string.app_authorization_revoked
                        else -> R.string.app_authorization_request_failed
                    }
                    Common.showToast(context.getString(message))
                } finally {
                    render(DebugAppAuthorization.changes.value)
                    row.isEnabled = true
                }
            }
        }

        fun prompt() {
            val builder = WitDialog.EditTextDialogBuilder(context)
                .setTitle(R.string.app_authorization_title)
                .setPlaceholder(context.getString(R.string.app_authorization_key))
                .setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
            builder.addAction(R.string.string_142) { dialog, _ -> dialog.dismiss() }
                .addAction(R.string.app_authorization_apply) { dialog, _ ->
                    val field = builder.editText
                    val key = field.text?.toString()?.trim().orEmpty()
                    if (key.isEmpty()) return@addAction
                    field.text?.clear()
                    dialog.dismiss()
                    submit { DebugAppAuthorization.apply(key) }
                }
                .create()
                .show()
        }

        row.setOnClickListener {
            if (DebugAppAuthorization.changes.value == null) {
                prompt()
            } else {
                WitDialog.MessageDialogBuilder(context)
                    .setTitle(R.string.app_authorization_title)
                    .setMessage(status.text)
                    .addAction(R.string.string_142) { dialog, _ -> dialog.dismiss() }
                    .addAction(R.string.app_authorization_clear) { dialog, _ ->
                        dialog.dismiss()
                        submit { DebugAppAuthorization.clear() }
                    }
                    .addAction(R.string.app_authorization_replace) { dialog, _ ->
                        dialog.dismiss()
                        prompt()
                    }
                    .create()
                    .show()
            }
        }
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    DebugAppAuthorization.changes.collectLatest {
                        render(it)
                        if (it?.currentState == "active") {
                            delay((it.localExpiresAt - System.currentTimeMillis()).coerceAtLeast(1L))
                            render(it)
                        }
                    }
                }
                confirmed = DebugAppAuthorization.refreshStatus()
                render(DebugAppAuthorization.changes.value)
            }
        }
    }
}
