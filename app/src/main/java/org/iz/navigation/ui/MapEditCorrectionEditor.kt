package org.iz.navigation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.iz.navigation.data.MapEditDraft
import org.iz.navigation.data.OsmRef
import org.iz.navigation.data.Place
import org.iz.navigation.data.validateMapEdit
import org.iz.navigation.data.validateMapEditForPublish
import org.iz.navigation.data.MapEditFieldAction
import org.iz.navigation.data.selectPreset
import org.iz.navigation.data.hasEditorIntent
import org.iz.navigation.integration.MediaShare
import org.iz.navigation.integration.osm.OsmAuthManager
import org.iz.navigation.integration.osm.OsmCorrectionElement
import org.iz.navigation.integration.osm.OsmCorrectionReview
import org.iz.navigation.integration.osm.OsmUnsupportedCorrection
import org.iz.navigation.integration.osm.apiName
import org.iz.navigation.integration.osm.correctionRef
import org.iz.navigation.integration.osm.editorGeometry
import org.iz.navigation.integration.osm.upgradeEditorDraft
import org.iz.navigation.integration.osm.phoneText
import org.iz.navigation.integration.osm.rebaseEditorDraft
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun MapEditCorrectionEditor(
    initial: MapEditDraft, target: OsmCorrectionElement?, busy: Boolean, canWriteMap: Boolean,
    authBusy: Boolean, authError: String?, liveBaseline: () -> MapEditDraft, liveBusy: () -> Boolean,
    onClose: () -> Unit, onSave: (MapEditDraft) -> Unit,
    onReadTarget: suspend (OsmRef) -> OsmCorrectionElement,
    onReview: suspend (MapEditDraft) -> OsmCorrectionReview,
    isReviewCurrent: (OsmCorrectionReview) -> Boolean, onLogin: (MapEditDraft) -> Unit,
    onPublish: (OsmCorrectionReview) -> Unit, onNoteFallback: (Place) -> Unit,
    catalog: org.iz.navigation.osmcatalog.OsmPresetCatalog? = null,
    favoriteIds: Set<String> = emptySet(), onFavorite: (String, Boolean) -> Unit = { _, _ -> },
    catalogLoad: MapEditCatalogLoad? = null,
) {
    val load = resolveMapEditCatalog(catalog, catalogLoad)
    val availableCatalog = load.result?.getOrNull()
    val ref = requireNotNull(initial.correctionRef())
    var base by remember(initial.id) { mutableStateOf(initial) }
    var ready by rememberSaveable(initial.id) { mutableStateOf(initial.editorVersion == 2) }
    var name by rememberSaveable(initial.id) { mutableStateOf(initial.name) }
    var phone by rememberSaveable(initial.id) { mutableStateOf(initial.phone) }
    var nameAction by rememberSaveable(initial.id) { mutableStateOf(initial.nameAction) }
    var phoneAction by rememberSaveable(initial.id) { mutableStateOf(initial.phoneAction) }
    var snapshot by rememberSaveable(initial.id) { mutableStateOf(initial.presetSnapshot) }
    var originalSnapshot by rememberSaveable(initial.id) { mutableStateOf(initial.originalPresetSnapshot) }
    var survey by rememberSaveable(initial.id) { mutableStateOf(initial.surveyConfirmed) }
    var observationDate by rememberSaveable(initial.id) { mutableStateOf(java.time.Instant.ofEpochMilli(initial.observedAt)
        .atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()) }
    var current by remember(initial.id) { mutableStateOf(target) }
    var geometry by remember(initial.id) { mutableStateOf<org.iz.navigation.osmcatalog.OsmPresetGeometry?>(null) }
    var review by remember(initial.id) { mutableStateOf<OsmCorrectionReview?>(null) }
    var reviewed by remember(initial.id) { mutableStateOf(false) }
    var querying by remember(initial.id) { mutableStateOf(target == null) }
    var unsupported by remember(initial.id) { mutableStateOf(false) }
    var submitted by remember(initial.id) { mutableStateOf(false) }
    var error by remember(initial.id) { mutableStateOf<String?>(null) }
    var rebaseNotice by remember(initial.id) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val auth = remember(context) { OsmAuthManager.get(context) }
    val observedAt = runCatching {
        val date = java.time.LocalDate.parse(observationDate)
        require(!date.isAfter(java.time.LocalDate.now()))
        val originalDate = java.time.Instant.ofEpochMilli(base.observedAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        if (date == originalDate) base.observedAt else date.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    }.getOrNull()
    var draft = base.copy(originalPresetSnapshot = originalSnapshot, name = name.trim(), phone = phone.trim(), nameAction = nameAction,
        phoneAction = phoneAction, surveyConfirmed = survey, observedAt = observedAt ?: base.observedAt)
    snapshot?.let {
        if (draft.editorVersion == 2) draft = draft.selectPreset(org.iz.navigation.osmcatalog.OsmPresetRuleSnapshot.fromJson(it))
    }
    val saveValid = observedAt != null && runCatching { validateMapEdit(draft) }.isSuccess
    val loginValid = ready && availableCatalog != null && geometry != null && base.editorVersion == 2 && saveValid
    val publishValid = ready && availableCatalog != null && geometry != null && base.editorVersion == 2 && observedAt != null && runCatching { validateMapEditForPublish(draft) }.isSuccess
    val locked = busy || querying || authBusy || submitted
    fun clearReview() { review = null; reviewed = false }
    LaunchedEffect(busy) { if (!busy) submitted = false }
    val exit = OsmDraftExitOwner("osm-map-correction/${initial.id}",
        accountKey = { auth.communitySessionSource.sessions.value?.let { "${it.userId}:${it.generation}" } },
        working = { listOf(name, phone, nameAction, phoneAction, snapshot, originalSnapshot, survey, observationDate) },
        baseline = { liveBaseline().let { saved ->
            val value = if (saved.editorVersion == 2) saved else base
            listOf(value.name, value.phone, value.nameAction, value.phoneAction, value.presetSnapshot, value.originalPresetSnapshot,
                value.surveyConfirmed, java.time.Instant.ofEpochMilli(value.observedAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString())
        } }, busy = { liveBusy() || querying || submitted },
        discard = {
            val saved = liveBaseline().let { if (it.editorVersion == 2) it else base }
            name = saved.name; phone = saved.phone; nameAction = saved.nameAction; phoneAction = saved.phoneAction
            snapshot = saved.presetSnapshot; originalSnapshot = saved.originalPresetSnapshot; survey = saved.surveyConfirmed
            observationDate = java.time.Instant.ofEpochMilli(saved.observedAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
            clearReview()
        })
    LaunchedEffect(initial.id) {
        try {
            if (current == null) current = onReadTarget(ref)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: OsmUnsupportedCorrection) { unsupported = true }
        catch (_: Exception) { error = "Güncel OSM bilgileri alınamadı. Taslağını saklayabilir veya yeniden deneyebilirsin." }
        finally { querying = false }
    }
    LaunchedEffect(current, availableCatalog) {
        val actual = current ?: return@LaunchedEffect
        if (availableCatalog != null) {
            try {
                geometry = actual.editorGeometry(availableCatalog)
                if (initial.editorVersion != 2 && base.editorVersion != 2) {
                    val upgraded = actual.upgradeEditorDraft(initial, availableCatalog)
                    base = upgraded
                    if (!ready) {
                        name = upgraded.name; phone = upgraded.phone
                        nameAction = upgraded.nameAction; phoneAction = upgraded.phoneAction
                        snapshot = upgraded.presetSnapshot
                        originalSnapshot = upgraded.originalPresetSnapshot
                    }
                    ready = true
                }
            } catch (_: OsmUnsupportedCorrection) { geometry = null; unsupported = true }
            catch (_: Exception) { error = "Eski taslak hazırlanamadı. Taslak bilgileri korunuyor."; ready = false }
        }
    }
    val activeReview = review?.takeIf(isReviewCurrent)
    LaunchedEffect(review, activeReview) {
        if (review != null && activeReview == null) {
            clearReview(); error = "OSM oturumu değişti. Düzeltmeyi yeniden incele."
        }
    }
    val fallbackPlace = Place(name = current?.tags?.get("name").orEmpty().ifBlank { "OSM ${ref.type.apiName()}/${ref.id}" },
        latitude = initial.latitude, longitude = initial.longitude, osmType = ref.type, osmId = ref.id)
    Dialog(onDismissRequest = { if (!locked) { if (review != null) clearReview() else exit(onClose) } },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = !locked, dismissOnClickOutside = !locked)) {
        Surface(Modifier.padding(16.dp).widthIn(max = 560.dp).fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (activeReview == null) "OSM mekânını düzelt" else "OSM'de yayımlamadan önce", style = MaterialTheme.typography.headlineSmall)
                Text("OSM ${ref.type.apiName()}/${ref.id}")
                (error ?: authError)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                rebaseNotice?.let { Text(it) }
                OutlinedButton(enabled = !locked && availableCatalog != null, modifier = Modifier.fillMaxWidth()
                    .testTag("osm-map-correction-rebase"), onClick = {
                    val loadedCatalog = availableCatalog
                    if (!locked && loadedCatalog != null) {
                        val working = draft
                        val owner = auth.currentCredentials()
                        querying = true; clearReview(); survey = false; error = null; rebaseNotice = null
                        scope.launch {
                            try {
                                val fresh = onReadTarget(ref)
                                check(auth.currentCredentials() === owner) { "OSM oturumu değişti." }
                                val rebased = fresh.rebaseEditorDraft(working, loadedCatalog)
                                base = rebased; current = fresh; geometry = fresh.editorGeometry(loadedCatalog)
                                name = rebased.name; phone = rebased.phone
                                nameAction = rebased.nameAction; phoneAction = rebased.phoneAction
                                snapshot = rebased.presetSnapshot; originalSnapshot = rebased.originalPresetSnapshot
                                survey = false; ready = true; unsupported = false
                                rebaseNotice = "Güncel OSM bilgileri alındı. Yerinde doğrulamayı ve değişiklikleri yeniden onayla."
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: OsmUnsupportedCorrection) { geometry = null; unsupported = true }
                            catch (_: Exception) { error = "Güncel OSM kaydı alınamadı. Yerel düzenlemelerin korunuyor." }
                            finally { querying = false }
                        }
                    }
                }) { Text("Güncel OSM kaydını yeniden yükle") }
                if (activeReview != null) {
                    Text("OSM hesabı · ${activeReview.credentials.user.displayName}")
                    Text("OSM sürümü ${activeReview.original.version}")
                    val beforeName = activeReview.original.tags["name"].orEmpty()
                    val afterName = activeReview.corrected.tags["name"].orEmpty()
                    val beforePhone = activeReview.original.phoneText()
                    val afterPhone = activeReview.corrected.phoneText()
                    val beforeType = mapEditPresetLabel(activeReview.draft.originalPresetSnapshot) ?: "Tanımlanamayan tür"
                    val afterType = mapEditPresetLabel(activeReview.draft.presetSnapshot
                        ?: activeReview.draft.originalPresetSnapshot) ?: "Tanımlanamayan tür"
                    Text("Ad: ${beforeName.ifBlank { "Adsız" }} → ${afterName.ifBlank { if (beforeName.isNotBlank()) "Silinecek" else "Adsız" }}")
                    Text("Tür: $beforeType → $afterType")
                    Text("Telefon: ${beforePhone.ifBlank { "Yok" }} → ${afterPhone.ifBlank { if (beforePhone.isNotBlank()) "Silinecek" else "Yok" }}")
                    Text("OSM etiket değişiklikleri", style = MaterialTheme.typography.titleSmall)
                    activeReview.fields.forEach { field ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(correctionFieldLabel(field.key), style = MaterialTheme.typography.titleSmall)
                            if (correctionFieldLabel(field.key) != field.key) Text(field.key, style = MaterialTheme.typography.bodySmall)
                            Text("Önce: ${field.before ?: "Boş"}")
                            Text("Sonra: ${field.after ?: "Silinecek"}")
                        }
                    }
                    Text("Onayladığın bilgiler OSM hesabınla herkese açık haritada güncellenecek.")
                    CorrectionConfirmationRow("Güncellenecek bilgileri ve OSM hesabımı kontrol ettim.",
                        "osm-map-correction-reviewed", reviewed, !locked && canWriteMap) { reviewed = it }
                    Button(onClick = {
                        if (!submitted && review === activeReview && isReviewCurrent(activeReview)) {
                            submitted = true; clearReview(); onPublish(activeReview)
                        } else { clearReview(); error = "OSM oturumu değişti. Düzeltmeyi yeniden incele." }
                    }, enabled = reviewed && canWriteMap && !locked, modifier = Modifier.fillMaxWidth().testTag("osm-map-correction-publish")) { Text("Düzeltmeyi yayımla") }
                    TextButton(onClick = ::clearReview, enabled = !locked) { Text("Düzenlemeye dön") }
                } else if (unsupported) {
                    Text("Bu OSM nesnesinin geometrisi İz'de düzenlenemiyor. OSM düzenleyicisini kullan.")
                } else {
                    if (base.legacyAddressIntent) {
                        Text("Eski taslaktaki adres değişiklikleri korunuyor ve önce/sonra listesinde gösterilecek.")
                        if (base.street.isNotBlank()) Text("addr:street: ${base.street}")
                        if (base.houseNumber.isNotBlank()) Text("addr:housenumber: ${base.houseNumber}")
                    }
                    Text("Dokunmadığın bilgiler korunur. Mevcut bir bilgiyi boşaltmak yayımlandığında siler.")
                    CorrectionTextField("name", name, current?.tags?.get("name"), !locked && ready && geometry != null) {
                        name = it
                        nameAction = fieldAction(it, current?.tags?.get("name").orEmpty())
                        survey = false
                    }
                    if (nameAction == MapEditFieldAction.DELETE) Text("Yayımlanırsa bu bilgi OSM'den silinecek.")
                    Text("Tür", style = MaterialTheme.typography.labelLarge)
                    geometry?.let { kind -> MapEditPresetSelector(availableCatalog, kind, snapshot, favoriteIds, !locked && ready,
                        { snapshot = it.toJson(); survey = false }, onFavorite, required = false) }
                    MapEditCatalogStatus(load, !locked)
                    CorrectionTextField("phone", phone, current?.phoneText(), !locked && ready && geometry != null) {
                        phone = it; phoneAction = fieldAction(it, current?.phoneText().orEmpty()); survey = false
                    }
                    current?.tags?.filterKeys { it == "phone" || it == "contact:phone" }?.forEach { (key, value) -> Text("$key: $value") }
                    if (phoneAction == MapEditFieldAction.DELETE) Text("Yayımlanırsa bu bilgi OSM'den silinecek.")
                    OutlinedTextField(observationDate, { observationDate = it; survey = false }, label = { Text("Gözlem tarihi") },
                        enabled = !locked && ready && geometry != null, isError = observedAt == null, singleLine = true, modifier = Modifier.fillMaxWidth())
                    CorrectionConfirmationRow("Düzelttiğim bilgileri yerinde doğruladım.", "osm-map-correction-survey", survey, !locked && ready && geometry != null) { survey = it }
                    val hasInput = draft.hasEditorIntent()
                    if (canWriteMap) Button(enabled = publishValid && survey && hasInput && !locked, modifier = Modifier.fillMaxWidth(), onClick = {
                        querying = true; error = null; rebaseNotice = null
                        val request = draft
                        scope.launch {
                            try { val prepared = onReview(request); current = prepared.original; review = prepared }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: OsmUnsupportedCorrection) { unsupported = true }
                            catch (_: Exception) { error = "OSM bilgileri doğrulanamadı. Taslağını saklayıp yeniden deneyebilirsin." }
                            finally { querying = false }
                        }
                    }) { Text("OSM bilgilerini kontrol et ve incele") }
                    else Button(onClick = { onLogin(draft) }, enabled = loginValid && !locked, modifier = Modifier.fillMaxWidth()) { Text("OSM düzenleme iznini etkinleştir") }
                }
                if (activeReview == null) {
                    OutlinedButton(enabled = saveValid && !locked, onClick = { onSave(draft) }, modifier = Modifier.fillMaxWidth()) { Text("Taslağı sakla") }
                    MapEditFallbackActions(fallbackPlace, !locked) { place -> exit { onNoteFallback(place) } }
                }
                if (locked) LinearProgressIndicator(Modifier.fillMaxWidth())
                TextButton(onClick = { exit(onClose) }, enabled = !locked) { Text("Kapat") }
            }
        }
    }
}

internal fun fieldAction(value: String, original: String): MapEditFieldAction = when {
    value.trim() == original.trim() -> MapEditFieldAction.UNTOUCHED
    value.isBlank() && original.isNotBlank() -> MapEditFieldAction.DELETE
    value.isBlank() -> MapEditFieldAction.UNTOUCHED
    else -> MapEditFieldAction.SET
}

@Composable
internal fun CorrectionConfirmationRow(
    label: String,
    tag: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag).toggleable(
            value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange,
        ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Text(label)
    }
}

@Composable
private fun CorrectionTextField(key: String, value: String, current: String?, enabled: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = onChange, enabled = enabled, singleLine = true,
        label = { Text(correctionFieldLabel(key)) },
        supportingText = { Text("OSM’deki değer: ${current ?: "Boş"}") },
        isError = value.codePointCount(0, value.length) > if (key == "phone" && value == current) 1024 else 255,
        modifier = Modifier.fillMaxWidth().testTag("osm-map-correction-$key"))
}

private fun correctionFieldLabel(key: String): String = when (key) {
    "name" -> "Mekân adı"
    "phone" -> "Telefon"
    "contact:phone" -> "İletişim telefonu (contact:phone)"
    else -> key
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
