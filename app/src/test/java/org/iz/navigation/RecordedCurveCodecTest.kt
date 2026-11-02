package org.iz.navigation

import org.iz.navigation.data.RecordedCurveCodec
import org.iz.navigation.data.RecordedCurveDetail
import org.iz.navigation.data.RecordedCurveDetails
import org.iz.navigation.data.RecordedCurveDirection
import org.iz.navigation.data.RecordedCurveEndpoint
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RecordedCurveCodecTest {
    @Test fun nontrivialAssetRoundTripPreservesSelectionIdsAndRecordedProvenance() {
        val restored = RecordedCurveCodec.decode(RecordedCurveCodec.encode(asset()), JOURNEY_ID, FINGERPRINT)
        assertNotNull(restored)
        restored!!
        assertEquals(JOURNEY_ID, restored.journeyId)
        assertEquals(FINGERPRINT, restored.sourceFingerprint)
        assertEquals(1, restored.algorithmVersion)
        assertEquals(8, restored.usablePointCount)
        assertEquals(1, restored.excludedIntervalCount)
        assertEquals(listOf("curve-right-01", "curve-left-02"), restored.curves.map { it.id })
        val first = restored.curves[0]
        assertEquals(JOURNEY_ID, first.journeyId)
        assertEquals(FINGERPRINT, first.sourceFingerprint)
        assertEquals(1, first.algorithmVersion)
        assertEquals(RecordedCurveDirection.RIGHT, first.direction)
        assertEquals("fix-a", first.start.pointId)
        assertEquals(1_000L, first.start.at)
        assertEquals(0.0, first.start.latitude, 0.0)
        assertEquals(0.0, first.start.longitude, 0.0)
        assertEquals("fix-d", first.end.pointId)
        assertEquals(7_000L, first.end.at)
        assertEquals(0.0006, first.end.latitude, 0.0)
        assertEquals(0.0006, first.end.longitude, 0.0)
        assertEquals(4, first.pointCount)
        assertEquals(113.89, first.recordedDistanceMeters, 0.0)
        assertEquals(3.6, first.sampledMinSpeedKmh!!, 0.0)
        assertEquals(10.8, first.sampledMaxSpeedKmh!!, 0.0)
        val second = restored.curves[1]
        assertEquals(RecordedCurveDirection.LEFT, second.direction)
        assertEquals("fix-e", second.start.pointId)
        assertEquals("fix-h", second.end.pointId)
        assertEquals(12_000L, second.start.at)
        assertEquals(18_000L, second.end.at)
        assertNull(second.sampledMinSpeedKmh)
        assertNull(second.sampledMaxSpeedKmh)
    }

    @Test fun zeroSampledSpeedRemainsMeasuredZeroWhileUnknownRemainsNull() {
        val first = asset().curves[0].copy(sampledMinSpeedKmh = 0.0, sampledMaxSpeedKmh = 0.0)
        val input = asset().copy(curves = listOf(first, asset().curves[1]))
        val restored = RecordedCurveCodec.decode(RecordedCurveCodec.encode(input), JOURNEY_ID, FINGERPRINT)
        assertNotNull(restored)
        assertEquals(0.0, restored!!.curves[0].sampledMinSpeedKmh!!, 0.0)
        assertEquals(0.0, restored.curves[0].sampledMaxSpeedKmh!!, 0.0)
        assertNull(restored.curves[1].sampledMinSpeedKmh)
        assertNull(restored.curves[1].sampledMaxSpeedKmh)
    }

    @Test fun endpointIdsWithJsonEscapesSurviveWithoutBecomingDifferentSelections() {
        val escaped = "fix-\"quoted\"-\\gps-İz"
        val first = asset().curves[0].let { it.copy(start = it.start.copy(pointId = escaped)) }
        val input = asset().copy(curves = listOf(first, asset().curves[1]))
        val restored = RecordedCurveCodec.decode(RecordedCurveCodec.encode(input), JOURNEY_ID, FINGERPRINT)
        assertNotNull(restored)
        assertEquals("curve-right-01", restored!!.curves[0].id)
        assertEquals(escaped, restored.curves[0].start.pointId)
        assertEquals("fix-d", restored.curves[0].end.pointId)
        assertEquals(FINGERPRINT, restored.curves[0].sourceFingerprint)
    }

    @Test fun emptyPreparedResultRetainsItsKnownCountsAndDoesNotInventACurve() {
        val empty = RecordedCurveDetails(JOURNEY_ID, FINGERPRINT, 1, emptyList(), 0, 3)
        val restored = RecordedCurveCodec.decode(RecordedCurveCodec.encode(empty), JOURNEY_ID, FINGERPRINT)
        assertNotNull(restored)
        assertEquals(JOURNEY_ID, restored!!.journeyId)
        assertEquals(0, restored.usablePointCount)
        assertEquals(3, restored.excludedIntervalCount)
        assertTrue(restored.curves.isEmpty())
    }

    @Test fun expectedJourneyAndFingerprintMustBothMatchThePersistedAsset() {
        val encoded = RecordedCurveCodec.encode(asset())
        assertNotNull("Fixture must be valid for its actual owner", RecordedCurveCodec.decode(encoded, JOURNEY_ID, FINGERPRINT))
        assertNull(RecordedCurveCodec.decode(encoded, "another-journey", FINGERPRINT))
        assertNull(RecordedCurveCodec.decode(encoded, JOURNEY_ID, "b".repeat(64)))
    }

    @Test fun malformedOrNonObjectJsonIsRejectedWithoutPublishingAnEmptyAsset() {
        listOf("", " ", "{", "not-json", "[]", "null", "{}", "{\"curves\":[}").forEach { input ->
            assertNull("Malformed input: $input", RecordedCurveCodec.decode(input, JOURNEY_ID, FINGERPRINT))
        }
    }

    @Test fun trailingNonJsonDataCannotHideBehindAnOtherwiseValidAsset() {
        val json = literalAsset().toString()
        assertNotNull(RecordedCurveCodec.decode(json, JOURNEY_ID, FINGERPRINT))
        assertNull(RecordedCurveCodec.decode(json + " trailing-garbage", JOURNEY_ID, FINGERPRINT))
        assertNull(RecordedCurveCodec.decode(json + "{}", JOURNEY_ID, FINGERPRINT))
    }

    @Test fun validJsonWithMoreThanEightMiBOfPaddingIsRejected() {
        val encoded = RecordedCurveCodec.encode(asset())
        assertNotNull(RecordedCurveCodec.decode(encoded, JOURNEY_ID, FINGERPRINT))
        // ASCII whitespace keeps the JSON object valid while exceeding the actual input-byte budget.
        val oversized = encoded + " ".repeat(8 * 1024 * 1024 + 1)
        assertNull(RecordedCurveCodec.decode(oversized, JOURNEY_ID, FINGERPRINT))
    }

    @Test fun literalExternalAssetDecodesToTheHandWrittenRecordedModel() {
        val decoded = RecordedCurveCodec.decode(literalAsset().toString(), JOURNEY_ID, FINGERPRINT)
        assertEquals(asset(), decoded)
        assertEquals(listOf("curve-right-01", "curve-left-02"), decoded!!.curves.map { it.id })
    }

    @Test fun encoderUsesTheAgreedModelKeysAndExplicitNullForUnknownSpeeds() {
        val json = JSONObject(RecordedCurveCodec.encode(asset()))
        assertEquals("codec-trip", json.getString("journeyId"))
        assertEquals(FINGERPRINT, json.getString("sourceFingerprint"))
        assertEquals(1, json.getInt("algorithmVersion"))
        assertEquals(8, json.getInt("usablePointCount"))
        assertEquals(1, json.getInt("excludedIntervalCount"))
        assertEquals(2, json.getJSONArray("curves").length())
        val first = json.getJSONArray("curves").getJSONObject(0)
        assertEquals("curve-right-01", first.getString("id"))
        assertEquals("RIGHT", first.getString("direction"))
        assertEquals("fix-a", first.getJSONObject("start").getString("pointId"))
        assertEquals(1_000L, first.getJSONObject("start").getLong("at"))
        assertEquals(0.0006, first.getJSONObject("end").getDouble("longitude"), 0.0)
        val second = json.getJSONArray("curves").getJSONObject(1)
        assertTrue(second.has("sampledMinSpeedKmh"))
        assertTrue(second.has("sampledMaxSpeedKmh"))
        assertTrue(second.isNull("sampledMinSpeedKmh"))
        assertTrue(second.isNull("sampledMaxSpeedKmh"))
    }

    @Test fun unknownRootOrNestedAlgorithmCannotBeReadAsVersionOne() {
        assertRejected("root version") { it.put("algorithmVersion", 99) }
        assertRejected("nested version") { firstCurve(it).put("algorithmVersion", 99) }
    }

    @Test fun everyNestedIdentityAndFingerprintMustMatchTheOwner() {
        assertRejected("nested journey") { firstCurve(it).put("journeyId", "foreign-trip") }
        assertRejected("nested fingerprint") { firstCurve(it).put("sourceFingerprint", "b".repeat(64)) }
        assertRejected("root identity") { it.put("journeyId", "foreign-trip") }
        assertRejected("root fingerprint") { it.put("sourceFingerprint", "b".repeat(64)) }
    }

    @Test fun malformedFingerprintsAreRejectedEvenWhenCallerAndAllRowsAgreeOnThem() {
        listOf("", "a".repeat(63), "a".repeat(65), "g".repeat(64)).forEach { bad ->
            val json = literalAsset().put("sourceFingerprint", bad)
            val curves = json.getJSONArray("curves")
            for (index in 0 until curves.length()) curves.getJSONObject(index).put("sourceFingerprint", bad)
            assertNull("Invalid agreed fingerprint length=${bad.length}",
                RecordedCurveCodec.decode(json.toString(), JOURNEY_ID, bad))
        }
    }

    @Test fun negativeOrIncoherentCountsAreNotCoercedIntoUsableAssets() {
        assertRejected("negative usable") { it.put("usablePointCount", -1) }
        assertRejected("negative excluded") { it.put("excludedIntervalCount", -1) }
        assertRejected("curve exceeds usable points") { it.put("usablePointCount", 3) }
        listOf(-1, 0, 3).forEach { count ->
            assertRejected("curve pointCount=$count") { firstCurve(it).put("pointCount", count) }
        }
    }

    @Test fun invalidDistancesAreRejectedRatherThanRestoredAsZero() {
        listOf<Any>(-1.0, "NaN", "Infinity", "not-a-distance", JSONObject.NULL).forEach { distance ->
            assertRejected("distance=$distance") { firstCurve(it).put("recordedDistanceMeters", distance) }
        }
    }

    @Test fun invalidCoordinateRangesOrNonFiniteCoordinatesInvalidateTheAsset() {
        listOf("latitude" to -90.0001, "latitude" to 90.0001,
            "longitude" to -180.0001, "longitude" to 180.0001,
            "latitude" to "NaN", "longitude" to "Infinity").forEach { (field, value) ->
            listOf("start", "end").forEach { endpoint ->
                assertRejected("$endpoint.$field=$value") {
                    firstCurve(it).getJSONObject(endpoint).put(field, value)
                }
            }
        }
    }

    @Test fun negativeEqualOrReversedEndpointTimesInvalidateTheAsset() {
        assertRejected("negative start") { firstCurve(it).getJSONObject("start").put("at", -1) }
        assertRejected("negative end") { firstCurve(it).getJSONObject("end").put("at", -1) }
        assertRejected("equal endpoint clocks") { firstCurve(it).getJSONObject("end").put("at", 1_000) }
        assertRejected("reversed endpoint clocks") { firstCurve(it).getJSONObject("end").put("at", 999) }
    }

    @Test fun reversedCurveOrderAndOverlappingSpansAreNotSilentlySortedOrJoined() {
        assertRejected("reversed list") {
            val rows = it.getJSONArray("curves")
            it.put("curves", JSONArray().put(rows.getJSONObject(1)).put(rows.getJSONObject(0)))
        }
        assertRejected("overlapping spans") {
            it.getJSONArray("curves").getJSONObject(1).getJSONObject("start").put("at", 6_000)
        }
    }

    @Test fun impossibleSpeedPairsCannotBecomeAValidMeasuredRange() {
        val invalid = listOf<Pair<Any, Any>>(
            -1.0 to 10.8, 3.6 to -1.0, 10.8 to 3.6,
            "NaN" to 10.8, 3.6 to "Infinity", "not-speed" to 10.8,
            JSONObject.NULL to 10.8, 3.6 to JSONObject.NULL,
        )
        invalid.forEach { (minimum, maximum) ->
            assertRejected("speed range=$minimum..$maximum") {
                firstCurve(it).put("sampledMinSpeedKmh", minimum).put("sampledMaxSpeedKmh", maximum)
            }
        }
    }

    @Test fun duplicateOrBlankSelectionAndEndpointIdsAreNotRenderable() {
        assertRejected("blank curve ID") { firstCurve(it).put("id", "") }
        assertRejected("duplicate curve ID") {
            it.getJSONArray("curves").getJSONObject(1).put("id", "curve-right-01")
        }
        assertRejected("blank start ID") { firstCurve(it).getJSONObject("start").put("pointId", "") }
        assertRejected("blank end ID") { firstCurve(it).getJSONObject("end").put("pointId", "") }
    }

    @Test fun missingRequiredStructureDoesNotDecodeAsADefaultEmptyResult() {
        listOf("journeyId", "sourceFingerprint", "algorithmVersion", "usablePointCount",
            "excludedIntervalCount", "curves").forEach { key ->
            assertRejected("missing root $key") { it.remove(key) }
        }
        listOf("id", "journeyId", "sourceFingerprint", "algorithmVersion", "start", "end",
            "direction", "pointCount", "recordedDistanceMeters").forEach { key ->
            assertRejected("missing curve $key") { firstCurve(it).remove(key) }
        }
        assertRejected("wrong curves type") { it.put("curves", JSONObject()) }
        assertRejected("unknown direction") { firstCurve(it).put("direction", "STRAIGHT") }
    }

    @Test fun exactly4096OrderedCurvesCanBeDecodedWithTheirSelectionIds() {
        val json = manyCurves(4_096)
        val decoded = RecordedCurveCodec.decode(json, JOURNEY_ID, FINGERPRINT)
        assertNotNull(decoded)
        assertEquals(4_096, decoded!!.curves.size)
        assertEquals("curve-0", decoded.curves.first().id)
        assertEquals("curve-4095", decoded.curves.last().id)
        assertEquals(16_384, decoded.usablePointCount)
    }

    @Test fun moreThan4096OtherwiseValidCurvesIsRejectedBelowTheByteLimit() {
        val json = manyCurves(4_097)
        assertTrue("This fixture isolates curve count, not byte size", json.toByteArray(Charsets.UTF_8).size < 8 * 1024 * 1024)
        assertNull(RecordedCurveCodec.decode(json, JOURNEY_ID, FINGERPRINT))
    }

    @Test fun utf8ByteBudgetIsNotMistakenForUtf16CharacterCount() {
        val json = manyCurves(4_096, decoratedIds = true)
        assertTrue("UTF-16 size alone would admit this input", json.length < 8 * 1024 * 1024)
        assertTrue("Actual UTF-8 exceeds the allowed input budget", json.toByteArray(Charsets.UTF_8).size > 8 * 1024 * 1024)
        assertNull(RecordedCurveCodec.decode(json, JOURNEY_ID, FINGERPRINT))
    }

    private fun assertRejected(label: String, change: (JSONObject) -> Unit) {
        val json = literalAsset()
        change(json)
        assertNull(label, RecordedCurveCodec.decode(json.toString(), JOURNEY_ID, FINGERPRINT))
    }

    private fun firstCurve(json: JSONObject): JSONObject = json.getJSONArray("curves").getJSONObject(0)

    private fun manyCurves(count: Int, decoratedIds: Boolean = false): String {
        val json = literalAsset()
        val rows = JSONArray()
        val decoration = if (decoratedIds) "曲".repeat(190) else ""
        repeat(count) { index ->
            // Unique, non-overlapping recorded spans and <=200-character IDs; no production builder.
            val row = firstCurve(literalAsset())
            row.put("id", "$decoration" + "curve-$index")
            row.getJSONObject("start").put("pointId", "$decoration" + "start-$index")
                .put("at", 1_000L + index * 10_000L)
            row.getJSONObject("end").put("pointId", "$decoration" + "end-$index")
                .put("at", 7_000L + index * 10_000L)
            rows.put(row)
        }
        return json.put("curves", rows).put("usablePointCount", count * 4)
            .put("excludedIntervalCount", 0).toString()
    }

    // External schema1 fixture is literal and independent of encode()/RecordedCurves.prepare().
    private fun literalAsset() = JSONObject("""
        {
          "journeyId":"codec-trip", "sourceFingerprint":"$FINGERPRINT", "algorithmVersion":1,
          "usablePointCount":8, "excludedIntervalCount":1,
          "curves":[
            {"id":"curve-right-01", "journeyId":"codec-trip", "sourceFingerprint":"$FINGERPRINT",
             "algorithmVersion":1, "direction":"RIGHT", "pointCount":4, "recordedDistanceMeters":113.89,
             "sampledMinSpeedKmh":3.6, "sampledMaxSpeedKmh":10.8,
             "start":{"pointId":"fix-a", "at":1000, "latitude":0.0, "longitude":0.0},
             "end":{"pointId":"fix-d", "at":7000, "latitude":0.0006, "longitude":0.0006}},
            {"id":"curve-left-02", "journeyId":"codec-trip", "sourceFingerprint":"$FINGERPRINT",
             "algorithmVersion":1, "direction":"LEFT", "pointCount":4, "recordedDistanceMeters":113.89,
             "sampledMinSpeedKmh":null, "sampledMaxSpeedKmh":null,
             "start":{"pointId":"fix-e", "at":12000, "latitude":0.001, "longitude":0.001},
             "end":{"pointId":"fix-h", "at":18000, "latitude":0.0016, "longitude":0.0004}}
          ]
        }
    """.trimIndent())

    private fun asset(): RecordedCurveDetails {
        val first = RecordedCurveDetail("curve-right-01", JOURNEY_ID, FINGERPRINT, 1,
            RecordedCurveEndpoint("fix-a", 1_000, 0.0, 0.0),
            RecordedCurveEndpoint("fix-d", 7_000, 0.0006, 0.0006),
            RecordedCurveDirection.RIGHT, 4, 113.89, 3.6, 10.8)
        val second = RecordedCurveDetail("curve-left-02", JOURNEY_ID, FINGERPRINT, 1,
            RecordedCurveEndpoint("fix-e", 12_000, 0.001, 0.001),
            RecordedCurveEndpoint("fix-h", 18_000, 0.0016, 0.0004),
            RecordedCurveDirection.LEFT, 4, 113.89, null, null)
        return RecordedCurveDetails(JOURNEY_ID, FINGERPRINT, 1, listOf(first, second), 8, 1)
    }

    private companion object {
        const val JOURNEY_ID = "codec-trip"
        val FINGERPRINT = "a".repeat(64)
    }
}
