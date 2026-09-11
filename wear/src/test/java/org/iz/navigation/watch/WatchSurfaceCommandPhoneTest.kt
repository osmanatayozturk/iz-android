package org.iz.navigation.watch

import org.junit.Assert.*
import org.junit.Test

class WatchSurfaceCommandPhoneTest {
    @Test fun disconnectedCacheNeverAuthorizesFallbackPhoneCommands() {
        assertNull(WatchSurfaceData("a", 5, connected = false).commandPhone(setOf("b")))
        assertNull(WatchSurfaceData("a", 5, connected = false).commandPhone(setOf("a", "b")))
    }
    @Test fun commandsUseExactlyTheConnectedReaderSelection() {
        assertEquals("a", WatchSurfaceData("a", 5, connected = true).commandPhone(setOf("a", "b")))
        assertEquals("b", WatchSurfaceData("b", 5, connected = true).commandPhone(setOf("a", "b")))
        assertNull(WatchSurfaceData("a", 5, connected = true).commandPhone(setOf("b")))
    }
}
