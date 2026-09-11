package com.atay.iz.weather

import android.content.Context
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

class OpenMeteoWeatherProvider(
    private val endpoint: String,
    private val storageDirectory: File,
    client: OkHttpClient,
    private val clock: () -> Long = System::currentTimeMillis,
) : WeatherProvider {
    private val client = singleAttemptHttpClient(client)

    constructor(context: Context, endpoint: String = DEFAULT_WEATHER_ENDPOINT) : this(
        endpoint = endpoint,
        storageDirectory = weatherStorage(context, "route-weather-forecast"),
        client = defaultWeatherHttpClient(),
    )

    override suspend fun hourly(
        coordinates: List<WeatherCoordinate>,
        from: Long,
        until: Long,
    ): List<LocationForecast> = withContext(Dispatchers.IO) {
        require(coordinates.size <= 48) { "Tek istekte en fazla 48 konum sorgulanabilir." }
        require(from >= 0L && until >= from && until - from <= MAX_RANGE_MILLIS) { "Tahmin zaman aralığı geçersiz." }
        if (coordinates.isEmpty()) return@withContext emptyList()
        val now = clock()
        val results = arrayOfNulls<LocationForecast>(coordinates.size)
        val missingIndexes = mutableListOf<Int>()
        coordinates.forEachIndexed { index, coordinate ->
            results[index] = readCache(cacheFile(coordinate, from, until), now)
            if (results[index] == null) missingIndexes += index
        }
        if (missingIndexes.isEmpty()) return@withContext results.map { requireNotNull(it) }

        try {
            WeatherHttpGate.forecast(storageDirectory, clock) {
                val requested = missingIndexes.map(coordinates::get)
                val url = endpoint.toHttpUrl().newBuilder()
                    .addQueryParameter("latitude", requested.joinToString(",") { formatCoordinate(it.latitude) })
                    .addQueryParameter("longitude", requested.joinToString(",") { formatCoordinate(it.longitude) })
                    .addQueryParameter("hourly", HOURLY_FIELDS)
                    .addQueryParameter("timeformat", "unixtime")
                    .addQueryParameter("timezone", "GMT")
                    .addQueryParameter("wind_speed_unit", "kmh")
                    .addQueryParameter("start_date", utcDate(from))
                    .addQueryParameter("end_date", utcDate(until))
                    .build()
                val request = weatherRequest(url.toString()).get().build()
                val response = executeBounded(client, request, MAX_FORECAST_RESPONSE_BYTES)
                if (response.code == 429 || response.code == 503) {
                    WeatherHttpGate.setCooldown(storageDirectory, "forecast-http-state.json", clock(), response.retryAfter)
                    throw WeatherServiceException("Hava durumu servisi beklememizi istiyor. Biraz sonra yeniden deneyin.")
                }
                if (!response.successful) throw WeatherServiceException("Hava durumu servisi şu an kullanılamıyor (${response.code}).")
                val entries = responseEntries(response.body, requested.size)
                val fetchedAt = clock()
                missingIndexes.forEachIndexed { responseIndex, originalIndex ->
                    val forecast = entries.getOrNull(responseIndex)?.let { parseForecast(coordinates[originalIndex], it, fetchedAt) }
                        ?: LocationForecast(coordinates[originalIndex], emptyList(), fetchedAt)
                    results[originalIndex] = forecast
                    storeCache(cacheFile(coordinates[originalIndex], from, until), forecast)
                }
                trimCache()
            }
            return@withContext results.map { requireNotNull(it) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: WeatherServiceException) {
            throw error
        } catch (error: BoundedResponseException) {
            throw WeatherServiceException("Tahmin yanıtı çok büyük.", error)
        } catch (error: IOException) {
            coroutineContext.ensureActive()
            throw WeatherServiceException("Hava durumu servisine ulaşılamadı.", error)
        } catch (error: JSONException) {
            throw WeatherServiceException("Hava durumu servisi geçersiz yanıt verdi.", error)
        } catch (error: IllegalArgumentException) {
            throw WeatherServiceException("Hava durumu servisi geçersiz tahmin döndürdü.", error)
        }
    }

    private fun responseEntries(body: String, expected: Int): List<JSONObject?> {
        val trimmed = body.trimStart()
        if (trimmed.startsWith("[")) {
            val array = JSONArray(body)
            return (0 until expected).map { index -> if (index < array.length() && !array.isNull(index)) array.optJSONObject(index) else null }
        }
        return listOf(JSONObject(body)) + List((expected - 1).coerceAtLeast(0)) { null }
    }

    private fun parseForecast(coordinate: WeatherCoordinate, value: JSONObject, fetchedAt: Long): LocationForecast {
        val hourly = value.optJSONObject("hourly") ?: return LocationForecast(coordinate, emptyList(), fetchedAt)
        val times = hourly.optJSONArray("time") ?: return LocationForecast(coordinate, emptyList(), fetchedAt)
        val temperatures = hourly.optJSONArray("temperature_2m")
        val probabilities = hourly.optJSONArray("precipitation_probability")
        val precipitation = hourly.optJSONArray("precipitation")
        val wind = hourly.optJSONArray("wind_speed_10m")
        val direction = hourly.optJSONArray("wind_direction_10m")
        val gust = hourly.optJSONArray("wind_gusts_10m")
        val hours = (0 until times.length()).mapNotNull { index ->
            if (times.isNull(index)) return@mapNotNull null
            val seconds = times.optLong(index, Long.MIN_VALUE)
            if (seconds == Long.MIN_VALUE || seconds > Long.MAX_VALUE / 1_000L) return@mapNotNull null
            runCatching {
                WeatherHour(
                    seconds * 1_000L,
                    WeatherReading(
                        temperatureC = temperatures.numberOrNull(index),
                        precipitationProbabilityPercent = probabilities.numberOrNull(index),
                        precipitationMm = precipitation.numberOrNull(index),
                        windKmh = wind.numberOrNull(index),
                        gustKmh = gust.numberOrNull(index),
                        windDirectionDegrees = direction.numberOrNull(index),
                    ),
                )
            }.getOrNull()
        }.distinctBy { it.time }.sortedBy { it.time }
        return LocationForecast(coordinate, hours, fetchedAt)
    }

    private fun cacheFile(coordinate: WeatherCoordinate, from: Long, until: Long): File {
        val key = "$endpoint:${formatCoordinate(coordinate.latitude)},${formatCoordinate(coordinate.longitude)}:${utcDate(from)}:${utcDate(until)}"
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(storageDirectory, "forecast-$digest.json")
    }

    private fun readCache(file: File, now: Long): LocationForecast? = runCatching {
        if (!file.isFile || file.length() !in 1..MAX_CACHE_FILE_BYTES) return null
        deserializeForecast(JSONObject(file.readText())).takeIf { now - it.fetchedAt in 0..CACHE_MAX_AGE_MILLIS }
    }.getOrNull()

    private fun storeCache(file: File, forecast: LocationForecast) {
        runCatching {
            storageDirectory.mkdirs()
            val temporary = File(storageDirectory, "${file.name}.${UUID.randomUUID()}.tmp")
            try {
                temporary.writeText(serializeForecast(forecast).toString())
                if (temporary.length() > MAX_CACHE_FILE_BYTES) return
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } finally {
                temporary.delete()
            }
        }
    }

    private fun trimCache() {
        var bytes = 0L
        storageDirectory.listFiles { file -> file.name.startsWith("forecast-") && file.name.endsWith(".json") }
            ?.sortedByDescending(File::lastModified)
            ?.forEachIndexed { index, file ->
                bytes += file.length()
                if (index >= MAX_CACHE_FILES || bytes > MAX_CACHE_BYTES) file.delete()
            }
    }

    companion object {
        private const val HOURLY_FIELDS = "temperature_2m,precipitation_probability,precipitation,wind_speed_10m,wind_direction_10m,wind_gusts_10m"
        private const val MAX_FORECAST_RESPONSE_BYTES = 2_000_000
        private const val MAX_CACHE_FILE_BYTES = 2_000_000L
        private const val MAX_CACHE_FILES = 128
        private const val MAX_CACHE_BYTES = 16_000_000L
        private const val CACHE_MAX_AGE_MILLIS = 15 * 60 * 1_000L
        private const val MAX_RANGE_MILLIS = 9L * 24L * 60L * 60L * 1_000L
        private val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

        private fun utcDate(epochMillis: Long): String = DATE_FORMATTER.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneOffset.UTC))
        private fun formatCoordinate(value: Double): String = String.format(Locale.US, "%.5f", value)
    }
}

private fun JSONArray?.numberOrNull(index: Int): Double? {
    if (this == null || index !in 0 until length() || isNull(index)) return null
    val value = optDouble(index, Double.NaN)
    return value.takeIf(Double::isFinite)
}

private fun serializeForecast(forecast: LocationForecast): JSONObject = JSONObject()
    .put("latitude", forecast.coordinate.latitude)
    .put("longitude", forecast.coordinate.longitude)
    .put("fetchedAt", forecast.fetchedAt)
    .put("hours", JSONArray().apply {
        forecast.hours.forEach { hour ->
            put(JSONObject()
                .put("time", hour.time)
                .putNullable("temperatureC", hour.reading.temperatureC)
                .putNullable("precipitationProbabilityPercent", hour.reading.precipitationProbabilityPercent)
                .putNullable("precipitationMm", hour.reading.precipitationMm)
                .putNullable("windKmh", hour.reading.windKmh)
                .putNullable("gustKmh", hour.reading.gustKmh)
                .putNullable("windDirectionDegrees", hour.reading.windDirectionDegrees))
        }
    })

private fun deserializeForecast(value: JSONObject): LocationForecast {
    val coordinate = WeatherCoordinate(value.getDouble("latitude"), value.getDouble("longitude"))
    val hoursJson = value.getJSONArray("hours")
    val hours = (0 until hoursJson.length()).map { index ->
        val hour = hoursJson.getJSONObject(index)
        WeatherHour(hour.getLong("time"), WeatherReading(
            temperatureC = hour.nullableDouble("temperatureC"),
            precipitationProbabilityPercent = hour.nullableDouble("precipitationProbabilityPercent"),
            precipitationMm = hour.nullableDouble("precipitationMm"),
            windKmh = hour.nullableDouble("windKmh"),
            gustKmh = hour.nullableDouble("gustKmh"),
            windDirectionDegrees = hour.nullableDouble("windDirectionDegrees"),
        ))
    }
    return LocationForecast(coordinate, hours, value.getLong("fetchedAt"))
}

private fun JSONObject.putNullable(name: String, value: Double?): JSONObject = put(name, value ?: JSONObject.NULL)
private fun JSONObject.nullableDouble(name: String): Double? = if (isNull(name)) null else getDouble(name)
