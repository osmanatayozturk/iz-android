package org.iz.navigation.wearprotocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

// Enum positions are part of wire version 1. Do not reorder them without a version change.
enum class WearMode { CAR, MOTORCYCLE, BICYCLE, WALK, PASSENGER, RUN }
enum class WearAction { START, STOP, REFRESH }
enum class WearResultCode { STARTED, STOPPED, REFRESHED, NEEDS_PHONE, REJECTED, ERROR }
enum class WearWeatherStatus { LOADING, READY, ERROR }
enum class WearWeatherThreshold { BELOW_THRESHOLD, EXCEEDED }

data class WearRouteWeather(
    val status: WearWeatherStatus,
    val calculatedAt: Long,
    val validUntil: Long,
    val remainingMeters: Double = 0.0,
    val arrivalAt: Long? = null,
    val hazardStartsAt: Long? = null,
    val threshold: WearWeatherThreshold = WearWeatherThreshold.BELOW_THRESHOLD,
    val headline: String = "",
    val detail: String = "",
)

data class WearCommand(
    val id: String,
    val action: WearAction,
    val requestedAt: Long,
    val mode: WearMode? = null,
    val journeyId: String? = null,
)

data class WearResult(
    val commandId: String,
    val code: WearResultCode,
    val message: String,
    val journeyId: String? = null,
)

data class WearSnapshot(
    val generatedAt: Long,
    val journeyId: String? = null,
    val mode: WearMode? = null,
    val startedAt: Long? = null,
    val temporary: Boolean = false,
    val deadlineAt: Long? = null,
    val recording: Boolean = false,
    val distanceMeters: Double = 0.0,
    val elapsedMillis: Long = 0L,
    val averageSpeedKmh: Double? = null,
    val maxSpeedKmh: Double? = null,
    val currentSpeedKmh: Double? = null,
    val stepCount: Long? = null,
    val averagePaceSecondsPerKm: Double? = null,
    val health: WearHealthSummary? = null,
    val weather: WearRouteWeather? = null,
    val candidateProgressMeters: Double? = null,
)

/** Samsung Health WATCH measurements only. Null means missing, and times are measurement times. */
data class WearHealthSummary(
    val heartRateMeanBpm: Double? = null,
    val heartRateMinBpm: Double? = null,
    val heartRateMaxBpm: Double? = null,
    val latestHeartRateBpm: Double? = null,
    val latestHeartRateAt: Long? = null,
    val heartRateSampleCount: Int = 0,
    val totalCaloriesKcal: Double? = null,
    val calorieCoverageMillis: Long = 0,
    val watchSteps: Long? = null,
    val stepCoverageMillis: Long = 0,
    val measurementStartAt: Long? = null,
    val measurementEndAt: Long? = null,
    val lastCheckedAt: Long? = null,
    val partial: Boolean = true,
)

/**
 * Bounded, big-endian wire format shared by the phone and watch.
 *
 * Header: four-byte IZWP magic, one-byte version, one-byte message type.
 * Strings have an unsigned two-byte UTF-8 byte length. Nullable fields start with
 * exactly 0 or 1, as do booleans. Unknown versions, types, enums and trailing bytes
 * are rejected; a failed decode never returns a partly populated command.
 */
object WearProtocol {
    const val COMMAND_PATH = "/iz/v1/command"
    const val RESULT_PATH = "/iz/v1/result"
    const val STATE_PATH = "/iz/v1/state"
    const val STATE_KEY = "snapshot"
    const val PHONE_CAPABILITY = "iz_phone_v1"
    const val WATCH_CAPABILITY = "iz_watch_v1"
    const val V2_COMMAND_PATH = "/iz/v2/command"
    const val V2_RESULT_PATH = "/iz/v2/result"
    const val V2_STATE_PATH = "/iz/v2/state"
    const val V2_PHONE_CAPABILITY = "iz_phone_v2"
    const val V2_WATCH_CAPABILITY = "iz_watch_v2"
    const val V3_COMMAND_PATH = "/iz/v3/command"
    const val V3_RESULT_PATH = "/iz/v3/result"
    const val V3_STATE_PATH = "/iz/v3/state"
    const val V3_PHONE_CAPABILITY = "iz_phone_v3"
    const val V3_WATCH_CAPABILITY = "iz_watch_v3"
    const val V4_COMMAND_PATH = "/iz/v4/command"
    const val V4_RESULT_PATH = "/iz/v4/result"
    const val V4_STATE_PATH = "/iz/v4/state"
    const val V4_PHONE_CAPABILITY = "iz_phone_v4"
    const val V4_WATCH_CAPABILITY = "iz_watch_v4"
    const val CURRENT_VERSION = 4
    const val COMMAND_TTL_MS = 120_000L
    const val STATE_TTL_MS = 30_000L

    private const val MAGIC = 0x495A5750
    private const val COMMAND = 1
    private const val RESULT = 2
    private const val SNAPSHOT = 3
    private const val HEADER_BYTES = 6
    private const val MAX_PAYLOAD_BYTES = 4_096
    private const val MAX_ID_BYTES = 200
    private const val MAX_MESSAGE_BYTES = 2_048
    private const val MAX_WEATHER_HEADLINE_BYTES = 120
    private const val MAX_WEATHER_DETAIL_BYTES = 300
    private const val MAX_CLOCK_SKEW_MS = 5_000L

    fun commandPath(version: Int): String = when (version) {
        1 -> COMMAND_PATH; 2 -> V2_COMMAND_PATH; 3 -> V3_COMMAND_PATH; 4 -> V4_COMMAND_PATH; else -> error("Unsupported version")
    }
    fun resultPath(version: Int): String = when (version) {
        1 -> RESULT_PATH; 2 -> V2_RESULT_PATH; 3 -> V3_RESULT_PATH; 4 -> V4_RESULT_PATH; else -> error("Unsupported version")
    }
    fun statePath(version: Int): String = when (version) {
        1 -> STATE_PATH; 2 -> V2_STATE_PATH; 3 -> V3_STATE_PATH; 4 -> V4_STATE_PATH; else -> error("Unsupported version")
    }
    fun phoneCapability(version: Int): String = when (version) {
        1 -> PHONE_CAPABILITY; 2 -> V2_PHONE_CAPABILITY; 3 -> V3_PHONE_CAPABILITY; 4 -> V4_PHONE_CAPABILITY; else -> error("Unsupported version")
    }
    fun commandVersion(path: String): Int? = when (path) {
        COMMAND_PATH -> 1; V2_COMMAND_PATH -> 2; V3_COMMAND_PATH -> 3; V4_COMMAND_PATH -> 4; else -> null
    }
    fun frameVersion(bytes: ByteArray): Int? {
        if (bytes.size !in HEADER_BYTES..MAX_PAYLOAD_BYTES || ByteBuffer.wrap(bytes).int != MAGIC) return null
        return bytes[4].toInt().takeIf { it in 1..CURRENT_VERSION }
    }

    fun encodeCommand(value: WearCommand, version: Int = CURRENT_VERSION): ByteArray {
        validate(value)
        require(version != 1 || value.mode != WearMode.RUN) { "RUN requires wire version 2" }
        return encode(COMMAND, version) {
            writeText(value.id, MAX_ID_BYTES)
            writeByte(value.action.ordinal)
            writeLong(value.requestedAt)
            writeOptional(value.mode) { writeByte(it.ordinal) }
            writeOptional(value.journeyId) { writeText(it, MAX_ID_BYTES) }
        }
    }

    fun decodeCommand(bytes: ByteArray): WearCommand? = decode(bytes, COMMAND) { version ->
        WearCommand(
            id = readText(MAX_ID_BYTES),
            action = readEnum(WearAction.entries),
            requestedAt = readLong(),
            mode = readOptional { readEnum(if (version == 1) WearMode.entries.take(5) else WearMode.entries) },
            journeyId = readOptional { readText(MAX_ID_BYTES) },
        ).also(::validate)
    }

    fun encodeResult(value: WearResult, version: Int = CURRENT_VERSION): ByteArray {
        validate(value)
        return encode(RESULT, version) {
            writeText(value.commandId, MAX_ID_BYTES)
            writeByte(value.code.ordinal)
            writeText(value.message, MAX_MESSAGE_BYTES)
            writeOptional(value.journeyId) { writeText(it, MAX_ID_BYTES) }
        }
    }

    fun decodeResult(bytes: ByteArray): WearResult? = decode(bytes, RESULT) { _ ->
        WearResult(
            commandId = readText(MAX_ID_BYTES),
            code = readEnum(WearResultCode.entries),
            message = readText(MAX_MESSAGE_BYTES),
            journeyId = readOptional { readText(MAX_ID_BYTES) },
        ).also(::validate)
    }

    fun encodeSnapshot(value: WearSnapshot, version: Int = CURRENT_VERSION): ByteArray {
        validate(value)
        return encode(SNAPSHOT, version) {
            writeLong(value.generatedAt)
            writeOptional(value.journeyId) { writeText(it, MAX_ID_BYTES) }
            writeOptional(value.mode.takeUnless { version == 1 && it == WearMode.RUN }) { writeByte(it.ordinal) }
            writeOptional(value.startedAt) { writeLong(it) }
            writeBoolean(value.temporary)
            writeOptional(value.deadlineAt) { writeLong(it) }
            writeBoolean(value.recording)
            writeDouble(value.distanceMeters)
            writeLong(value.elapsedMillis)
            writeOptional(value.averageSpeedKmh) { writeDouble(it) }
            writeOptional(value.maxSpeedKmh) { writeDouble(it) }
            writeOptional(value.currentSpeedKmh) { writeDouble(it) }
            writeOptional(value.stepCount) { writeLong(it) }
            if (version >= 2) {
                writeOptional(value.averagePaceSecondsPerKm) { writeDouble(it) }
                writeOptional(value.health) { writeHealth(it) }
            }
            if (version >= 3) writeOptional(value.weather) { writeWeather(it) }
            if (version >= 4) writeOptional(value.candidateProgressMeters) { writeDouble(it) }
        }
    }

    fun decodeSnapshot(bytes: ByteArray): WearSnapshot? = decode(bytes, SNAPSHOT) { version ->
        WearSnapshot(
            generatedAt = readLong(),
            journeyId = readOptional { readText(MAX_ID_BYTES) },
            mode = readOptional { readEnum(if (version == 1) WearMode.entries.take(5) else WearMode.entries) },
            startedAt = readOptional { readLong() },
            temporary = readFlag(),
            deadlineAt = readOptional { readLong() },
            recording = readFlag(),
            distanceMeters = readDouble(),
            elapsedMillis = readLong(),
            averageSpeedKmh = readOptional { readDouble() },
            maxSpeedKmh = readOptional { readDouble() },
            currentSpeedKmh = readOptional { readDouble() },
            stepCount = readOptional { readLong() },
            averagePaceSecondsPerKm = if (version >= 2) readOptional { readDouble() } else null,
            health = if (version >= 2) readOptional { readHealth() } else null,
            weather = if (version >= 3) readOptional { readWeather() } else null,
            candidateProgressMeters = if (version >= 4) readOptional { readDouble() } else null,
        ).also(::validate)
    }

    fun isFreshCommand(value: WearCommand, now: Long): Boolean =
        isFresh(value.requestedAt, now, COMMAND_TTL_MS)

    fun isFreshSnapshot(value: WearSnapshot, now: Long): Boolean =
        isFresh(value.generatedAt, now, STATE_TTL_MS)

    /** The expiry boundary is exclusive; only small clock differences across devices are tolerated. */
    private fun isFresh(timestamp: Long, now: Long, ttl: Long): Boolean {
        if (timestamp < 0 || now < 0) return false
        return if (timestamp > now) timestamp - now <= MAX_CLOCK_SKEW_MS else now - timestamp < ttl
    }

    private fun validate(value: WearCommand) {
        validateId(value.id)
        require(value.requestedAt >= 0) { "Negative command timestamp" }
        value.journeyId?.let(::validateId)
        require(when (value.action) {
            WearAction.START -> value.mode != null && value.journeyId == null
            WearAction.STOP -> value.mode == null && value.journeyId != null
            WearAction.REFRESH -> value.mode == null && value.journeyId == null
        }) { "Invalid command shape" }
    }

    private fun validate(value: WearResult) {
        validateId(value.commandId)
        value.journeyId?.let(::validateId)
        require('\u0000' !in value.message) { "Invalid result message" }
        require(value.message.length <= MAX_MESSAGE_BYTES && utf8(value.message).size <= MAX_MESSAGE_BYTES) {
            "Result message is too long"
        }
    }

    private fun validate(value: WearSnapshot) {
        require(value.generatedAt >= 0 && value.elapsedMillis >= 0) { "Negative snapshot time" }
        require(nonNegativeFinite(value.distanceMeters)) { "Invalid distance" }
        require(listOf(value.averageSpeedKmh, value.maxSpeedKmh, value.currentSpeedKmh).all {
            it == null || nonNegativeFinite(it)
        }) { "Invalid speed" }
        require(value.stepCount == null || value.stepCount >= 0) { "Invalid step count" }
        require(value.candidateProgressMeters == null || value.temporary && nonNegativeFinite(value.candidateProgressMeters))
        require(value.averagePaceSecondsPerKm == null || nonNegativeFinite(value.averagePaceSecondsPerKm)) { "Invalid pace" }
        value.health?.let(::validate)
        value.weather?.let { validate(it, value.generatedAt) }
        if (value.journeyId == null) {
            require(value.mode == null && value.startedAt == null && !value.temporary && value.deadlineAt == null &&
                !value.recording && value.distanceMeters == 0.0 && value.elapsedMillis == 0L &&
                value.averageSpeedKmh == null && value.maxSpeedKmh == null && value.currentSpeedKmh == null &&
                value.stepCount == null && value.averagePaceSecondsPerKm == null && value.health == null &&
                value.weather == null) { "Idle snapshot contains journey state" }
        } else {
            validateId(value.journeyId)
            val start = value.startedAt
            require(start != null && start >= 0) { "Journey start is missing" }
            // mode may be null: the phone can be recording an UNKNOWN automatic candidate.
            require(if (value.temporary) value.deadlineAt != null && value.deadlineAt > start
                else value.deadlineAt == null) { "Invalid candidate deadline" }
        }
    }

    private fun validate(value: WearRouteWeather, generatedAt: Long) {
        require(value.calculatedAt >= 0 && value.validUntil >= value.calculatedAt) { "Invalid weather validity" }
        require(value.status != WearWeatherStatus.READY || value.validUntil > value.calculatedAt) { "Ready weather has no validity window" }
        require(value.calculatedAt <= generatedAt || value.calculatedAt - generatedAt <= MAX_CLOCK_SKEW_MS) { "Weather calculation is in the future" }
        require(nonNegativeFinite(value.remainingMeters)) { "Invalid weather distance" }
        require(value.arrivalAt == null || value.arrivalAt >= value.calculatedAt) { "Invalid weather arrival" }
        require(value.hazardStartsAt == null || value.hazardStartsAt >= value.calculatedAt) { "Invalid weather encounter" }
        require(value.arrivalAt == null || value.hazardStartsAt == null || value.hazardStartsAt <= value.arrivalAt) { "Weather encounter follows arrival" }
        validateWeatherText(value.headline, MAX_WEATHER_HEADLINE_BYTES)
        validateWeatherText(value.detail, MAX_WEATHER_DETAIL_BYTES)
        if (value.status == WearWeatherStatus.READY) {
            require(value.arrivalAt != null && value.headline.isNotBlank()) { "Ready weather is incomplete" }
            require(value.threshold != WearWeatherThreshold.EXCEEDED || value.hazardStartsAt != null) { "Exceeded weather has no encounter time" }
        }
    }

    private fun validateWeatherText(value: String, maximum: Int) {
        require('\u0000' !in value && value.length <= maximum && utf8(value).size <= maximum) { "Invalid weather text" }
    }

    private fun validate(value: WearHealthSummary) {
        require(listOf(value.heartRateMeanBpm, value.heartRateMinBpm, value.heartRateMaxBpm,
            value.latestHeartRateBpm, value.totalCaloriesKcal).all { it == null || nonNegativeFinite(it) })
        require(listOf(value.latestHeartRateAt, value.measurementStartAt, value.measurementEndAt,
            value.lastCheckedAt).all { it == null || it >= 0 })
        require(value.heartRateSampleCount >= 0 && value.calorieCoverageMillis >= 0 && value.stepCoverageMillis >= 0)
        require(value.watchSteps == null || value.watchSteps >= 0)
        require((value.latestHeartRateBpm == null) == (value.latestHeartRateAt == null))
        require((value.measurementStartAt == null) == (value.measurementEndAt == null))
        require(value.measurementStartAt == null || value.measurementEndAt!! >= value.measurementStartAt)
        require(value.heartRateMinBpm == null || value.heartRateMaxBpm == null || value.heartRateMinBpm <= value.heartRateMaxBpm)
    }

    private fun DataOutputStream.writeHealth(value: WearHealthSummary) {
        writeOptional(value.heartRateMeanBpm) { writeDouble(it) }
        writeOptional(value.heartRateMinBpm) { writeDouble(it) }
        writeOptional(value.heartRateMaxBpm) { writeDouble(it) }
        writeOptional(value.latestHeartRateBpm) { writeDouble(it) }
        writeOptional(value.latestHeartRateAt) { writeLong(it) }
        writeInt(value.heartRateSampleCount)
        writeOptional(value.totalCaloriesKcal) { writeDouble(it) }
        writeLong(value.calorieCoverageMillis)
        writeOptional(value.watchSteps) { writeLong(it) }
        writeLong(value.stepCoverageMillis)
        writeOptional(value.measurementStartAt) { writeLong(it) }
        writeOptional(value.measurementEndAt) { writeLong(it) }
        writeOptional(value.lastCheckedAt) { writeLong(it) }
        writeBoolean(value.partial)
    }

    private fun DataOutputStream.writeWeather(value: WearRouteWeather) {
        writeByte(value.status.ordinal)
        writeLong(value.calculatedAt)
        writeLong(value.validUntil)
        writeDouble(value.remainingMeters)
        writeOptional(value.arrivalAt) { writeLong(it) }
        writeOptional(value.hazardStartsAt) { writeLong(it) }
        writeByte(value.threshold.ordinal)
        writeText(value.headline, MAX_WEATHER_HEADLINE_BYTES)
        writeText(value.detail, MAX_WEATHER_DETAIL_BYTES)
    }

    private fun DataInputStream.readWeather() = WearRouteWeather(
        status = readEnum(WearWeatherStatus.entries), calculatedAt = readLong(), validUntil = readLong(),
        remainingMeters = readDouble(), arrivalAt = readOptional { readLong() },
        hazardStartsAt = readOptional { readLong() }, threshold = readEnum(WearWeatherThreshold.entries),
        headline = readText(MAX_WEATHER_HEADLINE_BYTES), detail = readText(MAX_WEATHER_DETAIL_BYTES),
    )

    private fun DataInputStream.readHealth() = WearHealthSummary(
        heartRateMeanBpm = readOptional { readDouble() }, heartRateMinBpm = readOptional { readDouble() },
        heartRateMaxBpm = readOptional { readDouble() }, latestHeartRateBpm = readOptional { readDouble() },
        latestHeartRateAt = readOptional { readLong() }, heartRateSampleCount = readInt(),
        totalCaloriesKcal = readOptional { readDouble() }, calorieCoverageMillis = readLong(),
        watchSteps = readOptional { readLong() }, stepCoverageMillis = readLong(),
        measurementStartAt = readOptional { readLong() }, measurementEndAt = readOptional { readLong() },
        lastCheckedAt = readOptional { readLong() }, partial = readFlag(),
    )

    private fun validateId(value: String) {
        require(value.isNotEmpty() && value.length <= MAX_ID_BYTES &&
            value.none { it.isWhitespace() || it.isISOControl() }) { "Invalid identity" }
        require(utf8(value).size <= MAX_ID_BYTES) { "Identity is too long" }
    }

    private fun nonNegativeFinite(value: Double): Boolean = value.isFinite() && value >= 0.0

    private fun encode(type: Int, version: Int, write: DataOutputStream.() -> Unit): ByteArray {
        require(version in 1..CURRENT_VERSION) { "Unsupported version" }
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { output ->
            output.writeInt(MAGIC)
            output.writeByte(version)
            output.writeByte(type)
            write.invoke(output)
        }
        return buffer.toByteArray().also { require(it.size <= MAX_PAYLOAD_BYTES) { "Payload is too long" } }
    }

    private fun <T> decode(bytes: ByteArray, type: Int, read: DataInputStream.(Int) -> T): T? {
        if (bytes.size !in HEADER_BYTES..MAX_PAYLOAD_BYTES) return null
        return try {
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                require(input.readInt() == MAGIC) { "Unsupported frame" }
                val version = input.readUnsignedByte()
                require(version in 1..CURRENT_VERSION && input.readUnsignedByte() == type) { "Unsupported frame" }
                read.invoke(input, version).also { require(input.available() == 0) { "Trailing frame data" } }
            }
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun DataOutputStream.writeText(value: String, maximum: Int) {
        val encoded = utf8(value)
        require(encoded.size <= maximum) { "String is too long" }
        writeShort(encoded.size)
        write(encoded)
    }

    private fun DataInputStream.readText(maximum: Int): String {
        val size = readUnsignedShort()
        require(size <= maximum && size <= available()) { "Invalid string length" }
        val bytes = ByteArray(size)
        readFully(bytes)
        return Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    }

    private fun utf8(value: String): ByteArray = try {
        val encoded = Charsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(value))
        ByteArray(encoded.remaining()).also { encoded.get(it) }
    } catch (error: CharacterCodingException) {
        throw IllegalArgumentException("Invalid Unicode string", error)
    }

    private fun DataInputStream.readFlag(): Boolean = when (readUnsignedByte()) {
        0 -> false
        1 -> true
        else -> throw IllegalArgumentException("Invalid boolean")
    }

    private fun <T> DataInputStream.readEnum(entries: List<T>): T =
        entries.getOrNull(readUnsignedByte()) ?: throw IllegalArgumentException("Unknown enum value")

    private fun <T> DataOutputStream.writeOptional(value: T?, write: DataOutputStream.(T) -> Unit) {
        writeBoolean(value != null)
        if (value != null) write.invoke(this, value)
    }

    private fun <T> DataInputStream.readOptional(read: DataInputStream.() -> T): T? =
        if (readFlag()) read.invoke(this) else null
}
