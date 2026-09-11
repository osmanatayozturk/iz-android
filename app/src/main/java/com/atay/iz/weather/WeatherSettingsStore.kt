package com.atay.iz.weather

import android.content.Context
import com.atay.iz.data.Transport
import java.net.URI
import org.json.JSONObject

data class RideWeatherSettings(
    val thresholds: WeatherThresholds = WeatherThresholds(),
    val voiceEnabled: Boolean = false,
    val routeEndpoint: String = DEFAULT_ROUTE_ENDPOINT,
    val weatherEndpoint: String = DEFAULT_WEATHER_ENDPOINT,
    val alertsEnabled: Boolean = true,
    val travelSpeedKmh: Double? = null,
)

fun defaultWeatherSettings(transport: Transport): RideWeatherSettings =
    RideWeatherSettings(thresholds = defaultWeatherThresholds(transport), travelSpeedKmh = routeTravelSpeedKmh(transport, null))

fun defaultWeatherThresholds(transport: Transport): WeatherThresholds = when (transport) {
    Transport.CAR, Transport.PASSENGER -> WeatherThresholds(70.0, 2.0, 50.0, 70.0, 0.0, 38.0)
    Transport.MOTORCYCLE -> WeatherThresholds(50.0, 0.2, 30.0, 50.0, 5.0, 35.0)
    Transport.BICYCLE -> WeatherThresholds(40.0, 0.2, 20.0, 35.0, 5.0, 32.0)
    Transport.WALK -> WeatherThresholds(50.0, 0.5, 30.0, 45.0, 0.0, 32.0)
    Transport.RUN -> WeatherThresholds(40.0, 0.2, 25.0, 40.0, 5.0, 28.0)
    Transport.UNKNOWN -> throw IllegalArgumentException("Bir yolculuk türü seç.")
}

class WeatherSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("ride_weather_settings_v1", Context.MODE_PRIVATE)
    fun read(transport: Transport = Transport.MOTORCYCLE): RideWeatherSettings {
        defaultWeatherThresholds(transport)
        migrateThresholdsOnce()
        return readStored(transport)
    }

    private fun readStored(transport: Transport): RideWeatherSettings {
        val defaults = defaultWeatherSettings(transport)
        val key = "settings_${transport.name}"
        // Existing installations used one motorcycle-only setting. Never copy it to other modes.
        val raw = if (transport == Transport.MOTORCYCLE && !preferences.contains(key)) {
            preferences.getString("settings", null)
        } else {
            preferences.getString(key, null)
        }
        val value = WeatherSettingsCodec.decode(raw, defaults)
        return runCatching { routeTravelSpeedKmh(transport, value.travelSpeedKmh); value }.getOrDefault(defaults)
    }

    fun save(value: RideWeatherSettings, transport: Transport = Transport.MOTORCYCLE) {
        routeTravelSpeedKmh(transport, value.travelSpeedKmh)
        val encoded = WeatherSettingsCodec.encode(value)
        migrateThresholdsOnce()
        check(preferences.edit().putString("settings_${transport.name}", encoded).commit()) { "Hava ayarları kaydedilemedi." }
    }

    /** The user requested resetting all six threshold profiles once in 0.5.0. */
    private fun migrateThresholdsOnce() = synchronized(migrationLock) {
        if (preferences.getInt("thresholdDefaultsRevision", 0) >= 1) return@synchronized
        val editor = preferences.edit()
        Transport.entries.filter { it != Transport.UNKNOWN }.forEach { mode ->
            val updated = readStored(mode).copy(thresholds = defaultWeatherThresholds(mode))
            editor.putString("settings_${mode.name}", WeatherSettingsCodec.encode(updated))
        }
        check(editor.putInt("thresholdDefaultsRevision", 1).commit()) { "Hava varsayılanları güncellenemedi." }
    }

    companion object { private val migrationLock = Any() }

}

internal object WeatherSettingsCodec {
    fun validEndpoint(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() &&
            uri.rawUserInfo == null && uri.rawFragment == null && uri.rawQuery == null
    }.getOrDefault(false)

    fun validate(value: RideWeatherSettings) {
        val t = value.thresholds
        require(t.precipitationProbabilityPercent.isFinite() && t.precipitationProbabilityPercent in 1.0..100.0) { "Yağış olasılığı eşiği 1–100 arasında olmalı." }
        require(t.precipitationMm.isFinite() && t.precipitationMm in 0.01..100.0) { "Yağış miktarı eşiği 0,01–100 mm arasında olmalı." }
        require(t.windKmh.isFinite() && t.windKmh in 1.0..250.0 && t.gustKmh.isFinite() && t.gustKmh in 1.0..350.0) { "Rüzgâr ve hamle eşiklerini kontrol et." }
        require(t.coldC.isFinite() && t.hotC.isFinite() && t.coldC in -50.0..40.0 && t.hotC in -20.0..60.0 && t.coldC < t.hotC) { "Soğuk eşiği sıcak eşiğinden küçük olmalı." }
        require(validEndpoint(value.routeEndpoint) && validEndpoint(value.weatherEndpoint)) { "Servis adresleri HTTPS olmalı; kimlik bilgisi veya sorgu içermemeli." }
        require(value.travelSpeedKmh == null || value.travelSpeedKmh.isFinite() && value.travelSpeedKmh in 0.5..60.0) { "Planlama hızını kontrol et." }
    }

    fun encode(value: RideWeatherSettings): String {
        validate(value)
        return JSONObject().put("version", 2).put("voice", value.voiceEnabled)
            .put("alertsEnabled", value.alertsEnabled).put("travelSpeedKmh", value.travelSpeedKmh ?: JSONObject.NULL)
            .put("routeEndpoint", value.routeEndpoint).put("weatherEndpoint", value.weatherEndpoint)
            .put("probability", value.thresholds.precipitationProbabilityPercent)
            .put("precipitation", value.thresholds.precipitationMm)
            .put("wind", value.thresholds.windKmh).put("gust", value.thresholds.gustKmh)
            .put("cold", value.thresholds.coldC).put("hot", value.thresholds.hotC).toString()
    }

    fun decode(raw: String?, defaults: RideWeatherSettings = RideWeatherSettings()): RideWeatherSettings = runCatching {
        require(raw != null && raw.length <= 8192)
        val json = JSONObject(raw)
        val version = json.getInt("version")
        require(version in 1..2)
        RideWeatherSettings(
            thresholds = WeatherThresholds(
                json.getDouble("probability"), json.getDouble("precipitation"),
                json.getDouble("wind"), json.getDouble("gust"), json.getDouble("cold"), json.getDouble("hot"),
            ),
            voiceEnabled = json.getBoolean("voice"),
            routeEndpoint = json.getString("routeEndpoint"), weatherEndpoint = json.getString("weatherEndpoint"),
            alertsEnabled = if (version == 1) true else json.getBoolean("alertsEnabled"),
            travelSpeedKmh = if (version == 1 || json.isNull("travelSpeedKmh")) null else json.getDouble("travelSpeedKmh"),
        ).also(::validate)
    }.getOrDefault(defaults)
}
