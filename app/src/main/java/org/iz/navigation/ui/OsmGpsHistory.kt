package org.iz.navigation.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.iz.navigation.integration.osm.OsmGpxHistoryEntry
import org.iz.navigation.integration.osm.OsmGpxReceipt
import org.iz.navigation.integration.osm.OsmGpxVisibility

internal val OsmGpxHistoryEntry.canChangeToTrackable: Boolean
    get() = !pending && visibility.equals(OsmGpxVisibility.IDENTIFIABLE.wireValue, ignoreCase = true)

@Composable
internal fun ColumnScope.OsmGpsHistoryList(
    entries: List<OsmGpxHistoryEntry>,
    selected: Set<Long> = emptySet(),
    editingEnabled: Boolean = false,
    results: Map<Long, String> = emptyMap(),
    onToggle: (Long) -> Unit = {},
    onOpen: (OsmGpxHistoryEntry) -> Unit,
) {
    val ordered = remember(entries) {
        entries.sortedWith(compareByDescending<OsmGpxHistoryEntry> { it.timestampMillis }.thenByDescending { it.id })
    }
    LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("osm-gps-history-list"),
        contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item { Text("Hesaba ait GPS izleri", style = MaterialTheme.typography.titleMedium) }
        if (ordered.isEmpty()) item { Text("Bu OSM hesabında yüklenmiş iz bulunamadı.") }
        items(ordered, key = { it.id }) { trace ->
            Surface(onClick = { onOpen(trace) }, color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()
                .heightIn(min = 48.dp).testTag("osm-gps-history-${trace.id}")) {
                Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(trace.fileName, style = MaterialTheme.typography.titleMedium)
                    Text(osmGpsDate(trace.timestampMillis), style = MaterialTheme.typography.bodySmall)
                    Text(if (trace.pending) "GPS izi · OSM'de işleniyor" else "GPS izi · Yüklendi",
                        style = MaterialTheme.typography.bodySmall)
                    when {
                        trace.visibility.equals(OsmGpxVisibility.IDENTIFIABLE.wireValue, ignoreCase = true) ->
                            Text("Tanımlanabilir", style = MaterialTheme.typography.bodySmall)
                        trace.visibility.equals(OsmGpxVisibility.TRACKABLE.wireValue, ignoreCase = true) ->
                            Text("Takip Edilebilir", style = MaterialTheme.typography.bodySmall)
                    }
                    Text("İz ayrıntısı ve görünürlük · OSM'de aç", color = MaterialTheme.colorScheme.primary)
                }
            }
            val result = results[trace.id]
            if (result != null) {
                Text(result, Modifier.testTag("osm-gps-visibility-result-${trace.id}"), style = MaterialTheme.typography.bodySmall)
            } else if (trace.canChangeToTrackable && editingEnabled) {
                val checked = trace.id in selected
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("osm-gps-visibility-select-${trace.id}")
                        .toggleable(checked, role = Role.Checkbox, onValueChange = { onToggle(trace.id) }),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Checkbox(checked = checked, onCheckedChange = null)
                    Text("${trace.fileName} izini Takip Edilebilir yapmak için seç")
                }
            }
            HorizontalDivider()
        }
    }
}

@Composable
internal fun OsmGpsVisibilityConfirmation(
    username: String,
    entries: List<OsmGpxHistoryEntry>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        modifier = Modifier.testTag("osm-gps-visibility-consent"),
        onDismissRequest = onDismiss,
        title = { Text("${entries.size} izin görünürlüğü değişsin mi?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("OSM hesabı $username")
                Text("Seçili izler Tanımlanabilir yerine Takip Edilebilir olacak.")
                Text("OSM API’si iz noktalarını ve kayıt zamanlarını paylaşmaya devam eder.")
                entries.forEach { Text("${it.fileName} · ${osmGpsDate(it.timestampMillis)}") }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.heightIn(min = 48.dp)
                .testTag("osm-gps-visibility-confirm")) { Text("Takip Edilebilir yap") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } },
    )
}

@Composable
internal fun ColumnScope.OsmGpsUnknownList(
    receipts: List<OsmGpxReceipt>,
    enabled: Boolean,
    onCheck: () -> Unit,
) {
    val ordered = remember(receipts) { receipts.sortedByDescending { it.attemptedAt } }
    LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("osm-gps-unknown-list"),
        contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (ordered.isEmpty()) item { Text("Kontrol bekleyen iz bulunamadı.") }
        items(ordered, key = { "${it.key.journeyId}:${it.key.sha256}" }) { receipt ->
            Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(receipt.key.fileName, style = MaterialTheme.typography.titleMedium)
                    Text("Gönderim sonucu doğrulanamadı. OSM’de kontrol et ve iz geçmişini yeniden denetle.",
                        Modifier.testTag("osm-gps-result-${receipt.key.journeyId}"), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onCheck, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)
                        .testTag("osm-gps-check-${receipt.key.journeyId}")) { Text("OSM’de izlerimi kontrol et") }
                }
            }
            HorizontalDivider()
        }
    }
}
