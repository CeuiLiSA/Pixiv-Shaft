package ceui.pixiv.widgets

import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CompletableDeferred
import org.checkerframework.checker.units.qual.A

class FragmentResultStore : ViewModel() {

    private val _taskMap = hashMapOf<String, CompletableDeferred<*>>()
    private val _pendingResultMap = hashMapOf<String, () -> Unit>()

    fun <T> putTask(requestId: String, task: CompletableDeferred<T>) {
        _taskMap[requestId] = task
    }

    fun <T> getTypedTask(requestId: String): CompletableDeferred<T>? {
        return _taskMap.getOrPut(requestId, defaultValue = { CompletableDeferred<T>() }) as? CompletableDeferred<T>
    }


    fun putResult(requestId: String, block: () -> Unit) {
        _pendingResultMap[requestId] = block
    }

    fun getTypedResult(requestId: String): (() -> Unit)? {
        return _pendingResultMap[requestId]
    }
}