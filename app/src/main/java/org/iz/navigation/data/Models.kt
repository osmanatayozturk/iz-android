package org.iz.navigation.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo
import java.util.UUID

enum class Transport {
    CAR, MOTORCYCLE, BICYCLE, WALK, UNKNOWN, PASSENGER, RUN;

    val supportsSteps: Boolean get() = this == WALK || this == RUN
}
enum class JourneyStatus { TEMPORARY, CONFIRMED }

@Entity(tableName = "journeys")
data class Journey(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val title: String = "",
    val transport: Transport = Transport.UNKNOWN,
    val status: JourneyStatus = JourneyStatus.CONFIRMED,
    val startedAt: Long = System.currentTimeMillis(),
    val endedAt: Long? = null,
    val expiresAt: Long? = null,
    val note: String = "",
    val interrupted: Boolean = false,
    /** Phone sensor steps during walking/running; null means no measurement is available. */
    val stepCount: Long? = null,
)

@Entity(tableName = "track_points", foreignKeys = [ForeignKey(
    entity = Journey::class, parentColumns = ["id"], childColumns = ["journeyId"],
    onDelete = ForeignKey.CASCADE,
)], indices = [Index("journeyId")])
data class TrackPoint(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val journeyId: String,
    val latitude: Double,
    val longitude: Double,
    val recordedAt: Long = System.currentTimeMillis(),
    val accuracy: Float,
    val speed: Float? = null,
    val altitude: Double? = null,
    val breakBefore: Boolean = false,
)

@Entity(tableName = "places")
data class Place(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val googlePlaceId: String? = null,
    val osmType: OsmType? = null,
    val osmId: Long? = null,
    @ColumnInfo(defaultValue = "'LEGACY'") val source: PlaceSource = PlaceSource.LEGACY,
    @ColumnInfo(defaultValue = "0") val sortOrder: Long = 0,
)

@Entity(tableName = "visits", foreignKeys = [
    ForeignKey(entity = Place::class, parentColumns = ["id"], childColumns = ["placeId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = Journey::class, parentColumns = ["id"], childColumns = ["journeyId"], onDelete = ForeignKey.SET_NULL),
], indices = [Index("placeId"), Index("journeyId")])
data class Visit(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val placeId: String,
    val journeyId: String? = null,
    val visitedAt: Long = System.currentTimeMillis(),
    val note: String = "",
    val rating: Int? = null,
)

@Entity(tableName = "photos", foreignKeys = [
    ForeignKey(entity = Journey::class, parentColumns = ["id"], childColumns = ["journeyId"], onDelete = ForeignKey.SET_NULL),
    ForeignKey(entity = Visit::class, parentColumns = ["id"], childColumns = ["visitId"], onDelete = ForeignKey.CASCADE),
], indices = [Index("journeyId"), Index("visitId")])
data class Photo(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val relativePath: String,
    val journeyId: String? = null,
    val visitId: String? = null,
    val takenAt: Long? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val caption: String = "",
)

@Entity(tableName = "share_drafts", foreignKeys = [ForeignKey(
    entity = Visit::class, parentColumns = ["id"], childColumns = ["visitId"], onDelete = ForeignKey.CASCADE,
)], indices = [Index(value = ["visitId"], unique = true)])
data class ShareDraft(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val visitId: String,
    val text: String = "",
    val rating: Int? = null,
    val photoIds: List<String> = emptyList(),
    val markedSharedAt: Long? = null,
)

data class DiarySnapshot(
    val journeys: List<Journey> = emptyList(),
    val points: List<TrackPoint> = emptyList(),
    val places: List<Place> = emptyList(),
    val visits: List<Visit> = emptyList(),
    val photos: List<Photo> = emptyList(),
    val drafts: List<ShareDraft> = emptyList(),
    val contributions: List<ContributionDraft> = emptyList(),
    val healthSamples: List<JourneyHealthSample> = emptyList(),
    val mapEdits: List<MapEditDraft> = emptyList(),
    val watchHealthSessions: List<WatchHealthSession> = emptyList(),
    val watchHealthSamples: List<WatchHealthSample> = emptyList(),
    val savedPlans: List<SavedRoutePlan> = emptyList(),
    val importedTracks: List<org.iz.navigation.gpx.ImportedTrack> = emptyList(),
    val collections: List<JourneyCollection> = emptyList(),
    val memberships: List<CollectionMembership> = emptyList(),
)
