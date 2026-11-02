package org.iz.navigation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.iz.navigation.navigation.EngineReadiness
import org.iz.navigation.navigation.LocalVoiceCapability
import org.iz.navigation.navigation.NavigationState
import org.iz.navigation.navigation.PreparedRouteState
import org.iz.navigation.navigation.SpeechStatus
import org.iz.navigation.offline.canResumeOfflinePack
import org.iz.navigation.offline.OPEN_FREE_MAP_STYLE
import org.iz.navigation.offline.OfflinePackStatus
import org.iz.navigation.weather.PlannedRoute
import org.iz.navigation.weather.RouteProvider

/** The displayed preview wins, even if it cannot be prepared; never select a hidden live route. */
internal fun preparedRouteCandidate(ui: PhoneDirectionsState, nav: NavigationState): PlannedRoute? =
    (ui.preview ?: nav.route)?.takeIf { it.provider == RouteProvider.VALHALLA && it.maneuvers.isNotEmpty() }

internal fun preparedRouteToken(entryId: Long, revision: Long, ui: PhoneDirectionsState,
    nav: NavigationState, prepared: PreparedRouteState): PreparedRouteUiToken = PreparedRouteUiToken(
    ownerId = "$entryId:$revision:${ui.recordJourney}:${prepared.fingerprint.orEmpty()}",
    sessionId = nav.sessionId,
    preparedRouteId = prepared.route?.id ?: preparedRouteCandidate(ui, nav)?.id,
    routeRevision = nav.routeRevision,
    generation = prepared.generation,
    packageId = prepared.pack?.let { pack ->
        "${pack.request.routeId}:${pack.request.sourceFingerprint}:${pack.generation}:${pack.regionId}:${pack.status}"
    },
)

/** Projection uses stored facts. No inferred totals, verification time, voice or coverage claims. */
internal fun preparedRouteData(token: PreparedRouteUiToken, ui: PhoneDirectionsState,
    nav: NavigationState, prepared: PreparedRouteState, locked: Boolean, mapReady: Boolean): PreparedRoutePanelData {
    val pack = prepared.pack
    val route = prepared.route
    val routeReady = route != null && route.provider == RouteProvider.VALHALLA && route.maneuvers.isNotEmpty() &&
        prepared.fingerprint?.matches(Regex("[a-fA-F0-9]{64}")) == true
    val matchingPack = routeReady && pack?.request?.routeId == route?.id &&
        pack?.request?.sourceFingerprint == prepared.fingerprint
    val inUse = prepared.inUse || (nav.route != null && prepared.fingerprint != null &&
        nav.preparedRouteFingerprint == prepared.fingerprint)
    val available = !locked && !prepared.busy && !ui.busy && !nav.loading
    val actions = buildSet {
        if (available) {
            if (!inUse && pack == null && preparedRouteCandidate(ui, nav) != null)
                add(PreparedRouteUiAction.PREPARE_ROUTE)
            if (!inUse && routeReady && pack?.regionId == null && pack?.status != OfflinePackStatus.DOWNLOADING)
                add(PreparedRouteUiAction.SET_CORRIDOR)
            if (matchingPack && pack != null) {
                when (pack.status) {
                    OfflinePackStatus.PREPARED -> if (prepared.selection != null) add(PreparedRouteUiAction.DOWNLOAD_MAP)
                    OfflinePackStatus.DOWNLOADING -> {
                        add(PreparedRouteUiAction.PAUSE_DOWNLOAD)
                        add(PreparedRouteUiAction.CANCEL_DOWNLOAD)
                    }
                    OfflinePackStatus.PAUSED, OfflinePackStatus.PARTIAL, OfflinePackStatus.ERROR ->
                        if (canResumeOfflinePack(pack)) add(PreparedRouteUiAction.RESUME_DOWNLOAD)
                    OfflinePackStatus.READY -> Unit
                }
            }
            if (pack != null && !inUse) add(PreparedRouteUiAction.DELETE_MAP)
            if (routeReady && !inUse && nav.trackFollow == null && !nav.simulation)
                add(PreparedRouteUiAction.USE_PREPARED)
        }
    }
    val mapState = when {
        pack == null -> PreparedMapUiState.NOT_PREPARED
        !matchingPack -> PreparedMapUiState.STALE
        else -> when (pack.status) {
            OfflinePackStatus.PREPARED -> PreparedMapUiState.NOT_PREPARED
            OfflinePackStatus.DOWNLOADING -> PreparedMapUiState.DOWNLOADING
            OfflinePackStatus.PAUSED -> PreparedMapUiState.PAUSED
            OfflinePackStatus.PARTIAL -> PreparedMapUiState.INTERRUPTED
            OfflinePackStatus.ERROR -> PreparedMapUiState.FAILED
            OfflinePackStatus.READY -> if (mapReady) PreparedMapUiState.READY else PreparedMapUiState.UNKNOWN
        }
    }
    return PreparedRoutePanelData(
        token = token, route = route ?: preparedRouteCandidate(ui, nav), routeReady = routeReady,
        mapState = mapState,
        corridor = prepared.selection?.let { PreparedCorridorUiSelection(it.fromMeters, it.toMeters, it.halfWidthMeters) },
        allowedActions = actions,
        downloadedBytes = pack?.downloadedBytes,
        totalTiles = pack?.tileCount?.toLong(),
        source = pack?.request?.styleUrl?.let { if (it == OPEN_FREE_MAP_STYLE) "OpenFreeMap" else it },
        activeUse = inUse,
        error = prepared.error ?: pack?.error,
    )
}

@Composable
internal fun PreparedRouteControls(vm: PhoneNavigationViewModel, entryId: Long, ui: PhoneDirectionsState,
    nav: NavigationState, commandBusy: Boolean, speechStatus: SpeechStatus,
    onUse: (PreparedRouteUiToken) -> Unit) {
    var showNotices by remember(entryId, vm.preparedRoutes.maps) { mutableStateOf(false) }
    val stored by vm.preparedRoutes.state.collectAsStateWithLifecycle()
    val pack by vm.preparedRoutes.maps.state.collectAsStateWithLifecycle()
    val prepared = stored.copy(pack = pack)
    val token = preparedRouteToken(entryId, vm.directions.revisionToken, ui, nav, prepared)
    val data = preparedRouteData(token, ui, nav, prepared, commandBusy, vm.preparedRoutes.mapReady())
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(preparedLocalVoiceLabel(speechStatus), style = MaterialTheme.typography.bodyMedium)
        Text("Sesin duyulduğunu ses denemesiyle kendin kontrol et. Rota ve harita hazırlığı ses hazırlığını doğrulamaz.",
            style = MaterialTheme.typography.bodySmall)
        preparedRouteCandidate(ui, nav)?.let { candidate ->
            Text("Hazırlama adayı: ${candidate.stops.lastOrNull()?.label ?: "Hedef bilgisi yok"}. Rotayı hazırla bu rotayı kaydeder.",
                style = MaterialTheme.typography.bodySmall)
        } ?: Text("Rota hazırlamak için dönüş talimatlı bir Valhalla rotası önizle.",
            style = MaterialTheme.typography.bodySmall)
        if (prepared.busy) Text("Rota hazırlanıyor.")
        Text("Koridor genişliği yolun her yanında 50–1000 m olmalı.", style = MaterialTheme.typography.bodySmall)
        if (pack != null && prepared.selection == null)
            Text("Kaydedilmiş alanın başlangıç ve bitiş seçimi mevcut değil. Yeni alan seçimi için önce mevcut alanı kaldır.",
                style = MaterialTheme.typography.bodySmall)
        if (nav.trackFollow != null) Text("Hazır rotayı kullanmadan önce açık GPX takibini bitir.",
            style = MaterialTheme.typography.bodySmall)
        PreparedRoutePanel(data, PreparedRoutePanelCallbacks(
            onCorridorSelected = { owner, selection -> vm.preparedAction(entryId, owner, PreparedRouteUiAction.SET_CORRIDOR, selection) },
            onAction = { owner, action ->
                if (action == PreparedRouteUiAction.USE_PREPARED) onUse(owner)
                else vm.preparedAction(entryId, owner, action)
            },
        ))
        TextButton(onClick = { showNotices = true }, modifier = Modifier.sizeIn(minHeight = 48.dp)
            .testTag("offline-map-notices")) { Text("Harita ve yazı tipi lisansları") }
    }
    if (showNotices) OfflineMapNoticesDialog(vm.preparedRoutes.maps) { showNotices = false }
}

private fun preparedLocalVoiceLabel(status: SpeechStatus): String = when (status.localVoice) {
    LocalVoiceCapability.UNKNOWN -> "Yerel Türkçe ses hazırlığı doğrulanmadı."
    LocalVoiceCapability.AVAILABLE -> if (status.readiness == EngineReadiness.READY) "Yerel Türkçe ses hazır."
        else "Yerel Türkçe ses seçildi; ses motoru henüz hazır değil."
    LocalVoiceCapability.NETWORK_REQUIRED -> "Seçili Türkçe ses internet gerektiriyor."
    LocalVoiceCapability.MISSING -> "Yüklü yerel Türkçe ses bulunamadı."
    LocalVoiceCapability.SELECTION_FAILED -> "Yerel Türkçe ses seçilemedi."
}
