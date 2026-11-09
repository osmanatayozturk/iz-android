package org.iz.navigation.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.iz.navigation.data.DestinationShortcut
import org.iz.navigation.data.DestinationShortcutKind
import org.iz.navigation.data.Place
import org.iz.navigation.data.isValidDestinationShortcut
import org.iz.navigation.weather.RouteStop
import org.iz.navigation.weather.WeatherCoordinate

internal fun shortcutRouteStop(place: Place?): RouteStop? {
    if (place == null || !place.isValidDestinationShortcut()) return null
    return RouteStop(place.name, WeatherCoordinate(requireNotNull(place.latitude), requireNotNull(place.longitude)))
}

@Composable
internal fun ShortcutDestinationsRow(shortcuts: List<DestinationShortcut>, enabled: Boolean, showUnassigned: Boolean,
    onShortcut: (DestinationShortcutKind) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (kind in DestinationShortcutKind.entries) {
            val place = shortcuts.firstOrNull { it.kind == kind }?.place
            if (!showUnassigned && place == null) continue
            val valid = place?.isValidDestinationShortcut() == true
            val label = if (kind == DestinationShortcutKind.HOME) "Ev" else "İş"
            val title = when {
                showUnassigned && !valid -> "$label adresi ekle"
                place != null && place.name.isNotBlank() -> "$label · ${place.name}"
                else -> label
            }
            OutlinedButton(onClick = { onShortcut(kind) }, enabled = enabled && (valid || showUnassigned),
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("destination-shortcut-${kind.name}")
                    .semantics { if (!valid) stateDescription = if (showUnassigned) "Atanmamış" else "Kullanılamıyor" },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
