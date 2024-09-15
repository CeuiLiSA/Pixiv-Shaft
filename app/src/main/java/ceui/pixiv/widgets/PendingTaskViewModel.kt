package ceui.pixiv.widgets

import android.os.Parcelable
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CompletableDeferred
import java.io.Serializable
import java.util.concurrent.ConcurrentHashMap

class FragmentResultStore : ViewModel() {

    private val _taskMap = hashMapOf<String, CompletableDeferred<*>>()
    private val _pendingResultMap = hashMapOf<String, () -> Unit>()

    fun <T> putTask(requestId: String, task: CompletableDeferred<T>) {
        _taskMap[requestId] = task
    }

    fun <T> getTypedTask(requestId: String): CompletableDeferred<T>? {
        return _taskMap.getOrPut(requestId, defaultValue = { CompletableDeferred<T>() }) as? CompletableDeferred<T>
    }


    fun putResult(fragmentUniqueId: String, block: () -> Unit) {
        _pendingResultMap[fragmentUniqueId] = block
    }

    fun getTypedResult(fragmentUniqueId: String): (() -> Unit)? {
        return _pendingResultMap[fragmentUniqueId]
    }

    fun removeResult(fragmentUniqueId: String) {
        _pendingResultMap.remove(fragmentUniqueId)
    }
}