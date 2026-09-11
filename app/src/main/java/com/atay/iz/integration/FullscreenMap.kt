package com.atay.iz.integration

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Previews open a separate map window, outside any scrollable page or draggable sheet. */
@Composable
fun ExpandableMap(
    modifier: Modifier = Modifier,
    title: String = "Harita",
    expandable: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (Modifier) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    if (!expandable) {
        content(modifier)
        return
    }
    Box(modifier) {
        content(Modifier.fillMaxSize())
        // A preview tap only opens the map; it must not also choose a place underneath.
        Box(Modifier.matchParentSize()
            .semantics { contentDescription = "Haritayı tam ekran aç" }
            .clickable { expanded = true })
        Surface(
            onClick = { expanded = true },
            modifier = Modifier.align(Alignment.TopEnd).padding(10.dp),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surface.copy(alpha = .96f),
        ) {
            Icon(Icons.Outlined.Fullscreen, "Haritayı büyüt", Modifier.padding(8.dp).size(24.dp))
        }
    }
    if (expanded) FullscreenMapDialog(title, onDismiss = { expanded = false }, actions = actions, content = content)
}

@Composable
fun FullscreenMapDialog(
    title: String,
    onDismiss: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (Modifier) -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(Modifier.fillMaxSize().testTag("fullscreen_map")) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "Tam ekran haritayı kapat") }
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    actions()
                }
                Box(Modifier.weight(1f).fillMaxWidth().testTag("fullscreen_map_viewport")) {
                    content(Modifier.fillMaxSize())
                }
                bottomBar()
            }
        }
    }
}
