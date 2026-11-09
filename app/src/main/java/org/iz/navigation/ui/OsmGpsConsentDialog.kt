package org.iz.navigation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.iz.navigation.integration.PreparedOsmGpxTrace
import org.iz.navigation.integration.osm.OsmGpxVisibility

internal data class OsmGpsConsentPreview(
    val trace: PreparedOsmGpxTrace,
    val index: Int,
    val count: Int,
)

@Composable
internal fun OsmGpsConsentDialog(
    username: String,
    visibility: OsmGpxVisibility,
    preview: OsmGpsConsentPreview,
    enabled: Boolean,
    onVisibility: (OsmGpxVisibility) -> Unit,
    onPreview: (Int) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scroll = rememberScrollState()
    LaunchedEffect(preview.index) { scroll.scrollTo(0) }
    var showPoints by remember(preview.trace) { mutableStateOf(false) }
    if (showPoints) {
        OsmGpsPointDetailsDialog(preview.trace) { showPoints = false }
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("osm-gps-consent"),
        title = { Text("${preview.count} GPS izini OSM'ye gönder?") },
        text = {
            Column(Modifier.verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("OSM hesabı $username")
                Text("Görünürlük ${visibility.label}")
                Text("OSM API'si her iki seçenekte de gönderilen noktaları ve kayıt zamanlarını paylaşır.")
                Text("Tanımlanabilir iz OSM hesabınla ilişkilendirilir ve herkese açık iz listesinde görünür.")
                Text("İz ${preview.index + 1}/${preview.count}")
                OsmGpsPreview(preview.trace, onShowPoints = { showPoints = true })
                Text("Başlangıç ve bitişten en az 1 km çıkarıldı.")
                if (preview.count > 1) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { onPreview(preview.index - 1) }, enabled = enabled && preview.index > 0,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("osm-gps-preview-previous")) {
                            Text("Önceki iz")
                        }
                        TextButton(onClick = { onPreview(preview.index + 1) },
                            enabled = enabled && preview.index + 1 < preview.count,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("osm-gps-preview-next")) {
                            Text("Sonraki iz")
                        }
                    }
                }
                Text("Bu gönderimdeki tüm izler için görünürlük")
                Column(Modifier.selectableGroup()) {
                    OsmGpxVisibility.entries.forEach { option ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("osm-gps-visibility-${option.wireValue}")
                                .selectable(visibility == option, enabled = enabled, role = Role.RadioButton,
                                    onClick = { onVisibility(option) }),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            RadioButton(selected = visibility == option, onClick = null, enabled = enabled)
                            Text(option.label)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = enabled,
                modifier = Modifier.heightIn(min = 48.dp).testTag("osm-gps-consent-confirm")) {
                Text("${preview.count} izi OSM'ye gönder")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp).testTag("osm-gps-consent-cancel")) {
                Text("Vazgeç")
            }
        },
    )
}

private val OsmGpxVisibility.label: String
    get() = when (this) {
        OsmGpxVisibility.IDENTIFIABLE -> "Tanımlanabilir"
        OsmGpxVisibility.TRACKABLE -> "Takip Edilebilir"
    }
