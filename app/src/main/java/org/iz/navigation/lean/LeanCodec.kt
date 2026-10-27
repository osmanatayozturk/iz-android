package org.iz.navigation.lean

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Versioned, bounded private telemetry; never forwarded to power diagnostics or sharing. */
internal object LeanCodec {
    const val MAX_CHUNK_BYTES = 256 * 1024
    const val MAX_RECORDS = 2048
    const val MAX_CURVES = 2000
    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    fun inputHash(input: LeanInputSession, chunks: List<LeanChunkManifest>): String {
        val c = input.calibration
        val parts = chunks.sortedBy { it.partIndex }.joinToString("|") {
            it.partIndex.toString() + ":" + it.byteCount + ":" + it.sha256 + ":" +
                it.recordCount + ":" + it.firstElapsedNanos + ":" + it.lastElapsedNanos
        }
        val identity = listOf(input.token.id, input.token.journeyId, input.token.generation,
            c.epoch, c.createdAt, c.bodyConfirmed, c.uprightGravity.map { java.lang.Double.doubleToLongBits(it) },
            c.gyroBias.map { java.lang.Double.doubleToLongBits(it) }, input.bootId,
            input.startedAtUtc, input.startedAtElapsedNanos, input.endedAtElapsedNanos,
            input.partialReasons.sorted(), input.droppedSamples, parts).joinToString("\n")
        return sha256(identity.toByteArray(Charsets.UTF_8))
    }
    private fun obj(vararg fields: Pair<String, Any?>) = JSONObject().apply {
        fields.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
    }
    private fun n(value: Double?): Double? { require(value == null || value.isFinite()); return value }
    private fun JSONObject.number(key: String): Double = get(key).let {
        require(it is Number && it.toDouble().isFinite()) { "Invalid telemetry number" }; it.toDouble()
    }
    private fun JSONObject.optional(key: String): Double? = if (isNull(key)) null else number(key)
    private fun JSONObject.long(key: String): Long = get(key).let {
        require(it is Int || it is Long || it is Short || it is Byte) { "Invalid telemetry integer" }; (it as Number).toLong()
    }
    private fun JSONObject.text(key: String, max: Int = 200): String = get(key).let {
        require(it is String && it.isNotBlank() && it.length <= max && it.none { c -> c.code < 32 }); it
    }
    private fun vector(array: JSONArray): List<Double> {
        require(array.length() == 3)
        return List(3) { (array.get(it) as? Number)?.toDouble()?.also { v -> require(v.isFinite()) } ?: error("Invalid vector") }
    }

    fun calibrationJson(value: LeanCalibration): String {
        require(value.epoch.isNotBlank() && value.epoch.length <= 200 && value.createdAt >= 0)
        require(value.uprightGravity.size == 3 && value.gyroBias.size == 3)
        require((value.uprightGravity + value.gyroBias).all { it.isFinite() })
        return obj("version" to 1, "epoch" to value.epoch, "createdAt" to value.createdAt,
            "gravity" to JSONArray(value.uprightGravity), "bias" to JSONArray(value.gyroBias),
            "body" to value.bodyConfirmed).toString()
    }
    fun calibration(raw: String): LeanCalibration {
        require(raw.length <= 8192)
        val j = JSONObject(raw); require(j.getInt("version") == 1 && j.get("body") is Boolean)
        return LeanCalibration(j.text("epoch"), j.long("createdAt"), vector(j.getJSONArray("gravity")),
            vector(j.getJSONArray("bias")), j.getBoolean("body"))
    }
    fun reasonsJson(values: Set<String>): String {
        require(values.size <= 64 && values.all { it.matches(Regex("[A-Za-z0-9_.:-]{1,100}")) })
        return JSONArray(values.sorted()).toString()
    }
    fun reasons(raw: String): Set<String> {
        require(raw.length <= 8192); val a = JSONArray(raw); require(a.length() <= 64)
        return (0 until a.length()).map { a.get(it).also { v -> require(v is String) } as String }.toSet()
            .also { reasonsJson(it) }
    }
    fun encodeRecords(records: List<LeanRecord>): ByteArray {
        require(records.size in 1..MAX_RECORDS)
        val rows = JSONArray()
        records.forEach { r ->
            require(r.elapsedNanos >= 0)
            rows.put(when (r) {
                is LeanImuRecord -> {
                    require(r.sequence >= 0)
                    obj("t" to "imu", "ns" to r.elapsedNanos, "kind" to r.kind.name, "seq" to r.sequence,
                        "x" to n(r.x), "y" to n(r.y), "z" to n(r.z), "accuracy" to r.accuracy)
                }
                is LeanGnssRecord -> {
                    require(r.utcMillis >= 0 && r.latitude.isFinite() && r.latitude in -90.0..90.0 &&
                        r.longitude.isFinite() && r.longitude in -180.0..180.0)
                    require(r.provider == null || r.provider.length <= 80 && r.provider.none { it.code < 32 })
                    obj("t" to "gnss", "ns" to r.elapsedNanos, "utc" to r.utcMillis, "lat" to r.latitude,
                        "lon" to r.longitude, "accuracy" to n(r.accuracyMeters), "speed" to n(r.speedMps),
                        "bearing" to n(r.bearingDegrees), "speedAccuracy" to n(r.speedAccuracyMps),
                        "bearingAccuracy" to n(r.bearingAccuracyDegrees), "timeUncertainty" to n(r.elapsedUncertaintyNanos),
                        "mock" to r.mock, "provider" to r.provider)
                }
            })
        }
        return obj("version" to 1, "records" to rows).toString().toByteArray(Charsets.UTF_8)
            .also { require(it.size <= MAX_CHUNK_BYTES) { "Telemetry batch too large" } }
    }
    fun decodeRecords(bytes: ByteArray): Sequence<LeanRecord> {
        require(bytes.size in 1..MAX_CHUNK_BYTES)
        val j = JSONObject(bytes.toString(Charsets.UTF_8)); require(j.getInt("version") == 1)
        val rows = j.getJSONArray("records"); require(rows.length() in 1..MAX_RECORDS)
        return sequence {
            for (i in 0 until rows.length()) {
                val r = rows.getJSONObject(i)
                val value = when (r.text("t", 8)) {
                    "imu" -> LeanImuRecord(LeanSensorKind.valueOf(r.text("kind", 30)), r.long("seq"),
                        r.long("ns"), r.number("x"), r.number("y"), r.number("z"), r.getInt("accuracy"))
                    "gnss" -> {
                        require(r.get("mock") is Boolean)
                        LeanGnssRecord(r.long("ns"), r.long("utc"), r.number("lat"), r.number("lon"),
                            r.optional("accuracy"), r.optional("speed"), r.optional("bearing"), r.optional("speedAccuracy"),
                            r.optional("bearingAccuracy"), r.optional("timeUncertainty"), r.getBoolean("mock"),
                            if (r.isNull("provider")) null else r.text("provider", 80))
                    }
                    else -> error("Unknown telemetry record")
                }
                encodeRecords(listOf(value))
                yield(value)
            }
        }
    }
    private fun angle(value: Double?): Double? = n(value)?.also { require(it in 0.0..90.0) }
    fun estimateJson(value: LeanEstimate): String {
        require(value.algorithmVersion.matches(Regex("[A-Za-z0-9_.-]{1,120}")) &&
            value.calibrationEpoch.isNotBlank() && value.calibrationEpoch.length <= 200)
        require(value.validMillis >= 0 && value.observedMillis >= value.validMillis)
        require(value.curves.size <= MAX_CURVES && (value.frameRotation.isEmpty() || value.frameRotation.size == 9) &&
            value.frameRotation.all { it.isFinite() })
        val curves = JSONArray()
        value.curves.forEach { c ->
            require(c.startElapsedNanos >= 0 && c.endElapsedNanos >= c.startElapsedNanos)
            require((c.latitude == null) == (c.longitude == null))
            require(c.latitude == null || c.latitude.isFinite() && c.latitude in -90.0..90.0)
            require(c.longitude == null || c.longitude.isFinite() && c.longitude in -180.0..180.0)
            require((c.startUtcMillis == null) == (c.endUtcMillis == null))
            require(c.startUtcMillis == null || c.startUtcMillis >= 0 && c.endUtcMillis!! >= c.startUtcMillis)
            curves.put(obj("start" to c.startElapsedNanos, "end" to c.endElapsedNanos,
                "left" to angle(c.leftDegrees), "right" to angle(c.rightDegrees), "lat" to c.latitude,
                "lon" to c.longitude, "startUtc" to c.startUtcMillis, "endUtc" to c.endUtcMillis))
        }
        return obj("version" to 1, "algorithm" to value.algorithmVersion, "calibration" to value.calibrationEpoch,
            "left" to angle(value.leftDegrees), "right" to angle(value.rightDegrees), "valid" to value.validMillis,
            "observed" to value.observedMillis, "reasons" to JSONArray(reasonsJson(value.partialReasons)),
            "curves" to curves, "frame" to JSONArray(value.frameRotation)).toString()
    }
    fun estimate(raw: String): LeanEstimate {
        require(raw.length <= 1024 * 1024)
        val j = JSONObject(raw); require(j.getInt("version") == 1)
        val a = j.getJSONArray("curves"); require(a.length() <= MAX_CURVES)
        val f = j.getJSONArray("frame"); require(f.length() == 0 || f.length() == 9)
        val result = LeanEstimate(j.text("algorithm", 120), j.text("calibration"), j.optional("left"), j.optional("right"),
            j.long("valid"), j.long("observed"), reasons(j.getJSONArray("reasons").toString()),
            List(a.length()) { i -> a.getJSONObject(i).let {
                LeanCurveResult(it.long("start"), it.long("end"), it.optional("left"), it.optional("right"),
                    it.optional("lat"), it.optional("lon"),
                    if (it.isNull("startUtc")) null else it.long("startUtc"),
                    if (it.isNull("endUtc")) null else it.long("endUtc"))
            } }, List(f.length()) { f.getDouble(it) })
        estimateJson(result)
        return result
    }
}
