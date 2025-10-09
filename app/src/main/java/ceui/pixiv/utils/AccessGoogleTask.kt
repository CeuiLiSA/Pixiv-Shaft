package ceui.pixiv.utils

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import ceui.loxia.RefreshState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis

class AccessGoogleTask {

    private val _taskMutex = Mutex()


    private val _refreshState = MutableLiveData<RefreshState>()
    val refreshState: LiveData<RefreshState> = _refreshState


    val canAccessGoogle = MutableLiveData(true)
    val canAccessGoogleFlow = MutableStateFlow(false)

    private suspend fun impl(): Boolean = withContext(Dispatchers.Main) {
        try {
            _refreshState.value = RefreshState.LOADING()

            var canAccess = false
            val elapsed = measureTimeMillis {
                canAccess = withContext(Dispatchers.IO) {
                    val client = OkHttpClient.Builder()
                        .callTimeout(3, TimeUnit.SECONDS)
                        .build()

                    val request = Request.Builder()
                        .url("https://www.google.com/generate_204")
                        .build()

                    val response = client.newCall(request).execute()
                    if (response.code == 204) {
                        true
                    } else {
                        throw RuntimeException(response.peekBody(Long.MAX_VALUE).string())
                    }
                }
            }

            Timber.d("Google connectivity check took ${elapsed}ms")

            _refreshState.value = RefreshState.LOADED(hasContent = canAccess, hasNext = false)
            return@withContext canAccess
        } catch (ex: IOException) {
            Timber.w("Google connectivity check failed: ${ex.message}")
            _refreshState.value = RefreshState.ERROR(ex)
            return@withContext false
        } finally {
            _taskMutex.unlock()
        }
    }

    fun checkIfCanAccessGoogle() {
        MainScope().launch {
            if (!_taskMutex.tryLock()) {
                Timber.e("fetchLocationInfoFromIp refresh tryLock returned")
                return@launch
            }

            val canAccess = impl()
//            canAccessGoogle.postValue(canAccess)
            canAccessGoogleFlow.value = canAccess // Flow 监听这里的值
        }
    }
}