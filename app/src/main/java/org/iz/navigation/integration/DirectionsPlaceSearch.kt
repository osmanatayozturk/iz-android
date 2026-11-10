package org.iz.navigation.integration

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.iz.navigation.data.SelectedOsmPlace
import org.iz.navigation.weather.WeatherCoordinate
import org.iz.navigation.weather.WeatherEngine
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import kotlin.coroutines.coroutineContext

/** Shared production seam for the explicit phone submission, including the one-time origin lookup. */
internal class DirectionsPlaceSearch(
    private val nominatim: NominatimSearch,
    private val nearby: PartialNameOverpassSearch,
) {
    suspend fun search(query: String, location: suspend () -> WeatherCoordinate?): List<SelectedOsmPlace> {
        coroutineContext.ensureActive()
        val normalized = normalizeNominatimQuery(query)
        if (normalized.length < 2) return emptyList()
        val origin = location()
        val box = origin?.let(DirectionsViewbox::around)
        return withContext(Dispatchers.IO) {
            val places = nominatim.searchForDirections(normalized, box) {
                if (box == null) emptyList() else nearby.search(normalized, box, origin)
            }
            coroutineContext.ensureActive()
            rankDirectionsPlaces(places, origin)
        }
    }
}

internal class DirectionsViewbox private constructor(val value: String) {
    val overpassValue: String get() = value.split(',').let { (west, north, east, south) -> "$south,$west,$north,$east" }
    companion object {
        fun around(origin: WeatherCoordinate): DirectionsViewbox? {
            // Round the center before expanding it. Use integer hundredths for the boundary checks.
            val latitude = roundedHundredths(origin.latitude)
            val longitude = roundedHundredths(origin.longitude)
            val west = longitude - 25
            val east = longitude + 25
            val north = latitude + 20
            val south = latitude - 20
            if (west < -18_000 || east > 18_000 || south < -9_000 || north > 9_000) return null
            return DirectionsViewbox(String.format(Locale.ROOT, "%.2f,%.2f,%.2f,%.2f",
                west / 100.0, north / 100.0, east / 100.0, south / 100.0))
        }

        private fun roundedHundredths(value: Double): Int =
            BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).movePointRight(2).toInt()
    }
}

internal fun rankDirectionsPlaces(places: List<SelectedOsmPlace>, origin: WeatherCoordinate?): List<SelectedOsmPlace> {
    if (origin == null) return places
    return places.map { place ->
        place to WeatherEngine.distanceMeters(origin, WeatherCoordinate(place.latitude, place.longitude))
    }.sortedBy { it.second }.map { it.first }
}
