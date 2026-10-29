package org.iz.navigation.ui.history

import org.junit.Assert.*
import org.junit.Test

class UiResultRequestsTest {
    @Test fun secondLaunchCannotOverwriteFirstRequestWhenItsUiOwnerWasReplaced() {
        val requests = UiResultRequests()
        val first = UiAsyncOwner.capture("entry-a", null)
        val replacement = UiAsyncOwner.capture("entry-b", null)
        assertTrue(requests.begin("gallery", first))
        assertFalse("An unsettled A must fence B even after its owner disappeared", requests.begin("gallery", replacement))
        assertSame("A result must still consume A, never latest B", first, requests.settle("gallery"))
        assertTrue(requests.begin("gallery", replacement)); assertSame(replacement, requests.settle("gallery"))
    }
    @Test fun independentLaunchersAndOneTimeSettlementDoNotShareResults() {
        val requests = UiResultRequests()
        val photo = UiAsyncOwner.capture("photo", null); val backup = UiAsyncOwner.capture("backup", null)
        assertTrue(requests.begin("camera", photo)); assertTrue(requests.begin("backup", backup))
        assertSame(photo, requests.settle("camera")); assertNull(requests.settle("camera"))
        assertSame(backup, requests.settle("backup"))
    }
    @Test fun lateFailedLaunchCleanupCannotClearAnAlreadyReplacementRequest() {
        val requests = UiResultRequests()
        val old = UiAsyncOwner.capture("page", null); val next = UiAsyncOwner.capture("page", null)
        requests.begin("file", old); requests.settle("file"); requests.begin("file", next)
        requests.abort("file", old)
        assertSame(next, requests.settle("file"))
    }
    @Test fun requestRegistryEpochIsStableForItsOwnerAndFreshForReplacementProcessOwner() {
        val requests = UiResultRequests(); val epoch = requests.epoch
        requests.begin("file", UiAsyncOwner.capture("page", null)); requests.settle("file")
        assertEquals(epoch, requests.epoch)
        assertNotEquals(epoch, UiResultRequests().epoch)
    }
}
