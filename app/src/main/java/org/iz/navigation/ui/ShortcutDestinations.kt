package org.iz.navigation.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
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

private data class ShortcutSlot(
    val kind: DestinationShortcutKind,
    val label: String,
    val title: String,
    val valid: Boolean,
) {
    val actionLabel: String get() = if (valid) "$title için yol tarifi al" else title
}

@Composable
internal fun ShortcutDestinationsRow(shortcuts: List<DestinationShortcut>, enabled: Boolean, showUnassigned: Boolean,
    onShortcut: (DestinationShortcutKind) -> Unit) {
    val slots = DestinationShortcutKind.entries.mapNotNull { kind ->
        val place = shortcuts.firstOrNull { it.kind == kind }?.place
        if (!showUnassigned && place == null) return@mapNotNull null
        val valid = place?.isValidDestinationShortcut() == true
        val label = if (kind == DestinationShortcutKind.HOME) "Ev" else "İş"
        val title = when {
            showUnassigned && !valid -> "$label adresi ekle"
            place != null && place.name.isNotBlank() -> "$label · ${place.name}"
            else -> label
        }
        ShortcutSlot(kind, label, title, valid)
    }
    if (slots.isEmpty()) return

    val spacing = 8.dp
    val horizontalPadding = 12.dp
    val labelStyle = LocalTextStyle.current.merge(MaterialTheme.typography.labelLarge)
    val textMeasurer = rememberTextMeasurer()
    val colors = MaterialTheme.colorScheme
    // The map can be any color, including black before its tiles are ready.
    val slotColors = ButtonDefaults.outlinedButtonColors(
        containerColor = colors.surface,
        contentColor = colors.onSurface,
        disabledContainerColor = colors.surfaceVariant,
        disabledContentColor = colors.onSurfaceVariant,
    )
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Weighted buttons share the row after spacing. Use the smaller slot if pixels do not divide evenly.
        val labelWidth = with(LocalDensity.current) {
            val rowWidth = constraints.maxWidth - spacing.roundToPx() * (slots.size - 1)
            (rowWidth / slots.size - 2 * horizontalPadding.roundToPx()).coerceAtLeast(0)
        }
        val compact = constraints.hasBoundedWidth && (labelWidth == 0 || slots.any { slot ->
            val layout = textMeasurer.measure(slot.title, style = labelStyle, softWrap = false,
                maxLines = 1, overflow = TextOverflow.Ellipsis, constraints = Constraints(maxWidth = labelWidth))
            layout.hasVisualOverflow || layout.isLineEllipsized(0)
        })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing)) {
            slots.forEach { slot ->
                val slotEnabled = enabled && (slot.valid || showUnassigned)
                OutlinedButton(onClick = { onShortcut(slot.kind) }, enabled = slotEnabled,
                    colors = slotColors,
                    border = BorderStroke(1.dp, if (slotEnabled) colors.onSurfaceVariant else colors.outline),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("destination-shortcut-${slot.kind.name}")
                        .semantics {
                            if (compact && slot.title != slot.label) contentDescription = slot.title
                            onClick(label = slot.actionLabel, action = null)
                            if (!slot.valid) stateDescription = if (showUnassigned) "Atanmamış" else "Kullanılamıyor"
                        },
                    contentPadding = PaddingValues(horizontal = horizontalPadding, vertical = 8.dp)) {
                    Text(if (compact) slot.label else slot.title, style = labelStyle, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}
