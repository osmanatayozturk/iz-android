package org.iz.navigation.data

import android.content.Context
import androidx.room.withTransaction
import org.iz.navigation.tracking.AutomaticCandidateDecision
import org.iz.navigation.tracking.TrackingPolicy
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import org.iz.navigation.power.AnalysisInput
import org.iz.navigation.power.PostJourneyAnalysis
import org.iz.navigation.power.PowerDiagnostics
import org.iz.navigation.lean.LeanStore

class DiaryRepository internal constructor(context: Context, private val db: DiaryDatabase) {
    constructor(context: Context) : this(context, DiaryDatabase.get(context.applicationContext))
    private val appContext = context.applicationContext
    internal val leanStore = LeanStore(appContext, db)
    private val dao = db.diaryDao()
    private val mapDao = db.mapEditDao()
    private val watchDao = db.watchHealthDao()
    private val libraryDao = db.travelLibraryDao()
    /** Invalidate CPU work before waiting; all raw mutations order Raw lease -> privacy -> Room. */
    internal suspend fun <T> withRawMutationLease(action: suspend () -> T): T {
        val analysis = PostJourneyAnalysis.get(appContext)
        return LeanStore.withReadyRawMutation(analysis::awaitReady, analysis::request, action)
    }
    private val clock = flow {
        while (true) { emit(System.currentTimeMillis()); delay(1_000) }
    }
    val journeys: Flow<List<Journey>> = combine(dao.journeys(), clock) { values, now ->
        values.filterNot { DiaryRules.isExpired(it, now) }
    }.distinctUntilChanged()
    val points: Flow<List<TrackPoint>> = combine(dao.points(), journeys) { values, trips ->
        val ids = trips.map { it.id }.toSet()
        values.filter { it.journeyId in ids }
    }.distinctUntilChanged()
    val places: Flow<List<Place>> = dao.places()
    val visits: Flow<List<Visit>> = dao.visits()
    val photos: Flow<List<Photo>> = dao.photos()
    val drafts: Flow<List<ShareDraft>> = dao.drafts()
    val contributions: Flow<List<ContributionDraft>> = dao.contributions()
    val mapEdits: Flow<List<MapEditDraft>> = mapDao.observeAll()
    val healthSamples: Flow<List<JourneyHealthSample>> = combine(dao.healthSamples(), journeys) { values, trips ->
        val ids = trips.map { it.id }.toSet()
        values.filter { it.journeyId in ids }
    }.distinctUntilChanged()
    val healthSyncs: Flow<List<JourneyHealthSync>> = combine(dao.healthSyncs(), journeys) { values, trips ->
        val ids = trips.map { it.id }.toSet()
        values.filter { it.journeyId in ids }
    }.distinctUntilChanged()
    val healthSummaries: Flow<List<JourneyHealthSummary>> = flow {
        emitAll(PostJourneyAnalysis.get(appContext).state.map { analysis ->
            if (analysis.pending) emptyList() else analysis.entries.values.map { it.health }
        })
    }.distinctUntilChanged()

    /** A coherent raw snapshot, read only by the gated post-journey worker. No derived DB fields. */
    internal suspend fun analysisInput(checkActive: () -> Unit = {}): AnalysisInput = db.withTransaction {
        checkActive()
        val trips = dao.allJourneys(); checkActive()
        val points = dao.allPoints(); checkActive()
        val samples = dao.allHealthSamples(); checkActive()
        val syncs = dao.allHealthSyncs(); checkActive()
        val watch = dao.analysisWatchSamples(); checkActive()
        AnalysisInput(trips, points, samples, syncs, watch)
    }
    private suspend fun <T> privacyChange(block: suspend (PostJourneyAnalysis) -> T): T {
        val analysis = PostJourneyAnalysis.get(appContext)
        return analysis.privacyChange { block(analysis) }
    }
    private suspend fun <T> recordingChange(block: suspend () -> T): T {
        val analysis = PostJourneyAnalysis.get(appContext)
        analysis.recordingWillChange()
        return try { block() } finally { analysis.recordingDidChange() }
    }

    suspend fun activeJourney(): Journey? = dao.activeJourney(System.currentTimeMillis())
    suspend fun getJourney(id: String): Journey? = dao.journey(id)
    suspend fun journeyPoints(id: String): List<TrackPoint> = dao.journeyPoints(id)
    fun observeJourneyPoints(id: String): Flow<List<TrackPoint>> = dao.observeJourneyPoints(id)
    suspend fun allHealthJourneys(): List<Journey> = dao.allJourneys().filter { it.status == JourneyStatus.CONFIRMED }

    /** Commit only after every requested HC page succeeds. Missing permissions must be excluded from metrics. */
    suspend fun replaceJourneyHealth(journeyIds: Set<String>, samples: List<ImportedHealthSample>,
        metrics: Set<HealthMetric> = HealthMetric.entries.toSet(), now: Long = System.currentTimeMillis()) = privacyChange { analysis -> db.withTransaction {
        require(now > 0)
        if (metrics.isEmpty() || journeyIds.isEmpty()) return@withTransaction
        val trips = dao.allJourneys().filter { it.id in journeyIds && it.status == JourneyStatus.CONFIRMED }
        trips.forEach { dao.deleteJourneyHealth(it.id, metrics.toList()) }
        val candidates = samples.filter { it.metric in metrics && HealthRules.isValid(it) }
        val associated = trips.flatMap { trip -> candidates.filter { HealthRules.belongsTo(it, trip, now) }.map { it.forJourney(trip.id) } }
        dao.saveHealthSamples(associated)
        dao.saveHealthSyncs(trips.flatMap { trip -> metrics.map { JourneyHealthSync(trip.id, it, now) } })
        analysis.purgeHealth(trips.map { it.id }.toSet())
    } }

    /** A changed parent record replaces all its old samples, including an update that now has no eligible samples. */
    suspend fun applyHealthChanges(upserted: List<ImportedHealthSample>, deletedSourceIds: Set<String>,
        now: Long = System.currentTimeMillis(), journeyIds: Set<String>? = null) = privacyChange { analysis -> db.withTransaction {
        require(now > 0)
        val sourceIds = deletedSourceIds + upserted.map { it.sourceId }
        val previous = sourceIds.toList().chunked(400).flatMap { dao.healthSamplesBySources(it) }
        sourceIds.toList().chunked(400).forEach { dao.deleteHealthSources(it) }
        val trips = dao.allJourneys().filter { it.status == JourneyStatus.CONFIRMED && (journeyIds == null || it.id in journeyIds) }
        val candidates = upserted.filter(HealthRules::isValid)
        val associated = trips.flatMap { trip -> candidates.filter { HealthRules.belongsTo(it, trip, now) }.map { it.forJourney(trip.id) } }
        dao.saveHealthSamples(associated)
        val existingIds = trips.map { it.id }.toSet()
        dao.saveHealthSyncs((previous + associated).filter { it.journeyId in existingIds }
            .map { JourneyHealthSync(it.journeyId, it.metric, now) }.distinctBy { it.journeyId to it.metric })
        analysis.purgeHealth((previous + associated).map { it.journeyId }.toSet())
    } }

    suspend fun clearHealthData() = privacyChange { analysis -> db.withTransaction {
        dao.clearHealthSamples()
        dao.clearHealthSyncs()
        watchDao.clearSamples()
        watchDao.clearSessions()
        // Purge before Room commits. A checked file error rolls back raw deletion too.
        analysis.purgeHealth()
    } }

    suspend fun createJourney(transport: Transport, temporary: Boolean, now: Long = System.currentTimeMillis()): Journey = recordingChange { db.withTransaction {
        dao.activeJourney(now) ?: Journey(
            transport = transport,
            status = if (temporary) JourneyStatus.TEMPORARY else JourneyStatus.CONFIRMED,
            startedAt = now,
            expiresAt = if (temporary) now + DiaryRules.TEMPORARY_LIFETIME_MILLIS else null,
        ).also { dao.save(it) }
    } }

    suspend fun confirmJourney(id: String, transport: Transport) = db.withTransaction {
        val journey = dao.journey(id) ?: return@withTransaction
        if (journey.status != JourneyStatus.TEMPORARY) return@withTransaction
        require(!DiaryRules.isExpired(journey, System.currentTimeMillis())) { "Geçici kaydın süresi doldu." }
        dao.save(DiaryRules.confirmed(journey, transport))
    }

    suspend fun finishJourney(id: String, now: Long = System.currentTimeMillis()) = privacyChange { analysis -> db.withTransaction {
        dao.journey(id)?.let { if (it.endedAt == null) {
            val finished = it.copy(endedAt = now.coerceAtLeast(it.startedAt))
            dao.save(finished)
            dao.pruneHealthOutsideJourney(id, finished.startedAt, finished.endedAt!!)
            watchDao.pruneOutsideJourney(id, finished.startedAt, finished.endedAt)
            analysis.purgeJourneys(setOf(id))
        } }
    } }

    /** Resolves against the latest row in one transaction so a stale timeout cannot erase a saved memory. */
    suspend fun resolveAutomaticCandidate(id: String, now: Long = System.currentTimeMillis(), restart: Boolean = true): Journey? = deleteWithFiles(purgeJourneys = true) {
        val current = dao.journey(id) ?: return@deleteWithFiles null
        when (TrackingPolicy.automaticCandidateDecision(current, dao.journeyPoints(id), now)) {
            AutomaticCandidateDecision.KEEP -> current
            AutomaticCandidateDecision.CONFIRM -> DiaryRules.confirmed(current, current.transport).also { dao.save(it) }
            AutomaticCandidateDecision.RESET -> {
                dao.deleteDirectPhotos(id)
                dao.deleteJourney(id)
                if (restart) Journey(transport = current.transport, status = JourneyStatus.TEMPORARY,
                    startedAt = now, expiresAt = now + DiaryRules.TEMPORARY_LIFETIME_MILLIS).also { dao.save(it) } else null
            }
        }
    }

    suspend fun rejectJourney(id: String) {
        deleteWithFiles(purgeJourneys = true) {
            val journey = dao.journey(id)
            // A delayed notification must not discard an already confirmed journey.
            if (journey?.status == JourneyStatus.TEMPORARY) {
                dao.deleteDirectPhotos(id)
                dao.deleteJourney(id)
            }
        }
    }

    suspend fun markInterrupted(id: String) = privacyChange { analysis -> db.withTransaction {
        dao.journey(id)?.let {
            if (it.endedAt == null) {
                val interrupted = it.copy(
                    endedAt = (dao.lastPointTime(id) ?: System.currentTimeMillis()).coerceAtLeast(it.startedAt),
                    interrupted = true,
                )
                dao.save(interrupted)
                dao.pruneHealthOutsideJourney(id, interrupted.startedAt, interrupted.endedAt!!)
                watchDao.pruneOutsideJourney(id, interrupted.startedAt, interrupted.endedAt)
                analysis.purgeJourneys(setOf(id))
            }
        }
    } }

    suspend fun cleanupExpired(now: Long = System.currentTimeMillis()) = deleteWithFiles(purgeJourneys = true) {
        dao.allJourneys().filter { DiaryRules.isExpired(it, now) }.forEach {
            dao.deleteDirectPhotos(it.id)
            dao.deleteJourney(it.id)
        }
    }

    suspend fun addPoint(point: TrackPoint) {
        val saved = db.withTransaction {
            if (!DiaryRules.isUsablePoint(point)) return@withTransaction false
            val journey = dao.journey(point.journeyId) ?: return@withTransaction false
            val candidateDeadline = TrackingPolicy.automaticCandidateDeadline(journey)
            if (candidateDeadline != null && point.recordedAt >= candidateDeadline) return@withTransaction false
            if (journey.endedAt == null && !DiaryRules.isExpired(journey, System.currentTimeMillis()) && point.recordedAt >= journey.startedAt) {
                dao.save(point)
                true
            } else false
        }
        if (saved) PowerDiagnostics.count(PowerDiagnostics.Metric.GPS_POINT_SAVED)
    }

    /** Absolute session totals make duplicate or reordered callbacks harmless. */
    suspend fun recordWalkingSteps(journeyId: String, observedTotal: Long): Boolean = db.withTransaction {
        require(observedTotal >= 0)
        val now = System.currentTimeMillis()
        val journey = dao.journey(journeyId) ?: return@withTransaction false
        if (!journey.transport.supportsSteps || journey.endedAt != null ||
            DiaryRules.isExpired(journey, now) || dao.activeJourney(now)?.id != journeyId) return@withTransaction false
        if (journey.stepCount == null || observedTotal > journey.stepCount) {
            dao.save(journey.copy(stepCount = observedTotal))
        }
        true
    }

    /** Editors update only user-editable fields on the latest row, never stale recording state. */
    suspend fun updateJourneyDetails(id: String, title: String, note: String, transport: Transport): Boolean = withRawMutationLease { privacyChange { _ -> db.withTransaction {
        val current = dao.journey(id) ?: return@withTransaction false
        if (current.transport != transport) db.leanDao().clearJourney(id)
        dao.save(current.copy(
            title = title,
            note = note,
            transport = transport,
            stepCount = current.stepCount.takeIf { transport.supportsSteps && current.transport.supportsSteps },
        ))
        true
    } } }

    suspend fun saveJourney(entity: Journey) = recordingChange { withRawMutationLease { privacyChange { analysis -> db.withTransaction {
        require(entity.endedAt == null || entity.endedAt >= entity.startedAt)
        require(entity.stepCount == null || entity.stepCount >= 0)
        val active = dao.activeJourney(System.currentTimeMillis())
        require(entity.endedAt != null || active == null || active.id == entity.id) { "Zaten aktif bir yolculuk var." }
        val current = dao.journey(entity.id)
        // An editor may hold an older snapshot while the walking sensor keeps recording.
        val steps = if (entity.transport.supportsSteps) {
            listOfNotNull(entity.stepCount, current?.takeIf { it.transport.supportsSteps }?.stepCount).maxOrNull()
        } else null
        if (current != null && (current.transport != entity.transport || entity.status != JourneyStatus.CONFIRMED)) db.leanDao().clearJourney(entity.id)
        dao.save(entity.copy(stepCount = steps))
        libraryDao.pruneIneligibleMemberships()
        dao.pruneHealthOutsideJourney(entity.id, entity.startedAt, entity.endedAt ?: System.currentTimeMillis())
        watchDao.pruneOutsideJourney(entity.id, entity.startedAt, entity.endedAt ?: System.currentTimeMillis())
        if (current != null && (current.startedAt != entity.startedAt || current.endedAt != entity.endedAt ||
                current.transport != entity.transport || current.status != entity.status)) {
            analysis.purgeJourneys(setOf(entity.id))
        }
    } } } }

    suspend fun savePlace(entity: Place) = db.withTransaction {
        require(entity.name.isNotBlank()) { "Yer adı gerekli." }
        requireValidCoordinates(entity.latitude, entity.longitude)
        requireValidOsmReference(entity.osmType, entity.osmId)
        val current = dao.place(entity.id)
        val position = if (current != null) current.sortOrder else {
            val places = dao.allPlaces()
            val last = places.maxOfOrNull { it.sortOrder } ?: -1L
            if (last < PlaceOrderRules.MAX_ORDER) last + 1 else {
                places.forEachIndexed { index, place -> dao.setPlaceOrder(place.id, index.toLong()) }
                places.size.toLong()
            }
        }
        // A metadata editor may hold an older rank while a separate sort has already been saved.
        dao.save(entity.copy(sortOrder = position))
    }

    suspend fun reorderPlaces(originalOrder: List<String>, newOrder: List<String>) = db.withTransaction {
        val reordered = PlaceOrderRules.reorder(dao.allPlaces(), originalOrder, newOrder)
        reordered.forEach { dao.setPlaceOrder(it.id, it.sortOrder) }
    }
    suspend fun saveVisit(entity: Visit) = db.withTransaction {
        require(entity.rating == null || entity.rating in 1..5)
        promote(entity.journeyId)
        dao.save(entity)
    }

    suspend fun savePhoto(entity: Photo) = db.withTransaction {
        require(entity.journeyId != null || entity.visitId != null) { "Fotoğraf bir ziyaret veya yolculuğa bağlanmalı." }
        photoFile(entity.relativePath)
        requireValidCoordinates(entity.latitude, entity.longitude)
        promote(entity.journeyId)
        entity.visitId?.let { promote(dao.visit(it)?.journeyId) }
        dao.save(entity)
    }

    suspend fun saveDraft(entity: ShareDraft) = db.withTransaction {
        require(entity.rating == null || entity.rating in 1..5)
        val validPhotoIds = dao.allPhotos().filter { it.visitId == entity.visitId }.map { it.id }.toSet()
        require(entity.photoIds.all { it in validPhotoIds }) { "Taslağa yalnızca bu ziyaretin fotoğrafları eklenebilir." }
        dao.deleteOtherDrafts(entity.visitId, entity.id)
        dao.save(entity)
    }

    suspend fun getContribution(id: String): ContributionDraft? = dao.contribution(id)

    suspend fun getMapEdit(id: String): MapEditDraft? = mapDao.read(id)

    suspend fun saveMapEdit(draft: MapEditDraft) = db.withTransaction {
        val current = mapDao.read(draft.id)
        require(current == null || current.status in setOf(MapEditStatus.DRAFT, MapEditStatus.FAILED))
        val editable = draft.editableCopy()
        validateMapEdit(editable)
        mapDao.save(editable)
    }

    suspend fun beginMapEditSend(id: String, userId: Long, time: Long): MapEditDraft? = db.withTransaction {
        require(userId > 0 && time > 0)
        val current = mapDao.read(id) ?: return@withTransaction null
        if (current.status !in setOf(MapEditStatus.DRAFT, MapEditStatus.FAILED)) return@withTransaction null
        validateMapEditForPublish(current)
        val previous = current.submittedAt ?: 0L
        require(previous < Long.MAX_VALUE)
        val sending = current.copy(status = MapEditStatus.SENDING, stage = MapEditStage.CREATE_CHANGESET,
            submittedAt = maxOf(time, previous + 1), submittedBy = userId, changesetId = null,
            remoteNodeId = null, remoteNodeVersion = null, changesetClosed = false, error = null)
        validateMapEdit(sending)
        mapDao.save(sending)
        sending
    }

    /** Compare the complete attempt so delayed callbacks cannot mutate a replaced or restored draft. */
    suspend fun compareAndSetMapEdit(expected: MapEditDraft, updated: MapEditDraft): Boolean = db.withTransaction {
        if (mapDao.read(expected.id) != expected) return@withTransaction false
        require(expected.status in setOf(MapEditStatus.SENDING, MapEditStatus.UNKNOWN, MapEditStatus.SENT))
        require(updated.copy(status = expected.status, stage = expected.stage, changesetId = expected.changesetId,
            remoteNodeId = expected.remoteNodeId, remoteNodeVersion = expected.remoteNodeVersion,
            changesetClosed = expected.changesetClosed, error = expected.error) == expected)
        require(updated.status != MapEditStatus.DRAFT)
        require(expected.changesetId == null || updated.changesetId == expected.changesetId)
        require(expected.remoteNodeId == null || updated.remoteNodeId == expected.remoteNodeId)
        validateMapEdit(updated)
        mapDao.save(updated)
        if (expected.remoteNodeId == null && updated.remoteNodeId != null) {
            val place = expected.placeId?.let { id -> dao.allPlaces().firstOrNull { it.id == id } }
            if (place != null) dao.save(place.copy(osmType = OsmType.NODE, osmId = updated.remoteNodeId, source = PlaceSource.OSM))
            else if (expected.placeId == null) savePlace(Place(name = updated.name.ifBlank { updated.preset.label },
                latitude = updated.latitude, longitude = updated.longitude, osmType = OsmType.NODE,
                osmId = updated.remoteNodeId, source = PlaceSource.OSM))
        }
        true
    }

    suspend fun deleteMapEdit(id: String) = db.withTransaction {
        require(mapDao.read(id)?.status !in setOf(MapEditStatus.SENDING, MapEditStatus.UNKNOWN))
        mapDao.delete(id)
    }

    suspend fun recoverInterruptedMapEdits(): Int = mapDao.recoverInterrupted()

    /** Editing never turns an uncertain or sent public request back into a resendable draft. */
    suspend fun saveContribution(draft: ContributionDraft) = db.withTransaction {
        val current = dao.contribution(draft.id)
        require(current == null || current.status in setOf(ContributionStatus.DRAFT, ContributionStatus.FAILED)) { "Gönderilmiş veya sonucu belirsiz katkı düzenlenemez." }
        require(draft.status in setOf(ContributionStatus.DRAFT, ContributionStatus.FAILED)) { "Katkı önce yerel taslak olarak kaydedilmeli." }
        val editable = draft.copy(status = ContributionStatus.DRAFT, remoteNoteId = null, remoteStatus = null, submittedAt = null, submittedBy = null, error = null)
        validateContribution(editable)
        dao.save(editable)
    }

    suspend fun deleteContribution(id: String) = db.withTransaction {
        require(dao.contribution(id)?.status != ContributionStatus.SENDING) { "Gönderim sürerken katkı silinemez." }
        dao.deleteContribution(id)
    }

    /** Invoke once at application startup, before enabling contribution actions. Never retries a POST. */
    suspend fun recoverInterruptedContributions(): Int = dao.recoverInterruptedContributions()

    suspend fun beginContributionSend(id: String, userId: Long, time: Long): ContributionDraft? = db.withTransaction {
        require(userId > 0 && time > 0)
        val current = dao.contribution(id) ?: return@withTransaction null
        if (current.status !in setOf(ContributionStatus.DRAFT, ContributionStatus.FAILED)) return@withTransaction null
        require(current.text.isNotBlank()) { "Göndermek için bir gözlem yazın." }
        // Millisecond identity stays unique even when a retry is requested within the same clock tick.
        val submittedAt = maxOf(time, (current.submittedAt ?: 0).let { if (it == Long.MAX_VALUE) error("Geçersiz gönderim zamanı.") else it + 1 })
        val sending = current.copy(status = ContributionStatus.SENDING, submittedAt = submittedAt, submittedBy = userId,
            remoteNoteId = null, remoteStatus = null, error = null)
        validateContribution(sending)
        dao.save(sending)
        sending
    }

    suspend fun updateContributionResult(id: String, expectedSubmittedAt: Long, status: ContributionStatus,
        remoteNoteId: Long? = null, remoteStatus: String? = null, error: String? = null): Boolean = db.withTransaction {
        require(status in setOf(ContributionStatus.SENT, ContributionStatus.FAILED, ContributionStatus.UNKNOWN))
        val current = dao.contribution(id) ?: return@withTransaction false
        if (current.status != ContributionStatus.SENDING || current.submittedAt != expectedSubmittedAt) return@withTransaction false
        val updated = current.copy(status = status, remoteNoteId = remoteNoteId, remoteStatus = remoteStatus, error = error)
        validateContribution(updated)
        dao.save(updated)
        true
    }

    suspend fun reconcileContributionResult(id: String, expectedSubmittedAt: Long, expectedStatus: ContributionStatus,
        remoteNoteId: Long, remoteStatus: String): Boolean = db.withTransaction {
        require(expectedStatus == ContributionStatus.UNKNOWN)
        val current = dao.contribution(id) ?: return@withTransaction false
        if (current.status != expectedStatus || current.submittedAt != expectedSubmittedAt) return@withTransaction false
        val reconciled = current.copy(status = ContributionStatus.SENT, remoteNoteId = remoteNoteId, remoteStatus = remoteStatus, error = null)
        validateContribution(reconciled)
        dao.save(reconciled)
        true
    }

    suspend fun refreshContributionRemoteStatus(id: String, remoteNoteId: Long, remoteStatus: String): Boolean = db.withTransaction {
        val current = dao.contribution(id) ?: return@withTransaction false
        if (current.status != ContributionStatus.SENT || current.remoteNoteId != remoteNoteId) return@withTransaction false
        val updated = current.copy(remoteStatus = remoteStatus)
        validateContribution(updated)
        dao.save(updated)
        true
    }

    suspend fun deleteJourney(id: String) = deleteWithFiles(purgeJourneys = true) { dao.deleteDirectPhotos(id); dao.deleteJourney(id) }
    suspend fun deleteVisit(id: String) = deleteWithFiles { dao.deleteVisit(id) }
    suspend fun deletePlace(id: String) = deleteWithFiles { dao.deletePlace(id) }
    suspend fun deletePhoto(id: String) = deleteWithFiles {
        dao.deletePhoto(id)
        dao.allDrafts().filter { id in it.photoIds }.forEach { dao.save(it.copy(photoIds = it.photoIds - id)) }
    }

    suspend fun snapshot(): DiarySnapshot = db.withTransaction {
        DiarySnapshot(dao.allJourneys(), dao.allPoints(), dao.allPlaces(), dao.allVisits(), dao.allPhotos(), dao.allDrafts(), dao.allContributions(), dao.allHealthSamples(),
            mapEdits = mapDao.all(), watchHealthSessions = watchDao.allSessions(), watchHealthSamples = watchDao.allSamples(),
            savedPlans = libraryDao.allPlans().map { it.model() }, importedTracks = libraryDao.allTracks().map { it.model() },
            collections = libraryDao.allCollections().map { it.model() }, memberships = libraryDao.allMemberships().map { it.model() },
            destinationShortcuts = libraryDao.allShortcuts().map { DestinationShortcutRef(DestinationShortcutKind.valueOf(it.kind), it.placeId) },
            leanSessions = leanStore.snapshot())
    }

    suspend fun restore(snapshot: DiarySnapshot, leanRaw: Map<String, List<File>> = emptyMap()) {
        DiaryRules.validate(snapshot)
        validateMapSnapshot(snapshot)
        require(snapshot.journeys.none { it.status == JourneyStatus.TEMPORARY }) { "Geçici yolculuklar geri yüklenemez." }
        val restoredPlaces = PlaceOrderRules.normalizeDuplicates(snapshot.places)
        val now = System.currentTimeMillis()
        val restoredJourneys = snapshot.journeys.map { DiaryRules.restored(it, now) }
        val restoredById = restoredJourneys.associateBy { it.id }
        // Restoring an open trip closes it now. Preserve its diary, but never retain health outside that new end.
        val restoredHealth = snapshot.healthSamples.filter { sample ->
            val journey = restoredById.getValue(sample.journeyId)
            val end = requireNotNull(journey.endedAt)
            sample.startAt >= journey.startedAt && sample.startAt < end && sample.endAt <= end
        }
        val restoredWatchSessions = snapshot.watchHealthSessions.map { it.copy(acceptsUploads = false) }
        val restoredWatchSamples = snapshot.watchHealthSamples.filter { sample ->
            val journey = restoredById.getValue(sample.journeyId)
            WatchHealthRules.belongsTo(sample, journey, now)
        }
        DiaryRules.validate(snapshot.copy(journeys = restoredJourneys, healthSamples = restoredHealth,
            watchHealthSessions = restoredWatchSessions, watchHealthSamples = restoredWatchSamples))
        deleteWithFiles(replaceAll = true) {
            libraryDao.clearShortcuts(); libraryDao.clearCollections(); libraryDao.clearTracks(); libraryDao.clearPlans()
            dao.clearHealthSamples(); dao.clearHealthSyncs()
            watchDao.clearSamples(); watchDao.clearSessions(); mapDao.clear()
            dao.clearContributions(); dao.clearDrafts(); dao.clearPhotos(); dao.clearVisits(); dao.clearPoints(); dao.clearJourneys(); dao.clearPlaces()
            restoredJourneys.forEach { dao.save(it) }
            restoredPlaces.forEach { dao.save(it) }
            snapshot.points.forEach { dao.save(it) }
            snapshot.visits.forEach { dao.save(it) }
            snapshot.photos.forEach { dao.save(it) }
            snapshot.drafts.forEach { dao.save(it) }
            snapshot.contributions.forEach { dao.save(if (it.status == ContributionStatus.SENDING) it.copy(status = ContributionStatus.UNKNOWN) else it) }
            dao.saveHealthSamples(restoredHealth)
            watchDao.restoreSessions(restoredWatchSessions)
            watchDao.restoreSamples(restoredWatchSamples)
            snapshot.mapEdits.forEach { mapDao.save(if (it.status == MapEditStatus.SENDING) it.copy(status = MapEditStatus.UNKNOWN) else it) }
            snapshot.savedPlans.forEach { libraryDao.save(SavedRoutePlanEntity.from(TravelLibraryRules.normalizePlan(it))) }
            snapshot.importedTracks.forEach { libraryDao.storeTrack(TravelLibraryRules.normalizeTrack(it)) }
            snapshot.collections.forEach { libraryDao.save(JourneyCollectionEntity.from(it)) }
            libraryDao.saveMemberships(snapshot.memberships.map(CollectionMembershipEntity::from))
            snapshot.destinationShortcuts.forEach { libraryDao.saveShortcut(DestinationShortcutEntity(it.kind.name, it.placeId)) }
            leanStore.restoreSessions(snapshot.leanSessions, leanRaw)
        }
    }

    fun photoFile(relativePath: String): File {
        require(DiaryRules.isSafePhotoPath(relativePath)) { "Geçersiz fotoğraf yolu." }
        val root = File(appContext.filesDir, "photos").canonicalFile
        val file = File(appContext.filesDir, relativePath).canonicalFile
        require(file.path.startsWith(root.path + File.separator)) { "Fotoğraf yolu uygulama alanı dışında." }
        return file
    }

    private suspend fun promote(id: String?) {
        id ?: return
        dao.journey(id)?.let {
            require(!DiaryRules.isExpired(it, System.currentTimeMillis())) { "Geçici kaydın süresi doldu." }
            if (it.status == JourneyStatus.TEMPORARY) dao.save(DiaryRules.confirmed(it, it.transport))
        }
    }

    private suspend fun <T> deleteWithFiles(purgeJourneys: Boolean = false, replaceAll: Boolean = false,
        block: suspend () -> T): T {
        suspend fun execute(analysis: PostJourneyAnalysis?): T {
            val (result, removed) = db.withTransaction {
                val before = dao.allPhotos().map { it.relativePath }.toSet()
                val beforeIds = if (purgeJourneys) dao.allJourneys().map { it.id }.toSet() else emptySet()
                val result = block()
                if (replaceAll) requireNotNull(analysis).purgeAll()
                else if (purgeJourneys) requireNotNull(analysis).purgeJourneys(beforeIds - dao.allJourneys().map { it.id }.toSet())
                result to (before - dao.allPhotos().map { it.relativePath }.toSet())
            }
            // Preserve the existing backup/photo contract: a purge failure happens before DB commit.
            // Orphan photo cleanup still must not undo an otherwise successful transaction.
            removed.forEach { runCatching { photoFile(it).delete() } }
            return result
        }
        return if (purgeJourneys || replaceAll) withRawMutationLease { privacyChange { execute(it) } } else execute(null)
    }

    private fun requireValidCoordinates(latitude: Double?, longitude: Double?) {
        require((latitude == null && longitude == null) ||
            (latitude != null && longitude != null && latitude.isFinite() && longitude.isFinite() &&
                latitude in -90.0..90.0 && longitude in -180.0..180.0)) { "Geçersiz koordinat." }
    }
}
