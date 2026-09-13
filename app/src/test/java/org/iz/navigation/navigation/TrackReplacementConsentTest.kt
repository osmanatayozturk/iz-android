package org.iz.navigation.navigation

import org.junit.Assert.*
import org.junit.Test

class TrackReplacementConsentTest {
    @Test fun openingAPreviewDoesNotAuthorizeReplacingAStartedTrack() {
        assertThrows(IllegalStateException::class.java) { TrackReplacementConsent().consume("session-a") }
    }
    @Test fun approvalIsConsumedExactlyOnceForTheConfirmedSession() {
        val consent = TrackReplacementConsent()
        consent.approve("session-a")
        assertTrue(consent.consume("session-a"))
        assertThrows(IllegalStateException::class.java) { consent.consume("session-a") }
    }
    @Test fun newSessionCannotInheritAnOldDialogsApproval() {
        val consent = TrackReplacementConsent()
        consent.approve("session-a")
        assertThrows(IllegalStateException::class.java) { consent.consume("session-b") }
        assertThrows(IllegalStateException::class.java) { consent.consume("session-a") }
    }
    @Test fun aNewStartWithoutConfirmationClearsAnyEarlierApproval() {
        val consent = TrackReplacementConsent()
        consent.approve("session-a")
        consent.approve(null)
        assertThrows(IllegalStateException::class.java) { consent.consume("session-a") }
    }
    @Test fun finishedTrackNeedsNoReplacementAndDoesNotRetainItsApproval() {
        val consent = TrackReplacementConsent()
        consent.approve("session-a")
        assertFalse(consent.consume(null))
        assertThrows(IllegalStateException::class.java) { consent.consume("session-a") }
    }
    @Test fun ordinaryNavigationWithoutGpxNeedsNoConsent() {
        assertFalse(TrackReplacementConsent().consume(null))
    }
}
