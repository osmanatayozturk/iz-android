package org.iz.navigation.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

enum class MapEditStatus { DRAFT, SENDING, SENT, FAILED, UNKNOWN }
enum class MapEditStage { NONE, CREATE_CHANGESET, UPLOAD_NODE, CLOSE_CHANGESET }
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
) {
    fun publicTags(): Map<String, String> = linkedMapOf(preset.key to preset.value).apply {
        if (name.isNotBlank()) put("name", name.trim())
        if (street.isNotBlank()) put("addr:street", street.trim())
        if (houseNumber.isNotBlank()) put("addr:housenumber", houseNumber.trim())
    }

    /** A definitely rejected attempt may be edited; uncertain/published attempts stay immutable. */
    fun editableCopy(): MapEditDraft {
        require(status in setOf(MapEditStatus.DRAFT, MapEditStatus.FAILED))
        return copy(status = MapEditStatus.DRAFT, stage = MapEditStage.NONE, submittedAt = null, submittedBy = null,
            changesetId = null, remoteNodeId = null, remoteNodeVersion = null, changesetClosed = false, error = null)
    }
}

fun validateMapEdit(value: MapEditDraft) {
    require(runCatching { UUID.fromString(value.id).toString().equals(value.id, true) }.getOrDefault(false))
    require(value.placeId == null || (value.placeId.isNotBlank() && value.placeId.length <= 200 && value.placeId.none { it.code < 32 }))
    require(value.latitude.isFinite() && value.latitude in -90.0..90.0 && value.longitude.isFinite() && value.longitude in -180.0..180.0)
    listOf(value.name, value.street, value.houseNumber).forEach { field ->
        require(field.codePointCount(0, field.length) <= 255 && field.none { it.code < 32 || it.code == 127 })
    }
    require(value.observedAt >= 0 && (value.error == null || value.error.length <= 4000))
    require(value.changesetId == null || value.changesetId > 0)
    require((value.remoteNodeId == null && value.remoteNodeVersion == null) ||
        (value.remoteNodeId != null && value.remoteNodeId > 0 && value.remoteNodeVersion != null && value.remoteNodeVersion > 0 && value.changesetId != null))
    require(!value.changesetClosed || value.changesetId != null)
    if (value.status == MapEditStatus.DRAFT) {
        require(value.stage == MapEditStage.NONE && value.submittedAt == null && value.submittedBy == null &&
            value.changesetId == null && value.remoteNodeId == null && !value.changesetClosed)
    } else {
        require(value.stage != MapEditStage.NONE && value.submittedAt != null && value.submittedAt > 0 && value.submittedBy != null && value.submittedBy > 0)
        require(value.surveyConfirmed)
        if (value.stage != MapEditStage.CREATE_CHANGESET) require(value.changesetId != null)
        if (value.status == MapEditStatus.SENT) require(value.remoteNodeId != null && value.stage == MapEditStage.CLOSE_CHANGESET)
        if (value.status == MapEditStatus.FAILED) require(value.remoteNodeId == null)
    }
}

fun validateMapEditForPublish(value: MapEditDraft) {
    validateMapEdit(value)
    require(value.surveyConfirmed) { "Yalnızca yerinde doğruladığın bilgileri yayımla." }
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
