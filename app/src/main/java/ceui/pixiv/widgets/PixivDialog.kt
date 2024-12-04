package ceui.pixiv.widgets

import android.os.Bundle
import android.view.View
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import ceui.lisa.R
import ceui.loxia.Event
import ceui.loxia.FRAGMENT_RESULT_REQUEST_ID
import ceui.pixiv.ui.common.FragmentResultRequestIdOwner
import ceui.pixiv.ui.common.NavFragmentViewModel
import kotlinx.coroutines.CompletableDeferred

class DialogViewModel : ViewModel() {
    val menuTaskPool = hashMapOf<String, CompletableDeferred<MenuItem>>()
    val alertTaskPool = hashMapOf<String, CompletableDeferred<Boolean>>()

    val choosenOffsetPage = MutableLiveData<Int>()
    val triggerOffsetPageEvent = MutableLiveData<Event<Int>>()

    val chosenUsersYoriCount = MutableLiveData<Int>()
    val triggerUsersYoriEvent = MutableLiveData<Event<Long>>()
}

open class PixivDialog(layoutId: Int) : DialogFragment(layoutId), FragmentResultRequestIdOwner {

    protected val viewModel by activityViewModels<DialogViewModel>()
    private val fragmentViewModel: NavFragmentViewModel by viewModels()
    private val fragmentResultStore by activityViewModels<FragmentResultStore>()

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


    open fun onViewFirstCreated(view: View) {

    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)


        if (fragmentViewModel.viewCreatedTime.value == null) {
            onViewFirstCreated(view)
        }

        fragmentViewModel.viewCreatedTime.value = System.currentTimeMillis()
    }

    fun <T: Any> setFragmentResult(result: T) {
        resultRequestId?.let { requestId ->
            fragmentResultStore.putResult(requestId, result)
        }
    }

    override val resultRequestId: String?
        get() = arguments?.getString(FRAGMENT_RESULT_REQUEST_ID)
    override val fragmentUniqueId: String
        get() = fragmentViewModel.fragmentUniqueId
}