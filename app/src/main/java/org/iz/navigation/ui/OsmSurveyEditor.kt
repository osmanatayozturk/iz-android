@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package org.iz.navigation.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.iz.navigation.data.*
import org.iz.navigation.integration.*
import org.iz.navigation.integration.osm.*
import org.iz.navigation.ui.history.LocalUiEntryKey
import org.iz.navigation.ui.history.LocalUiHistory
import java.time.LocalDate
import java.time.ZoneId

/** The page owns persistence/network. This editor owns only saveable input and reviewed UI state. */
@Composable
internal fun OsmSurveyEditor(
    initial: MapEditDraft, target: OsmCorrectionElement?, busy: Boolean, canWriteMap: Boolean,
    authBusy: Boolean, authError: String?, liveBaseline: () -> MapEditDraft, liveBusy: () -> Boolean,
    onClose: () -> Unit, onSave: (MapEditDraft) -> Unit,
    onReadTarget: suspend (OsmRef) -> OsmCorrectionElement,
    onReview: suspend (MapEditDraft) -> OsmCorrectionReview,
    isReviewCurrent: (OsmCorrectionReview) -> Boolean, onLogin: (MapEditDraft) -> Unit,
    onPublish: (OsmCorrectionReview) -> Unit, onNoteFallback: (Place) -> Unit,
    today: LocalDate = LocalDate.now(),
) {
    val first = remember(initial.id) { requireNotNull(initial.surveyObservation()) }
    var payload by rememberSaveable(initial.id) { mutableStateOf(first.encode()) }
    var date by rememberSaveable(initial.id) { mutableStateOf(first.observedOn) }
    var confirmed by rememberSaveable(initial.id) { mutableStateOf(initial.surveyConfirmed) }
    var simple by rememberSaveable(initial.id) {
        val hours = decodeSurveyObservation(payload).hours
        mutableStateOf(restoredSurveyWeek(hours) != null)
    }
    var formWeek by remember(initial.id) { mutableStateOf(restoredSurveyWeek(decodeSurveyObservation(payload).hours)) }
    var current by remember(initial.id) { mutableStateOf(target) }
    var querying by remember(initial.id) { mutableStateOf(target == null) }
    var error by remember(initial.id) { mutableStateOf<String?>(null) }
    var review by remember(initial.id) { mutableStateOf<OsmCorrectionReview?>(null) }
    var reviewed by remember(initial.id) { mutableStateOf(false) }
    var replacement by remember(initial.id) { mutableStateOf<OsmCorrectionElement?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val auth = remember(context) { OsmAuthManager.get(context) }
    val entryKey = LocalUiEntryKey.current
    val history = LocalUiHistory.current
    fun entryIsCurrent() = history == null || history.current.key == entryKey
    val observation = decodeSurveyObservation(payload).copy(observedOn = date)
    val venue = current?.let {
        SurveyVenue(it.ref, it.version, it.tags["name"].orEmpty(), initial.latitude, initial.longitude, it.tags)
    }
    val parsedDate = surveyDate(date)
    val structuralDateValid = parsedDate != null
    val validDate = parsedDate?.let { !it.isAfter(today) } == true
    val draft = runCatching {
        initial.copy(name = "", street = "", houseNumber = "", surveyPayload = observation.encode(),
            observedAt = LocalDate.parse(date).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            surveyConfirmed = confirmed)
    }.getOrNull()
    val changes = current?.let { runCatching { observation.publicChanges(it.tags, today) } }
    val publishable = validDate && replacement == null && observation.baseVersion == current?.version &&
        changes?.getOrNull()?.isNotEmpty() == true
    val locked = busy || authBusy || querying

    fun resetReview() { review = null; reviewed = false }
    fun update(value: SurveyObservation): Boolean {
        if (value.hours.value.length > 8192 || value.wheelchair.value.length > 8192) {
            error = "Taslak alanı en fazla 8192 karakter olabilir. Son kabul edilen metin korunuyor."
            return false
        }
        // The valid date is stored separately so incomplete date input cannot discard the raw field text.
        val encoded = runCatching { value.copy(observedOn = first.observedOn).encode() }.getOrNull()
        if (encoded == null) {
            error = "Taslak sınırı aşıldı. Son kabul edilen metin korunuyor."
            return false
        }
        payload = encoded
        confirmed = false
        error = null
        resetReview()
        return true
    }
    fun field(field: SurveyField, action: SurveyAction) {
        fun change(value: SurveyFieldObservation) = value.copy(action = action,
            value = if (action == SurveyAction.VERIFY || action == SurveyAction.EDIT && value.action != SurveyAction.EDIT)
                value.before.orEmpty() else value.value,
            fullyObserved = action == SurveyAction.VERIFY,
            formDraft = if (action == SurveyAction.EDIT && value.action == SurveyAction.EDIT) value.formDraft else null)
        val next = when (field) {
            SurveyField.OPENING_HOURS -> observation.copy(hours = change(observation.hours))
            SurveyField.WHEELCHAIR -> observation.copy(wheelchair = change(observation.wheelchair))
        }
        if (update(next) && field == SurveyField.OPENING_HOURS &&
            !(action == SurveyAction.EDIT && observation.hours.action == SurveyAction.EDIT)) {
            simple = false
            formWeek = null
        }
    }
    val exit = OsmDraftExitOwner("osm-survey/${initial.id}",
        accountKey = { auth.communitySessionSource.sessions.value?.let { "${it.userId}:${it.generation}" } },
        working = { listOf(draft?.surveyPayload ?: payload, date, confirmed) },
        baseline = { liveBaseline().let { listOf(it.surveyPayload, it.surveyObservation()?.observedOn, it.surveyConfirmed) } },
        busy = { liveBusy() || querying },
        discard = {
            val saved = liveBaseline()
            payload = requireNotNull(saved.surveyPayload)
            date = requireNotNull(saved.surveyObservation()).observedOn
            confirmed = saved.surveyConfirmed
            simple = false
            resetReview()
        })
    LaunchedEffect(initial.id) {
        if (current != null) return@LaunchedEffect
        try {
            current = onReadTarget(requireNotNull(initial.correctionRef())).also {
                require(it.ref == initial.correctionRef() && it.preset() == initial.preset)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "Güncel OSM bilgileri alınamadı. Taslağını saklayıp yeniden deneyebilirsin." }
        finally { querying = false }
    }
    val activeReview = review?.takeIf(isReviewCurrent)
    LaunchedEffect(review, activeReview) {
        if (review != null && activeReview == null) { resetReview(); error = "OSM hesabı değişti. Gözlemi yeniden incele." }
    }

    Dialog(onDismissRequest = {
        if (!locked) {
            if (review != null) resetReview() else exit(onClose)
        }
    }, properties = DialogProperties(usePlatformDefaultWidth = false,
        dismissOnBackPress = !locked, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxWidth().widthIn(max = 600.dp).fillMaxHeight(.96f).padding(12.dp),
            shape = MaterialTheme.shapes.extraLarge) {
            LazyColumn(Modifier.fillMaxSize().testTag("osm-survey-editor-list"),
                contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Text(if (activeReview == null) "Yerinde tamamla" else "Gözlemi yayımlamadan önce",
                        Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
                    Text(current?.tags?.get("name:tr") ?: current?.tags?.get("name") ?: initial.preset.label)
                }
                (error ?: authError)?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
                if (querying) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                if (activeReview != null) {
                    item {
                        Text("OSM hesabı · ${activeReview.credentials.user.displayName}")
                        Text("Gözlem tarihi · ${observation.observedOn}")
                        Text("OSM sürümü ${activeReview.original.version}")
                    }
                    itemsIndexed(activeReview.fields) { _, change ->
                        Column {
                            Text(surveyFieldLabel(change.key), style = MaterialTheme.typography.titleSmall)
                            Text("Önce: ${change.before ?: "Boş"}")
                            Text("Sonra: ${change.after ?: "Boş"}")
                        }
                    }
                    item {
                        Text("Onayladığın bilgiler OSM hesabınla herkese açık haritada güncellenecek.")
                        SurveyConfirmation("Güncellenecek bilgileri ve OSM hesabımı kontrol ettim.", "osm-survey-reviewed",
                            reviewed, !locked && canWriteMap) { reviewed = it }
                    }
                    item {
                        Button(onClick = {
                            resetReview()
                            if (entryIsCurrent() && isReviewCurrent(activeReview)) onPublish(activeReview)
                            else error = "OSM hesabı değişti. Gözlemi yeniden incele."
                        }, enabled = reviewed && canWriteMap && !locked,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("osm-survey-publish")) {
                            Text("Gözlemi yayımla")
                        }
                        TextButton(onClick = ::resetReview, enabled = !locked, modifier = Modifier.heightIn(min = 48.dp)) { Text("Düzenlemeye dön") }
                    }
                } else {
                    item {
                        TextButton(onClick = {
                            if (!entryIsCurrent()) return@TextButton
                            val owner = auth.currentCredentials()
                            querying = true
                            resetReview()
                            scope.launch {
                                try {
                                    val found = onReadTarget(requireNotNull(initial.correctionRef()))
                                    require(found.ref == initial.correctionRef() && found.preset() == initial.preset)
                                    if (entryIsCurrent() && auth.currentCredentials() === owner) {
                                        if (found.version == observation.baseVersion) {
                                            current = found
                                            error = null
                                        } else replacement = found
                                    }
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { error = "Güncel OSM bilgileri alınamadı. Taslağın korunuyor." }
                                finally { querying = false }
                            }
                        }, enabled = !locked, modifier = Modifier.heightIn(min = 48.dp).testTag("osm-survey-read-current")) {
                            Text("Güncel OSM bilgilerini yeniden oku")
                        }
                    }
                    replacement?.let { fresh ->
                        item {
                            Text("OSM sürümü değişti: ${observation.baseVersion} → ${fresh.version}. Gözlemini güncel bilgiyle karşılaştır.")
                            for ((field, saved) in listOf(SurveyField.OPENING_HOURS to observation.hours, SurveyField.WHEELCHAIR to observation.wheelchair)) {
                                Text("${surveyFieldLabel(field.key)} — Önce: ${saved.before ?: "Eksik"} · Güncel: ${fresh.tags[field.key] ?: "Eksik"}")
                                Text("Kontrol tarihi — Önce: ${saved.beforeDate ?: "Bilinmiyor"} · Güncel: ${fresh.tags[field.dateKey] ?: "Bilinmiyor"}")
                            }
                            Button(onClick = {
                                fun rebased(field: SurveyField, saved: SurveyFieldObservation) = saved.copy(
                                    action = if (saved.action == SurveyAction.EDIT) SurveyAction.EDIT else SurveyAction.UNTOUCHED,
                                    before = fresh.tags[field.key], beforeDate = fresh.tags[field.dateKey], fullyObserved = false)
                                update(observation.copy(baseVersion = fresh.version,
                                    hours = rebased(SurveyField.OPENING_HOURS, observation.hours),
                                    wheelchair = rebased(SurveyField.WHEELCHAIR, observation.wheelchair)))
                                current = fresh
                                replacement = null
                                simple = false
                                formWeek = null
                            }, enabled = !locked, modifier = Modifier.heightIn(min = 48.dp).testTag("osm-survey-accept-current")) {
                                Text("Güncel sürümle gözlemi yeniden kontrol et")
                            }
                            Text("Yerinde gözlem ve alan doğrulama onaylarını yeniden ver.")
                            TextButton(onClick = { replacement = null }, enabled = !locked, modifier = Modifier.heightIn(min = 48.dp)) { Text("Eski taslağı koru") }
                        }
                    }
                    item {
                        OutlinedTextField(date, { date = it; confirmed = false; resetReview() }, enabled = !locked,
                            label = { Text("Gözlem tarihi (YYYY-MM-DD)") }, isError = !validDate,
                            modifier = Modifier.fillMaxWidth().testTag("osm-survey-observed-on"), singleLine = true)
                        if (!validDate) Text("Gelecekte olmayan geçerli bir gözlem tarihi gir.", color = MaterialTheme.colorScheme.error)
                    }
                    item {
                        SurveyFieldHeader(SurveyField.OPENING_HOURS, observation.hours, locked,
                            venue?.tasks(today)?.firstOrNull { it.field == SurveyField.OPENING_HOURS }) {
                            field(SurveyField.OPENING_HOURS, it)
                        }
                    }
                    if (observation.hours.action == SurveyAction.EDIT) {
                        val convertible = restoredSurveyWeek(observation.hours)
                            ?: SurveyOpeningHours.toWeek(observation.hours.value.ifBlank { observation.hours.before.orEmpty() })
                        item {
                            Row {
                                TextButton(onClick = { simple = false }, enabled = !locked,
                                    modifier = Modifier.heightIn(min = 48.dp)) { Text("Gelişmiş OSM ifadesi") }
                                if (convertible != null) TextButton(onClick = {
                                    update(observation.copy(hours = observation.hours.copy(
                                        value = convertible.expression(), formDraft = convertible.encodeDraft())))
                                    formWeek = convertible
                                    simple = true
                                }, enabled = !locked, modifier = Modifier.heightIn(min = 48.dp).testTag("osm-survey-hours-simple")) {
                                    Text("Haftalık form")
                                }
                                if (observation.hours.before.isNullOrBlank() && observation.hours.value.isBlank()) TextButton(onClick = {
                                    val week = SurveyWeek(List(7) { SurveyDayHours(SurveyDayState.UNKNOWN) })
                                    update(observation.copy(hours = observation.hours.copy(value = week.expression(), formDraft = week.encodeDraft())))
                                    formWeek = week
                                    simple = true
                                }, enabled = !locked, modifier = Modifier.heightIn(min = 48.dp)) { Text("Haftalık form") }
                            }
                        }
                        if (simple && formWeek != null) {
                            itemsIndexed(requireNotNull(formWeek).days) { index, day ->
                                SurveyHoursDay(index, day, !locked) { changed ->
                                    val next = requireNotNull(formWeek).editDay(index, changed)
                                    val privateDraft = runCatching { next.encodeDraft() }.getOrNull()
                                    if (privateDraft == null) error = "Saat formu sınırı aşıldı. Son kabul edilen aralıklar korunuyor."
                                    else if (update(observation.copy(hours = observation.hours.copy(
                                        value = next.expression(), formDraft = privateDraft, fullyObserved = false)))) formWeek = next
                                }
                            }
                        } else item {
                            OutlinedTextField(observation.hours.value, {
                                update(observation.copy(hours = observation.hours.copy(value = it, formDraft = null, fullyObserved = false)))
                            }, enabled = !locked, label = { Text("Gelişmiş OSM saat ifadesi") },
                                modifier = Modifier.fillMaxWidth().testTag("osm-survey-hours-advanced"),
                                isError = !SurveyOpeningHours.isValid(observation.hours.value), minLines = 2)
                        }
                        item {
                            TextButton(onClick = {
                                update(observation.copy(hours = observation.hours.copy(value = "24/7", formDraft = null, fullyObserved = false)))
                                simple = false
                            }, enabled = !locked, modifier = Modifier.heightIn(min = 48.dp)) { Text("24 saat / 7 gün") }
                            val complete = SurveyOpeningHours.isValid(observation.hours.value) && !SurveyOpeningHours.isPartial(observation.hours.value)
                            SurveyConfirmation("Haftanın tamamını yerinde doğruladım.", "osm-survey-hours-complete",
                                observation.hours.fullyObserved, complete && !locked) {
                                update(observation.copy(hours = observation.hours.copy(fullyObserved = it)))
                            }
                            if (!SurveyOpeningHours.isValid(observation.hours.value)) {
                                Text("Saat ifadesini kontrol et. Taslağın saklanıyor.", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    item {
                        SurveyFieldHeader(SurveyField.WHEELCHAIR, observation.wheelchair, locked,
                            venue?.tasks(today)?.firstOrNull { it.field == SurveyField.WHEELCHAIR }) {
                            field(SurveyField.WHEELCHAIR, it)
                        }
                    }
                    if (observation.wheelchair.action == SurveyAction.EDIT) item {
                        Column {
                            for ((value, label, guidance) in listOf(
                                Triple("yes", "Uygun", "Giriş ve kullanım tekerlekli sandalye ile mümkün."),
                                Triple("limited", "Sınırlı", "Erişimin yalnız bir kısmı mümkün veya yardım gerekiyor."),
                                Triple("no", "Uygun değil", "Giriş ya da kullanım tekerlekli sandalye ile mümkün değil."))) {
                                FilterChip(selected = observation.wheelchair.value == value, enabled = !locked,
                                    onClick = { update(observation.copy(wheelchair = observation.wheelchair.copy(
                                        action = SurveyAction.EDIT, value = value, fullyObserved = true))) },
                                    label = { Text(label) }, modifier = Modifier.heightIn(min = 48.dp).testTag("osm-survey-wheelchair-$value"))
                                Text(guidance, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    item {
                        Text("Bilmiyorum, OSM’deki değeri ve kontrol tarihini korur.", style = MaterialTheme.typography.bodySmall)
                        SurveyConfirmation("Doğruladığım bilgileri yerinde gözlemledim.", "osm-survey-confirmed",
                            confirmed, !locked) { confirmed = it }
                        Button(enabled = publishable && confirmed && !locked, onClick = {
                            val value = draft ?: return@Button
                            if (!entryIsCurrent()) return@Button
                            val owner = auth.currentCredentials()
                            querying = true
                            scope.launch {
                                try {
                                    val found = onReview(value)
                                    if (entryIsCurrent() && auth.currentCredentials() === owner && isReviewCurrent(found)) {
                                        review = found
                                        reviewed = false
                                    } else error = "OSM hesabı veya ekran değişti. Gözlemi yeniden incele."
                                }
                                catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { error = "OSM bilgileri değişmiş veya gözlem doğrulanamadı. Taslağını saklayıp yeniden incele." }
                                finally { querying = false }
                            }
                        }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("osm-survey-review")) {
                            Text("OSM bilgilerini kontrol et ve incele")
                        }
                        if (!canWriteMap) TextButton(onClick = { draft?.let(onLogin) }, enabled = draft != null && structuralDateValid && !locked,
                            modifier = Modifier.heightIn(min = 48.dp)) { Text("OSM düzenleme iznini etkinleştir") }
                    }
                    item {
                        OutlinedButton(onClick = { draft?.let(onSave) }, enabled = draft != null && structuralDateValid && !locked,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("osm-survey-save")) { Text("Taslağı sakla") }
                    }
                }
                item { TextButton(onClick = { exit(onClose) }, enabled = !locked, modifier = Modifier.heightIn(min = 48.dp)) { Text("Kapat") } }
            }
        }
    }
}

private fun restoredSurveyWeek(hours: SurveyFieldObservation): SurveyWeek? =
    hours.formDraft?.let(SurveyOpeningHours::fromDraft)?.takeIf { it.expression() == hours.value }

@Composable
private fun SurveyFieldHeader(field: SurveyField, observation: SurveyFieldObservation, locked: Boolean,
    task: SurveyFieldTask?, onAction: (SurveyAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(surveyFieldLabel(field.key), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        Text("OSM'deki değer: ${observation.before ?: "Eksik"}")
        if (field == SurveyField.WHEELCHAIR && observation.before == "designated")
            Text("Tekerlekli sandalye kullanımı için özel olarak düzenlenmiş (designated).",
                style = MaterialTheme.typography.bodySmall)
        Text("Son kontrol: ${observation.beforeDate ?: "Bilinmiyor"}", style = MaterialTheme.typography.bodySmall)
        task?.let { Text(surveyStatusLabel(it.status), style = MaterialTheme.typography.bodySmall) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((action, label) in listOf(SurveyAction.VERIFY to "Hâlâ doğru", SurveyAction.EDIT to "Düzelt", SurveyAction.UNKNOWN to "Bilmiyorum")) {
                val canVerify = !observation.before.isNullOrBlank() && when (field) {
                    SurveyField.OPENING_HOURS -> SurveyOpeningHours.isValid(observation.before) && !SurveyOpeningHours.isPartial(observation.before)
                    SurveyField.WHEELCHAIR -> observation.before in setOf("yes", "limited", "no", "designated")
                }
                FilterChip(selected = observation.action == action, onClick = { onAction(action) },
                    enabled = !locked && (action != SurveyAction.VERIFY || canVerify), label = { Text(label) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("osm-survey-${field.key}-${action.name}"))
            }
        }
    }
}

@Composable
private fun SurveyConfirmation(label: String, tag: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag)
        .toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onCheckedChange = null, enabled = enabled)
        Text(label, Modifier.weight(1f))
    }
}

internal fun surveyFieldLabel(key: String): String = when (key) {
    "opening_hours" -> "Açılış saatleri"
    "wheelchair" -> "Tekerlekli sandalye erişimi"
    "check_date:opening_hours" -> "Açılış saatleri kontrol tarihi"
    "check_date:wheelchair" -> "Erişim kontrol tarihi"
    else -> key
}

internal fun surveyStatusLabel(status: SurveyTaskStatus): String = when (status) {
    SurveyTaskStatus.MISSING -> "Eksik"
    SurveyTaskStatus.PARTIAL -> "Kısmi"
    SurveyTaskStatus.UNKNOWN_DATE -> "Kontrol tarihi bilinmiyor"
    SurveyTaskStatus.DUE -> "Yeniden kontrol zamanı geldi"
}
