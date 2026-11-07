package org.iz.navigation.integration.osm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class OsmGpxAuthorizationTest {
    @Test fun upgradeAddsOnlyGpxToExistingCapabilities() {
        val current = setOf("read_prefs", "write_notes", "write_api", "consume_messages", "send_messages")
        assertEquals(current + "write_gpx", osmGpxUpgradeScopes(current))
        assertEquals(current + "write_gpx", OsmOAuthScopes.requested(current + "write_gpx"))
        assertFalse("write_gpx" in OsmOAuthScopes.requested())
    }
}
