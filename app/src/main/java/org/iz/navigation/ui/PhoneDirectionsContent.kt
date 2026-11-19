@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package org.iz.navigation.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import org.iz.navigation.weather.RouteSchedule
import org.iz.navigation.weather.RouteTravelTime
import org.iz.navigation.weather.RouteStop
import org.iz.navigation.weather.RoutePreferences
import org.iz.navigation.weather.RouteStopLimits.MAX_PLANNED_STOPS
import org.iz.navigation.weather.geometryKey
import org.iz.navigation.weather.RoutingPolicy
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
    onMap: () -> Unit = onClose,
    commandErrorVersion: Long = 0,
    voiceControls: (@Composable () -> Unit)? = null,
    onPlannedBreakMinutesChanged: (Int, Int) -> Unit = { _, _ -> },
    onResumePlannedBreak: () -> Unit = {},
    plannedBreakResumeEnabled: Boolean = false,
    moveVia: ((Int, Int) -> Unit)? = null,
    editVia: ((Int) -> Unit)? = null,
) {
    val listState = rememberLazyListState()
    val shownRoute = ui.preview ?: nav.route
    val live = ui.preview == null && (nav.sessionId != null || nav.guidance || nav.recording || nav.locationActive)
    val locked = ui.starting || commandBusy
    val sessionActive = nav.sessionId != null || nav.recording || nav.guidance || nav.locationActive
    var turnsRoute by remember { mutableStateOf<PlannedRoute?>(null) }
    LaunchedEffect(ui.startedCount) { if (ui.startedCount > 0) listState.animateScrollToItem(0) }
    LaunchedEffect(nav.recording) { if (nav.recording) listState.animateScrollToItem(0) }
    LaunchedEffect(ui.message, nav.message, commandErrorVersion) {
        if (ui.message != null || nav.message != null) listState.animateScrollToItem(0)
    }
    LazyColumn(Modifier.fillMaxSize().testTag("navigation-screen"), state = listState,
        contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ui.message?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("directions-command-error")) } }
        nav.message?.let { message -> item { Text(message) } }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (live) "Yol tarifi" else "Çok duraklı planlayıcı",
                        style = MaterialTheme.typography.headlineSmall)
                    Text(when {
                        nav.simulation -> "Simülasyon · Günlüğe kaydedilmez"
                        nav.guidance && nav.recording -> "Yol tarifi açık · Kayıt açık"
                        nav.guidance && nav.recordingPending -> "Yol tarifi açık · Kayıt konum bekliyor"
                        nav.guidance -> "Yol tarifi açık · Kayıt kapalı"
                        nav.recording -> nav.journey?.transport?.takeUnless { it == Transport.UNKNOWN }?.label()
                            ?.let { "$it · Kayıt açık" } ?: "Kayıt açık"
                        else -> "İki nokta seç, rotanı önizle."
                    }, style = MaterialTheme.typography.bodyMedium)
                }
                TextButton(onClick = onClose, modifier = Modifier.testTag("close-directions-planner")) { Text("Geri") }
                TextButton(onClick = onMap, modifier = Modifier.testTag("directions-return-map")) { Text("Haritaya dön") }
            }
        }
        if (sessionActive) item {
            Column(Modifier.fillMaxWidth().testTag("active-session-actions"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (nav.approximateOrigin != null) Text("Yaklaşık konum", style = MaterialTheme.typography.bodyMedium)
                if (nav.recordingPending) RecordingPendingWarning()
                if (nav.guidance) OutlinedButton(stopGuidance, enabled = !locked,
                    modifier = Modifier.fillMaxWidth().testTag("stop-navigation-guidance")) { Text(PhoneSessionEnd.GUIDANCE.label) }
                if (nav.recording) OutlinedButton(stopRecording, enabled = !locked,
                    modifier = Modifier.fillMaxWidth().testTag("stop-navigation-recording")) { Text(PhoneSessionEnd.RECORDING.label) }
            }
            val activeBreak = nav.plannedBreak
            if (activeBreak != null) {
                PlannedBreakCard(activeBreak, enabled = !locked && plannedBreakResumeEnabled, onResume = onResumePlannedBreak, timing = nav.tripTiming)
            } else {
                Text(navigationInstruction(nav), style = MaterialTheme.typography.titleMedium)
            }
            if (ui.preview != null) TextButton(showLive) { Text("Canlı rotaya dön") }
            if (nav.guidance) OutlinedButton(mute) { Text(if (nav.muted) "Sesi aç" else "Sesi kapat") }
        }
        if (showMap) item {
            WeatherRouteMap(
                route = shownRoute, assessment = null,
                modifier = Modifier.fillMaxWidth().height(290.dp).testTag("directions-route-map"),
                stops = shownRoute?.stops ?: if (live) emptyList() else ui.stops,
                liveCoordinate = nav.fix?.coordinate ?: ui.origin?.takeIf { ui.originCurrent && nav.approximateOrigin == null }?.coordinate,
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
                            } ?: Text(if (live) if (nav.recording) "Serbest sürüş · Konumlar kaydediliyor" else "Serbest sürüş" else "A başlangıç · B varış",
                                style = MaterialTheme.typography.labelLarge)
                        }
                    }
                },
            )
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
            if (ui.via.isNotEmpty()) Text("Ara duraklar", style = MaterialTheme.typography.titleSmall)
            val stopTimings = ui.preview?.let { route ->
                RouteSchedule.stops(route, (route.effectiveDepartureAt ?: route.createdAt).coerceAtLeast(0))
            }
            ui.via.forEachIndexed { index, stop ->
                key(stop, ui.via.take(index).count { it == stop }) {
                    if (editVia != null) {
                        DirectionEndpoint("${index + 1}", "Ara durak", stop, null,
                            { editVia(index) }, !locked, "directions-via-$index")
                    } else {
                        Text("${index + 1}. ${stop.label}", style = MaterialTheme.typography.titleSmall)
                    }
                    var showActions by rememberSaveable { mutableStateOf(false) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton({ showActions = true }, enabled = !locked,
                            modifier = Modifier.testTag("directions-via-actions-$index")) { Text("Durak işlemleri") }
                        DropdownMenu(expanded = showActions, onDismissRequest = { showActions = false }) {
                            if (moveVia != null) {
                                DropdownMenuItem(text = { Text("Yukarı taşı") }, enabled = !locked && index > 0,
                                    onClick = { showActions = false; moveVia(index, index - 1) })
                                DropdownMenuItem(text = { Text("Aşağı taşı") }, enabled = !locked && index < ui.via.lastIndex,
                                    onClick = { showActions = false; moveVia(index, index + 1) })
                            }
                            DropdownMenuItem(text = { Text("Durağı kaldır") }, enabled = !locked,
                                onClick = { showActions = false; removeVia(index) })
                        }
                    }
                    val scheduledBreak = stopTimings?.getOrNull(index + 1)?.arrivalAt
                    PlannedBreakEditor(stop, enabled = !locked, arrivalAt = scheduledBreak) { minutes ->
                        onPlannedBreakMinutesChanged(index, minutes)
                    }
                }
            }
            TextButton(addVia, enabled = !locked && ui.via.size < MAX_PLANNED_STOPS - 2, modifier = Modifier.testTag("directions-add-via")) { Text("Ara durak ekle") }
            DirectionEndpoint("B", "Varış", ui.destination, null, chooseDestination, !locked, "directions-destination")
            if (ui.via.size >= MAX_PLANNED_STOPS - 2) Text("Başlangıç ve varış dahil en fazla $MAX_PLANNED_STOPS durak.",
                style = MaterialTheme.typography.bodySmall)
            if (!ui.originCurrent) Text("Başlatınca önce A noktasına, ardından planlanan duraklara gidilir.", style = MaterialTheme.typography.bodySmall)
        }
        item {
            val activeMode = ui.transport in setOf(Transport.WALK, Transport.RUN, Transport.BICYCLE)
            Text("Rota tercihleri", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RoutePreferenceChip(if (activeMode) "Otoyola girme" else "Otoyoldan kaçın", ui.preferences.avoidHighways,
                    !locked, "directions-avoid-highways") {
                    setPreferences(ui.preferences.copy(avoidHighways = !ui.preferences.avoidHighways))
                }
                RoutePreferenceChip("Ücretli yoldan kaçın", !activeMode && ui.preferences.avoidTolls,
                    !locked && !activeMode, "directions-avoid-tolls") {
                    setPreferences(ui.preferences.copy(avoidTolls = !ui.preferences.avoidTolls))
                }
                RoutePreferenceChip("Feribottan kaçın", ui.preferences.avoidFerries,
                    !locked, "directions-avoid-ferries") {
                    setPreferences(ui.preferences.copy(avoidFerries = !ui.preferences.avoidFerries))
                }
                RoutePreferenceChip("Toprak yollardan kaçın", !activeMode && ui.preferences.avoidUnpaved,
                    !locked && !activeMode, "directions-avoid-unpaved") {
                    setPreferences(ui.preferences.copy(avoidUnpaved = !ui.preferences.avoidUnpaved))
                }
            }
            if (activeMode) Text("${ui.transport.label()} rotalarında ücretli yol ve toprak yol tercihleri desteklenmiyor.",
                style = MaterialTheme.typography.bodySmall)
            if (activeMode && ui.preferences.avoidHighways) Text("Otoyolsuz rota servis haritasından doğrulanır; doğrulanamazsa başlatılmaz. Harita verileri eksik olabilir.", style = MaterialTheme.typography.bodySmall)
            Text(if (activeMode) "Feribottan kaçınma tercihi mümkün olduğunda uygulanır; kesin feribot yasağı değildir."
                else if (ui.transport == Transport.MOTORCYCLE) "Otoyol, ücretli yol, feribot ve toprak yoldan kaçınma tercihleri mümkün olduğunda uygulanır; kesin yol veya yüzey yasağı değildir."
                else "Otoyol, ücretli yol ve feribottan kaçınma tercihleri mümkün olduğunda uygulanır; kesin yol yasağı değildir.", style = MaterialTheme.typography.bodySmall)
            saveRoute?.let { OutlinedButton(it, enabled = ui.stops.size in 2..MAX_PLANNED_STOPS && !locked, modifier = Modifier.testTag("directions-save-route")) { Text("Rotayı kaydet") } }
        }
        if (ui.preview != null) item {
            OutlinedButton(requestAlternatives, enabled = !ui.busy && !locked, modifier = Modifier.testTag("directions-alternatives")) { Text("Alternatif rotalar") }
            ui.alternativeMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            ui.alternatives.forEachIndexed { index, route ->
                FilterChip(ui.preview.geometryKey() == route.geometryKey(), { selectAlternative(route.id) }, enabled = !ui.busy && !locked,
                    label = {
                        val timing = RouteSchedule.planned(route, (route.effectiveDepartureAt ?: route.createdAt).coerceAtLeast(0))
                        Text("Rota ${index + 1} · ${formatDistance(route.distanceMeters)} · ${formatDuration(timing.totalSeconds)}")
                    },
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
            if (nav.recording && !ui.recordJourney) Text("Kayıt açık. Kayıtsız başlamak için önce Yolculuk kaydını bitir'i seç.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            if (ui.preview == null) {
                Button(preview, enabled = ui.canPreview && !commandBusy,
                    modifier = Modifier.fillMaxWidth().testTag("preview-directions")) {
                    Text(if (ui.busy) "Rota hesaplanıyor…" else "Rota önizle")
                }
            } else {
                Button(start, enabled = !ui.busy && !commandBusy,
                    modifier = Modifier.fillMaxWidth().testTag("start-guidance")) {
                    Text(if (ui.starting) "Başlatılıyor…" else "Yol tarifini başlat")
                }
                TextButton(preview, enabled = ui.canPreview && !commandBusy,
                    modifier = Modifier.testTag("preview-directions")) { Text("Rotayı yeniden hesapla") }
            }
        }
        if (ui.origin != null && ui.destination != null && RoutingPolicy.supportsStopOrder(ui.transport, ui.stops.size)) item {
            val matrixReason = RoutingPolicy.stopOrderIssue(ui.transport, ui.stops.size, ui.preferences)
            OutlinedButton(suggestStopOrder, enabled = matrixReason == null && !ui.busy && !locked,
                modifier = Modifier.testTag("directions-matrix-order")) {
                Text(if (ui.ordering) "Durak sırası hesaplanıyor…" else "Daha hızlı durak sırası öner")
            }
            matrixReason?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        ui.orderProposal?.let { proposal -> item {
            StopOrderProposalCard(proposal, !ui.busy && !locked, acceptStopOrder, dismissStopOrder)
        } }
        shownRoute?.let { route -> item {
            Card(Modifier.fillMaxWidth().testTag("directions-route-summary")) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    RouteTimingText(route, nav.takeIf { live }, preview = !live, showBreakdown = true)
                    RouteTrafficText(route)
                    TextButton(onClick = { turnsRoute = route }, enabled = route.maneuvers.isNotEmpty()) { Text("Dönüşler") }
                    if (ui.preview != null && onPrepareGroup != null) OutlinedButton(onPrepareGroup, modifier = Modifier.testTag("directions-create-group")) { Text("Bu rotayla grup oluştur") }
                }
            }
        } }
        if (ui.busy || ui.locating || nav.loading || commandBusy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (voiceControls != null) item(key = "navigation-voice") { voiceControls() }
        item {
            TextButton(trafficSettings, enabled = !locked, modifier = Modifier.testTag("directions-traffic-settings")) {
                Text(if (ui.transport == Transport.MOTORCYCLE) "Hız sınırı ayarları" else "Trafik ayarları")
            }
            lastDestination?.let { TextButton(it, enabled = !locked) { Text("Son hedefi seç") } }
            if (!nav.recording) OutlinedButton(freeDrive, enabled = !ui.busy && !commandBusy,
                modifier = Modifier.testTag("start-free-drive")) { Text("Hedefsiz sürüş başlat") }
            OutlinedButton(permissions) { Text("Konum ve bildirim izinlerini ayarla") }
            Text("Android Auto bağlantısı kesilse de telefon kaydı ve yönlendirme devam eder.", style = MaterialTheme.typography.bodySmall)
        }
        if (sessionActive) item {
            Button(finish, enabled = !locked, modifier = Modifier.fillMaxWidth().testTag("finish-navigation-journey")) {
                Text(PhoneSessionEnd.ALL.label)
            }
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
internal fun RoutePreferenceChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    FilterChip(selected = selected, onClick = onClick, enabled = enabled,
        label = { Text(label) },
        leadingIcon = if (selected) { { Icon(Icons.Filled.Check, contentDescription = null) } } else null,
        modifier = Modifier.testTag(tag))
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
                if (nav.route != null) DropdownMenuItem(text = { Text(PhoneSessionEnd.GUIDANCE.label) },
                    onClick = { expanded = false; stop() }, enabled = enabled, modifier = Modifier.testTag("directions-menu-stop"))
                DropdownMenuItem(text = { Text(PhoneSessionEnd.ALL.label) }, onClick = { expanded = false; finish() },
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
    nav.plannedBreak != null -> "Planlı mola"
    nav.paused -> "Yönlendirme duraklatıldı"
    nav.guidance && nav.gpsStale && !nav.simulation -> "Güvenilir GPS konumu bekleniyor"
    nav.guidance -> nav.route?.maneuvers?.getOrNull(nav.progress?.maneuverIndex ?: 0)?.instruction
        ?.takeIf { it.isNotBlank() } ?: "Yönlendirme açık"
    else -> "Serbest sürüş"
}

@Composable
internal fun RouteTimingText(route: PlannedRoute, nav: NavigationState?, preview: Boolean, showBreakdown: Boolean = false) {
    if (!preview && nav != null && nav.gpsStale && !nav.simulation && nav.plannedBreak == null) {
        Text("Güncel kalan yol ve süre bilgisi yok.", style = MaterialTheme.typography.bodySmall)
        return
    }
    val departure = (route.effectiveDepartureAt ?: route.createdAt).coerceAtLeast(0)
    val timing = if (preview) RouteSchedule.planned(route, departure) else nav?.tripTiming
    val seconds = timing?.totalSeconds ?: nav?.progress?.remainingSeconds ?: route.durationSeconds
    val meters = nav?.progress?.remainingMeters ?: route.distanceMeters
    val hasCurrentTiming = nav?.let { !it.gpsStale || it.plannedBreak != null } ?: true
    val legacyArrival = if (timing == null && nav?.paused != true && hasCurrentTiming &&
        route.stops.none { it.plannedBreakSeconds > 0 }) {
        val now by produceState(System.currentTimeMillis(), route.id) {
            while (true) { value = System.currentTimeMillis(); delay(30_000) }
        }
        RouteTravelTime.plannedArrival(now, seconds)
    } else null
    val arrival = (timing?.arrivalAt ?: legacyArrival)?.takeIf { hasCurrentTiming }
    val arrivalLabel = arrival?.let {
        SimpleDateFormat("HH:mm", Locale.forLanguageTag("tr-TR")).format(Date(it))
    } ?: "—"
    val minutes = ceil(seconds / 60).toInt().coerceAtLeast(0)
    Text("${if (preview) "Önizleme" else "Kalan"} · ${String.format(Locale.forLanguageTag("tr-TR"), "%.1f", meters / 1000)} km · $minutes dk",
        style = MaterialTheme.typography.titleSmall)
    if (showBreakdown && timing != null) RouteTimingBreakdown(timing)
    Text("Tahmini varış $arrivalLabel", style = MaterialTheme.typography.bodySmall)
    if (showBreakdown && preview) PlannedViaSchedule(route, departure)
}

@Composable
internal fun RouteTrafficText(route: PlannedRoute) {
    if (route.transport == Transport.MOTORCYCLE && route.provider == RouteProvider.VALHALLA) {
        Text(org.iz.navigation.weather.MOTORCYCLE_ROUTE_CONTEXT, style = MaterialTheme.typography.bodySmall)
        return
    }
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
