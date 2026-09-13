package org.iz.navigation.data

import android.content.Context
import androidx.room.withTransaction
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.iz.navigation.gpx.ImportedTrack

class TravelLibraryRepository internal constructor(private val db: DiaryDatabase) {
    constructor(context: Context) : this(DiaryDatabase.get(context.applicationContext))
    private val dao = db.travelLibraryDao()
    val savedPlans: Flow<List<SavedRoutePlan>> = dao.plans().map { rows -> rows.map { it.model() } }.flowOn(Dispatchers.Default)
    val importedTracks: Flow<List<ImportedTrack>> = dao.tracks().map { rows -> rows.map { it.model() } }.flowOn(Dispatchers.Default)
    val collections: Flow<List<JourneyCollection>> = dao.collections().map { rows -> rows.map { it.model() } }
    val memberships: Flow<List<CollectionMembership>> = dao.memberships().map { rows -> rows.map { it.model() } }

    suspend fun savePlan(value: SavedRoutePlan) = withContext(Dispatchers.Default) {
        val plan = TravelLibraryRules.normalizePlan(value)
        db.withTransaction {
            require(dao.planCount(plan.id) < TravelLibraryRules.MAX_ITEMS) { "Rota kayıt sınırına ulaşıldı." }
            dao.save(SavedRoutePlanEntity.from(plan))
        }
    }
    suspend fun deletePlan(id: String) { TravelLibraryRules.validId(id); dao.deletePlan(id) }

    suspend fun saveTrack(value: ImportedTrack) = withContext(Dispatchers.Default) {
        val track = TravelLibraryRules.normalizeTrack(value)
        db.withTransaction {
            require(dao.trackCount(track.id) < TravelLibraryRules.MAX_ITEMS) { "GPX kayıt sınırına ulaşıldı." }
            require(dao.pointCount(track.id) + track.segments.sumOf { it.points.size.toLong() } <= TravelLibraryRules.MAX_TOTAL_POINTS) { "Kütüphanede çok fazla GPX noktası var." }
            dao.storeTrack(track)
        }
    }
    suspend fun deleteTrack(id: String) { TravelLibraryRules.validId(id); dao.deleteTrack(id) }

    suspend fun saveCollection(value: JourneyCollection) {
        TravelLibraryRules.validateCollection(value)
        db.withTransaction {
            require(dao.collectionCount(value.id) < TravelLibraryRules.MAX_ITEMS) { "Gezi kayıt sınırına ulaşıldı." }
            dao.save(JourneyCollectionEntity.from(value))
        }
    }
    suspend fun deleteCollection(id: String) { TravelLibraryRules.validId(id); dao.deleteCollection(id) }

    suspend fun setCollectionJourneys(collectionId: String, orderedIds: List<String>) {
        TravelLibraryRules.validId(collectionId)
        val ids = orderedIds.toList()
        require(ids.size <= TravelLibraryRules.MAX_MEMBERSHIPS && ids.distinct().size == ids.size) { "Yolculuklar yinelenmeden seçilmeli." }
        ids.forEach(TravelLibraryRules::validId)
        db.withTransaction {
            require(dao.hasCollection(collectionId)) { "Gezi artık mevcut değil." }
            require(dao.membershipCount(collectionId) + ids.size <= TravelLibraryRules.MAX_MEMBERSHIPS) { "Gezilerde çok fazla yolculuk var." }
            ids.forEach { id -> require(db.diaryDao().journey(id)?.let(TravelLibraryRules::isEligible) == true) { "Yalnız tamamlanmış, kalıcı yolculuklar seçilebilir." } }
            dao.deleteMemberships(collectionId)
            dao.saveMemberships(ids.mapIndexed { index, id -> CollectionMembershipEntity(collectionId, id, index.toLong()) })
            dao.touchCollection(collectionId, System.currentTimeMillis())
        }
    }
}

/** Caller holds the shared diary transaction. No original GPX payload is persisted. */
internal suspend fun TravelLibraryDao.storeTrack(track: ImportedTrack) {
    save(ImportedTrackEntity(track.id, track.name, track.createdAt))
    deleteSegments(track.id)
    track.segments.forEachIndexed { index, segment ->
        val segmentId = UUID.randomUUID().toString()
        saveSegments(listOf(ImportedSegmentEntity(segmentId, track.id, index, segment.name, segment.trackName, segment.trackIndex)))
        // Keep transient allocation bounded as well as individual SQLite rows.
        segment.points.chunked(1_000).forEachIndexed { chunkIndex, chunk ->
            savePoints(chunk.mapIndexed { pointIndex, point -> ImportedCoordinateEntity(segmentId, chunkIndex * 1_000 + pointIndex, point.latitude, point.longitude) })
        }
    }
}
