package com.example.version1

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder

data class WeatherPlace(val name:String,val latitude:Double,val longitude:Double,val fromDeviceLocation:Boolean=false)
data class ComfortWeather(val place:WeatherPlace,val temperature:Float,val humidity:Float,
                          val radiation:Float?,val code:Int,val observedAt:Long,val fetchedAt:Long) {
    fun fresh(now:Long) = now-fetchedAt in 0..1_800_000 && now-observedAt in 0..3_600_000
}
/** Only selected/rounded region coordinates are sent. No health data or location history. */
object ComfortWeatherClient {
    private val http=PublicHttpsJsonClient()
    private suspend fun get(url:String)=JSONObject(http.get(URL(url),65_536,16))
    suspend fun search(name:String): List<WeatherPlace> = withContext(Dispatchers.IO) {
        require(name.trim().length in 2..60)
        val root=get("https://geocoding-api.open-meteo.com/v1/search?name=${URLEncoder.encode(name.trim(),"UTF-8")}&count=5&language=ko&format=json")
        val results=root.optJSONArray("results") ?: return@withContext emptyList()
        (0 until minOf(5,results.length())).map { i -> results.getJSONObject(i).let {
            WeatherPlace("${it.getString("name")} · ${it.optString("admin1")} ${it.optString("country")}",it.getDouble("latitude"),it.getDouble("longitude"))
        } }
    }
    suspend fun fetch(place:WeatherPlace): ComfortWeather = withContext(Dispatchers.IO) {
        require(place.latitude.isFinite() && place.latitude in -90.0..90.0 && place.longitude.isFinite() && place.longitude in -180.0..180.0)
        val j=get("https://api.open-meteo.com/v1/forecast?latitude=${place.latitude}&longitude=${place.longitude}&current=temperature_2m,relative_humidity_2m,shortwave_radiation,weather_code&timeformat=unixtime&timezone=GMT").getJSONObject("current")
        val t=j.getDouble("temperature_2m").toFloat();val h=j.getDouble("relative_humidity_2m").toFloat()
        require(t.isFinite() && t in -60f..65f && h.isFinite() && h in 0f..100f)
        ComfortWeather(place,t,h,j.optDouble("shortwave_radiation").toFloat().takeIf { it.isFinite() && it in 0f..1500f },j.getInt("weather_code"),j.getLong("time")*1000,System.currentTimeMillis())
    }
}
