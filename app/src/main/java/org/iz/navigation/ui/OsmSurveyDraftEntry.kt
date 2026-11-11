package org.iz.navigation.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import org.iz.navigation.data.*
import org.iz.navigation.integration.osm.OsmCorrectionElement

internal data class OsmSurveyDraftEntry(val draft: MapEditDraft, val target: OsmCorrectionElement)

internal suspend fun findEditableSurveyDraft(repository: DiaryRepository, ref: OsmRef, accountId: Long?): MapEditDraft? =
    repository.mapEdits.first().filter {
        it.targetType == ref.type && it.targetId == ref.id && it.surveyPayload != null &&
            it.status in setOf(MapEditStatus.DRAFT, MapEditStatus.FAILED) &&
            (it.submittedBy == null || it.submittedBy == accountId)
    }.maxWithOrNull(compareBy<MapEditDraft> { it.observedAt }.thenBy { it.id })

/** Shared entry path: fresh object read and persistent draft selection, never a Compose initial empty list. */
internal suspend fun openSurveyDraftEntry(
    repository: DiaryRepository, place: Place, newDraftId: String, accountId: Long?,
    isCurrent: () -> Boolean, readTarget: suspend (OsmRef) -> OsmCorrectionElement,
    findExisting: suspend (OsmRef, Long?) -> MapEditDraft? = { ref, account -> findEditableSurveyDraft(repository, ref, account) },
): OsmSurveyDraftEntry {
    fun current() { if (!isCurrent()) throw CancellationException("OSM hesabı veya ekran değişti.") }
    current()
    val ref = OsmRef(requireNotNull(place.osmType), requireNotNull(place.osmId))
    val target = readTarget(ref)
    current()
    require(target.ref == ref)
    val existing = findExisting(ref, accountId)
    current()
    if (existing != null) {
        require(existing.targetType == ref.type && existing.targetId == ref.id &&
            existing.status in setOf(MapEditStatus.DRAFT, MapEditStatus.FAILED) &&
            (existing.submittedBy == null || existing.submittedBy == accountId) &&
            existing.surveyPayload != null)
        requireNotNull(existing.surveyObservation())
        return OsmSurveyDraftEntry(existing, target)
    }
    val observation = SurveyObservation(target.version, java.time.LocalDate.now().toString(),
        hours = SurveyFieldObservation(before = target.tags["opening_hours"], beforeDate = target.tags["check_date:opening_hours"]),
        wheelchair = SurveyFieldObservation(before = target.tags["wheelchair"], beforeDate = target.tags["check_date:wheelchair"]))
    val draft = MapEditDraft(id = newDraftId, latitude = requireNotNull(place.latitude),
        longitude = requireNotNull(place.longitude), preset = target.preset(), targetType = ref.type,
        targetId = ref.id, surveyPayload = observation.encode())
    repository.saveMapEdit(draft) { current -> current == null && isCurrent() }
    current()
    return OsmSurveyDraftEntry(draft, target)
}
