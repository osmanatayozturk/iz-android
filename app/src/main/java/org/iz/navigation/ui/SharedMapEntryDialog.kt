package org.iz.navigation.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import org.iz.navigation.car.SharedMapInput
import org.iz.navigation.car.SharedMapStop
import org.iz.navigation.car.SharedMapLinks
import org.iz.navigation.integration.MapLinkResolver
import org.iz.navigation.integration.MediaShare
import org.iz.navigation.weather.RouteStop
import org.iz.navigation.data.SelectedOsmPlace

/** Unresolved occurrences stay indexed; repeated names are never collapsed into a set. */
internal data class SharedMapDraft(val stops: List<SharedMapStop>, val dynamicOrigin: Boolean, val single: Boolean) {
    val complete get() = stops.isNotEmpty() && stops.all { it.coordinate != null }
    fun choose(index: Int, stop: SharedMapStop) = copy(stops = stops.toMutableList().apply { set(index, stop) })
}

internal fun SharedMapInput.draft(): SharedMapDraft? = when (this) {
    is SharedMapInput.Single -> SharedMapDraft(listOf(target), false, true)
    is SharedMapInput.Itinerary -> SharedMapDraft(
        listOfNotNull(origin?.takeUnless { it.currentLocation }) + via + destination,
        origin == null || origin.currentLocation, false)
    else -> null
}

internal fun sharedSelectedStop(selected: SelectedOsmPlace): SharedMapStop = SharedMapStop(
    selected.name.trim().filterNot { it.code < 32 }.take(300).ifBlank { "Hedef" },
    org.iz.navigation.weather.WeatherCoordinate(selected.latitude, selected.longitude))

@Composable
internal fun SharedMapEntryDialog(input: SharedMapInput, onClose: () -> Unit,
    onSingle: (RouteStop) -> Unit, onItinerary: (List<RouteStop>, Boolean) -> Unit,
    stillCurrent: () -> Boolean = { true }, configuration: () -> String = { "" },
    identity: () -> Any? = { configuration() },
    resolve: suspend (String) -> SharedMapInput? = { MapLinkResolver().resolve(it) }) {
    val context = LocalContext.current
    val currentOwner by rememberUpdatedState(stillCurrent)
    val currentConfiguration by rememberUpdatedState(configuration)
    val currentResolver by rememberUpdatedState(resolve)
    val currentIdentity by rememberUpdatedState(identity)
    val scope = rememberCoroutineScope()
    val requests = remember(scope, input) { SharedMapRequestOwner(scope, { currentResolver(it) },
        { currentIdentity() }, { currentOwner() }) }
    val response by requests.state.collectAsStateWithLifecycle()
    DisposableEffect(requests) { onDispose { requests.close() } }
    var savedInput by rememberSaveable(input, stateSaver = SharedMapInputSaver) { mutableStateOf<SharedMapInput?>(input) }
    val resolved = savedInput ?: input
    val draft = resolved.draft()
    val loading = response.busy
    var error by remember(input) { mutableStateOf<String?>(null) }
    var retry by remember(input) { mutableIntStateOf(0) }
    var choosing by rememberSaveable(input) { mutableStateOf<Int?>(null) }
    var handedOff by remember(input) { mutableStateOf(false) }
    LaunchedEffect(resolved) {
        val candidate = draft
        if (input is SharedMapInput.ShortLink && response.result != null && !requests.accepts(response))
            return@LaunchedEffect
        if (candidate?.complete == true && currentOwner() && !handedOff) {
            handedOff = true
            val stops = candidate.stops.map { RouteStop(it.name, requireNotNull(it.coordinate)) }
            if (candidate.single) onSingle(stops.single()) else onItinerary(stops, candidate.dynamicOrigin)
        }
    }
    LaunchedEffect(input, retry) {
        if (input !is SharedMapInput.ShortLink || resolved !is SharedMapInput.ShortLink) return@LaunchedEffect
        requests.submit(input.url)
    }
    LaunchedEffect(response) {
        if (response.result != null && requests.accepts(response)) { savedInput = response.result; error = null }
        else if (!response.busy && response.error != null && currentOwner()) error = response.error
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().fillMaxHeight(.9f).padding(12.dp), shape = MaterialTheme.shapes.extraLarge) {
            LazyColumn(Modifier.padding(20.dp).testTag("shared-map-entry"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Text("Paylaşılan yer", Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
                    Text("Paylaşımı almak veya önizlemek yolculuğu başlatmaz.")
                    TextButton(onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text("Geri") }
                }
                if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Bağlantı çözümleniyor…") }
                error?.let { message -> item {
                    Text(message, color = MaterialTheme.colorScheme.error)
                    TextButton({ retry++ }, enabled = !loading, modifier = Modifier.heightIn(min = 48.dp)) { Text("Yeniden dene") }
                } }
                val unsupported = resolved as? SharedMapInput.Unsupported
                unsupported?.let { item {
                    Text(it.message)
                    if (input is SharedMapInput.ShortLink) {
                        TextButton(onClick = {
                            if (currentOwner() && !loading) {
                                savedInput = input
                                error = null
                                retry++
                            }
                        }, enabled = !loading, modifier = Modifier.heightIn(min = 48.dp)
                            .testTag("shared-link-retry")) { Text("Yeniden dene") }
                    }
                    it.originalUrl?.takeIf { original ->
                        SharedMapLinks.allowedUrl(original) != null && android.content.Intent(android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(original)).resolveActivity(context.packageManager) != null
                    }?.let { original ->
                        TextButton(onClick = { if (currentOwner()) runCatching { MediaShare.openUrl(context, original) } },
                            modifier = Modifier.heightIn(min = 48.dp)) { Text("Kaynak uygulamada aç") }
                    }
                } }
                draft?.let { candidate ->
                    if (candidate.dynamicOrigin) item { Text("Başlangıç: mevcut konum; rota önizlenirken alınacak.") }
                    itemsIndexed(candidate.stops) { index, stop ->
                        OutlinedButton(onClick = { choosing = index }, enabled = !loading && !handedOff,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("shared-stop-$index")) {
                            Text("${index + 1}. ${stop.name} · ${if (stop.coordinate == null) "Yerini seç" else "Seçildi"}")
                        }
                    }
                    item {
                        Text("Adı olan ama konumu eksik her durağı Ara ile ayrı seç. Sıra ve yinelenen duraklar korunur.")
                        Button(onClick = {
                            if (!currentOwner() || handedOff || !candidate.complete) return@Button
                            handedOff = true
                            val stops = candidate.stops.map { RouteStop(it.name, requireNotNull(it.coordinate)) }
                            if (candidate.single) onSingle(stops.single()) else onItinerary(stops, candidate.dynamicOrigin)
                        }, enabled = candidate.complete && !loading && !handedOff,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("shared-map-continue")) {
                            Text(if (candidate.single) "Hedefi ve aracı kontrol et" else "Planlayıcıda aç")
                        }
                    }
                }
            }
        }
    }
    choosing?.let { index ->
        val stop = draft?.stops?.getOrNull(index)
        if (stop != null) OsmSearchDialog(onDismiss = { choosing = null }, initialQuery = stop.name,
            onSelect = { selected ->
                if (currentOwner() && !handedOff) {
                    val next = draft?.choose(index, sharedSelectedStop(selected))
                    if (next != null) savedInput = if (next.single) SharedMapInput.Single(next.stops.single())
                        else SharedMapInput.Itinerary(if (next.dynamicOrigin) null else next.stops.first(),
                            if (next.dynamicOrigin) next.stops.dropLast(1) else next.stops.drop(1).dropLast(1), next.stops.last())
                }
                choosing = null
            })
    }
}
