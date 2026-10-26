package org.iz.navigation.power

import android.util.AtomicFile
import org.iz.navigation.data.*
import org.iz.navigation.integration.*
import org.iz.navigation.wearprotocol.WearDailyJourneyTotal
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

enum class AnalysisPhase { WAITING_END, WAITING_BATTERY, QUEUED, RUNNING, READY, FAILED }
data class AnalysisEntry(val fingerprint: String, val stats: JourneyStats, val health: JourneyHealthSummary,
    val watch: JourneyHealthSummary, val journey: Journey) {
    /** Display title/note are deliberately absent from durable analysis metadata. */
    fun matches(candidate: Journey): Boolean = candidate.status == JourneyStatus.CONFIRMED &&
        journey.status == JourneyStatus.CONFIRMED && journey.id == candidate.id && journey.transport == candidate.transport &&
        journey.startedAt == candidate.startedAt && journey.endedAt == candidate.endedAt &&
        journey.stepCount == candidate.stepCount && journey.interrupted == candidate.interrupted
}
data class AnalysisState(val phase: AnalysisPhase = AnalysisPhase.QUEUED, val pending: Boolean = true,
    val entries: Map<String, AnalysisEntry> = emptyMap(), val cacheRevision: String = "", val changed: Long = 0) {
    val label: String get() = when (phase) {
        AnalysisPhase.WAITING_END -> "Kayıt ve navigasyon bitince hazırlanacak"
        AnalysisPhase.WAITING_BATTERY -> "Pil seviyesi bekleniyor · En az %20"
        AnalysisPhase.RUNNING -> "Hazırlanıyor"
        AnalysisPhase.FAILED -> "Ayrıntılar hazırlanamadı. Ham kayıtların korunuyor."
        AnalysisPhase.QUEUED -> "Hazırlama sırası bekleniyor"
        AnalysisPhase.READY -> "Hazır"
    }
    fun ready(id: String): AnalysisEntry? = entries[id].takeUnless {
        pending || phase == AnalysisPhase.WAITING_END || phase == AnalysisPhase.RUNNING
    }
}

/** Disposable private results, outside the diary DB and every raw backup/restore format. */
internal class AnalysisCache(private val directory: File, private val onReadFailure: () -> Unit = {}) {
    private fun failedAsset(file: File) {
        // This is disposable output only. A UI read may mark a cache miss, never read raw
        // diary inputs or calculate geometry. Regeneration remains owned by the gated worker.
        AtomicFile(file).delete()
        onReadFailure()
    }
    private val index = File(directory, "index.json")
    private fun key(value: String) = digest(value)
    private fun asset(kind: String, id: String, fingerprint: String): File {
        require(fingerprint.matches(Regex("[a-f0-9]{64}")))
        return File(directory, "$kind-${key(id)}-$fingerprint.json")
    }
    fun hasPending() = File(directory, "pending").exists()
    @Synchronized fun pending(value: Boolean) {
        directory.mkdirs()
        val file = File(directory, "pending")
        if (value && !file.exists()) write(file, "1") else if (!value) AtomicFile(file).delete()
    }
    @Synchronized fun load(): Pair<AnalysisState, JSONObject?> = runCatching {
        val root = read(index) ?: return@runCatching AnalysisState() to null
        require(root.getInt("schema") == 2)
        val entries = linkedMapOf<String, AnalysisEntry>()
        root.getJSONArray("entries").each { j ->
            val id = j.getString("id")
            entries[id] = decodeEntry(j, id)
        }
        AnalysisState(AnalysisPhase.READY, hasPending(), entries, root.getString("revision"), root.getLong("changed")) to root.optJSONObject("daily")
    }.getOrElse { AnalysisState() to null }
    @Synchronized fun save(entries: Map<String, AnalysisEntry>, revision: String, daily: JSONObject?, changed: Long,
        checkActive: () -> Unit = {}) {
        val root = JSONObject().put("schema", 2).put("revision", revision).put("changed", changed).put("daily", daily ?: JSONObject.NULL)
            .put("entries", JSONArray().apply { entries.forEach { (id, entry) -> checkActive(); put(encodeEntry(entry).put("id", id)) } })
        checkActive()
        write(index, root.toString(), checkActive)
    }
    @Synchronized fun saveEntry(id: String, entry: AnalysisEntry, checkActive: () -> Unit = {}) {
        write(File(directory, "entry-${key(id)}.json"), encodeEntry(entry).toString(), checkActive)
    }
    @Synchronized fun entry(id: String, fingerprint: String): AnalysisEntry? = runCatching {
        val j = read(File(directory, "entry-${key(id)}.json")) ?: return@runCatching null
        if (j.getString("fingerprint") != fingerprint) null else decodeEntry(j, id)
    }.getOrNull()
    private fun encodeEntry(entry: AnalysisEntry) = JSONObject().put("fingerprint", entry.fingerprint)
        .put("stats", stats(entry.stats)).put("health", health(entry.health)).put("watch", health(entry.watch))
        .put("journey", journey(entry.journey))
    private fun decodeEntry(j: JSONObject, id: String) = AnalysisEntry(j.getString("fingerprint"), stats(j.getJSONObject("stats")),
        health(j.getJSONObject("health"), id), health(j.getJSONObject("watch"), id), journey(j.getJSONObject("journey"), id))
    @Synchronized fun saveTrail(id: String, fingerprint: String, trail: RecordedSpeedTrail, checkActive: () -> Unit = {}) {
        val root = JSONObject().put("fingerprint", fingerprint).put("json", trail.geoJson(checkActive))
            .put("paths", JSONArray().apply { trail.paths.forEach { checkActive(); put(coordinates(it, checkActive)) } })
            .put("scales", JSONArray().apply { trail.scales.forEach {
                checkActive(); put(JSONObject().put("id", it.journeyId).put("min", it.minKmh).put("max", it.maxKmh))
            } })
        checkActive()
        write(asset("trail", id, fingerprint), root.toString(), checkActive)
    }
    @Synchronized fun trail(id: String, fingerprint: String): RecordedTrailRender? {
        val file = runCatching { asset("trail", id, fingerprint) }.getOrNull() ?: return null
        val result = runCatching {
            val root = read(file) ?: return@runCatching null
            if (root.getString("fingerprint") != fingerprint) return@runCatching null
            val json = root.getString("json")
            val paths = root.getJSONArray("paths").objects { coordinates(getJSONArray(it)) }
            val scales = root.getJSONArray("scales").objects { getJSONObject(it).let { j -> JourneySpeedScale(j.getString("id"), j.getDouble("min"), j.getDouble("max")) } }
            val features = JSONObject(json).getJSONArray("features")
            val lines = features.objects { getJSONObject(it).let { f ->
                SpeedTrailLine(id, coordinates(f.getJSONObject("geometry").getJSONArray("coordinates")), f.getJSONObject("properties").getString("color"))
            } }
            RecordedTrailRender(RecordedSpeedTrail(paths, lines, scales), json, setOf(id))
        }.getOrNull()
        if (result == null) failedAsset(file)
        return result
    }
    @Synchronized fun hasTrail(id: String, fingerprint: String) = asset("trail", id, fingerprint).let { it.isFile && it.length() > 0 }
    @Synchronized fun saveHealthSamples(id: String, fingerprint: String, samples: List<JourneyHealthSample>, checkActive: () -> Unit = {}) {
        val items = JSONArray()
        samples.forEachIndexed { index, sample ->
            if (index % 256 == 0) checkActive()
            items.put(JSONObject().put("source", sample.sourceId).put("origin", sample.originPackage)
                .put("device", sample.deviceType ?: JSONObject.NULL).put("manufacturer", sample.deviceManufacturer ?: JSONObject.NULL)
                .put("model", sample.deviceModel ?: JSONObject.NULL).put("start", sample.startAt).put("end", sample.endAt).put("value", sample.value))
        }
        val root = JSONObject().put("fingerprint", fingerprint).put("samples", items)
        checkActive()
        write(asset("heart", id, fingerprint), root.toString(), checkActive)
    }
    /** Prepared valid HC heart series, loaded only by an open detail chart on an IO dispatcher. */
    @Synchronized fun healthSamples(id: String, fingerprint: String): List<JourneyHealthSample> {
        val file = runCatching { asset("heart", id, fingerprint) }.getOrNull() ?: return emptyList()
        val result = runCatching {
            val root = read(file) ?: return@runCatching null
            if (root.getString("fingerprint") != fingerprint) return@runCatching null
            root.getJSONArray("samples").objects { getJSONObject(it).let { sample ->
                JourneyHealthSample(id, sample.getString("source"), sample.getString("origin"), sample.longOrNull("device")?.toInt(),
                    sample.stringOrNull("manufacturer"), sample.stringOrNull("model"), HealthMetric.HEART_RATE_BPM,
                    sample.getLong("start"), sample.getLong("end"), sample.getDouble("value"))
            } }
        }.getOrNull()
        if (result == null) failedAsset(file)
        return result.orEmpty()
    }
    @Synchronized fun hasHealthSamples(id: String, fingerprint: String) = asset("heart", id, fingerprint).let { it.isFile && it.length() > 0 }
    private fun heatmapFile(revision: String, filter: String): File {
        require(revision.matches(Regex("[a-f0-9]{64}")))
        require(filter == "ALL" || Transport.entries.any { it.name == filter })
        return File(directory, "heatmap-$revision-$filter.json")
    }
    @Synchronized fun saveHeatmap(revision: String, filter: String, render: HeatmapRender, checkActive: () -> Unit = {}) {
        write(heatmapFile(revision, filter), JSONObject().put("json", render.json).put("count", render.data.journeyCount)
            .put("bounds", coordinates(render.bounds, checkActive)).toString(), checkActive)
    }
    @Synchronized fun heatmap(revision: String, filter: String): HeatmapRender? {
        if (revision.isEmpty()) return null
        val file = runCatching { heatmapFile(revision, filter) }.getOrNull() ?: return null
        val result = runCatching {
            val j = read(file) ?: return@runCatching null
            val json = j.getString("json")
            val geometry = JSONObject(json)
            require(geometry.getString("type") == "FeatureCollection")
            geometry.getJSONArray("features")
            HeatmapRender(HeatmapData(journeyCount = j.getInt("count")), json, coordinates(j.getJSONArray("bounds")))
        }.getOrNull()
        if (result == null) failedAsset(file)
        return result
    }
    @Synchronized fun hasHeatmap(revision: String, filter: String): Boolean = runCatching {
        heatmapFile(revision, filter).let { it.isFile && it.length() > 0 }
    }.getOrDefault(false)
    @Synchronized fun cleanup(entries: Map<String, AnalysisEntry>, revision: String, checkActive: () -> Unit = {}) {
        val assets = entries.map { (id, entry) -> "${key(id)}-${entry.fingerprint}" }.toSet()
        val ids = entries.keys.map(::key).toSet()
        directory.listFiles()?.forEach { file ->
            checkActive()
            val name = file.name
            if (!name.endsWith(".json")) return@forEach
            if (name.startsWith("heatmap-") && !name.startsWith("heatmap-$revision-")) AtomicFile(file).delete()
            if (name.startsWith("entry-") && name.removePrefix("entry-").removeSuffix(".json") !in ids) AtomicFile(file).delete()
            if ((name.startsWith("trail-") || name.startsWith("heart-")) &&
                name.removePrefix("trail-").removePrefix("heart-").removeSuffix(".json") !in assets) AtomicFile(file).delete()
        }
    }
    private fun write(file: File, text: String, checkActive: () -> Unit = {}) {
        directory.mkdirs()
        val atomic = AtomicFile(file)
        checkActive()
        val output = atomic.startWrite()
        try {
            val bytes = text.toByteArray(Charsets.UTF_8)
            var offset = 0
            while (offset < bytes.size) {
                checkActive()
                val size = minOf(8_192, bytes.size - offset)
                output.write(bytes, offset, size); offset += size
            }
            checkActive(); atomic.finishWrite(output)
        } catch (error: Throwable) { atomic.failWrite(output); throw error }
    }
    private fun read(file: File): JSONObject? {
        val atomic = AtomicFile(file)
        if (!atomic.baseFile.exists() && !File(file.path + ".bak").exists()) return null
        return atomic.openRead().bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
    }
    private fun coordinates(values: List<GeoCoordinate>, checkActive: () -> Unit = {}) = JSONArray().apply {
        values.forEachIndexed { index, coordinate -> if (index % 256 == 0) checkActive(); put(JSONArray().put(coordinate.longitude).put(coordinate.latitude)) }
    }
    private fun coordinates(values: JSONArray): List<GeoCoordinate> = values.objects { getJSONArray(it).let { p -> GeoCoordinate(p.getDouble(1), p.getDouble(0)) } }
    // Only the recording metadata required by summaries is retained; personal title/note is not duplicated.
    private fun journey(j: Journey) = JSONObject().put("transport", j.transport.name).put("start", j.startedAt)
        .put("end", j.endedAt ?: JSONObject.NULL).put("steps", j.stepCount ?: JSONObject.NULL).put("interrupted", j.interrupted)
    private fun journey(j: JSONObject, id: String) = Journey(id = id, transport = Transport.valueOf(j.getString("transport")),
        startedAt = j.getLong("start"), endedAt = j.longOrNull("end"), stepCount = j.longOrNull("steps"), interrupted = j.getBoolean("interrupted"))
    private fun stats(s: JourneyStats) = JSONObject().put("distance", s.distanceMeters).put("elapsed", s.elapsedMillis)
        .put("observed", s.observedMillis).put("moving", s.movingMillis).put("stopped", s.stoppedMillis).put("missing", s.unobservedMillis)
        .put("average", s.averageSpeedKmh ?: JSONObject.NULL).put("movingAverage", s.movingAverageSpeedKmh ?: JSONObject.NULL).put("max", s.maxSpeedKmh ?: JSONObject.NULL)
    private fun stats(j: JSONObject) = JourneyStats(j.getDouble("distance"), j.getLong("elapsed"), j.getLong("observed"),
        j.getLong("moving"), j.getLong("stopped"), j.getLong("missing"), j.doubleOrNull("average"), j.doubleOrNull("movingAverage"), j.doubleOrNull("max"))
    private fun health(h: JourneyHealthSummary) = JSONObject().put("mean", h.heartRateMeanBpm ?: JSONObject.NULL)
        .put("min", h.heartRateMinBpm ?: JSONObject.NULL).put("max", h.heartRateMaxBpm ?: JSONObject.NULL)
        .put("latest", h.latestHeartRateBpm ?: JSONObject.NULL).put("latestAt", h.latestHeartRateAt ?: JSONObject.NULL).put("count", h.heartRateSampleCount)
        .put("calories", h.totalCaloriesKcal ?: JSONObject.NULL).put("calorieCoverage", h.calorieCoverageMillis)
        .put("steps", h.watchSteps ?: JSONObject.NULL).put("stepCoverage", h.stepCoverageMillis)
        .put("start", h.measurementStartAt ?: JSONObject.NULL).put("end", h.measurementEndAt ?: JSONObject.NULL)
        .put("checked", JSONObject().apply { h.metricCheckedAt.forEach { (key, value) -> put(key.name, value) } })
    private fun health(j: JSONObject, id: String) = JourneyHealthSummary(id, j.doubleOrNull("mean"), j.doubleOrNull("min"),
        j.doubleOrNull("max"), j.doubleOrNull("latest"), j.longOrNull("latestAt"), j.getInt("count"), j.doubleOrNull("calories"),
        j.getLong("calorieCoverage"), j.longOrNull("steps"), j.getLong("stepCoverage"), j.longOrNull("start"), j.longOrNull("end"),
        HealthMetric.entries.mapNotNull { metric -> j.getJSONObject("checked").longOrNull(metric.name)?.let { metric to it } }.toMap())
    companion object {
        fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        private fun JSONObject.doubleOrNull(key: String) = if (isNull(key)) null else getDouble(key).takeIf { it.isFinite() }
        private fun JSONObject.longOrNull(key: String) = if (isNull(key)) null else getLong(key)
        private fun JSONObject.stringOrNull(key: String) = if (isNull(key)) null else getString(key)
        private fun JSONArray.each(block: (JSONObject) -> Unit) { for (i in 0 until length()) block(getJSONObject(i)) }
        private fun <T> JSONArray.objects(block: JSONArray.(Int) -> T): List<T> = (0 until length()).map { block(it) }
        fun daily(day: DailyActivityDay, totals: DailyJourneyTotals) = JSONObject().put("date", day.localDate).put("zone", day.zoneId).apply {
            fun total(key: String, value: WearDailyJourneyTotal?) { put(key, value?.let { JSONObject().put("distance", it.distanceMeters).put("elapsed", it.elapsedMillis).put("count", it.journeyCount) } ?: JSONObject.NULL) }
            total("vehicle", totals.vehicle); total("walk", totals.walkingRunning); total("cycle", totals.cycling)
        }
        fun daily(j: JSONObject?, day: DailyActivityDay): DailyJourneyTotals? = runCatching {
            if (j == null || j.getString("date") != day.localDate || j.getString("zone") != day.zoneId) return@runCatching null
            fun total(key: String) = j.optJSONObject(key)?.let { WearDailyJourneyTotal(it.getDouble("distance"), it.getLong("elapsed"), it.getInt("count")) }
            DailyJourneyTotals(total("vehicle")!!, total("walk")!!, total("cycle"))
        }.getOrNull()
    }
}
