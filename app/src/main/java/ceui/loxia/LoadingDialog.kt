package ceui.loxia

import android.app.Dialog
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import ceui.pixiv.witstudio.dialog.WitTipDialog

class LoadingDialog : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        isCancelable = false
        return WitTipDialog.Builder(requireContext())
            .create()
    }

    companion object {
        fun show(fragment: Fragment): LoadingDialog {
            val dialog = LoadingDialog()
            dialog.show(fragment.parentFragmentManager, "loading_dialog")
            return dialog
        }
    }
}
