package com.atay.iz.group

import com.atay.iz.navigation.NavigationFix
import com.atay.iz.navigation.NavigationState
import kotlinx.coroutines.flow.StateFlow
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

internal interface GroupBackend {
    val configured: Boolean
    val hasIdentity: Boolean
    suspend fun command(action: String, body: JSONObject = JSONObject()): JSONObject
    suspend fun token(): String
    fun openSocket(listener: WebSocketListener): WebSocket
}
internal interface GroupNavigation {
    val state: StateFlow<NavigationState>
    suspend fun setSharingLocation(enabled: Boolean, expectedSessionId: String? = null)
    suspend fun currentLocation(): NavigationFix
}
