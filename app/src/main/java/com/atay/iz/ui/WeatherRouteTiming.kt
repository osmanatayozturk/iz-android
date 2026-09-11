package com.atay.iz.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.atay.iz.weather.PlannedRoute
import com.atay.iz.weather.RideWeatherLiveState
import com.atay.iz.weather.RouteTravelTime

/** Kept inside the expandable map content so it also appears in the fullscreen window. */
@Composable
internal fun WeatherRouteTimingOverlay(
    route: PlannedRoute,
    departureAt: Long,
    live: RideWeatherLiveState? = null,
    modifier: Modifier = Modifier,
) {
    Surface(modifier.testTag("weather_map_timing"), shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = .95f)) {
        WeatherRouteTimingDetails(route, departureAt, live, Modifier.padding(10.dp))
    }
}

@Composable
internal fun WeatherRouteTimingDetails(
    route: PlannedRoute,
    departureAt: Long,
    live: RideWeatherLiveState? = null,
    modifier: Modifier = Modifier,
) {
    val active = live?.takeIf { it.route?.id == route.id }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        val duration = active?.remainingSeconds ?: route.durationSeconds
        Text(if (active?.timingUpdatedAt != null) "Kalan süre: ${formatDuration(duration)}"
            else "Tahmini süre: ${formatDuration(duration)}", fontWeight = FontWeight.SemiBold)
        val arrival = if (active == null) RouteTravelTime.plannedArrival(departureAt.coerceAtLeast(0), route.durationSeconds)
            else active.arrivalAt
        if (arrival != null) Text(
            (if (active?.gpsStale == true) "Son tahmini varış: " else "Tahmini varış: ") + weatherTime(arrival),
            style = MaterialTheme.typography.bodySmall)
        if (active?.gpsStale == true) Text(
            if (active.timingUpdatedAt == null) "Canlı varış için konum bekleniyor."
            else "Konum güncel değil; süre tahmini sabitlendi.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}
