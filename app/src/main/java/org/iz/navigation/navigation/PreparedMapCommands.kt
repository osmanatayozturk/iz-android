package org.iz.navigation.navigation

import org.iz.navigation.offline.OfflinePack

/** Same facade actions used by the Android owner and the real pure controller fixture. */
internal interface PreparedMapActions {
    val pack: OfflinePack?
    fun download(): Boolean
    fun resume(): Boolean
    fun pause(): Boolean
    fun cancel(): Boolean
    fun delete(): Boolean
}
internal class PreparedMapCommands(private val selections: PreparedSelectionStore, private val actions: PreparedMapActions) {
    fun dispatch(state: PreparedRouteState, command: String): Boolean {
        if (command == "download" || command == "resume") selections.requireForTransfer(state, actions.pack)
        if (command == "delete") check(!state.inUse) { "Kullanılan harita alanı kaldırılamaz." }
        return when (command) {
            "download" -> actions.download(); "pause" -> actions.pause(); "resume" -> actions.resume()
            "cancel" -> actions.cancel(); "delete" -> actions.delete(); else -> false
        }
    }
}
