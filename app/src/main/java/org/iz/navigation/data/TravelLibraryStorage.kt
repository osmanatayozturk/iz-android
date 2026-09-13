package org.iz.navigation.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import org.iz.navigation.gpx.ImportedTrack
import org.iz.navigation.gpx.TrackSegment
import org.iz.navigation.weather.RoutePreferences
import org.iz.navigation.weather.WeatherCoordinate
import org.json.JSONArray

@Entity(tableName = "saved_route_plans")
internal data class SavedRoutePlanEntity(@PrimaryKey val id: String, val name: String, val stopsJson: String,
    val transport: Transport, val avoidHighways: Boolean, val avoidTolls: Boolean, val avoidFerries: Boolean,
    val travelSpeedKmh: Double?, val originUsesCurrentLocation: Boolean, val createdAt: Long, val updatedAt: Long) {
    fun model() = SavedRoutePlan(id, name, TravelLibraryJson.readStops(JSONArray(stopsJson)), transport,
        RoutePreferences(avoidHighways, avoidTolls, avoidFerries), travelSpeedKmh, originUsesCurrentLocation, createdAt, updatedAt)
    companion object {
        fun from(value: SavedRoutePlan) = SavedRoutePlanEntity(value.id, value.name, TravelLibraryJson.stops(value.stops).toString(), value.transport,
            value.preferences.avoidHighways, value.preferences.avoidTolls, value.preferences.avoidFerries,
            value.travelSpeedKmh, value.originUsesCurrentLocation, value.createdAt, value.updatedAt)
    }
}

@Entity(tableName = "imported_tracks")
internal data class ImportedTrackEntity(@PrimaryKey val id: String, val name: String, val createdAt: Long)

@Entity(tableName = "imported_track_segments", foreignKeys = [ForeignKey(entity = ImportedTrackEntity::class,
    parentColumns = ["id"], childColumns = ["trackId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["trackId", "sortOrder"], unique = true)])
internal data class ImportedSegmentEntity(@PrimaryKey val id: String, val trackId: String, val sortOrder: Int,
    val name: String, val trackName: String, val trackIndex: Int)

// Keep even a 100000-point segment out of a single SQLite CursorWindow row.
@Entity(tableName = "imported_track_points", primaryKeys = ["segmentId", "sortOrder"],
    foreignKeys = [ForeignKey(entity = ImportedSegmentEntity::class, parentColumns = ["id"], childColumns = ["segmentId"], onDelete = ForeignKey.CASCADE)])
internal data class ImportedCoordinateEntity(val segmentId: String, val sortOrder: Int, val latitude: Double, val longitude: Double)

@Entity(tableName = "journey_collections")
internal data class JourneyCollectionEntity(@PrimaryKey val id: String, val name: String, val createdAt: Long, val updatedAt: Long) {
    fun model() = JourneyCollection(id, name, createdAt, updatedAt)
    companion object { fun from(value: JourneyCollection) = JourneyCollectionEntity(value.id, value.name, value.createdAt, value.updatedAt) }
}

@Entity(tableName = "collection_memberships", primaryKeys = ["collectionId", "journeyId"], foreignKeys = [
    ForeignKey(entity = JourneyCollectionEntity::class, parentColumns = ["id"], childColumns = ["collectionId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = Journey::class, parentColumns = ["id"], childColumns = ["journeyId"], onDelete = ForeignKey.CASCADE),
], indices = [Index("journeyId"), Index(value = ["collectionId", "sortOrder"], unique = true)])
internal data class CollectionMembershipEntity(val collectionId: String, val journeyId: String, val sortOrder: Long) {
    fun model() = CollectionMembership(collectionId, journeyId, sortOrder)
    companion object { fun from(value: CollectionMembership) = CollectionMembershipEntity(value.collectionId, value.journeyId, value.sortOrder) }
}

internal data class StoredTrackSegment(@Embedded val segment: ImportedSegmentEntity,
    @Relation(parentColumn = "id", entityColumn = "segmentId") val points: List<ImportedCoordinateEntity>) {
    fun model() = TrackSegment(segment.name, points.sortedBy { it.sortOrder }.map { WeatherCoordinate(it.latitude, it.longitude) }, segment.trackName, segment.trackIndex)
}

internal data class StoredTrack(@Embedded val track: ImportedTrackEntity,
    @Relation(entity = ImportedSegmentEntity::class, parentColumn = "id", entityColumn = "trackId") val segments: List<StoredTrackSegment>) {
    fun model() = ImportedTrack(track.id, track.name, segments.sortedBy { it.segment.sortOrder }.map { it.model() }, track.createdAt)
}

@Dao
internal interface TravelLibraryDao {
    @Query("SELECT * FROM saved_route_plans ORDER BY updatedAt DESC, id") fun plans(): Flow<List<SavedRoutePlanEntity>>
    @Query("SELECT * FROM saved_route_plans ORDER BY updatedAt DESC, id") suspend fun allPlans(): List<SavedRoutePlanEntity>
    @Transaction @Query("SELECT * FROM imported_tracks ORDER BY createdAt DESC, id") fun tracks(): Flow<List<StoredTrack>>
    @Transaction @Query("SELECT * FROM imported_tracks ORDER BY createdAt DESC, id") suspend fun allTracks(): List<StoredTrack>
    @Query("SELECT * FROM journey_collections ORDER BY updatedAt DESC, id") fun collections(): Flow<List<JourneyCollectionEntity>>
    @Query("SELECT * FROM journey_collections ORDER BY updatedAt DESC, id") suspend fun allCollections(): List<JourneyCollectionEntity>
    @Query("SELECT * FROM collection_memberships ORDER BY collectionId, sortOrder") fun memberships(): Flow<List<CollectionMembershipEntity>>
    @Query("SELECT * FROM collection_memberships ORDER BY collectionId, sortOrder") suspend fun allMemberships(): List<CollectionMembershipEntity>
    @Query("SELECT COUNT(*) FROM saved_route_plans WHERE id != :exceptId") suspend fun planCount(exceptId: String): Int
    @Query("SELECT COUNT(*) FROM imported_tracks WHERE id != :exceptId") suspend fun trackCount(exceptId: String): Int
    @Query("SELECT COUNT(*) FROM journey_collections WHERE id != :exceptId") suspend fun collectionCount(exceptId: String): Int
    @Query("SELECT COUNT(*) FROM imported_track_points p INNER JOIN imported_track_segments s ON s.id = p.segmentId WHERE s.trackId != :exceptId") suspend fun pointCount(exceptId: String): Long
    @Query("SELECT COUNT(*) FROM collection_memberships WHERE collectionId != :exceptId") suspend fun membershipCount(exceptId: String): Long
    @Query("SELECT EXISTS(SELECT 1 FROM journey_collections WHERE id = :id)") suspend fun hasCollection(id: String): Boolean
    @Upsert suspend fun save(value: SavedRoutePlanEntity)
    @Upsert suspend fun save(value: ImportedTrackEntity)
    @Insert suspend fun saveSegments(values: List<ImportedSegmentEntity>)
    @Insert suspend fun savePoints(values: List<ImportedCoordinateEntity>)
    @Upsert suspend fun save(value: JourneyCollectionEntity)
    @Insert suspend fun saveMemberships(values: List<CollectionMembershipEntity>)
    @Query("DELETE FROM saved_route_plans WHERE id = :id") suspend fun deletePlan(id: String)
    @Query("DELETE FROM imported_tracks WHERE id = :id") suspend fun deleteTrack(id: String)
    @Query("DELETE FROM imported_track_segments WHERE trackId = :id") suspend fun deleteSegments(id: String)
    @Query("DELETE FROM journey_collections WHERE id = :id") suspend fun deleteCollection(id: String)
    @Query("DELETE FROM collection_memberships WHERE collectionId = :id") suspend fun deleteMemberships(id: String)
    @Query("UPDATE journey_collections SET updatedAt = MAX(createdAt, :time) WHERE id = :id") suspend fun touchCollection(id: String, time: Long)
    @Query("DELETE FROM collection_memberships WHERE journeyId IN (SELECT id FROM journeys WHERE endedAt IS NULL OR endedAt < startedAt OR expiresAt IS NOT NULL OR status != 'CONFIRMED')") suspend fun pruneIneligibleMemberships()
    @Query("DELETE FROM saved_route_plans") suspend fun clearPlans()
    @Query("DELETE FROM imported_tracks") suspend fun clearTracks()
    @Query("DELETE FROM journey_collections") suspend fun clearCollections()
}

internal val TRAVEL_LIBRARY_SCHEMA_SQL = listOf(
    "CREATE TABLE IF NOT EXISTS saved_route_plans (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, stopsJson TEXT NOT NULL, transport TEXT NOT NULL, avoidHighways INTEGER NOT NULL, avoidTolls INTEGER NOT NULL, avoidFerries INTEGER NOT NULL, travelSpeedKmh REAL, originUsesCurrentLocation INTEGER NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)",
    "CREATE TABLE IF NOT EXISTS imported_tracks (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, createdAt INTEGER NOT NULL)",
    "CREATE TABLE IF NOT EXISTS imported_track_segments (id TEXT NOT NULL PRIMARY KEY, trackId TEXT NOT NULL, sortOrder INTEGER NOT NULL, name TEXT NOT NULL, trackName TEXT NOT NULL, trackIndex INTEGER NOT NULL, FOREIGN KEY(trackId) REFERENCES imported_tracks(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
    "CREATE UNIQUE INDEX IF NOT EXISTS index_imported_track_segments_trackId_sortOrder ON imported_track_segments (trackId, sortOrder)",
    "CREATE TABLE IF NOT EXISTS imported_track_points (segmentId TEXT NOT NULL, sortOrder INTEGER NOT NULL, latitude REAL NOT NULL, longitude REAL NOT NULL, PRIMARY KEY(segmentId, sortOrder), FOREIGN KEY(segmentId) REFERENCES imported_track_segments(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
    "CREATE TABLE IF NOT EXISTS journey_collections (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)",
    "CREATE TABLE IF NOT EXISTS collection_memberships (collectionId TEXT NOT NULL, journeyId TEXT NOT NULL, sortOrder INTEGER NOT NULL, PRIMARY KEY(collectionId, journeyId), FOREIGN KEY(collectionId) REFERENCES journey_collections(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(journeyId) REFERENCES journeys(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
    "CREATE INDEX IF NOT EXISTS index_collection_memberships_journeyId ON collection_memberships (journeyId)",
    "CREATE UNIQUE INDEX IF NOT EXISTS index_collection_memberships_collectionId_sortOrder ON collection_memberships (collectionId, sortOrder)",
)
