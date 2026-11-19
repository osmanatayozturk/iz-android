package org.iz.navigation.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.iz.navigation.navigation.NavigationState
import kotlin.math.ceil

internal data class CompactHudData(val instruction: String, val next: String, val remaining: String,
    val speed: String, val speedDescription: String, val weather: String, val warnings: List<String>,
    val muted: Boolean, val guidance: Boolean, val gpsWarning: Boolean = false, val recordingWarning: Boolean = false) {
    val summaryText: String get() =
        "${next.removePrefix("Manevra ")} · ${remaining.removePrefix("Kalan ").replaceFirst(" · ", " / ")}".replace('.', ',')
    val summaryDescription: String get() = "$next · $remaining"
}

internal fun compactHudData(nav: NavigationState, weather: String, message: String?, voiceFault: String?): CompactHudData {
    val current = nav.guidance && !nav.gpsStale && !nav.paused && nav.approximateOrigin == null
    fun distance(value: Double?) = value?.takeIf { it.isFinite() && it >= 0 }?.let {
        if (it >= 1000) "%.1f km".format(java.util.Locale.ROOT, it / 1000) else "${it.toInt()} m"
    } ?: "—"
    val seconds = nav.tripTiming?.totalSeconds ?: nav.progress?.remainingSeconds?.takeIf {
        nav.route?.stops?.none { stop -> stop.plannedBreakSeconds > 0 } == true
    }
    val minutes = seconds?.takeIf { current && it.isFinite() && it >= 0 }?.let { "${ceil(it / 60).toInt()} dk" } ?: "—"
    val breakLabel = nav.tripTiming?.breakSeconds?.takeIf { it > 0 }?.let { " · ${ceil(it / 60).toInt()} dk mola dahil" }.orEmpty()
    val speed = compactRoadSpeedReadout(nav.roadSpeed)
    val simulationRepeat = "Deneme sürüşü · günlüğe kaydedilmez"
    fun visibleMessage(value: String?) = value?.takeUnless { nav.simulation && it == simulationRepeat }
    val warnings = buildList {
        if (nav.gpsStale) add("GPS konumu bekleniyor")
        if (nav.recordingPending) add("Kayıt: hassas ve güvenilir konum bekleniyor")
        if (nav.simulation) add("Simülasyon")
        visibleMessage(message)?.let(::add)
        visibleMessage(nav.message)?.takeUnless { it == message }?.let(::add)
        if (nav.approximateOrigin != null) add("Yaklaşık konum")
        if (nav.paused) add("Duraklatıldı")
        if (!nav.locationActive && !nav.simulation) add("Konum bağlantısı kuruluyor")
        voiceFault?.let(::add)
    }
    return CompactHudData(navigationInstruction(nav),
        if (nav.plannedBreak != null) "Planlı mola" else "Manevra ${distance(nav.progress?.nextManeuverDistanceMeters.takeIf { current })}",
        "Kalan ${distance(nav.progress?.remainingMeters.takeIf { current })} · $minutes$breakLabel",
        if (nav.gpsStale || nav.paused || nav.approximateOrigin != null) "—/—" else speed.value,
        if (nav.gpsStale || nav.paused || nav.approximateOrigin != null) "Güncel hız ve hız sınırı bilinmiyor" else speed.description,
        weather, warnings, nav.muted, nav.guidance, nav.gpsStale, nav.recordingPending)
}

/** Two-line guidance slots keep the map steady across maneuvers. Extra status appears only when needed. */
@Composable
internal fun CompactDrivingHud(data: CompactHudData, landscape: Boolean, onMute: () -> Unit,
    onManage: () -> Unit, onWeather: () -> Unit, onRecoverRecording: (() -> Unit)? = null,
    onResumeBreak: (() -> Unit)? = null, resumeEnabled: Boolean = false,
    disclosure: @Composable () -> Unit = {}, controls: @Composable () -> Unit) {
    val scale = LocalDensity.current.fontScale
    val instruction: @Composable () -> Unit = {
        Box(Modifier.fillMaxWidth().height((44 * scale).dp)) {
            Text(data.instruction, Modifier.fillMaxWidth().testTag("hud-instruction").semantics { contentDescription = data.instruction },
                fontSize = 20.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
    val summary: @Composable () -> Unit = {
        Box(Modifier.fillMaxWidth().height((34 * scale).dp)) {
            Text(data.summaryText, Modifier.fillMaxWidth().testTag("hud-remaining").semantics { contentDescription = data.summaryDescription },
                fontSize = 15.sp, lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
    val commands: @Composable () -> Unit = {
        Row {
            if (data.guidance) IconButton(onMute, Modifier.size(48.dp).testTag("compact-card-mute")) {
                Icon(if (data.muted) Icons.Outlined.VolumeOff else Icons.Outlined.VolumeUp, if (data.muted) "Sesi aç" else "Sesi kapat")
            }
            IconButton(onManage, Modifier.size(48.dp).testTag("home-finish-session")) {
                Icon(Icons.Outlined.MoreVert, "Yolculuk işlemlerini aç")
            }
        }
    }
    val warnings: @Composable () -> Unit = {
        val visible = buildList {
            if (data.gpsWarning) add("GPS bekleniyor")
            if (data.recordingWarning) add("Kayıt bekleniyor")
            data.warnings.filterNot { data.gpsWarning && it == "GPS konumu bekleniyor" ||
                data.recordingWarning && it == "Kayıt: hassas ve güvenilir konum bekleniyor" }.forEach(::add)
        }
        if (visible.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().testTag("hud-reserved-status")
                .semantics { contentDescription = data.warnings.joinToString(" · ") }) {
                visible.forEach { warning ->
                    Text(warning, Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp, lineHeight = 15.sp)
                }
            }
        }
    }
    val preparedDisclosure: @Composable () -> Unit = {
        Box(Modifier.fillMaxWidth().testTag("hud-prepared-status-slot")) {
            MaterialTheme(typography = MaterialTheme.typography.copy(
                labelSmall = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp))) {
                disclosure()
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val extraActions = onRecoverRecording != null || onResumeBreak != null
        val controlsHeight = if (extraActions) 96.dp else 48.dp
        val textBudget = (maxHeight - minOf(maxHeight * .30f, 160.dp) - controlsHeight - 4.dp).coerceAtLeast(0.dp)
        val wide = landscape && maxWidth >= 480.dp
        Surface(Modifier.fillMaxWidth().testTag("compact-guidance-header")) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) {
                Column(Modifier.fillMaxWidth().heightIn(max = textBudget).verticalScroll(rememberScrollState())
                    .testTag("live-guidance-card").clickable(onClickLabel = "Yolculuk işlemlerini aç", onClick = onManage)) {
                    if (wide && scale <= 1.3f) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.weight(.55f)) { instruction() }
                            Box(Modifier.weight(.45f)) { summary() }
                        }
                        warnings()
                        preparedDisclosure()
                    } else if (wide) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.weight(.55f)) { instruction() }
                            Column(Modifier.weight(.45f)) { warnings(); preparedDisclosure() }
                        }
                        summary()
                    } else {
                        instruction()
                        summary()
                        warnings()
                        preparedDisclosure()
                    }
                }
                Row(Modifier.fillMaxWidth().height(48.dp).testTag("navigation-map-controls-row"),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(data.speed, Modifier.weight(1f).testTag("road-speed-panel").semantics { contentDescription = data.speedDescription },
                        style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TextButton(onWeather, Modifier.weight(1f).widthIn(min = 48.dp).height(48.dp).testTag("hud-weather")
                        .semantics { contentDescription = "Hava durumu: ${data.weather}" },
                        contentPadding = PaddingValues(horizontal = 4.dp)) {
                        Icon(Icons.Outlined.Cloud, null, Modifier.size(18.dp))
                        Text(data.weather, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    commands()
                    controls()
                }
                if (extraActions) Row(Modifier.fillMaxWidth().height(48.dp), horizontalArrangement = Arrangement.End) {
                    onRecoverRecording?.let { action -> IconButton(action, Modifier.size(48.dp).testTag("recording-permission-settings")) {
                        Icon(Icons.Outlined.Settings, "Kayıt için konum iznini düzelt")
                    } }
                    onResumeBreak?.let { action -> IconButton(action, Modifier.size(48.dp).testTag("hud-resume-break"), enabled = resumeEnabled) {
                        Icon(Icons.Outlined.PlayArrow, "Planlı molayı bitir ve devam et")
                    } }
                }
            }
        }
    }
}
