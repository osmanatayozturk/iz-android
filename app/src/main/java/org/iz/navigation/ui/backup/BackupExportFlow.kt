package org.iz.navigation.ui.backup

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.iz.navigation.data.BackupCopyException
import org.iz.navigation.data.BackupCopyPhase
import org.iz.navigation.data.BackupPreparationCancelledException
import org.iz.navigation.data.BackupPreparationException
import org.iz.navigation.data.PreparedDiaryBackup
import org.iz.navigation.ui.history.UiAsyncOwner
import java.util.UUID

internal enum class BackupExportPhase { IDLE, PREPARING, READY, PICKER, SAVING, CLEANING }

internal enum class BackupExportResult {
    SAVED, CANCELLED, INTERRUPTED, PREPARATION_FAILED, PICKER_FAILED, DESTINATION_UNCONFIRMED, WRITE_FAILED, VERIFY_FAILED
}

internal data class BackupExportNotice(
    val result: BackupExportResult? = null,
    val destinationMayRemain: Boolean = false,
    val privateCleanupFailed: Boolean = false,
    val preparationDetail: String? = null,
)

internal interface BackupExportOperations<Target : Any> {
    suspend fun prepareExport(includeHealth: Boolean): PreparedDiaryBackup
    suspend fun inspectDestination(target: Target, title: String): (suspend () -> Boolean)?
    suspend fun savePrepared(prepared: PreparedDiaryBackup, target: Target)
    suspend fun discardPrepared(prepared: PreparedDiaryBackup): Boolean
    suspend fun cleanupAbandonedExports(): Boolean
}

/** Activity ViewModel lifetime. Only an outcome marker survives process death. */
internal class BackupExportFlow<Target : Any>(private val scope: CoroutineScope, private val savedState: SavedStateHandle) {
    private class Request<Target : Any>(val owner: UiAsyncOwner, val includeHealth: Boolean) {
        // A random title binds the returned document to this launch even on pickers that offer replacement.
        val title = "iz-yedek-${UUID.randomUUID()}.zip"
        val selection = CompletableDeferred<Selection>()
        var target: Target? = null
        var job: Job? = null
        var ownerLeft = false
    }

    private data class Selection(val accepted: Boolean, val launchFailed: Boolean = false)

    var phase by mutableStateOf(BackupExportPhase.IDLE)
        private set
    val busy get() = phase != BackupExportPhase.IDLE
    private var active: Request<Target>? = null
    private var startup: Job? = null
    private val interrupted = savedState.get<String>(MARKER)
    private val messages = Channel<BackupExportNotice>(Channel.BUFFERED)
    val notices = messages.receiveAsFlow()

    fun initialize(backup: BackupExportOperations<Target>) {
        if (startup != null) return
        startup = scope.launch {
            val privateClean = cleanup { backup.cleanupAbandonedExports() }
            if (active == null) savedState.remove<String>(MARKER)
            if (interrupted != null || !privateClean) {
                messages.trySend(BackupExportNotice(
                    result = when (interrupted) {
                        null -> null
                        VERIFIED -> BackupExportResult.SAVED
                        else -> BackupExportResult.INTERRUPTED
                    },
                    destinationMayRemain = interrupted == DESTINATION,
                    privateCleanupFailed = !privateClean,
                ))
            }
        }
    }

    fun start(backup: BackupExportOperations<Target>, owner: UiAsyncOwner, includeHealth: Boolean): Boolean {
        if (active != null) return false
        initialize(backup)
        val request = Request<Target>(owner, includeHealth)
        active = request
        update(BackupExportPhase.PREPARING)
        request.job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var prepared: PreparedDiaryBackup? = null
            var removeDestination: (suspend () -> Boolean)? = null
            var result = BackupExportResult.PREPARATION_FAILED
            var detail: String? = null
            var preparationCleanupFailed = false
            try {
                startup?.join()
                val stage = backup.prepareExport(request.includeHealth)
                prepared = stage
                currentCoroutineContext().ensureActive()
                update(BackupExportPhase.READY)
                val selection = request.selection.await()
                val target = request.target
                result = when {
                    selection.launchFailed -> BackupExportResult.PICKER_FAILED
                    !selection.accepted || target == null -> BackupExportResult.CANCELLED
                    else -> BackupExportResult.DESTINATION_UNCONFIRMED
                }
                if (target != null) {
                    removeDestination = backup.inspectDestination(target, request.title)
                    if (selection.accepted && removeDestination != null) {
                        currentCoroutineContext().ensureActive()
                        result = BackupExportResult.WRITE_FAILED
                        backup.savePrepared(stage, target)
                        result = BackupExportResult.SAVED
                    }
                }
            } catch (error: BackupPreparationCancelledException) {
                result = BackupExportResult.CANCELLED
                preparationCleanupFailed = error.cleanupFailed
                throw error
            } catch (error: CancellationException) {
                result = BackupExportResult.CANCELLED
                throw error
            } catch (error: BackupPreparationException) {
                preparationCleanupFailed = error.cleanupFailed
                detail = error.limitMessage
            } catch (error: BackupCopyException) {
                result = when (error.phase) {
                    BackupCopyPhase.WRITE -> BackupExportResult.WRITE_FAILED
                    BackupCopyPhase.VERIFY -> BackupExportResult.VERIFY_FAILED
                }
            } catch (_: Exception) {
                // The current phase supplies the safe user-facing outcome.
            } finally {
                withContext(NonCancellable) {
                    val target = request.target
                    val verified = result == BackupExportResult.SAVED
                    update(BackupExportPhase.CLEANING, mayHaveDocument = target != null, verified = verified)
                    val destinationClean = verified || target == null || (removeDestination?.let { cleanup(it) } == true)
                    val privateClean = prepared?.let { cleanup { backup.discardPrepared(it) } } ?: true
                    active = null
                    update(BackupExportPhase.IDLE)
                    messages.trySend(BackupExportNotice(result, !destinationClean, preparationCleanupFailed || !privateClean, detail))
                }
            }
        }
        return true
    }

    fun openPicker(launch: (UiAsyncOwner, String) -> Boolean) {
        val request = active ?: return
        if (phase != BackupExportPhase.READY) return
        update(BackupExportPhase.PICKER, mayHaveDocument = true)
        try {
            if (!launch(request.owner, request.title)) {
                request.selection.complete(Selection(accepted = false))
            }
        } catch (_: Exception) {
            request.selection.complete(Selection(accepted = false, launchFailed = true))
        }
    }

    fun onPickerResult(owner: UiAsyncOwner?, target: Target?, ownerStillCurrent: Boolean) {
        val request = active
        if (request == null || request.owner !== owner || phase != BackupExportPhase.PICKER) {
            if (target != null) messages.trySend(BackupExportNotice(destinationMayRemain = true))
            return
        }
        request.target = target
        val accepted = !request.ownerLeft && ownerStillCurrent && target != null
        update(
            if (accepted) BackupExportPhase.SAVING else BackupExportPhase.CLEANING,
            mayHaveDocument = target != null,
        )
        request.selection.complete(Selection(accepted))
    }

    fun cancelIfOwnerLeft(entryKey: String) {
        val request = active ?: return
        if (request.owner.entryKey == entryKey) return
        // Keep this request registered until its picker returns, but retire its right to write.
        if (phase == BackupExportPhase.PICKER) request.ownerLeft = true else cancel()
    }

    fun cancel() {
        if (phase !in cancellablePhases) return
        val request = active ?: return
        update(BackupExportPhase.CLEANING, mayHaveDocument = request.target != null)
        request.job?.cancel()
    }

    private fun update(next: BackupExportPhase, mayHaveDocument: Boolean = false, verified: Boolean = false) {
        phase = next
        if (next == BackupExportPhase.IDLE) savedState.remove<String>(MARKER)
        else savedState[MARKER] = when {
            verified -> VERIFIED
            mayHaveDocument -> DESTINATION
            else -> PRIVATE
        }
    }

    private suspend fun cleanup(action: suspend () -> Boolean): Boolean = try {
        action()
    } catch (error: CancellationException) {
        currentCoroutineContext().ensureActive()
        false
    } catch (_: Exception) {
        false
    }

    companion object {
        val cancellablePhases = setOf(BackupExportPhase.PREPARING, BackupExportPhase.READY, BackupExportPhase.SAVING)
        private const val MARKER = "backupExportInterrupted"
        private const val PRIVATE = "private"
        private const val DESTINATION = "destination"
        private const val VERIFIED = "verified"
    }
}
