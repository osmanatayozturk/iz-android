@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package org.iz.navigation.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.iz.navigation.data.Photo

@Composable
internal fun PhotoShareSheet(photos: List<Photo>, onDismiss: () -> Unit, onShare: (List<Photo>) -> Unit, onGallery: (List<Photo>) -> Unit) {
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var reviewing by remember { mutableStateOf(false) }
    val chosen = photos.filter { it.id in selected }
    val selectionScroll = rememberScrollState()
    val reviewScroll = rememberScrollState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(if (reviewing) reviewScroll else selectionScroll)
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Fotoğraflarını paylaş", style = MaterialTheme.typography.headlineSmall)
            Text("${chosen.size} fotoğraf seçili", style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.testTag("photo-share-count").semantics { liveRegion = LiveRegionMode.Polite })
            if (reviewing && chosen.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth().testTag("photo-share-preview"),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(chosen, key = Photo::id) { photo ->
                        DiaryPhotoImage(photo, description = photo.caption.ifBlank { "Seçili fotoğraf" },
                            modifier = Modifier.size(88.dp).testTag("photo-share-preview-${photo.id}"))
                    }
                }
            }
            Text("Paylaşılan ve galeriye kaydedilen kopyalarda dosyaya ekli konum ve çekim bilgileri kaldırılır. Özgün fotoğraflar İz’de korunur. Büyük fotoğraflar küçültülebilir.", color = Muted)
            if (reviewing) {
                Button(onClick = { onShare(chosen) }, enabled = chosen.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                    Text("Seçilen fotoğrafları paylaş")
                }
                OutlinedButton(onClick = { onGallery(chosen) }, enabled = chosen.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                    Text("Galeriye kaydet")
                }
                TextButton(onClick = { reviewing = false }, modifier = Modifier.fillMaxWidth()) { Text("Seçimi değiştir") }
            } else {
                if (photos.isEmpty()) Text("Paylaşılabilecek fotoğraf yok.", color = Muted)
                photos.forEachIndexed { index, photo ->
                    val checked = photo.id in selected
                    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .toggleable(checked, role = Role.Checkbox, onValueChange = { value ->
                                    selected = if (value) selected + photo.id else selected - photo.id
                                })
                                .testTag("photo-share-select-${photo.id}").padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Checkbox(checked, onCheckedChange = null)
                            DiaryPhotoImage(photo, description = null, modifier = Modifier.size(56.dp))
                            Text(photo.caption.ifBlank { "Fotoğraf ${index + 1}" }, Modifier.weight(1f))
                        }
                    }
                }
                Button(onClick = { reviewing = true }, enabled = chosen.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                    Text("Seçimi incele")
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Vazgeç") }
        }
    }
}
