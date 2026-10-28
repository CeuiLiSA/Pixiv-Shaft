package ceui.pixiv.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import android.os.Build
import ceui.pixiv.utils.NetworkStateManager.NetworkType
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

interface INetworkState {
    val networkState: LiveData<NetworkType>
}

/**
 * 进程级网络状态，由 Hilt 以 @Singleton 构造并经 [ceui.pixiv.services.ServicesProvider] 暴露。
 * 构造时向 ConnectivityManager 注册回调（一次 binder 调用，不读任何全局状态），所以能在
 * Shaft 的 super.onCreate() 注入时就建好。
 */
@Singleton
class NetworkStateManager @Inject constructor(@ApplicationContext context: Context) : INetworkState {

    enum class NetworkType {
        WIFI,
        CELLULAR,
        NONE;

        val isOnline: Boolean get() = this == WIFI || this == CELLULAR
    }

    private val _networkState = MutableLiveData(NetworkType.NONE)
    override val networkState: LiveData<NetworkType> get() = _networkState

    private val connectivityManager: ConnectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            updateNetworkType(network)
        }

        override fun onLost(network: Network) {
            updateNetworkType(null)
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
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
    }
}
