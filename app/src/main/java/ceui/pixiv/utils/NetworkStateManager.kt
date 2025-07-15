package ceui.pixiv.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import ceui.pixiv.client.IpLocationResponse
import ceui.pixiv.utils.NetworkStateManager.NetworkType
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber

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

    private val _isVpnActive = MutableLiveData(false)
    private val _ipLocationResponse = MutableLiveData<IpLocationResponse?>(null)

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
        _isVpnActive.postValue(isVpnActive(context))
        MainScope().launch {
            if (!_taskMutex.tryLock()) {
                Timber.e("fetchLocationInfoFromIp refresh tryLock returned")
                return@launch
            }

            _ipLocationResponse.postValue(fetchLocationInfoFromIp())
        }
    }

    companion object {
        fun isVpnActive(context: Context): Boolean {
            val connectivityManager =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            val activeNetwork = connectivityManager.activeNetwork ?: return false
            val capabilities =
                connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
            return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
    }

    fun isInChinaMainland(): Boolean {
        return _ipLocationResponse.value?.country == "CN"
    }

    private suspend fun fetchLocationInfoFromIp(): IpLocationResponse? =
        withContext(Dispatchers.IO) {
            try {
                val client = OkHttpClient()
                val request = Request.Builder()
                    .url("https://ipapi.co/json/")
                    .build()
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: return@withContext null
                    val result = Gson().fromJson(body, IpLocationResponse::class.java)
                    Timber.d("fetchLocationInfoFromIp ${body}")
                    return@withContext result
                }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            } finally {
                _taskMutex.unlock() // 释放锁
            }
        }
}
