package org.iz.navigation.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.iz.navigation.integration.osm.OsmGpxReceiptKey
import java.text.DateFormat
import java.util.Date
import java.util.Locale

internal data class OsmGpsCandidate(val key: OsmGpxReceiptKey, val title: String, val startedAt: Long, val distanceMeters: Double)
internal data class OsmGpsBlockedCandidate(val journeyId: String, val title: String, val startedAt: Long)

/** Entry-owned selections contain no GPX bytes. An account change retires the entire selection. */
internal class OsmGpsSelection {
    private var accountId: Long? = null
    private var shortcutHandled = false
    private var keys by mutableStateOf(emptySet<OsmGpxReceiptKey>())

    fun keysFor(account: Long?): Set<OsmGpxReceiptKey> =
        if (account != null && account == accountId) keys else emptySet()

    fun bind(account: Long?) {
        if (accountId == account) return
        if (accountId != null) shortcutHandled = true
        accountId = account
        keys = emptySet()
    }

    fun retain(available: Set<OsmGpxReceiptKey>) {
        val selectedIds = keys.mapTo(mutableSetOf()) { it.journeyId }
        keys = available.filterTo(mutableSetOf()) { it.journeyId in selectedIds }
    }

    fun toggle(key: OsmGpxReceiptKey) {
        if (accountId != null) keys = if (key in keys) keys - key else keys + key
    }

    fun toggleAll(available: Set<OsmGpxReceiptKey>) {
        if (accountId != null) keys = if (keys.containsAll(available)) emptySet() else available
    }

    fun preselect(key: OsmGpxReceiptKey?) {
        if (accountId == null || shortcutHandled) return
        shortcutHandled = true
        if (key != null) keys = keys + key
    }

}

@Composable
internal fun ColumnScope.OsmGpsCandidates(
    candidates: List<OsmGpsCandidate>,
    selected: Set<OsmGpxReceiptKey>,
    enabled: Boolean,
    onToggleAll: () -> Unit,
    onToggle: (OsmGpxReceiptKey) -> Unit,
    onPreview: (OsmGpxReceiptKey) -> Unit,
    rowStatus: (OsmGpxReceiptKey) -> String? = { null },
    blocked: List<OsmGpsBlockedCandidate> = emptyList(),
) {
    if (candidates.isNotEmpty()) {
        val allSelected = candidates.all { it.key in selected }
        TextButton(onClick = onToggleAll, enabled = enabled, modifier = Modifier.padding(horizontal = 12.dp)
            .heightIn(min = 48.dp).testTag("osm-gps-select-all")) {
            Text(if (allSelected) "Tüm seçimleri kaldır" else "Tümünü seç")
        }
    }
    LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("osm-gps-list"),
        contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (candidates.isNotEmpty() || blocked.isEmpty()) {
            item { Text("Paylaşılabilecek izler", style = MaterialTheme.typography.titleMedium) }
            if (candidates.isEmpty()) item { Text("Yüklenebilir iz bulunamadı.") }
        }
        items(candidates, key = { it.key.journeyId }) { candidate ->
            val selectedHere = candidate.key in selected
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("osm-gps-journey-${candidate.key.journeyId}")
                    .toggleable(value = selectedHere, enabled = enabled, role = Role.Checkbox,
                        onValueChange = { onToggle(candidate.key) }),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Checkbox(checked = selectedHere, onCheckedChange = null, enabled = enabled)
                    Column(Modifier.weight(1f)) {
                        val date = osmGpsDate(candidate.startedAt)
                        Text(candidate.title.ifBlank { date }, style = MaterialTheme.typography.titleMedium)
                        if (candidate.title.isNotBlank()) Text(date, style = MaterialTheme.typography.bodySmall)
                        Text(osmGpsDistance(candidate.distanceMeters), style = MaterialTheme.typography.bodySmall)
                    }
                }
                TextButton(onClick = { onPreview(candidate.key) }, enabled = enabled,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("osm-gps-preview-open-${candidate.key.journeyId}")) {
                    Text("Önizle")
                }
                rowStatus(candidate.key)?.let {
                    Text(it, Modifier.testTag("osm-gps-result-${candidate.key.journeyId}")
                        .semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall)
                }
            }
            HorizontalDivider()
        }
        if (blocked.isNotEmpty()) {
            item { Text("Planı doğrulanamayan yolculuklar", style = MaterialTheme.typography.titleMedium) }
            items(blocked, key = { it.journeyId }) { candidate ->
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("osm-gps-blocked-${candidate.journeyId}")) {
                    val date = osmGpsDate(candidate.startedAt)
                    Text(candidate.title.ifBlank { date }, style = MaterialTheme.typography.titleMedium)
                    if (candidate.title.isNotBlank()) Text(date, style = MaterialTheme.typography.bodySmall)
                    Text("İz doğrulanamadı · Gönderim bekliyor.", style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
            }
        }
    }
}

internal fun osmGpsDate(timestamp: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.forLanguageTag("tr-TR")).format(Date(timestamp))

internal fun osmGpsDistance(meters: Double): String = if (meters < 1_000.0)
    String.format(Locale.forLanguageTag("tr-TR"), "%.0f m", meters)
else String.format(Locale.forLanguageTag("tr-TR"), "%.2f km", meters / 1_000.0)

@Preview(showBackground = true, widthDp = 360, heightDp = 560)
@Composable
private fun OsmGpsCandidatesPreview() {
    IzTheme {
        Column(Modifier.fillMaxSize()) {
            val candidate = OsmGpsCandidate(OsmGpxReceiptKey("sample", "a".repeat(64)), "Sahil yolculuğu", 0, 3200.0)
            OsmGpsCandidates(listOf(candidate), setOf(candidate.key), true, {}, {}, {})
        }
    }
}
