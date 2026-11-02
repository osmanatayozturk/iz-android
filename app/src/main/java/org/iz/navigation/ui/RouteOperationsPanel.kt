package org.iz.navigation.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.iz.navigation.weather.PlannedRoute
import org.iz.navigation.weather.RouteProvider
import org.iz.navigation.weather.RouteStop
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Presentation identities are opaque to UI. Core revalidates every callback against its live owner. */
internal data class RouteOperationsUiToken(
    val ownerId: String, val sessionId: String?, val routeId: String?, val routeRevision: Long, val generation: Long,
)
internal enum class RouteOnlineUiState { UNKNOWN, OFFLINE, AVAILABLE, UNSUPPORTED }
internal enum class RouteOperationUiAction { AVOID, SKIP, LOOKUP_FUEL, LOOKUP_REST, SELECT_POI, CONFIRM, CANCEL, PAUSE, RESUME }
internal enum class RoutePreviewUiReason { AVOID, SKIP, POI }
internal data class RouteAvoidUiTarget(val id: String, val instruction: String)
/** The producer supplies eligible INTERMEDIATE stops only; never the final destination. */
internal data class RouteStopUiTarget(val id: String, val stop: RouteStop)
internal data class RouteOperationUiPreview(
    val owner: RouteOperationsUiToken, val requestId: String, val route: PlannedRoute, val reason: RoutePreviewUiReason,
)
internal enum class RoutePoiUiKind { FUEL, REST }
internal enum class RoutePoiUiState { IDLE, LOADING, RESULTS, EMPTY, ERROR, CACHED }
internal enum class RoutePoiUiDistanceKind { FROM_ROUTE, AHEAD_ON_ROUTE, DRIVING_DETOUR }
internal data class RoutePoiUiDistance(val meters: Double, val kind: RoutePoiUiDistanceKind)
internal data class RoutePoiUiResult(val id: String, val name: String, val address: String?, val distance: RoutePoiUiDistance?)
internal data class RoutePoiUiData(
    val owner: RouteOperationsUiToken, val requestId: String, val kind: RoutePoiUiKind,
    val state: RoutePoiUiState, val results: List<RoutePoiUiResult>, val fetchedAt: Long? = null, val error: String? = null,
)
internal data class RouteWeatherUiFact(
    val routeId: String, val routeRevision: Long, val summary: String, val current: Boolean?, val fetchedAt: Long? = null,
)
internal data class RouteOperationsPanelData(
    val token: RouteOperationsUiToken,
    val route: PlannedRoute?,
    val paused: Boolean?,
    val online: RouteOnlineUiState,
    val allowedActions: Set<RouteOperationUiAction>,
    val maneuvers: List<RouteAvoidUiTarget> = emptyList(),
    val intermediateStops: List<RouteStopUiTarget> = emptyList(),
    val preview: RouteOperationUiPreview? = null,
    val pendingRequestId: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val arrivalAt: Long? = null,
    val arrivalCurrent: Boolean? = null,
    val timingUpdating: Boolean = false,
    val weather: RouteWeatherUiFact? = null,
    val poi: RoutePoiUiData? = null,
)
internal data class RouteOperationsPanelCallbacks(
    val onAvoid: (RouteOperationsUiToken, String) -> Unit,
    val onSkip: (RouteOperationsUiToken, String) -> Unit,
    val onLookup: (RouteOperationsUiToken, RoutePoiUiKind) -> Unit,
    val onSelectPoi: (RouteOperationsUiToken, String, String) -> Unit,
    val onConfirm: (RouteOperationsUiToken, String, String) -> Unit,
    val onCancel: (RouteOperationsUiToken, String) -> Unit,
    val onPause: (RouteOperationsUiToken) -> Unit,
    val onResume: (RouteOperationsUiToken) -> Unit,
)

/** Caller owns scrolling and all producers. Callbacks are intents, never successful outcomes. */
@Composable
internal fun RouteOperationsPanel(data: RouteOperationsPanelData, callbacks: RouteOperationsPanelCallbacks,
    modifier: Modifier = Modifier) {
    val latest by rememberUpdatedState(data)
    val token = data.token
    val route = data.route?.takeIf { it.id == token.routeId }
    val preview = data.preview?.takeIf { it.owner == token && it.requestId.isNotBlank() }
    val poi = data.poi?.takeIf { it.owner == token }
    var segmentId by remember(token, data.maneuvers) { mutableStateOf<String?>(null) }
    var stopId by remember(token, data.intermediateStops) { mutableStateOf<String?>(null) }
    var poiId by remember(token, poi?.requestId, poi?.results) { mutableStateOf<String?>(null) }
    val segment = data.maneuvers.firstOrNull { it.id == segmentId }
    val stop = data.intermediateStops.firstOrNull { it.id == stopId }
    val selectedPoi = poi?.results?.firstOrNull { it.id == poiId }
    fun dispatch(action: RouteOperationUiAction, block: (RouteOperationsPanelData) -> Unit) {
        val current = latest
        if (current.token == token && routeOperationAllowed(current, action)) block(current)
    }

    OutlinedCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Rota işlemleri", style = MaterialTheme.typography.titleLarge)
            if (route == null) Text("Geçerli rota bilgisi bekleniyor.")
            else Text("Mevcut hedef: ${route.stops.last().label}")
            Text(when (data.online) {
                RouteOnlineUiState.UNKNOWN -> "Çevrimiçi rota işlemleri doğrulanmadı."
                RouteOnlineUiState.OFFLINE -> "Yeni rota için internet gerekiyor."
                RouteOnlineUiState.AVAILABLE -> "İnternet bağlantısı var. İşlemin desteklenmesi rota servisiyle ayrıca doğrulanır."
                RouteOnlineUiState.UNSUPPORTED -> "Seçili işlem bu rota sağlayıcısında kullanılamıyor."
            })
            if (data.busy) Text("Rota işlemi sürüyor. Mevcut rota korunuyor.")
            data.error?.takeIf { it.isNotBlank() }?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text(when (data.paused) {
                true -> "Duraklama sürüyor. Varış tahmini bekliyor."
                false -> "Duraklama kapalı."
                null -> "Duraklama durumu doğrulanmadı."
            })
            data.arrivalAt?.takeIf { route != null && it >= 0 }?.let { at ->
                val label = when {
                    data.paused == true -> "Duraklama öncesi son tahmin"
                    data.paused == false && data.arrivalCurrent == true -> "Tahmini varış"
                    else -> "Son tahmini varış"
                }
                Text("$label: ${routeOperationTime(at)}", style = MaterialTheme.typography.bodySmall)
            }
            if (route != null && data.timingUpdating) Text("Varış tahmini güncelleniyor.", style = MaterialTheme.typography.bodySmall)
            if (data.paused == true) Text("Duraklama nedeniyle ilerideki varış saatleri belirsiz.",
                style = MaterialTheme.typography.bodySmall)
            val weather = data.weather?.takeIf { route != null && it.routeId == token.routeId && it.routeRevision == token.routeRevision }
            if (weather != null) {
                Text(when (weather.current) {
                    true -> "Rota hava durumu"
                    false -> "Son rota tahmini"
                    null -> "Rota tahmininin güncelliği doğrulanmadı."
                },
                    style = MaterialTheme.typography.titleSmall)
                Text(weather.summary)
                weather.fetchedAt?.takeIf { it >= 0 }?.let { Text("Tahmin zamanı: ${routeOperationTime(it)}",
                    style = MaterialTheme.typography.bodySmall) }
            } else Text("Bu rotanın hava verisi bekleniyor.", style = MaterialTheme.typography.bodySmall)
            if (data.paused == true) {
                OutlinedButton(onClick = { dispatch(RouteOperationUiAction.RESUME) { callbacks.onResume(token) } },
                    enabled = routeOperationAllowed(data, RouteOperationUiAction.RESUME), modifier = Modifier.sizeIn(minHeight = 48.dp)) {
                    Text("Duraklamayı bitir")
                }
            } else {
                OutlinedButton(onClick = { dispatch(RouteOperationUiAction.PAUSE) { callbacks.onPause(token) } },
                    enabled = routeOperationAllowed(data, RouteOperationUiAction.PAUSE), modifier = Modifier.sizeIn(minHeight = 48.dp)) {
                    Text("Duraklamayı başlat")
                }
            }
            Text("Bu işlemler yolculuğun kayıt durumunu değiştirmez.", style = MaterialTheme.typography.bodySmall)
            OperationChoice("Kullanılmayacak bölüm", segmentId,
                if (route == null) emptyList() else data.maneuvers.map { it.id to it.instruction }) { segmentId = it }
            OutlinedButton(onClick = {
                val id = segment?.id ?: return@OutlinedButton
                dispatch(RouteOperationUiAction.AVOID) { current ->
                    if (current.maneuvers.any { it.id == id }) callbacks.onAvoid(token, id)
                }
            }, enabled = segment != null && routeOperationAllowed(data, RouteOperationUiAction.AVOID),
                modifier = Modifier.sizeIn(minHeight = 48.dp)) { Text("Bu bölümü kullanma") }
            OperationChoice("Atlanacak ara durak", stopId,
                if (route == null) emptyList() else data.intermediateStops.map { it.id to it.stop.label }) { stopId = it }
            if (data.intermediateStops.isEmpty()) Text("Atlanacak ara durak yok.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = {
                val id = stop?.id ?: return@OutlinedButton
                dispatch(RouteOperationUiAction.SKIP) { current ->
                    if (current.intermediateStops.any { it.id == id }) callbacks.onSkip(token, id)
                }
            }, enabled = stop != null && routeOperationAllowed(data, RouteOperationUiAction.SKIP),
                modifier = Modifier.sizeIn(minHeight = 48.dp)) { Text("Bu durağı atla") }
            Text("Değişiklik önce önizlenir. Onaylayana kadar mevcut rota korunur.", style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            Text("Rotaya yakın yerler", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = { dispatch(RouteOperationUiAction.LOOKUP_FUEL) { callbacks.onLookup(token, RoutePoiUiKind.FUEL) } },
                enabled = routeOperationAllowed(data, RouteOperationUiAction.LOOKUP_FUEL), modifier = Modifier.sizeIn(minHeight = 48.dp)) {
                Text("Yakıt yeri ara")
            }
            OutlinedButton(onClick = { dispatch(RouteOperationUiAction.LOOKUP_REST) { callbacks.onLookup(token, RoutePoiUiKind.REST) } },
                enabled = routeOperationAllowed(data, RouteOperationUiAction.LOOKUP_REST), modifier = Modifier.sizeIn(minHeight = 48.dp)) {
                Text("Mola yeri ara")
            }
            if (poi != null) {
                Text(when (poi.state) {
                    RoutePoiUiState.IDLE -> if (poi.results.isEmpty()) "Arama başlatılmadı." else "Son sonuçlar gösteriliyor."
                    RoutePoiUiState.LOADING -> if (poi.results.isEmpty()) "Yerler aranıyor." else "Yerler aranıyor. Son sonuçlar gösteriliyor."
                    RoutePoiUiState.RESULTS -> if (poi.results.isEmpty()) "Sonuç bulunamadı." else "Arama sonuçları"
                    RoutePoiUiState.EMPTY -> if (poi.results.isEmpty()) "Sonuç bulunamadı." else "Yeni sonuç bulunamadı. Son sonuçlar gösteriliyor."
                    RoutePoiUiState.ERROR -> if (poi.results.isEmpty()) "Yerler alınamadı." else "Yerler yenilenemedi. Son sonuçlar gösteriliyor."
                    RoutePoiUiState.CACHED -> "Son sonuçlar gösteriliyor."
                })
                poi.fetchedAt?.takeIf { it >= 0 }?.let { Text("Sonuç zamanı: ${routeOperationTime(it)}",
                    style = MaterialTheme.typography.bodySmall) }
                poi.error?.takeIf { it.isNotBlank() }?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 240.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(poi.results, key = { it.id }) { item ->
                        OutlinedCard(onClick = { poiId = item.id }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(item.name, style = MaterialTheme.typography.titleSmall)
                                Text(item.address?.takeIf { it.isNotBlank() } ?: "Adres bilgisi yok",
                                    style = MaterialTheme.typography.bodySmall)
                                Text(routePoiDistance(item.distance), style = MaterialTheme.typography.bodySmall)
                                if (item.distance?.kind != RoutePoiUiDistanceKind.DRIVING_DETOUR)
                                    Text("Sapma mesafesi hesaplanmadı.", style = MaterialTheme.typography.bodySmall)
                                Text("Açık olduğu doğrulanmadı.", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                selectedPoi?.let { item ->
                    Text("Seçili yer: ${item.name}")
                    OutlinedButton(onClick = {
                        dispatch(RouteOperationUiAction.SELECT_POI) { current ->
                            val results = current.poi?.takeIf { it.owner == token && it.requestId == poi.requestId }
                            if (results?.results?.any { it.id == item.id } == true)
                                callbacks.onSelectPoi(token, poi.requestId, item.id)
                        }
                    }, enabled = routeOperationAllowed(data, RouteOperationUiAction.SELECT_POI),
                        modifier = Modifier.sizeIn(minHeight = 48.dp)) { Text("Rotada önizle") }
                }
            } else if (data.poi != null) Text("Bu rotanın yer bilgisi bekleniyor.")
            if (preview != null) {
                HorizontalDivider()
                Text("Yeni rota önizlemesi", style = MaterialTheme.typography.titleMedium)
                Text("Hedef: ${preview.route.stops.last().label}")
                Text("Planlanan mesafe: ${routeOperationDistance(preview.route.distanceMeters)}")
                Text("Planlanan süre: ${routeOperationDuration(preview.route.durationSeconds)}")
                Text("Sağlayıcı: ${if (preview.route.provider == RouteProvider.TOMTOM) "TomTom" else "Valhalla"}")
                preview.route.providerWarnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text("Onaylayana kadar mevcut rota korunur. Kayıt durumu değişmez.",
                    style = MaterialTheme.typography.bodySmall)
                Button(onClick = {
                    dispatch(RouteOperationUiAction.CONFIRM) { current ->
                        if (current.preview?.let { it.owner == token && it.requestId == preview.requestId &&
                                it.route.id == preview.route.id } == true)
                            callbacks.onConfirm(token, preview.requestId, preview.route.id)
                    }
                }, enabled = routeOperationAllowed(data, RouteOperationUiAction.CONFIRM),
                    modifier = Modifier.sizeIn(minHeight = 48.dp)) {
                    Text(when (preview.reason) {
                        RoutePreviewUiReason.AVOID -> "Yeni rotayı kullan"
                        RoutePreviewUiReason.SKIP -> "Durağı atla"
                        RoutePreviewUiReason.POI -> "Durağı ekle"
                    })
                }
            } else if (data.preview != null) Text("Önizleme güncel değil. Mevcut rota korunuyor.")
            val cancellationId = preview?.requestId ?: data.pendingRequestId?.takeIf { it.isNotBlank() }
            if (cancellationId != null) {
                TextButton(onClick = {
                    dispatch(RouteOperationUiAction.CANCEL) { current ->
                        val currentId = current.preview?.takeIf { it.owner == token && it.requestId.isNotBlank() }?.requestId ?: current.pendingRequestId
                        if (currentId == cancellationId) callbacks.onCancel(token, cancellationId)
                    }
                }, enabled = routeOperationAllowed(data, RouteOperationUiAction.CANCEL),
                    modifier = Modifier.sizeIn(minHeight = 48.dp)) { Text("İşlemden vazgeç") }
            }
        }
    }
}

private fun routeOperationAllowed(data: RouteOperationsPanelData, action: RouteOperationUiAction): Boolean {
    if (data.token.ownerId.isBlank() || action !in data.allowedActions) return false
    if (data.busy && action !in setOf(RouteOperationUiAction.CANCEL, RouteOperationUiAction.PAUSE, RouteOperationUiAction.RESUME)) return false
    if (action in setOf(RouteOperationUiAction.PAUSE, RouteOperationUiAction.RESUME))
        return data.token.sessionId?.isNotBlank() == true &&
            (if (action == RouteOperationUiAction.PAUSE) data.paused == false else data.paused == true)
    if (action == RouteOperationUiAction.CANCEL)
        return data.pendingRequestId?.isNotBlank() == true || data.preview?.let { it.owner == data.token && it.requestId.isNotBlank() } == true
    if (data.route?.id != data.token.routeId || data.route == null) return false
    if (action == RouteOperationUiAction.CONFIRM)
        return data.preview?.let { it.owner == data.token && it.requestId.isNotBlank() } == true
    return action !in setOf(RouteOperationUiAction.AVOID, RouteOperationUiAction.SKIP, RouteOperationUiAction.SELECT_POI) ||
        data.online == RouteOnlineUiState.AVAILABLE
}

@Composable
private fun OperationChoice(label: String, selected: String?, choices: List<Pair<String, String>>, onSelect: (String) -> Unit) {
    var expanded by remember(choices) { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { expanded = true }, enabled = choices.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth()) {
                Text(label, style = MaterialTheme.typography.labelMedium)
                Text(choices.firstOrNull { it.first == selected }?.second ?: "Seç")
            }
        }
        DropdownMenu(expanded, { expanded = false }, modifier = Modifier.heightIn(max = 320.dp)) {
            choices.forEach { (id, title) ->
                DropdownMenuItem(text = { Text(title) }, onClick = { expanded = false; onSelect(id) })
            }
        }
    }
}
private fun routeOperationTime(at: Long): String =
    SimpleDateFormat("d MMM yyyy HH:mm", Locale.forLanguageTag("tr-TR")).format(Date(at))
private fun routeOperationDistance(value: Double): String = value.takeIf { it.isFinite() && it >= 0 }
    ?.let { String.format(Locale.forLanguageTag("tr-TR"), "%.2f km", it / 1000) } ?: "Bilinmiyor"
private fun routeOperationDuration(seconds: Double): String = seconds.takeIf { it.isFinite() && it >= 0 }
    ?.let { if (it < 60) "${it.toLong()} sn" else "${(it / 60).toLong()} dk" } ?: "Bilinmiyor"
private fun routePoiDistance(value: RoutePoiUiDistance?): String {
    val fact = value ?: return "Yakınlık bilgisi yok."
    val meters = fact.meters.takeIf { it.isFinite() && it >= 0 } ?: return "Yakınlık bilgisi yok."
    val label = when (fact.kind) {
        RoutePoiUiDistanceKind.FROM_ROUTE -> "Rotaya uzaklık"
        RoutePoiUiDistanceKind.AHEAD_ON_ROUTE -> "Rotada ileride"
        RoutePoiUiDistanceKind.DRIVING_DETOUR -> "Ek sürüş mesafesi"
    }
    return "$label: ${routeOperationDistance(meters)}"
}
