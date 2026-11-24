package org.iz.navigation.integration

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.iz.navigation.data.DiaryDatabase
import org.iz.navigation.data.Journey
import org.iz.navigation.data.JourneyStatus
import org.iz.navigation.data.RecordedCurveDetail
import org.iz.navigation.data.RecordedCurves
import org.iz.navigation.data.TrackPoint
import org.iz.navigation.data.Transport
import org.iz.navigation.lean.LeanStore
import org.iz.navigation.power.PostJourneyAnalysis
import java.security.MessageDigest

internal data class CurvePassCandidate(val journeyId: String, val journeyTitle: String, val at: Long,
    val curve: RecordedCurveDetail)

/** A local, explicit read for the selected curve. No provider or durable comparison state. */
internal class CurvePassComparisonRepository(context: Context) {
    private val db = DiaryDatabase.get(context.applicationContext)
    private val analysis = PostJourneyAnalysis.get(context.applicationContext)
    private val lean = LeanStore.get(context.applicationContext)
    private val diary = db.diaryDao()

    suspend fun candidates(selected: RecordedCurveDetail): List<CurvePassCandidate> = withContext(Dispatchers.IO) {
        val job = currentCoroutineContext()
        val selectedRide = read(selected.journeyId) ?: return@withContext emptyList()
        val reference = selectedRide.trace(selected, job::ensureActive) ?: return@withContext emptyList()
        val candidates = mutableListOf<CurvePassCandidate>()
        for (journey in diary.allJourneys()) {
            job.ensureActive()
            if (!journey.eligible() || journey.startedAt >= selected.start.at) continue
            val prepared = analysis.state.value.ready(journey.id)?.takeIf { it.matches(journey) }?.let {
                analysis.cache.curves(journey.id, it.fingerprint)
            }
            val hinted = prepared?.curves?.filter { it.possibleEarlierPass(selected) }
            if (hinted?.isEmpty() == true && !prepared.truncated) continue
            val ride = if (journey.id == selectedRide.journey.id) selectedRide else read(journey.id) ?: continue
            val fingerprint = prepared?.sourceFingerprint ?: ride.fingerprint(job::ensureActive)
            val actual = RecordedCurves.prepare(ride.journey, ride.points, fingerprint, job::ensureActive)
            for (curve in actual.curves) {
                job.ensureActive()
                if (!curve.possibleEarlierPass(selected)) continue
                val trace = CurvePassMatcher.trace(curve, ride.points) ?: continue
                if (CurvePassMatcher.matches(reference, trace)) {
                    candidates += CurvePassCandidate(ride.journey.id, ride.journey.title, curve.start.at, curve)
                }
            }
        }
        candidates.sortedWith(compareByDescending<CurvePassCandidate> { it.at }.thenBy { it.curve.id })
    }

    suspend fun compare(selected: RecordedCurveDetail, candidate: CurvePassCandidate): CurvePassComparison? =
        withContext(Dispatchers.IO) {
            if (candidate.journeyId != candidate.curve.journeyId || !candidate.curve.possibleEarlierPass(selected))
                return@withContext null
            val job = currentCoroutineContext()
            val selectedRide = read(selected.journeyId) ?: return@withContext null
            val previousRide = if (candidate.journeyId == selected.journeyId) selectedRide
                else read(candidate.journeyId) ?: return@withContext null
            val reference = selectedRide.trace(selected, job::ensureActive) ?: return@withContext null
            val previous = previousRide.trace(candidate.curve, job::ensureActive) ?: return@withContext null
            val selectedReport = lean.observeReport(selected.journeyId).first()
            val previousReport = if (candidate.journeyId == selected.journeyId) selectedReport
                else lean.observeReport(candidate.journeyId).first()
            job.ensureActive()
            CurvePassMatcher.compare(reference, previous, selectedReport, previousReport)
        }

    private suspend fun read(id: String): Ride? = db.withTransaction {
        val journey = diary.journey(id)?.takeIf { it.eligible() } ?: return@withTransaction null
        Ride(journey, diary.journeyPoints(id))
    }

    private data class Ride(val journey: Journey, val points: List<TrackPoint>) {
        fun trace(curve: RecordedCurveDetail, checkActive: () -> Unit): CurvePassMatcher.Trace? {
            val actual = RecordedCurves.prepare(journey, points, curve.sourceFingerprint, checkActive)
            if (actual.curves.none { it == curve }) return null
            return CurvePassMatcher.trace(curve, points)
        }

        fun fingerprint(checkActive: () -> Unit): String {
            val digest = MessageDigest.getInstance("SHA-256")
            points.forEachIndexed { index, point ->
                if (index % 256 == 0) checkActive()
                digest.update(point.toString().toByteArray(Charsets.UTF_8))
                digest.update(0.toByte())
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }

    private fun Journey.eligible(): Boolean =
        transport == Transport.MOTORCYCLE && status == JourneyStatus.CONFIRMED && endedAt != null

    private fun RecordedCurveDetail.possibleEarlierPass(selected: RecordedCurveDetail): Boolean =
        end.at < selected.start.at && direction == selected.direction &&
            kotlin.math.abs(start.latitude - selected.start.latitude) < .0002 &&
            kotlin.math.abs(end.latitude - selected.end.latitude) < .0002
}
