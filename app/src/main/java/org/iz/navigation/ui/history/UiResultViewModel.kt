package org.iz.navigation.ui.history

import androidx.lifecycle.ViewModel
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.mutableStateOf
import org.iz.navigation.ui.PhoneVoiceSettingsOwner

internal sealed interface UiResultPayload {
    data class Photos(val journeyId: String?, val visitId: String?, val modalRevision: Long) : UiResultPayload
    data class Camera(val uri: String, val journeyId: String?, val visitId: String?) : UiResultPayload
    data class Backup(val includeHealth: Boolean) : UiResultPayload
    data class TrackExport(val id: String) : UiResultPayload
    data class Voice(val owner: PhoneVoiceSettingsOwner) : UiResultPayload
    data class Permissions(val result: UiPermissionReturn) : UiResultPayload
}

/** Activity UI lifetime only. Neither request fences nor payloads use SavedState/History/database. */
internal class UiResultViewModel : ViewModel() {
    val requests = UiResultRequests()
    val deliveredCommunityTargets = mutableSetOf<String>()
    val backupBusy = mutableStateOf(false)
    val libraryBusy = mutableStateOf(false)
    val gpxStarting = mutableStateOf(false)
    val preparingGpx = mutableStateOf(false)
    val formBusy = mutableStateOf(false)
    val contributionBusy = mutableStateOf(false)
    private data class Payload(val owner: UiAsyncOwner, val value: UiResultPayload)
    private val payloads = mutableMapOf<String, Payload>()
    fun putPayload(launcher: String, owner: UiAsyncOwner, payload: UiResultPayload) { payloads[launcher] = Payload(owner, payload) }
    fun takePayload(launcher: String, owner: UiAsyncOwner?): UiResultPayload? {
        val payload = payloads[launcher] ?: return null
        if (payload.owner !== owner) return null
        payloads.remove(launcher)
        return payload.value
    }
}

val LocalUiVoiceSettingsAction = staticCompositionLocalOf<(() -> Unit)?> { null }

internal data class UiPermissionReturn(val onResult: () -> Unit, val onStale: () -> Unit)
internal val LocalUiDirectionsPermissions = staticCompositionLocalOf<((Array<String>, UiPermissionReturn) -> Boolean)?> { null }
