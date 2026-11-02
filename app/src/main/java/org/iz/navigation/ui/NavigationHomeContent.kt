@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package org.iz.navigation.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import org.iz.navigation.R
import org.iz.navigation.data.GeoCoordinate
import org.iz.navigation.data.TrackPoint
import org.iz.navigation.integration.RouteMapMember
import org.iz.navigation.integration.WeatherRouteMap
import org.iz.navigation.navigation.NavigationState
import org.iz.navigation.speed.roadSpeedPresentation
import kotlin.math.roundToInt

/** Keep original positional callers and trailing planner lambdas source-compatible. */
@Composable
internal fun NavigationHomeContent(ui: PhoneDirectionsState, nav: NavigationState, points: List<TrackPoint>,
    members: List<RouteMapMember>, showPlanner: Boolean, weatherLabel: String, onSearch: () -> Unit,
    onRecord: () -> Unit, onGroup: () -> Unit, onMenu: () -> Unit, onWeather: () -> Unit, onMute: () -> Unit,
    onManageSession: () -> Unit, onLocate: () -> Unit, onPin: (GeoCoordinate) -> Unit,
    planner: @Composable () -> Unit) {
    NavigationHomeContent(ui, nav, points, members, showPlanner, weatherLabel, onSearch, onRecord, onGroup,
        onMenu, onWeather, onMute, onManageSession, onLocate, onPin, emptyList(), {}, planner = planner)
}
/** Only the visible cards accept gestures. The map has no scroll or sheet parent. */
@Composable
internal fun NavigationHomeContent(
    ui: PhoneDirectionsState,
    nav: NavigationState,
    points: List<TrackPoint>,
    members: List<RouteMapMember>,
    showPlanner: Boolean,
    weatherLabel: String,
    onSearch: () -> Unit,
    onRecord: () -> Unit,
    onGroup: () -> Unit,
    onMenu: () -> Unit,
    onWeather: () -> Unit,
    onMute: () -> Unit,
    onManageSession: () -> Unit,
    onLocate: () -> Unit,
    onPin: (GeoCoordinate) -> Unit,
    shortcuts: List<org.iz.navigation.data.DestinationShortcut>,
    onShortcut: (org.iz.navigation.data.DestinationShortcutKind) -> Unit,
    voiceFault: String? = null,
    showDashboard: Boolean = false,
    onDashboard: (() -> Unit)? = null,
    dashboard: (@Composable () -> Unit)? = null,
    editPreview: org.iz.navigation.navigation.RouteEditPreview? = null,
    planner: @Composable () -> Unit,
) {
    val presentation = navigationMapPresentation(ui, nav, points, showPlanner, editPreview)
    val live = presentation.sessionActive
    val route = presentation.route
    val current = presentation.coordinate
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val dashboardVisible = showDashboard && dashboard != null
    val baseShowBrand = !live && !showPlanner && !dashboardVisible
    val showShortcuts = !showPlanner && !dashboardVisible && shortcuts.isNotEmpty()
    val showDestinationHeader = !nav.guidance && !dashboardVisible
    var shortcutMeasurement by remember { mutableStateOf<Pair<List<Any?>, androidx.compose.ui.unit.Dp>?>(null) }
    var headerMeasurement by remember { mutableStateOf<Pair<List<Any?>, androidx.compose.ui.unit.Dp>?>(null) }
    var panelsMeasurement by remember { mutableStateOf<Pair<List<Any?>, androidx.compose.ui.unit.Dp>?>(null) }
    var dashboardMeasurement by remember { mutableStateOf<Pair<List<Any?>, IntSize>?>(null) }
    var dashboardButtonMeasurement by remember { mutableStateOf<Pair<List<Any?>, androidx.compose.ui.unit.Dp>?>(null) }
    val dashboardEpoch = remember(dashboardVisible) { Any() }
    BoxWithConstraints(Modifier.fillMaxSize().testTag("navigation-home")) {
        val dashboardAtSide = dashboardVisible && maxWidth > maxHeight
        val dashboardKey = listOf(maxWidth, maxHeight, density.density, density.fontScale, dashboardAtSide, dashboardEpoch)
        val measuredDashboard = dashboardMeasurement?.takeIf { it.first == dashboardKey }?.second
        val desiredSideWidth = (maxWidth * .44f).coerceAtMost(400.dp).coerceAtLeast(minOf(240.dp, maxWidth))
        val desiredBottomHeight = (maxHeight * .52f).coerceAtMost((maxHeight - 100.dp).coerceAtLeast(0.dp))
        val dashboardSideWidth = if (dashboardAtSide) measuredDashboard?.let { with(density) { it.width.toDp() } } ?: desiredSideWidth else 0.dp
        val dashboardBottomHeight = if (dashboardVisible && !dashboardAtSide) measuredDashboard?.let { with(density) { it.height.toDp() } } ?: desiredBottomHeight else 0.dp
        val dashboardButtonVisible = onDashboard != null && !dashboardVisible
        val dashboardButtonKey = listOf(maxWidth, maxHeight, density.density, density.fontScale, dashboardEpoch)
        val dashboardButtonHeight = if (dashboardButtonVisible) dashboardButtonMeasurement?.takeIf { it.first == dashboardButtonKey }?.second ?: 48.dp else 0.dp
        // Bounds from an earlier header/panel/viewport must never drive this frame's camera.
        // GPS coordinates alone do not invalidate geometry or remount the native map.
        val speedText = roadSpeedPresentation(nav.roadSpeed)
        val layoutKey = listOf(maxWidth, maxHeight, density.density, density.fontScale, showPlanner, dashboardVisible, dashboardAtSide, dashboardSideWidth, dashboardBottomHeight, dashboardButtonHeight,
            showDestinationHeader, live, nav.guidance, nav.recording, nav.simulation, nav.gpsStale,
            nav.locationActive, nav.sessionTransport, nav.message, ui.message, weatherLabel, current == null, ui.locating, voiceFault, presentation.editPreviewLabel,
            if (nav.guidance) navigationInstruction(nav) else null,
            if (nav.guidance) nav.progress?.nextManeuverDistanceMeters else null,
            nav.roadSpeed.visible, speedText.ownText, speedText.limitText, speedText.sourceText,
            if (showShortcuts) shortcuts.map { it.kind to it.place.name } else emptyList())
        // A shortcut row does not depend on maneuver, speed or warning text in the other panels.
        val showBrand = baseShowBrand && !(showShortcuts && maxHeight < 400.dp)
        val shortcutKey = listOf(maxWidth, maxHeight, density.density, density.fontScale, ui.busy,
            shortcuts.map { it.kind to it.place.name })
        val headerKey = listOf(maxWidth, maxHeight, density.density, density.fontScale, showBrand)
        val shortcutHeight = shortcutMeasurement?.takeIf { it.first == shortcutKey }?.second ?: 0.dp
        val shortcutOffset = if (showShortcuts) shortcutHeight + 8.dp else 0.dp
        val headerHeight = if (!showDestinationHeader) 0.dp else
            headerMeasurement?.takeIf { it.first == headerKey }?.second ?: if (baseShowBrand) 144.dp else 80.dp

        val compact = maxHeight - shortcutOffset < 640.dp
        val shortIdle = baseShowBrand && maxHeight - shortcutOffset < 400.dp
        val horizontalMapControls = baseShowBrand && headerHeight + shortcutOffset + 24.dp + 104.dp > maxHeight - 92.dp
        // Compact branches depend on measured header/shortcut extents as well as input data.
        val panelsKey = layoutKey + listOf(showBrand, compact, shortIdle, headerHeight, shortcutOffset)
        val panelsHeight = panelsMeasurement?.takeIf { it.first == panelsKey }?.second ?: 0.dp
        // Never draw controls with a provisional offset when their own row/header changed.
        val controlsReady = (!showDestinationHeader || headerMeasurement?.first == headerKey) &&
            (!showShortcuts || shortcutMeasurement?.first == shortcutKey) &&
            (presentation.editPreviewLabel == null || panelsMeasurement?.first == panelsKey)
        val dashboardReady = !dashboardVisible || measuredDashboard != null
        val dashboardButtonReady = !dashboardButtonVisible || dashboardButtonMeasurement?.first == dashboardButtonKey
        val viewportReady = panelsMeasurement?.first == panelsKey && controlsReady && dashboardReady && dashboardButtonReady
        val attributionWidth = with(density) {
            textMeasurer.measure("© OpenStreetMap contributors", style = MaterialTheme.typography.labelSmall).size.width.toDp()
        } + 20.dp
        val compactWeatherWidth = (maxWidth - 24.dp - attributionWidth - 8.dp).coerceAtLeast(48.dp)
        WeatherRouteMap(route = route, assessment = null, modifier = Modifier.fillMaxSize().padding(end = dashboardSideWidth).testTag("navigation-home-map"),
            expandable = false, stops = presentation.stops, liveCoordinate = current,
            recordedPoints = presentation.trail, gpsStale = live && nav.gpsStale,
            cameraIdentity = presentation.cameraIdentity,
            preparedRoutes = if (preparedNavigationMapApplies(presentation, nav))
                (androidx.compose.ui.platform.LocalContext.current.applicationContext as? org.iz.navigation.IzApplication)?.preparedRoutes else null,
            drivingCamera = phoneDriveCameraInput(nav, showPlanner, android.os.SystemClock.elapsedRealtime()),
            cameraViewportReady = viewportReady, navigationControlsReady = controlsReady,
            navigationLayout = true, members = members, onLocate = onLocate, onMapLongClick = onPin,
            navigationControlsTopInset = if (presentation.editPreviewLabel != null)
                panelsHeight + 24.dp else headerHeight + shortcutOffset + 24.dp,
            navigationControlsHorizontal = horizontalMapControls,
            attributionBottomInset = if (dashboardBottomHeight > 0.dp) dashboardBottomHeight + 92.dp else if (showPlanner && !dashboardVisible) maxHeight * .64f + 4.dp else 92.dp,
            cameraViewportInsets = PaddingValues(start = 28.dp, top = panelsHeight + 24.dp, end = 84.dp,
                bottom = if (dashboardBottomHeight > 0.dp) dashboardBottomHeight + 112.dp else if (showPlanner && !dashboardVisible) maxHeight * .64f + 24.dp else 112.dp + dashboardButtonHeight))
        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(end = dashboardSideWidth).padding(12.dp)
            .onSizeChanged { panelsMeasurement = panelsKey to with(density) { it.height.toDp() } }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            presentation.editPreviewLabel?.let { label ->
                Surface(Modifier.fillMaxWidth().testTag("navigation-edit-preview-label"), shape = MaterialTheme.shapes.medium) {
                    Text(label, Modifier.padding(12.dp), style = MaterialTheme.typography.labelLarge)
                }
            }
            if (showShortcuts) Box(Modifier.fillMaxWidth().onSizeChanged { shortcutMeasurement = shortcutKey to with(density) { it.height.toDp() } }) {
                ShortcutDestinationsRow(shortcuts, !ui.busy, onShortcut)
            }
            if (showDestinationHeader) Column(Modifier.fillMaxWidth().onSizeChanged { headerMeasurement = headerKey to with(density) { it.height.toDp() } },
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (showBrand) HomeBrand()
                Surface(onClick = onSearch, modifier = Modifier.fillMaxWidth().testTag("open-navigation"),
                    shape = RoundedCornerShape(24.dp), shadowElevation = 4.dp, color = MaterialTheme.colorScheme.surface) {
                    Row(Modifier.padding(horizontal = 18.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Outlined.Search, null, tint = Forest)
                        Column(Modifier.weight(1f)) {
                            Text("Nereye?", style = MaterialTheme.typography.titleLarge)
                            if (nav.guidance) Text(nav.route?.stops?.lastOrNull()?.label.orEmpty(),
                                maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        }
                        Icon(Icons.Outlined.Route, "Yol tarifi", tint = Forest)
                    }
                }
            }
            if (!showPlanner && !dashboardVisible && live) Surface(onClick = onManageSession, shape = RoundedCornerShape(20.dp), shadowElevation = 2.dp,
                color = MaterialTheme.colorScheme.surface.copy(alpha = .98f), modifier = Modifier.fillMaxWidth().padding(end = 60.dp).testTag("live-guidance-card")
                    .semantics { onClick(label = "Yolculuk işlemlerini aç", action = null) }) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (nav.guidance) nav.progress?.nextManeuverDistanceMeters?.let { meters ->
                            Text(if (meters >= 1000) "%.1f km sonra".format(meters / 1000) else "${meters.roundToInt().coerceAtLeast(0)} m sonra",
                                modifier = Modifier.align(Alignment.CenterVertically),
                                style = MaterialTheme.typography.titleLarge, color = Forest)
                        }
                        if (nav.guidance) IconButton(onMute,
                            Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                .align(Alignment.CenterVertically).testTag("compact-card-mute")) {
                            Icon(if (nav.muted) Icons.Outlined.VolumeOff else Icons.Outlined.VolumeUp,
                                if (nav.muted) "Sesi aç" else "Sesi kapat")
                        }
                        TextButton(onManageSession,
                            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                .align(Alignment.CenterVertically).testTag("home-finish-session")
                                .semantics { contentDescription = "Yolculuk işlemlerini aç" },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) { Text("İşlemler") }
                    }
                    if (nav.simulation) Text("Simülasyon", color = Forest, style = MaterialTheme.typography.labelSmall)
                    if (nav.guidance) {
                        Text(navigationInstruction(nav), fontSize = 24.sp, lineHeight = 29.sp,
                            maxLines = 3, overflow = TextOverflow.Ellipsis)
                    } else Text("${nav.sessionTransport?.label().orEmpty()} · Serbest yolculuk", style = MaterialTheme.typography.titleLarge)
                    if (nav.guidance) voiceFault?.let { Text(it, modifier = Modifier.testTag("navigation-voice-fault"),
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    if (nav.gpsStale) Text("GPS konumu bekleniyor", color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                    if (!nav.locationActive && !nav.simulation) Text("Konum bağlantısı kuruluyor", style = MaterialTheme.typography.bodySmall)
                    // The simulation identity stays here; the full explanation remains in the directions panel.
                    // Unknown messages and every operational warning keep their original visibility.
                    nav.message?.takeUnless { nav.simulation && it == "Deneme sürüşü · günlüğe kaydedilmez" }
                        ?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
            if (!showPlanner && !dashboardVisible && nav.roadSpeed.visible) {
                val speed = roadSpeedPresentation(nav.roadSpeed)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("road-speed-panel")) {
                    SpeedReadout("Hızım", speed.ownText, "GPS", Modifier.width(100.dp))
                    SpeedReadout("Hız sınırı", speed.limitText, speed.sourceText, Modifier.widthIn(min = 128.dp, max = 200.dp))
                }
            }
            if (shortIdle) Row(Modifier.fillMaxWidth().padding(end = if (horizontalMapControls) 116.dp else 60.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (current == null) HomeLocateChip(ui.locating, onLocate, Modifier.weight(1f, fill = false))
                HomeWeatherChip(weatherLabel, onWeather, Modifier.weight(1f, fill = false))
            } else if (!showPlanner && !dashboardVisible && !live && current == null) HomeLocateChip(ui.locating, onLocate)
            if (!showPlanner && !dashboardVisible && ui.message != null) Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
                Text(ui.message, Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall)
            }
            if (!showPlanner && !dashboardVisible && !shortIdle) HomeWeatherChip(weatherLabel, onWeather,
                if (compact && live) Modifier.align(Alignment.End).widthIn(max = compactWeatherWidth) else Modifier)
        }
        if (showPlanner && !dashboardVisible) Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(.64f).padding(bottom = 88.dp),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp), shadowElevation = 8.dp,
            color = MaterialTheme.colorScheme.surface) { planner() }
        if (dashboardVisible) {
            val panelModifier = if (dashboardAtSide) Modifier.align(Alignment.TopEnd).padding(top = 12.dp, bottom = 12.dp)
                .width(desiredSideWidth).fillMaxHeight()
            else Modifier.align(Alignment.BottomCenter).padding(bottom = 88.dp).fillMaxWidth().height(desiredBottomHeight)
            Box(panelModifier.onSizeChanged { dashboardMeasurement = dashboardKey to it }) { dashboard?.invoke() }
        }
        if (dashboardButtonVisible) Box(Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 92.dp)) {
            OutlinedButton(onClick = { onDashboard?.invoke() },
                modifier = Modifier.sizeIn(minHeight = 48.dp).onSizeChanged {
                    dashboardButtonMeasurement = dashboardButtonKey to with(density) { it.height.toDp() }
                }.testTag("open-navigation-dashboard"),
                colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Icon(Icons.Outlined.Dashboard, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Gösterge")
            }
        }
        Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(end = dashboardSideWidth).padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            shape = RoundedCornerShape(24.dp), shadowElevation = 6.dp, color = MaterialTheme.colorScheme.surface) {
            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onRecord, Modifier.weight(1f).height(56.dp).testTag("home-record"), contentPadding = PaddingValues(6.dp)) {
                    Icon(if (nav.recording) Icons.Outlined.StopCircle else Icons.Outlined.FiberManualRecord, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(5.dp)); Text(if (nav.recording) "Kayıt açık" else "Kaydet", maxLines = 1)
                }
                TextButton(onGroup, Modifier.weight(1f).height(56.dp).testTag("home-group"), contentPadding = PaddingValues(6.dp)) {
                    Icon(Icons.Outlined.Groups, null, Modifier.size(20.dp)); Spacer(Modifier.width(5.dp)); Text("Grup")
                }
                TextButton(onMenu, Modifier.weight(1f).height(56.dp).testTag("home-menu"), contentPadding = PaddingValues(6.dp)) {
                    Icon(Icons.Outlined.Menu, null, Modifier.size(20.dp)); Spacer(Modifier.width(5.dp)); Text("Menü")
                }
            }
        }
    }
}

@Composable
private fun HomeLocateChip(locating: Boolean, onLocate: () -> Unit, modifier: Modifier = Modifier) {
    AssistChip(onClick = onLocate, modifier = modifier,
        label = { Text(if (locating) "Konum alınıyor…" else "Konumumu göster", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(Icons.Outlined.GpsFixed, null, Modifier.size(18.dp)) },
        colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.surface))
}

@Composable
private fun HomeWeatherChip(label: String, onWeather: () -> Unit, modifier: Modifier = Modifier) {
    AssistChip(onClick = onWeather, modifier = modifier.testTag("home-weather"),
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(Icons.Outlined.Cloud, null, Modifier.size(18.dp)) },
        colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.surface))
}

@Composable
private fun HomeBrand() {
    Surface(Modifier.fillMaxWidth().testTag("home-brand").semantics(mergeDescendants = true) {},
        shape = RoundedCornerShape(24.dp), color = Paper, shadowElevation = 2.dp) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Image(painterResource(R.drawable.ic_launcher), contentDescription = null,
                modifier = Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).testTag("home-brand-logo"))
            Column(Modifier.weight(1f)) {
                Text("İz", style = MaterialTheme.typography.headlineMedium, color = Forest)
                Text("Küçük yollar, güzel anılar.", style = MaterialTheme.typography.bodySmall, color = Muted)
            }
        }
    }
}

@Composable
private fun SpeedReadout(label: String, value: String, source: String, modifier: Modifier) {
    Surface(modifier, shape = RoundedCornerShape(16.dp), shadowElevation = 2.dp, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(value, style = MaterialTheme.typography.headlineSmall)
                Text("km/sa", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(bottom = 4.dp))
            }
            Text(source, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
