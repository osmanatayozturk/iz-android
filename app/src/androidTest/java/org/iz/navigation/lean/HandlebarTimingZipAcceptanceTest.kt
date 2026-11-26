package org.iz.navigation.lean

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.iz.navigation.data.DiaryBackup
import org.iz.navigation.data.DiaryDatabase
import org.iz.navigation.data.DiaryRepository
import org.iz.navigation.data.DiaryRules
import org.iz.navigation.data.Journey
import org.iz.navigation.data.RecordedCurveDetail
import org.iz.navigation.data.RecordedCurveDirection
import org.iz.navigation.data.RecordedCurveEndpoint
import org.iz.navigation.data.TrackPoint
import org.iz.navigation.data.Transport
import org.iz.navigation.integration.CurvePassLean
import org.iz.navigation.integration.CurvePassMatcher
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HandlebarTimingZipAcceptanceTest {
    @Test
    fun restoresMeasuredEstimatedMixedAndLegacyCurvesWithoutComparingEstimatedLean() {
        runBlocking(Dispatchers.IO) {
            withZipFixture { context, original, manifest ->
                for ((id, flags) in variants) {
                    val rawCurves = manifest.result(id).getJSONArray("curves")
                    assertEquals(flags.size, rawCurves.length())
                    for ((index, estimated) in flags.withIndex()) {
                        val curve = rawCurves.getJSONObject(index)
                        if (id == LEGACY) {
                            assertFalse("Legacy fixture must have no timing flag on disk", curve.has(TIMING))
                        } else {
                            assertEquals("Export must write a Boolean for each v2 curve", estimated, curve.get(TIMING))
                        }
                    }
                }
                withTimingRoom(context) { database, store ->
                    DiaryBackup(context, DiaryRepository(context, database)).importFrom(uri(context, original))
                    val earlier = trace(database.diaryDao().journeyPoints(LEGACY))
                    val oldReport = store.observeReport(LEGACY).first()
                    assertEquals(setOf(HandlebarLeanEstimator.LEGACY_VERSION),
                        requireNotNull(oldReport.handlebar).algorithmVersions)
                    for ((id, flags) in variants) {
                        val report = store.observeReport(id).first()
                        val handlebar = requireNotNull(report.handlebar)
                        assertEquals(flags, handlebar.curves.map { it.timingEstimated })
                        assertEquals(flags.any { it }, REASON in handlebar.partialReasons)
                        if (id == LEGACY) continue
                        assertEquals(setOf(HandlebarLeanEstimator.VERSION), handlebar.algorithmVersions)
                        val recorded = database.diaryDao().journeyPoints(id).chunked(SHAPE.size)
                        assertEquals(flags.size, recorded.size)
                        for ((index, points) in recorded.withIndex()) {
                            val comparison = requireNotNull(CurvePassMatcher.compare(trace(points), earlier, report, oldReport))
                            assertEquals(CurvePassLean(LeanMount.HANDLEBAR, null, 24.0), comparison.previous.lean)
                            if (flags[index]) assertNull(comparison.selected.lean)
                            else assertEquals(CurvePassLean(LeanMount.HANDLEBAR, null, 24.0), comparison.selected.lean)
                            assertTrue(comparison.samples.isNotEmpty())
                        }
                    }
                }
            }
        }
    }

    @Test
    fun rejectsMalformedOrContradictoryV2TimingWithoutReplacingExistingDiary() {
        runBlocking(Dispatchers.IO) {
            withZipFixture { context, _, originalManifest ->
                val negatives: List<Triple<String, String, (JSONObject) -> Unit>> = listOf(
                    Triple("missing even on explicitly measured curve", "Missing handlebar curve timing confidence") { manifest ->
                        manifest.result(MEASURED).curve().remove(TIMING)
                    },
                    Triple("missing on estimated curve in a mixed report", "Missing handlebar curve timing confidence") { manifest ->
                        manifest.result(MIXED).curve(1).remove(TIMING)
                    },
                    Triple("string in place of Boolean", "Invalid telemetry flag") { manifest ->
                        manifest.result(ESTIMATED).curve().put(TIMING, "true")
                    },
                    Triple("number in place of Boolean", "Invalid telemetry flag") { manifest ->
                        manifest.result(MEASURED).curve().put(TIMING, 0)
                    },
                    Triple("JSON null in place of Boolean", "Invalid telemetry flag") { manifest ->
                        manifest.result(MEASURED).curve().put(TIMING, JSONObject.NULL)
                    },
                    Triple("estimated curve without report reason", "Report and curve timing confidence disagree") { manifest ->
                        manifest.result(ESTIMATED).put("reasons", JSONArray())
                    },
                    Triple("report reason without any estimated curve", "Report and curve timing confidence disagree") { manifest ->
                        manifest.result(MEASURED).put("reasons", JSONArray(listOf(REASON)))
                    },
                )
                withTimingRoom(context) { database, store ->
                    val baseline = Journey(id = "preserved-diary", title = "QA baseline",
                        transport = Transport.WALK, startedAt = UTC, endedAt = UTC + 60_000)
                    database.diaryDao().save(baseline)
                    val backup = DiaryBackup(context, DiaryRepository(context, database))
                    for ((label, expected, edit) in negatives) {
                        val manifest = JSONObject(originalManifest.toString()).also(edit)
                        val archive = tempArchive(context)
                        try {
                            ZipOutputStream(archive.outputStream()).use { zip ->
                                zip.putNextEntry(ZipEntry(MANIFEST))
                                zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
                                zip.closeEntry()
                            }
                            var applied = false
                            try {
                                backup.importFrom(uri(context, archive)) { applied = true }
                                fail("Malformed timing accepted: $label")
                            } catch (error: IllegalArgumentException) {
                                assertEquals(label, expected, error.message)
                            } catch (error: IllegalStateException) {
                                assertEquals(label, expected, error.message)
                            }
                            assertFalse(label, applied)
                            assertEquals(label, listOf(baseline), database.diaryDao().allJourneys())
                            assertNull(label, store.observeReport(MEASURED).first().handlebar)
                        } finally {
                            archive.delete()
                        }
                    }
                }
            }
        }
    }

    private suspend fun withZipFixture(block: suspend (Context, File, JSONObject) -> Unit) {
        val context = requireTimingQa(emulator = true)
        val archive = tempArchive(context)
        try {
            withTimingRoom(context) { database, store ->
                for ((index, run) in variants.entries.withIndex()) {
                    val start = UTC + index * 60_000L
                    val trip = Journey(id = run.key, transport = Transport.MOTORCYCLE, startedAt = start)
                    database.diaryDao().save(trip)
                    val token = requireNotNull(store.begin(trip.id, handlebarTimingCalibration, "qa-boot", start, ELAPSED))
                    val curves = run.value.mapIndexed { segment, estimated ->
                        val offset = segment * 12_000L
                        val section = SHAPE.mapIndexed { point, (lat, lon) ->
                            TrackPoint("${trip.id}-$segment-$point", trip.id, lat, lon,
                                start + offset + point * 1_000L, 2f, 10f)
                        }
                        for (point in section) database.diaryDao().save(point)
                        val middle = section[2]
                        LeanCurveResult(ELAPSED + offset * 1_000_000, ELAPSED + (offset + 5000) * 1_000_000,
                            null, 24.0, middle.latitude, middle.longitude,
                            section.first().recordedAt, section.last().recordedAt, estimated)
                    }
                    store.seal(token, ELAPSED + 20_000L * 1_000_000)
                    database.diaryDao().save(trip.copy(endedAt = start + 20_000L))
                    val pending = store.pendingInputs().single { it.token.id == token.id }
                    val algorithm = if (trip.id == LEGACY) HandlebarLeanEstimator.LEGACY_VERSION else HandlebarLeanEstimator.VERSION
                    val reasons = if (run.value.any { it }) setOf(REASON) else emptySet()
                    assertTrue(store.commitResult(token.id, pending.inputHash,
                        LeanEstimate(algorithm, handlebarTimingCalibration.epoch, null, 24.0,
                            5_000L * curves.size, 20_000L, reasons, curves)) {})
                }
                DiaryBackup(context, DiaryRepository(context, database)).exportTo(uri(context, archive))
            }
            val manifest = ZipFile(archive).use { zip ->
                assertEquals("The synthetic archive contains only its manifest", 1, zip.size())
                zip.getInputStream(requireNotNull(zip.getEntry(MANIFEST))).bufferedReader(Charsets.UTF_8).use {
                    JSONObject(it.readText())
                }
            }
            block(context, archive, manifest)
        } finally {
            archive.delete()
        }
    }

    private fun trace(points: List<TrackPoint>): CurvePassMatcher.Trace {
        fun endpoint(p: TrackPoint) = RecordedCurveEndpoint(p.id, p.recordedAt, p.latitude, p.longitude)
        val first = points.first()
        return requireNotNull(CurvePassMatcher.trace(RecordedCurveDetail(first.id, first.journeyId,
            "b".repeat(64), 1, endpoint(first), endpoint(points.last()), RecordedCurveDirection.RIGHT, points.size,
            points.zipWithNext().sumOf { (a, b) -> DiaryRules.metersBetween(a, b) }, null, null), points))
    }

    private fun JSONObject.result(id: String): JSONObject {
        val sessions = getJSONArray("leanSessions")
        val match = (0 until sessions.length()).map(sessions::getJSONObject).single {
            it.getJSONObject("input").getJSONObject("token").getString("journeyId") == id
        }
        return match.getJSONObject("result")
    }

    private fun JSONObject.curve(index: Int = 0) = getJSONArray("curves").getJSONObject(index)

    private fun tempArchive(context: Context): File {
        val directory = File(context.cacheDir, "camera")
        check(directory.mkdirs() || directory.isDirectory)
        return File.createTempFile("qa-timing-zip-", ".zip", directory)
    }

    private fun uri(context: Context, archive: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.files", archive)

    private companion object {
        const val UTC = 1_740_000_000_000L
        const val ELAPSED = 5_000_000_000L
        const val MANIFEST = "manifest.json"
        const val TIMING = "timingEstimated"
        const val REASON = "HANDLEBAR_TIMING_ESTIMATED"
        const val LEGACY = "qa-v1"
        const val MEASURED = "qa-v2-measured"
        const val ESTIMATED = "qa-v2-estimated"
        const val MIXED = "qa-v2-mixed"
        val variants = linkedMapOf(LEGACY to listOf(false), MEASURED to listOf(false),
            ESTIMATED to listOf(true), MIXED to listOf(false, true))
        val SHAPE = listOf(
            .0 to .0, .00015 to .0, .00028 to .00004, .00039 to .00012, .00045 to .00025, .00045 to .00040,
        ).map { (lat, lon) -> 40.0 + lat to 29.0 + lon }
    }
}
