package org.iz.navigation.data

import org.iz.navigation.gpx.ImportedTrack
import org.iz.navigation.gpx.TrackSegment
import org.iz.navigation.weather.RouteStop
import org.iz.navigation.weather.WeatherCoordinate
import org.junit.Assert.*
import org.junit.Test

class TravelLibraryRulesTest {
    private val a = WeatherCoordinate(10.0, 20.0)
    private val b = WeatherCoordinate(10.001, 20.001)
    private val plan = SavedRoutePlan(id = "plan", name = "Walk", stops = listOf(RouteStop("A", a), RouteStop("B", b)), transport = Transport.WALK, createdAt = 10)
    private val journey = Journey(id = "trip", startedAt = 1_000, endedAt = 5_000)
    private val collection = JourneyCollection(id = "collection", name = "Summer", createdAt = 10)

    @Test fun planStoresEffectiveHumanSpeedAndPreservesExplicitChoices() {
        val normalized = TravelLibraryRules.normalizePlan(plan)
        assertEquals(5.1, normalized.travelSpeedKmh!!, 0.0)
        assertEquals(plan.preferences, normalized.preferences)
        assertEquals(plan.stops, normalized.stops)
        assertNull(TravelLibraryRules.normalizePlan(plan.copy(transport = Transport.CAR)).travelSpeedKmh)
    }

    @Test fun invalidPlansAreRejectedAtTheStorageBoundary() {
        listOf(plan.copy(stops = plan.stops.take(1)), plan.copy(stops = List(6) { plan.stops.first() }),
            plan.copy(name = " "), plan.copy(id = "bad\nidentity"), plan.copy(transport = Transport.UNKNOWN),
            plan.copy(travelSpeedKmh = Double.NaN), plan.copy(travelSpeedKmh = 100.0),
            plan.copy(transport = Transport.CAR, travelSpeedKmh = 5.0), plan.copy(updatedAt = 9)).forEach {
            assertThrows(IllegalArgumentException::class.java) { TravelLibraryRules.normalizePlan(it) }
        }
    }

    @Test fun geometryNormalizationKeepsBreaksNamesAndTrackGroups() {
        val track = ImportedTrack(id = "track", name = "Trail", createdAt = 10, segments = listOf(
            TrackSegment("First", listOf(a, a, b), "Track A", 0),
            TrackSegment("Second", listOf(b, a), "Track B", 1)))
        val normalized = TravelLibraryRules.normalizeTrack(track)
        assertEquals(listOf(a, b), normalized.segments[0].points)
        assertEquals(track.segments[1], normalized.segments[1])
        assertEquals("First", normalized.segments[0].name)
        assertEquals("Track A", normalized.segments[0].trackName)
        assertEquals(track.id, normalized.id)
    }

    @Test fun unusableOrOversizeGeometryIsRejectedBeforeNormalization() {
        val segment = TrackSegment("", listOf(a, b))
        val track = ImportedTrack(name = "Trail", segments = listOf(segment))
        listOf(track.copy(segments = emptyList()), track.copy(segments = listOf(segment.copy(points = listOf(a, a)))),
            track.copy(segments = List(1001) { segment }),
            track.copy(segments = listOf(segment.copy(points = List(100001) { if (it % 2 == 0) a else b }))),
            track.copy(segments = listOf(segment.copy(trackIndex = -1)))).forEach {
            assertThrows(IllegalArgumentException::class.java) { TravelLibraryRules.normalizeTrack(it) }
        }
    }

    @Test fun onlyCompletedPermanentConfirmedJourneysCanBeMembers() {
        assertTrue(TravelLibraryRules.isEligible(journey))
        listOf(journey.copy(endedAt = null), journey.copy(status = JourneyStatus.TEMPORARY),
            journey.copy(expiresAt = 9000), journey.copy(endedAt = 0)).forEach { assertFalse(TravelLibraryRules.isEligible(it)) }
    }

    @Test fun snapshotRejectsBrokenDuplicateAndAmbiguousMemberships() {
        val membership = CollectionMembership(collection.id, journey.id, 0)
        val valid = DiarySnapshot(journeys = listOf(journey), collections = listOf(collection), memberships = listOf(membership))
        DiaryRules.validate(valid)
        listOf(valid.copy(memberships = listOf(membership, membership)), valid.copy(collections = emptyList()),
            valid.copy(journeys = listOf(journey.copy(endedAt = null))),
            valid.copy(memberships = listOf(membership.copy(sortOrder = -1))),
            valid.copy(journeys = listOf(journey, journey.copy(id = "other")), memberships = listOf(membership, membership.copy(journeyId = "other")))).forEach {
            assertThrows(IllegalArgumentException::class.java) { DiaryRules.validate(it) }
        }
    }

    @Test fun collectionSummaryCountsEachJourneyOnceAndUsesExistingStats() {
        val point = TrackPoint(journeyId = journey.id, latitude = a.latitude, longitude = a.longitude, recordedAt = 1000, accuracy = 5f)
        val points = listOf(point, point.copy(id = "second", latitude = 10.0001, recordedAt = 3000))
        val state = DiarySnapshot(journeys = listOf(journey), points = points, collections = listOf(collection),
            memberships = listOf(CollectionMembership(collection.id, journey.id, 0), CollectionMembership(collection.id, journey.id, 1)))
        val summary = TravelLibraryRules.collectionSummary(collection.id, state)
        val expected = JourneyStatistics.calculate(journey, points)
        assertEquals(1, summary.journeyCount)
        assertEquals(expected.distanceMeters, summary.distanceMeters, 0.0)
        assertEquals(expected.elapsedMillis, summary.elapsedMillis)
        assertEquals(expected.stoppedMillis, summary.stoppedMillis)
        assertEquals(expected.movingMillis, summary.movingMillis)
    }
}
