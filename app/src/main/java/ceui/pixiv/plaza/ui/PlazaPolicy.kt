package ceui.pixiv.plaza.ui

import android.content.Context
import ceui.lisa.R
import ceui.pixiv.session.SessionManager
import ceui.pixiv.witstudio.dialog.WitDialog

internal const val PLAZA_POLICY_VERSION = "2026-09-16"
internal fun Context.withPlazaPolicy(action: () -> Unit) {
    val owner = SessionManager.loggedInUid
    if (owner <= 0) { showPlazaError(ceui.pixiv.plaza.PlazaMessage(R.string.plaza_auth_error)); return }
    val prefs = applicationContext.getSharedPreferences("plaza-safety", Context.MODE_PRIVATE)
    val key = "policy:$owner"
    if (prefs.getString(key, null) == PLAZA_POLICY_VERSION) { action(); return }
    WitDialog.MessageDialogBuilder(this)
        .setTitle(getString(R.string.plaza_policy_title))
        .setMessage(getString(R.string.plaza_policy_body))
        .addAction(getString(R.string.cancel)) { dialog, _ -> dialog.dismiss() }
        .addAction(getString(R.string.plaza_policy_accept)) { dialog, _ ->
            dialog.dismiss()
            if (owner == SessionManager.loggedInUid) {
                prefs.edit().putString(key, PLAZA_POLICY_VERSION).apply()
                action()
            }
        }.show()
}
