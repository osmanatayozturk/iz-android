package org.iz.navigation.car

import org.iz.navigation.data.DestinationShortcut
import org.iz.navigation.data.DestinationShortcutKind
import org.iz.navigation.weather.RouteStop
import org.iz.navigation.weather.WeatherCoordinate

internal fun carShortcutLabel(kind: DestinationShortcutKind): String = when (kind) {
    DestinationShortcutKind.HOME -> "Ev"
    DestinationShortcutKind.WORK -> "İş"
}

/** Resolve the shared live Place; absent/deleted/invalid coordinates must never become a target. */
internal fun carShortcutStop(shortcut: DestinationShortcut): RouteStop? {
    val latitude = shortcut.place.latitude ?: return null
    val longitude = shortcut.place.longitude ?: return null
    if (!latitude.isFinite() || !longitude.isFinite() || latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
    return RouteStop(shortcut.place.name.take(300).ifBlank { carShortcutLabel(shortcut.kind) }, WeatherCoordinate(latitude, longitude))
}
