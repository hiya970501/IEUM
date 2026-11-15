package com.example.afterglow.wear

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.net.HttpURLConnection
import java.net.URL

/** Foreground caller owns registration; HTTPS can use Wi-Fi, cellular or the phone's Internet proxy. */
internal class WatchWifiLink(context:Context) : AutoCloseable {
    private val connectivity=context.applicationContext.getSystemService(ConnectivityManager::class.java)
    @Volatile private var network:Network?=null
    private var callback:ConnectivityManager.NetworkCallback?=null
    @Synchronized fun start() {
        if(callback!=null) return
        val current=object:ConnectivityManager.NetworkCallback() {
            override fun onAvailable(value:Network) { synchronized(this@WatchWifiLink) { if(callback===this) network=value } }
            override fun onLost(value:Network) { synchronized(this@WatchWifiLink) { if(network==value) network=null } }
        }
        callback=current
        try { connectivity.registerDefaultNetworkCallback(current) }
        catch(e:Exception) { callback=null;throw e }
    }
    fun open(url:URL):HttpURLConnection {
        require(url.protocol=="https")
        val connection=(network ?: error("워치 인터넷 연결을 확인하세요")).openConnection(url) as javax.net.ssl.HttpsURLConnection
        connection.sslSocketFactory=com.example.afterglow.net.RelayTlsSocketFactory()
        return connection
    }
    @Synchronized override fun close() {
        val old=callback;callback=null;network=null
        old?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
    }
}
