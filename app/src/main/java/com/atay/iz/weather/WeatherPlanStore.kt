package com.atay.iz.weather

import android.content.Context
import com.atay.iz.data.Transport
import org.json.JSONArray
import org.json.JSONObject

data class SavedWeatherPlan(
    val stops: List<RouteStop>,
    val departureAt: Long,
    val transport: Transport = Transport.MOTORCYCLE,
)

/** A draft, never a Journey or an instruction to restart background tracking. */
class WeatherPlanStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("ride_weather_plan_v1", Context.MODE_PRIVATE)
    fun read(): SavedWeatherPlan? = WeatherPlanCodec.decode(preferences.getString("plan", null))
    fun save(value: SavedWeatherPlan) {
        check(preferences.edit().putString("plan", WeatherPlanCodec.encode(value)).commit()) { "Sürüş planı kaydedilemedi." }
    }
}

internal object WeatherPlanCodec {
    fun encode(value: SavedWeatherPlan): String {
        require(value.transport != Transport.UNKNOWN) { "Yolculuk tarzını seç." }
        require(value.stops.size in 2..5 && value.departureAt >= 0) { "Başlangıç ve hedef seç." }
        val stops = JSONArray()
        value.stops.forEach { stop ->
            val point = stop.coordinate
            require(point.latitude.isFinite() && point.longitude.isFinite() &&
                point.latitude in -90.0..90.0 && point.longitude in -180.0..180.0 &&
                stop.label.length <= 200) { "Rota noktası geçerli değil." }
            stops.put(JSONObject().put("name", stop.label).put("lat", point.latitude).put("lon", point.longitude))
        }
        return JSONObject().put("version", 2).put("transport", value.transport.name)
            .put("departureAt", value.departureAt).put("stops", stops).toString()
    }

    fun decode(raw: String?): SavedWeatherPlan? = runCatching {
        require(raw != null && raw.length <= 16_384)
        val json = JSONObject(raw)
        val version = json.getInt("version")
        require(version in 1..2)
        val transport = if (version == 1) Transport.MOTORCYCLE else Transport.valueOf(json.getString("transport"))
        val list = json.getJSONArray("stops")
        require(list.length() in 2..5)
        SavedWeatherPlan((0 until list.length()).map {
            val point = list.getJSONObject(it)
            RouteStop(point.getString("name"), WeatherCoordinate(point.getDouble("lat"), point.getDouble("lon")))
        }, json.getLong("departureAt"), transport).also { encode(it) }
    }.getOrNull()
}
