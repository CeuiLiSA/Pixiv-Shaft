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

interface INetworkState {
    val networkState: LiveData<NetworkType>
}

class NetworkStateManager(context: Context) {

    enum class NetworkType {
        WIFI,
        CELLULAR,
        NONE
    }

    private val _networkState = MutableLiveData<NetworkType>()
    val networkState: LiveData<NetworkType> = _networkState

    private val connectivityManager =
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
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
        } else {
            connectivityManager.registerNetworkCallback(networkRequest, networkCallback)
        }
    }

    fun unregisterNetworkCallback() {
        connectivityManager.unregisterNetworkCallback(networkCallback)
    }

    private fun updateNetworkType(network: Network?) {
        val networkType = if (network == null) {
            NetworkType.NONE
        } else {
            val currentNetworkCapabilities = connectivityManager.getNetworkCapabilities(network)
            when {
                currentNetworkCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> NetworkType.WIFI
                currentNetworkCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> NetworkType.CELLULAR
                else -> NetworkType.NONE
            }
        }

        _networkState.postValue(networkType)
    }
}

