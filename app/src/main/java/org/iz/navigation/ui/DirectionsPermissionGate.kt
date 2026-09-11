package org.iz.navigation.ui

internal enum class DirectionsAction { CURRENT_ORIGIN, PREVIEW, START, FREE_DRIVE }

/** A permission result belongs to one entry and one unchanged plan, and is consumed once. */
internal class DirectionsPermissionGate {
    private data class Pending(val action: DirectionsAction, val entry: Long, val revision: Long)
    private var pending: Pending? = null

    fun request(action: DirectionsAction, entry: Long, revision: Long): Boolean {
        if (pending != null) return false
        pending = Pending(action, entry, revision)
        return true
    }

    fun consume(entry: Long, revision: Long, granted: Boolean): DirectionsAction? {
        val request = pending ?: return null
        pending = null
        return request.action.takeIf { granted && request.entry == entry && request.revision == revision }
    }

    fun isPendingFor(entry: Long, revision: Long): Boolean = pending?.let { it.entry == entry && it.revision == revision } == true
}
