package org.iz.navigation.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Supplied display values only: no producer, clock, saved action or map owner. */
internal data class NavigationDashboardViewData(
    val speed: String,
    val speedLimit: String,
    val limitSource: String,
    val status: String,
    val maneuver: String?,
    val remaining: String?,
    val warnings: List<String>,
    val weather: String,
    val weatherDetail: String?,
    val group: String,
    val groupDetail: String?,
    val analysis: String,
    val guidance: Boolean,
    val muted: Boolean,
    val sessionActive: Boolean,
)

/** Markup only; callers retain actual entry/session/command ownership. */
@Composable
internal fun NavigationDashboardPanel(data: NavigationDashboardViewData, locked: Boolean,
    onClose: () -> Unit, onMute: () -> Unit, onManage: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier.testTag("navigation-dashboard"), tonalElevation = 3.dp) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Gösterge", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onClose, modifier = Modifier.sizeIn(minHeight = 48.dp).testTag("close-navigation-dashboard")) { Text("Kapat") }
            }
            // The immediate riding context does not leave the viewport when details scroll.
            Column(Modifier.fillMaxWidth().testTag("dashboard-critical-context"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(data.status, style = MaterialTheme.typography.labelSmall)
                data.maneuver?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
                data.warnings.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item(key = "speed") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DashboardSpeed("Hızım", data.speed, "GPS", Modifier.weight(1f))
                        DashboardSpeed("Hız sınırı", data.speedLimit, data.limitSource, Modifier.weight(1f))
                    }
                }
                data.remaining?.let { value -> item(key = "remaining") { Text(value) } }
                item(key = "weather") {
                    Text("Hava durumu", style = MaterialTheme.typography.titleMedium)
                    Text(data.weather)
                    data.weatherDetail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
                item(key = "group") {
                    Text("Grup", style = MaterialTheme.typography.titleMedium)
                    Text(data.group)
                    data.groupDetail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
                item(key = "analysis") {
                    Text("Yolculuk ayrıntıları", style = MaterialTheme.typography.titleMedium)
                    Text(data.analysis)
                }
            }
            if (data.sessionActive) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (data.guidance) OutlinedButton(onClick = onMute, enabled = !locked,
                    modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp).testTag("dashboard-mute")) {
                    Text(if (data.muted) "Sesi aç" else "Sesi kapat")
                }
                Button(onClick = onManage, enabled = !locked,
                    modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp).testTag("dashboard-session-actions")) { Text("Yolculuk işlemleri") }
            }
        }
    }
}

@Composable
private fun DashboardSpeed(label: String, value: String, source: String, modifier: Modifier) {
    Card(modifier) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(value, style = MaterialTheme.typography.headlineMedium)
            Text("km/sa", style = MaterialTheme.typography.labelMedium)
            Text(source, style = MaterialTheme.typography.bodySmall)
        }
    }
}
