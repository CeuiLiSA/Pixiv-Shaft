package ceui.pixiv.widgets

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CompletableDeferred

class FragmentResultStore : ViewModel() {

    val taskMap = hashMapOf<String, CompletableDeferred<Any>>()
}