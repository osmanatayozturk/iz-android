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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.iz.navigation.data.Journey
import org.iz.navigation.data.TrackPoint
import org.iz.navigation.integration.OsmGpxPreparation
import org.iz.navigation.integration.PreparedOsmGpxTrace
import org.iz.navigation.integration.osm.OsmGpxReceiptKey
import org.iz.navigation.integration.prepareOsmGpxTrace
import java.text.DateFormat
import java.util.Date
import java.util.Locale

internal data class OsmGpsCandidate(val key: OsmGpxReceiptKey, val title: String, val startedAt: Long, val distanceMeters: Double)

/** Entry-owned selections contain no GPX bytes. An account change retires the entire selection. */
internal class OsmGpsSelection {
    private var accountId: Long? = null
    private val preparationLock = Mutex()
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

    fun retain(available: Set<OsmGpxReceiptKey>) { keys = keys.intersect(available) }

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

    suspend fun prepare(journey: Journey, points: suspend (String) -> List<TrackPoint>): PreparedOsmGpxTrace? =
        preparationLock.withLock {
            withContext(Dispatchers.IO) {
                (prepareOsmGpxTrace(journey, points(journey.id)) as? OsmGpxPreparation.Ready)?.trace
            }
        }

    suspend fun summarize(journey: Journey, points: suspend (String) -> List<TrackPoint>): OsmGpsCandidate? =
        prepare(journey, points)?.let { trace ->
            OsmGpsCandidate(OsmGpxReceiptKey.of(trace), journey.title, journey.startedAt, trace.distanceMeters)
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
) {
    if (candidates.isNotEmpty()) {
        val allSelected = candidates.all { it.key in selected }
        TextButton(onClick = onToggleAll, enabled = enabled, modifier = Modifier.padding(horizontal = 12.dp)
            .heightIn(min = 48.dp).testTag("osm-gps-select-all")) {
            Text(if (allSelected) "Tüm seçimleri kaldır" else "Tümünü seç")
        }
    }
    LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("osm-gps-list"),
        contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (candidates.isEmpty()) item { Text("Yüklenebilir iz bulunamadı.") }
        items(candidates, key = { it.key.journeyId }) { candidate ->
            val selectedHere = candidate.key in selected
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
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
