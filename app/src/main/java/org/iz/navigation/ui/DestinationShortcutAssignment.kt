package org.iz.navigation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.iz.navigation.data.DestinationShortcutKind
import org.iz.navigation.data.Place
import org.iz.navigation.data.isValidDestinationShortcut
import org.iz.navigation.ui.history.UiEntry
import org.iz.navigation.ui.history.UiPage

internal fun shortcutAssignmentId(kind: DestinationShortcutKind): String = "shortcut-assignment:${kind.name}"

internal fun UiEntry.shortcutAssignmentKind(): DestinationShortcutKind? =
    if (page == UiPage.PLACES && parentKey == "map") DestinationShortcutKind.entries.find {
        entityId == shortcutAssignmentId(it)
    } else null

internal fun shortcutAssignmentTitle(kind: DestinationShortcutKind): String =
    when (kind) {
        DestinationShortcutKind.HOME -> "Ev adresi ekle"
        DestinationShortcutKind.WORK -> "İş adresi ekle"
    }

@Composable
internal fun DestinationShortcutAssignmentScreen(
    kind: DestinationShortcutKind,
    places: List<Place>,
    busy: Boolean,
    error: String?,
    onPlace: (Place) -> Unit,
    onNewPlace: () -> Unit,
    onCancel: () -> Unit,
) {
    val eligible = remember(places) { places.filter(Place::isValidDestinationShortcut) }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).testTag("shortcut-assignment"),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(shortcutAssignmentTitle(kind), style = MaterialTheme.typography.headlineSmall)
        Text("Konumu olan kayıtlı bir yer seç veya yeni bir yer kaydet.")
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let {
            Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                color = MaterialTheme.colorScheme.error)
            Text("Yeniden denemek için bir yer seç veya yeni yer ekle.", style = MaterialTheme.typography.bodySmall)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("shortcut-assignment-list"),
            contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (eligible.isEmpty()) item { Text("Konumlu kayıtlı yer bulunamadı.") }
            items(eligible, key = Place::id) { place ->
                OutlinedCard(onClick = { onPlace(place) }, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .testTag("shortcut-assignment-place-${place.id}")) {
                    Text(place.name, Modifier.padding(12.dp))
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onCancel, enabled = !busy,
                modifier = Modifier.heightIn(min = 48.dp).testTag("shortcut-assignment-cancel")) { Text("Vazgeç") }
            Button(onClick = onNewPlace, enabled = !busy,
                modifier = Modifier.heightIn(min = 48.dp).testTag("shortcut-assignment-new")) { Text("Yeni yer ekle") }
        }
    }
}
