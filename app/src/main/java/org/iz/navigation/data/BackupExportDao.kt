package org.iz.navigation.data

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Query

internal interface BackupRecord<out T> { val backupKey: Long; val value: T }
internal data class BackupJourneysRow(override val backupKey: Long, @Embedded override val value: Journey) : BackupRecord<Journey>
internal data class BackupPointsRow(override val backupKey: Long, @Embedded override val value: TrackPoint) : BackupRecord<TrackPoint>
internal data class BackupPlacesRow(override val backupKey: Long, @Embedded override val value: Place) : BackupRecord<Place>
internal data class BackupVisitsRow(override val backupKey: Long, @Embedded override val value: Visit) : BackupRecord<Visit>
internal data class BackupPhotosRow(override val backupKey: Long, @Embedded override val value: Photo) : BackupRecord<Photo>
internal data class BackupDraftsRow(override val backupKey: Long, @Embedded override val value: ShareDraft) : BackupRecord<ShareDraft>
internal data class BackupContributionsRow(override val backupKey: Long, @Embedded override val value: ContributionDraft) : BackupRecord<ContributionDraft>
internal data class BackupHealthSamplesRow(override val backupKey: Long, @Embedded override val value: JourneyHealthSample) : BackupRecord<JourneyHealthSample>
internal data class BackupMapEditsRow(override val backupKey: Long, @Embedded override val value: MapEditDraft) : BackupRecord<MapEditDraft>
internal data class BackupWatchHealthSessionsRow(override val backupKey: Long, @Embedded override val value: WatchHealthSession) : BackupRecord<WatchHealthSession>
internal data class BackupWatchHealthSamplesRow(override val backupKey: Long, @Embedded override val value: WatchHealthSample) : BackupRecord<WatchHealthSample>
internal data class BackupPlansRow(override val backupKey: Long, @Embedded override val value: SavedRoutePlanEntity) : BackupRecord<SavedRoutePlanEntity>
internal data class BackupTracksRow(override val backupKey: Long, @Embedded override val value: ImportedTrackEntity) : BackupRecord<ImportedTrackEntity>
internal data class BackupCollectionsRow(override val backupKey: Long, @Embedded override val value: JourneyCollectionEntity) : BackupRecord<JourneyCollectionEntity>
internal data class BackupMembershipsRow(override val backupKey: Long, @Embedded override val value: CollectionMembershipEntity) : BackupRecord<CollectionMembershipEntity>
internal data class BackupShortcutsRow(override val backupKey: Long, @Embedded override val value: DestinationShortcutEntity) : BackupRecord<DestinationShortcutEntity>
internal data class BackupLeanSessionsRow(override val backupKey: Long, @Embedded override val value: LeanSessionEntity) : BackupRecord<LeanSessionEntity>
internal data class BackupSegmentRow(override val backupKey: Long, @Embedded override val value: ImportedSegmentEntity) : BackupRecord<ImportedSegmentEntity>
internal data class BackupCoordinateRow(override val backupKey: Long, @Embedded override val value: ImportedCoordinateEntity) : BackupRecord<ImportedCoordinateEntity>
internal data class BackupJourneyReference(val id: String, val transport: Transport, val status: JourneyStatus,
    val startedAt: Long, val endedAt: Long?, val expiresAt: Long?) {
    fun model() = Journey(id = id, transport = transport, status = status, startedAt = startedAt, endedAt = endedAt, expiresAt = expiresAt)
}
internal data class BackupPhotoReference(val id: String, val visitId: String?)

/** Export-only stable key ranges; caller owns one Room transaction for all pages and references. */
@Dao
internal interface BackupExportDao {
    @Query("SELECT t.rowid AS backupKey, t.* FROM journeys t WHERE t.rowid >= :first AND t.status = 'CONFIRMED' ORDER BY t.rowid LIMIT :limit")
    suspend fun journeys(first: Long, limit: Int): List<BackupJourneysRow>
    @Query("SELECT t.rowid AS backupKey, t.* FROM track_points t WHERE t.rowid >= :first AND EXISTS (SELECT 1 FROM journeys j WHERE j.id=t.journeyId AND j.status='CONFIRMED') ORDER BY t.rowid LIMIT :limit")
    suspend fun points(first: Long, limit: Int): List<BackupPointsRow>
    @Query("SELECT t.rowid AS backupKey, t.* FROM places t WHERE t.rowid >= :first AND 1 ORDER BY t.rowid LIMIT :limit")
    suspend fun places(first: Long, limit: Int): List<BackupPlacesRow>
    @Query("SELECT t.rowid AS backupKey, t.* FROM visits t WHERE t.rowid >= :first AND 1 ORDER BY t.rowid LIMIT :limit")
    suspend fun visits(first: Long, limit: Int): List<BackupVisitsRow>
    @Query("SELECT t.rowid AS backupKey, t.* FROM photos t WHERE t.rowid >= :first AND (t.visitId IS NOT NULL OR EXISTS (SELECT 1 FROM journeys j WHERE j.id=t.journeyId AND j.status='CONFIRMED')) ORDER BY t.rowid LIMIT :limit")
    suspend fun photos(first: Long, limit: Int): List<BackupPhotosRow>
    @Query("SELECT t.rowid AS backupKey, t.* FROM share_drafts t WHERE t.rowid >= :first AND 1 ORDER BY t.rowid LIMIT :limit")
    suspend fun drafts(first: Long, limit: Int): List<BackupDraftsRow>
    @Query("SELECT t.rowid AS backupKey, t.* FROM contribution_drafts t WHERE t.rowid >= :first AND 1 ORDER BY t.rowid LIMIT :limit")
    suspend fun contributions(first: Long, limit: Int): List<BackupContributionsRow>
    @Query("SELECT t.rowid AS backupKey, t.* FROM journey_health_samples t WHERE t.rowid >= :first AND EXISTS (SELECT 1 FROM journeys j WHERE j.id=t.journeyId AND j.status='CONFIRMED') ORDER BY t.rowid LIMIT :limit")
    suspend fun healthSamples(first: Long, limit: Int): List<BackupHealthSamplesRow>
    @Query("SELECT t.rowid AS backupKey, t.* FROM map_edit_drafts t WHERE t.rowid >= :first AND 1 ORDER BY t.rowid LIMIT :limit")
    suspend fun mapEdits(first: Long, limit: Int): List<BackupMapEditsRow>
    @Query("SELECT t.rowid AS backupKey, t.* FROM watch_health_sessions t WHERE t.rowid >= :first AND EXISTS (SELECT 1 FROM journeys j WHERE j.id=t.journeyId AND j.status='CONFIRMED') ORDER BY t.rowid LIMIT :limit")
    suspend fun watchHealthSessions(first: Long, limit: Int): List<BackupWatchHealthSessionsRow>
    @Query("SELECT t.rowid AS backupKey, t.* FROM watch_health_samples t WHERE t.rowid >= :first AND EXISTS (SELECT 1 FROM journeys j WHERE j.id=t.journeyId AND j.status='CONFIRMED') ORDER BY t.rowid LIMIT :limit")
    suspend fun watchHealthSamples(first: Long, limit: Int): List<BackupWatchHealthSamplesRow>
    @Query("SELECT t.rowid AS backupKey, t.* FROM saved_route_plans t WHERE t.rowid >= :first AND 1 ORDER BY t.rowid LIMIT :limit")
    suspend fun plans(first: Long, limit: Int): List<BackupPlansRow>
    @Query("SELECT t.rowid AS backupKey, t.* FROM imported_tracks t WHERE t.rowid >= :first AND 1 ORDER BY t.rowid LIMIT :limit")
    suspend fun tracks(first: Long, limit: Int): List<BackupTracksRow>
    @Query("SELECT t.rowid AS backupKey, t.* FROM journey_collections t WHERE t.rowid >= :first AND 1 ORDER BY t.rowid LIMIT :limit")
    suspend fun collections(first: Long, limit: Int): List<BackupCollectionsRow>
    @Query("SELECT t.rowid AS backupKey, t.* FROM collection_memberships t WHERE t.rowid >= :first AND EXISTS (SELECT 1 FROM journeys j WHERE j.id=t.journeyId AND j.status='CONFIRMED') ORDER BY t.rowid LIMIT :limit")
    suspend fun memberships(first: Long, limit: Int): List<BackupMembershipsRow>
    @Query("SELECT t.rowid AS backupKey, t.* FROM destination_shortcuts t WHERE t.rowid >= :first AND 1 ORDER BY t.rowid LIMIT :limit")
    suspend fun shortcuts(first: Long, limit: Int): List<BackupShortcutsRow>
    @Query("SELECT t.rowid AS backupKey, t.* FROM lean_sessions t WHERE t.rowid >= :first AND EXISTS (SELECT 1 FROM journeys j WHERE j.id=t.journeyId AND j.status='CONFIRMED') ORDER BY t.rowid LIMIT :limit")
    suspend fun leanSessions(first: Long, limit: Int): List<BackupLeanSessionsRow>

    @Query("SELECT sortOrder AS backupKey, * FROM imported_track_segments WHERE trackId=:id AND sortOrder >= :first ORDER BY sortOrder LIMIT :limit")
    suspend fun segments(id: String, first: Long, limit: Int): List<BackupSegmentRow>
    @Query("SELECT sortOrder AS backupKey, * FROM imported_track_points WHERE segmentId=:id AND sortOrder >= :first ORDER BY sortOrder LIMIT :limit")
    suspend fun coordinates(id: String, first: Long, limit: Int): List<BackupCoordinateRow>
    @Query("SELECT id,transport,status,startedAt,endedAt,expiresAt FROM journeys WHERE id=:id")
    suspend fun journey(id: String): BackupJourneyReference?
    @Query("SELECT * FROM visits WHERE id=:id") suspend fun visit(id: String): Visit?
    @Query("SELECT * FROM places WHERE id=:id") suspend fun place(id: String): Place?
    @Query("SELECT * FROM watch_health_sessions WHERE id=:id") suspend fun watchSession(id: String): WatchHealthSession?
    @Query("SELECT * FROM journey_collections WHERE id=:id") suspend fun collection(id: String): JourneyCollectionEntity?
    @Query("SELECT p.id,p.visitId FROM photos p WHERE p.id IN (:ids) AND (p.visitId IS NOT NULL OR EXISTS (SELECT 1 FROM journeys j WHERE j.id=p.journeyId AND j.status='CONFIRMED'))")
    suspend fun draftPhotos(ids: List<String>): List<BackupPhotoReference>
    @Query("SELECT * FROM lean_sessions WHERE id=:id") suspend fun leanSessionById(id: String): LeanSessionEntity?
    @Query("SELECT sessionId,partIndex,length(payload) AS byteCount,sha256,recordCount,firstElapsedNanos,lastElapsedNanos FROM lean_chunks WHERE sessionId=:id ORDER BY partIndex LIMIT :limit")
    suspend fun leanChunks(id: String, limit: Int): List<LeanChunkInfo>
}
