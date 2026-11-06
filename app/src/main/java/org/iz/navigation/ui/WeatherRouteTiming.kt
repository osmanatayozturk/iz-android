package org.iz.navigation.ui

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
import org.iz.navigation.weather.PlannedRoute
import org.iz.navigation.weather.RideWeatherLiveState
import org.iz.navigation.weather.RouteSchedule
import org.iz.navigation.weather.RouteTripTiming

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
        WeatherRouteTimingDetails(route, departureAt, live, Modifier.padding(10.dp), compact = true)
    }
}

@Composable
internal fun WeatherRouteTimingDetails(
    route: PlannedRoute,
    departureAt: Long,
    live: RideWeatherLiveState? = null,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val active = live?.takeIf { it.route?.id == route.id }
    val departure = departureAt.coerceAtLeast(0)
    val timing = if (active == null) RouteSchedule.planned(route, departure) else active.tripTiming
    val duration = timing?.totalSeconds ?: active?.remainingSeconds
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        if (!compact && timing != null) {
            RouteTimingBreakdown(timing)
        } else if (duration != null) {
            Text(if (active?.timingUpdatedAt != null) "Kalan süre: ${formatDuration(duration)}"
                else "Tahmini süre: ${formatDuration(duration)}", fontWeight = FontWeight.SemiBold)
        }
        val arrival = timing?.arrivalAt ?: active?.arrivalAt
        if (arrival != null) Text(
            (if (active?.gpsStale == true && active.plannedBreak == null) "Son tahmini varış: " else "Tahmini varış: ") +
                weatherTime(arrival), style = MaterialTheme.typography.bodySmall)
        if (active?.gpsStale == true) Text(
            when {
                active.plannedBreak != null -> "Konum güncel değil. Mola sayacı çalışıyor."
                active.timingUpdatedAt == null -> "Canlı varış için konum bekleniyor."
                else -> "Konum güncel değil; süre tahmini sabitlendi."
            },
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        if (!compact && active == null) PlannedViaSchedule(route, departure)
    }
}

@Composable
internal fun RouteTimingBreakdown(timing: RouteTripTiming) {
    val style = MaterialTheme.typography.bodySmall
    Text("Sürüş: ${formatDuration(timing.drivingSeconds)}", style = style)
    Text("Mola: ${formatDuration(timing.breakSeconds)}", style = style)
    Text("Toplam süre: ${formatDuration(timing.totalSeconds)}", style = style, fontWeight = FontWeight.SemiBold)
}

@Composable
internal fun PlannedViaSchedule(route: PlannedRoute, departureAt: Long) {
    if (route.stops.size < 3 || route.stopElapsedSeconds.size != route.stops.size) return
    RouteSchedule.stops(route, departureAt).drop(1).dropLast(1).forEach { timing ->
        Column(Modifier.padding(top = 4.dp)) {
            Text(route.stops[timing.stopIndex].label, style = MaterialTheme.typography.labelLarge)
            Text("Varış ${weatherTime(timing.arrivalAt)}", style = MaterialTheme.typography.bodySmall)
            Text("Çıkış ${weatherTime(timing.departureAt)}", style = MaterialTheme.typography.bodySmall)
        }
    }
}
