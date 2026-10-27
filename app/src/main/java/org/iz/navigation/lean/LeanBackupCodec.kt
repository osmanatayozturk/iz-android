package org.iz.navigation.lean

import org.iz.navigation.data.DiarySnapshot
import org.iz.navigation.data.JourneyStatus
import org.iz.navigation.data.Transport
import org.json.JSONArray
import org.json.JSONObject

/** ZIP8 typed metadata; raw samples are separate bounded entries. */
internal object LeanBackupCodec {
    private val digest = Regex("[0-9a-f]{64}")
    private fun obj(vararg pairs: Pair<String, Any?>) = JSONObject().apply {
        pairs.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
    }
    private fun JSONObject.text(key: String): String = get(key).let {
        require(it is String && it.isNotBlank() && it.length <= 200 && it.none { c -> c.code < 32 }); it
    }
    private fun JSONObject.long(key: String): Long = get(key).let {
        require(it is Byte || it is Short || it is Int || it is Long) { "Invalid lean integer: $key" }
        (it as Number).toLong()
    }
    private fun JSONObject.int(key: String): Int = long(key).also { require(it in 0..Int.MAX_VALUE.toLong()) }.toInt()

    fun encode(sessions: List<LeanBackupSession>): JSONArray {
        validate(sessions)
        return JSONArray().apply { sessions.forEach { session ->
            val input = session.input
            put(obj("state" to session.state, "input" to obj(
                "token" to obj("id" to input.token.id, "journeyId" to input.token.journeyId, "generation" to input.token.generation),
                "calibration" to JSONObject(LeanCodec.calibrationJson(input.calibration)), "bootId" to input.bootId,
                "startedAtUtc" to input.startedAtUtc, "startedAtElapsedNanos" to input.startedAtElapsedNanos,
                "endedAtElapsedNanos" to input.endedAtElapsedNanos, "inputHash" to input.inputHash,
                "partialReasons" to JSONArray(LeanCodec.reasonsJson(input.partialReasons)), "droppedSamples" to input.droppedSamples),
                "chunks" to JSONArray().apply { session.chunks.forEach { part -> put(obj(
                    "partIndex" to part.partIndex, "byteCount" to part.byteCount, "sha256" to part.sha256,
                    "recordCount" to part.recordCount, "firstElapsedNanos" to part.firstElapsedNanos,
                    "lastElapsedNanos" to part.lastElapsedNanos)) } },
                "result" to session.result?.let { JSONObject(LeanCodec.estimateJson(it)) }))
        } }
    }

    fun decode(array: JSONArray): List<LeanBackupSession> {
        require(array.length() <= LeanStore.MAX_CHUNKS)
        var totalChunks = 0L
        return List(array.length()) { index ->
            val json = array.getJSONObject(index)
            val input = json.getJSONObject("input")
            val token = input.getJSONObject("token")
            val parts = json.getJSONArray("chunks")
            totalChunks += parts.length()
            require(totalChunks <= LeanStore.MAX_CHUNKS)
            LeanBackupSession(LeanInputSession(
                LeanSessionToken(token.text("id"), token.text("journeyId"), token.long("generation")),
                LeanCodec.calibration(input.getJSONObject("calibration").toString()), input.text("bootId"),
                input.long("startedAtUtc"), input.long("startedAtElapsedNanos"), input.long("endedAtElapsedNanos"),
                input.text("inputHash"), LeanCodec.reasons(input.getJSONArray("partialReasons").toString()),
                input.long("droppedSamples")), json.text("state"),
                List(parts.length()) { p -> parts.getJSONObject(p).let {
                    LeanChunkManifest(it.int("partIndex"), it.int("byteCount"), it.text("sha256"),
                        it.int("recordCount"), it.long("firstElapsedNanos"), it.long("lastElapsedNanos"))
                } },
                if (json.isNull("result")) null else LeanCodec.estimate(json.getJSONObject("result").toString()))
        }.also(::validate)
    }

    fun validate(sessions: List<LeanBackupSession>) {
        require(sessions.size <= LeanStore.MAX_CHUNKS)
        require(sessions.map { it.input.token.id }.toSet().size == sessions.size) { "Duplicate lean session" }
        var chunks = 0L
        var bytes = 0L
        sessions.forEach { session ->
            val input = session.input
            LeanArchive.rawName(input.token.id)
            require(input.token.journeyId.isNotBlank() && input.token.journeyId.length <= 200 &&
                input.token.journeyId.none { it.code < 32 } && input.token.generation >= 0)
            require(input.bootId.isNotBlank() && input.bootId.length <= 200 && input.bootId.none { it.code < 32 })
            val end = requireNotNull(input.endedAtElapsedNanos) { "Active lean capture cannot be backed up" }
            require(input.startedAtUtc >= 0 && input.startedAtElapsedNanos >= 0 && end >= input.startedAtElapsedNanos &&
                input.droppedSamples >= 0 && digest.matches(input.inputHash))
            require(input.calibration.bodyConfirmed)
            LeanCodec.calibration(LeanCodec.calibrationJson(input.calibration))
            LeanCodec.reasonsJson(input.partialReasons)
            require(session.state in setOf("SEALED", "PARTIAL", "COMPLETE")) { "Active lean capture cannot be backed up" }
            require((session.state == "COMPLETE") == (session.result != null))
            session.result?.let { result ->
                LeanCodec.estimateJson(result)
                require(result.calibrationEpoch == input.calibration.epoch)
                require(result.observedMillis <= (end - input.startedAtElapsedNanos) / 1_000_000)
                require(result.curves.all { it.startElapsedNanos >= input.startedAtElapsedNanos && it.endElapsedNanos <= end })
            }
            chunks += session.chunks.size
            require(chunks <= LeanStore.MAX_CHUNKS)
            session.chunks.forEachIndexed { index, part ->
                require(part.partIndex == index && part.byteCount in 1..LeanCodec.MAX_CHUNK_BYTES &&
                    part.recordCount in 1..LeanCodec.MAX_RECORDS && digest.matches(part.sha256))
                require(part.firstElapsedNanos >= input.startedAtElapsedNanos &&
                    part.lastElapsedNanos >= part.firstElapsedNanos && part.lastElapsedNanos <= end)
                bytes += part.byteCount
                require(bytes <= LeanStore.MAX_RAW_BYTES)
            }
            // Durable completed reports may already have had their raw chunks removed.
            if (session.state != "COMPLETE" || session.chunks.isNotEmpty()) {
                require(inputHash(input, session.chunks) == input.inputHash) { "Lean input metadata checksum mismatch" }
            }
        }
    }

    fun validateSnapshot(snapshot: DiarySnapshot) {
        validate(snapshot.leanSessions)
        val journeys = snapshot.journeys.associateBy { it.id }
        require(snapshot.leanSessions.all {
            journeys[it.input.token.journeyId]?.let { trip ->
                trip.status == JourneyStatus.CONFIRMED && trip.transport == Transport.MOTORCYCLE
            } == true
        }) { "Invalid lean journey reference" }
        require(snapshot.destinationShortcuts.size <= 2 &&
            snapshot.destinationShortcuts.map { it.kind }.toSet().size == snapshot.destinationShortcuts.size)
        val places = snapshot.places.map { it.id }.toSet()
        require(snapshot.destinationShortcuts.all { it.placeId in places }) { "Invalid destination shortcut" }
    }

    /** Same identity as LeanStore; no raw payload is materialized for the manifest. */
    fun inputHash(input: LeanInputSession, chunks: List<LeanChunkManifest>): String {
        return LeanCodec.inputHash(input, chunks)
    }
}
