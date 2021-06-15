package ceui.lisa.jetpack

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

//SOME TEST CODE

class SimpleTextViewModel: ViewModel() {
    val text = MutableLiveData<String?>()
}

class SingleValueViewModel<T>: ViewModel() {
    val content = MutableLiveData<T>()
}