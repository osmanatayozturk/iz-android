package org.iz.navigation.ui

import org.iz.navigation.ui.history.UiAsyncOwner
import org.junit.Assert.*
import org.junit.Test

class PhoneVoiceSettingsOwnerTest {
    @Test fun unchangedExplicitReturnStillRequiresCurrentUiOwner() {
        val owner = PhoneVoiceSettingsOwner(UiAsyncOwner.capture("map", null), "session", 7, true)
        assertTrue(owner.matches("session", 7, true, uiMatches = true))
        assertFalse(owner.matches("session", 7, true, uiMatches = false))
    }
    @Test fun sameMapEntryCannotRetryVoiceAfterAutoReplacesSessionRouteOrGuidance() {
        val owner = PhoneVoiceSettingsOwner(UiAsyncOwner.capture("map", null), "session", 7, true)
        assertFalse("Auto replaced the session while the same map entry/settings remain open",
            owner.matches("replacement", 7, true, uiMatches = true))
        assertFalse("Same session now has a different route revision", owner.matches("session", 8, true, uiMatches = true))
        assertFalse("Guidance ended while settings were open", owner.matches("session", 7, false, uiMatches = true))
    }
}
