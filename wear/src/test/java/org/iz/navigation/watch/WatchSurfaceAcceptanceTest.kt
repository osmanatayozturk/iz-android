package org.iz.navigation.watch

import org.iz.navigation.wearprotocol.WearSnapshot
import org.junit.Assert.*
import org.junit.Test

class WatchSurfaceAcceptanceTest {
    @Test fun newestSelectedPhoneSnapshotIsAccepted() {
        assertTrue(WatchSurfaceAcceptance.accept("a", "a", 4, 4, null, WearSnapshot(1000), 1000))
    }
    @Test fun olderSnapshotCannotReplaceEvenExpiredCurrentSnapshot() {
        assertFalse(WatchSurfaceAcceptance.accept("a", "a", 4, 4, WearSnapshot(2000), WearSnapshot(1000), 100_000))
    }
    @Test fun otherPhoneAndVersionAreRejected() {
        assertFalse(WatchSurfaceAcceptance.accept("b", "a", 4, 4, null, WearSnapshot(1000), 1000))
        assertFalse(WatchSurfaceAcceptance.accept("a", "a", 3, 4, null, WearSnapshot(1000), 1000))
    }
    @Test fun futureSnapshotIsRejected() {
        assertFalse(WatchSurfaceAcceptance.accept("a", "a", 4, 4, null, WearSnapshot(90_000), 1000))
    }
}
