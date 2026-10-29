package org.iz.navigation.ui.history

import java.util.UUID

/** Existing feature-ViewModel lifetime only. No Saver, URI, permission token, queue or backend action. */
class UiResultRequests {
    val epoch: String = UUID.randomUUID().toString()
    private val pending = mutableMapOf<String, UiAsyncOwner>()
    // A remains the only owner until its result/failure settles; a later request is never queued.
    fun begin(launcher: String, owner: UiAsyncOwner): Boolean {
        if (pending.containsKey(launcher)) return false
        pending[launcher] = owner
        return true
    }
    fun settle(launcher: String): UiAsyncOwner? = pending.remove(launcher)
    fun abort(launcher: String, owner: UiAsyncOwner) { if (pending[launcher] === owner) pending.remove(launcher) }
}
