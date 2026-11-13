package org.iz.navigation.data

import java.io.File
import java.io.OutputStreamWriter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.iz.navigation.lean.LeanArchive
import org.iz.navigation.lean.LeanBackupCodec
import org.iz.navigation.lean.LeanBackupSession
import org.iz.navigation.lean.LeanStore
import org.iz.navigation.weather.WeatherCoordinate
import org.json.JSONObject

/** Manifest pages share one Room snapshot; attachments are revalidated under the longer raw lease. */
internal class PagedBackupExporter(
    private val repository: DiaryRepository,
    private val dao: BackupExportDao,
    private val options: BackupExportOptions,
) {
    private data class PhotoSeal(val file: File, val bytes: Long, val modified: Long)
    private val photos = linkedMapOf<String, PhotoSeal>()
    private val entries = linkedMapOf<String, Long>()
    private val rawSeals = linkedMapOf<String, String>()
    private val journeys = object : LinkedHashMap<String, Journey?>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Journey?>?) = size > 32
    }
    private var expanded = 0L
    private var leanChunks = 0L
    private var leanBytes = 0L
    private var libraryPoints = 0L

    private suspend fun journey(id: String?): Journey? {
        if (id == null) return null
        if (!journeys.containsKey(id)) journeys[id] = dao.journey(id)?.model()?.takeIf { it.status == JourneyStatus.CONFIRMED }
        return journeys[id]
    }
    private fun validate(snapshot: DiarySnapshot) { DiaryRules.validate(snapshot); validateMapSnapshot(snapshot) }
    private suspend fun visitSnapshot(original: Visit): DiarySnapshot {
        val trip = journey(original.journeyId)
        val visit = original.copy(journeyId = trip?.id)
        return DiarySnapshot(journeys = listOfNotNull(trip), visits = listOf(visit),
            places = listOf(requireNotNull(dao.place(visit.placeId)) { "Ziyaretin yeri eksik." }))
    }
    private fun entry(name: String, bytes: Long) {
        if (entries.size >= BACKUP_MAX_FILES) throw BackupLimitException("Yedekte çok fazla dosya var.")
        require(bytes >= 0 && !entries.containsKey(name)) { "Yinelenen yedek girdisi." }
        if (bytes > BACKUP_TOTAL_BYTES - expanded) throw BackupLimitException("Yedek 512 MB sınırını aşıyor.")
        entries[name] = bytes; expanded += bytes
    }
    private suspend fun <T> rows(name: String, limit: Int = 500_000,
        load: suspend (Long, Int) -> List<BackupRecord<T>>, consume: suspend (T) -> Unit) =
        forEachBackupPage(options.pageSize, limit, load = { first, count ->
            val page = load(first, count)
            options.onPage(name, page.size)
            page.map { BackupPage(it.backupKey, it.value) }
        }, consume = consume)

    suspend fun writeManifest(zip: ZipOutputStream, includeHealth: Boolean) {
        val coroutine = currentCoroutineContext()
        val check = { coroutine.ensureActive() }
        zip.putNextEntry(ZipEntry("manifest.json"))
        val budget = BackupByteLimitOutputStream(zip, options.manifestLimit, "Günlük verisi 32 MB sınırını aşıyor.")
        val out = OutputStreamWriter(budget, Charsets.UTF_8).buffered(8192)
        val header = BackupJson.header(System.currentTimeMillis())
        out.write('{'.code)
        var firstHeader = true
        header.keys().forEach { key ->
            if (!firstHeader) out.write(','.code); firstHeader = false
            BackupJsonStream.string(out, key); out.write(':'.code); BackupJsonStream.value(out, header.get(key))
        }
        suspend fun <T> array(name: String, limit: Int = 500_000,
            load: suspend (Long, Int) -> List<BackupRecord<T>>, encode: suspend (T) -> JSONObject) {
            out.write(','.code); BackupJsonStream.string(out, name); out.write(":[")
            var first = true
            rows(name, limit, load) { row ->
                val value = encode(row)
                if (!first) out.write(','.code); first = false
                BackupJsonStream.value(out, value)
                out.flush() // Enforce the encoded byte cap before fetching the next record/page.
            }
            out.write(']'.code)
        }
        array("journeys", load = dao::journeys) {
            validate(DiarySnapshot(journeys = listOf(it))); BackupJson.recordJourneys(it)
        }
        array("points", load = dao::points) {
            // The query includes only existing confirmed parents. Primary keys enforce global uniqueness.
            TravelLibraryRules.validId(it.id)
            require(DiaryRules.isUsablePoint(it)) { "Geçersiz rota noktası." }
            BackupJson.recordPoints(it)
        }
        array("places", load = dao::places) { validate(DiarySnapshot(places = listOf(it))); BackupJson.recordPlaces(it) }
        array("visits", load = dao::visits) {
            val page = visitSnapshot(it); validate(page); BackupJson.recordVisits(page.visits.single())
        }
        array("photos", load = dao::photos) { original ->
            val trip = journey(original.journeyId)
            val photo = original.copy(journeyId = trip?.id)
            val refs = photo.visitId?.let { visitSnapshot(requireNotNull(dao.visit(it))) } ?: DiarySnapshot()
            validate(refs.copy(journeys = (refs.journeys + listOfNotNull(trip)).distinctBy { it.id }, photos = listOf(photo)))
            if (!photos.containsKey(photo.relativePath)) {
                val file = repository.photoFile(photo.relativePath)
                require(file.isFile) { "Fotoğraf eksik." }
                val bytes = file.length()
                if (bytes > BACKUP_PHOTO_BYTES) throw BackupLimitException("Fotoğraf 50 MB sınırından büyük.")
                val modified = file.lastModified()
                entry(photo.relativePath, bytes)
                photos[photo.relativePath] = PhotoSeal(file, bytes, modified)
            }
            BackupJson.recordPhotos(photo)
        }
        array("drafts", load = dao::drafts) { draft ->
            val retained = ArrayList<String>()
            for (ids in draft.photoIds.chunked(options.pageSize)) {
                check()
                val found = dao.draftPhotos(ids).associateBy { it.id }
                ids.forEach { id -> found[id]?.let { photo ->
                    require(photo.visitId == draft.visitId) { "Geçersiz paylaşım taslağı." }; retained += id
                } }
            }
            val refs = visitSnapshot(requireNotNull(dao.visit(draft.visitId)))
            // Photo linkage was checked in bounded projections above; validate all remaining shared rules.
            validate(refs.copy(drafts = listOf(draft.copy(photoIds = emptyList()))))
            BackupJson.recordDrafts(draft.copy(photoIds = retained))
        }
        array("contributions", load = dao::contributions) {
            validate(DiarySnapshot(contributions = listOf(it))); BackupJson.recordContributions(it)
        }
        array("healthSamples", load = { first, count -> if (includeHealth) dao.healthSamples(first, count) else emptyList() }) {
            HealthRules.validateSnapshot(DiarySnapshot(journeys = listOfNotNull(journey(it.journeyId)), healthSamples = listOf(it)))
            BackupJson.recordHealthSamples(it)
        }
        array("mapEdits", load = dao::mapEdits) { validateMapEdit(it); BackupJson.recordMapEdits(it) }
        array("osmPresetFavorites", MAX_OSM_PRESET_FAVORITES, dao::osmPresetFavorites) {
            validateOsmPresetFavoriteId(it.presetId); BackupJson.recordOsmPresetFavorite(it)
        }
        array("watchHealthSessions", load = { first, count -> if (includeHealth) dao.watchHealthSessions(first, count) else emptyList() }) {
            WatchHealthRules.validateSnapshot(listOfNotNull(journey(it.journeyId)), listOf(it), emptyList())
            BackupJson.recordWatchHealthSessions(it)
        }
        array("watchHealthSamples", load = { first, count -> if (includeHealth) dao.watchHealthSamples(first, count) else emptyList() }) {
            WatchHealthRules.validateSnapshot(listOfNotNull(journey(it.journeyId)), listOfNotNull(dao.watchSession(it.sessionId)), listOf(it))
            BackupJson.recordWatchHealthSamples(it)
        }
        array("savedPlans", TravelLibraryRules.MAX_ITEMS, dao::plans) { TravelLibraryJson.plan(it.model()) }
        // GPX model/JSONArray would hold every point. Write its nested arrays a page at a time instead.
        out.write(",\"importedTracks\":[")
        var firstTrack = true
        rows("importedTracks", TravelLibraryRules.MAX_ITEMS, dao::tracks) { track ->
            TravelLibraryRules.validId(track.id); TravelLibraryRules.validName(track.name); require(track.createdAt >= 0)
            if (!firstTrack) out.write(','.code); firstTrack = false
            out.write("{\"id\":"); BackupJsonStream.string(out, track.id)
            out.write(",\"name\":"); BackupJsonStream.string(out, track.name)
            out.write(",\"createdAt\":${track.createdAt},\"segments\":[")
            var firstSegment = true; var pointCount = 0L
            val groupNames = mutableMapOf<Int, String>()
            val segments = rows("trackSegments", TravelLibraryRules.MAX_SEGMENTS, { first, count -> dao.segments(track.id, first, count) }) { segment ->
                require(segment.name.length <= 300 && segment.trackName.length <= 300 && segment.trackIndex in 0..99)
                val previousName = groupNames.put(segment.trackIndex, segment.trackName)
                require(previousName == null || previousName == segment.trackName) { "GPX iz grupları tutarsız." }
                if (!firstSegment) out.write(','.code); firstSegment = false
                out.write("{\"name\":"); BackupJsonStream.string(out, segment.name)
                out.write(",\"trackName\":"); BackupJsonStream.string(out, segment.trackName)
                out.write(",\"trackIndex\":${segment.trackIndex},\"points\":[")
                var previous: WeatherCoordinate? = null; var kept = 0
                rows("trackPoints", TravelLibraryRules.MAX_TRACK_POINTS, { first, count -> dao.coordinates(segment.id, first, count) }) { point ->
                    if (++pointCount > TravelLibraryRules.MAX_TRACK_POINTS || ++libraryPoints > TravelLibraryRules.MAX_TOTAL_POINTS)
                        throw BackupLimitException("Kütüphanede çok fazla GPX noktası var.")
                    val coordinate = WeatherCoordinate(point.latitude, point.longitude)
                    if (previous != coordinate) {
                        if (kept++ != 0) out.write(','.code)
                        BackupJsonStream.value(out, JSONObject().put("latitude", point.latitude).put("longitude", point.longitude))
                    }
                    previous = coordinate
                }
                require(kept >= 2) { "Her GPX bölümünde en az iki farklı konum gerekli." }
                out.write("]}")
            }
            require(segments >= 1) { "GPX bölüm sayısı geçersiz." }
            out.write("]}"); out.flush()
        }
        out.write(']'.code)
        array("collections", TravelLibraryRules.MAX_ITEMS, dao::collections) { TravelLibraryJson.collection(it.model()) }
        array("memberships", TravelLibraryRules.MAX_MEMBERSHIPS, dao::memberships) {
            val member = it.model()
            TravelLibraryRules.validate(DiarySnapshot(journeys = listOfNotNull(journey(it.journeyId)),
                collections = listOf(requireNotNull(dao.collection(it.collectionId)).model()), memberships = listOf(member)))
            TravelLibraryJson.membership(member)
        }
        array("destinationShortcuts", 2, dao::shortcuts) {
            val shortcut = DestinationShortcutRef(DestinationShortcutKind.valueOf(it.kind), it.placeId)
            LeanBackupCodec.validateSnapshot(DiarySnapshot(places = listOf(requireNotNull(dao.place(it.placeId))), destinationShortcuts = listOf(shortcut)))
            BackupJson.recordDestinationShortcuts(shortcut)
        }
        array("leanSessions", LeanStore.MAX_CHUNKS, dao::leanSessions) { row ->
            val session = leanSession(row)
            leanChunks += session.chunks.size
            leanBytes += session.chunks.sumOf { it.byteCount.toLong() }
            if (leanChunks > LeanStore.MAX_CHUNKS || leanBytes > LeanStore.MAX_RAW_BYTES)
                throw BackupLimitException("Sensör yedeği sınırı aşıldı.")
            val encoded = LeanBackupCodec.encode(listOf(session)).getJSONObject(0)
            if (session.chunks.isNotEmpty()) {
                entry(LeanArchive.rawName(row.id), LeanArchive.framedBytes(listOf(session)))
                rawSeals[row.id] = metadataDigest(encoded)
            }
            encoded
        }
        out.write('}'.code); out.flush(); zip.closeEntry()
        entry("manifest.json", budget.count)
    }

    /** Room transaction is over. Raw lease still protects source mutations; metadata drift fails closed. */
    suspend fun writeAttachments(zip: ZipOutputStream): Map<String, Long> {
        val coroutine = currentCoroutineContext()
        val check = { coroutine.ensureActive() }
        for ((path, seal) in photos) {
            check()
            require(seal.file.isFile && seal.file.length() == seal.bytes && seal.file.lastModified() == seal.modified) { "Fotoğraf hazırlık sırasında değişti." }
            val before = seal.file.inputStream().use { backupDigest(it, seal.bytes, check) }
            require(before.bytes == seal.bytes && seal.file.lastModified() == seal.modified) { "Fotoğraf hazırlık sırasında değişti." }
            zip.putNextEntry(ZipEntry(path))
            val digest = seal.file.inputStream().use { backupCopy(it, zip, seal.bytes, check) }
            require(digest.bytes == seal.bytes && digest.sha256 == before.sha256 && seal.file.length() == seal.bytes && seal.file.lastModified() == seal.modified) {
                "Fotoğraf hazırlık sırasında değişti."
            }
            zip.closeEntry()
        }
        for ((id, fingerprint) in rawSeals) {
            check()
            val row = requireNotNull(dao.leanSessionById(id)) { "Sensör yedeği kaydı kayboldu." }
            val session = leanSession(row)
            require(metadataDigest(LeanBackupCodec.encode(listOf(session)).getJSONObject(0)) == fingerprint) { "Sensör yedeği hazırlık sırasında değişti." }
            val name = LeanArchive.rawName(id)
            val expected = entries.getValue(name)
            zip.putNextEntry(ZipEntry(name))
            val bounded = BackupByteLimitOutputStream(zip, expected, "Sensör yedeği boyutu eşleşmedi.")
            repository.leanStore.writeRaw(session, bounded)
            require(bounded.count == expected) { "Sensör yedeği boyutu eşleşmedi." }
            zip.closeEntry()
        }
        return entries.toMap()
    }

    private fun metadataDigest(value: JSONObject): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val sink = java.security.DigestOutputStream(object : java.io.OutputStream() {
            override fun write(value: Int) {}
            override fun write(bytes: ByteArray, offset: Int, length: Int) {}
        }, digest)
        val writer = OutputStreamWriter(sink, Charsets.UTF_8).buffered(8192)
        BackupJsonStream.value(writer, value); writer.flush()
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun leanSession(row: LeanSessionEntity): LeanBackupSession {
        val parts = dao.leanChunks(row.id, LeanStore.MAX_CHUNKS + 1)
        if (parts.size > LeanStore.MAX_CHUNKS) throw BackupLimitException("Sensör yedeği parça sınırı aşıldı.")
        return repository.leanStore.backupSession(row, parts).also {
            LeanBackupCodec.validateSnapshot(DiarySnapshot(journeys = listOfNotNull(journey(row.journeyId)), leanSessions = listOf(it)))
        }
    }
}
