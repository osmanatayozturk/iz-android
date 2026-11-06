package org.iz.navigation.ui.history

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.mutableStateOf
import org.iz.navigation.ui.PhoneVoiceSettingsOwner
import org.iz.navigation.ui.backup.BackupExportFlow

internal sealed interface UiResultPayload {
    data class Photos(val journeyId: String?, val visitId: String?, val modalRevision: Long) : UiResultPayload
    data class Camera(val uri: String, val journeyId: String?, val visitId: String?) : UiResultPayload
    data class TrackExport(val id: String) : UiResultPayload
    data class Voice(val owner: PhoneVoiceSettingsOwner) : UiResultPayload
    data class Permissions(val result: UiPermissionReturn) : UiResultPayload
}

/** Request fences and payloads remain transient. Backup saves only its outcome marker. */
internal class UiResultViewModel(savedState: SavedStateHandle) : ViewModel() {
    val requests = UiResultRequests()
    val registrations = UiOwnerRegistrations()
    val deliveredCommunityTargets = mutableSetOf<String>()
    val backupBusy = mutableStateOf(false)
    val backupExport = BackupExportFlow<Uri>(viewModelScope, savedState)
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
