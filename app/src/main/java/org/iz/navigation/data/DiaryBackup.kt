package org.iz.navigation.data

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** User-initiated, versioned ZIP backup; journal content is never uploaded automatically. */
class DiaryBackup(context: Context, private val repository: DiaryRepository) {
    private val context = context.applicationContext

    suspend fun exportTo(uri: Uri, includeHealth: Boolean = false) = withContext(Dispatchers.IO) {
        val snapshot = exportable(repository.snapshot(), includeHealth)
        DiaryRules.validate(snapshot)
        validateMapSnapshot(snapshot)
        val paths = snapshot.photos.map { it.relativePath }.distinct()
        require(paths.size <= MAX_FILES) { "Yedekte çok fazla fotoğraf var." }
        val totalBytes = paths.sumOf { path ->
            val file = repository.photoFile(path)
            require(file.isFile && file.length() <= MAX_PHOTO_BYTES) { "Fotoğraf eksik veya 50 MB sınırından büyük: $path" }
            file.length()
        }
        require(totalBytes <= MAX_TOTAL_BYTES) { "Yedek 512 MB sınırını aşıyor." }
        val manifest = BackupJson.encode(snapshot, includeHealth).toString().toByteArray(Charsets.UTF_8)
        require(manifest.size <= MAX_MANIFEST_BYTES) { "Günlük verisi 32 MB sınırını aşıyor." }
        require(totalBytes + manifest.size <= MAX_TOTAL_BYTES) { "Yedek 512 MB sınırını aşıyor." }
        val output = context.contentResolver.openOutputStream(uri, "wt")
            ?: error("Yedek dosyası açılamadı.")
        ZipOutputStream(output.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(manifest)
            zip.closeEntry()
            paths.forEach { path ->
                zip.putNextEntry(ZipEntry(path))
                repository.photoFile(path).inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    suspend fun importFrom(uri: Uri, afterReplace: suspend () -> Unit = {}) = withContext(Dispatchers.IO) {
        val staging = File(context.cacheDir, "diary-import-${UUID.randomUUID()}")
        check(staging.mkdirs()) { "Yedek için geçici alan oluşturulamadı." }
        val installed = mutableListOf<File>()
        var committed = false
        try {
            val extracted = linkedMapOf<String, File>()
            var expandedBytes = 0L
            val input = context.contentResolver.openInputStream(uri) ?: error("Yedek dosyası açılamadı.")
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(!entry.isDirectory) { "Yedekte beklenmeyen klasör var." }
                    val name = entry.name
                    require(name == MANIFEST || DiaryRules.isSafePhotoPath(name)) { "Güvenli olmayan yedek yolu." }
                    require(name !in extracted && extracted.size <= MAX_FILES) { "Yinelenen dosya veya çok fazla dosya." }
                    // Untrusted ZIP paths are never used as filesystem paths.
                    val file = File(staging, "entry-${extracted.size}")
                    val limit = if (name == MANIFEST) MAX_MANIFEST_BYTES.toLong() else MAX_PHOTO_BYTES
                    val copied = file.outputStream().use { copyLimited(zip, it, limit, MAX_TOTAL_BYTES - expandedBytes) }
                    expandedBytes += copied
                    extracted[name] = file
                    zip.closeEntry()
                }
            }
            val manifest = extracted[MANIFEST] ?: error("Yedek içeriği bulunamadı.")
            val snapshot = BackupJson.decode(JSONObject(manifest.readText(Charsets.UTF_8)))
            DiaryRules.validate(snapshot)
            validateMapSnapshot(snapshot)
            require(snapshot.journeys.none { it.status == JourneyStatus.TEMPORARY }) { "Yedek geçici yolculuk içeriyor." }
            val photoPaths = snapshot.photos.map { it.relativePath }.toSet()
            require(extracted.keys == photoPaths + MANIFEST) { "Yedek fotoğrafları eksik veya beklenmeyen dosyalar içeriyor." }
            // Install under fresh names before the transaction, preserving every old photo on failure.
            val remapped = photoPaths.associateWith { original ->
                val extension = original.substringAfterLast('.', "jpg").lowercase()
                    .takeIf { it.matches(Regex("[a-z0-9]{1,8}")) } ?: "jpg"
                val relativePath = "photos/${UUID.randomUUID()}.$extension"
                val target = repository.photoFile(relativePath)
                check(target.parentFile?.mkdirs() == true || target.parentFile?.isDirectory == true)
                installed += target
                extracted.getValue(original).inputStream().use { source -> target.outputStream().use { source.copyTo(it) } }
                relativePath
            }
            // A cancellation after the DB commits must not make finally remove its newly installed photos.
            withContext(NonCancellable) {
                repository.restore(snapshot.copy(photos = snapshot.photos.map { it.copy(relativePath = remapped.getValue(it.relativePath)) }))
                committed = true
                // Recording teardown must never change the old diary if the transaction fails.
                afterReplace()
            }
        } finally {
            if (!committed) installed.forEach { runCatching { it.delete() } }
            // Staging was created by this method under our private cache, never from a ZIP path.
            staging.listFiles()?.forEach { it.delete() }
            staging.delete()
        }
    }

    private fun exportable(snapshot: DiarySnapshot, includeHealth: Boolean): DiarySnapshot {
        val journeys = snapshot.journeys.filter { it.status == JourneyStatus.CONFIRMED }
        val ids = journeys.map { it.id }.toSet()
        val photos = snapshot.photos.filter { it.visitId != null || it.journeyId in ids }.map {
            if (it.journeyId != null && it.journeyId !in ids) it.copy(journeyId = null) else it
        }
        val photoIds = photos.map { it.id }.toSet()
        return snapshot.copy(
            journeys = journeys,
            points = snapshot.points.filter { it.journeyId in ids },
            visits = snapshot.visits.map { if (it.journeyId != null && it.journeyId !in ids) it.copy(journeyId = null) else it },
            photos = photos,
            drafts = snapshot.drafts.map { it.copy(photoIds = it.photoIds.filter { id -> id in photoIds }) },
            healthSamples = if (includeHealth) snapshot.healthSamples.filter { it.journeyId in ids } else emptyList(),
            watchHealthSessions = if (includeHealth) snapshot.watchHealthSessions.filter { it.journeyId in ids } else emptyList(),
            watchHealthSamples = if (includeHealth) snapshot.watchHealthSamples.filter { it.journeyId in ids } else emptyList(),
        )
    }

    private fun copyLimited(input: InputStream, output: OutputStream, limit: Long, remaining: Long): Long {
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= limit && total <= remaining) { "Yedek açıldığında izin verilen boyutu aşıyor." }
            output.write(buffer, 0, count)
        }
        return total
    }

    companion object {
        private const val MANIFEST = "manifest.json"
        private const val MAX_FILES = 10_000
        private const val MAX_MANIFEST_BYTES = 32 * 1024 * 1024
        private const val MAX_PHOTO_BYTES = 50 * 1024 * 1024L
        private const val MAX_TOTAL_BYTES = 512 * 1024 * 1024L
    }
}

internal object BackupJson {
    private const val VERSION = 6
    private const val FORMAT = "org.iz.navigation.backup"
    // Read compatibility for user-created backups from the former application identity.
    private const val LEGACY_FORMAT = "com.atay.iz.backup"
    private fun json(vararg pairs: Pair<String, Any?>): JSONObject = JSONObject().apply {
        pairs.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
    }
    private fun <T> array(values: List<T>, encode: (T) -> JSONObject): JSONArray = JSONArray().apply { values.forEach { put(encode(it)) } }

    fun encode(value: DiarySnapshot, includeHealth: Boolean = false): JSONObject = json(
        "format" to FORMAT, "version" to VERSION, "exportedAt" to System.currentTimeMillis(),
        "journeys" to array(value.journeys) { json("id" to it.id, "title" to it.title, "transport" to it.transport.name, "status" to it.status.name,
            "startedAt" to it.startedAt, "endedAt" to it.endedAt, "expiresAt" to it.expiresAt, "note" to it.note,
            "interrupted" to it.interrupted, "stepCount" to it.stepCount) },
        "points" to array(value.points) { json("id" to it.id, "journeyId" to it.journeyId, "latitude" to it.latitude, "longitude" to it.longitude,
            "recordedAt" to it.recordedAt, "accuracy" to it.accuracy, "speed" to it.speed, "altitude" to it.altitude, "breakBefore" to it.breakBefore) },
        "places" to array(value.places) { json("id" to it.id, "name" to it.name, "latitude" to it.latitude, "longitude" to it.longitude,
            "googlePlaceId" to it.googlePlaceId, "osmType" to it.osmType?.name, "osmId" to it.osmId, "source" to it.source.name, "sortOrder" to it.sortOrder) },
        "visits" to array(value.visits) { json("id" to it.id, "placeId" to it.placeId, "journeyId" to it.journeyId, "visitedAt" to it.visitedAt, "note" to it.note, "rating" to it.rating) },
        "photos" to array(value.photos) { json("id" to it.id, "relativePath" to it.relativePath, "journeyId" to it.journeyId, "visitId" to it.visitId,
            "takenAt" to it.takenAt, "latitude" to it.latitude, "longitude" to it.longitude, "caption" to it.caption) },
        "drafts" to array(value.drafts) { json("id" to it.id, "visitId" to it.visitId, "text" to it.text, "rating" to it.rating,
            "photoIds" to JSONArray(it.photoIds), "markedSharedAt" to it.markedSharedAt) },
        "contributions" to array(value.contributions) { json("id" to it.id, "placeId" to it.placeId, "latitude" to it.latitude, "longitude" to it.longitude,
            "observedAt" to it.observedAt, "kind" to it.kind.name, "text" to it.text, "status" to it.status.name, "osmType" to it.osmType?.name,
            "osmId" to it.osmId, "remoteNoteId" to it.remoteNoteId, "remoteStatus" to it.remoteStatus,
            "submittedAt" to it.submittedAt, "submittedBy" to it.submittedBy, "error" to it.error) },
        "healthSamples" to array(if (includeHealth) value.healthSamples else emptyList()) {
            json("journeyId" to it.journeyId, "sourceId" to it.sourceId, "originPackage" to it.originPackage,
                "deviceType" to it.deviceType, "deviceManufacturer" to it.deviceManufacturer, "deviceModel" to it.deviceModel,
                "metric" to it.metric.name, "startAt" to it.startAt, "endAt" to it.endAt, "value" to it.value)
        },
        "mapEdits" to array(value.mapEdits) { json("id" to it.id, "placeId" to it.placeId,
            "latitude" to it.latitude, "longitude" to it.longitude, "preset" to it.preset.name,
            "name" to it.name, "street" to it.street, "houseNumber" to it.houseNumber,
            "observedAt" to it.observedAt, "surveyConfirmed" to it.surveyConfirmed,
            "status" to it.status.name, "stage" to it.stage.name, "submittedAt" to it.submittedAt,
            "submittedBy" to it.submittedBy, "changesetId" to it.changesetId,
            "remoteNodeId" to it.remoteNodeId, "remoteNodeVersion" to it.remoteNodeVersion,
            "changesetClosed" to it.changesetClosed, "error" to it.error) },
        "watchHealthSessions" to array(if (includeHealth) value.watchHealthSessions else emptyList()) {
            json("id" to it.id, "journeyId" to it.journeyId, "watchId" to it.watchId,
                "deviceName" to it.deviceName, "createdAt" to it.createdAt)
        },
        "watchHealthSamples" to array(if (includeHealth) value.watchHealthSamples else emptyList()) {
            json("sessionId" to it.sessionId, "sequence" to it.sequence, "journeyId" to it.journeyId,
                "metric" to it.metric.name, "startAt" to it.startAt, "endAt" to it.endAt, "value" to it.value)
        },
    ).also {
        PlaceOrderRules.validate(value.places)
        validateMapSnapshot(value)
        value.mapEdits.forEach(::validateMapEdit)
        if (includeHealth) {
            HealthRules.validateSnapshot(value)
            WatchHealthRules.validateSnapshot(value.journeys, value.watchHealthSessions, value.watchHealthSamples)
        }
    }

    fun decode(value: JSONObject): DiarySnapshot {
        val format = value.getString("format")
        require(format == FORMAT || format == LEGACY_FORMAT) { "Bu dosya bir İz yedeği değil." }
        val version = value.getInt("version")
        require(version in 1..VERSION) { "Bu yedek sürümü desteklenmiyor." }
        fun <T> records(key: String, parse: (JSONObject) -> T): List<T> {
            val values = value.getJSONArray(key)
            require(values.length() <= 500_000) { "Yedekte çok fazla kayıt var." }
            return List(values.length()) { parse(values.getJSONObject(it)) }
        }
        return DiarySnapshot(
            journeys = records("journeys") { Journey(it.getString("id"), it.getString("title"), Transport.valueOf(it.getString("transport")),
                JourneyStatus.valueOf(it.getString("status")), it.getLong("startedAt"), it.nullLong("endedAt"), it.nullLong("expiresAt"),
                it.getString("note"), it.getBoolean("interrupted"), if (it.has("stepCount")) it.nullLong("stepCount") else null) },
            points = records("points") { TrackPoint(it.getString("id"), it.getString("journeyId"), it.getDouble("latitude"), it.getDouble("longitude"),
                it.getLong("recordedAt"), it.getDouble("accuracy").toFloat(), it.nullDouble("speed")?.toFloat(), it.nullDouble("altitude"), it.getBoolean("breakBefore")) },
            places = records("places") { Place(it.getString("id"), it.getString("name"), it.nullDouble("latitude"), it.nullDouble("longitude"), it.nullString("googlePlaceId"),
                if (version >= 3) it.nullString("osmType")?.let(OsmType::valueOf) else null,
                if (version >= 3) it.strictNullLong("osmId") else null,
                if (version >= 3) PlaceSource.valueOf(it.getString("source")) else PlaceSource.LEGACY,
                if (version >= 6) it.strictLong("sortOrder") else 0L) },
            visits = records("visits") { Visit(it.getString("id"), it.getString("placeId"), it.nullString("journeyId"), it.getLong("visitedAt"), it.getString("note"), it.nullInt("rating")) },
            photos = records("photos") { Photo(it.getString("id"), it.getString("relativePath"), it.nullString("journeyId"), it.nullString("visitId"),
                it.nullLong("takenAt"), it.nullDouble("latitude"), it.nullDouble("longitude"), it.getString("caption")) },
            drafts = records("drafts") {
                val ids = it.getJSONArray("photoIds")
                require(ids.length() <= 10_000)
                ShareDraft(it.getString("id"), it.getString("visitId"), it.getString("text"), it.nullInt("rating"),
                    List(ids.length()) { index -> ids.getString(index) }, it.nullLong("markedSharedAt"))
            },
            contributions = if (version < 3) emptyList() else records("contributions") {
                ContributionDraft(id = it.getString("id"), placeId = it.nullString("placeId"), latitude = it.getDouble("latitude"), longitude = it.getDouble("longitude"),
                    observedAt = it.strictLong("observedAt"), kind = ContributionKind.valueOf(it.getString("kind")), text = it.getString("text"),
                    status = ContributionStatus.valueOf(it.getString("status")), osmType = it.nullString("osmType")?.let(OsmType::valueOf), osmId = it.strictNullLong("osmId"),
                    remoteNoteId = it.strictNullLong("remoteNoteId"), remoteStatus = it.nullString("remoteStatus"), submittedAt = it.strictNullLong("submittedAt"),
                    submittedBy = it.strictNullLong("submittedBy"), error = it.nullString("error"))
            },
            healthSamples = if (version < 4) emptyList() else records("healthSamples") {
                JourneyHealthSample(journeyId = it.strictString("journeyId"), sourceId = it.strictString("sourceId"),
                    originPackage = it.strictString("originPackage"), deviceType = it.strictNullInt("deviceType"),
                    deviceManufacturer = it.nullString("deviceManufacturer"), deviceModel = it.nullString("deviceModel"),
                    metric = HealthMetric.valueOf(it.strictString("metric")), startAt = it.strictLong("startAt"),
                    endAt = it.strictLong("endAt"), value = it.strictDouble("value"))
            },
            mapEdits = if (version < 5) emptyList() else records("mapEdits") {
                MapEditDraft(id = it.strictString("id"), placeId = it.nullString("placeId"),
                    latitude = it.strictDouble("latitude"), longitude = it.strictDouble("longitude"),
                    preset = MapPlacePreset.valueOf(it.strictString("preset")), name = it.strictString("name"),
                    street = it.strictString("street"), houseNumber = it.strictString("houseNumber"),
                    observedAt = it.strictLong("observedAt"), surveyConfirmed = it.strictBoolean("surveyConfirmed"),
                    status = MapEditStatus.valueOf(it.strictString("status")), stage = MapEditStage.valueOf(it.strictString("stage")),
                    submittedAt = it.strictNullLong("submittedAt"), submittedBy = it.strictNullLong("submittedBy"),
                    changesetId = it.strictNullLong("changesetId"), remoteNodeId = it.strictNullLong("remoteNodeId"),
                    remoteNodeVersion = it.strictNullLong("remoteNodeVersion"), changesetClosed = it.strictBoolean("changesetClosed"),
                    error = it.nullString("error"))
            },
            watchHealthSessions = if (version < 5) emptyList() else records("watchHealthSessions") {
                WatchHealthSession(it.strictString("id"), it.strictString("journeyId"), it.strictString("watchId"),
                    it.strictString("deviceName"), it.strictLong("createdAt"), acceptsUploads = false)
            },
            watchHealthSamples = if (version < 5) emptyList() else records("watchHealthSamples") {
                WatchHealthSample(it.strictString("sessionId"), it.strictLong("sequence"), it.strictString("journeyId"),
                    HealthMetric.valueOf(it.strictString("metric")), it.strictLong("startAt"), it.strictLong("endAt"), it.strictDouble("value"))
            },
        ).let { snapshot ->
            if (version < 6) snapshot.copy(places = PlaceOrderRules.legacy(snapshot.places, snapshot.visits)) else snapshot
        }.also { DiaryRules.validate(it); validateMapSnapshot(it) }.let { snapshot ->
            snapshot.copy(contributions = snapshot.contributions.map { if (it.status == ContributionStatus.SENDING) it.copy(status = ContributionStatus.UNKNOWN) else it },
                mapEdits = snapshot.mapEdits.map { if (it.status == MapEditStatus.SENDING) it.copy(status = MapEditStatus.UNKNOWN) else it })
        }
    }

    private fun JSONObject.nullString(key: String): String? = if (isNull(key)) null else getString(key)
    private fun JSONObject.nullLong(key: String): Long? = if (isNull(key)) null else getLong(key)
    private fun JSONObject.nullInt(key: String): Int? = if (isNull(key)) null else getInt(key)
    private fun JSONObject.nullDouble(key: String): Double? = if (isNull(key)) null else getDouble(key)
    private fun JSONObject.strictNullLong(key: String): Long? = if (isNull(key)) null else strictLong(key)
    private fun JSONObject.strictString(key: String): String {
        val value = get(key)
        require(value is String) { "Invalid text: $key" }
        return value
    }
    private fun JSONObject.strictBoolean(key: String): Boolean {
        val value = get(key)
        require(value is Boolean) { "Invalid boolean: $key" }
        return value
    }
    private fun JSONObject.strictDouble(key: String): Double {
        val value = get(key)
        require(value is Number) { "Invalid number: $key" }
        return value.toDouble()
    }
    private fun JSONObject.strictNullInt(key: String): Int? {
        val value = strictNullLong(key) ?: return null
        require(value in Int.MIN_VALUE..Int.MAX_VALUE) { "Invalid integer: $key" }
        return value.toInt()
    }
    private fun JSONObject.strictLong(key: String): Long {
        val number = get(key)
        require(number is Byte || number is Short || number is Int || number is Long) { "Geçersiz tamsayı: $key" }
        return (number as Number).toLong()
    }
}
