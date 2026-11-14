package org.iz.navigation.integration

import org.iz.navigation.data.SelectedOsmPlace
import org.iz.navigation.weather.WeatherCoordinate
import org.iz.navigation.weather.WeatherEngine

/** Transient discovery/display data only; never saved or used as editing geometry. */
internal data class MapPlaceCandidate(val place: SelectedOsmPlace, val typeLabel: String,
    val distanceMeters: Double = 0.0, val outline: MapPlaceOutline? = null)
internal data class RankedMapCandidates(val candidates: List<MapPlaceCandidate>, val capped: Boolean)

internal fun rankMapCandidates(found: List<MapPlaceCandidate>, latitude: Double, longitude: Double): RankedMapCandidates {
    require(validCoordinate(latitude, longitude))
    val valid = found.filter { it.place.osmRef?.id?.let { id -> id > 0 } == true &&
        validCoordinate(it.place.latitude, it.place.longitude) }.distinctBy { it.place.osmRef }
        .map { it.copy(distanceMeters = WeatherEngine.distanceMeters(WeatherCoordinate(latitude, longitude),
            WeatherCoordinate(it.place.latitude, it.place.longitude))) }
        .sortedBy { it.distanceMeters } // Stable sort deliberately retains service order for ties.
    return RankedMapCandidates(valid.take(50), valid.size > 50)
}

internal fun mapCandidateHits(candidates: List<MapPlaceCandidate>, hits: List<SelectedOsmPlace>): List<MapPlaceCandidate> =
    candidates.filter { candidate -> hits.any { it.osmRef == candidate.place.osmRef &&
        it.latitude == candidate.place.latitude && it.longitude == candidate.place.longitude } }
