package org.iz.navigation.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.iz.navigation.data.*
import org.iz.navigation.weather.RouteStop
import org.iz.navigation.weather.WeatherCoordinate

internal fun shortcutRouteStop(place: Place?): RouteStop? {
    val latitude = place?.latitude ?: return null
    val longitude = place.longitude ?: return null
    if (place.name.isBlank() || place.name.length > 300) return null
    if (!latitude.isFinite() || !longitude.isFinite() || latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
    return RouteStop(place.name, WeatherCoordinate(latitude, longitude))
}

@Composable
internal fun ShortcutDestinationsRow(shortcuts: List<DestinationShortcut>, enabled: Boolean,
    onShortcut: (DestinationShortcutKind) -> Unit) {
    if (shortcuts.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        shortcuts.forEach { shortcut ->
            val label = if (shortcut.kind == DestinationShortcutKind.HOME) "Ev" else "İş"
            AssistChip(onClick = { onShortcut(shortcut.kind) }, label = { Text("$label · ${shortcut.place.name}", maxLines = 1) },
                enabled = enabled && shortcutRouteStop(shortcut.place) != null,
                modifier = Modifier.weight(1f).testTag("destination-shortcut-${shortcut.kind.name}"))
        }
    }
}