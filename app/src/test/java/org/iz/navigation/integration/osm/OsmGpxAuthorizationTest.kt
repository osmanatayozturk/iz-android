package org.iz.navigation.integration.osm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class OsmGpxAuthorizationTest {
    private val current = setOf("read_prefs", "write_notes", "write_api", "consume_messages", "send_messages")

    @Test fun writeUpgradeAddsOnlyWriteToExistingCapabilities() {
        assertEquals(current + "write_gpx", osmGpxUpgradeScopes(current))
        assertEquals(current + "write_gpx", OsmOAuthScopes.requested(current + "write_gpx"))
        assertFalse("write_gpx" in OsmOAuthScopes.requested())
    }

    @Test fun readUpgradeAddsOnlyReadAndKeepsPreviouslyGrantedWrite() {
        assertEquals(current + "read_gpx", osmGpxUpgradeScopes(current, OSM_GPX_READ_SCOPE))
        val both = current + setOf("read_gpx", "write_gpx")
        assertEquals(both, osmGpxUpgradeScopes(current + "write_gpx", OSM_GPX_READ_SCOPE))
        assertEquals(both, osmGpxUpgradeScopes(current + "read_gpx"))
        assertFalse("read_gpx" in OsmOAuthScopes.requested())
    }

    @Test fun gpxCallbackRequiresExplicitRequestedScopes() {
        val requested = current + "read_gpx"
        for (scope in listOf(null, "", current.joinToString(" "),
            (requested - "write_notes").joinToString(" "),
            (requested + "write_gpx").joinToString(" "))) {
            assertThrows(IllegalArgumentException::class.java) { OsmOAuthScopes.granted(requested, scope) }
        }
        assertEquals(requested, OsmOAuthScopes.granted(requested, requested.joinToString(" ")))
        assertEquals(current, OsmOAuthScopes.granted(current, null))
        assertThrows(IllegalArgumentException::class.java) {
            OsmOAuthScopes.granted(current, requested.joinToString(" "))
        }
    }

    @Test fun readAndWriteUseTheSameSessionWhenReadIsGranted() {
        val source = OsmCommunityAuthSource {}
        val user = OsmUser(41, "Fixture")
        val writeOnly = OsmCredentials("write-token", user, null, current + "write_gpx")
        source.update(writeOnly, 1)
        assertNull(source.sessionFor(writeOnly, OSM_GPX_READ_SCOPE))

        val both = OsmCredentials("both-token", user, null, current + setOf("read_gpx", "write_gpx"))
        source.update(both, 2)
        assertNull(source.sessionFor(writeOnly))
        assertSame(source.sessions.value, source.sessionFor(both))
        assertSame(source.sessions.value, source.sessionFor(both, OSM_GPX_READ_SCOPE))
    }
}
