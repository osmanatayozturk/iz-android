package org.iz.navigation.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.iz.navigation.weather.PlannedRoute
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** UI facts only. preparedRouteId identifies the candidate, not the active replacement route.
 * Core revalidates owner/session/revision/generation/package before changing owned state. */
internal data class PreparedRouteUiToken(
    val ownerId: String, val sessionId: String?, val preparedRouteId: String?,
    val routeRevision: Long, val generation: Long, val packageId: String?,
)
/** Distances along the supplied route; halfWidthMeters is the width on EACH side of its line. */
internal data class PreparedCorridorUiSelection(val fromMeters: Double, val toMeters: Double, val halfWidthMeters: Double)
internal enum class PreparedMapUiState {
    UNKNOWN, NOT_PREPARED, SOURCE_UNAVAILABLE, DOWNLOADING, PAUSED, INTERRUPTED, VERIFYING,
    READY, STALE, OUTSIDE_COVERAGE, FAILED,
}
internal enum class PreparedRouteUiAction {
    PREPARE_ROUTE, SET_CORRIDOR, DOWNLOAD_MAP, PAUSE_DOWNLOAD, RESUME_DOWNLOAD,
    CANCEL_DOWNLOAD, DELETE_MAP, USE_PREPARED,
}
internal data class PreparedRoutePanelData(
    val token: PreparedRouteUiToken,
    val route: PlannedRoute?,
    val routeReady: Boolean?,
    val mapState: PreparedMapUiState,
    val corridor: PreparedCorridorUiSelection?,
    val allowedActions: Set<PreparedRouteUiAction>,
    val downloadedBytes: Long? = null,
    val totalBytes: Long? = null,
    val downloadedTiles: Long? = null,
    val totalTiles: Long? = null,
    val source: String? = null,
    val verifiedAt: Long? = null,
    val areaName: String? = null,
    val activeUse: Boolean? = null,
    val error: String? = null,
)
internal data class PreparedRoutePanelCallbacks(
    val onCorridorSelected: (PreparedRouteUiToken, PreparedCorridorUiSelection) -> Unit,
    val onAction: (PreparedRouteUiToken, PreparedRouteUiAction) -> Unit,
)

/** Caller supplies an existing scroll surface; rendering performs no I/O or background work. */
@Composable
internal fun PreparedRoutePanel(data: PreparedRoutePanelData, callbacks: PreparedRoutePanelCallbacks,
    modifier: Modifier = Modifier) {
    val current by rememberUpdatedState(data)
    val token = data.token
    val initialFrom = data.corridor?.fromMeters?.let { preparedInput(it / 1000) }.orEmpty()
    val initialTo = data.corridor?.toMeters?.let { preparedInput(it / 1000) }.orEmpty()
    val initialWidth = data.corridor?.halfWidthMeters?.let(::preparedInput).orEmpty()
    var from by remember(token, data.corridor) { mutableStateOf(initialFrom) }
    var to by remember(token, data.corridor) { mutableStateOf(initialTo) }
    var width by remember(token, data.corridor) { mutableStateOf(initialWidth) }
    var pending by remember(token) { mutableStateOf<PreparedRouteUiAction?>(null) }
    val dirty = from != initialFrom || to != initialTo || width != initialWidth
    val matchingRoute = data.route?.takeIf { it.id == token.preparedRouteId }
    val selection = preparedCorridorInput(from, to, width, matchingRoute?.distanceMeters)
    fun allowed(action: PreparedRouteUiAction, value: PreparedRoutePanelData = current): Boolean =
        value.token == token && value.token.ownerId.isNotBlank() && action in value.allowedActions && when (action) {
            PreparedRouteUiAction.DELETE_MAP -> value.activeUse == false && value.token.packageId?.isNotBlank() == true
            PreparedRouteUiAction.USE_PREPARED -> !dirty && value.routeReady == true &&
                value.route != null && value.route.id == value.token.preparedRouteId
            PreparedRouteUiAction.SET_CORRIDOR -> value.route != null && value.route.id == value.token.preparedRouteId
            PreparedRouteUiAction.DOWNLOAD_MAP -> !dirty && value.corridor != null && value.route != null &&
                value.route.id == value.token.preparedRouteId && value.mapState != PreparedMapUiState.SOURCE_UNAVAILABLE
            PreparedRouteUiAction.RESUME_DOWNLOAD -> value.mapState != PreparedMapUiState.SOURCE_UNAVAILABLE
            else -> true
        }
    fun dispatch(action: PreparedRouteUiAction) {
        if (allowed(action)) callbacks.onAction(token, action)
    }

    OutlinedCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Hazır rota ve harita alanı", style = MaterialTheme.typography.titleLarge)
            Text(when (data.routeReady) {
                true -> "Rota hazır."
                false -> "Rota çevrimdışı kullanım için hazırlanmadı."
                null -> "Rota hazırlığı doğrulanmadı."
            })
            matchingRoute?.let { route ->
                Text("Hedef: ${route.stops.last().label}")
                Text("Planlanan mesafe: ${preparedDistance(route.distanceMeters)}",
                    style = MaterialTheme.typography.bodySmall)
            }
            Text(preparedMapLabel(data.mapState))
            Text("Kaynak: ${data.source?.takeIf { it.isNotBlank() } ?: "Bilinmiyor"}",
                style = MaterialTheme.typography.bodySmall)
            Text(data.verifiedAt?.takeIf { it >= 0 }?.let { "Doğrulama: ${preparedTime(it)}" }
                ?: "Doğrulama zamanı bilinmiyor.", style = MaterialTheme.typography.bodySmall)
            if (data.downloadedBytes != null || data.totalBytes != null || data.mapState == PreparedMapUiState.DOWNLOADING)
                Text("Boyut: ${preparedBytes(data.downloadedBytes)} / ${preparedBytes(data.totalBytes)}")
            if (data.downloadedTiles != null || data.totalTiles != null)
                Text("Harita parçası: ${preparedCount(data.downloadedTiles)} / ${preparedCount(data.totalTiles)}",
                    style = MaterialTheme.typography.bodySmall)
            data.error?.takeIf { it.isNotBlank() }?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text("Seçili koridor", style = MaterialTheme.typography.titleMedium)
            Text("Başlangıç ve bitiş, rotanın başlangıcından uzaklıktır. Genişlik yolun her iki yanında uygulanır.",
                style = MaterialTheme.typography.bodySmall)
            if (matchingRoute == null) Text("Alan seçimi için rota bilgisi gerekiyor.", style = MaterialTheme.typography.bodySmall)
            val editable = allowed(PreparedRouteUiAction.SET_CORRIDOR)
            OutlinedTextField(from, { from = it }, label = { Text("Başlangıç (km)") }, singleLine = true,
                enabled = editable, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            OutlinedTextField(to, { to = it }, label = { Text("Bitiş (km)") }, singleLine = true,
                enabled = editable, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            OutlinedTextField(width, { width = it }, label = { Text("Yolun her yanında (m)") }, singleLine = true,
                enabled = editable, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            if (dirty && selection == null) Text("Başlangıç ve bitiş rota içinde olmalı; bitiş başlangıçtan sonra, genişlik sıfırdan büyük olmalı.",
                style = MaterialTheme.typography.bodySmall)
            if (dirty) Text("Yeni alan seçimini önce uygula.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = {
                if (allowed(PreparedRouteUiAction.SET_CORRIDOR) && selection != null) {
                    callbacks.onCorridorSelected(token, selection)
                    from = preparedInput(selection.fromMeters / 1000)
                    to = preparedInput(selection.toMeters / 1000)
                    width = preparedInput(selection.halfWidthMeters)
                }
            }, enabled = editable && selection != null && dirty, modifier = Modifier.sizeIn(minHeight = 48.dp)) {
                Text("Alanı seç")
            }
            OutlinedButton(onClick = { dispatch(PreparedRouteUiAction.PREPARE_ROUTE) },
                enabled = allowed(PreparedRouteUiAction.PREPARE_ROUTE), modifier = Modifier.sizeIn(minHeight = 48.dp)) {
                Text("Rotayı hazırla")
            }
            OutlinedButton(onClick = { dispatch(PreparedRouteUiAction.DOWNLOAD_MAP) },
                enabled = allowed(PreparedRouteUiAction.DOWNLOAD_MAP), modifier = Modifier.sizeIn(minHeight = 48.dp)) {
                Text("Harita alanını indir")
            }
            listOf(PreparedRouteUiAction.PAUSE_DOWNLOAD, PreparedRouteUiAction.RESUME_DOWNLOAD,
                PreparedRouteUiAction.CANCEL_DOWNLOAD).filter { it in data.allowedActions }.forEach { action ->
                OutlinedButton(onClick = { dispatch(action) }, enabled = allowed(action),
                    modifier = Modifier.sizeIn(minHeight = 48.dp)) {
                    Text(when (action) {
                        PreparedRouteUiAction.PAUSE_DOWNLOAD -> "İndirmeyi duraklat"
                        PreparedRouteUiAction.RESUME_DOWNLOAD -> "İndirmeye devam et"
                        else -> "İndirmeyi iptal et"
                    })
                }
            }
            Text("Harita yalnızca kaydedilmiş alanda görünür. Yeni rota için internet gerekir.",
                style = MaterialTheme.typography.bodySmall)
            Button(onClick = { if (allowed(PreparedRouteUiAction.USE_PREPARED)) pending = PreparedRouteUiAction.USE_PREPARED },
                enabled = allowed(PreparedRouteUiAction.USE_PREPARED), modifier = Modifier.sizeIn(minHeight = 48.dp)) {
                Text("Hazır rotayı kullan")
            }
            if (data.activeUse != false) Text(if (data.activeUse == true)
                "Bu harita alanı kullanılıyor. Yolculuk bittikten sonra kaldır."
                else "Harita alanının kullanım durumu doğrulanmadı.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { if (allowed(PreparedRouteUiAction.DELETE_MAP)) pending = PreparedRouteUiAction.DELETE_MAP },
                enabled = allowed(PreparedRouteUiAction.DELETE_MAP), modifier = Modifier.sizeIn(minHeight = 48.dp)) {
                Text("Harita alanını kaldır")
            }
        }
    }
    pending?.let { action ->
        val deleting = action == PreparedRouteUiAction.DELETE_MAP
        AlertDialog(onDismissRequest = { pending = null },
            title = { Text(if (deleting) "Harita alanı kaldırılsın mı?" else "Hazır rota kullanılsın mı?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (deleting) Text("${data.areaName?.takeIf { it.isNotBlank() } ?: "Seçili harita alanı"} kaldırılacak. Yolculuk kayıtları korunur.")
                    else {
                        Text("Hedef: ${data.route?.stops?.lastOrNull()?.label ?: "Rota bilgisi yok"}")
                        if (data.mapState != PreparedMapUiState.READY) Text("Harita alanının tamamı hazır değil. Yalnızca mevcut alan görüntülenebilir.")
                        Text("Yeni rota hesaplamak için internet gerekir.")
                        data.route?.providerWarnings?.forEach { Text(it) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pending = null; dispatch(action) }, enabled = allowed(action)) {
                Text(if (deleting) "Harita alanını kaldır" else "Hazır rotayı kullan")
            } },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Vazgeç") } })
    }
}

private fun preparedCorridorInput(from: String, to: String, width: String, routeMeters: Double?): PreparedCorridorUiSelection? {
    fun parse(text: String) = text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }
    val start = parse(from)?.times(1000)?.takeIf { it.isFinite() && it >= 0 } ?: return null
    val end = parse(to)?.times(1000)?.takeIf { it.isFinite() } ?: return null
    val half = parse(width)?.takeIf { it > 0 } ?: return null
    val length = routeMeters?.takeIf { it.isFinite() && it >= 0 } ?: return null
    val boundedEnd = if (end > length && end - length <= 0.000001) length else end
    return if (boundedEnd > start && boundedEnd <= length) PreparedCorridorUiSelection(start, boundedEnd, half) else null
}
private fun preparedInput(value: Double): String = if (value.isFinite() && value >= 0)
    value.toString().removeSuffix(".0") else ""
private fun preparedDistance(value: Double): String = if (value.isFinite() && value >= 0)
    String.format(Locale.forLanguageTag("tr-TR"), "%.2f km", value / 1000) else "Bilinmiyor"
private fun preparedCount(value: Long?): String = value?.takeIf { it >= 0 }?.toString() ?: "Bilinmiyor"
private fun preparedBytes(value: Long?): String = value?.takeIf { it >= 0 }?.let {
    when {
        it < 1024 -> "$it B"
        it < 1_048_576 -> String.format(Locale.forLanguageTag("tr-TR"), "%.1f KiB", it / 1024.0)
        else -> String.format(Locale.forLanguageTag("tr-TR"), "%.1f MiB", it / 1_048_576.0)
    }
} ?: "Bilinmiyor"
private fun preparedTime(at: Long): String =
    SimpleDateFormat("d MMM yyyy HH:mm", Locale.forLanguageTag("tr-TR")).format(Date(at))
private fun preparedMapLabel(state: PreparedMapUiState): String = when (state) {
    PreparedMapUiState.UNKNOWN -> "Harita alanının durumu doğrulanmadı."
    PreparedMapUiState.NOT_PREPARED -> "Harita alanı hazırlanmadı."
    PreparedMapUiState.SOURCE_UNAVAILABLE -> "Bu kaynakla harita alanı indirilemiyor."
    PreparedMapUiState.DOWNLOADING -> "Harita alanı indiriliyor."
    PreparedMapUiState.PAUSED -> "İndirme duraklatıldı."
    PreparedMapUiState.INTERRUPTED -> "İndirme yarım kaldı."
    PreparedMapUiState.VERIFYING -> "Harita alanı doğrulanıyor."
    PreparedMapUiState.READY -> "Seçili harita alanı hazır."
    PreparedMapUiState.STALE -> "Harita alanı güncel değil."
    PreparedMapUiState.OUTSIDE_COVERAGE -> "Bu bölüm kaydedilmiş harita alanının dışında."
    PreparedMapUiState.FAILED -> "Harita alanı hazırlanamadı. Mevcut rota korundu."
}
