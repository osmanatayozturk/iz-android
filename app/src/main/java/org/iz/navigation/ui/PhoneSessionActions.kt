package org.iz.navigation.ui

import org.iz.navigation.navigation.NavigationState
import org.iz.navigation.navigation.NavigationStopSnapshot

internal enum class PhoneSessionEnd { GUIDANCE, RECORDING, ALL }

/** The same token is shown in the confirmation and checked again under the core command lock. */
internal data class PhoneSessionEndRequest(val action: PhoneSessionEnd, val snapshot: NavigationStopSnapshot) {
    fun matches(nav: NavigationState): Boolean = snapshot == NavigationStopSnapshot.from(nav)
}

internal fun phoneSessionEndRequest(action: PhoneSessionEnd, nav: NavigationState): PhoneSessionEndRequest? {
    val snapshot = NavigationStopSnapshot.from(nav) ?: return null
    if (action == PhoneSessionEnd.GUIDANCE && !snapshot.guidance ||
        action == PhoneSessionEnd.RECORDING && (!snapshot.recording || snapshot.journeyId == null || snapshot.simulation)) return null
    return PhoneSessionEndRequest(action, snapshot)
}

internal suspend fun dispatchPhoneSessionEnd(
    request: PhoneSessionEndRequest,
    current: NavigationState,
    locked: Boolean,
    stopGuidance: suspend (NavigationStopSnapshot) -> Boolean,
    stopRecording: suspend (NavigationStopSnapshot) -> Boolean,
    finish: suspend (NavigationStopSnapshot) -> Boolean,
): Boolean {
    if (locked || !request.matches(current)) return false
    return when (request.action) {
        PhoneSessionEnd.GUIDANCE -> stopGuidance(request.snapshot)
        PhoneSessionEnd.RECORDING -> stopRecording(request.snapshot)
        PhoneSessionEnd.ALL -> finish(request.snapshot)
    }
}

internal fun openPhoneDirectionsPanel(nav: NavigationState, alreadyOpen: Boolean, prepareIdle: () -> Unit, open: () -> Unit) {
    val active = nav.sessionId != null || nav.guidance || nav.recording || nav.locationActive
    if (!alreadyOpen && !active) prepareIdle()
    open()
}

internal val PhoneSessionEnd.label: String get() = when (this) {
    PhoneSessionEnd.GUIDANCE -> "Yol tarifini bitir"
    PhoneSessionEnd.RECORDING -> "Yolculuk kaydını bitir"
    PhoneSessionEnd.ALL -> "Yolculuğu tamamen bitir"
}

internal val PhoneSessionEndRequest.title: String get() = when (action) {
    PhoneSessionEnd.GUIDANCE -> "Yol tarifi bitsin mi?"
    PhoneSessionEnd.RECORDING -> "Yolculuk kaydı bitsin mi?"
    PhoneSessionEnd.ALL -> "Yolculuk tamamen bitsin mi?"
}

internal val PhoneSessionEndRequest.explanation: String get() = buildList {
    when (action) {
        PhoneSessionEnd.GUIDANCE -> {
            add("Yol tarifi bitecek.")
            if (snapshot.recording) add("Yolculuk kaydı devam edecek.")
            if (snapshot.sharingLocation) add("Grup paylaşımı devam edecek.")
        }
        PhoneSessionEnd.RECORDING -> {
            add("Yolculuk kaydı bitecek.")
            if (snapshot.guidance) add("Yol tarifi devam edecek.")
            if (snapshot.sharingLocation) add("Grup paylaşımı devam edecek.")
        }
        PhoneSessionEnd.ALL -> {
            if (snapshot.guidance) add("Yol tarifi bitecek.")
            if (snapshot.recording) add("Yolculuk kaydı bitecek.")
            if (snapshot.sharingLocation) add("Grup paylaşımı bitecek.")
            if (isEmpty()) add("Yolculuk oturumu sonlandırılacak.")
        }
    }
}.joinToString(" ")
