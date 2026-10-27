package org.iz.navigation.navigation

import org.iz.navigation.weather.WeatherCoordinate

enum class DriveCameraMode { ADAPTIVE, SIMPLE_2D }

data class DriveCameraViewport(
    val widthPx: Int, val heightPx: Int,
    val leftPx: Int = 0, val topPx: Int = 0, val rightPx: Int = 0, val bottomPx: Int = 0,
    val known: Boolean = true,
)

data class DriveCameraInput(
    val nowMillis: Long,
    val coordinate: WeatherCoordinate?,
    val speedKmh: Double?,
    val bearingDegrees: Double?,
    val fresh: Boolean,
    val enabled: Boolean,
    val maneuverMeters: Double? = null,
    val maneuverSeconds: Double? = null,
    val mode: DriveCameraMode = DriveCameraMode.ADAPTIVE,
)

data class DriveCameraTarget(
    val coordinate: WeatherCoordinate,
    val zoom: Double,
    val bearing: Double,
    val pitch: Double,
    val padding: List<Double>,
)
