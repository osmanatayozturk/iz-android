package org.iz.navigation.gpx

import java.util.UUID
import org.iz.navigation.weather.WeatherCoordinate

/** User-imported geometry, never measured diary points or provider navigation instructions. */
data class ImportedTrack(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val segments: List<TrackSegment>,
    val createdAt: Long = System.currentTimeMillis(),
)

data class TrackSegment(
    val name: String,
    val points: List<WeatherCoordinate>,
    val trackName: String = "",
    val trackIndex: Int = 0,
)

