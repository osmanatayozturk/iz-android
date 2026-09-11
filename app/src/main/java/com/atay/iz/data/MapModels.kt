package com.atay.iz.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

data class GeoCoordinate(val latitude: Double, val longitude: Double)
enum class OsmType { NODE, WAY, RELATION }
data class OsmRef(val type: OsmType, val id: Long)
enum class PlaceSource { LEGACY, OSM, USER }
data class SelectedOsmPlace(val name: String, val latitude: Double, val longitude: Double, val osmRef: OsmRef? = null)

enum class ContributionKind { MISSING_PLACE, WRONG_DETAILS, CLOSED_PLACE, OTHER }
enum class ContributionStatus { DRAFT, SENDING, SENT, FAILED, UNKNOWN }

/** A deliberately written public observation, independent of private visits and their deletion. */
@Entity(tableName = "contribution_drafts", indices = [Index("placeId")])
data class ContributionDraft(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val placeId: String? = null,
    val latitude: Double,
    val longitude: Double,
    val observedAt: Long,
    val kind: ContributionKind,
    val text: String,
    val status: ContributionStatus = ContributionStatus.DRAFT,
    val osmType: OsmType? = null,
    val osmId: Long? = null,
    val remoteNoteId: Long? = null,
    val remoteStatus: String? = null,
    val submittedAt: Long? = null,
    val submittedBy: Long? = null,
    val error: String? = null,
)

internal fun requireValidOsmReference(type: OsmType?, id: Long?) {
    require((type == null && id == null) || (type != null && id != null && id > 0)) { "Geçersiz OpenStreetMap kimliği." }
}

internal fun validateContribution(draft: ContributionDraft) {
    require(runCatching { UUID.fromString(draft.id).toString().equals(draft.id, ignoreCase = true) }.getOrDefault(false)) { "Geçersiz katkı kimliği." }
    require(draft.placeId == null || (draft.placeId.isNotBlank() && draft.placeId.length <= 200 && draft.placeId.none { it.code < 32 }))
    require(draft.latitude.isFinite() && draft.longitude.isFinite() && draft.latitude in -90.0..90.0 && draft.longitude in -180.0..180.0) { "Geçersiz katkı koordinatı." }
    require(draft.observedAt >= 0) { "Geçersiz gözlem zamanı." }
    require(draft.text.length <= 2_000 && draft.text.none { it.code < 32 && it != '\n' && it != '\r' && it != '\t' }) { "Katkı metni en fazla 2000 karakter olabilir." }
    requireValidOsmReference(draft.osmType, draft.osmId)
    require(draft.remoteNoteId == null || draft.remoteNoteId > 0)
    require(draft.remoteStatus == null || draft.remoteStatus in setOf("open", "closed", "hidden"))
    require(draft.remoteStatus == null || draft.remoteNoteId != null)
    require(draft.error == null || draft.error.length <= 4_000)
    if (draft.status == ContributionStatus.DRAFT) {
        require(draft.submittedAt == null && draft.submittedBy == null && draft.remoteNoteId == null && draft.remoteStatus == null) { "Gönderilmemiş taslakta uzak kayıt olamaz." }
    } else {
        require(draft.text.isNotBlank() && draft.submittedAt != null && draft.submittedAt > 0 && draft.submittedBy != null && draft.submittedBy > 0) { "Eksik gönderim bilgisi." }
        if (draft.status == ContributionStatus.SENT) require(draft.remoteNoteId != null && draft.remoteStatus != null) { "Gönderilen katkının uzak kimliği gerekli." }
        if (draft.status == ContributionStatus.SENDING || draft.status == ContributionStatus.FAILED) require(draft.remoteNoteId == null && draft.remoteStatus == null)
    }
}

/** Must run before replacing any database rows or installing backup photos. */
internal fun validateMapSnapshot(snapshot: DiarySnapshot) {
    snapshot.places.forEach { requireValidOsmReference(it.osmType, it.osmId) }
    require(snapshot.contributions.map { it.id }.toSet().size == snapshot.contributions.size) { "Yinelenen katkı kimliği." }
    snapshot.contributions.forEach(::validateContribution)
}
