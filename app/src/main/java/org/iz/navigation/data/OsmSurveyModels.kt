package org.iz.navigation.data

import java.time.LocalDate
import org.iz.navigation.integration.SurveyOpeningHours
import org.iz.navigation.offline.OfflineJsonDepth
import org.json.JSONObject

enum class SurveyField(val key: String, val dateKey: String) {
    OPENING_HOURS("opening_hours", "check_date:opening_hours"), WHEELCHAIR("wheelchair", "check_date:wheelchair")
}
enum class SurveyAction { UNTOUCHED, UNKNOWN, VERIFY, EDIT }
enum class SurveyTaskStatus { MISSING, PARTIAL, UNKNOWN_DATE, DUE }
data class SurveyFieldObservation(
    val action: SurveyAction = SurveyAction.UNTOUCHED, val value: String = "", val fullyObserved: Boolean = false,
    val before: String? = null, val beforeDate: String? = null,
    val formDraft: String? = null,
)
data class SurveyObservation(
    val baseVersion: Long, val observedOn: String,
    val hours: SurveyFieldObservation = SurveyFieldObservation(), val wheelchair: SurveyFieldObservation = SurveyFieldObservation(),
)
data class SurveyVenue(
    val ref: OsmRef, val version: Long, val name: String, val latitude: Double, val longitude: Double,
    val tags: Map<String,String>, val partialFields: Set<SurveyField> = emptySet(),
) {
    val place: SelectedOsmPlace get() {
        val street = listOfNotNull(tags["addr:street"], tags["addr:housenumber"]).joinToString(" ")
        val address = tags["addr:full"]?.takeIf(String::isNotBlank) ?: listOfNotNull(street.takeIf(String::isNotBlank),
            tags["addr:district"], tags["addr:city"], tags["addr:province"]).joinToString(", ").takeIf(String::isNotBlank)
        return SelectedOsmPlace(tags["name:tr"]?.takeIf(String::isNotBlank) ?: name, latitude, longitude, ref, address)
    }
}
data class SurveyFieldTask(val field: SurveyField, val status: SurveyTaskStatus, val value: String?, val checkedOn: String?)

fun SurveyVenue.tasks(today: LocalDate = LocalDate.now()): List<SurveyFieldTask> = SurveyField.entries.mapNotNull { field ->
    val value = tags[field.key]?.takeIf(String::isNotBlank)
    val rawDate = tags[field.dateKey]
    val checked = surveyDate(rawDate)?.takeIf { !it.isAfter(today) }
    val status = when {
        value == null -> SurveyTaskStatus.MISSING
        field in partialFields -> SurveyTaskStatus.PARTIAL
        field == SurveyField.OPENING_HOURS && SurveyOpeningHours.isPartial(value) -> SurveyTaskStatus.PARTIAL
        field == SurveyField.WHEELCHAIR && value !in setOf("yes", "limited", "no", "designated") -> SurveyTaskStatus.PARTIAL
        checked == null -> SurveyTaskStatus.UNKNOWN_DATE
        !today.isBefore(checked.plusYears(1)) -> SurveyTaskStatus.DUE
        else -> null
    }
    status?.let { SurveyFieldTask(field, it, value, checked?.toString()) }
}

internal fun surveyDate(value: String?): LocalDate? = value?.takeIf { it.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")) }
    ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.takeIf { it.year > 0 }

internal fun SurveyObservation.validateDraft() {
    require(baseVersion in 1 until Long.MAX_VALUE)
    require(surveyDate(observedOn) != null) { "Geçerli bir gözlem tarihi seç." }
    for (field in listOf(hours, wheelchair)) {
        require(field.value.length <= 8192 && (field.before?.length ?: 0) <= 8192 && (field.beforeDate?.length ?: 0) <= 1024)
        field.formDraft?.let { require(SurveyOpeningHours.fromDraft(it) != null) { "Saat formu taslağı doğrulanamadı." } }
    }
}

fun SurveyObservation.encode(): String {
    validateDraft()
    fun encodeField(value: SurveyFieldObservation) = JSONObject().put("action", value.action.name).put("value", value.value)
        .put("fullyObserved", value.fullyObserved).put("before", value.before ?: JSONObject.NULL).put("beforeDate", value.beforeDate ?: JSONObject.NULL)
        .put("formDraft",value.formDraft ?: JSONObject.NULL)
    return JSONObject().put("version", 1).put("baseVersion", baseVersion).put("observedOn", observedOn)
        .put("hours", encodeField(hours)).put("wheelchair", encodeField(wheelchair)).toString().also {
            require(it.length <= 128_000 && it.toByteArray(Charsets.UTF_8).size <= 128_000)
        }
}

fun decodeSurveyObservation(json: String): SurveyObservation {
    require(json.length <= 128_000 && json.toByteArray(Charsets.UTF_8).size <= 128_000)
    try {
        val root = JSONObject(OfflineJsonDepth.requireSafe(json))
        require(root.get("version") == 1)
        val version = root.get("baseVersion"); require(version is Number && version.toDouble() == version.toLong().toDouble())
        fun field(key: String): SurveyFieldObservation {
            val value = root.getJSONObject(key)
            fun string(name: String): String { val raw = value.get(name); require(raw is String); return raw }
            fun nullable(name: String): String? = if (value.isNull(name)) null else string(name)
            val observed = value.get("fullyObserved"); require(observed is Boolean)
            return SurveyFieldObservation(SurveyAction.valueOf(string("action")), string("value"), observed, nullable("before"), nullable("beforeDate"),nullable("formDraft"))
        }
        val date = root.get("observedOn"); require(date is String)
        return SurveyObservation(version.toLong(), date, field("hours"), field("wheelchair")).also { it.validateDraft() }
    } catch (error: Exception) { throw IllegalArgumentException("Yerinde gözlem taslağı doğrulanamadı.", error) }
}

fun MapEditDraft.surveyObservation(): SurveyObservation? = surveyPayload?.let(::decodeSurveyObservation)

/** Return only actual explicit changes; untouched/unknown public values are never revalidated or rewritten. */
fun SurveyObservation.publicChanges(original: Map<String,String>, today: LocalDate = LocalDate.now()): Map<String,String> {
    validateDraft()
    require(!requireNotNull(surveyDate(observedOn)).isAfter(today)) { "Gözlem tarihi gelecekte olamaz." }
    val changes = linkedMapOf<String,String>()
    for ((field, observation) in listOf(SurveyField.OPENING_HOURS to hours, SurveyField.WHEELCHAIR to wheelchair)) {
        if (observation.action in setOf(SurveyAction.UNKNOWN, SurveyAction.UNTOUCHED)) continue
        require(original[field.key] == observation.before && original[field.dateKey] == observation.beforeDate) { "OSM bilgisi değişti. Güncel değerleri yeniden incele." }
        val after = if (observation.action == SurveyAction.VERIFY) requireNotNull(observation.before) else observation.value
        require(after.isNotBlank() && after.codePointCount(0, after.length) <= 255) { "OSM alanı en fazla 255 karakter olabilir." }
        if (field == SurveyField.OPENING_HOURS) {
            observation.formDraft?.let {
                val week = requireNotNull(SurveyOpeningHours.fromDraft(it))
                require(week.expression() == after && week.validInput()) { "Saat ifadesini kontrol et. Taslağın saklanıyor." }
            }
            require(SurveyOpeningHours.isValid(after)) { "Saat ifadesini kontrol et. Taslağın saklanıyor." }
            if (observation.fullyObserved) require(!SurveyOpeningHours.isPartial(after)) { "Bilinmeyen saatler tam doğrulanmış sayılamaz." }
        } else {
            val allowed = if (observation.action == SurveyAction.VERIFY) setOf("yes", "limited", "no", "designated") else setOf("yes", "limited", "no")
            require(after in allowed && observation.fullyObserved)
        }
        if (observation.action == SurveyAction.VERIFY) require(observation.fullyObserved)
        if (after != original[field.key]) changes[field.key] = after
        if (observation.fullyObserved && original[field.dateKey] != observedOn) changes[field.dateKey] = observedOn
    }
    return changes
}

/** Installation-local, account-bound success can overlay an equal/older public snapshot, never a newer object. */
fun applySurveyReceipts(venue: SurveyVenue, drafts: List<MapEditDraft>, accountId: Long?): SurveyVenue {
    if (accountId == null) return venue
    var current = venue
    val candidates = drafts.filter { it.status == MapEditStatus.SENT && it.submittedBy == accountId && it.localChangesetCreated &&
        !it.localAttemptId.isNullOrBlank() && it.targetType == venue.ref.type && it.targetId == venue.ref.id && it.appliedVersion != null }
        .sortedWith(compareBy<MapEditDraft> { it.appliedVersion }.thenBy { it.submittedAt }.thenBy { it.id })
    for (draft in candidates) {
        val observation = runCatching { draft.surveyObservation() }.getOrNull() ?: continue
        val applied = requireNotNull(draft.appliedVersion)
        if (draft.reviewedVersion != observation.baseVersion || applied != observation.baseVersion + 1 || current.version > applied) continue
        val original = buildMap {
            for ((field, observed) in listOf(SurveyField.OPENING_HOURS to observation.hours, SurveyField.WHEELCHAIR to observation.wheelchair)) {
                observed.before?.let { put(field.key, it) }; observed.beforeDate?.let { put(field.dateKey, it) }
            }
        }
        val changes = runCatching { observation.publicChanges(original, LocalDate.MAX) }.getOrNull() ?: continue
        val partial = current.partialFields.toMutableSet()
        for ((field, observed) in listOf(SurveyField.OPENING_HOURS to observation.hours, SurveyField.WHEELCHAIR to observation.wheelchair)) {
            if (observed.action in setOf(SurveyAction.EDIT, SurveyAction.VERIFY)) {
                if (observed.fullyObserved) partial.remove(field) else partial.add(field)
            }
        }
        current = current.copy(version = applied, tags = current.tags + changes, partialFields = partial.toSet())
    }
    return current
}
