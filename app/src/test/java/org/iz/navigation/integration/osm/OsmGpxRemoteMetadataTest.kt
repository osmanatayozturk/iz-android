package org.iz.navigation.integration.osm

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class OsmGpxRemoteMetadataTest {
    @Test fun canonicalDigestPreservesFieldsBoundariesAndTagMultiplicityButExcludesVisibility() {
        val base = OsmGpxRemoteMetadata(4321, "İz & sample.gpx", 123456789, false, "identifiable", "<fixture>", listOf("ab", "c", "ab"))
        val digest = base.digest()
        assertTrue(digest.matches(Regex("[0-9a-f]{64}")))
        assertEquals(digest, base.copy(visibility = "trackable", pending = true, tags = listOf("c", "ab", "ab")).digest())
        for (changed in listOf(base.copy(id = 4322), base.copy(name = "other"),
            base.copy(timestampMillis = 123456790), base.copy(description = "other"),
            base.copy(tags = listOf("ab", "c")), base.copy(tags = listOf("a", "bc", "ab")))) {
            assertNotEquals(digest, changed.digest())
        }
        assertNotEquals(base.copy(tags = listOf("ab", "c")).digest(), base.copy(tags = listOf("a", "bc")).digest())
    }

    @Test fun historyOnlyGatewayCannotPretendToReadMetadata() {
        val gateway = OsmGpxHistoryGateway { _, _ -> emptyList() }
        assertThrows(IOException::class.java) { runBlocking {
            gateway.readMetadata("fixture-token", 41, 4321, canDispatch = { true })
        } }
    }
}
