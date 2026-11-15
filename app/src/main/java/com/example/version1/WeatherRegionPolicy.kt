package com.example.version1

import kotlin.math.*

/** Weather regions, not a persisted trip or a geographic identity. */
internal object WeatherRegionPolicy {
    const val REFRESH_MS=900_000L
    const val MIN_RETRY_MS=60_000L
    const val REGION_KM=5.0
    fun positionFresh(elapsed:Long,fixElapsed:Long)=fixElapsed>0L && elapsed-fixElapsed in 0..300_000L
    fun distanceKm(a:WeatherPlace,b:WeatherPlace):Double {
        val latitude=Math.toRadians(b.latitude-a.latitude)
        val longitude=Math.toRadians(b.longitude-a.longitude)
        val h=sin(latitude/2).pow(2)+cos(Math.toRadians(a.latitude))*cos(Math.toRadians(b.latitude))*sin(longitude/2).pow(2)
        return 6371.0*2*asin(sqrt(h.coerceIn(0.0,1.0)))
    }
    fun moved(a:WeatherPlace?,b:WeatherPlace)=a==null || !a.fromDeviceLocation || distanceKm(a,b)>=REGION_KM
    fun due(now:Long,lastAttempt:Long,lastSuccess:Long,missing:Boolean):Boolean =
        (lastAttempt==0L || now-lastAttempt>=MIN_RETRY_MS || now<lastAttempt) &&
            (missing || lastSuccess==0L || now-lastSuccess>=REFRESH_MS || now<lastSuccess)
}
