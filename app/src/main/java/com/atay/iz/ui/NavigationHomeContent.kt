@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.atay.iz.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.atay.iz.data.GeoCoordinate
import com.atay.iz.data.TrackPoint
import com.atay.iz.integration.RouteMapMember
import com.atay.iz.integration.WeatherRouteMap
import com.atay.iz.navigation.NavigationState
import kotlin.math.roundToInt

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
    onFinish: () -> Unit,
    onLocate: () -> Unit,
    onPin: (GeoCoordinate) -> Unit,
    planner: @Composable () -> Unit,
) {
    val presentation = navigationMapPresentation(ui, nav, points, showPlanner)
    val live = presentation.sessionActive
    val route = presentation.route
    val current = presentation.coordinate
    val density = LocalDensity.current
    var searchHeight by remember { mutableStateOf(80.dp) }
    BoxWithConstraints(Modifier.fillMaxSize().testTag("navigation-home")) {
        WeatherRouteMap(route = route, assessment = null, modifier = Modifier.fillMaxSize().testTag("navigation-home-map"),
            expandable = false, stops = presentation.stops, liveCoordinate = current,
            recordedPoints = presentation.trail, gpsStale = live && nav.gpsStale,
            cameraIdentity = presentation.cameraIdentity,
            navigationLayout = true, members = members, onLocate = onLocate, onMapLongClick = onPin,
            attributionBottomInset = if (showPlanner) maxHeight * .64f + 4.dp else 92.dp,
            cameraViewportInsets = PaddingValues(start = 28.dp, top = searchHeight + 60.dp, end = 84.dp,
                bottom = if (showPlanner) maxHeight * .64f + 24.dp else 112.dp))
        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(onClick = onSearch, modifier = Modifier.fillMaxWidth().onSizeChanged { searchHeight = with(density) { it.height.toDp() } }.testTag("open-navigation"),
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
            if (!showPlanner && live) Surface(shape = RoundedCornerShape(20.dp), shadowElevation = 2.dp,
                color = MaterialTheme.colorScheme.surface.copy(alpha = .98f), modifier = Modifier.fillMaxWidth().padding(end = 60.dp).testTag("live-guidance-card")) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (nav.simulation) "SİMÜLASYON" else if (nav.recording) "KAYIT AÇIK" else "KAYITSIZ YOLCULUK",
                            Modifier.weight(1f), color = Forest, style = MaterialTheme.typography.labelSmall)
                        if (nav.guidance) IconButton(onMute, Modifier.size(40.dp)) {
                            Icon(if (nav.muted) Icons.Outlined.VolumeOff else Icons.Outlined.VolumeUp,
                                if (nav.muted) "Sesi aç" else "Sesi kapat")
                        }
                    }
                    if (nav.guidance) {
                        nav.progress?.nextManeuverDistanceMeters?.let { meters ->
                            Text(if (meters >= 1000) "%.1f km sonra".format(meters / 1000) else "${meters.roundToInt().coerceAtLeast(0)} m sonra",
                                style = MaterialTheme.typography.titleLarge, color = Forest)
                        }
                        Text(navigationInstruction(nav), fontSize = 24.sp, lineHeight = 29.sp,
                            maxLines = 3, overflow = TextOverflow.Ellipsis)
                        route?.let { RouteTimingText(it, nav, preview = false) }
                    } else Text("${nav.sessionTransport?.label().orEmpty()} · Serbest yolculuk", style = MaterialTheme.typography.titleLarge)
                    if (nav.gpsStale) Text("GPS konumu bekleniyor", color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                    if (!nav.locationActive && !nav.simulation) Text("Konum bağlantısı kuruluyor", style = MaterialTheme.typography.bodySmall)
                    nav.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    TextButton(onFinish, modifier = Modifier.align(Alignment.End).testTag("home-finish-session")) { Text("Yolculuğu bitir") }
                }
            }
            if (!showPlanner && !live && current == null) AssistChip(onClick = onLocate,
                label = { Text(if (ui.locating) "Konum alınıyor…" else "Konumumu göster") },
                leadingIcon = { Icon(Icons.Outlined.GpsFixed, null, Modifier.size(18.dp)) },
                colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.surface))
            if (!showPlanner && ui.message != null) Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
                Text(ui.message, Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall)
            }
            if (!showPlanner) AssistChip(onClick = onWeather, label = { Text(weatherLabel) },
                leadingIcon = { Icon(Icons.Outlined.Cloud, null, Modifier.size(18.dp)) },
                modifier = Modifier.testTag("home-weather"),
                colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.surface))
        }
        if (showPlanner) Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(.64f).padding(bottom = 88.dp),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp), shadowElevation = 8.dp,
            color = MaterialTheme.colorScheme.surface) { planner() }
        Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
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




