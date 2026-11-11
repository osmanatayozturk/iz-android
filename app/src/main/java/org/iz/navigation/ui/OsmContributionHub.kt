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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.iz.navigation.data.ContributionDraft
import org.iz.navigation.data.ContributionStatus
import org.iz.navigation.data.DiaryRepository
import org.iz.navigation.data.MapEditDraft
import org.iz.navigation.data.MapEditStatus
import org.iz.navigation.integration.osm.OsmAuthManager
import org.iz.navigation.integration.osm.OsmGpxHistoryClient

@Composable
internal fun OsmContributionHub(
    repository: DiaryRepository,
    onClose: () -> Unit,
    onNewNote: () -> Unit,
    onNewPlace: () -> Unit,
    onPickExistingPlace: () -> Unit,
    onGps: () -> Unit,
    onResumeNote: (ContributionDraft) -> Unit,
    onResumePlace: (MapEditDraft) -> Unit,
    onNoteResult: (ContributionDraft) -> Unit,
    onPlaceResult: (MapEditDraft) -> Unit,
    onGpsHistory: () -> Unit,
    onManageNotes: (() -> Unit)? = null,
    onSurvey: () -> Unit = {},
) {
    val context = LocalContext.current
    val auth = remember(context) { OsmAuthManager.get(context) }
    val source = auth.communitySessionSource
    val authState by auth.state.collectAsStateWithLifecycle()
    val observedSession by source.sessions.collectAsStateWithLifecycle()
    val notes by repository.contributions.collectAsStateWithLifecycle(initialValue = emptyList())
    val places by repository.mapEdits.collectAsStateWithLifecycle(initialValue = emptyList())
    val session = observedSession?.takeIf {
        !authState.busy && it.userId == authState.user?.id && it === auth.currentGpxSession()
    }
    val historySession = session?.takeIf { it === auth.currentGpxHistorySession() }
    val historyReader = remember(auth) {
        OsmContributionHubHistory(auth::currentGpxHistorySession, OsmGpxHistoryClient(), source::rejectSession)
    }
    var refreshRevision by remember { mutableIntStateOf(0) }
    // Key the visible snapshot on the exact session so composition drops old rows before loading.
    var history by remember(historySession, refreshRevision) { mutableStateOf<OsmContributionHubHistoryResult?>(null) }
    LaunchedEffect(historyReader, historySession, refreshRevision) {
        if (historySession != null) history = historyReader.load(historySession)
    }

    val gps = (history as? OsmContributionHubHistoryResult.Ready)?.entries.orEmpty()
    val rows = remember(notes, places, session, gps) { osmContributionHubRows(notes, places, session?.userId, gps) }
    OsmContributionHubContent(rows, history, canRefresh = historySession != null,
        onClose = onClose, onNewNote = onNewNote, onNewPlace = onNewPlace,
        onPickExistingPlace = onPickExistingPlace, onGps = onGps, onGpsHistory = onGpsHistory,
        onRefresh = { refreshRevision++ }, onManageNotes = onManageNotes, onSurvey = onSurvey, onOpen = { row ->
            val current = session != null && auth.currentGpxSession() === session
            when (row) {
                is OsmContributionHubRow.Note -> if (row.draft.status == ContributionStatus.DRAFT || current) {
                    if (row.editable) onResumeNote(row.draft) else onNoteResult(row.draft)
                }
                is OsmContributionHubRow.Place -> if (row.draft.status == MapEditStatus.DRAFT || current) {
                    if (row.editable) onResumePlace(row.draft) else onPlaceResult(row.draft)
                }
                is OsmContributionHubRow.Gps -> if (historySession != null && auth.currentGpxHistorySession() === historySession) {
                    onGpsHistory()
                }
            }
        })
}

@Composable
internal fun OsmContributionHubContent(
    rows: List<OsmContributionHubRow>,
    history: OsmContributionHubHistoryResult?,
    canRefresh: Boolean,
    onClose: () -> Unit,
    onNewNote: () -> Unit,
    onNewPlace: () -> Unit,
    onPickExistingPlace: () -> Unit,
    onGps: () -> Unit,
    onGpsHistory: () -> Unit,
    onRefresh: () -> Unit,
    onOpen: (OsmContributionHubRow) -> Unit,
    onManageNotes: (() -> Unit)? = null,
    onSurvey: () -> Unit = {},
) {
    Column(Modifier.fillMaxSize().testTag("osm-contribution-hub")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("Katkı Merkezi", Modifier.weight(1f).semantics { heading() },
                style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text("Kapat") }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("osm-contribution-list"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { ContributionHubAction("Harita notu yaz", "new-note", onNewNote) }
            item { ContributionHubAction("Yeni mekân ekle", "new-place", onNewPlace) }
            item { ContributionHubAction("Mevcut mekânı düzelt", "existing-place", onPickExistingPlace) }
            item { ContributionHubAction("Yerinde tamamla", "survey", onSurvey) }
            item { ContributionHubAction("GPS izi gönder", "gps", onGps) }
            item { ContributionHubAction("Yüklenenler", "uploads", onGpsHistory) }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Katkılarım", Modifier.weight(1f).semantics { heading() },
                        style = MaterialTheme.typography.titleLarge)
                    if (canRefresh) TextButton(onClick = onRefresh, enabled = history != null,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("osm-contribution-refresh")) { Text("Yenile") }
                }
            }
            if (onManageNotes != null) item {
                TextButton(onClick = onManageNotes, modifier = Modifier.heightIn(min = 48.dp)
                    .testTag("osm-contribution-manage-notes")) { Text("Harita notlarını yönet") }
            }
            if (canRefresh && history == null) item {
                LinearProgressIndicator(Modifier.fillMaxWidth().testTag("osm-contribution-history-loading"))
            }
            if (history == OsmContributionHubHistoryResult.Failed) item {
                Text("OSM iz geçmişi yüklenemedi.", Modifier.testTag("osm-contribution-history-error"),
                    color = MaterialTheme.colorScheme.error)
            }
            if (rows.isEmpty() && (!canRefresh || history is OsmContributionHubHistoryResult.Ready)) item {
                Text("Henüz katkı kaydı yok.", style = MaterialTheme.typography.bodyMedium)
            }
            items(rows, key = { it.key }) { row -> ContributionHubRow(row) { onOpen(row) } }
        }
    }
}

@Composable
private fun ContributionHubAction(label: String, key: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("osm-contribution-$key")) { Text(label) }
}

@Composable
private fun ContributionHubRow(row: OsmContributionHubRow, onClick: () -> Unit) {
    val title: String
    val type: String
    val status: String
    val enabled: Boolean
    when (row) {
        is OsmContributionHubRow.Note -> {
            title = row.draft.text.ifBlank { row.draft.kind.label() }
            type = "Harita notu"
            status = row.draft.status.label()
            enabled = row.draft.status != ContributionStatus.SENDING
        }
        is OsmContributionHubRow.Place -> {
            title = row.draft.name.ifBlank { row.draft.preset.label }
            type = if (row.draft.targetType == null) "Yeni mekân" else "Mekân düzeltmesi"
            status = when (row.draft.status) {
                MapEditStatus.DRAFT -> "Taslak"
                MapEditStatus.SENDING -> "Gönderiliyor"
                MapEditStatus.SENT -> "OSM'de yayımlandı"
                MapEditStatus.FAILED -> "Gönderilemedi"
                MapEditStatus.UNKNOWN -> "Sonuç kontrol edilmeli"
            }
            enabled = row.draft.status != MapEditStatus.SENDING
        }
        is OsmContributionHubRow.Gps -> {
            title = row.entry.fileName
            type = "GPS izi"
            status = if (row.entry.pending) "OSM'de işleniyor" else "OSM'ye yüklendi"
            enabled = true
        }
    }
    OutlinedCard(onClick = onClick, enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("osm-contribution-${row.key}")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("$type · $status", style = MaterialTheme.typography.bodyMedium)
            Text(osmGpsDate(row.timestamp), style = MaterialTheme.typography.bodySmall)
        }
    }
}
