package org.iz.navigation.data

import java.util.zip.ZipOutputStream
import org.iz.navigation.gpx.ImportedTrack
import org.iz.navigation.gpx.TrackSegment
import org.iz.navigation.weather.WeatherCoordinate
import org.json.JSONObject

/** V14 tracks, segments and coordinates have separate directories and bounded JSON pages. */
internal object BackupLibraryPages {
    private const val TRACKS = "importedTracks"
    private const val SEGMENTS = "trackSegments"
    private const val COORDINATES = "trackCoordinates"
    private val trackFields = listOf("id", "name", "createdAt")
    private val segmentFields = listOf("id", "trackId", "sortOrder", "name", "trackName", "trackIndex")
    private val coordinateFields = listOf("segmentId", "sortOrder", "latitude", "longitude")

    suspend fun write(
        zip: ZipOutputStream,
        dao: BackupExportDao,
        options: BackupExportOptions,
        register: (String, Long) -> Unit,
    ): List<BackupBulkGroup> {
        val budget = BackupBulkBudget()
        // Each traversal runs under the same caller-owned Room snapshot. Only the
        // third reads coordinates, and neither a complete segment nor track is materialized.
        val tracks = BackupBulkPages.write(zip, TRACKS, register, budget) { emit ->
            options.rows(TRACKS, TravelLibraryRules.MAX_ITEMS, dao::tracks) { track ->
                validateTrack(track.id, track.name, track.createdAt)
                emit(JSONObject().put("id", track.id).put("name", track.name).put("createdAt", track.createdAt))
            }
        }
        val segments = BackupBulkPages.write(zip, SEGMENTS, register, budget) { emit ->
            var count = 0
            options.rows(TRACKS, TravelLibraryRules.MAX_ITEMS, dao::tracks) { track ->
                var sortOrder = 0
                val groupNames = mutableMapOf<Int, String>()
                options.segments(dao, track.id) { segment ->
                    if (++count > TravelLibraryRules.MAX_TOTAL_POINTS / 2)
                        throw BackupLimitException("Kütüphanede çok fazla GPX bölümü var.")
                    validateSegment(segment.id, segment.name, segment.trackName, segment.trackIndex, groupNames)
                    emit(JSONObject().put("id", segment.id).put("trackId", track.id).put("sortOrder", sortOrder++)
                        .put("name", segment.name).put("trackName", segment.trackName).put("trackIndex", segment.trackIndex))
                }
            }
        }
        val coordinates = BackupBulkPages.write(zip, COORDINATES, register, budget) { emit ->
            var libraryPoints = 0
            options.rows(TRACKS, TravelLibraryRules.MAX_ITEMS, dao::tracks) { track ->
                var trackPoints = 0
                options.segments(dao, track.id) { segment ->
                    var sortOrder = 0
                    var previous: WeatherCoordinate? = null
                    options.rows(COORDINATES, TravelLibraryRules.MAX_TRACK_POINTS,
                        { first, limit -> dao.coordinates(segment.id, first, limit) }) { row ->
                        require(row.segmentId == segment.id) { "GPX noktasının bölümü eksik." }
                        if (++trackPoints > TravelLibraryRules.MAX_TRACK_POINTS ||
                            ++libraryPoints > TravelLibraryRules.MAX_TOTAL_POINTS)
                            throw BackupLimitException("Kütüphanede çok fazla GPX noktası var.")
                        val point = WeatherCoordinate(row.latitude, row.longitude)
                        if (different(previous, point)) {
                            emit(JSONObject().put("segmentId", segment.id).put("sortOrder", sortOrder++)
                                .put("latitude", point.latitude).put("longitude", point.longitude))
                        }
                        previous = point
                    }
                    requireDrawable(sortOrder)
                }
            }
        }
        return listOf(tracks, segments, coordinates)
    }

    private suspend fun BackupExportOptions.segments(
        dao: BackupExportDao,
        trackId: String,
        consume: suspend (ImportedSegmentEntity) -> Unit,
    ) {
        val count = rows(SEGMENTS, TravelLibraryRules.MAX_SEGMENTS,
            { first, limit -> dao.segments(trackId, first, limit) }) { segment ->
            require(segment.trackId == trackId) { "GPX bölümünün izi eksik." }
            consume(segment)
        }
        require(count > 0) { "GPX bölüm sayısı geçersiz." }
    }

    private suspend fun <T> BackupExportOptions.rows(
        type: String,
        limit: Int,
        load: suspend (Long, Int) -> List<BackupRecord<T>>,
        consume: suspend (T) -> Unit,
    ): Int = forEachBackupPage(pageSize, limit, load = { first, count ->
        val page = load(first, count)
        onPage(type, page.size)
        page.map { BackupPage(it.backupKey, it.value) }
    }, consume = consume)

    fun read(
        groups: Map<String, BackupBulkGroup>,
        readPart: (BackupBulkPart) -> List<JSONObject>,
    ): List<ImportedTrack> {
        val trackGroup = groups.getValue(TRACKS)
        val segmentGroup = groups.getValue(SEGMENTS)
        val coordinateGroup = groups.getValue(COORDINATES)
        require(coordinateGroup.records in 0..TravelLibraryRules.MAX_TOTAL_POINTS)
        require(segmentGroup.records in 0..coordinateGroup.records / 2 && trackGroup.records in 0..segmentGroup.records)

        val tracks = linkedMapOf<String, TrackRows>()
        trackGroup.rows(TRACKS, TravelLibraryRules.MAX_ITEMS, readPart) { row ->
            row.requireFields(trackFields)
            val id = row.get("id") as String
            val name = row.get("name") as String
            val created = row.integer("createdAt")
            validateTrack(id, name, created)
            require(id !in tracks) { "Yinelenen GPX izi." }
            tracks[id] = TrackRows(id, name, created, tracks.size)
        }

        val segments = mutableMapOf<String, SegmentRows>()
        var previousTrack = -1
        segmentGroup.rows(SEGMENTS, coordinateGroup.records / 2, readPart) { row ->
            row.requireFields(segmentFields)
            val track = requireNotNull(tracks[row.get("trackId") as String]) { "GPX bölümünün izi eksik." }
            require(track.index >= previousTrack && row.integer("sortOrder") == track.segments.size.toLong() &&
                track.segments.size < TravelLibraryRules.MAX_SEGMENTS) { "GPX bölüm sırası veya sayısı geçersiz." }
            previousTrack = track.index
            val id = row.get("id") as String
            require(id !in segments) { "Yinelenen GPX bölümü." }
            val name = row.get("name") as String
            val trackName = row.get("trackName") as String
            val trackIndex = row.integer("trackIndex")
            require(trackIndex in 0..99) { "Geçersiz GPX iz grubu." }
            validateSegment(id, name, trackName, trackIndex.toInt(), track.groupNames)
            val segment = SegmentRows(track, segments.size, name, trackName, trackIndex.toInt())
            segments[id] = segment
            track.segments += segment
        }

        var previousSegment = -1
        coordinateGroup.rows(COORDINATES, TravelLibraryRules.MAX_TOTAL_POINTS, readPart) { row ->
            row.requireFields(coordinateFields)
            val segment = requireNotNull(segments[row.get("segmentId") as String]) { "GPX noktasının bölümü eksik." }
            require(segment.index >= previousSegment && row.integer("sortOrder") == segment.rawPoints.toLong()) {
                "GPX nokta sırası geçersiz."
            }
            previousSegment = segment.index
            segment.rawPoints++
            require(++segment.track.rawPoints <= TravelLibraryRules.MAX_TRACK_POINTS) { "GPX nokta sınırı aşıldı." }
            val point = WeatherCoordinate(row.number("latitude"), row.number("longitude"))
            if (different(segment.points.lastOrNull(), point)) segment.points += point
        }
        return tracks.values.map { track ->
            require(track.segments.isNotEmpty()) { "GPX bölüm sayısı geçersiz." }
            ImportedTrack(track.id, track.name, track.segments.map { segment ->
                requireDrawable(segment.points.size)
                TrackSegment(segment.name, segment.points, segment.trackName, segment.trackIndex)
            }, track.createdAt)
        }
    }

    private class TrackRows(val id: String, val name: String, val createdAt: Long, val index: Int) {
        val segments = mutableListOf<SegmentRows>()
        val groupNames = mutableMapOf<Int, String>()
        var rawPoints = 0
    }

    private class SegmentRows(
        val track: TrackRows,
        val index: Int,
        val name: String,
        val trackName: String,
        val trackIndex: Int,
    ) {
        val points = mutableListOf<WeatherCoordinate>()
        var rawPoints = 0
    }

    private inline fun BackupBulkGroup.rows(
        expectedType: String,
        limit: Int,
        readPart: (BackupBulkPart) -> List<JSONObject>,
        consume: (JSONObject) -> Unit,
    ) {
        require(type == expectedType && records in 0..limit && parts.size < BACKUP_MAX_FILES)
        var consumed = 0
        for ((index, part) in parts.withIndex()) {
            require(part.type == expectedType && part.sequence == index &&
                part.path == BackupBulkPages.pageName(expectedType, index) &&
                part.records in 1..minOf(BackupBulkPages.MAX_PAGE_RECORDS, records - consumed) &&
                part.bytes in 2..BackupBulkPages.MAX_PAGE_BYTES.toLong()) { "GPX yedek sayfası geçersiz." }
            val page = readPart(part)
            require(page.size == part.records) { "GPX yedek sayfası kayıt sayısı tutarsız." }
            page.forEach(consume)
            consumed += page.size
        }
        require(consumed == records) { "GPX yedek dizini kayıt sayısı tutarsız." }
    }

    private fun validateTrack(id: String, name: String, createdAt: Long) {
        TravelLibraryRules.validId(id)
        TravelLibraryRules.validName(name)
        require(createdAt >= 0) { "GPX kayıt tarihi geçersiz." }
    }

    private fun validateSegment(id: String, name: String, trackName: String, index: Int, groups: MutableMap<Int, String>) {
        TravelLibraryRules.validId(id)
        require(name.length <= 300 && trackName.length <= 300 && index in 0..99) { "GPX bölüm bilgileri geçersiz." }
        val previous = groups.put(index, trackName)
        require(previous == null || previous == trackName) { "GPX iz grupları tutarsız." }
    }

    private fun requireDrawable(size: Int) {
        require(size >= 2) { "Her GPX bölümünde en az iki farklı konum gerekli." }
    }

    private fun different(previous: WeatherCoordinate?, point: WeatherCoordinate) =
        previous == null || previous.latitude != point.latitude || previous.longitude != point.longitude

    private fun JSONObject.requireFields(fields: List<String>) {
        require(length() == fields.size && fields.all(::has)) { "GPX yedek kaydı geçersiz." }
    }

    private fun JSONObject.integer(key: String): Long = when (val value = get(key)) {
        is Int -> value.toLong()
        is Long -> value
        else -> throw IllegalArgumentException("Geçersiz GPX tamsayısı: $key.")
    }

    private fun JSONObject.number(key: String): Double {
        val value = get(key)
        require(value is Number) { "Geçersiz GPX sayısı: $key." }
        return value.toDouble()
    }
}
