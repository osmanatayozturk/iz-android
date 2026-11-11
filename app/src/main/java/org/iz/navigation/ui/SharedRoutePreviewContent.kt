package org.iz.navigation.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** The explicit start stays outside scrolling/map content, including short/large-font viewports. */
@Composable
internal fun SharedRoutePreviewContent(title: String, record: Boolean, error: String?, startEnabled: Boolean,
    modifier: Modifier = Modifier, previewMap: @Composable () -> Unit, onStart: () -> Unit) {
    Column(modifier.fillMaxSize().testTag("shared-preview-content"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val mapHeight = (maxHeight * .5f).coerceAtLeast(72.dp)
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Box(Modifier.fillMaxWidth().height(mapHeight)) { previewMap() }
                Text(if (record) "Bu yolculuk kaydedilecek." else "Bu yolculuk kaydedilmeyecek.")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
        Button(onStart, enabled = startEnabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("shared-start")) { Text("Sürüşe başla") }
    }
}
