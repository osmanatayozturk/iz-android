package org.iz.navigation.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.iz.navigation.integration.osm.OsmGpxHistoryEntry
import org.iz.navigation.integration.osm.OsmGpxReceipt

@Composable
internal fun ColumnScope.OsmGpsHistoryList(entries: List<OsmGpxHistoryEntry>, onOpen: (OsmGpxHistoryEntry) -> Unit) {
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
                    Text("İz ayrıntısı ve görünürlük · OSM'de aç", color = MaterialTheme.colorScheme.primary)
                }
            }
            HorizontalDivider()
        }
    }
}

@Composable
internal fun ColumnScope.OsmGpsUnknownList(
    receipts: List<OsmGpxReceipt>,
    opened: Set<OsmGpxReceipt>,
    enabled: Boolean,
    canRetry: Boolean,
    onCheck: (OsmGpxReceipt) -> Unit,
    onRetry: (OsmGpxReceipt) -> Unit,
) {
    LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("osm-gps-unknown-list"),
        contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (receipts.isEmpty()) item { Text("Kontrol bekleyen iz bulunamadı.") }
        items(receipts, key = { it.key.journeyId }) { receipt ->
            Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(receipt.key.fileName, style = MaterialTheme.typography.titleMedium)
                    Text("Gönderim sonucu doğrulanamadı. Yeniden göndermeden önce OSM’de kontrol et.",
                        Modifier.testTag("osm-gps-result-${receipt.key.journeyId}"), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { onCheck(receipt) }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)
                        .testTag("osm-gps-check-${receipt.key.journeyId}")) { Text("OSM’de izlerimi kontrol et") }
                    if (receipt in opened) {
                        TextButton(onClick = { onRetry(receipt) }, enabled = canRetry, modifier = Modifier.heightIn(min = 48.dp)
                            .testTag("osm-gps-unknown-reviewed-${receipt.key.journeyId}")) {
                            Text("İzi bulamadım, yeniden göndermeyi incele")
                        }
                    }
                }
            }
            HorizontalDivider()
        }
    }
}
