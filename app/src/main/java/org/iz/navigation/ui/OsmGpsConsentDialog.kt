package org.iz.navigation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.iz.navigation.integration.PreparedOsmGpxTrace

internal data class OsmGpsConsentPreview(
    val trace: PreparedOsmGpxTrace,
    val index: Int,
    val count: Int,
    val totalDistanceMeters: Double = trace.distanceMeters,
)

@Composable
internal fun OsmGpsConsentDialog(
    username: String,
    preview: OsmGpsConsentPreview,
    enabled: Boolean,
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
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize().testTag("osm-gps-consent")) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss, enabled = enabled) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "GPS izi adaylarına dön")
                    }
                    Text("GPS önizleme ve onay", style = MaterialTheme.typography.titleLarge)
                }
                HorizontalDivider()
                Column(Modifier.weight(1f).verticalScroll(scroll).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${preview.count} GPS izini OSM'ye gönder?", style = MaterialTheme.typography.titleMedium)
                    Text("OSM hesabı $username")
                    Text("Takip Edilebilir (Trackable)", Modifier.testTag("osm-gps-visibility-trackable"),
                        style = MaterialTheme.typography.titleSmall)
                    Text("Yeni izler bu görünürlükle gönderilir. Noktalar ve kayıt zamanları OSM API’sinde paylaşılır. İz, herkese açık iz listesinde hesap adınla gösterilmez.")
                    Text("Gönderilecek toplam ${osmGpsDistance(preview.totalDistanceMeters)}",
                        Modifier.testTag("osm-gps-plan-distance"))
                    Text("İz ${preview.index + 1}/${preview.count}")
                    OsmGpsPreview(preview.trace, onShowPoints = { showPoints = true })
                    Text("Başlangıç ve bitişten en az 1 km çıkarıldı. Daha önce paylaşılan aynı yönlü bölümler çıkarıldı. Seçili izler aynı yönde örtüşüyorsa bölüm en eski izde kalır.")
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
                }
                HorizontalDivider()
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Button(onClick = onConfirm, enabled = enabled,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("osm-gps-consent-confirm")) {
                        Text("Takip Edilebilir olarak gönder · ${preview.count} iz")
                    }
                    TextButton(onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("osm-gps-consent-cancel")) {
                        Text("Vazgeç")
                    }
                }
            }
        }
    }
}
