package org.iz.navigation.watch

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.*
import kotlinx.coroutines.launch
import org.iz.navigation.wearprotocol.*

@Composable
internal fun WatchSurfaceDetail(data: WatchSurfaceData, route: WatchSurfaceRoute, now: Long, onControls: () -> Unit) {
    val list = rememberScalingLazyListState()
    val focus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { focus.requestFocus() }
    val snapshot = when (route) {
        WatchSurfaceRoute.DAILY -> data.snapshot?.copy(navigation = null, journeyId = null, recording = false)
        WatchSurfaceRoute.RECORDING -> data.snapshot?.copy(navigation = null)
        else -> data.snapshot
    }
    val frame = WatchSurfacePolicy.frame(snapshot, data.connected, now)
    val day = WatchSurfacePolicy.day(snapshot, now)
    val health by WatchHealthRuntime.state.collectAsState()
    val context = LocalContext.current
    val heart = remember(health, data, now) { safeLiveHeart(context, data, now) }
    MaterialTheme(colors = Colors(primary = Color(0xFFB0D998), onPrimary = Color(0xFF091B15),
        background = Color(0xFF091B15), surface = Color(0xFF163328), onSurface = Color(0xFFF0F4DF))) {
        Scaffold(modifier = Modifier.fillMaxSize().background(Color(0xFF091B15)),
            timeText = { TimeText(modifier = Modifier.scrollAway(list)) },
            positionIndicator = { PositionIndicator(scalingLazyListState = list) }) {
            ScalingLazyColumn(state = list, modifier = Modifier.fillMaxSize().testTag("surface-detail")
                .onRotaryScrollEvent { scope.launch { list.scrollBy(it.verticalScrollPixels) }; true }
                .focusRequester(focus).focusable(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 36.dp)) {
                item { Text(frame.title, color = Color(0xFFB0D998), fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) }
                frame.lines.forEach { line -> item { Text(line, textAlign = TextAlign.Center, color = Color(0xFFF0F4DF), modifier = Modifier.padding(vertical = 4.dp)) } }
                if (route == WatchSurfaceRoute.DAILY && day != null) {
                    item { Text("Samsung Health · tüm gün\nİz kaydı ayrı gösterilir", textAlign = TextAlign.Center, fontSize = 12.sp) }
                    item { Text("Adım: ${status(day.stepsStatus)}\nEgzersiz: ${status(day.exerciseStatus)}\nMesafe: ${status(day.distanceStatus)}", textAlign = TextAlign.Center, fontSize = 12.sp) }
                    item { Text("Sağlık kontrolü: ${day.healthCheckedAt?.let { WatchSurfacePolicy.clock(it, day.zoneId) } ?: "yok"}\n${day.localDate}", textAlign = TextAlign.Center, fontSize = 12.sp) }
                }
                if (route == WatchSurfaceRoute.RECORDING && snapshot?.recording == true && !snapshot.temporary) {

                    if (heart != null) item { Text("Canlı nabız · ${heart.toInt()} atım/dk", color = Color(0xFFB0D998), textAlign = TextAlign.Center) }
                    item { Text("Toplam enerji dinlenmeyi içerir; Samsung Health aktarımı gecikebilir.", fontSize = 12.sp, textAlign = TextAlign.Center) }
                }
                if (!data.connected) item { Text("Telefon bağlı değil · son alınan veri", textAlign = TextAlign.Center, fontSize = 12.sp) }
                item { Chip(onClick = onControls, label = { Text("Kayıt ve ayarlar") }, modifier = Modifier.fillMaxWidth().testTag("surface-controls")) }
            }
        }
    }
}
private fun status(value: WearDailyHealthStatus) = when (value) {
    WearDailyHealthStatus.AVAILABLE -> "veri var"
    WearDailyHealthStatus.PARTIAL -> "kısmi veri"
    WearDailyHealthStatus.PERMISSION_REQUIRED -> "telefonda izin gerekli"
    WearDailyHealthStatus.UNAVAILABLE -> "ölçüm yok"
}

@Preview(device = "spec:width=180dp,height=180dp,dpi=320,isRound=true", showSystemUi = false)
@Composable private fun SmallRoundSurfacePreview() { WatchSurfaceDetail(WatchSurfaceData(), WatchSurfaceRoute.DAILY, 0, {}) }
@Preview(device = "spec:width=227dp,height=227dp,dpi=320,isRound=true", showSystemUi = false)
@Composable private fun LargeRoundSurfacePreview() { WatchSurfaceDetail(WatchSurfaceData(), WatchSurfaceRoute.NAVIGATION, 0, {}) }
