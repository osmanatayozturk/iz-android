package org.iz.navigation.ui

import org.iz.navigation.ui.history.UiAsyncOwner

/** Transient explicit-settings launch; never saved or restored. */
internal data class PhoneVoiceSettingsOwner(val ui: UiAsyncOwner, val sessionId: String?,
    val routeRevision: Long, val guidance: Boolean) {
    // A different Auto/phone session or route cannot consume an earlier settings return.
    fun matches(currentSessionId: String?, currentRouteRevision: Long, currentGuidance: Boolean, uiMatches: Boolean) = uiMatches &&
        sessionId == currentSessionId && routeRevision == currentRouteRevision && guidance == currentGuidance
}
