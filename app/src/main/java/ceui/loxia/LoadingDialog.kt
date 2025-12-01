package ceui.loxia

import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import android.widget.ProgressBar
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment

class LoadingDialog : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val progress = ProgressBar(requireContext())
        val padding = (32 * resources.displayMetrics.density).toInt()
        progress.setPadding(padding, padding, padding, padding)

        return AlertDialog.Builder(requireContext())
            .setView(progress)
            .setCancelable(false)
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
