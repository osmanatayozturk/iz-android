package org.iz.navigation.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Check
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.iz.navigation.osmcatalog.*
import org.json.JSONObject

internal data class MapEditCatalogLoad(val result: Result<OsmPresetCatalog>?, val retry: () -> Unit)

@Composable
internal fun rememberMapEditCatalog(): MapEditCatalogLoad {
    val context = LocalContext.current.applicationContext
    val loader: suspend () -> OsmPresetCatalog = remember(context) {
        suspend { withContext(Dispatchers.IO) { OsmPresetCatalog.load(context.assets.open(OsmPresetCatalog.BUNDLED_ASSET)) } }
    }
    return rememberMapEditCatalog(loader)
}

@Composable
internal fun rememberMapEditCatalog(loader: suspend () -> OsmPresetCatalog): MapEditCatalogLoad {
    var attempt by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf<Result<OsmPresetCatalog>?>(null) }
    LaunchedEffect(loader, attempt) {
        val generation = attempt
        result = null
        try {
            val catalog = loader()
            currentCoroutineContext().ensureActive()
            if (generation == attempt) result = Result.success(catalog)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            if (generation == attempt) result = Result.failure(failure)
        }
    }
    return MapEditCatalogLoad(result) { result = null; attempt++ }
}

@Composable
internal fun resolveMapEditCatalog(catalog: OsmPresetCatalog?, inherited: MapEditCatalogLoad?): MapEditCatalogLoad =
    if (catalog != null) MapEditCatalogLoad(Result.success(catalog)) {} else inherited ?: rememberMapEditCatalog()

@Composable
internal fun MapEditCatalogStatus(load: MapEditCatalogLoad, enabled: Boolean) {
    when {
        load.result == null -> {
            Text("Tür kataloğu hazırlanıyor…")
            LinearProgressIndicator(Modifier.fillMaxWidth().testTag("osm-map-catalog-loading"))
        }
        load.result.isFailure -> {
            Text("Tür kataloğu yüklenemedi.")
            TextButton(onClick = load.retry, enabled = enabled,
                modifier = Modifier.heightIn(min = 48.dp).testTag("osm-map-catalog-retry")) { Text("Yeniden dene") }
        }
    }
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
    var pendingId by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedId = snapshot?.let { runCatching { OsmPresetRuleSnapshot.fromJson(it).presetId }.getOrNull() }
    val pendingPreset = catalog?.resolve(pendingId.orEmpty())?.takeIf { it.selectable && geometry in it.geometry && !it.requiresLocationValidation }
    OutlinedButton(onClick = { pendingId = selectedId; open = true }, enabled = enabled && catalog != null,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("osm-map-type")) {
        Text(mapEditPresetLabel(snapshot) ?: "Tür seç")
    }
    if (required && snapshot == null) Text("Bir tür seç.", style = MaterialTheme.typography.bodySmall)
    if (open) {
        val results = remember(catalog, query, geometry, favoriteIds) {
            catalog?.search(query, geometry, favoriteIds).orEmpty()
        }
        AlertDialog(onDismissRequest = { open = false },
            title = { Text("Tür kataloğu ve favoriler") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(query, { query = it }, singleLine = true,
                        label = { Text("Tür ara") }, modifier = Modifier.fillMaxWidth())
                    if (results.isEmpty()) Text("Tür bulunamadı. Başka bir ad veya etiket dene.")
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp).testTag("osm-map-preset-results")) {
                        val favorites = results.filter { it.id in favoriteIds }
                        if (favorites.isNotEmpty()) item { Text("Favoriler", style = MaterialTheme.typography.titleSmall) }
                        items(favorites, key = { it.id }) { option ->
                            PresetChoice(option, option.id == pendingId, true, enabled,
                                { pendingId = option.id },
                                { onFavorite(option.id, false) })
                        }
                        val others = results.filterNot { it.id in favoriteIds }
                        if (others.isNotEmpty()) item { Text("Tüm özellik türleri", style = MaterialTheme.typography.titleSmall) }
                        items(others, key = { it.id }) { option ->
                            PresetChoice(option, option.id == pendingId, false, enabled,
                                { pendingId = option.id },
                                { onFavorite(option.id, true) })
                        }
                    }
                }
            },
            confirmButton = { TextButton(enabled = enabled && pendingPreset != null, onClick = {
                val selected = pendingPreset?.id ?: return@TextButton
                onSelect(requireNotNull(catalog).snapshot(selected, geometry))
                open = false
            }, modifier = Modifier.testTag("osm-map-preset-use")) { Text("Türü kullan") } },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Kapat") } })
    }
}

@Composable
private fun PresetChoice(option: OsmPresetDefinition, chosen: Boolean, favorite: Boolean,
    enabled: Boolean, onChoose: () -> Unit, onFavorite: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onChoose, enabled = enabled,
            modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { selected = chosen }) {
            if (chosen) {
                Icon(Icons.Filled.Check, contentDescription = null)
                Spacer(Modifier.width(8.dp))
            }
            Text(option.name)
        }
        IconToggleButton(checked = favorite, onCheckedChange = { onFavorite() }, enabled = enabled,
            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics {
                contentDescription = "${option.name} ${if (favorite) "favorilerden çıkar" else "favorilere ekle"}"
            }) {
            Icon(if (favorite) Icons.Filled.Star else Icons.Outlined.StarBorder, contentDescription = null)
        }
    }
}
