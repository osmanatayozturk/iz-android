package org.iz.navigation.data

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.iz.navigation.lean.LeanArchive
import org.iz.navigation.lean.LeanBackupCodec
import org.iz.navigation.lean.LeanStore
import org.json.JSONArray
import org.json.JSONObject

/** User-initiated versioned ZIP; journal and motion content are never uploaded automatically. */
class DiaryBackup(context: Context, private val repository: DiaryRepository) {
    private val context = context.applicationContext

    private val stages = DiaryBackupStages(this.context, repository)

    suspend fun prepareExport(includeHealth: Boolean = false): PreparedDiaryBackup =
        stages.prepare(includeHealth, BackupExportOptions())

    internal suspend fun prepareExport(includeHealth: Boolean, options: BackupExportOptions): PreparedDiaryBackup =
        stages.prepare(includeHealth, options)

    suspend fun savePrepared(prepared: PreparedDiaryBackup, uri: Uri) = stages.save(prepared, uri)
    suspend fun discardPrepared(prepared: PreparedDiaryBackup): Boolean = stages.discard(prepared)
    suspend fun cleanupAbandonedExports(): Boolean = stages.cleanupAbandoned()

    /** Compatibility entry point; interactive UI prepares before opening CreateDocument. */
    suspend fun exportTo(uri: Uri, includeHealth: Boolean = false) {
        val prepared = prepareExport(includeHealth)
        try { savePrepared(prepared, uri) }
        finally { withContext(NonCancellable) { discardPrepared(prepared) } }
    }

    suspend fun importFrom(uri: Uri, afterReplace: suspend () -> Unit = {}) = withContext(Dispatchers.IO) {
        // Invalidate/cancel analysis before acquiring raw lease, preserving Raw -> Privacy -> Room order.
        repository.withRawMutationLease {
            val staging = File(context.cacheDir, "diary-import-" + UUID.randomUUID())
            check(staging.mkdirs()) { "Yedek için geçici alan oluşturulamadı." }
            val installed = mutableListOf<File>()
            var committed = false
            try {
                val extracted = linkedMapOf<String, File>()
                val rawSources = linkedMapOf<String, List<File>>()
                val names = linkedSetOf<String>()
                var expandedBytes = 0L
                var stagedBytes = 0L
                var chunks = 0
                val store = repository.leanStore
                val input = context.contentResolver.openInputStream(uri) ?: error("Yedek dosyası açılamadı.")
                ZipInputStream(input.buffered()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        require(!entry.isDirectory) { "Yedekte beklenmeyen klasör var." }
                        val name = entry.name
                        require(name == MANIFEST || DiaryRules.isSafePhotoPath(name) || LeanArchive.isRawName(name)) {
                            "Güvenli olmayan yedek yolu."
                        }
                        require(names.size < MAX_FILES && names.add(name)) { "Yinelenen dosya veya çok fazla dosya." }
                        val isRaw = LeanArchive.isRawName(name)
                        val limit = when {
                            name == MANIFEST -> MAX_MANIFEST_BYTES.toLong()
                            isRaw -> LeanStore.MAX_RAW_BYTES + 4L * LeanStore.MAX_CHUNKS
                            else -> MAX_PHOTO_BYTES
                        }
                        var entryBytes = 0L
                        val bounded = object : InputStream() {
                            private fun count(size: Int): Int {
                                if (size > 0) {
                                    entryBytes += size; expandedBytes += size
                                    require(entryBytes <= limit && expandedBytes <= MAX_TOTAL_BYTES) {
                                        "Yedek açıldığında izin verilen boyutu aşıyor."
                                    }
                                }
                                return size
                            }
                            override fun read(): Int = zip.read().also { if (it >= 0) count(1) }
                            override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
                                count(zip.read(bytes, offset, length))
                        }
                        if (isRaw) {
                            rawSources[name] = LeanArchive.stage(bounded, staging) { bytes ->
                                require(++chunks <= LeanStore.MAX_CHUNKS)
                                val next = stagedBytes + bytes
                                require(next <= LeanStore.MAX_RAW_BYTES)
                                store.checkIncomingBudget(next)
                                stagedBytes = next
                            }
                        } else {
                            // Generated names only, never ZIP path -> filesystem path.
                            val file = File(staging, "entry-" + names.size)
                            file.outputStream().use { bounded.copyTo(it) }
                            extracted[name] = file
                        }
                        zip.closeEntry()
                    }
                }
                val manifest = extracted[MANIFEST] ?: error("Yedek içeriği bulunamadı.")
                val snapshot = BackupJson.decode(JSONObject(manifest.readText(Charsets.UTF_8)))
                DiaryRules.validate(snapshot)
                validateMapSnapshot(snapshot)
                LeanBackupCodec.validateSnapshot(snapshot)
                require(snapshot.journeys.none { it.status == JourneyStatus.TEMPORARY }) { "Yedek geçici yolculuk içeriyor." }
                val photoPaths = snapshot.photos.map { it.relativePath }.toSet()
                LeanArchive.validateEntryNames(names, photoPaths, snapshot.leanSessions)
                snapshot.leanSessions.forEach { session ->
                    LeanArchive.validate(session, rawSources[LeanArchive.rawName(session.input.token.id)].orEmpty())
                }
                // Install under fresh names, preserving every old photo if the transaction fails.
                val remapped = photoPaths.associateWith { original ->
                    val extension = original.substringAfterLast('.', "jpg").lowercase()
                        .takeIf { it.matches(Regex("[a-z0-9]{1,8}")) } ?: "jpg"
                    val relativePath = "photos/" + UUID.randomUUID() + "." + extension
                    val target = repository.photoFile(relativePath)
                    check(target.parentFile?.mkdirs() == true || target.parentFile?.isDirectory == true)
                    installed += target
                    extracted.getValue(original).inputStream().use { source -> target.outputStream().use { source.copyTo(it) } }
                    relativePath
                }
                withContext(NonCancellable) {
                    repository.restore(snapshot.copy(photos = snapshot.photos.map {
                        it.copy(relativePath = remapped.getValue(it.relativePath))
                    }), rawSources)
                    committed = true
                    afterReplace()
                }
            } finally {
                if (!committed) installed.forEach { runCatching { it.delete() } }
                staging.listFiles()?.forEach { it.delete() }
                staging.delete()
            }
        }
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
    private const val VERSION = 12
    private const val FORMAT = "org.iz.navigation.backup"
    // Read compatibility for user-created backups from the former application identity.
    private const val LEGACY_FORMAT = "com.atay.iz.backup"
    private fun json(vararg pairs: Pair<String, Any?>): JSONObject = JSONObject().apply {
        pairs.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
    }
    private fun <T> array(values: List<T>, encode: (T) -> JSONObject): JSONArray = JSONArray().apply { values.forEach { put(encode(it)) } }

    internal fun header(exportedAt: Long): JSONObject = json("format" to FORMAT, "version" to VERSION, "exportedAt" to exportedAt)

    internal fun recordJourneys(value: Journey): JSONObject = json("id" to value.id, "title" to value.title, "transport" to value.transport.name, "status" to value.status.name,
            "startedAt" to value.startedAt, "endedAt" to value.endedAt, "expiresAt" to value.expiresAt, "note" to value.note,
            "interrupted" to value.interrupted, "stepCount" to value.stepCount)

    internal fun recordPoints(value: TrackPoint): JSONObject = json("id" to value.id, "journeyId" to value.journeyId, "latitude" to value.latitude, "longitude" to value.longitude,
            "recordedAt" to value.recordedAt, "accuracy" to value.accuracy, "speed" to value.speed, "altitude" to value.altitude, "breakBefore" to value.breakBefore)

    internal fun recordPlaces(value: Place): JSONObject = json("id" to value.id, "name" to value.name, "latitude" to value.latitude, "longitude" to value.longitude,
            "googlePlaceId" to value.googlePlaceId, "osmType" to value.osmType?.name, "osmId" to value.osmId, "source" to value.source.name, "sortOrder" to value.sortOrder)

    internal fun recordVisits(value: Visit): JSONObject = json("id" to value.id, "placeId" to value.placeId, "journeyId" to value.journeyId, "visitedAt" to value.visitedAt, "note" to value.note, "rating" to value.rating)

    internal fun recordPhotos(value: Photo): JSONObject = json("id" to value.id, "relativePath" to value.relativePath, "journeyId" to value.journeyId, "visitId" to value.visitId,
            "takenAt" to value.takenAt, "latitude" to value.latitude, "longitude" to value.longitude, "caption" to value.caption)

    internal fun recordDrafts(value: ShareDraft): JSONObject = json("id" to value.id, "visitId" to value.visitId, "text" to value.text, "rating" to value.rating,
            "photoIds" to JSONArray(value.photoIds), "markedSharedAt" to value.markedSharedAt)

    internal fun recordContributions(value: ContributionDraft): JSONObject = json("id" to value.id, "placeId" to value.placeId, "latitude" to value.latitude, "longitude" to value.longitude,
            "observedAt" to value.observedAt, "kind" to value.kind.name, "text" to value.text, "status" to value.status.name, "osmType" to value.osmType?.name,
            "osmId" to value.osmId, "remoteNoteId" to value.remoteNoteId, "remoteStatus" to value.remoteStatus,
            "submittedAt" to value.submittedAt, "submittedBy" to value.submittedBy, "error" to value.error)

    internal fun recordHealthSamples(value: JourneyHealthSample): JSONObject = json("journeyId" to value.journeyId, "sourceId" to value.sourceId, "originPackage" to value.originPackage,
                "deviceType" to value.deviceType, "deviceManufacturer" to value.deviceManufacturer, "deviceModel" to value.deviceModel,
                "metric" to value.metric.name, "startAt" to value.startAt, "endAt" to value.endAt, "value" to value.value)

    // Never serialize installation-local map edit attempts or close receipts.
    internal fun recordMapEdits(value: MapEditDraft): JSONObject = json("id" to value.id, "placeId" to value.placeId,
            "latitude" to value.latitude, "longitude" to value.longitude, "preset" to value.preset.name,
            "name" to value.name, "street" to value.street, "houseNumber" to value.houseNumber,
            "observedAt" to value.observedAt, "surveyConfirmed" to value.surveyConfirmed,
            "status" to value.status.name, "stage" to value.stage.name, "submittedAt" to value.submittedAt,
            "submittedBy" to value.submittedBy, "changesetId" to value.changesetId,
            "remoteNodeId" to value.remoteNodeId, "remoteNodeVersion" to value.remoteNodeVersion,
            "changesetClosed" to value.changesetClosed, "error" to value.error,
            "targetType" to value.targetType?.name, "targetId" to value.targetId,
            "reviewedVersion" to value.reviewedVersion, "reviewedFingerprint" to value.reviewedFingerprint,
            "appliedVersion" to value.appliedVersion, "surveyPayload" to value.surveyPayload,
            "editorVersion" to value.editorVersion, "presetId" to value.presetId,
            "presetSnapshot" to value.presetSnapshot, "originalPresetSnapshot" to value.originalPresetSnapshot,
            "phone" to value.phone, "nameAction" to value.nameAction.name, "phoneAction" to value.phoneAction.name,
            "typeChanged" to value.typeChanged, "legacyAddressIntent" to value.legacyAddressIntent)

    internal fun recordOsmPresetFavorite(value: OsmPresetFavorite): JSONObject = json("presetId" to value.presetId)

    internal fun recordWatchHealthSessions(value: WatchHealthSession): JSONObject = json("id" to value.id, "journeyId" to value.journeyId, "watchId" to value.watchId,
                "deviceName" to value.deviceName, "createdAt" to value.createdAt)

    internal fun recordWatchHealthSamples(value: WatchHealthSample): JSONObject = json("sessionId" to value.sessionId, "sequence" to value.sequence, "journeyId" to value.journeyId,
                "metric" to value.metric.name, "startAt" to value.startAt, "endAt" to value.endAt, "value" to value.value)

    internal fun recordDestinationShortcuts(value: DestinationShortcutRef): JSONObject = json("kind" to value.kind.name, "placeId" to value.placeId)

    fun encode(value: DiarySnapshot, includeHealth: Boolean = false): JSONObject = json(
        "format" to FORMAT, "version" to VERSION, "exportedAt" to System.currentTimeMillis(),
        "journeys" to array(value.journeys, ::recordJourneys),
        "points" to array(value.points, ::recordPoints),
        "places" to array(value.places, ::recordPlaces),
        "visits" to array(value.visits, ::recordVisits),
        "photos" to array(value.photos, ::recordPhotos),
        "drafts" to array(value.drafts, ::recordDrafts),
        "contributions" to array(value.contributions, ::recordContributions),
        "healthSamples" to array(if (includeHealth) value.healthSamples else emptyList(), ::recordHealthSamples),
        "mapEdits" to array(value.mapEdits, ::recordMapEdits),
        "osmPresetFavorites" to array(value.osmPresetFavorites.sortedBy { it.presetId }, ::recordOsmPresetFavorite),
        "watchHealthSessions" to array(if (includeHealth) value.watchHealthSessions else emptyList(), ::recordWatchHealthSessions),
        "watchHealthSamples" to array(if (includeHealth) value.watchHealthSamples else emptyList(), ::recordWatchHealthSamples),
        "savedPlans" to array(value.savedPlans, TravelLibraryJson::plan),
        "importedTracks" to array(value.importedTracks, TravelLibraryJson::track),
        "collections" to array(value.collections, TravelLibraryJson::collection),
        "memberships" to array(value.memberships, TravelLibraryJson::membership),
        "destinationShortcuts" to array(value.destinationShortcuts, ::recordDestinationShortcuts),
        "leanSessions" to LeanBackupCodec.encode(value.leanSessions),
    ).also {
        LeanBackupCodec.validateSnapshot(value)
        TravelLibraryRules.validate(value)
        PlaceOrderRules.validate(value.places)
        validateMapSnapshot(value)
        value.mapEdits.forEach(::validateMapEdit)
        validateOsmPresetFavorites(value.osmPresetFavorites)
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
        fun <T> libraryRecords(key: String, limit: Int = TravelLibraryRules.MAX_ITEMS, parse: (JSONObject) -> T): List<T> {
            if (version < 7 || !value.has(key)) return emptyList()
            val array = value.getJSONArray(key)
            require(array.length() <= limit) { "Yedekte çok fazla kütüphane kaydı var." }
            return List(array.length()) { parse(array.getJSONObject(it)) }
        }
        var libraryPoints = 0L
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
                val targetType = it.nullString("targetType")?.let(OsmType::valueOf)
                val targetId = it.strictNullLong("targetId")
                val reviewedVersion = it.strictNullLong("reviewedVersion")
                val reviewedFingerprint = it.nullString("reviewedFingerprint")
                val appliedVersion = it.strictNullLong("appliedVersion")
                val surveyPayload = it.nullString("surveyPayload")
                require(version >= 10 || surveyPayload == null) { "Eski yedek yerinde gözlem alanı içeremez." }
                require(version >= 9 || listOf(targetType, targetId, reviewedVersion, reviewedFingerprint, appliedVersion).all { field -> field == null })
                MapEditDraft(id = it.strictString("id"), placeId = it.nullString("placeId"),
                    latitude = it.strictDouble("latitude"), longitude = it.strictDouble("longitude"),
                    preset = MapPlacePreset.valueOf(it.strictString("preset")), name = it.strictString("name"),
                    street = it.strictString("street"), houseNumber = it.strictString("houseNumber"),
                    observedAt = it.strictLong("observedAt"), surveyConfirmed = it.strictBoolean("surveyConfirmed"),
                    status = MapEditStatus.valueOf(it.strictString("status")), stage = MapEditStage.valueOf(it.strictString("stage")),
                    submittedAt = it.strictNullLong("submittedAt"), submittedBy = it.strictNullLong("submittedBy"),
                    changesetId = it.strictNullLong("changesetId"), remoteNodeId = it.strictNullLong("remoteNodeId"),
                    remoteNodeVersion = it.strictNullLong("remoteNodeVersion"), changesetClosed = it.strictBoolean("changesetClosed"),
                    error = it.nullString("error"), targetType = targetType, targetId = targetId,
                    reviewedVersion = reviewedVersion, reviewedFingerprint = reviewedFingerprint, appliedVersion = appliedVersion,
                    localAttemptId = null, localChangesetCreated = false, surveyPayload = surveyPayload,
                    editorVersion = if (version < 12) 1 else it.strictLong("editorVersion").also { value -> require(value in 1..2) }.toInt(),
                    presetId = if (version < 12) null else it.nullString("presetId"),
                    presetSnapshot = if (version < 12) null else it.nullString("presetSnapshot"),
                    originalPresetSnapshot = if (version < 12) null else it.nullString("originalPresetSnapshot"),
                    phone = if (version < 12) "" else it.strictString("phone"),
                    nameAction = if (version < 12) MapEditFieldAction.UNTOUCHED else MapEditFieldAction.valueOf(it.strictString("nameAction")),
                    phoneAction = if (version < 12) MapEditFieldAction.UNTOUCHED else MapEditFieldAction.valueOf(it.strictString("phoneAction")),
                    typeChanged = version >= 12 && it.strictBoolean("typeChanged"),
                    legacyAddressIntent = version >= 12 && it.strictBoolean("legacyAddressIntent"))
            },
            osmPresetFavorites = if (version < 12) emptyList() else records("osmPresetFavorites") {
                OsmPresetFavorite(it.strictString("presetId"))
            }.sortedBy { it.presetId },
            watchHealthSessions = if (version < 5) emptyList() else records("watchHealthSessions") {
                WatchHealthSession(it.strictString("id"), it.strictString("journeyId"), it.strictString("watchId"),
                    it.strictString("deviceName"), it.strictLong("createdAt"), acceptsUploads = false)
            },
            watchHealthSamples = if (version < 5) emptyList() else records("watchHealthSamples") {
                WatchHealthSample(it.strictString("sessionId"), it.strictLong("sequence"), it.strictString("journeyId"),
                    HealthMetric.valueOf(it.strictString("metric")), it.strictLong("startAt"), it.strictLong("endAt"), it.strictDouble("value"))
            },
            savedPlans = libraryRecords("savedPlans") { record ->
                require(version >= 11 || record.getJSONArray("stops").length() <= 5) { "20 duraklı rota için yedek sürümü 11 gerekir." }
                TravelLibraryJson.readPlan(record)
            },
            importedTracks = libraryRecords("importedTracks") { record ->
                TravelLibraryJson.readTrack(record).also { track ->
                    libraryPoints += track.segments.sumOf { it.points.size.toLong() }
                    require(libraryPoints <= TravelLibraryRules.MAX_TOTAL_POINTS) { "Yedekte çok fazla GPX noktası var." }
                }
            },
            collections = libraryRecords("collections", parse = TravelLibraryJson::readCollection),
            memberships = libraryRecords("memberships", TravelLibraryRules.MAX_MEMBERSHIPS, TravelLibraryJson::readMembership),
            destinationShortcuts = if (version < 8 || !value.has("destinationShortcuts")) emptyList() else
                libraryRecords("destinationShortcuts", 2) { DestinationShortcutRef(DestinationShortcutKind.valueOf(it.strictString("kind")), it.strictString("placeId")) },
            leanSessions = if (version < 8 || !value.has("leanSessions")) emptyList() else LeanBackupCodec.decode(value.getJSONArray("leanSessions")),
        ).let { snapshot ->
            if (version < 6) snapshot.copy(places = PlaceOrderRules.legacy(snapshot.places, snapshot.visits)) else snapshot
        }.also { DiaryRules.validate(it); validateMapSnapshot(it); LeanBackupCodec.validateSnapshot(it) }.let { snapshot ->
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
