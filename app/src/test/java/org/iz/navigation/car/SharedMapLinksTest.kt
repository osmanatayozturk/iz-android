package org.iz.navigation.car

import org.junit.Assert.*
import org.junit.Test

class SharedMapLinksTest {
    private fun single(raw: String) = SharedMapLinks.parse(raw) as SharedMapInput.Single
    @Test fun whatsAppTextUsesExplicitPinInsteadOfCamera() {
        val result = single("Konum burada\nhttps://www.google.com/maps/place/Okul/@5,6,12z/data=!3d41.1!4d29.2")
        assertEquals(41.1, result.target.coordinate!!.latitude, 0.0)
        assertEquals("Okul", result.target.name)
    }
    @Test fun cameraCenterNeverBecomesATarget() {
        assertTrue(SharedMapLinks.parse("https://www.google.com/maps/@41,29,12z") is SharedMapInput.Unsupported)
        assertTrue(SharedMapLinks.parse("https://maps.apple.com/frame?center=41,29") is SharedMapInput.Unsupported)
        assertNull(single("https://maps.apple.com/search?query=Okul&center=41,29").target.coordinate)
    }
    @Test fun namesRequireSearchAndOpaqueIdentifiersAreExplained() {
        assertNull(single("https://www.google.com/maps/search/?api=1&query=ODTU").target.coordinate)
        assertTrue(SharedMapLinks.parse("https://maps.apple.com/place?place-id=I123") is SharedMapInput.Unsupported)
        assertTrue(SharedMapLinks.parse("https://maps.google.com/?q=place_id:ChIJ123") is SharedMapInput.Unsupported)
    }
    @Test fun repeatedGoogleWaypointsAndDuplicateOccurrencesRemainInOrder() {
        val result = SharedMapLinks.parse("https://www.google.com/maps/dir/?origin=1,2&waypoints=A%7CB&waypoints=A&destination=3,4") as SharedMapInput.Itinerary
        assertEquals(listOf("A", "B", "A"), result.via.map { it.name })
        assertTrue(result.via.all { it.coordinate == null })
        assertEquals(1.0, result.origin!!.coordinate!!.latitude, 0.0)
    }
    @Test fun appleModernAndLegacyDirectionsPreserveDynamicOrigin() {
        val modern = SharedMapLinks.parse("https://maps.apple.com/directions?source=here&waypoint=One&waypoint=One&destination=3,4") as SharedMapInput.Itinerary
        assertTrue(modern.origin!!.currentLocation)
        assertEquals(listOf("One", "One"), modern.via.map { it.name })
        assertEquals(3.0, single("https://maps.apple.com/?daddr=3,4").target.coordinate!!.latitude, 0.0)
        assertEquals(3.0, single("https://maps.apple.com/place?coordinate=3,4&name=Park").target.coordinate!!.latitude, 0.0)
    }
    @Test fun twentyStopsAcceptedTwentyOneRejectedWithoutTruncation() {
        fun url(n: Int) = "https://www.google.com/maps/dir/?origin=A&destination=B&waypoints=" + (1..n).joinToString("%7C") { "V$it" }
        assertEquals(18, (SharedMapLinks.parse(url(18)) as SharedMapInput.Itinerary).via.size)
        assertTrue(SharedMapLinks.parse(url(19)) is SharedMapInput.Unsupported)
    }
    @Test fun pathDirectionsPreserveOrderAndIgnoreCamera() {
        val result = SharedMapLinks.parse("https://www.google.com/maps/dir/A/A/41,29/@5,6,12z/data=!4m2") as SharedMapInput.Itinerary
        assertEquals("A", result.origin!!.name)
        assertEquals("A", result.via.single().name)
        assertEquals(41.0, result.destination.coordinate!!.latitude, 0.0)
    }
    @Test fun emptyIntermediatePathStopIsRejectedRatherThanDropped() {
        assertTrue(SharedMapLinks.parse("https://www.google.com/maps/dir/A//B/") is SharedMapInput.Unsupported)
        assertEquals("B", single("https://www.google.com/maps/dir//B/").target.name)
    }
    @Test fun malformedOrMaliciousLinksNeverBecomeCoordinatesOrFetchableUrls() {
        listOf("https://google.com.evil/maps?q=1,2", "https://user:pass@maps.apple.com/?q=1,2", "http://maps.apple.com/?q=1,2",
            "https://www.google.com/maps/?q=91,2", "https://www.google.com/maps/?q=%XX").forEach {
            assertFalse(it, SharedMapLinks.parse(it) is SharedMapInput.Single)
        }
        assertTrue(SharedMapLinks.parse("https://maps.app.goo.gl/abc") is SharedMapInput.ShortLink)
        assertTrue(SharedMapLinks.parse("https://goo.gl/maps/abc") is SharedMapInput.ShortLink)
    }
}
