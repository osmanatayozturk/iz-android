package org.iz.navigation.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.lang.reflect.Proxy
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.iz.navigation.weather.WeatherCoordinate
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupLibraryPagesTest {
    @Test fun writesThreeGroupsAndKeepsTrackSegmentAndCoordinateOrderAcrossPages() = runBlocking {
        val first = ImportedTrackEntity("z-first", "First", 1)
        val second = ImportedTrackEntity("a-second", "Second", 2)
        val segments = mapOf(
            first.id to listOf(ImportedSegmentEntity("first-0", first.id, 5, "First section", "Track", 0),
                ImportedSegmentEntity("first-1", first.id, 8, "Second section", "Track", 0)),
            second.id to listOf(ImportedSegmentEntity("second-0", second.id, 0, "Third section", "", 0)),
        )
        val longSegment = (0..512).map { WeatherCoordinate(it / 1000.0, 0.0) }
        val points = mapOf(
            "first-0" to (listOf(longSegment.first()) + longSegment),
            "first-1" to listOf(WeatherCoordinate(20.0, 0.0), WeatherCoordinate(20.1, 0.0)),
            "second-0" to listOf(WeatherCoordinate(30.0, 0.0), WeatherCoordinate(30.1, 0.0)),
        )
        val dao = exportDao(listOf(first, second), segments, points)
        val archive = ByteArrayOutputStream()
        val registered = mutableMapOf<String, Long>()
        val groups = ZipOutputStream(archive).use { zip ->
            BackupLibraryPages.write(zip, dao, BackupExportOptions(pageSize = 1)) { name, bytes ->
                registered[name] = bytes
            }
        }
        assertEquals(listOf("importedTracks", "trackSegments", "trackCoordinates"), groups.map { it.type })
        assertEquals(listOf(512, 5), groups.last().parts.map { it.records })
        val pages = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(archive.toByteArray())).use { zip ->
            while (true) {
                val name = zip.nextEntry?.name ?: break
                pages[name] = zip.readBytes()
            }
        }
        assertEquals(registered, pages.mapValues { it.value.size.toLong() })
        val restored = BackupLibraryPages.read(groups.associateBy { it.type }) {
            BackupBulkPages.readPage(pages.getValue(it.path).inputStream(), it)
        }
        assertEquals(listOf(first.id, second.id), restored.map { it.id })
        assertEquals(listOf("First section", "Second section"), restored.first().segments.map { it.name })
        assertEquals(longSegment, restored.first().segments.first().points)
        assertEquals(points.getValue("second-0"), restored.last().segments.single().points)
    }

    @Test fun rejectsOrphanDuplicateAndOutOfOrderGeometryAcrossParts() {
        val a = segment("segment-a", "track", 0)
        val b = segment("segment-b", "track", 1)
        val coordinates = listOf(point("segment-a", 0, 0.0), point("segment-a", 1, 1.0),
            point("segment-b", 0, 2.0), point("segment-b", 1, 3.0))
        for (segments in listOf(
            listOf(a, segment("segment-b", "missing-track", 1)),
            listOf(a, segment("segment-a", "track", 1)),
            listOf(b, a),
        )) {
            assertThrows(IllegalArgumentException::class.java) { read(segments, coordinates) }
        }
        for (points in listOf(
            listOf(coordinates[0], point("missing-segment", 1, 1.0)) + coordinates.drop(2),
            listOf(coordinates[0], point("segment-a", 0, 1.0)) + coordinates.drop(2),
            listOf(coordinates[0], coordinates[2], coordinates[1], coordinates[3]),
            listOf(coordinates[0], point("segment-a", 1, 0.0)) + coordinates.drop(2),
        )) {
            assertThrows(IllegalArgumentException::class.java) { read(listOf(a, b), points) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            read(listOf(a, b), coordinates) { part ->
                if (part.type == "trackSegments" && part.sequence == 1) part.copy(type = "trackCoordinates") else part
            }
        }
    }

    private fun read(
        segments: List<JSONObject>,
        coordinates: List<JSONObject>,
        changePart: (BackupBulkPart) -> BackupBulkPart = { it },
    ) {
        val rows = linkedMapOf(
            "importedTracks" to listOf(JSONObject().put("id", "track").put("name", "Track").put("createdAt", 1)),
            "trackSegments" to segments,
            "trackCoordinates" to coordinates,
        )
        val groups = rows.mapValues { (type, values) ->
            BackupBulkGroup(type, values.size, values.indices.map { index ->
                changePart(BackupBulkPart(type, index, BackupBulkPages.pageName(type, index), 1, 32, "0".repeat(64)))
            })
        }
        BackupLibraryPages.read(groups) { part -> listOf(rows.getValue(part.type)[part.sequence]) }
    }

    private fun segment(id: String, track: String, order: Int) =
        JSONObject().put("id", id).put("trackId", track).put("sortOrder", order)
            .put("name", "").put("trackName", "").put("trackIndex", 0)

    private fun point(segment: String, index: Int, latitude: Double) =
        JSONObject().put("segmentId", segment).put("sortOrder", index)
            .put("latitude", latitude).put("longitude", 0.0)

    private fun exportDao(
        tracks: List<ImportedTrackEntity>,
        segments: Map<String, List<ImportedSegmentEntity>>,
        points: Map<String, List<WeatherCoordinate>>,
    ): BackupExportDao = BackupExportDao::class.java.cast(
        Proxy.newProxyInstance(BackupExportDao::class.java.classLoader, arrayOf(BackupExportDao::class.java)) { _, method, args ->
            when (method.name) {
                "tracks" -> tracks.mapIndexed { i, track -> BackupTracksRow(i.toLong(), track) }
                    .filter { it.backupKey >= args[0] as Long }.take(args[1] as Int)
                "segments" -> segments.getValue(args[0] as String).map { BackupSegmentRow(it.sortOrder.toLong(), it) }
                    .filter { it.backupKey >= args[1] as Long }.take(args[2] as Int)
                "coordinates" -> {
                    val segment = args[0] as String
                    val first = args[1] as Long
                    val limit = args[2] as Int
                    val values = points.getValue(segment)
                    values.indices.asSequence().filter { it.toLong() >= first }.take(limit).map { i ->
                        val value = values[i]
                        BackupCoordinateRow(i.toLong(), ImportedCoordinateEntity(segment, i, value.latitude, value.longitude))
                    }.toList()
                }
                else -> error(method.name)
            }
        },
    )
}
