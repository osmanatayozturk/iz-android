package org.iz.navigation.wear

import android.content.Context
import org.iz.navigation.data.*
import org.iz.navigation.tracking.TrackingService
import org.iz.navigation.wearprotocol.*
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import java.util.UUID

data class WatchHealthLinkState(val deviceName: String = "", val lastSeenAt: Long? = null,
    val onBody: Boolean? = null, val capturing: Boolean = false, val buffered: Int = 0)

object WatchHealthConnection {
    internal val mutableState = MutableStateFlow(WatchHealthLinkState())
    val state = mutableState.asStateFlow()
}

/** The Data Layer verifies application identity; each persisted session is additionally bound to node + install ID. */
internal class WatchHealthPhoneBridge(private val context: Context) {
    private val mutex = Mutex()
    private val health = WatchHealthRepository(context)
    private val diary = DiaryRepository(context)
    suspend fun receive(source: String, bytes: ByteArray) = mutex.withLock {
        val message = WatchHealthProtocol.decode(bytes) ?: return@withLock
        if (source.isBlank() || source.length > 100 || source.any { it.isWhitespace() || it.isISOControl() }) return@withLock
        val nodes = Wearable.getCapabilityClient(context).getCapability(WearProtocol.V4_WATCH_CAPABILITY, CapabilityClient.FILTER_REACHABLE).await().nodes
        if (nodes.none { it.id == source }) return@withLock
        val now = System.currentTimeMillis()
        val response: HealthMessage = when (message) {
            is HealthHello -> {
                val trusted = "$source:${message.watchId}"
                if (trusted.length > 200) return@withLock
                WatchHealthConnection.mutableState.value = WatchHealthLinkState(message.deviceName, now, message.onBody, message.capturing, message.buffered)
                val trip = diary.activeJourney()?.takeIf { it.status == JourneyStatus.CONFIRMED && !it.interrupted && it.startedAt <= now &&
                    TrackingService.isRunning && TrackingService.runningJourneyId == it.id }
                var session = if (trip == null) null else message.sessionId?.let { health.session(it) }?.takeIf {
                    it.acceptsUploads && it.watchId == trusted && it.journeyId == trip.id && it.createdAt <= now }
                if (trip != null && session == null) {
                    val proposed = WatchHealthSession(UUID.randomUUID().toString(), trip.id, trusted, message.deviceName, now)
                    if (health.register(proposed)) session = proposed
                }
                HealthHelloReply(message.requestId, System.currentTimeMillis(), session?.id, session?.journeyId, session?.createdAt)
            }
            is HealthBatch -> {
                val trusted = "$source:${message.watchId}"
                val session = health.session(message.sessionId)
                val accepted = try { session != null && session.watchId == trusted && health.import(trusted, session.id,
                    message.readings.map { WatchHealthSample(session.id, it.sequence, session.journeyId,
                        when (it.type) { HealthReadingType.HEART_RATE -> HealthMetric.HEART_RATE_BPM; HealthReadingType.STEPS -> HealthMetric.STEPS },
                        it.startAt, it.endAt, it.value) }, now) }
                    catch (_: WatchHealthTimePending) { return@withLock }
                HealthAck(message.sessionId, message.readings.last().sequence, terminal = !accepted)
            }
            else -> return@withLock
        }
        Wearable.getMessageClient(context).sendMessage(source, WatchHealthProtocol.PATH, WatchHealthProtocol.encode(response)).await()
    }
}
