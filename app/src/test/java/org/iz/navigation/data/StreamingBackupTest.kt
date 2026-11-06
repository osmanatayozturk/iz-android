package org.iz.navigation.data

import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.io.StringWriter
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class StreamingBackupTest {
    @Test fun pagesPreserveEqualTimestampRowsAndStopAtMaximumKey() = runBlocking {
        val rows = listOf(Long.MIN_VALUE, -20L, -1L, 0L, 17L, 23L, Long.MAX_VALUE)
            .mapIndexed { index, key -> BackupPage(key, TrackPointStamp("point-$index", 2_000)) }
        val expectedFetches = listOf(Long.MIN_VALUE to 3, 0L to 3, 24L to 3)
        val fetches = mutableListOf<Pair<Long, Int>>()
        val consumed = mutableListOf<TrackPointStamp>()

        val written = forEachBackupPage(
            pageSize = 3,
            load = { firstInclusive, limit ->
                val request = firstInclusive to limit
                assertEquals("Fetch at index ${fetches.size}", expectedFetches.getOrNull(fetches.size), request)
                fetches.add(request)
                rows.filter { it.key >= firstInclusive }.take(limit)
            },
            consume = { consumed.add(it) },
        )

        assertEquals(expectedFetches, fetches)
        assertEquals(rows.size, written)
        assertEquals(rows.map { it.value }, consumed)
    }

    @Test fun oversizedFetchCannotWriteRows() = runBlocking {
        val consumed = mutableListOf<Int>()
        val failure = runCatching {
            forEachBackupPage(
                pageSize = 2,
                load = { _, _ -> listOf(BackupPage(1L, 1), BackupPage(2L, 2), BackupPage(3L, 3)) },
                consume = { consumed.add(it) },
            )
        }.exceptionOrNull()

        assertNotNull("An oversized response must fail before writing a page", failure)
        assertEquals(emptyList<Int>(), consumed)
    }

    @Test fun manifestLimitCountsEncodedUtf8BytesAndRejectsTheNextByte() {
        val destination = ByteArrayOutputStream()
        val limitMessage = "Manifest byte limit exceeded"
        val limited = BackupByteLimitOutputStream(destination, 7L, limitMessage)
        val writer = OutputStreamWriter(limited, Charsets.UTF_8)
        val text = "İz🧭"

        writer.write(text)
        writer.flush()

        assertEquals(7L, limited.count)
        assertArrayEquals(text.toByteArray(Charsets.UTF_8), destination.toByteArray())

        val failure = runCatching {
            writer.write("!")
            writer.flush()
        }.exceptionOrNull()

        assertEquals(limitMessage, failure?.message)
        assertEquals(7L, limited.count)
        assertArrayEquals(text.toByteArray(Charsets.UTF_8), destination.toByteArray())
    }

    @Test fun streamedJsonPreservesLegacyBackupContent() {
        val journey = Journey(
            id = "journey", title = "İz 🧭 \"Çığ\"",
            note = "Çeşme\\Urla\b\t\n\u000c\r\u0001", startedAt = 1_000, endedAt = 5_000,
            interrupted = true,
        )
        val point = TrackPoint(
            id = "point", journeyId = journey.id, latitude = 38.25, longitude = 26.75,
            recordedAt = 2_000, accuracy = 4f, breakBefore = true,
        )
        val place = Place(id = "place", name = "Çeşme")
        val visit = Visit(id = "visit", placeId = place.id, journeyId = journey.id, visitedAt = 3_000, rating = 4)
        val photo = Photo(id = "photo", relativePath = "photos/fixture.jpg", visitId = visit.id)
        val draft = ShareDraft(id = "draft", visitId = visit.id, text = "Gün batımı", photoIds = listOf(photo.id))
        val snapshot = DiarySnapshot(
            journeys = listOf(journey), points = listOf(point), places = listOf(place),
            visits = listOf(visit), photos = listOf(photo), drafts = listOf(draft),
        )
        val legacy = BackupJson.encode(snapshot, includeHealth = true)
        val streamed = StringWriter()
        BackupJsonStream.value(streamed, legacy)
        val decoded = JSONObject(streamed.toString())

        assertEquals("Streaming must preserve the complete legacy JSON structure and values", jsonStructure(legacy), jsonStructure(decoded))
        assertEquals(snapshot, BackupJson.decode(decoded))
    }

    private fun jsonStructure(value: Any?): Any? = when (value) {
        null, JSONObject.NULL -> null
        is JSONObject -> value.keys().asSequence().associateWith { key -> jsonStructure(value.get(key)) }
        is JSONArray -> List(value.length()) { index -> jsonStructure(value.get(index)) }
        is Number -> value.toString().toBigDecimal().stripTrailingZeros()
        else -> value
    }

    private data class TrackPointStamp(val id: String, val timestamp: Long)
}
