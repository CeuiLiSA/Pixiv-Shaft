package ceui.pixiv.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import ceui.loxia.RefreshState
import ceui.pixiv.utils.NetworkStateManager.NetworkType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis

interface INetworkState {
    val networkState: LiveData<NetworkType>
}

class NetworkStateManager(private val context: Context) : INetworkState {

    enum class NetworkType {
        WIFI,
        CELLULAR,
        NONE
    }

    private val _taskMutex = Mutex()
    private val _networkState = MutableLiveData(NetworkType.NONE)
    override val networkState: LiveData<NetworkType> get() = _networkState

    private val _canAccessGoogle = MutableLiveData<Boolean>()
    val canAccessGoogle: LiveData<Boolean> = _canAccessGoogle

    private val _canAccessGoogleFlow = MutableStateFlow(false)

    val googleAccessRecoveredFlow: Flow<Boolean> = _canAccessGoogleFlow
        .scan(false to false) { acc, current ->
            acc.second to current
        }
        .filter { (previous, current) -> !previous && current }
        .map { true } // 只在 false -> true 时 emit true

    private val _refreshState = MutableLiveData<RefreshState>()
    val refreshState: LiveData<RefreshState> = _refreshState

    private val connectivityManager: ConnectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            updateNetworkType(network)
        }

        override fun onLost(network: Network) {
            updateNetworkType(null)
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities
        ) {
            updateNetworkType(network)
        }
    }

    init {
        registerNetworkCallback()
    }

    private fun registerNetworkCallback() {
        try {
            val networkRequest = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                connectivityManager.registerDefaultNetworkCallback(networkCallback)
            } else {
                connectivityManager.registerNetworkCallback(networkRequest, networkCallback)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            _networkState.postValue(NetworkType.NONE)
        }
    }

    fun unregisterNetworkCallback() {
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updateNetworkType(network: Network?) {
        val networkCapabilities = network?.let { connectivityManager.getNetworkCapabilities(it) }
        val networkType = when {
            networkCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> NetworkType.WIFI
            networkCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> NetworkType.CELLULAR
            else -> NetworkType.NONE
        }
        _networkState.postValue(networkType)
        MainScope().launch {
            if (!_taskMutex.tryLock()) {
                Timber.e("fetchLocationInfoFromIp refresh tryLock returned")
                return@launch
            }

            val canAccess = canAccessGoogle()
            _canAccessGoogle.postValue(canAccess)
            _canAccessGoogleFlow.value = canAccess // Flow 监听这里的值
        }
    }

    private suspend fun canAccessGoogle(): Boolean = withContext(Dispatchers.Main) {
        try {
            _refreshState.value = RefreshState.LOADING()

            var canAccess = false
            val elapsed = measureTimeMillis {
                canAccess = withContext(Dispatchers.IO) {
                    val client = OkHttpClient.Builder()
                        .callTimeout(2, TimeUnit.SECONDS)
                        .build()

                    val request = Request.Builder()
                        .url("https://www.google.com/generate_204")
                        .build()

                    val response = client.newCall(request).execute()
                    response.code == 204
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

}
