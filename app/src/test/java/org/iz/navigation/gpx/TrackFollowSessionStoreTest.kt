package org.iz.navigation.gpx

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TrackFollowSessionStoreTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun markerSurvivesNewStoreInstanceAndClearingRemovesIt() {
        val directory = folder.newFolder("navigation")
        val store = TrackFollowSessionStore(directory)
        assertFalse(store.read())
        store.write()
        assertTrue(TrackFollowSessionStore(directory).read())
        store.clear()
        assertFalse(TrackFollowSessionStore(directory).read())
        assertEquals(0, directory.listFiles()!!.size)
    }

    @Test fun markerDoesNotContainCoordinatesNameOrSessionIdentity() {
        val directory = folder.newFolder("navigation")
        TrackFollowSessionStore(directory).write()
        assertEquals(1, directory.listFiles()!!.size)
        assertTrue(directory.listFiles()!!.single().length() in 1..16)
    }
}
