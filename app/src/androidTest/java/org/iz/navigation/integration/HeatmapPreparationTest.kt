package org.iz.navigation.integration

import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.iz.navigation.data.Journey
import org.iz.navigation.data.TrackPoint
import org.iz.navigation.data.Transport
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HeatmapPreparationTest {
    @get:Rule val compose = createComposeRule()

    private data class Observation(val revision: Int, val render: HeatmapRender?)

    @Test fun routineUpdatesKeepReadyMapUntilReplacementButFilterChangesClearImmediately() {
        val journeys = listOf(
            Journey(id = "car", transport = Transport.CAR, startedAt = 0, endedAt = 60_000),
            Journey(id = "walk", transport = Transport.WALK, startedAt = 0, endedAt = 60_000),
        )
        fun point(id: String, latitude: Double) = TrackPoint(journeyId = id, latitude = latitude,
            longitude = 29.0, recordedAt = 1_000, accuracy = 5f)
        val points = mutableStateOf(listOf(point("car", 41.0), point("walk", 40.0)))
        val mode = mutableStateOf(Transport.CAR)
        val revision = mutableStateOf(0)
        val observations = mutableListOf<Observation>()
        compose.setContent {
            val currentRevision = revision.value
            val render = rememberHeatmapRender(journeys, points.value, mode.value, now = 100_000)
            SideEffect { observations += Observation(currentRevision, render) }
        }
        fun ready(version: Int, latitude: Double) = observations.any {
            it.revision == version && it.render?.data?.isolatedPoints?.singleOrNull()?.coordinate?.latitude == latitude
        }
        compose.waitUntil(10_000) { ready(0, 41.0) }
        compose.runOnIdle {
            revision.value = 1
            points.value = listOf(point("car", 41.5), point("walk", 40.0))
        }
        compose.waitUntil(10_000) { ready(1, 41.5) }
        compose.runOnIdle {
            assertFalse("Routine updates must not hide the map or resize its legend",
                observations.any { it.revision == 1 && it.render == null })
            revision.value = 2
            mode.value = Transport.WALK
        }
        compose.waitUntil(10_000) { ready(2, 40.0) }
        compose.runOnIdle {
            assertTrue("The old filter must clear immediately", observations.first { it.revision == 2 }.render == null)
            assertTrue(observations.filter { it.revision == 2 }.all {
                it.render == null || it.render.data.isolatedPoints.single().coordinate.latitude == 40.0
            })
            revision.value = 3
            points.value = emptyList()
        }
        compose.waitUntil(10_000) { observations.any { it.revision == 3 && it.render?.hasGeometry == false } }
        compose.runOnIdle {
            assertFalse("An empty refresh must not strand a loading overlay", observations.any { it.revision == 3 && it.render == null })
        }
    }
}
