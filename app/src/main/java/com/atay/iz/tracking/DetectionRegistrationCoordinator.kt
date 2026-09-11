package com.atay.iz.tracking

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

enum class DetectionRegistrationState { OFF, REGISTERING, READY, PERMISSION_REQUIRED, RETRY_PENDING }

internal interface DetectionRegistrationStore {
    var requestedEnabled: Boolean
    var registrationState: DetectionRegistrationState
    var registrationError: String?
    var lastRegistrationAt: Long
    var lastRegistrationAttemptAt: Long
}

internal interface DetectionRegistrationGateway {
    suspend fun register()
    suspend fun unregister()
}

internal interface DetectionRetryScheduler {
    fun schedule()
    fun cancel()
}

/** One process-wide coordinator serializes register/remove calls while keeping user intent separate. */
internal class DetectionRegistrationCoordinator(
    private val store: DetectionRegistrationStore,
    private val gateway: DetectionRegistrationGateway,
    private val retries: DetectionRetryScheduler,
    private val scope: CoroutineScope,
    private val permissionError: () -> String?,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val operations = Mutex()
    private val intentLock = Any()
    private var generation = 0L
    private var registeredInThisProcess = false

    fun enable(onResult: (String?) -> Unit = {}) {
        val token = synchronized(intentLock) { store.requestedEnabled = true; ++generation }
        reconcile(token, force = true, onResult)
    }

    fun restore(onResult: (String?) -> Unit = {}) {
        val token = synchronized(intentLock) { generation }
        reconcile(token, force = false, onResult)
    }

    fun disable() {
        val token = synchronized(intentLock) {
            store.requestedEnabled = false
            store.registrationState = DetectionRegistrationState.OFF
            store.registrationError = null
            registeredInThisProcess = false
            retries.cancel()
            ++generation
        }
        scope.launch {
            operations.withLock {
                if (!isCurrent(token) || store.requestedEnabled) return@withLock
                try {
                    withTimeout(REGISTRATION_TIMEOUT_MS) { gateway.unregister() }
                } catch (cancelled: CancellationException) {
                    if (cancelled !is TimeoutCancellationException) throw cancelled
                } catch (_: Exception) {
                    // The requested flag is already off, so the receiver rejects every event.
                }
            }
        }
    }

    private fun isCurrent(token: Long): Boolean = synchronized(intentLock) { generation == token }

    private fun reconcile(token: Long, force: Boolean, onResult: (String?) -> Unit) {
        scope.launch {
            val result = operations.withLock {
                if (!isCurrent(token)) return@withLock SUPERSEDED
                if (!store.requestedEnabled) return@withLock null
                val issue = permissionError()
                if (issue != null) {
                    synchronized(intentLock) {
                        if (generation == token) {
                            registeredInThisProcess = false
                            store.registrationState = DetectionRegistrationState.PERMISSION_REQUIRED
                            store.registrationError = issue
                            retries.cancel()
                        }
                    }
                    return@withLock issue
                }
                if (!force && registeredInThisProcess && store.registrationState == DetectionRegistrationState.READY &&
                    clock() - store.lastRegistrationAt in 0 until RESUME_REFRESH_MS) return@withLock null
                synchronized(intentLock) {
                    if (generation == token) {
                        store.registrationState = DetectionRegistrationState.REGISTERING
                        store.lastRegistrationAttemptAt = clock()
                        store.registrationError = null
                    }
                }
                val failure = try {
                    withTimeout(REGISTRATION_TIMEOUT_MS) { gateway.register() }
                    null
                } catch (error: TimeoutCancellationException) {
                    error
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    error
                }
                synchronized(intentLock) {
                    if (generation != token || !store.requestedEnabled) return@synchronized SUPERSEDED
                    val permissionIssue = permissionError()
                    if (failure == null && permissionIssue == null) {
                        registeredInThisProcess = true
                        store.registrationState = DetectionRegistrationState.READY
                        store.lastRegistrationAt = clock()
                        store.registrationError = null
                        retries.cancel()
                        null
                    } else {
                        registeredInThisProcess = false
                        val message = permissionIssue ?: "Hareket algılama bağlantısı kurulamadı; yeniden denenecek."
                        store.registrationError = message
                        store.registrationState = if (permissionIssue != null) DetectionRegistrationState.PERMISSION_REQUIRED
                            else DetectionRegistrationState.RETRY_PENDING
                        if (permissionIssue != null) retries.cancel() else retries.schedule()
                        message
                    }
                }
            }
            onResult(result)
        }
    }

    companion object {
        private const val REGISTRATION_TIMEOUT_MS = 8_000L
        private const val RESUME_REFRESH_MS = 60_000L
        private const val SUPERSEDED = "Hareket algılama isteği değişti."
    }
}
