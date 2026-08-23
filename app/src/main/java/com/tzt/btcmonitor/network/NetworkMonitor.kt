package com.tzt.btcmonitor.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.tzt.btcmonitor.logging.LogManager
import com.tzt.btcmonitor.model.NetworkType

class NetworkMonitor(
    context: Context,
    private val logs: LogManager,
    private val onChanged: (NetworkType) -> Unit
) {
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
    private var registered = false
    @Volatile private var lastType = NetworkType.OFFLINE

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = publish(currentType(), "NetworkAvailable")
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            publish(typeFrom(capabilities), "NetworkAvailable")
        }
        override fun onLost(network: Network) = publish(currentType(), "NetworkLost")
    }

    fun start() {
        if (registered) return
        registered = true
        publish(currentType(), "NetworkAvailable")
        connectivityManager.registerDefaultNetworkCallback(callback)
    }

    fun stop() {
        if (!registered) return
        registered = false
        runCatching { connectivityManager.unregisterNetworkCallback(callback) }
    }

    private fun currentType(): NetworkType {
        val network = connectivityManager.activeNetwork ?: return NetworkType.OFFLINE
        return connectivityManager.getNetworkCapabilities(network)?.let(::typeFrom)
            ?: NetworkType.OFFLINE
    }

    private fun typeFrom(capabilities: NetworkCapabilities): NetworkType = when {
        !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> NetworkType.OFFLINE
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.CELLULAR
        else -> NetworkType.OTHER
    }

    @Synchronized
    private fun publish(type: NetworkType, event: String) {
        if (type == lastType) return
        lastType = type
        logs.log(if (type == NetworkType.OFFLINE) "NetworkLost" else event, type.name)
        onChanged(type)
    }
}
