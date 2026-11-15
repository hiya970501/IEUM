package com.example.version1

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.round

/** One foreground fix, no location history or background service. Only rounded coordinates leave this class. */
internal data class WeatherPosition(val place:WeatherPlace,val elapsedRealtimeMs:Long)
internal object WeatherLocation {
    fun permitted(context: Context) = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    fun place(latitude: Double, longitude: Double, ageMs: Long, accuracy: Float, mock: Boolean): WeatherPlace {
        require(latitude.isFinite() && latitude in -90.0..90.0 && longitude.isFinite() && longitude in -180.0..180.0)
        require(ageMs in 0..300_000 && accuracy.isFinite() && accuracy in 0f..20_000f) { "최근 위치를 확인할 수 없어요." }
        return WeatherPlace(if(mock) "테스트 위치 주변" else "현재 위치 주변",round(latitude*100)/100,round(longitude*100)/100,true)
    }
    fun providers(precise:Boolean,available:Collection<String>)=
        listOfNotNull("fused",LocationManager.NETWORK_PROVIDER,if(precise) LocationManager.GPS_PROVIDER else null).filter { it in available }
    fun position(latitude:Double,longitude:Double,fixNanos:Long,nowNanos:Long,accuracy:Float,mock:Boolean):WeatherPosition {
        require(fixNanos>0 && nowNanos>=fixNanos && nowNanos-fixNanos<=300_000_000_000L) { "최근 위치를 확인할 수 없어요." }
        return WeatherPosition(place(latitude,longitude,(nowNanos-fixNanos)/1_000_000,accuracy,mock),fixNanos/1_000_000)
    }
    @android.annotation.SuppressLint("MissingPermission")
    suspend fun current(context: Context): WeatherPosition = withTimeout(15_000) {
        check(permitted(context)) { "위치 권한이 필요해요." }
        val manager = context.getSystemService(LocationManager::class.java) ?: error("위치 기능을 지원하지 않아요.")
        val precise = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val provider = providers(precise,manager.allProviders)
            .firstOrNull { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
            ?: error("사용할 수 있는 위치 제공자가 없어요.")
        val fix = suspendCancellableCoroutine<Location> { continuation ->
            if(Build.VERSION.SDK_INT>=30) {
                val cancellation = CancellationSignal()
                continuation.invokeOnCancellation { cancellation.cancel() }
                try { manager.getCurrentLocation(provider,cancellation,context.mainExecutor) { location ->
                    if(continuation.isActive) {
                        if(location!=null) continuation.resume(location)
                        else continuation.resumeWithException(IllegalStateException("현재 위치를 찾지 못했어요."))
                    }
                } } catch(e:Exception) { if(continuation.isActive) continuation.resumeWithException(e) }
            } else {
                val listener = object: LocationListener {
                    override fun onLocationChanged(location: Location) {
                        manager.removeUpdates(this)
                        if(continuation.isActive) continuation.resume(location)
                    }
                    override fun onProviderDisabled(provider: String) {
                        manager.removeUpdates(this)
                        if(continuation.isActive) continuation.resumeWithException(IllegalStateException("위치 제공자가 꺼졌어요."))
                    }
                    override fun onProviderEnabled(provider: String) = Unit
                    @Deprecated("Legacy API 28 callback")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
                }
                continuation.invokeOnCancellation { manager.removeUpdates(listener) }
                try { manager.requestSingleUpdate(provider,listener,Looper.getMainLooper()) }
                catch(e:Exception) { manager.removeUpdates(listener); if(continuation.isActive) continuation.resumeWithException(e) }
            }
        }
        position(fix.latitude,fix.longitude,fix.elapsedRealtimeNanos,SystemClock.elapsedRealtimeNanos(),
            if(fix.hasAccuracy()) fix.accuracy else Float.NaN,fix.isFromMockProvider)
    }
}
