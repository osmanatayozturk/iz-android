package org.iz.navigation.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

enum class MapEditStatus { DRAFT, SENDING, SENT, FAILED, UNKNOWN }
enum class MapEditStage { NONE, CREATE_CHANGESET, UPLOAD_NODE, UPDATE_ELEMENT, CLOSE_CHANGESET }
enum class MapEditFieldAction { UNTOUCHED, SET, DELETE }
enum class MapPlacePreset(val label: String, val key: String, val value: String) {
    CAFE("Kafe", "amenity", "cafe"),
    RESTAURANT("Restoran", "amenity", "restaurant"),
    SCHOOL("Okul", "amenity", "school"),
    PHARMACY("Eczane", "amenity", "pharmacy"),
    CONVENIENCE("Bakkal", "shop", "convenience"),
    SUPERMARKET("Süpermarket", "shop", "supermarket"),
    FUEL("Akaryakıt istasyonu", "amenity", "fuel"),
    DRINKING_WATER("İçme suyu", "amenity", "drinking_water"),
    TOILETS("Tuvalet", "amenity", "toilets"),
    BICYCLE_REPAIR("Bisiklet tamir istasyonu", "amenity", "bicycle_repair_station"),
    HOSPITAL("Hastane", "amenity", "hospital"),
    UNIVERSITY("Üniversite", "amenity", "university"),
    PUBLIC_BATH("Hamam", "amenity", "public_bath");

    val canCreate: Boolean get() = this !in setOf(HOSPITAL, UNIVERSITY, PUBLIC_BATH)
}

/** Public map contribution; deliberately independent of visits, photos and health records. */
@Entity(tableName = "map_edit_drafts", indices = [Index("placeId")])
data class MapEditDraft(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val placeId: String? = null,
    val latitude: Double,
    val longitude: Double,
    val preset: MapPlacePreset = MapPlacePreset.CAFE,
    val name: String = "",
    val street: String = "",
    val houseNumber: String = "",
    val observedAt: Long = System.currentTimeMillis(),
    val surveyConfirmed: Boolean = false,
    val status: MapEditStatus = MapEditStatus.DRAFT,
    val stage: MapEditStage = MapEditStage.NONE,
    val submittedAt: Long? = null,
    val submittedBy: Long? = null,
    val changesetId: Long? = null,
    val remoteNodeId: Long? = null,
    val remoteNodeVersion: Long? = null,
    val changesetClosed: Boolean = false,
    val error: String? = null,
    val targetType: OsmType? = null,
    val targetId: Long? = null,
    val reviewedVersion: Long? = null,
    val reviewedFingerprint: String? = null,
    val appliedVersion: Long? = null,
    // Installation-local attempt identity and receipt. Neither is portable backup data.
    val localAttemptId: String? = null,
    @ColumnInfo(defaultValue = "0") val localChangesetCreated: Boolean = false,
    val surveyPayload: String? = null,
    @ColumnInfo(defaultValue = "1") val editorVersion: Int = 1,
    val presetId: String? = null,
    val presetSnapshot: String? = null,
    val originalPresetSnapshot: String? = null,
    @ColumnInfo(defaultValue = "''") val phone: String = "",
    @ColumnInfo(defaultValue = "'UNTOUCHED'") val nameAction: MapEditFieldAction = MapEditFieldAction.UNTOUCHED,
    @ColumnInfo(defaultValue = "'UNTOUCHED'") val phoneAction: MapEditFieldAction = MapEditFieldAction.UNTOUCHED,
    @ColumnInfo(defaultValue = "0") val typeChanged: Boolean = false,
    @ColumnInfo(defaultValue = "0") val legacyAddressIntent: Boolean = false,
) {
    fun publicTags(): Map<String, String> = if (editorVersion == 2) editorPublicTags() else linkedMapOf<String, String>().apply {
        if (targetType == null) put(preset.key, preset.value)
        if (name.isNotBlank()) put("name", name.trim())
        if (street.isNotBlank()) put("addr:street", street.trim())
        if (houseNumber.isNotBlank()) put("addr:housenumber", houseNumber.trim())
    }

    /** A definitely rejected attempt may be edited; uncertain/published attempts stay immutable. */
    fun editableCopy(): MapEditDraft {
        require(status in setOf(MapEditStatus.DRAFT, MapEditStatus.FAILED))
        return copy(status = MapEditStatus.DRAFT, stage = MapEditStage.NONE, submittedAt = null, submittedBy = null,
            changesetId = null, remoteNodeId = null, remoteNodeVersion = null, changesetClosed = false, error = null,
            reviewedVersion = null, reviewedFingerprint = null, appliedVersion = null,
            localAttemptId = null, localChangesetCreated = false)
    }
}

internal fun MapEditDraft.withLocalChangeset(changeset: Long): MapEditDraft {
    require(status == MapEditStatus.SENDING && stage == MapEditStage.CREATE_CHANGESET)
    require(changesetId == null && localAttemptId != null && !localChangesetCreated && changeset > 0)
    return copy(changesetId = changeset, localChangesetCreated = true,
        stage = if (targetType == null) MapEditStage.UPLOAD_NODE else MapEditStage.UPDATE_ELEMENT)
}

fun validateMapEdit(value: MapEditDraft) {
    value.validateEditorDraft()
    require(value.editorVersion == 2 || value.targetType != null || value.preset.canCreate) { "Bu tür yalnızca mevcut OSM mekânlarında düzenlenebilir." }
    value.surveyObservation()?.let { require(value.targetType != null); it.validateDraft() }
    require(runCatching { UUID.fromString(value.id).toString().equals(value.id, true) }.getOrDefault(false))
    require(value.placeId == null || (value.placeId.isNotBlank() && value.placeId.length <= 200 && value.placeId.none { it.code < 32 }))
    require(value.latitude.isFinite() && value.latitude in -90.0..90.0 && value.longitude.isFinite() && value.longitude in -180.0..180.0)
    listOf(value.name, value.street, value.houseNumber).forEach { field ->
        require(field.codePointCount(0, field.length) <= 255 && field.none { it.code < 32 || it.code == 127 })
    }
    require(value.observedAt >= 0 && (value.error == null || value.error.length <= 4000))
    require(value.changesetId == null || value.changesetId > 0)
    require(!value.localChangesetCreated || (value.localAttemptId != null && value.changesetId != null))
    require((value.remoteNodeId == null && value.remoteNodeVersion == null) ||
        (value.remoteNodeId != null && value.remoteNodeId > 0 && value.remoteNodeVersion != null && value.remoteNodeVersion > 0 && value.changesetId != null))
    require(!value.changesetClosed || value.changesetId != null)
    validateMapEditTarget(value)
    if (value.status == MapEditStatus.DRAFT) {
        require(value.stage == MapEditStage.NONE && value.submittedAt == null && value.submittedBy == null &&
            value.changesetId == null && value.remoteNodeId == null && !value.changesetClosed && value.localAttemptId == null)
    } else {
        require(value.stage != MapEditStage.NONE && value.submittedAt != null && value.submittedAt > 0 && value.submittedBy != null && value.submittedBy > 0)
        require(value.surveyConfirmed)
        if (value.stage != MapEditStage.CREATE_CHANGESET) require(value.changesetId != null)
        if (value.status == MapEditStatus.SENT) require(
            (value.remoteNodeId != null || value.appliedVersion != null) && value.stage == MapEditStage.CLOSE_CHANGESET,
        )
        if (value.status == MapEditStatus.FAILED) require(value.remoteNodeId == null)
    }
}

private fun validateMapEditTarget(value: MapEditDraft) {
    if (value.targetType == null) {
        require(value.targetId == null && value.reviewedVersion == null && value.reviewedFingerprint == null &&
            value.appliedVersion == null && value.stage != MapEditStage.UPDATE_ELEMENT)
        return
    }
    require(value.targetType == OsmType.NODE || value.targetType == OsmType.WAY)
    require(value.targetId != null && value.targetId > 0)
    require(value.remoteNodeId == null && value.stage != MapEditStage.UPLOAD_NODE)
    if (value.status == MapEditStatus.DRAFT) {
        require(value.reviewedVersion == null && value.reviewedFingerprint == null && value.appliedVersion == null)
        return
    }
    val reviewed = requireNotNull(value.reviewedVersion)
    require(reviewed in 1 until Long.MAX_VALUE)
    require(value.reviewedFingerprint?.let { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } } == true)
    val hasSurveyIntent = value.surveyObservation()?.let { observation ->
        listOf(observation.hours, observation.wheelchair).any { it.action in setOf(SurveyAction.EDIT, SurveyAction.VERIFY) }
    } == true
    require(value.hasEditorIntent() || value.publicTags().isNotEmpty() || hasSurveyIntent)
    require(value.appliedVersion == if (value.status == MapEditStatus.SENT) reviewed + 1 else null)
}

fun validateMapEditForPublish(value: MapEditDraft) {
    validateMapEdit(value)
    require(value.surveyConfirmed) { "Yalnızca yerinde doğruladığın bilgileri yayımla." }
    if (value.editorVersion == 2) {
        if (value.targetType == null) requireNotNull(value.selectedPresetSnapshot()) { "Bir tür seç." }
        else require(value.hasEditorIntent()) { "En az bir bilgiyi değiştir." }
    }
    value.surveyObservation()?.let { observation ->
        val before = buildMap {
            for ((field, input) in listOf(SurveyField.OPENING_HOURS to observation.hours, SurveyField.WHEELCHAIR to observation.wheelchair)) {
                input.before?.let { put(field.key, it) }; input.beforeDate?.let { put(field.dateKey, it) }
            }
        }
        observation.publicChanges(before)
    }
}

/** Keep the Room 4→5 migration identical to the generated entity schema. */
const val CREATE_MAP_EDIT_DRAFTS_SQL = """CREATE TABLE IF NOT EXISTS map_edit_drafts (
    id TEXT NOT NULL PRIMARY KEY, placeId TEXT, latitude REAL NOT NULL, longitude REAL NOT NULL,
    preset TEXT NOT NULL, name TEXT NOT NULL, street TEXT NOT NULL, houseNumber TEXT NOT NULL,
    observedAt INTEGER NOT NULL, surveyConfirmed INTEGER NOT NULL, status TEXT NOT NULL, stage TEXT NOT NULL,
    submittedAt INTEGER, submittedBy INTEGER, changesetId INTEGER, remoteNodeId INTEGER, remoteNodeVersion INTEGER,
    changesetClosed INTEGER NOT NULL, error TEXT
)"""
const val CREATE_MAP_EDIT_INDEX_SQL = "CREATE INDEX IF NOT EXISTS index_map_edit_drafts_placeId ON map_edit_drafts (placeId)"
