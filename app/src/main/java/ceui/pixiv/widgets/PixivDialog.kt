package ceui.pixiv.widgets

import android.os.Bundle
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.ViewModel
import ceui.lisa.R
import kotlinx.coroutines.CompletableDeferred

class DialogViewModel : ViewModel() {
    val menuTaskPool = hashMapOf<String, CompletableDeferred<MenuItem>>()
    val alertTaskPool = hashMapOf<String, CompletableDeferred<Boolean>>()
}

open class PixivDialog(layoutId: Int) : DialogFragment(layoutId) {

    protected val viewModel by activityViewModels<DialogViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.TransparentDialogTheme)
    }

    open fun performCancel() {

    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setWindowAnimations(R.style.dialog_animation_fade)
        dialog?.setOnCancelListener {
            performCancel()
        }
    }
}