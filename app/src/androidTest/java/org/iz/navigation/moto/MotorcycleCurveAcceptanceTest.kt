package org.iz.navigation.moto

import android.content.Intent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.room.withTransaction
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import org.iz.navigation.MainActivity
import org.iz.navigation.data.DiaryDatabase
import org.iz.navigation.data.Journey
import org.iz.navigation.data.JourneyStatus
import org.iz.navigation.data.RecordedCurves
import org.iz.navigation.data.TrackPoint
import org.iz.navigation.data.Transport
import org.iz.navigation.integration.CurvePassComparisonRepository
import org.iz.navigation.power.PostJourneyAnalysis
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MotorcycleCurveAcceptanceTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun diaryFindsTheEarlierPassAndRejectsUnreliableAlternatives() = runBlocking<Unit>(Dispatchers.IO) {
        val context = requireMotorcycleQaTarget()
        val db = DiaryDatabase.get(context)
        val dao = db.diaryDao()
        val source = ride("latest", 90_000L, arc(7), speed = 10f)
        val match = ride("previous", 10_000L, arc(5, 1.0), speed = 8f, interval = 2_500L)
        val reverse = ride("reverse", 20_000L, arc(7).reversed())
        val parallel = ride("parallel", 30_000L, arc(7, 10.5))
        val branch = ride("branch", 40_000L,
            listOf(0.0 to 0.0, 25.0 to -7.0, 50.0 to 0.0, 73.0 to 14.0, 90.0 to 33.0, 99.0 to 62.0, 100.0 to 100.0))
        val gap = ride("gap", 50_000L, arc(7), gapAt = 3)
        val weak = ride("weak-gps", 60_000L, arc(5), accuracy = 13f)
        val candidate = ride("temporary", 70_000L, arc(7), status = JourneyStatus.TEMPORARY)
        val car = ride("car", 80_000L, arc(7), transport = Transport.CAR)
        val rides = listOf(source, match, reverse, parallel, branch, gap, weak, candidate, car)

        val selected = source.curves().single()
        val other = branch.curves().single()
        assertEquals(source.points.first().latitude, branch.points.first().latitude, 1e-10)
        assertEquals(source.points.first().longitude, branch.points.first().longitude, 1e-10)
        assertEquals(source.points.last().latitude, branch.points.last().latitude, 1e-10)
        assertEquals(source.points.last().longitude, branch.points.last().longitude, 1e-10)
        assertEquals(selected.direction, other.direction)
        assertTrue(abs(selected.recordedDistanceMeters - other.recordedDistanceMeters) < 20.0)
        assertNotNull(weak.curves().singleOrNull())
        assertFalse(gap.curves().any { it.start.at < gap.points[3].recordedAt && it.end.at >= gap.points[3].recordedAt })
        assertNotEquals(selected.direction, reverse.curves().single().direction)

        try {
            db.withTransaction {
                rides.forEach { ride ->
                    dao.save(ride.journey)
                    ride.points.forEach { dao.save(it) }
                }
            }
            val repository = CurvePassComparisonRepository(context)
            val accepted = repository.candidates(selected)
            assertEquals(listOf(match.journey.id), accepted.map { it.journeyId })
            val previous = accepted.single()
            assertEquals(match.points.first().recordedAt, previous.at)
            val result = requireNotNull(repository.compare(selected, previous))

            assertEquals(12_000L, result.selected.durationMillis)
            assertEquals(10_000L, result.previous.durationMillis)
            assertEquals(36.0, requireNotNull(result.selected.sampledMinSpeedKmh), .001)
            assertEquals(57.6, requireNotNull(result.selected.sampledMaxSpeedKmh), .001)
            assertEquals(28.8, requireNotNull(result.previous.sampledMinSpeedKmh), .001)
            assertEquals(43.2, requireNotNull(result.previous.sampledMaxSpeedKmh), .001)
            assertNull(result.selected.lean)
            assertNull(result.previous.lean)
            val samples = result.samples
            assertTrue(samples.size > source.points.size)
            assertEquals(0.0, samples.first().distanceMeters, 1e-8)
            assertEquals(selected.recordedDistanceMeters, samples.last().distanceMeters, 1e-6)
            assertTrue(samples.zipWithNext().all { (a, b) -> a.distanceMeters < b.distanceMeters })
            val midpoint = samples[samples.size / 2]
            assertTrue(requireNotNull(midpoint.selectedSpeedKmh) in 44.0..50.0)
            assertTrue(requireNotNull(midpoint.previousSpeedKmh) in 33.0..39.0)
            assertTrue(abs(midpoint.selectedCoordinate.latitude - midpoint.previousCoordinate.latitude) < .00004)
            assertTrue(abs(midpoint.selectedCoordinate.longitude - midpoint.previousCoordinate.longitude) < .00004)

            dao.deleteJourney(match.journey.id)
            assertNull(repository.compare(selected, previous))
        } finally {
            db.withTransaction { rides.forEach { dao.deleteJourney(it.journey.id) } }
        }
    }

    @Test fun journeyReportsOpensEarlierDateAndRendersASelectionOnTheSharedDistanceMap() {
        val context = requireMotorcycleQaTarget()
        val db = DiaryDatabase.get(context)
        val dao = db.diaryDao()
        val hour = 3_600_000L
        val now = System.currentTimeMillis()
        val selected = ride("Seçili viraj", now - hour, arc(7), speed = 10f)
        val previous = ride("Önceki viraj", now - 2 * hour, arc(5), speed = 8f)
        val rides = listOf(selected, previous)
        try {
            runBlocking {
                db.withTransaction {
                    rides.forEach { ride ->
                        dao.save(ride.journey)
                        ride.points.forEach { dao.save(it) }
                    }
                }
            }
            val analysis = PostJourneyAnalysis.get(context)
            analysis.request()
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use {
                runBlocking {
                    withTimeout(12_000) {
                        while (!analysis.canProcessNow()) delay(100)
                        withContext(Dispatchers.IO) { analysis.runPending() }
                        analysis.state.first { it.ready(selected.journey.id) != null }
                    }
                }
                compose.waitUntil(8_000) { compose.onAllNodesWithTag("home-menu").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("home-menu").performClick()
                compose.onNodeWithTag("menu-journeys").performScrollTo().performClick()
                openListRow(selected.journey.title)
                openListRow("Raporlar")
                compose.onNodeWithText("GPS virajları").assertIsDisplayed()
                compose.waitUntil(25_000) {
                    compose.onAllNodesWithText("Virajları göster (1)").fetchSemanticsNodes().isNotEmpty()
                }
                openListRow("Virajları göster (1)")
                compose.onNodeWithText("Sol viraj").performClick()
                val compare = hasTestTag("gps-curve-compare")
                compose.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToNode(compare)
                compose.onNode(compare).performClick()
                compose.waitUntil(8_000) {
                    compose.onAllNodesWithTag("curve-pass-candidate").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onAllNodesWithTag("curve-pass-candidate").assertCountEquals(1)
                compose.onNodeWithTag("curve-pass-candidate").assertTextContains(previous.journey.title).performClick()
                compose.waitUntil(8_000) {
                    compose.onAllNodesWithTag("curve-pass-map").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithTag("curve-pass-map").assertIsDisplayed()
                val dialogList = hasScrollToIndexAction() and hasAnyAncestor(hasTestTag("curve-pass-comparison"))
                val slider = hasTestTag("curve-pass-distance-slider")
                compose.onNode(dialogList).performScrollToNode(slider)
                val distance = compose.onNodeWithTag("curve-pass-distance")
                fun displayedDistance() = distance.fetchSemanticsNode().config[SemanticsProperties.Text].single().text
                val before = displayedDistance()
                compose.onNode(slider).performSemanticsAction(SemanticsActions.SetProgress) { it(4f) }
                assertNotEquals(before, displayedDistance())
                compose.onNode(dialogList).performScrollToNode(hasText("Yatış ölçümü yok"))
                compose.onAllNodesWithText("Yatış ölçümü yok").onFirst().assertIsDisplayed()
            }
        } finally {
            runBlocking { db.withTransaction { rides.forEach { dao.deleteJourney(it.journey.id) } } }
        }
    }

    private fun openListRow(label: String) {
        val row = hasText(label)
        compose.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToNode(row)
        compose.onNode(row).performClick()
    }

    private data class Ride(val journey: Journey, val points: List<TrackPoint>) {
        fun curves() = RecordedCurves.prepare(journey, points, "b".repeat(64)).curves
    }

    private fun ride(
        name: String, start: Long, geometry: List<Pair<Double, Double>>, speed: Float = 12f,
        interval: Long = 2_000L, accuracy: Float = 2.5f, gapAt: Int = -1,
        status: JourneyStatus = JourneyStatus.CONFIRMED, transport: Transport = Transport.MOTORCYCLE,
    ): Ride {
        val id = UUID.randomUUID().toString()
        val points = geometry.mapIndexed { index, (east, north) ->
            val gap = if (gapAt > 0 && index >= gapAt) 20_000L else 0L
            TrackPoint(
                journeyId = id, latitude = 0.27 + north / 111_195, longitude = -140.0 + east / 111_195,
                recordedAt = start + index * interval + gap, accuracy = accuracy, speed = speed + index,
                breakBefore = index == gapAt,
            )
        }
        return Ride(Journey(id = id, title = "QA $name", transport = transport, status = status,
            startedAt = start, endedAt = points.last().recordedAt.takeIf { status == JourneyStatus.CONFIRMED }), points)
    }

    private fun arc(points: Int, eastOffset: Double = 0.0) = List(points) { index ->
        val angle = Math.PI * index / (2 * (points - 1))
        (100 * sin(angle) + eastOffset) to (100 * (1 - cos(angle)))
    }
}
