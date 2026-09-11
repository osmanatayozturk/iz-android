package org.iz.navigation.wearprotocol

import java.io.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

enum class HealthReadingType { HEART_RATE, STEPS }
sealed interface HealthMessage
data class HealthHello(val requestId: String, val watchId: String, val deviceName: String,
    val sessionId: String?, val onBody: Boolean? = null, val capturing: Boolean = false,
    val buffered: Int = 0) : HealthMessage
data class HealthHelloReply(val requestId: String, val phoneAt: Long, val sessionId: String?,
    val journeyId: String?, val createdAt: Long? = null) : HealthMessage
data class HealthReading(val sequence: Long, val type: HealthReadingType, val startAt: Long,
    val endAt: Long, val value: Double)
data class HealthBatch(val watchId: String, val sessionId: String, val readings: List<HealthReading>) : HealthMessage
data class HealthAck(val sessionId: String, val throughSequence: Long, val terminal: Boolean) : HealthMessage

/** Different magic prevents health payloads being interpreted as journey control messages. */
object WatchHealthProtocol {
    const val PATH = "/iz/v4/health"
    const val LEASE_MILLIS = 15 * 60_000L
    const val MAX_READINGS = 50
    private const val MAGIC = 0x495A5748
    private fun identity(value: String) = value.isNotBlank() && value.length <= 200 && value.none { it.isWhitespace() || it.isISOControl() }
    fun valid(value: HealthReading): Boolean = value.sequence > 0 && value.startAt > 0 && value.endAt >= value.startAt &&
        value.value.isFinite() && when (value.type) {
            HealthReadingType.HEART_RATE -> value.endAt == value.startAt && value.value in 1.0..300.0
            HealthReadingType.STEPS -> value.endAt > value.startAt && value.value in 0.0..1_000_000.0 && value.value % 1.0 == 0.0
        }
    private fun validate(value: HealthMessage) { when (value) {
        is HealthHello -> require(identity(value.requestId) && identity(value.watchId) &&
            (value.sessionId == null || identity(value.sessionId)) && value.deviceName.length <= 200 &&
            value.deviceName.none { it.isISOControl() } && value.buffered in 0..100_000)
        is HealthHelloReply -> require(identity(value.requestId) && value.phoneAt > 0 &&
            (value.sessionId == null) == (value.journeyId == null) && (value.sessionId == null) == (value.createdAt == null) &&
            (value.sessionId == null || identity(value.sessionId) && identity(value.journeyId!!) && value.createdAt!! > 0 && value.createdAt <= value.phoneAt))
        is HealthBatch -> require(identity(value.watchId) && identity(value.sessionId) && value.readings.size in 1..MAX_READINGS &&
            value.readings.all(::valid) && value.readings.zipWithNext().all { (a,b) -> b.sequence > a.sequence })
        is HealthAck -> require(identity(value.sessionId) && value.throughSequence >= 0)
    } }
    fun encode(value: HealthMessage): ByteArray {
        validate(value)
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out -> with(out) {
            writeInt(MAGIC); writeByte(4)
            when (value) {
                is HealthHello -> { writeByte(1); text(value.requestId); text(value.watchId); text(value.deviceName); optional(value.sessionId)
                    writeByte(when (value.onBody) { null -> 0; false -> 1; true -> 2 }); writeBoolean(value.capturing); writeInt(value.buffered) }
                is HealthHelloReply -> { writeByte(2); text(value.requestId); writeLong(value.phoneAt); optional(value.sessionId); optional(value.journeyId)
                    writeBoolean(value.createdAt != null); value.createdAt?.let { writeLong(it) } }
                is HealthBatch -> { writeByte(3); text(value.watchId); text(value.sessionId); writeInt(value.readings.size)
                    value.readings.forEach { writeLong(it.sequence); writeByte(it.type.ordinal); writeLong(it.startAt); writeLong(it.endAt); writeDouble(it.value) } }
                is HealthAck -> { writeByte(4); text(value.sessionId); writeLong(value.throughSequence); writeBoolean(value.terminal) }
            }
        } }
        return buffer.toByteArray().also { require(it.size <= 4096) }
    }
    fun decode(bytes: ByteArray): HealthMessage? {
        if (bytes.size !in 6..4096) return null
        return try { DataInputStream(ByteArrayInputStream(bytes)).use { input -> with(input) {
            require(readInt() == MAGIC && readUnsignedByte() == 4)
            val value = when (readUnsignedByte()) {
                1 -> HealthHello(text(), text(), text(), optional(), when (readUnsignedByte()) { 0 -> null; 1 -> false; 2 -> true; else -> error("body") }, flag(), readInt())
                2 -> HealthHelloReply(text(), readLong(), optional(), optional(), if (flag()) readLong() else null)
                3 -> { val watch = text(); val session = text(); val size = readInt(); require(size in 1..MAX_READINGS)
                    HealthBatch(watch, session, List(size) { HealthReading(readLong(), HealthReadingType.entries.getOrNull(readUnsignedByte())
                        ?: error("metric"), readLong(), readLong(), readDouble()) }) }
                4 -> HealthAck(text(), readLong(), flag())
                else -> error("type")
            }
            require(available() == 0); validate(value); value
        } } } catch (_: Exception) { null }
    }
    private fun DataOutputStream.text(value: String) { val bytes = value.toByteArray(Charsets.UTF_8); require(bytes.size <= 800); writeShort(bytes.size); write(bytes) }
    private fun DataInputStream.text(): String { val size = readUnsignedShort(); require(size <= 800 && size <= available()); val bytes = ByteArray(size); readFully(bytes)
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString() }
    private fun DataInputStream.flag(): Boolean = when (readUnsignedByte()) { 0 -> false; 1 -> true; else -> error("flag") }
    private fun DataOutputStream.optional(value: String?) { writeBoolean(value != null); if (value != null) text(value) }
    private fun DataInputStream.optional(): String? = if (flag()) text() else null
}

/** phoneAt is captured just before reply transmission. Receipt anchoring avoids future-dating after slow phone processing. */
data class HealthClockAnchor(val phoneAt: Long, val elapsedAt: Long) {
    fun epochAt(elapsedMillis: Long): Long = phoneAt + elapsedMillis - elapsedAt
    fun discontinuous(other: HealthClockAnchor, atElapsed: Long) = kotlin.math.abs(epochAt(atElapsed) - other.epochAt(atElapsed)) > 2_000
    companion object {
        fun create(phoneAt: Long, sentAt: Long, receivedAt: Long): HealthClockAnchor? =
            if (phoneAt <= 0 || sentAt < 0 || receivedAt < sentAt || receivedAt - sentAt > 5_000) null
            else HealthClockAnchor(phoneAt, receivedAt)
    }
}
