package org.iz.navigation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.iz.navigation.data.Journey
import org.iz.navigation.data.TrackPoint
import org.iz.navigation.integration.OsmGpxBlockedReason
import org.iz.navigation.integration.OsmGpxPreparation
import org.iz.navigation.integration.PreparedOsmGpxTrace
import org.iz.navigation.integration.prepareOsmGpxTrace
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** The selected exact artifacts live only as long as this community entry. */
internal class OsmGpsSelection {
    private val selected = mutableStateMapOf<Journey, PreparedOsmGpxTrace>()
    private val preparationLock = Mutex()
    var shortcutHandled by mutableStateOf(false)
        private set
    var shortcutBlocked by mutableStateOf(false)
        private set

    fun shortcutPreparationFailed() {
        if (!shortcutHandled) shortcutBlocked = true
    }

    fun trace(journey: Journey): PreparedOsmGpxTrace? = selected[journey]

    fun traces(journeys: List<Journey>): List<PreparedOsmGpxTrace> = journeys.mapNotNull(::trace)

    fun retain(journeys: List<Journey>) {
        selected.keys.retainAll(journeys.toSet())
    }

    fun toggle(journey: Journey, trace: PreparedOsmGpxTrace) {
        if (selected.remove(journey) == null) selected[journey] = trace
    }

    fun preselect(journey: Journey, trace: PreparedOsmGpxTrace?) {
        if (shortcutHandled) return
        shortcutHandled = true
        shortcutBlocked = trace == null
        if (trace != null) selected[journey] = trace
    }

    suspend fun prepare(journey: Journey, points: suspend (String) -> List<TrackPoint>): OsmGpxPreparation =
        preparationLock.withLock {
            withContext(Dispatchers.IO) { prepareOsmGpxTrace(journey, points(journey.id)) }
        }
}

@Composable
internal fun ColumnScope.OsmGpsCandidates(
    journeys: List<Journey>,
    shortcutJourneyId: String?,
    selection: OsmGpsSelection,
    points: suspend (String) -> List<TrackPoint>,
    enabled: Boolean,
    canSelect: (PreparedOsmGpxTrace) -> Boolean = { true },
    receipt: @Composable (PreparedOsmGpxTrace) -> Unit = {},
) {
    val listState = rememberLazyListState()
    var positioned by remember(shortcutJourneyId) { mutableStateOf(false) }
    LaunchedEffect(journeys, shortcutJourneyId) {
        selection.retain(journeys)
        val index = journeys.indexOfFirst { it.id == shortcutJourneyId }
        if (!positioned && index >= 0) {
            listState.scrollToItem(index)
            positioned = true
        }
    }
    LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("osm-gps-list"), state = listState,
        contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (journeys.isEmpty()) item { Text("Henüz tamamlanmış yolculuk kaydın yok.") }
        items(journeys, key = { it.id }) { journey ->
            OsmGpsCandidate(journey, journey.id == shortcutJourneyId, selection, points, enabled, canSelect, receipt)
        }
    }
}

@Composable
private fun OsmGpsCandidate(
    journey: Journey,
    preselect: Boolean,
    selection: OsmGpsSelection,
    points: suspend (String) -> List<TrackPoint>,
    enabled: Boolean,
    canSelect: (PreparedOsmGpxTrace) -> Boolean,
    receipt: @Composable (PreparedOsmGpxTrace) -> Unit,
) {
    val retained = selection.trace(journey)
    var retry by remember(journey) { mutableIntStateOf(0) }
    var prepared by remember(journey, retry) { mutableStateOf<OsmGpxPreparation?>(retained?.let { OsmGpxPreparation.Ready(it) }) }
    var failed by remember(journey, retry) { mutableStateOf(false) }
    LaunchedEffect(journey, retry) {
        if (prepared != null) return@LaunchedEffect
        try {
            val result = selection.prepare(journey, points)
            prepared = result
            if (preselect) selection.preselect(journey, (result as? OsmGpxPreparation.Ready)?.trace)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
            if (preselect) selection.shortcutPreparationFailed()
        }
    }
    val trace = retained ?: (prepared as? OsmGpxPreparation.Ready)?.trace
    val available = trace != null && canSelect(trace)
    val status = when (val result = prepared) {
        is OsmGpxPreparation.Blocked -> when (result.reason) {
            OsmGpxBlockedReason.TOO_SHORT -> "İz çok kısa. Her iki uçtan 1 km çıkarıldıktan sonra gönderilecek iz kalmıyor."
            OsmGpxBlockedReason.NO_DRAWABLE_SEGMENT -> "Kırpmadan sonra çizilebilir iz kalmadı."
            OsmGpxBlockedReason.NOT_CONFIRMED_COMPLETED -> "Yalnız tamamlanmış ve onaylanmış yolculuklar gönderilebilir."
        }
        is OsmGpxPreparation.Ready -> "Başlangıçtan ve bitişten en az 1 km çıkarıldı. Kalan iz ${osmGpsDistance(result.trace.distanceMeters)}."
        null -> if (failed) "İz hazırlanamadı." else "Önizleme hazırlanıyor…"
    }
    val time = remember(journey.startedAt) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.forLanguageTag("tr-TR"))
            .format(Date(journey.startedAt))
    }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("osm-gps-journey-${journey.id}")
                .toggleable(value = retained != null, enabled = enabled && (available || retained != null),
                    role = Role.Checkbox, onValueChange = { trace?.let { selection.toggle(journey, it) } }),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Checkbox(checked = retained != null, onCheckedChange = null, enabled = enabled && (available || retained != null))
                Column(Modifier.weight(1f)) {
                    Text(journey.title.ifBlank { time }, style = MaterialTheme.typography.titleMedium)
                    if (journey.title.isNotBlank()) Text(time, style = MaterialTheme.typography.bodySmall)
                    Text(status, Modifier.testTag("osm-gps-preparation-${journey.id}"), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (trace != null) {
                OsmGpsPreview(trace)
                receipt(trace)
            }
            if (failed) TextButton(onClick = { retry++ }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Yeniden önizle")
            }
        }
    }
}

private fun osmGpsDistance(meters: Double): String = if (meters < 1_000.0)
    String.format(Locale.forLanguageTag("tr-TR"), "%.0f m", meters)
else String.format(Locale.forLanguageTag("tr-TR"), "%.2f km", meters / 1_000.0)
