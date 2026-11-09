package org.iz.navigation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.iz.navigation.data.MapEditDraft
import org.iz.navigation.data.OsmRef
import org.iz.navigation.data.Place
import org.iz.navigation.data.validateMapEdit
import org.iz.navigation.integration.MediaShare
import org.iz.navigation.integration.osm.OsmAuthManager
import org.iz.navigation.integration.osm.OsmCorrectionElement
import org.iz.navigation.integration.osm.OsmCorrectionReview
import org.iz.navigation.integration.osm.OsmUnsupportedCorrection
import org.iz.navigation.integration.osm.apiName
import org.iz.navigation.integration.osm.correctionRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun MapEditCorrectionEditor(
    initial: MapEditDraft,
    target: OsmCorrectionElement?,
    busy: Boolean,
    canWriteMap: Boolean,
    authBusy: Boolean,
    authError: String?,
    liveBaseline: () -> MapEditDraft,
    liveBusy: () -> Boolean,
    onClose: () -> Unit,
    onSave: (MapEditDraft) -> Unit,
    onReadTarget: suspend (OsmRef) -> OsmCorrectionElement,
    onReview: suspend (MapEditDraft) -> OsmCorrectionReview,
    isReviewCurrent: (OsmCorrectionReview) -> Boolean,
    onLogin: (MapEditDraft) -> Unit,
    onPublish: (OsmCorrectionReview) -> Unit,
    onNoteFallback: (Place) -> Unit,
) {
    val ref = requireNotNull(initial.correctionRef())
    var name by rememberSaveable(initial.id) { mutableStateOf(initial.name) }
    var street by rememberSaveable(initial.id) { mutableStateOf(initial.street) }
    var number by rememberSaveable(initial.id) { mutableStateOf(initial.houseNumber) }
    var survey by rememberSaveable(initial.id) { mutableStateOf(initial.surveyConfirmed) }
    var current by remember(initial.id) { mutableStateOf(target) }
    var review by remember(initial.id) { mutableStateOf<OsmCorrectionReview?>(null) }
    var reviewed by remember(initial.id) { mutableStateOf(false) }
    var querying by remember(initial.id) { mutableStateOf(target == null) }
    var unsupported by remember(initial.id) { mutableStateOf(false) }
    var error by remember(initial.id) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val auth = remember(context) { OsmAuthManager.get(context) }
    val draft = initial.copy(name = name.trim(), street = street.trim(), houseNumber = number.trim(), surveyConfirmed = survey)
    val valid = runCatching { validateMapEdit(draft) }.isSuccess
    val locked = busy || querying || authBusy
    fun clearReview() {
        review = null
        reviewed = false
    }
    val exit = OsmDraftExitOwner(
        "osm-map-correction/${initial.id}",
        accountKey = { auth.communitySessionSource.sessions.value?.let { "${it.userId}:${it.generation}" } },
        working = { listOf(name.trim(), street.trim(), number.trim(), survey) },
        baseline = { liveBaseline().let { listOf(it.name, it.street, it.houseNumber, it.surveyConfirmed) } },
        busy = { liveBusy() || querying },
        discard = {
            val saved = liveBaseline()
            name = saved.name
            street = saved.street
            number = saved.houseNumber
            survey = saved.surveyConfirmed
            clearReview()
        },
    )
    LaunchedEffect(initial.id) {
        if (current != null) return@LaunchedEffect
        try {
            current = onReadTarget(ref).also { if (it.preset() != initial.preset) throw OsmUnsupportedCorrection() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: OsmUnsupportedCorrection) {
            unsupported = true
        } catch (_: Exception) {
            error = "Güncel OSM bilgileri alınamadı. Taslağını saklayabilir veya incelemeyi yeniden deneyebilirsin."
        } finally {
            querying = false
        }
    }
    val activeReview = review?.takeIf(isReviewCurrent)
    LaunchedEffect(review, activeReview) {
        if (review != null && activeReview == null) {
            clearReview()
            error = "OSM oturumu değişti. Düzeltmeyi yeniden incele."
        }
    }
    val currentName = current?.tags?.get("name").orEmpty()
    val fallbackPlace = Place(name = currentName.ifBlank { initial.preset.label },
        latitude = initial.latitude, longitude = initial.longitude, osmType = ref.type, osmId = ref.id)
    Dialog(
        onDismissRequest = { if (!locked) { if (review != null) clearReview() else exit(onClose) } },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = !locked, dismissOnClickOutside = !locked),
    ) {
        Surface(
            Modifier.padding(16.dp).widthIn(max = 560.dp).fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp,
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (activeReview == null) "OSM mekânını düzelt" else "OSM düzeltmesini incele",
                    style = MaterialTheme.typography.headlineSmall)
                Text("${initial.preset.label} · OSM ${ref.type.apiName()}/${ref.id}")
                (error ?: authError)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (activeReview != null) {
                    Text("OSM hesabı · ${activeReview.credentials.user.displayName}")
                    Text("OSM sürümü ${activeReview.original.version}")
                    activeReview.fields.forEach { field ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(correctionFieldLabel(field.key), style = MaterialTheme.typography.titleSmall)
                            Text("Önce: ${field.before ?: "Boş"}")
                            Text("Sonra: ${field.after ?: "Boş"}")
                        }
                    }
                    Text("Onayladığın bilgiler OSM hesabınla herkese açık haritada güncellenecek.")
                    Row {
                        Checkbox(reviewed, { reviewed = it }, enabled = !locked && canWriteMap,
                            modifier = Modifier.testTag("osm-map-correction-reviewed"))
                        Text("Güncellenecek bilgileri ve OSM hesabımı kontrol ettim.", Modifier.padding(top = 12.dp))
                    }
                    Button(onClick = {
                        clearReview()
                        if (isReviewCurrent(activeReview)) onPublish(activeReview)
                        else error = "OSM oturumu değişti. Düzeltmeyi yeniden incele."
                    }, enabled = reviewed && canWriteMap && !locked,
                        modifier = Modifier.fillMaxWidth().testTag("osm-map-correction-publish")) { Text("Düzeltmeyi yayımla") }
                    TextButton(onClick = ::clearReview, enabled = !locked) { Text("Düzenlemeye dön") }
                } else if (unsupported) {
                    Text("Bu OSM kaydı için İz'de doğrudan düzeltme kullanılamıyor.")
                } else {
                    Text("Boş bıraktığın alanlarda OSM’deki değer korunur.")
                    CorrectionTextField("name", name, current?.tags?.get("name"), !locked) { name = it; survey = false }
                    CorrectionTextField("addr:street", street, current?.tags?.get("addr:street"), !locked) { street = it; survey = false }
                    CorrectionTextField("addr:housenumber", number, current?.tags?.get("addr:housenumber"), !locked) { number = it; survey = false }
                    Row {
                        Checkbox(survey, { survey = it }, enabled = !locked, modifier = Modifier.testTag("osm-map-correction-survey"))
                        Text("Düzelttiğim bilgileri yerinde doğruladım.", Modifier.padding(top = 12.dp))
                    }
                    val hasInput = listOf(name, street, number).any { it.isNotBlank() }
                    if (canWriteMap) Button(enabled = valid && survey && hasInput && !locked, modifier = Modifier.fillMaxWidth(), onClick = {
                        querying = true
                        error = null
                        scope.launch {
                            try {
                                val prepared = onReview(draft)
                                current = prepared.original
                                review = prepared
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: OsmUnsupportedCorrection) {
                                unsupported = true
                            } catch (invalid: IllegalArgumentException) {
                                error = invalid.message?.takeIf { it == "En az bir bilgiyi değiştir." }
                                    ?: "OSM bilgileri doğrulanamadı. Taslağını saklayıp yeniden deneyebilirsin."
                            } catch (_: Exception) {
                                error = "OSM bilgileri doğrulanamadı. Taslağını saklayıp yeniden deneyebilirsin."
                            } finally {
                                querying = false
                            }
                        }
                    }) { Text("OSM bilgilerini kontrol et ve incele") }
                    else Button(onClick = { onLogin(draft) }, enabled = valid && !locked, modifier = Modifier.fillMaxWidth()) {
                        Text("OSM düzenleme iznini etkinleştir")
                    }
                }
                if (activeReview == null) {
                    OutlinedButton(enabled = valid && !locked, onClick = { onSave(draft) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Taslağı sakla")
                    }
                    if (!unsupported) Text("Konum, kategori ve diğer OSM bilgileri için not veya OSM düzenleyicisini kullan.")
                    MapEditFallbackActions(fallbackPlace, enabled = !locked, onNoteFallback = { place -> exit { onNoteFallback(place) } })
                }
                if (locked) LinearProgressIndicator(Modifier.fillMaxWidth())
                TextButton(onClick = { exit(onClose) }, enabled = !locked) { Text("Kapat") }
            }
        }
    }
}

@Composable
private fun CorrectionTextField(key: String, value: String, current: String?, enabled: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = onChange, enabled = enabled, singleLine = true,
        label = { Text(correctionFieldLabel(key)) },
        supportingText = { Text("OSM’deki değer: ${current ?: "Boş"}") },
        isError = value.codePointCount(0, value.length) > 255,
        modifier = Modifier.fillMaxWidth().testTag("osm-map-correction-$key"))
}

private fun correctionFieldLabel(key: String): String = when (key) {
    "name" -> "Mekân adı"
    "addr:street" -> "Cadde / sokak"
    "addr:housenumber" -> "Kapı numarası"
    else -> error("Unexpected correction field")
}

@Composable
internal fun MapEditFallbackActions(place: Place, enabled: Boolean, onNoteFallback: (Place) -> Unit) {
    val context = LocalContext.current
    val ref = place.osmType?.let { type -> place.osmId?.takeIf { it > 0 }?.let { OsmRef(type, it) } }
    Column {
        TextButton(onClick = { onNoteFallback(place) }, enabled = enabled && place.latitude != null && place.longitude != null) {
            Text("OSM notu oluştur")
        }
        if (ref != null) TextButton(onClick = {
            MediaShare.openUrl(context, "https://www.openstreetmap.org/edit?editor=id&${ref.type.apiName()}=${ref.id}")
        }, enabled = enabled) { Text("OSM düzenleyicisinde aç") }
    }
}
