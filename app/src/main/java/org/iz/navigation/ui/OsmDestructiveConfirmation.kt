package org.iz.navigation.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.iz.navigation.ui.history.UiHistory

/** Opaque live identity only: never serialized or logged, no credential field is copied. */
internal class OsmConfirmationOwner(
    private val entry: String, private val account: Long?, private val cache: String?, private val session: Any?,
    private val history: UiHistory?,
) {
    // History can prune/replace an entry before Compose disposes its old callbacks.
    fun isCurrentEntry(): Boolean = if (history == null) entry == "standalone" else history.current.key == entry
    fun matches(other: OsmConfirmationOwner) = isCurrentEntry() && other.isCurrentEntry() &&
        entry == other.entry && account == other.account &&
        cache == other.cache && session === other.session
}
internal class OsmDestructiveRequest<T>(val target: T, internal val owner: OsmConfirmationOwner)

/** Main-thread, transient consent. No mutation callback is retained by an open dialog. */
internal class OsmDestructiveConfirmation<T>(private val eligible: (T) -> Boolean) {
    private val mutablePending = MutableStateFlow<OsmDestructiveRequest<T>?>(null)
    val pending = mutablePending.asStateFlow()
    private var epoch = 0L

    fun request(target: T, owner: OsmConfirmationOwner, busy: Boolean): OsmDestructiveRequest<T>? {
        if (busy || !owner.isCurrentEntry() || !eligible(target) || pending.value != null) return null
        return OsmDestructiveRequest(target, owner).also { mutablePending.value = it }
    }
    fun dismiss(request: OsmDestructiveRequest<T>) { if (mutablePending.value === request) mutablePending.value = null }
    fun invalidate() { epoch++; mutablePending.value = null }

    fun take(request: OsmDestructiveRequest<T>, owner: OsmConfirmationOwner, current: T?, busy: Boolean): Permit<T>? {
        if (mutablePending.value !== request || busy) return null
        // Consume before calling the exit guard or feature code, including when the target is stale.
        mutablePending.value = null
        if (!request.owner.matches(owner) || current != request.target || !eligible(request.target)) return null
        val issuedEpoch = epoch
        return Permit(request.target, request.owner, eligible) { epoch == issuedEpoch }
    }

    internal class Permit<T>(private val target: T, private val owner: OsmConfirmationOwner,
        private val eligible: (T) -> Boolean, private val active: () -> Boolean) {
        private var spent = false
        fun applyIfCurrent(owner: OsmConfirmationOwner, current: T?, busy: Boolean, action: (T) -> Unit): Boolean {
            if (spent) return false
            spent = true
            if (busy || !active() || !this.owner.matches(owner) || current != target || !eligible(target)) return false
            action(target)
            return true
        }
    }
}
