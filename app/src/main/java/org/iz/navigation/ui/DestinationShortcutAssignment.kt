@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package org.iz.navigation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
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
    onAddressSearch: (() -> Unit)? = null,
) {
    val eligible = remember(places) { places.filter(Place::isValidDestinationShortcut) }
    var selectedId by rememberSaveable(kind) { mutableStateOf<String?>(null) }
    val selected = eligible.firstOrNull { it.id == selectedId }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).testTag("shortcut-assignment"),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(shortcutAssignmentTitle(kind), style = MaterialTheme.typography.headlineSmall)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let {
            Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                color = MaterialTheme.colorScheme.error)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().selectableGroup().testTag("shortcut-assignment-list"),
            contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text("Kayıtlı yerlerden seç", style = MaterialTheme.typography.titleMedium) }
            if (eligible.isEmpty()) item { Text("Konumlu kayıtlı yer bulunamadı.") }
            items(eligible, key = Place::id) { place ->
                val chosen = place.id == selectedId
                Surface(
                    color = if (chosen) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .selectable(chosen, enabled = !busy, role = Role.RadioButton, onClick = { selectedId = place.id })
                            .testTag("shortcut-assignment-place-${place.id}").padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = chosen, onClick = null, enabled = !busy)
                        Text(place.name)
                    }
                }
            }
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            onAddressSearch?.let { search ->
                OutlinedButton(onClick = search, enabled = !busy) { Text("Adres ara") }
            }
            OutlinedButton(onClick = onNewPlace, enabled = !busy,
                modifier = Modifier.heightIn(min = 48.dp).testTag("shortcut-assignment-new")) { Text("Yeni yer ekle") }
            TextButton(onClick = onCancel, enabled = !busy,
                modifier = Modifier.heightIn(min = 48.dp).testTag("shortcut-assignment-cancel")) { Text("Vazgeç") }
        }
        Button(onClick = { selected?.let(onPlace) }, enabled = selected != null && !busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("shortcut-assignment-save")) {
            Text("Seçimi kaydet")
        }
    }
}
