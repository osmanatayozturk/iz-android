package org.iz.navigation.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.iz.navigation.osmcatalog.*
import org.json.JSONObject

@Composable
internal fun rememberMapEditCatalog(): Result<OsmPresetCatalog>? {
    val context = LocalContext.current.applicationContext
    return produceState<Result<OsmPresetCatalog>?>(null, context) {
        value = withContext(Dispatchers.IO) {
            runCatching { OsmPresetCatalog.load(context.assets.open(OsmPresetCatalog.BUNDLED_ASSET)) }
        }
    }.value
}

internal fun mapEditPresetLabel(snapshot: String?): String? = snapshot?.let {
    runCatching { JSONObject(OsmPresetRuleSnapshot.fromJson(it).ruleJson).getString("name") }.getOrNull()
}

/** Presentation of the pinned core catalogue; selection never changes a favorite. */
@Composable
internal fun MapEditPresetSelector(
    catalog: OsmPresetCatalog?, geometry: OsmPresetGeometry, snapshot: String?,
    favoriteIds: Set<String>, enabled: Boolean,
    onSelect: (OsmPresetRuleSnapshot) -> Unit, onFavorite: (String, Boolean) -> Unit,
    required: Boolean = true,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val selectedId = snapshot?.let { runCatching { OsmPresetRuleSnapshot.fromJson(it).presetId }.getOrNull() }
    OutlinedButton(onClick = { open = true }, enabled = enabled && catalog != null,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("osm-map-type")) {
        Text(mapEditPresetLabel(snapshot) ?: "Tür seç")
    }
    if (required && snapshot == null) Text("Bir tür seç.", style = MaterialTheme.typography.bodySmall)
    if (open) {
        val results = remember(catalog, query, geometry, favoriteIds) {
            catalog?.search(query, geometry, favoriteIds).orEmpty()
        }
        AlertDialog(onDismissRequest = { open = false },
            title = { Text("Tür seç") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(query, { query = it }, singleLine = true,
                        label = { Text("Tür ara") }, modifier = Modifier.fillMaxWidth())
                    if (results.isEmpty()) Text("Tür bulunamadı. Başka bir ad veya etiket dene.")
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                        val favorites = results.filter { it.id in favoriteIds }
                        if (favorites.isNotEmpty()) item { Text("Favoriler", style = MaterialTheme.typography.titleSmall) }
                        items(favorites, key = { it.id }) { option ->
                            PresetChoice(option, option.id == selectedId, true, enabled,
                                { onSelect(requireNotNull(catalog).snapshot(option.id, geometry)); open = false },
                                { onFavorite(option.id, false) })
                        }
                        val others = results.filterNot { it.id in favoriteIds }
                        if (favorites.isNotEmpty() && others.isNotEmpty()) item { Text("Diğer türler", style = MaterialTheme.typography.titleSmall) }
                        items(others, key = { it.id }) { option ->
                            PresetChoice(option, option.id == selectedId, false, enabled,
                                { onSelect(requireNotNull(catalog).snapshot(option.id, geometry)); open = false },
                                { onFavorite(option.id, true) })
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text("Kapat") } })
    }
}

@Composable
private fun PresetChoice(option: OsmPresetDefinition, chosen: Boolean, favorite: Boolean,
    enabled: Boolean, onChoose: () -> Unit, onFavorite: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onChoose, enabled = enabled,
            modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { selected = chosen }) {
            Text(if (chosen) "✓ ${option.name}" else option.name)
        }
        IconToggleButton(checked = favorite, onCheckedChange = { onFavorite() }, enabled = enabled,
            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics {
                contentDescription = "${option.name} ${if (favorite) "favorilerden çıkar" else "favorilere ekle"}"
            }) {
            Icon(if (favorite) Icons.Filled.Star else Icons.Outlined.StarBorder, contentDescription = null)
        }
    }
}
