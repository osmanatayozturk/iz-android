package org.iz.navigation.power

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import org.iz.navigation.BuildConfig
import org.json.JSONObject
import java.io.File
import java.io.PrintWriter
import java.time.Instant
import java.time.ZoneOffset

internal data class PhoneBattery(val percent: Int?, val charging: Boolean?, val temperatureC: Double?,
    val currentMicroAmps: Int?, val chargeMicroAmpHours: Int?)

internal object PhoneBatteryReader {
    fun read(context: Context): PhoneBattery {
        val manager = context.getSystemService(BatteryManager::class.java)
        val intent = runCatching { context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
        fun property(id: Int) = runCatching { manager?.getIntProperty(id) }.getOrNull()?.takeUnless { it == Int.MIN_VALUE }
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = property(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
            ?: if (level >= 0 && scale > 0) (level * 100L / scale).toInt().takeIf { it in 0..100 } else null
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val temperature = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        return PhoneBattery(percent, status.takeIf { it >= 0 }?.let {
            it == BatteryManager.BATTERY_STATUS_CHARGING || it == BatteryManager.BATTERY_STATUS_FULL
        }, temperature?.takeUnless { it == Int.MIN_VALUE }?.div(10.0),
            property(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW), property(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER))
    }
}

/** Fixed-schema local counters. No API accepts locations, text, IDs, URLs or health measurements. */
internal object PowerDiagnostics {
    enum class Metric { GPS_CALLBACK, GPS_POINT_SAVED, ROUTE_PLAN, TRAFFIC_REFRESH, OFF_ROUTE_PLAN,
        MAP_POSITION, MAP_ROUTE, MAP_TRAIL, WATCH_PUBLISH, WATCH_RECEIVE, WEATHER_REQUEST, GROUP_REQUEST, ANALYSIS }
    enum class Event { STARTUP, APP_VISIBLE, APP_HIDDEN, RECORDING_STARTED, RECORDING_STOPPED,
        GUIDANCE_STARTED, GUIDANCE_STOPPED, ANALYSIS_STARTED, ANALYSIS_PAUSED, ANALYSIS_FINISHED }
    private val stateLock = Any()
    private val fileLock = Any()
    private val counts = LongArray(Metric.entries.size)
    private val times = LongArray(Metric.entries.size)
    private var lastSample = -60_000L
    private var sequence = 0L
    @Volatile private var context: Context? = null
    private var foreground = false
    private var recording = false
    private var guidance = false
    private var processing = false
    private var pending = false
    private var analysisPhase = AnalysisPhase.QUEUED
    private data class Snapshot(val sequence: Long, val at: Long, val elapsed: Long, val event: Event?,
        val foreground: Boolean, val recording: Boolean, val guidance: Boolean, val processing: Boolean,
        val pending: Boolean, val phase: AnalysisPhase, val counts: LongArray, val times: LongArray)
    // One consumer preserves event order; a bounded mailbox never holds up a GPS or UI callback.
    private val samples = Channel<Snapshot>(64, BufferOverflow.DROP_OLDEST)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private const val MAX_BYTES = 2L * 1024 * 1024
    private const val CHUNK_BYTES = 256L * 1024
    private const val RETENTION = 7L * 86_400_000
    const val DUMP_ARGUMENT = "iz-battery-diagnostics"

    fun initialize(application: Application) {
        synchronized(stateLock) {
            if (context != null) return
            context = application.applicationContext
            scope.launch { for (snapshot in samples) runCatching { append(snapshot) } }
            enqueue(Event.STARTUP)
        }
    }
    /** Counts and durations are cumulative since this process's STARTUP; each completion increments once. */
    fun count(metric: Metric, elapsedNanos: Long = 0) = synchronized(stateLock) {
        counts[metric.ordinal]++
        if (elapsedNanos > 0) times[metric.ordinal] += elapsedNanos / 1_000_000
    }
    fun foreground(value: Boolean) = synchronized(stateLock) {
        if (foreground != value) { foreground = value; enqueue(if (value) Event.APP_VISIBLE else Event.APP_HIDDEN) }
    }
    fun state(isRecording: Boolean, isGuiding: Boolean) {
        synchronized(stateLock) {
            val recordChanged = recording != isRecording
            val guidanceChanged = guidance != isGuiding
            recording = isRecording; guidance = isGuiding
            if (recordChanged) enqueue(if (isRecording) Event.RECORDING_STARTED else Event.RECORDING_STOPPED)
            if (guidanceChanged) enqueue(if (isGuiding) Event.GUIDANCE_STARTED else Event.GUIDANCE_STOPPED)
        }
        sampleIfDue()
    }
    fun analysis(isPending: Boolean, isProcessing: Boolean, phase: AnalysisPhase? = null) = synchronized(stateLock) {
        pending = isPending; processing = isProcessing
        if (phase != null) analysisPhase = phase
    }
    fun sampleIfDue() = synchronized(stateLock) {
        if (!foreground && !recording && !guidance && !processing) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastSample >= 60_000) { lastSample = now; enqueue(null) }
    }
    fun event(value: Event) = synchronized(stateLock) { enqueue(value) }
    private fun enqueue(event: Event?) {
        if (context == null) return
        samples.trySend(Snapshot(++sequence, System.currentTimeMillis(), SystemClock.elapsedRealtime(), event,
            foreground, recording, guidance, processing, pending, analysisPhase, counts.copyOf(), times.copyOf()))
    }
    private fun append(snapshot: Snapshot) {
        val app = context ?: return
        val battery = PhoneBatteryReader.read(app)
        val item = JSONObject().put("schema", 1).put("sequence", snapshot.sequence).put("at", snapshot.at)
            .put("elapsedMs", snapshot.elapsed).put("version", BuildConfig.VERSION_NAME).put("code", BuildConfig.VERSION_CODE)
            .put("event", snapshot.event?.name ?: "SAMPLE").put("foreground", snapshot.foreground)
            .put("screenInteractive", app.getSystemService(PowerManager::class.java)?.isInteractive ?: JSONObject.NULL)
            .put("recording", snapshot.recording).put("guidance", snapshot.guidance)
            .put("processing", snapshot.processing).put("pending", snapshot.pending).put("analysisPhase", snapshot.phase.name)
            .put("batteryPercent", battery.percent ?: JSONObject.NULL).put("charging", battery.charging ?: JSONObject.NULL)
            .put("temperatureC", battery.temperatureC ?: JSONObject.NULL)
            .put("currentMicroAmps", battery.currentMicroAmps ?: JSONObject.NULL)
            .put("chargeMicroAmpHours", battery.chargeMicroAmpHours ?: JSONObject.NULL)
            .put("processCpuMs", Process.getElapsedCpuTime()).put("counterMode", "PROCESS_CUMULATIVE")
            .put("counters", JSONObject().apply { Metric.entries.forEach { metric ->
                put(metric.name, JSONObject().put("count", snapshot.counts[metric.ordinal]).put("durationMs", snapshot.times[metric.ordinal]))
            } })
        synchronized(fileLock) {
            val directory = File(app.noBackupFilesDir, "power_diagnostics").apply { mkdirs() }
            prune(directory, snapshot.at)
            val day = Instant.ofEpochMilli(snapshot.at).atZone(ZoneOffset.UTC).toLocalDate().toString()
            val last = files(directory).lastOrNull { it.name.startsWith("battery-$day-") }
            val output = last?.takeIf { it.length() < CHUNK_BYTES }
                ?: File(directory, "battery-$day-${snapshot.at.toString().padStart(13, '0')}.jsonl")
            output.appendText(item.toString() + "\n", Charsets.UTF_8)
            prune(directory, snapshot.at)
        }
    }
    private fun files(directory: File) = directory.listFiles()?.filter {
        it.isFile && it.name.matches(Regex("battery-\\d{4}-\\d{2}-\\d{2}-\\d+\\.jsonl"))
    }?.sortedBy { it.name }.orEmpty()
    // A later append must not renew older samples. Chunk names contain their oldest event time.
    private fun firstRecordedAt(file: File): Long = file.name.removeSuffix(".jsonl").substringAfterLast('-').toLongOrNull() ?: 0L
    private fun prune(directory: File, now: Long) {
        files(directory).filter { now - firstRecordedAt(it) >= RETENTION }.forEach { it.delete() }
        val retained = files(directory)
        var size = retained.sumOf { it.length() }
        for (file in retained) { if (size <= MAX_BYTES) break; val length = file.length(); if (file.delete()) size -= length }
    }
    /** Activity.dump is the only export path. Reading neither creates nor prunes diagnostic files. */
    fun dump(writer: PrintWriter) {
        val app = context ?: return
        synchronized(fileLock) {
            val directory = File(app.noBackupFilesDir, "power_diagnostics")
            val now = System.currentTimeMillis()
            writer.println("IZ_BATTERY_DIAGNOSTICS_V1")
            var remaining = MAX_BYTES
            for (file in files(directory).filter { now - firstRecordedAt(it) in 0 until RETENTION }) {
                if (file.length() > remaining) continue
                remaining -= file.length()
                runCatching { file.useLines(Charsets.UTF_8) { lines -> lines.forEach { line ->
                    if (line.length <= 16_384 && runCatching {
                        val row = JSONObject(line)
                        validDumpRow(row) && now - row.getLong("at") in 0 until RETENTION
                    }.getOrDefault(false)) writer.println(line)
                } } }
            }
            writer.println("IZ_BATTERY_DIAGNOSTICS_END")
        }
    }
    private fun validDumpRow(row: JSONObject): Boolean {
        val allowed = setOf("schema", "sequence", "at", "elapsedMs", "version", "code", "event", "foreground", "screenInteractive",
            "recording", "guidance", "processing", "pending", "batteryPercent", "charging", "temperatureC", "currentMicroAmps",
            "chargeMicroAmpHours", "processCpuMs", "counterMode", "counters", "analysisPhase")
        if (row.keys().asSequence().any { it !in allowed } || row.getInt("schema") != 1 ||
            row.getString("event") !in Event.entries.map { it.name } + "SAMPLE" ||
            row.getString("analysisPhase") !in AnalysisPhase.entries.map { it.name } ||
            row.getString("counterMode") != "PROCESS_CUMULATIVE" || !row.getString("version").matches(Regex("[0-9A-Za-z.+-]{1,40}"))) return false
        val numeric = setOf("schema", "sequence", "at", "elapsedMs", "code", "batteryPercent", "temperatureC",
            "currentMicroAmps", "chargeMicroAmpHours", "processCpuMs")
        val booleans = setOf("foreground", "screenInteractive", "recording", "guidance", "processing", "pending", "charging")
        if (numeric.any { name -> !row.isNull(name) && (row.get(name) !is Number || !(row.get(name) as Number).toDouble().isFinite()) } ||
            booleans.any { name -> !row.isNull(name) && row.get(name) !is Boolean }) return false
        val counters = row.getJSONObject("counters")
        return counters.keys().asSequence().all { name -> name in Metric.entries.map { it.name } &&
            counters.getJSONObject(name).let { value -> value.keys().asSequence().all { it == "count" || it == "durationMs" } &&
                value.get("count") is Number && value.get("durationMs") is Number &&
                value.getLong("count") >= 0 && value.getLong("durationMs") >= 0 } }
    }
}
