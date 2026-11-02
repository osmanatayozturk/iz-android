package org.iz.navigation.data

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Bounded disposable assets only; this is not a diary backup or database format. */
internal object RecordedCurveCodec {
    const val MAX_BYTES = 8 * 1024 * 1024
    fun encode(value: RecordedCurveDetails): String {
        require(valid(value)) { "Geçersiz hazırlanmış viraj ayrıntısı." }
        val root = JSONObject().put("journeyId", value.journeyId).put("sourceFingerprint", value.sourceFingerprint)
            .put("algorithmVersion", value.algorithmVersion).put("usablePointCount", value.usablePointCount)
            .put("excludedIntervalCount", value.excludedIntervalCount).put("truncated", value.truncated)
            .put("curves", JSONArray().apply { value.curves.forEach { curve ->
                put(JSONObject().put("id", curve.id).put("journeyId", curve.journeyId)
                    .put("sourceFingerprint", curve.sourceFingerprint).put("algorithmVersion", curve.algorithmVersion)
                    .put("start", endpoint(curve.start)).put("end", endpoint(curve.end))
                    .put("direction", curve.direction.name).put("pointCount", curve.pointCount)
                    .put("recordedDistanceMeters", curve.recordedDistanceMeters)
                    .put("sampledMinSpeedKmh", curve.sampledMinSpeedKmh ?: JSONObject.NULL)
                    .put("sampledMaxSpeedKmh", curve.sampledMaxSpeedKmh ?: JSONObject.NULL))
            } })
        return root.toString().also { require(it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) }
    }
    fun decode(json: String, journeyId: String, fingerprint: String): RecordedCurveDetails? = try {
        require(json.length <= MAX_BYTES && json.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        val parser = JSONTokener(json)
        val root = parser.nextValue() as? JSONObject ?: error("Missing object")
        require(parser.nextClean() == '\u0000')
        require(root.getString("journeyId") == journeyId && root.getString("sourceFingerprint") == fingerprint)
        val rows = root.getJSONArray("curves")
        require(rows.length() <= RecordedCurves.MAX_CURVES)
        val curves = (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            RecordedCurveDetail(row.getString("id"), row.getString("journeyId"), row.getString("sourceFingerprint"),
                integer(row, "algorithmVersion"), endpoint(row.getJSONObject("start")), endpoint(row.getJSONObject("end")),
                RecordedCurveDirection.valueOf(row.getString("direction")), integer(row, "pointCount"),
                number(row, "recordedDistanceMeters"), optionalNumber(row, "sampledMinSpeedKmh"), optionalNumber(row, "sampledMaxSpeedKmh"))
        }
        val truncated = if (root.has("truncated")) root.get("truncated").also { require(it is Boolean) } as Boolean else false
        RecordedCurveDetails(journeyId, fingerprint, integer(root, "algorithmVersion"), curves,
            integer(root, "usablePointCount"), integer(root, "excludedIntervalCount"), truncated).takeIf(::valid)
    } catch (_: Exception) { null }

    private fun valid(value: RecordedCurveDetails): Boolean {
        if (!identifier(value.journeyId) || !value.sourceFingerprint.matches(Regex("[a-f0-9]{64}")) ||
            value.algorithmVersion != RecordedCurves.ALGORITHM_VERSION || value.usablePointCount < 0 ||
            value.excludedIntervalCount < 0 || value.curves.size > RecordedCurves.MAX_CURVES) return false
        val ids = hashSetOf<String>()
        var previous: RecordedCurveEndpoint? = null
        for (curve in value.curves) {
            if (!identifier(curve.id) || !ids.add(curve.id) || curve.journeyId != value.journeyId ||
                curve.sourceFingerprint != value.sourceFingerprint || curve.algorithmVersion != value.algorithmVersion ||
                curve.pointCount < 4 || curve.pointCount > value.usablePointCount ||
                !endpointValid(curve.start) || !endpointValid(curve.end) || curve.end.at <= curve.start.at ||
                curve.start.pointId == curve.end.pointId ||
                !curve.recordedDistanceMeters.isFinite() || curve.recordedDistanceMeters < 0) return false
            val prior = previous
            if (prior != null && (curve.start.at < prior.at || curve.start.at == prior.at && curve.start != prior)) return false
            val minimum = curve.sampledMinSpeedKmh; val maximum = curve.sampledMaxSpeedKmh
            if ((minimum == null) != (maximum == null) || minimum != null && maximum != null &&
                (!minimum.isFinite() || !maximum.isFinite() || minimum < 0 || maximum > 360 || minimum > maximum)) return false
            previous = curve.end
        }
        return true
    }
    private fun identifier(value: String) = value.isNotBlank() && value.length <= 200 && value.none { it.code < 32 }
    private fun endpointValid(p: RecordedCurveEndpoint) = identifier(p.pointId) && p.at >= 0 &&
        p.latitude.isFinite() && p.latitude in -90.0..90.0 && p.longitude.isFinite() && p.longitude in -180.0..180.0
    private fun endpoint(p: RecordedCurveEndpoint) = JSONObject().put("pointId", p.pointId).put("at", p.at)
        .put("latitude", p.latitude).put("longitude", p.longitude)
    private fun endpoint(p: JSONObject) = RecordedCurveEndpoint(p.getString("pointId"), long(p, "at"), number(p, "latitude"), number(p, "longitude"))
    private fun number(j: JSONObject, key: String): Double = (j.get(key) as? Number ?: error("Missing number"))
        .toDouble().also { require(it.isFinite()) }
    private fun optionalNumber(j: JSONObject, key: String): Double? {
        require(j.has(key)); return if (j.isNull(key)) null else number(j, key)
    }
    private fun integer(j: JSONObject, key: String): Int = number(j, key).also {
        require(it >= 0 && it <= Int.MAX_VALUE && it % 1.0 == 0.0)
    }.toInt()
    private fun long(j: JSONObject, key: String): Long {
        val raw = j.get(key) as? Number ?: error("Missing time")
        if (raw is Long || raw is Int) return raw.toLong().also { require(it >= 0) }
        return raw.toDouble().also { require(it.isFinite() && it >= 0 && it < Long.MAX_VALUE.toDouble() && it % 1.0 == 0.0) }.toLong()
    }
}