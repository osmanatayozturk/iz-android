package org.iz.navigation.garage

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.iz.navigation.IzApplication
import org.iz.navigation.data.*

// Previous versions could leave an assignment behind after the journey's transport changed.
internal const val MATCHING_JOURNEY_VEHICLES = """SELECT l.rowid AS backupKey, l.journeyId, l.vehicleId
    FROM journey_vehicles l
    JOIN journeys j ON j.id = l.journeyId
    JOIN garage_vehicles v ON v.id = l.vehicleId
    WHERE j.status = 'CONFIRMED' AND j.transport = v.transport"""

@Dao
internal interface GarageDao {
    @Query("SELECT * FROM garage_vehicles ORDER BY name COLLATE NOCASE, id") fun vehicles(): Flow<List<GarageVehicle>>
    @Query("SELECT * FROM garage_vehicles WHERE id=:id") suspend fun vehicle(id: String): GarageVehicle?
    @Query("SELECT * FROM garage_vehicles WHERE id=:id") fun observeVehicle(id: String): Flow<GarageVehicle?>
    @Query("SELECT * FROM fuel_fills WHERE vehicleId=:id ORDER BY at,id") fun fills(id: String): Flow<List<FuelFill>>
    @Query("SELECT vehicleId FROM ($MATCHING_JOURNEY_VEHICLES) WHERE journeyId=:id") fun assignment(id: String): Flow<String?>
    @Query("SELECT EXISTS(SELECT 1 FROM ($MATCHING_JOURNEY_VEHICLES) WHERE journeyId=:journeyId AND vehicleId=:vehicleId)")
    suspend fun isAssigned(journeyId: String, vehicleId: String): Boolean
    @Query("SELECT * FROM fuel_fills WHERE vehicleId=:id ORDER BY at,id") suspend fun vehicleFills(id: String): List<FuelFill>
    @Query("SELECT * FROM fuel_fills WHERE id=:id") suspend fun fill(id: String): FuelFill?
    @Query("SELECT * FROM garage_vehicles ORDER BY id") suspend fun allVehicles(): List<GarageVehicle>
    @Query("SELECT * FROM fuel_fills ORDER BY at,id") suspend fun allFills(): List<FuelFill>
    @Query("SELECT journeyId, vehicleId FROM ($MATCHING_JOURNEY_VEHICLES) ORDER BY journeyId") suspend fun allAssignments(): List<JourneyVehicle>
    @Query("""SELECT p.* FROM track_points p JOIN ($MATCHING_JOURNEY_VEHICLES) l ON l.journeyId=p.journeyId
        WHERE l.vehicleId=:vehicle AND p.recordedAt>=:since
        UNION ALL
        SELECT p.* FROM ($MATCHING_JOURNEY_VEHICLES) l JOIN track_points p ON p.id=(
            SELECT q.id FROM track_points q WHERE q.journeyId=l.journeyId AND q.recordedAt<:since
            ORDER BY q.recordedAt DESC,q.id DESC LIMIT 1)
        WHERE l.vehicleId=:vehicle
        ORDER BY journeyId,recordedAt,id""")
    fun points(vehicle: String, since: Long): Flow<List<TrackPoint>>
    @Query("""INSERT INTO journey_start_vehicles(journeyId, transport, vehicleId)
        SELECT :journeyId, transport, MIN(id) FROM garage_vehicles
        WHERE transport IN ('CAR', 'MOTORCYCLE') GROUP BY transport HAVING COUNT(*) = 1""")
    suspend fun captureStartVehicles(journeyId: String)

    @Query("""INSERT OR IGNORE INTO journey_vehicles(journeyId, vehicleId)
        SELECT j.id, v.id FROM journeys j
        JOIN journey_start_vehicles s ON s.journeyId = j.id AND s.transport = j.transport
        JOIN garage_vehicles v ON v.id = s.vehicleId AND v.transport = j.transport
        WHERE j.id = :journeyId AND j.status = 'CONFIRMED'""")
    suspend fun assignStartVehicle(journeyId: String)

    @Query("""INSERT OR IGNORE INTO journey_vehicles(journeyId, vehicleId)
        SELECT j.id, v.id FROM journeys j JOIN garage_vehicles v ON v.transport = j.transport
        WHERE j.id = :journeyId AND j.status = 'CONFIRMED' AND j.endedAt IS NULL AND v.id = :vehicleId""")
    suspend fun assignStartChoiceIfUnassigned(journeyId: String, vehicleId: String)

    @Query("DELETE FROM journey_start_vehicles WHERE journeyId = :journeyId")
    suspend fun forgetStartVehicles(journeyId: String)

    @Upsert suspend fun save(vehicle: GarageVehicle)
    @Upsert suspend fun save(fill: FuelFill)
    @Upsert suspend fun save(link: JourneyVehicle)
    @Query("DELETE FROM journey_vehicles WHERE journeyId=:id") suspend fun clearAssignment(id: String)
    @Query("DELETE FROM garage_vehicles WHERE id=:id") suspend fun deleteVehicle(id: String)
    @Query("DELETE FROM fuel_fills WHERE id=:id") suspend fun deleteFill(id: String)
    @Query("DELETE FROM garage_vehicles") suspend fun clear()
}

class GarageRepository internal constructor(private val app: IzApplication) {
    private val changes = Mutex()
    // Written by recording attachment and read by bindSession under the same navigation command lock.
    private var lastNewRecording: Pair<String, String>? = null
    private val db = DiaryDatabase.get(app)
    private val dao = db.garageDao()
    val vehicles = dao.vehicles()
    internal data class Binding(val sessionId: String, val vehicleId: String, val explicitChoice: Boolean)
    internal val unrecordedBinding = MutableStateFlow<Binding?>(null)

    fun fills(vehicleId: String) = dao.fills(vehicleId)
    fun assignedVehicleId(journeyId: String) = dao.assignment(journeyId)

    suspend fun saveVehicle(vehicle: GarageVehicle) = mutate {
        vehicle.validate()
        val original = dao.vehicle(vehicle.id)
        require(original == null || original.transport == vehicle.transport) { "Kaydedilen aracın türü değiştirilemez." }
        dao.save(vehicle.copy(name = vehicle.name.trim()))
    }

    suspend fun deleteVehicle(id: String) = mutate { dao.deleteVehicle(id) }
    suspend fun deleteFill(id: String) = mutate { dao.deleteFill(id) }

    suspend fun saveFill(fill: FuelFill) = mutate {
        fill.validate()
        require(dao.vehicle(fill.vehicleId) != null) { "Araç artık garajda değil." }
        require(dao.fill(fill.id)?.vehicleId?.let { it == fill.vehicleId } != false) { "Dolum başka araca ait." }
        val history = dao.vehicleFills(fill.vehicleId).filterNot { it.id == fill.id } + fill
        validateFillOrder(history.sortedBy { it.at })
        dao.save(fill)
    }

    suspend fun assignJourney(journeyId: String, vehicleId: String) = mutate { assign(journeyId, vehicleId) }

    private suspend fun assign(journeyId: String, vehicleId: String) {
        val journey = requireNotNull(db.diaryDao().journey(journeyId)) { "Yolculuk bulunamadı." }
        val vehicle = requireNotNull(dao.vehicle(vehicleId)) { "Araç bulunamadı." }
        require(journey.status == JourneyStatus.CONFIRMED && journey.transport == vehicle.transport) {
            "Araç türü kaydedilen yolculukla eşleşmiyor."
        }
        dao.save(JourneyVehicle(journeyId, vehicleId))
    }

    internal suspend fun bindStartChoice(journeyId: String, vehicleId: String) =
        mutate { insertStartChoice(journeyId, vehicleId) }

    private suspend fun insertStartChoice(journeyId: String, vehicleId: String) {
        dao.assignStartChoiceIfUnassigned(journeyId, vehicleId)
        check(dao.isAssigned(journeyId, vehicleId)) { "Yolculuk veya araç seçimi değişti." }
    }

    suspend fun bindSession(sessionId: String, journeyId: String?, vehicleId: String?, transport: Transport,
        explicitChoice: Boolean = true) {
        if (vehicleId == null) return
        changes.withLock {
            app.navigation.withRecordingStartSettled {
                db.withTransaction {
                    val vehicle = requireNotNull(dao.vehicle(vehicleId)) { "Araç bulunamadı." }
                    val state = app.navigation.state.value
                    val attached = state.journey?.id
                    val lateStart = journeyId == null && explicitChoice && attached != null &&
                        lastNewRecording == (sessionId to attached)
                    check(state.sessionId == sessionId && (attached == journeyId || lateStart) &&
                        state.sessionTransport == transport && vehicle.transport == transport && !state.simulation) {
                        "Sürüş veya araç değişti."
                    }
                    if (explicitChoice && attached != null) insertStartChoice(attached, vehicleId)
                }
                if (journeyId == null) rememberUnrecorded(sessionId, vehicleId, explicitChoice)
            }
        }
    }

    private suspend fun <T> mutate(block: suspend () -> T): T = changes.withLock { db.withTransaction { block() } }

    internal fun rememberUnrecorded(session: String, id: String, explicitChoice: Boolean) {
        unrecordedBinding.update { previous ->
            if (!explicitChoice && previous?.sessionId == session && previous.explicitChoice) previous
            else Binding(session, id, explicitChoice)
        }
    }

    internal suspend fun carryStartChoice(sessionId: String, journeyId: String) {
        val state = app.navigation.state.value
        if (state.simulation || state.sessionId != sessionId || state.journey?.id != journeyId) return
        db.withTransaction {
            val choice = unrecordedBinding.value
            if (choice?.sessionId == sessionId && choice.explicitChoice) {
                dao.assignStartChoiceIfUnassigned(journeyId, choice.vehicleId)
            }
        }
        lastNewRecording = sessionId to journeyId
    }

    internal suspend fun applyForProjection(vehicle: GarageVehicle, selectedId: String?, extraKm: (FuelStats) -> Double,
        action: suspend () -> Boolean): Boolean = changes.withLock {
        val options = dao.allVehicles().filter { it.transport == vehicle.transport }
        val current = if (selectedId == null) options.singleOrNull() else options.find { it.id == selectedId }
        if (current != vehicle) return@withLock false
        val stats = observeStats(vehicle.id).first() ?: return@withLock false
        if (stats.needsFuel(extraKm(stats))) action() else false
    }

    internal suspend fun applyForOwner(session: String, journey: String?, id: String, extraKm: (FuelStats) -> Double,
        action: suspend () -> Boolean): Boolean =
        changes.withLock {
            val assigned = if (journey != null) dao.assignment(journey).first()
                else unrecordedBinding.value?.takeIf { it.sessionId == session }?.vehicleId
            if (assigned != id) return@withLock false
            val stats = observeStats(id).first() ?: return@withLock false
            if (stats.needsFuel(extraKm(stats))) action() else false
        }

    /** A drive with insufficient fuel history should never read its GPS history to evaluate a prompt. */
    internal fun offerStats(vehicle: GarageVehicle): Flow<FuelStats?> = observeStats(vehicle.id, requireLearned = true)

    fun stats(vehicleId: String): Flow<FuelStats> = observeStats(vehicleId).filterNotNull()

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeStats(vehicleId: String, requireLearned: Boolean = false): Flow<FuelStats?> =
        combine(dao.observeVehicle(vehicleId), dao.fills(vehicleId)) { vehicle, fills -> vehicle to fills }
            .distinctUntilChanged()
            .flatMapLatest { (vehicle, fills) ->
                if (vehicle == null) return@flatMapLatest flowOf(null)
                val lastFill = fills.lastOrNull()?.takeIf { it.at >= vehicle.odometerAt }
                val since = lastFill?.at ?: vehicle.odometerAt
                val baseline = lastFill?.odometerKm ?: vehicle.odometerKm
                val history = FuelHistory.prepare(vehicle, fills)
                if (requireLearned && history.learningFullCount < 5) return@flatMapLatest flowOf(null)
                dao.points(vehicleId, since).map { points ->
                    var meters = 0.0
                    var previous: TrackPoint? = null
                    for (point in points) {
                        val a = previous
                        if (a != null && a.journeyId == point.journeyId && point.recordedAt > since &&
                            point.recordedAt > a.recordedAt) {
                            val fraction = (point.recordedAt - maxOf(since, a.recordedAt)).toDouble() /
                                (point.recordedAt - a.recordedAt)
                            meters += DiaryRules.distanceContributionMeters(a, point) * fraction
                        }
                        previous = point
                    }
                    history.statsAt(baseline + meters / 1000).copy(distanceBaselineAt = since)
                }
            }.distinctUntilChanged().flowOn(Dispatchers.Default)
}
