package org.iz.navigation.car

import androidx.car.app.model.Distance
import androidx.car.app.navigation.model.*
import org.iz.navigation.navigation.NavigationState
import org.iz.navigation.weather.RouteManeuver
import java.time.ZonedDateTime

internal fun carManeuverType(type: Int): Int = when (type) {
    1, 2, 3 -> Maneuver.TYPE_DEPART
    4 -> Maneuver.TYPE_DESTINATION
    5 -> Maneuver.TYPE_DESTINATION_RIGHT
    6 -> Maneuver.TYPE_DESTINATION_LEFT
    7 -> Maneuver.TYPE_NAME_CHANGE
    8, 17, 22 -> Maneuver.TYPE_STRAIGHT
    9 -> Maneuver.TYPE_TURN_SLIGHT_RIGHT
    10 -> Maneuver.TYPE_TURN_NORMAL_RIGHT
    11 -> Maneuver.TYPE_TURN_SHARP_RIGHT
    12 -> Maneuver.TYPE_U_TURN_RIGHT
    13 -> Maneuver.TYPE_U_TURN_LEFT
    14 -> Maneuver.TYPE_TURN_SHARP_LEFT
    15 -> Maneuver.TYPE_TURN_NORMAL_LEFT
    16 -> Maneuver.TYPE_TURN_SLIGHT_LEFT
    18 -> Maneuver.TYPE_ON_RAMP_NORMAL_RIGHT
    19 -> Maneuver.TYPE_ON_RAMP_NORMAL_LEFT
    20 -> Maneuver.TYPE_OFF_RAMP_NORMAL_RIGHT
    21 -> Maneuver.TYPE_OFF_RAMP_NORMAL_LEFT
    23 -> Maneuver.TYPE_KEEP_RIGHT
    24 -> Maneuver.TYPE_KEEP_LEFT
    25 -> Maneuver.TYPE_MERGE_SIDE_UNSPECIFIED
    28 -> Maneuver.TYPE_FERRY_BOAT
    37 -> Maneuver.TYPE_MERGE_RIGHT
    38 -> Maneuver.TYPE_MERGE_LEFT
    // Valhalla's roundabout type alone doesn't specify clockwise/counterclockwise travel.
    else -> Maneuver.TYPE_UNKNOWN
}

internal fun carStep(maneuver: RouteManeuver): Step = Step.Builder(maneuver.instruction.ifBlank { "Rotayı takip edin" })
    .setManeuver(Maneuver.Builder(carManeuverType(maneuver.type)).build()).build()

internal fun carDistance(meters: Double): Distance = Distance.create(meters.coerceAtLeast(0.0), Distance.UNIT_METERS)

internal fun carEstimate(meters: Double, seconds: Double): TravelEstimate = TravelEstimate.Builder(
    carDistance(meters), ZonedDateTime.now().plusSeconds(seconds.toLong().coerceAtLeast(0)))
    .setRemainingTimeSeconds(seconds.toLong().coerceAtLeast(0)).build()

internal fun carTrip(state: NavigationState): Trip {
    val route = state.route ?: return Trip.Builder().setLoading(true).build()
    val progress = state.progress
    if (state.gpsStale || state.loading || progress == null || progress.offRoute) {
        return Trip.Builder().setLoading(true).build()
    }
    val builder = Trip.Builder().addDestination(Destination.Builder().setName(route.stops.last().label).build(),
        carEstimate(progress.remainingMeters, progress.remainingSeconds))
    route.maneuvers.getOrNull(progress.maneuverIndex)?.let { maneuver ->
        builder.addStep(carStep(maneuver), carEstimate(progress.nextManeuverDistanceMeters,
            (maneuver.beginElapsedSeconds - progress.elapsedSeconds).coerceAtLeast(0.0)))
        // Upcoming street_names describe the turn's road, not the current matched segment.
    }
    return builder.build()
}
