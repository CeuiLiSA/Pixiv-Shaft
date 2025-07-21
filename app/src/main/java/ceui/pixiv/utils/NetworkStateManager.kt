package ceui.pixiv.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import ceui.pixiv.utils.NetworkStateManager.NetworkType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.TimeUnit

interface INetworkState {
    val networkState: LiveData<NetworkType>
}

class NetworkStateManager(private val context: Context) : INetworkState {

    enum class NetworkType {
        WIFI,
        CELLULAR,
        NONE
    }

    private val _taskMutex = Mutex() // 互斥锁，防止重复刷新
    private val _networkState = MutableLiveData(NetworkType.NONE)
    override val networkState: LiveData<NetworkType> get() = _networkState

    private val _canAccessGoogle = MutableLiveData<Boolean>()
    val canAccessGoogle: LiveData<Boolean> = _canAccessGoogle

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

            _canAccessGoogle.postValue(canAccessGoogle())
        }
    }

    companion object {
        fun isGoogleCanBeAccessed(context: Context): Boolean {
            val connectivityManager =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            val activeNetwork = connectivityManager.activeNetwork ?: return false
            val capabilities =
                connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
            return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
    }

    private suspend fun canAccessGoogle(): Boolean = withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient.Builder()
                .callTimeout(2, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder()
                .url("https://www.google.com/generate_204")  // ✅ 专门用于网络连通性检测的轻量接口
                .build()

            val response = client.newCall(request).execute()
            return@withContext response.code == 204 // Google 返回 204 表示成功连接
        } catch (e: IOException) {
            Timber.w("Google connectivity check failed: ${e.message}")
            return@withContext false
        }
    }
}
