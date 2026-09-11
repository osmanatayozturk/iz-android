package com.atay.iz.weather

import com.atay.iz.data.Transport

data class WeatherCoordinate(val latitude: Double, val longitude: Double) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0) { "Geçersiz enlem." }
        require(longitude.isFinite() && longitude in -180.0..180.0) { "Geçersiz boylam." }
    }
}

data class RouteStop(val label: String, val coordinate: WeatherCoordinate) {
    init { require(label.isNotBlank() && label.length <= 300) { "Durak adı geçersiz." } }
}

data class RouteVertex(val coordinate: WeatherCoordinate, val elapsedSeconds: Double) {
    init { require(elapsedSeconds.isFinite() && elapsedSeconds >= 0.0) { "Rota zamanı geçersiz." } }
}

enum class RouteProvider { VALHALLA, TOMTOM }

data class RouteTrafficInfo(
    val fetchedAt: Long,
    val delaySeconds: Double,
    val noTrafficDurationSeconds: Double?,
    val experimental: Boolean = false,
) {
    init {
        require(delaySeconds.isFinite() && delaySeconds >= 0.0)
        require(noTrafficDurationSeconds == null || noTrafficDurationSeconds.isFinite() && noTrafficDurationSeconds >= 0.0)
    }
}

data class PlannedRoute(
    val id: String,
    val stops: List<RouteStop>,
    val vertices: List<RouteVertex>,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val createdAt: Long,
    val stopElapsedSeconds: List<Double> = emptyList(),
    val transport: Transport = Transport.MOTORCYCLE,
    val travelSpeedKmh: Double? = null,
    val maneuvers: List<RouteManeuver> = emptyList(),
    val provider: RouteProvider = RouteProvider.VALHALLA,
    val traffic: RouteTrafficInfo? = null,
    val trafficUnavailableReason: String? = null,
) {
    init {
        routeTravelSpeedKmh(transport, travelSpeedKmh)
        require(id.isNotBlank()) { "Rota kimliği boş olamaz." }
        require(stops.size in 2..6) { "Rota 2 ile 6 durak içermelidir." }
        require(vertices.isNotEmpty()) { "Rota geometrisi boş olamaz." }
        require(distanceMeters.isFinite() && distanceMeters >= 0.0) { "Rota mesafesi geçersiz." }
        require(durationSeconds.isFinite() && durationSeconds >= 0.0) { "Rota süresi geçersiz." }
        require(vertices.zipWithNext().all { (a, b) -> a.elapsedSeconds <= b.elapsedSeconds }) { "Rota zamanları sıralı olmalıdır." }
        require(vertices.all { it.elapsedSeconds <= durationSeconds + 0.001 }) { "Rota zamanı toplam süreyi aşamaz." }
        require(stopElapsedSeconds.isEmpty() || stopElapsedSeconds.size == stops.size) { "Durak zaman çizelgesi eksik." }
        require(stopElapsedSeconds.all { it.isFinite() && it in 0.0..durationSeconds }) { "Durak zamanı geçersiz." }
        require(stopElapsedSeconds.zipWithNext().all { (a, b) -> a <= b }) { "Durak zamanları sıralı olmalıdır." }
    }
}

data class RouteManeuver(
    val type: Int,
    val instruction: String,
    val verbalInstruction: String,
    val streetNames: List<String>,
    val beginShapeIndex: Int,
    val endShapeIndex: Int,
    val beginElapsedSeconds: Double,
    val endElapsedSeconds: Double,
    val roundaboutExit: Int? = null,
)

data class WeatherThresholds(
    val precipitationProbabilityPercent: Double = 50.0,
    val precipitationMm: Double = 0.2,
    val windKmh: Double = 30.0,
    val gustKmh: Double = 50.0,
    val coldC: Double = 5.0,
    val hotC: Double = 35.0,
) {
    init {
        require(precipitationProbabilityPercent.isFinite() && precipitationProbabilityPercent in 0.0..100.0) { "Yağış olasılığı eşiği geçersiz." }
        require(precipitationMm.isFinite() && precipitationMm in 0.0..1_000.0) { "Yağış eşiği geçersiz." }
        require(windKmh.isFinite() && windKmh in 0.0..500.0) { "Rüzgâr eşiği geçersiz." }
        require(gustKmh.isFinite() && gustKmh in 0.0..500.0) { "Rüzgâr hamlesi eşiği geçersiz." }
        require(coldC.isFinite() && coldC in -100.0..100.0) { "Soğuk eşiği geçersiz." }
        require(hotC.isFinite() && hotC in -100.0..100.0) { "Sıcak eşiği geçersiz." }
        require(coldC <= hotC) { "Soğuk eşiği sıcak eşiğini aşamaz." }
    }
}

enum class WeatherHazard { RAIN, WIND, COLD, HEAT }

data class WeatherReading(
    val temperatureC: Double?,
    val precipitationProbabilityPercent: Double?,
    val precipitationMm: Double?,
    val windKmh: Double?,
    val gustKmh: Double?,
    val windDirectionDegrees: Double?,
) {
    init {
        require(temperatureC == null || temperatureC.isFinite() && temperatureC in -100.0..100.0) { "Sıcaklık geçersiz." }
        require(precipitationProbabilityPercent == null || precipitationProbabilityPercent.isFinite() && precipitationProbabilityPercent in 0.0..100.0) { "Yağış olasılığı geçersiz." }
        require(precipitationMm == null || precipitationMm.isFinite() && precipitationMm in 0.0..1_000.0) { "Yağış miktarı geçersiz." }
        require(windKmh == null || windKmh.isFinite() && windKmh in 0.0..500.0) { "Rüzgâr hızı geçersiz." }
        require(gustKmh == null || gustKmh.isFinite() && gustKmh in 0.0..500.0) { "Rüzgâr hamlesi geçersiz." }
        require(windDirectionDegrees == null || windDirectionDegrees.isFinite() && windDirectionDegrees in 0.0..360.0) { "Rüzgâr yönü geçersiz." }
    }
}

data class WeatherHour(val time: Long, val reading: WeatherReading)

data class LocationForecast(val coordinate: WeatherCoordinate, val hours: List<WeatherHour>, val fetchedAt: Long) {
    init { require(hours.zipWithNext().all { (a, b) -> a.time < b.time }) { "Tahmin saatleri sıralı ve benzersiz olmalıdır." } }
}

data class RouteWeatherSample(
    val coordinate: WeatherCoordinate,
    val elapsedSeconds: Double,
    val arrivalAt: Long,
    val reading: WeatherReading,
    val hazards: Set<WeatherHazard>,
    val complete: Boolean,
)

data class WeatherAssessment(
    val departureAt: Long,
    val samples: List<RouteWeatherSample>,
    val exceededSeconds: Double,
    val complete: Boolean,
    val minTemperatureC: Double?,
    val maxTemperatureC: Double?,
    val maxPrecipitationProbabilityPercent: Double?,
    val maxWindKmh: Double?,
    val maxGustKmh: Double?,
    val fetchedAt: Long?,
)

interface RoutePlanner {
    suspend fun plan(
        stops: List<RouteStop>,
        departureAt: Long,
        transport: Transport = Transport.MOTORCYCLE,
        travelSpeedKmh: Double? = null,
    ): PlannedRoute
}

/** Valhalla uses pedestrian speed for both walking and running estimates. */
internal fun routeTravelSpeedKmh(transport: Transport, requested: Double?): Double? {
    val default = when (transport) {
        Transport.BICYCLE -> 18.0
        Transport.WALK -> 5.1
        Transport.RUN -> 10.0
        Transport.CAR, Transport.MOTORCYCLE, Transport.PASSENGER -> null
        Transport.UNKNOWN -> throw IllegalArgumentException("Rota için bir yolculuk türü seç.")
    }
    if (default == null) {
        require(requested == null) { "Araçla yolculuk süresini rota servisi hesaplar." }
        return null
    }
    val speed = requested ?: default
    val range = if (transport == Transport.BICYCLE) 5.0..60.0 else 0.5..25.0
    require(speed.isFinite() && speed in range) { "Rota için seçilen hız geçerli değil." }
    return speed
}

interface WeatherProvider {
    suspend fun hourly(coordinates: List<WeatherCoordinate>, from: Long, until: Long): List<LocationForecast>
}
