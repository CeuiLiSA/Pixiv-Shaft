package ceui.pixiv.plaza.ui

import android.content.Context
import ceui.lisa.R
import ceui.pixiv.plaza.PlazaMessage
import ceui.pixiv.witstudio.dialog.WitDialog

internal fun Context.showPlazaError(error: PlazaMessage) {
    WitDialog.MessageDialogBuilder(this)
        .setTitle(getString(R.string.plaza_error_title))
        .setMessage(error.resolve(this))
        .addAction(getString(R.string.plaza_understood)) { dialog, _ -> dialog.dismiss() }
        .show()
}
