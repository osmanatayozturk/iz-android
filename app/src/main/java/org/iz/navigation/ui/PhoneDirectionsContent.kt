@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package org.iz.navigation.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.iz.navigation.data.TrackPoint
import org.iz.navigation.data.Transport
import org.iz.navigation.integration.WeatherRouteMap
import org.iz.navigation.navigation.NavigationState
import org.iz.navigation.weather.PlannedRoute
import org.iz.navigation.weather.RouteProvider
import org.iz.navigation.weather.RouteStop
import org.iz.navigation.weather.RoutePreferences
import org.iz.navigation.weather.geometryKey
import org.iz.navigation.weather.matrixUnavailableReason
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

@Composable
internal fun PhoneDirectionsContent(
    ui: PhoneDirectionsState,
    nav: NavigationState,
    points: List<TrackPoint>,
    commandBusy: Boolean,
    onClose: () -> Unit,
    chooseOrigin: () -> Unit,
    chooseDestination: () -> Unit,
    currentOrigin: () -> Unit,
    transport: (Transport) -> Unit,
    preview: () -> Unit,
    start: () -> Unit,
    freeDrive: () -> Unit,
    finish: () -> Unit,
    mute: () -> Unit,
    stopGuidance: () -> Unit,
    showLive: () -> Unit,
    trafficSettings: () -> Unit,
    lastDestination: (() -> Unit)?,
    permissions: () -> Unit,
    showMap: Boolean = true,
    recordJourney: (Boolean) -> Unit = {},
    stopRecording: () -> Unit = {},
    addVia: () -> Unit = {},
    removeVia: (Int) -> Unit = {},
    onPrepareGroup: (() -> Unit)? = null,
    suggestStopOrder: () -> Unit = {},
    acceptStopOrder: () -> Unit = {},
    dismissStopOrder: () -> Unit = {},
    setPreferences: (RoutePreferences) -> Unit = {},
    requestAlternatives: () -> Unit = {},
    selectAlternative: (String) -> Unit = {},
    saveRoute: (() -> Unit)? = null,
) {
    val listState = rememberLazyListState()
    val shownRoute = ui.preview ?: nav.route
    val live = ui.preview == null && (nav.sessionId != null || nav.guidance || nav.recording || nav.locationActive)
    val locked = ui.starting || commandBusy
    var turnsRoute by remember { mutableStateOf<PlannedRoute?>(null) }
    LaunchedEffect(ui.startedCount) { if (ui.startedCount > 0) listState.animateScrollToItem(0) }
    LaunchedEffect(nav.recording) { if (nav.recording) listState.animateScrollToItem(0) }
    LazyColumn(Modifier.fillMaxSize().testTag("navigation-screen"), state = listState,
        contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Yol tarifi", style = MaterialTheme.typography.headlineSmall)
                    Text(when {
                        nav.simulation -> "Simülasyon · Günlüğe kaydedilmez"
                        nav.recording -> "${nav.journey?.transport?.label().orEmpty()} · Kayıt açık"
                        else -> "İki nokta seç, rotanı önizle."
                    }, style = MaterialTheme.typography.bodyMedium)
                }
                TextButton(onClick = onClose, modifier = Modifier.testTag("close-directions-planner")) { Text("Haritaya dön") }
            }
        }
        if (showMap) item {
            WeatherRouteMap(
                route = shownRoute, assessment = null,
                modifier = Modifier.fillMaxWidth().height(290.dp).testTag("directions-route-map"),
                stops = shownRoute?.stops ?: if (live) emptyList() else ui.stops,
                liveCoordinate = nav.fix?.coordinate ?: ui.origin?.takeIf { ui.originCurrent }?.coordinate,
                recordedPoints = points, gpsStale = nav.gpsStale,
                singleStopLabel = if (ui.origin == null && ui.destination != null) "B" else "A",
                title = if (live) "Canlı yol tarifi" else "Rota önizleme",
                cameraIdentity = if (live) "live:${nav.journey?.id}:${ui.startedCount}:${shownRoute?.stops?.lastOrNull()?.coordinate}"
                    else ui.preview?.id,
                overlay = {
                    Surface(Modifier.align(Alignment.TopStart).padding(start = 10.dp, top = 10.dp, end = 60.dp)
                        .widthIn(max = 360.dp), shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surface.copy(alpha = .96f)) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(if (live) "Canlı rota" else "Rota önizleme", Modifier.weight(1f),
                                    style = MaterialTheme.typography.labelMedium)
                                DirectionsMapActions(
                                    route = shownRoute, nav = nav, enabled = !locked,
                                    turns = { turnsRoute = shownRoute }, mute = mute, stop = stopGuidance, finish = finish,
                                )
                            }
                            if (live) {
                                Text(navigationInstruction(nav), style = MaterialTheme.typography.titleSmall, maxLines = 3,
                                    overflow = TextOverflow.Ellipsis)
                                if (nav.gpsStale) Text("GPS konumu bekleniyor", style = MaterialTheme.typography.labelSmall)
                            }
                            shownRoute?.let { route ->
                                RouteTimingText(route, nav.takeIf { live }, preview = !live)
                                RouteTrafficText(route)
                            } ?: Text(if (live) "Serbest sürüş · ${points.size} kayıtlı konum" else "A başlangıç · B varış",
                                style = MaterialTheme.typography.labelLarge)
                        }
                    }
                },
            )
        }
        if (nav.sessionId != null || nav.recording || nav.guidance || nav.locationActive) item {
            Text(navigationInstruction(nav), style = MaterialTheme.typography.titleMedium)
            if (ui.preview != null) TextButton(showLive) { Text("Canlı rotaya dön") }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(mute) { Text(if (nav.muted) "Sesi aç" else "Sesi kapat") }
                if (nav.guidance) OutlinedButton(stopGuidance, enabled = !locked) { Text("Yönlendirmeyi durdur") }
                if (nav.recording) OutlinedButton(stopRecording, enabled = !locked, modifier = Modifier.testTag("stop-navigation-recording")) { Text("Kaydı durdur") }
                Button(finish, enabled = !locked, modifier = Modifier.testTag("finish-navigation-journey")) { Text("Yolculuğu bitir") }
            }
            Text("Yönlendirmeyi durdur yalnızca yol tarifini, Kaydı durdur yalnızca günlüğü durdurur. Yolculuğu bitir hepsini ve konum paylaşımını kapatır.", style = MaterialTheme.typography.bodySmall)
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(Transport.CAR, Transport.MOTORCYCLE, Transport.PASSENGER, Transport.BICYCLE, Transport.WALK, Transport.RUN).forEach { mode ->
                    FilterChip(selected = ui.transport == mode, onClick = { transport(mode) },
                        label = { Text(mode.label()) }, enabled = !locked, modifier = Modifier.testTag("directions-mode-${mode.name}"))
                }
            }
            if (nav.recording && nav.journey?.transport != ui.transport)
                Text("Açık yolculuk ${nav.journey?.transport?.label()}. Bu türle başlamak için önce mevcut yolculuğu bitir.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        item {
            DirectionEndpoint("A", "Başlangıç", ui.origin,
                if (ui.originCurrent) { if (ui.locating) "Mevcut konum alınıyor…" else "Mevcut konum" } else null,
                chooseOrigin, !locked, "directions-origin")
            TextButton(currentOrigin, enabled = !locked && !ui.locating, modifier = Modifier.testTag("directions-current-origin")) {
                Text("Mevcut konumumu kullan")
            }
            DirectionEndpoint("B", "Varış", ui.destination, null, chooseDestination, !locked, "directions-destination")
            ui.destination?.let { Text("Hedef: ${it.label}", style = MaterialTheme.typography.bodySmall) }
            ui.via.forEachIndexed { index, stop ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${index + 1}. ${stop.label}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton({ removeVia(index) }, enabled = !locked) { Text("Kaldır") }
                }
            }
            TextButton(addVia, enabled = !locked && ui.via.size < 3, modifier = Modifier.testTag("directions-add-via")) { Text("Ara durak ekle") }
            if (!ui.originCurrent) Text("Başlatınca önce A noktasına, ardından planlanan duraklara gidilir.", style = MaterialTheme.typography.bodySmall)
        }
        item {
            val activeMode = ui.transport in setOf(Transport.WALK, Transport.RUN, Transport.BICYCLE)
            Text("Rota tercihleri", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(ui.preferences.avoidHighways, { setPreferences(ui.preferences.copy(avoidHighways = !ui.preferences.avoidHighways)) },
                    enabled = !locked, label = { Text(if (activeMode) "Otoyola girme" else "Otoyoldan kaçın") }, modifier = Modifier.testTag("directions-avoid-highways"))
                if (!activeMode) FilterChip(ui.preferences.avoidTolls, { setPreferences(ui.preferences.copy(avoidTolls = !ui.preferences.avoidTolls)) },
                    enabled = !locked, label = { Text("Ücretli yoldan kaçın") }, modifier = Modifier.testTag("directions-avoid-tolls"))
                FilterChip(ui.preferences.avoidFerries, { setPreferences(ui.preferences.copy(avoidFerries = !ui.preferences.avoidFerries)) },
                    enabled = !locked, label = { Text("Feribottan kaçın") }, modifier = Modifier.testTag("directions-avoid-ferries"))
            }
            if (activeMode && ui.preferences.avoidHighways) Text("Otoyolsuz rota servis haritasından doğrulanır; doğrulanamazsa başlatılmaz. Harita verileri eksik olabilir.", style = MaterialTheme.typography.bodySmall)
            Text(if (activeMode) "Feribottan kaçınma tercihi mümkün olduğunda uygulanır; kesin feribot yasağı değildir."
                else "Otoyol, ücretli yol ve feribottan kaçınma tercihleri mümkün olduğunda uygulanır; kesin yol yasağı değildir.", style = MaterialTheme.typography.bodySmall)
            saveRoute?.let { OutlinedButton(it, enabled = ui.stops.size in 2..5 && !locked, modifier = Modifier.testTag("directions-save-route")) { Text("Rotayı kaydet") } }
        }
        if (ui.preview != null) item {
            OutlinedButton(requestAlternatives, enabled = !ui.busy && !locked, modifier = Modifier.testTag("directions-alternatives")) { Text("Alternatif rotalar") }
            ui.alternativeMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            ui.alternatives.forEachIndexed { index, route ->
                FilterChip(ui.preview.geometryKey() == route.geometryKey(), { selectAlternative(route.id) }, enabled = !ui.busy && !locked,
                    label = { Text("Rota ${index + 1} · ${formatDistance(route.distanceMeters)} · ${formatDuration(route.durationSeconds)}") },
                    modifier = Modifier.testTag("directions-route-choice-$index"))
            }
            ui.preview.providerWarnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = ui.recordJourney, onCheckedChange = recordJourney, enabled = !locked,
                    modifier = Modifier.testTag("record-navigation-choice"))
                Column(Modifier.weight(1f)) {
                    Text("Bu yolculuğu kaydet", style = MaterialTheme.typography.titleSmall)
                    Text(if (ui.recordJourney) "Rota ve izin verilen sağlık verileri günlüğüne kaydedilir."
                        else "Yalnızca yol tarifi; günlük ve sağlık kaydı tutulmaz.", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (nav.recording && !ui.recordJourney) Text("Kayıt açık. Kayıtsız başlamak için önce Kaydı durdur'u seç.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(preview, enabled = ui.canPreview && !commandBusy,
                    modifier = Modifier.weight(1f).testTag("preview-directions")) { Text("Rota önizle") }
                Button(start, enabled = ui.preview != null && !ui.busy && !commandBusy,
                    modifier = Modifier.weight(1f).testTag("start-guidance")) { Text(if (ui.starting) "Başlatılıyor…" else "Başlat") }
            }
            Text("Önizleme oturum başlatmaz. Başlat, seçtiğin kayıt tercihiyle sesli yönlendirmeyi açar.", style = MaterialTheme.typography.bodySmall)
        }
        if (ui.origin != null && ui.destination != null && ui.stops.size in 4..5 && ui.transport in setOf(Transport.CAR, Transport.PASSENGER, Transport.MOTORCYCLE)) item {
            val matrixReason = ui.preferences.matrixUnavailableReason(ui.transport)
            OutlinedButton(suggestStopOrder, enabled = matrixReason == null && !ui.busy && !locked,
                modifier = Modifier.testTag("directions-matrix-order")) {
                Text(if (ui.ordering) "Durak sırası hesaplanıyor…" else "Daha hızlı durak sırası öner")
            }
            matrixReason?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (ui.transport == Transport.MOTORCYCLE) Text("Matrix sıra önerisi otomobil tahminidir; motosiklet rotasıyla doğrulanır.",
                style = MaterialTheme.typography.bodySmall)
        }
        ui.orderProposal?.let { proposal -> item {
            StopOrderProposalCard(proposal, !ui.busy && !locked, acceptStopOrder, dismissStopOrder)
        } }
        shownRoute?.let { route -> item {
            Card(Modifier.fillMaxWidth().testTag("directions-route-summary")) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    RouteTimingText(route, nav.takeIf { live }, preview = !live)
                    RouteTrafficText(route)
                    TextButton(onClick = { turnsRoute = route }, enabled = route.maneuvers.isNotEmpty()) { Text("Dönüşler") }
                    if (ui.preview != null && onPrepareGroup != null) OutlinedButton(onPrepareGroup, modifier = Modifier.testTag("directions-create-group")) { Text("Bu rotayla grup oluştur") }
                }
            }
        } }
        if (ui.busy || ui.locating || nav.loading || commandBusy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        ui.message?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
        nav.message?.let { message -> item { Text(message) } }
        item {
            TextButton(trafficSettings, enabled = !locked, modifier = Modifier.testTag("directions-traffic-settings")) { Text("Trafik ayarları") }
            lastDestination?.let { TextButton(it, enabled = !locked) { Text("Son hedefi seç") } }
            if (!nav.recording) OutlinedButton(freeDrive, enabled = !ui.busy && !commandBusy,
                modifier = Modifier.testTag("start-free-drive")) { Text("Hedefsiz sürüş başlat") }
            OutlinedButton(permissions) { Text("Konum ve bildirim izinlerini ayarla") }
            Text("Android Auto bağlantısı kesilse de telefon kaydı ve yönlendirme devam eder.", style = MaterialTheme.typography.bodySmall)
        }
    }
    turnsRoute?.let { route ->
        AlertDialog(onDismissRequest = { turnsRoute = null },
            title = { Text("Dönüşler") },
            text = {
                Column(Modifier.testTag("directions-maneuvers").verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    route.maneuvers.forEachIndexed { index, maneuver ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("${index + 1}.", color = MaterialTheme.colorScheme.primary)
                            Text(maneuver.instruction.ifBlank { maneuver.verbalInstruction })
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { turnsRoute = null }) { Text("Kapat") } },
        )
    }
}

@Composable
private fun DirectionsMapActions(route: PlannedRoute?, nav: NavigationState, enabled: Boolean,
    turns: () -> Unit, mute: () -> Unit, stop: () -> Unit, finish: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.size(36.dp).testTag("directions-map-actions")) {
            Icon(Icons.Outlined.MoreVert, "Rota işlemleri")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Dönüşler") }, onClick = { expanded = false; turns() },
                enabled = route?.maneuvers?.isNotEmpty() == true, modifier = Modifier.testTag("directions-menu-maneuvers"))
            if (nav.sessionId != null || nav.recording || nav.guidance || nav.locationActive) {
                DropdownMenuItem(text = { Text(if (nav.muted) "Sesi aç" else "Sesi kapat") },
                    onClick = { expanded = false; mute() }, modifier = Modifier.testTag("directions-menu-mute"))
                if (nav.route != null) DropdownMenuItem(text = { Text("Yönlendirmeyi durdur") },
                    onClick = { expanded = false; stop() }, enabled = enabled, modifier = Modifier.testTag("directions-menu-stop"))
                DropdownMenuItem(text = { Text("Yolculuğu bitir") }, onClick = { expanded = false; finish() },
                    enabled = enabled, modifier = Modifier.testTag("directions-menu-finish"))
            }
        }
    }
}

@Composable
private fun DirectionEndpoint(letter: String, title: String, stop: RouteStop?, status: String?,
    onChoose: () -> Unit, enabled: Boolean, tag: String) {
    OutlinedCard(onClick = onChoose, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag(tag)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(letter, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.labelMedium)
                Text(status ?: stop?.label ?: "Nokta seç", style = MaterialTheme.typography.titleMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                stop?.let { Text(String.format(Locale.US, "%.5f, %.5f", it.coordinate.latitude, it.coordinate.longitude),
                    style = MaterialTheme.typography.bodySmall) }
            }
            Text("Değiştir", style = MaterialTheme.typography.labelMedium)
        }
    }
}

internal fun navigationInstruction(nav: NavigationState): String = when {
    nav.arrived -> if (nav.recording) "Varış noktasına ulaştın. Kayıt devam ediyor." else "Varış noktasına ulaştın."
    nav.guidance -> nav.route?.maneuvers?.getOrNull(nav.progress?.maneuverIndex ?: 0)?.instruction
        ?.takeIf { it.isNotBlank() } ?: "Yönlendirme açık"
    else -> "Serbest sürüş"
}

@Composable
internal fun RouteTimingText(route: PlannedRoute, nav: NavigationState?, preview: Boolean) {
    val now by produceState(System.currentTimeMillis(), route.id) {
        while (true) { value = System.currentTimeMillis(); delay(30_000) }
    }
    val seconds = nav?.progress?.remainingSeconds ?: route.durationSeconds
    val meters = nav?.progress?.remainingMeters ?: route.distanceMeters
    val departure = if (preview) route.effectiveDepartureAt ?: route.createdAt else now
    val arrival = if (nav?.gpsStale == true) "—" else SimpleDateFormat("HH:mm", Locale.forLanguageTag("tr-TR"))
        .format(Date(departure + (seconds * 1000).toLong()))
    val minutes = ceil(seconds / 60).toInt().coerceAtLeast(0)
    Text("${if (preview) "Önizleme" else "Kalan"} · ${String.format(Locale.forLanguageTag("tr-TR"), "%.1f", meters / 1000)} km · $minutes dk",
        style = MaterialTheme.typography.titleSmall)
    Text("Tahmini varış $arrival", style = MaterialTheme.typography.bodySmall)
}

@Composable
internal fun RouteTrafficText(route: PlannedRoute) {
    val now by produceState(System.currentTimeMillis(), route.traffic?.fetchedAt) {
        while (true) { value = System.currentTimeMillis(); delay(30_000) }
    }
    val traffic = route.traffic
    if (route.provider == RouteProvider.TOMTOM && traffic != null) {
        val ageSeconds = ((now - traffic.fetchedAt).coerceAtLeast(0) / 1000)
        Text("TomTom trafik · +${ceil(traffic.delaySeconds / 60).toInt()} dk gecikme", style = MaterialTheme.typography.bodySmall)
        Text(if (ageSeconds > 300) "Trafik verisi eski · ${ageSeconds / 60} dk önce"
            else "Trafik verisi ${if (ageSeconds < 60) "az önce" else "${ageSeconds / 60} dk önce"} güncellendi",
            style = MaterialTheme.typography.bodySmall,
            color = if (ageSeconds > 300) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        if (traffic.experimental) Text("Motosiklet trafik tahmini · Deneysel", style = MaterialTheme.typography.labelMedium)
    } else {
        Text("Trafik hariç tahmin · Valhalla", style = MaterialTheme.typography.bodySmall)
        route.trafficUnavailableReason?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
