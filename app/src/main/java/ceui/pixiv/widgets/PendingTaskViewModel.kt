package ceui.pixiv.widgets

import android.os.Parcelable
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CompletableDeferred
import java.io.Serializable
import java.util.concurrent.ConcurrentHashMap

data class FragmentResultByFragment<FragmentT: Fragment, ResultT>(
    val result: ResultT,
    val fragment: FragmentT,
)

class FragmentResultStore : ViewModel() {

    private val _taskMap = hashMapOf<String, CompletableDeferred<FragmentResultByFragment<*, *>>>()
    private val _pendingResultMap = hashMapOf<String, Any>()

    fun putTask(requestId: String, task: CompletableDeferred<FragmentResultByFragment<*, *>>) {
        _taskMap[requestId] = task
    }

    fun getTypedTask(requestId: String): CompletableDeferred<FragmentResultByFragment<*, *>>? {
        return _taskMap[requestId]
    }


    fun putResult(fragmentUniqueId: String, result: Any) {
        _pendingResultMap[fragmentUniqueId] = result
    }

    fun getTypedResult(fragmentUniqueId: String): Any? {
        return _pendingResultMap[fragmentUniqueId]
    }

    fun removeResult(fragmentUniqueId: String) {
        _pendingResultMap.remove(fragmentUniqueId)
    }
}