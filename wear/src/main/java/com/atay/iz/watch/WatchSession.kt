package com.atay.iz.watch

import com.atay.iz.wearprotocol.*

/** Commands are never optimistic: phone snapshots and correlated acknowledgements are authoritative. */
internal data class WatchUiState(
    val phoneId: String? = null,
    val phoneName: String? = null,
    val snapshot: WearSnapshot? = null,
    val pending: WearCommand? = null,
    val needsPhone: Boolean = false,
    val message: String? = null,
    val messageIsConnectionIssue: Boolean = false,
    val snapshotAfter: Long? = null,
    val phoneVersion: Int = 1,
    val pendingVersion: Int = 1,
) {
    fun fresh(now: Long): Boolean = phoneId != null && snapshot?.let { WearProtocol.isFreshSnapshot(it, now) } == true
    fun supports(mode: WearMode): Boolean = mode != WearMode.RUN || phoneVersion >= 2
    fun canStart(now: Long, mode: WearMode = WearMode.WALK): Boolean = supports(mode) && fresh(now) && snapshot?.recording == false && snapshot.journeyId == null && pending == null
    fun canStop(id: String, now: Long): Boolean = fresh(now) && snapshot?.recording == true && snapshot.journeyId == id && pending == null

    fun phone(id: String?, name: String?, version: Int = 1): WatchUiState {
        if (id == phoneId) return copy(phoneName = name, phoneVersion = version,
            snapshot = snapshot.takeIf { version == phoneVersion })
        return copy(phoneId = id, phoneName = name, phoneVersion = version, snapshot = if (id == null) snapshot else null,
            pending = null, needsPhone = false, snapshotAfter = null, messageIsConnectionIssue = id == null,
            message = if (id == null) "Telefon bağlantısı kesildi. İstekler çevrimdışı bekletilmez." else null)
    }

    fun receive(source: String, value: WearSnapshot, now: Long): WatchUiState {
        if (source != phoneId || !WearProtocol.isFreshSnapshot(value, now)) return this
        val prior = snapshot
        if (prior != null && WearProtocol.isFreshSnapshot(prior, now) && prior.generatedAt > value.generatedAt) return this
        val barrier = snapshotAfter
        if (barrier != null && value.generatedAt <= barrier && WearProtocol.isFreshSnapshot(WearSnapshot(generatedAt = barrier), now)) return this
        return copy(snapshot = value, snapshotAfter = null, message = if (messageIsConnectionIssue) null else message, messageIsConnectionIssue = false)
    }

    fun begin(command: WearCommand, now: Long): WatchUiState {
        val allowed = when (command.action) {
            WearAction.START -> command.mode?.let { canStart(now, it) } == true
            WearAction.STOP -> command.journeyId?.let { canStop(it, now) } == true
            WearAction.REFRESH -> false
        }
        return if (allowed && WearProtocol.isFreshCommand(command, now)) copy(pending = command, pendingVersion = phoneVersion,
            needsPhone = false, message = null, messageIsConnectionIssue = false) else this
    }

    fun result(source: String, result: WearResult): WatchUiState {
        val command = pending ?: return this
        if (source != phoneId || result.commandId != command.id) return this
        return when (result.code) {
            WearResultCode.NEEDS_PHONE -> if (command.action == WearAction.START)
                copy(needsPhone = true, message = result.message) else this
            WearResultCode.STARTED -> if (command.action == WearAction.START)
                copy(pending = null, needsPhone = false, snapshot = null, snapshotAfter = snapshot?.generatedAt, message = result.message) else this
            WearResultCode.STOPPED -> if (command.action == WearAction.STOP)
                copy(pending = null, needsPhone = false, snapshot = null, snapshotAfter = snapshot?.generatedAt, message = result.message) else this
            WearResultCode.REJECTED, WearResultCode.ERROR ->
                copy(pending = null, needsPhone = false, snapshot = null, snapshotAfter = snapshot?.generatedAt, message = result.message)
            WearResultCode.REFRESHED -> this
        }
    }

    fun failed(commandId: String, explanation: String): WatchUiState =
        if (pending?.id == commandId) copy(pending = null, needsPhone = false, snapshot = null, snapshotAfter = snapshot?.generatedAt, message = explanation) else this

    /** Retry the same live command to retrieve its idempotent phone result; never queue a disconnected request. */
    fun retryCommand(now: Long): WearCommand? = pending?.takeIf { phoneId != null && WearProtocol.isFreshCommand(it, now) }

    fun tick(now: Long): WatchUiState = if (pending?.let { !WearProtocol.isFreshCommand(it, now) } == true) {
        copy(pending = null, needsPhone = false, snapshot = null, message = "İstek süresi doldu. Telefon durumunu yenile.")
    } else this
}

internal fun WearMode.label(): String = when (this) {
    WearMode.CAR -> "Araba"
    WearMode.MOTORCYCLE -> "Motosiklet"
    WearMode.BICYCLE -> "Bisiklet"
    WearMode.WALK -> "Yürüyüş"
    WearMode.PASSENGER -> "Yolcu"
    WearMode.RUN -> "Koşu"
}
