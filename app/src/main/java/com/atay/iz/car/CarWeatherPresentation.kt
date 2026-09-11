package com.atay.iz.car

import com.atay.iz.weather.RideWeatherLiveState
import com.atay.iz.weather.RideWeatherStatus
import com.atay.iz.weather.RideWeatherTiming
import java.util.Locale

internal data class CarWeatherPresentation(val title: String, val summary: String, val detail: String)

/** One concise forecast description shared by the car home, menu and weather page. */
internal fun carWeatherPresentation(weather: RideWeatherLiveState, now: Long = System.currentTimeMillis()): CarWeatherPresentation {
    val currentLocation = weather.route == null
    val title = if (currentLocation) "Mevcut konumun havası" else "Rota hava durumu"
    if (weather.status == RideWeatherStatus.OFF) {
        return CarWeatherPresentation("Hava durumu", "Hava takibi kapalı",
            "Hava takibi kapalı. Telefonda bir yolculuk başlatın veya hava takibini etkinleştirin.")
    }
    val assessment = weather.assessment
    val hasReadings = assessment != null && listOf(
        assessment.minTemperatureC, assessment.maxTemperatureC,
        assessment.maxPrecipitationProbabilityPercent, assessment.maxWindKmh, assessment.maxGustKmh,
    ).any { it != null }
    val fresh = RideWeatherTiming.forecastFresh(assessment?.fetchedAt, now)
    val status = when {
        weather.status == RideWeatherStatus.LOADING -> "Tahmin alınıyor"
        weather.gpsStale -> "GPS güncel değil"
        weather.refreshFailed || weather.status == RideWeatherStatus.ERROR -> if (hasReadings) "Tahmin yenilenemedi" else "Tahmin alınamadı"
        assessment == null || assessment.fetchedAt == null -> "Tahmin verisi yok"
        !fresh -> "Tahmin güncel değil"
        !assessment.complete -> "Tahmin kısmen eksik"
        currentLocation -> "Mevcut konum"
        else -> "Rota tahmini"
    }
    fun number(value: Double): String = String.format(Locale.forLanguageTag("tr"), "%.1f", value).removeSuffix(",0")
    val low = assessment?.minTemperatureC
    val high = assessment?.maxTemperatureC
    val temperature = when {
        low != null && high != null && low != high -> "${number(low)}–${number(high)} °C"
        low != null || high != null -> "${number(low ?: high!!)} °C"
        else -> "Sıcaklık —"
    }
    val rain = assessment?.maxPrecipitationProbabilityPercent?.let { "Yağış %${number(it)}" } ?: "Yağış —"
    val wind = assessment?.maxWindKmh?.let { "Rüzgâr ${number(it)} km/sa" } ?: "Rüzgâr —"
    val gust = assessment?.maxGustKmh?.let { "Hamle ${number(it)} km/sa" } ?: "Hamle —"
    val readings = listOf(temperature, rain, wind).joinToString(" · ")
    val summary = if (hasReadings) "$status · $readings" else status
    val notices = buildList {
        if (weather.gpsStale) add("GPS güncel değil; konuma bağlı uyarılar duraklatıldı.")
        if (assessment?.fetchedAt != null && !fresh) add("Tahmin güncel değil; son veriler gösteriliyor.")
        if (assessment != null && !assessment.complete) add("Tahmin kısmen eksik.")
        weather.message?.takeIf { it.isNotBlank() }?.let { add(it.replace(Regex("\\s+"), " ").take(180)) }
    }
    val detail = buildList {
        add(status)
        if (hasReadings) { add(readings); add(gust) }
        add(if (currentLocation) "Yalnızca mevcut konum; ilerideki yolun tahmini değildir."
            else "Rota boyunca sıcaklık aralığı ve en yüksek yağış/rüzgâr değerleri.")
        addAll(notices.distinct())
    }.joinToString("\n")
    return CarWeatherPresentation(title, summary, detail)
}

