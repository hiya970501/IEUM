package com.example.version1

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock

/** Foreground-owned GPS/network listener. Unregister on loss of foreground/consent/permission. */
internal class WeatherLocationTracker(context:Context,
    private val changed:(WeatherPosition)->Unit,private val unavailable:()->Unit,
    private val providers:List<String>?=null):AutoCloseable {
    private val context=context.applicationContext
    private val manager=this.context.getSystemService(LocationManager::class.java)
    private var closed=false
    private var latestNanos=0L
    private val registered=mutableSetOf<String>()
    private val listener=object:LocationListener {
        override fun onLocationChanged(location:Location) {
            if(closed || !WeatherLocation.permitted(context) || location.elapsedRealtimeNanos<=latestNanos) return
            val region=runCatching { WeatherLocation.position(location.latitude,location.longitude,
                location.elapsedRealtimeNanos,SystemClock.elapsedRealtimeNanos(),
                if(location.hasAccuracy()) location.accuracy else Float.NaN,location.isFromMockProvider) }.getOrNull() ?: return
            latestNanos=location.elapsedRealtimeNanos
            changed(region)
        }
        override fun onProviderDisabled(provider:String) {
            if(!closed && registered.none { runCatching { manager?.isProviderEnabled(it)==true }.getOrDefault(false) }) unavailable()
        }
        override fun onProviderEnabled(provider:String)=Unit
        @Deprecated("Legacy API 28 callback") override fun onStatusChanged(provider:String?,status:Int,extras:Bundle?)=Unit
    }
    @android.annotation.SuppressLint("MissingPermission")
    fun start():Boolean {
        if(closed || manager==null || !WeatherLocation.permitted(context)) return false
        if(registered.isNotEmpty()) return true
        val fine=context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
        val candidates=providers ?: WeatherLocation.providers(fine,manager.allProviders)
        for(provider in candidates) if(provider in manager.allProviders) {
            runCatching {
                manager.requestLocationUpdates(provider,60_000L,0f,listener,Looper.getMainLooper())
                registered.add(provider)
            }
        }
        return registered.isNotEmpty()
    }
    override fun close() {
        if(closed) return
        closed=true
        runCatching { manager?.removeUpdates(listener) }
        registered.clear()
    }
}
