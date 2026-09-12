package org.iz.navigation.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

class DiaryConverters {
    @TypeConverter fun mapPreset(value: String): MapPlacePreset = MapPlacePreset.valueOf(value)
    @TypeConverter fun mapPreset(value: MapPlacePreset): String = value.name
    @TypeConverter fun mapEditStatus(value: String): MapEditStatus = MapEditStatus.valueOf(value)
    @TypeConverter fun mapEditStatus(value: MapEditStatus): String = value.name
    @TypeConverter fun mapEditStage(value: String): MapEditStage = MapEditStage.valueOf(value)
    @TypeConverter fun mapEditStage(value: MapEditStage): String = value.name
    @TypeConverter fun transport(value: String): Transport = Transport.valueOf(value)
    @TypeConverter fun transport(value: Transport): String = value.name
    @TypeConverter fun status(value: String): JourneyStatus = JourneyStatus.valueOf(value)
    @TypeConverter fun status(value: JourneyStatus): String = value.name
    @TypeConverter fun osmType(value: String?): OsmType? = value?.let(OsmType::valueOf)
    @TypeConverter fun osmType(value: OsmType?): String? = value?.name
    @TypeConverter fun placeSource(value: String): PlaceSource = PlaceSource.valueOf(value)
    @TypeConverter fun placeSource(value: PlaceSource): String = value.name
    @TypeConverter fun contributionKind(value: String): ContributionKind = ContributionKind.valueOf(value)
    @TypeConverter fun contributionKind(value: ContributionKind): String = value.name
    @TypeConverter fun contributionStatus(value: String): ContributionStatus = ContributionStatus.valueOf(value)
    @TypeConverter fun contributionStatus(value: ContributionStatus): String = value.name
    @TypeConverter fun healthMetric(value: String): HealthMetric = HealthMetric.valueOf(value)
    @TypeConverter fun healthMetric(value: HealthMetric): String = value.name
    // UUID IDs never contain newlines; unlike comma joins this keeps empty lists unambiguous.
    @TypeConverter fun ids(value: String): List<String> = value.lines().filter { it.isNotBlank() }
    @TypeConverter fun ids(value: List<String>): String = value.joinToString("\n")
}

@Dao
internal interface DiaryDao {
    @Query("SELECT * FROM journeys ORDER BY startedAt DESC") fun journeys(): Flow<List<Journey>>
    @Query("SELECT * FROM track_points ORDER BY recordedAt") fun points(): Flow<List<TrackPoint>>
    @Query("SELECT * FROM track_points WHERE journeyId = :id ORDER BY recordedAt") fun observeJourneyPoints(id: String): Flow<List<TrackPoint>>
    @Query("SELECT * FROM places ORDER BY sortOrder, name COLLATE NOCASE, id") fun places(): Flow<List<Place>>
    @Query("SELECT * FROM visits ORDER BY visitedAt DESC") fun visits(): Flow<List<Visit>>
    @Query("SELECT * FROM photos ORDER BY takenAt DESC") fun photos(): Flow<List<Photo>>
    @Query("SELECT * FROM share_drafts") fun drafts(): Flow<List<ShareDraft>>
    @Query("SELECT * FROM contribution_drafts ORDER BY observedAt DESC") fun contributions(): Flow<List<ContributionDraft>>
    @Query("SELECT * FROM journey_health_samples ORDER BY startAt, sourceId") fun healthSamples(): Flow<List<JourneyHealthSample>>
    @Query("SELECT * FROM journey_health_sync") fun healthSyncs(): Flow<List<JourneyHealthSync>>
    @Query("SELECT * FROM journey_health_samples ORDER BY startAt, sourceId") suspend fun allHealthSamples(): List<JourneyHealthSample>
    @Query("SELECT * FROM journey_health_samples WHERE sourceId IN (:ids)") suspend fun healthSamplesBySources(ids: List<String>): List<JourneyHealthSample>
    @Query("SELECT * FROM journeys ORDER BY startedAt DESC") suspend fun allJourneys(): List<Journey>
    @Query("SELECT * FROM track_points ORDER BY recordedAt") suspend fun allPoints(): List<TrackPoint>
    @Query("SELECT * FROM track_points WHERE journeyId = :id ORDER BY recordedAt") suspend fun journeyPoints(id: String): List<TrackPoint>
    @Query("SELECT * FROM places ORDER BY sortOrder, name COLLATE NOCASE, id") suspend fun allPlaces(): List<Place>
    @Query("SELECT * FROM places WHERE id = :id") suspend fun place(id: String): Place?
    @Query("UPDATE places SET sortOrder = :position WHERE id = :id") suspend fun setPlaceOrder(id: String, position: Long)
    @Query("SELECT * FROM visits") suspend fun allVisits(): List<Visit>
    @Query("SELECT * FROM photos") suspend fun allPhotos(): List<Photo>
    @Query("SELECT * FROM share_drafts") suspend fun allDrafts(): List<ShareDraft>
    @Query("SELECT * FROM contribution_drafts ORDER BY observedAt DESC") suspend fun allContributions(): List<ContributionDraft>
    @Query("SELECT * FROM contribution_drafts WHERE id = :id") suspend fun contribution(id: String): ContributionDraft?
    @Query("SELECT * FROM journeys WHERE id = :id") suspend fun journey(id: String): Journey?
    @Query("SELECT * FROM visits WHERE id = :id") suspend fun visit(id: String): Visit?
    @Query("SELECT * FROM journeys WHERE endedAt IS NULL AND (status = 'CONFIRMED' OR COALESCE(expiresAt, startedAt + 86400000) > :now) ORDER BY startedAt DESC LIMIT 1")
    suspend fun activeJourney(now: Long): Journey?
    @Query("SELECT MAX(recordedAt) FROM track_points WHERE journeyId = :id") suspend fun lastPointTime(id: String): Long?
    @Upsert suspend fun save(value: Journey)
    @Upsert suspend fun save(value: TrackPoint)
    @Upsert suspend fun save(value: Place)
    @Upsert suspend fun save(value: Visit)
    @Upsert suspend fun save(value: Photo)
    @Upsert suspend fun save(value: ShareDraft)
    @Upsert suspend fun save(value: ContributionDraft)
    @Upsert suspend fun saveHealthSamples(values: List<JourneyHealthSample>)
    @Upsert suspend fun saveHealthSyncs(values: List<JourneyHealthSync>)
    @Query("DELETE FROM journey_health_samples WHERE journeyId = :journeyId AND metric IN (:metrics)")
    suspend fun deleteJourneyHealth(journeyId: String, metrics: List<HealthMetric>)
    @Query("DELETE FROM journey_health_samples WHERE sourceId IN (:ids)") suspend fun deleteHealthSources(ids: List<String>)
    @Query("DELETE FROM journey_health_samples WHERE journeyId = :journeyId AND (startAt < :startedAt OR startAt >= :endedAt OR endAt > :endedAt)")
    suspend fun pruneHealthOutsideJourney(journeyId: String, startedAt: Long, endedAt: Long)
    @Query("DELETE FROM journey_health_samples") suspend fun clearHealthSamples()
    @Query("DELETE FROM journey_health_sync") suspend fun clearHealthSyncs()
    @Query("DELETE FROM contribution_drafts WHERE id = :id") suspend fun deleteContribution(id: String)
    @Query("UPDATE contribution_drafts SET status = 'UNKNOWN' WHERE status = 'SENDING'") suspend fun recoverInterruptedContributions(): Int
    @Query("DELETE FROM journeys WHERE id = :id") suspend fun deleteJourney(id: String)
    @Query("DELETE FROM visits WHERE id = :id") suspend fun deleteVisit(id: String)
    @Query("DELETE FROM places WHERE id = :id") suspend fun deletePlace(id: String)
    @Query("DELETE FROM photos WHERE id = :id") suspend fun deletePhoto(id: String)
    @Query("DELETE FROM photos WHERE journeyId = :id AND visitId IS NULL") suspend fun deleteDirectPhotos(id: String)
    @Query("DELETE FROM share_drafts WHERE visitId = :visitId AND id != :id") suspend fun deleteOtherDrafts(visitId: String, id: String)
    @Query("DELETE FROM share_drafts") suspend fun clearDrafts()
    @Query("DELETE FROM contribution_drafts") suspend fun clearContributions()
    @Query("DELETE FROM photos") suspend fun clearPhotos()
    @Query("DELETE FROM visits") suspend fun clearVisits()
    @Query("DELETE FROM track_points") suspend fun clearPoints()
    @Query("DELETE FROM journeys") suspend fun clearJourneys()
    @Query("DELETE FROM places") suspend fun clearPlaces()
}

@Database(entities = [Journey::class, TrackPoint::class, Place::class, Visit::class, Photo::class, ShareDraft::class, ContributionDraft::class,
    JourneyHealthSample::class, JourneyHealthSync::class, MapEditDraft::class,
    WatchHealthSession::class, WatchHealthSample::class], version = 6, exportSchema = true)
@TypeConverters(DiaryConverters::class)
internal abstract class DiaryDatabase : RoomDatabase() {
    abstract fun diaryDao(): DiaryDao
    abstract fun mapEditDao(): MapEditDao
    abstract fun watchHealthDao(): WatchHealthDao

    companion object {
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE places ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0")
                val ids = mutableListOf<String>()
                db.query("SELECT p.id FROM places p LEFT JOIN visits v ON v.placeId = p.id GROUP BY p.id ORDER BY COALESCE(MAX(v.visitedAt),0) DESC, p.name COLLATE NOCASE, p.id").use { cursor ->
                    while (cursor.moveToNext()) ids += cursor.getString(0)
                }
                ids.forEachIndexed { index, id -> db.execSQL("UPDATE places SET sortOrder = ? WHERE id = ?", arrayOf(index.toLong(), id)) }
            }
        }
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(CREATE_MAP_EDIT_DRAFTS_SQL)
                db.execSQL(CREATE_MAP_EDIT_INDEX_SQL)
                db.execSQL("""CREATE TABLE IF NOT EXISTS watch_health_sessions (
                    id TEXT NOT NULL PRIMARY KEY, journeyId TEXT NOT NULL, watchId TEXT NOT NULL,
                    deviceName TEXT NOT NULL, createdAt INTEGER NOT NULL, acceptsUploads INTEGER NOT NULL DEFAULT 1,
                    FOREIGN KEY(journeyId) REFERENCES journeys(id) ON UPDATE NO ACTION ON DELETE CASCADE
                )""")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_watch_health_sessions_journeyId ON watch_health_sessions (journeyId)")
                db.execSQL("""CREATE TABLE IF NOT EXISTS watch_health_samples (
                    sessionId TEXT NOT NULL, sequence INTEGER NOT NULL, journeyId TEXT NOT NULL,
                    metric TEXT NOT NULL, startAt INTEGER NOT NULL, endAt INTEGER NOT NULL, value REAL NOT NULL,
                    PRIMARY KEY(sessionId, sequence),
                    FOREIGN KEY(journeyId) REFERENCES journeys(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(sessionId) REFERENCES watch_health_sessions(id) ON UPDATE NO ACTION ON DELETE CASCADE
                )""")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_watch_health_samples_journeyId ON watch_health_samples (journeyId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_watch_health_samples_sessionId ON watch_health_samples (sessionId)")
            }
        }
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE journeys ADD COLUMN stepCount INTEGER")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE places ADD COLUMN osmType TEXT")
                db.execSQL("ALTER TABLE places ADD COLUMN osmId INTEGER")
                db.execSQL("ALTER TABLE places ADD COLUMN source TEXT NOT NULL DEFAULT 'LEGACY'")
                db.execSQL("""CREATE TABLE IF NOT EXISTS contribution_drafts (
                    id TEXT NOT NULL PRIMARY KEY, placeId TEXT, latitude REAL NOT NULL, longitude REAL NOT NULL,
                    observedAt INTEGER NOT NULL, kind TEXT NOT NULL, text TEXT NOT NULL, status TEXT NOT NULL,
                    osmType TEXT, osmId INTEGER, remoteNoteId INTEGER, remoteStatus TEXT,
                    submittedAt INTEGER, submittedBy INTEGER, error TEXT
                )""")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_contribution_drafts_placeId ON contribution_drafts (placeId)")
            }
        }
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS journey_health_samples (
                    journeyId TEXT NOT NULL, sourceId TEXT NOT NULL, originPackage TEXT NOT NULL,
                    deviceType INTEGER, deviceManufacturer TEXT, deviceModel TEXT, metric TEXT NOT NULL,
                    startAt INTEGER NOT NULL, endAt INTEGER NOT NULL, value REAL NOT NULL,
                    PRIMARY KEY(journeyId, sourceId, metric, startAt, endAt),
                    FOREIGN KEY(journeyId) REFERENCES journeys(id) ON UPDATE NO ACTION ON DELETE CASCADE
                )""")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_journey_health_samples_journeyId ON journey_health_samples (journeyId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_journey_health_samples_sourceId ON journey_health_samples (sourceId)")
                db.execSQL("""CREATE TABLE IF NOT EXISTS journey_health_sync (
                    journeyId TEXT NOT NULL, metric TEXT NOT NULL, checkedAt INTEGER NOT NULL,
                    PRIMARY KEY(journeyId, metric),
                    FOREIGN KEY(journeyId) REFERENCES journeys(id) ON UPDATE NO ACTION ON DELETE CASCADE
                )""")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_journey_health_sync_journeyId ON journey_health_sync (journeyId)")
            }
        }
        @Volatile private var instance: DiaryDatabase? = null
        fun get(context: Context): DiaryDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, DiaryDatabase::class.java, "iz-diary.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .build().also { instance = it }
        }
    }
}
