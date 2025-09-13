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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.scan

interface INetworkState {
    val networkState: LiveData<NetworkType>
}

class NetworkStateManager(private val context: Context) : INetworkState {

    enum class NetworkType {
        WIFI,
        CELLULAR,
        NONE
    }

    private val accessGoogleTask = AccessGoogleTask()

    val refreshState: LiveData<RefreshState>
        get() {
            return accessGoogleTask.refreshState
        }
    private val _networkState = MutableLiveData(NetworkType.NONE)
    override val networkState: LiveData<NetworkType> get() = _networkState

    val canAccessGoogle: LiveData<Boolean> = accessGoogleTask.canAccessGoogle

    val googleAccessRecoveredFlow: Flow<Boolean> = accessGoogleTask.canAccessGoogleFlow
        .scan(false to false) { acc, current ->
            acc.second to current
        }
        .filter { (previous, current) -> !previous && current }
        .map { true } // 只在 false -> true 时 emit true


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
        checkIfCanAccessGoogle()
    }

    fun checkIfCanAccessGoogle() {
        accessGoogleTask.checkIfCanAccessGoogle()
    }
}
