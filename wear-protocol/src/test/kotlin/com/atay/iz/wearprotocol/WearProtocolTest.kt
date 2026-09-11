package com.atay.iz.wearprotocol

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class WearProtocolTest {
    @Test fun versionFourCanNegotiateWithoutChangingLegacyCommandPayloads() {
        assertEquals(4, WearProtocol.CURRENT_VERSION)
        val command = WearCommand("request", WearAction.REFRESH, 1000L)
        assertEquals(command, WearProtocol.decodeCommand(WearProtocol.encodeCommand(command, 4)))
        assertEquals(command, WearProtocol.decodeCommand(WearProtocol.encodeCommand(command, 1)))
    }
    @Test fun allFiveModesSurviveStartCommandTransfer() {
        for (mode in WearMode.entries) {
            val command = WearCommand("request-1", WearAction.START, 1_000L, mode)
            assertEquals(command, WearProtocol.decodeCommand(WearProtocol.encodeCommand(command)))
        }
    }

    @Test fun stopRemainsBoundToItsJourneyAndRefreshDoesNotAcquireAnAction() {
        val stop = WearCommand("stop-1", WearAction.STOP, 1_000L, journeyId = "trip-1")
        val refresh = WearCommand("refresh-1", WearAction.REFRESH, 1_000L)
        assertEquals(stop, WearProtocol.decodeCommand(WearProtocol.encodeCommand(stop)))
        assertEquals(refresh, WearProtocol.decodeCommand(WearProtocol.encodeCommand(refresh)))
    }

    @Test fun decodesAnIndependentV1StartFixture() {
        val frame = hex("495a575001010001630000000000000003e8010300")
        val expected = WearCommand("c", WearAction.START, 1_000L, WearMode.WALK)
        assertEquals(expected, WearProtocol.decodeCommand(frame))
        assertArrayEquals(frame, WearProtocol.encodeCommand(expected, version = 1))
    }

    @Test fun allAcknowledgedOutcomesKeepIdentityAndTurkishMessages() {
        for (code in WearResultCode.entries) {
            val result = WearResult("request-1", code, "Yürüyüş kaydı: 500 metre ✓", "trip-1")
            assertEquals(result, WearProtocol.decodeResult(WearProtocol.encodeResult(result)))
        }
        val result = WearResult("refresh-1", WearResultCode.REFRESHED, "Kayıt yok")
        assertEquals(result, WearProtocol.decodeResult(WearProtocol.encodeResult(result)))
    }

    @Test fun measuredValuesSurviveSnapshotTransfer() {
        val snapshot = activeSnapshot()
        assertEquals(snapshot, WearProtocol.decodeSnapshot(WearProtocol.encodeSnapshot(snapshot)))
    }

    @Test fun missingMeasurementsRemainUnknownRatherThanBecomingZero() {
        val missing = activeSnapshot().copy(averageSpeedKmh = null, maxSpeedKmh = null,
            currentSpeedKmh = null, stepCount = null)
        val zero = missing.copy(averageSpeedKmh = 0.0, maxSpeedKmh = 0.0, currentSpeedKmh = 0.0, stepCount = 0L)
        assertEquals(missing, WearProtocol.decodeSnapshot(WearProtocol.encodeSnapshot(missing)))
        assertEquals(zero, WearProtocol.decodeSnapshot(WearProtocol.encodeSnapshot(zero)))
    }

    @Test fun activeUnknownTransportIsRepresentedWithoutInventingAMode() {
        val snapshot = activeSnapshot().copy(mode = null, stepCount = null)
        assertEquals(snapshot, WearProtocol.decodeSnapshot(WearProtocol.encodeSnapshot(snapshot)))
    }

    @Test fun idleAndInterruptedSnapshotsRemainDistinct() {
        val idle = WearSnapshot(generatedAt = 10_000L)
        val interrupted = activeSnapshot().copy(recording = false, temporary = false, deadlineAt = null)
        assertEquals(idle, WearProtocol.decodeSnapshot(WearProtocol.encodeSnapshot(idle)))
        assertEquals(interrupted, WearProtocol.decodeSnapshot(WearProtocol.encodeSnapshot(interrupted)))
    }

    @Test fun invalidActionShapesCannotBeEncoded() {
        val invalid = listOf(
            WearCommand("a", WearAction.START, 1L),
            WearCommand("a", WearAction.START, 1L, WearMode.CAR, "trip"),
            WearCommand("a", WearAction.STOP, 1L),
            WearCommand("a", WearAction.STOP, 1L, WearMode.CAR, "trip"),
            WearCommand("a", WearAction.REFRESH, 1L, WearMode.CAR),
            WearCommand("a", WearAction.REFRESH, 1L, journeyId = "trip"),
            WearCommand("", WearAction.REFRESH, 1L),
            WearCommand(" ", WearAction.REFRESH, 1L),
            WearCommand("a\n", WearAction.REFRESH, 1L),
            WearCommand("a", WearAction.REFRESH, -1L),
        )
        invalid.forEach { value -> assertThrows(IllegalArgumentException::class.java) { WearProtocol.encodeCommand(value) } }
    }

    @Test fun malformedActionShapeCannotBypassDecoderValidation() {
        // STOP carrying a mode and no journey ID.
        assertNull(WearProtocol.decodeCommand(hex("495a575001010001630100000000000003e8010300")))
    }

    @Test fun invalidSnapshotShapesAndNumbersCannotBeEncoded() {
        val active = activeSnapshot()
        val invalid = listOf(
            WearSnapshot(1L, recording = true), WearSnapshot(1L, mode = WearMode.WALK),
            WearSnapshot(1L, distanceMeters = 1.0), WearSnapshot(1L, stepCount = 0L),
            active.copy(journeyId = null), active.copy(startedAt = null), active.copy(startedAt = -1L),
            active.copy(generatedAt = -1L), active.copy(deadlineAt = null),
            active.copy(deadlineAt = active.startedAt), active.copy(temporary = false),
            active.copy(distanceMeters = -1.0), active.copy(distanceMeters = Double.NaN),
            active.copy(distanceMeters = Double.POSITIVE_INFINITY),
            active.copy(averageSpeedKmh = Double.NEGATIVE_INFINITY), active.copy(maxSpeedKmh = -1.0),
            active.copy(currentSpeedKmh = Double.NaN), active.copy(elapsedMillis = -1L), active.copy(stepCount = -1L),
        )
        invalid.forEach { value -> assertThrows(IllegalArgumentException::class.java) { WearProtocol.encodeSnapshot(value) } }
    }

    @Test fun binaryNaNAndNegativeNumbersAreRejectedByDecoder() {
        val idle = WearProtocol.encodeSnapshot(WearSnapshot(1L))
        // V1 idle frame: the distance starts at byte 20 and elapsed duration at byte 28.
        for (distance in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0)) {
            val corrupted = idle.copyOf()
            ByteBuffer.wrap(corrupted).putDouble(20, distance)
            assertNull(WearProtocol.decodeSnapshot(corrupted))
        }
        val negativeElapsed = idle.copyOf()
        ByteBuffer.wrap(negativeElapsed).putLong(28, -1L)
        assertNull(WearProtocol.decodeSnapshot(negativeElapsed))
        assertNull(WearProtocol.decodeSnapshot(idle.copyOf().also { it[19] = 1 }))
    }

    @Test fun unknownEnumsAndNonBooleanPresenceFlagsAreRejected() {
        val valid = hex("495a575001010001630000000000000003e8010300")
        assertNull(WearProtocol.decodeCommand(valid.copyOf().also { it[9] = 127 }))
        assertNull(WearProtocol.decodeCommand(valid.copyOf().also { it[19] = 127 }))
        assertNull(WearProtocol.decodeCommand(valid.copyOf().also { it[18] = 2 }))
    }

    @Test fun wrongMagicVersionAndMessageTypeDoNotDecode() {
        val command = WearProtocol.encodeCommand(WearCommand("a", WearAction.REFRESH, 1L))
        assertNull(WearProtocol.decodeCommand(command.copyOf().also { it[0] = 0 }))
        assertNull(WearProtocol.decodeCommand(command.copyOf().also { it[4] = (WearProtocol.CURRENT_VERSION + 1).toByte() }))
        assertNull(WearProtocol.decodeCommand(command.copyOf().also { it[5] = 127 }))
        assertNull(WearProtocol.decodeResult(command))
        assertNull(WearProtocol.decodeSnapshot(command))
        assertNull(WearProtocol.decodeCommand(WearProtocol.encodeResult(WearResult("a", WearResultCode.ERROR, "Error"))))
    }

    @Test fun everyTruncationAndTrailingByteIsRejectedForAllFrameTypes() {
        rejectIncomplete(WearProtocol.encodeCommand(WearCommand("a", WearAction.START, 1L, WearMode.CAR)), WearProtocol::decodeCommand)
        rejectIncomplete(WearProtocol.encodeResult(WearResult("a", WearResultCode.NEEDS_PHONE, "Telefonda devam et")), WearProtocol::decodeResult)
        rejectIncomplete(WearProtocol.encodeSnapshot(activeSnapshot()), WearProtocol::decodeSnapshot)
    }

    @Test fun oversizedPayloadsAndStringsAreBoundedBeforeProcessing() {
        val oversized = ByteArray(4_097)
        assertNull(WearProtocol.decodeCommand(oversized))
        assertNull(WearProtocol.decodeResult(oversized))
        assertNull(WearProtocol.decodeSnapshot(oversized))
        assertThrows(IllegalArgumentException::class.java) {
            WearProtocol.encodeCommand(WearCommand("a".repeat(201), WearAction.REFRESH, 1L))
        }
        assertThrows(IllegalArgumentException::class.java) {
            WearProtocol.encodeResult(WearResult("a", WearResultCode.ERROR, "ş".repeat(2_049)))
        }
        assertNull(WearProtocol.decodeCommand(hex("495a57500101ffff")))
    }

    @Test fun invalidUtf8AndUnpairedSurrogatesCannotSilentlyChangeIdentity() {
        assertNull(WearProtocol.decodeCommand(hex("495a575001010001ff0000000000000003e8010300")))
        assertThrows(IllegalArgumentException::class.java) {
            WearProtocol.encodeCommand(WearCommand("\uD800", WearAction.REFRESH, 1L))
        }
        assertThrows(IllegalArgumentException::class.java) {
            WearProtocol.encodeResult(WearResult("a", WearResultCode.ERROR, "bad\u0000message"))
        }
    }

    @Test fun commandsExpireAtTtlBoundaryAndCannotBeFreshByOverflow() {
        val command = WearCommand("a", WearAction.START, 1_000_000L, WearMode.WALK)
        assertTrue(WearProtocol.isFreshCommand(command, 1_119_999L))
        assertFalse(WearProtocol.isFreshCommand(command, 1_120_000L))
        assertFalse(WearProtocol.isFreshCommand(command, Long.MAX_VALUE))
        assertFalse(WearProtocol.isFreshCommand(command, -1L))
        assertFalse(WearProtocol.isFreshCommand(command.copy(requestedAt = Long.MIN_VALUE), 0L))
        assertFalse(WearProtocol.isFreshCommand(command.copy(requestedAt = Long.MAX_VALUE), 0L))
    }

    @Test fun snapshotsBecomeStaleWithoutChangingStoredMeasurements() {
        val snapshot = WearSnapshot(1_000_000L)
        assertTrue(WearProtocol.isFreshSnapshot(snapshot, 1_029_999L))
        assertFalse(WearProtocol.isFreshSnapshot(snapshot, 1_030_000L))
        assertFalse(WearProtocol.isFreshSnapshot(snapshot.copy(generatedAt = -1L), 0L))
        assertFalse(WearProtocol.isFreshSnapshot(snapshot, Long.MAX_VALUE))
    }

    @Test fun smallDeviceClockSkewIsToleratedButFarFutureCommandsAreNot() {
        val command = WearCommand("a", WearAction.REFRESH, 1_000_000L)
        val snapshot = WearSnapshot(1_000_000L)
        assertTrue(WearProtocol.isFreshCommand(command, 995_000L))
        assertFalse(WearProtocol.isFreshCommand(command, 994_999L))
        assertTrue(WearProtocol.isFreshSnapshot(snapshot, 995_000L))
        assertFalse(WearProtocol.isFreshSnapshot(snapshot, 994_999L))
    }

    @Test fun v2RunUsesAppendedCodeAndV1CannotStartIt() {
        val command = WearCommand("c", WearAction.START, 1_000L, WearMode.RUN)
        val frame = hex("495a575002010001630000000000000003e8010500")
        assertArrayEquals(frame, WearProtocol.encodeCommand(command, version = 2))
        assertEquals(command, WearProtocol.decodeCommand(frame))
        assertNull(WearProtocol.decodeCommand(frame.copyOf().also { it[4] = 1 }))
        assertThrows(IllegalArgumentException::class.java) { WearProtocol.encodeCommand(command, 1) }
        assertEquals(listOf(0, 1, 2, 3, 4, 5), WearMode.entries.map { it.ordinal })
    }

    @Test fun oldWatchReceivesGenericRunningJourneyAndCanStillStopIt() {
        val run = activeSnapshot().copy(mode = WearMode.RUN, averagePaceSecondsPerKm = 420.0,
            health = WearHealthSummary(watchSteps = 0, lastCheckedAt = 60_000, partial = true))
        val legacy = WearProtocol.decodeSnapshot(WearProtocol.encodeSnapshot(run, 1))!!
        assertEquals(run.journeyId, legacy.journeyId)
        assertTrue(legacy.recording)
        assertNull(legacy.mode)
        assertNull(legacy.health)
        assertNull(legacy.averagePaceSecondsPerKm)
        val stop = WearCommand("stop", WearAction.STOP, run.generatedAt, journeyId = legacy.journeyId)
        assertEquals(stop, WearProtocol.decodeCommand(WearProtocol.encodeCommand(stop, 1)))
        assertEquals(1, WearProtocol.frameVersion(WearProtocol.encodeSnapshot(run, 1)))
    }

    @Test fun v2HealthPreservesMeasuredTimesMissingAndActualZeroSeparately() {
        val health = WearHealthSummary(heartRateMeanBpm = 123.5, heartRateMinBpm = 95.0,
            heartRateMaxBpm = 141.0, latestHeartRateBpm = 119.0, latestHeartRateAt = 40_000,
            heartRateSampleCount = 5, totalCaloriesKcal = 0.0, calorieCoverageMillis = 20_000,
            watchSteps = null, stepCoverageMillis = 0, measurementStartAt = 20_000,
            measurementEndAt = 40_000, lastCheckedAt = 59_000, partial = true)
        val snapshot = activeSnapshot().copy(health = health)
        assertEquals(snapshot, WearProtocol.decodeSnapshot(WearProtocol.encodeSnapshot(snapshot)))
        assertNotEquals(snapshot.generatedAt, snapshot.health!!.latestHeartRateAt)
        rejectIncomplete(WearProtocol.encodeSnapshot(snapshot), WearProtocol::decodeSnapshot)
        assertThrows(IllegalArgumentException::class.java) {
            WearProtocol.encodeSnapshot(snapshot.copy(health = health.copy(latestHeartRateBpm = Double.NaN)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            WearProtocol.encodeSnapshot(snapshot.copy(health = health.copy(watchSteps = -1)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            WearProtocol.encodeSnapshot(WearSnapshot(1, health = health))
        }
    }

    @Test fun legacySnapshotGoldenFramesRemainByteForByteStable() {
        val v1 = hex("495a57500103000000000000ee48010006747269702d3101030100000000000003e8010100000000000dbf88014080060000000000000000000000ea6001403ec3d70a3d70a40140440000000000000100000000000000000100000000000002df")
        val v2 = hex("495a57500203000000000000ee48010006747269702d3101030100000000000003e8010100000000000dbf88014080060000000000000000000000ea6001403ec3d70a3d70a40140440000000000000100000000000000000100000000000002df0000")
        assertArrayEquals(v1, WearProtocol.encodeSnapshot(activeSnapshot(), version = 1))
        assertArrayEquals(v2, WearProtocol.encodeSnapshot(activeSnapshot(), version = 2))
        assertEquals(activeSnapshot().copy(averagePaceSecondsPerKm = null, health = null, weather = null), WearProtocol.decodeSnapshot(v1))
        assertEquals(activeSnapshot().copy(weather = null), WearProtocol.decodeSnapshot(v2))
    }

    @Test fun v3WeatherRoundTripsReadyLoadingErrorAndNullWithoutChangingForecastTimes() {
        val ready = WearRouteWeather(
            status = WearWeatherStatus.READY,
            calculatedAt = 50_000L,
            validUntil = 3_650_000L,
            remainingMeters = 12_345.5,
            arrivalAt = 1_250_000L,
            hazardStartsAt = 350_000L,
            threshold = WearWeatherThreshold.EXCEEDED,
            headline = "Yağmur yaklaşıyor",
            detail = "Yaklaşık 5 dk sonra • varış 12:20",
        )
        val values = listOf(
            ready,
            WearRouteWeather(WearWeatherStatus.LOADING, 50_000L, 50_000L),
            WearRouteWeather(WearWeatherStatus.ERROR, 50_000L, 50_000L, headline = "Hava verisi alınamadı"),
        )
        values.forEach { weather ->
            val snapshot = activeSnapshot().copy(weather = weather)
            assertEquals(snapshot, WearProtocol.decodeSnapshot(WearProtocol.encodeSnapshot(snapshot, version = 3)))
            assertEquals(weather.calculatedAt, WearProtocol.decodeSnapshot(WearProtocol.encodeSnapshot(snapshot, 3))!!.weather!!.calculatedAt)
            assertEquals(weather.validUntil, WearProtocol.decodeSnapshot(WearProtocol.encodeSnapshot(snapshot, 3))!!.weather!!.validUntil)
        }
        val withoutWeather = activeSnapshot().copy(weather = null)
        assertEquals(withoutWeather, WearProtocol.decodeSnapshot(WearProtocol.encodeSnapshot(withoutWeather, 3)))
    }

    @Test fun v3WeatherBoundsAndReadyShapeAreValidatedBeforeEncoding() {
        val ready = WearRouteWeather(WearWeatherStatus.READY, 50_000L, 3_650_000L,
            remainingMeters = 2_000.0, arrivalAt = 500_000L, hazardStartsAt = 200_000L,
            threshold = WearWeatherThreshold.EXCEEDED, headline = "Yağmur", detail = "Yaklaşık 3 dk sonra")
        val invalid = listOf(
            ready.copy(calculatedAt = -1L),
            ready.copy(validUntil = ready.calculatedAt),
            ready.copy(remainingMeters = -1.0),
            ready.copy(remainingMeters = Double.NaN),
            ready.copy(arrivalAt = ready.calculatedAt - 1),
            ready.copy(hazardStartsAt = ready.calculatedAt - 1),
            ready.copy(hazardStartsAt = ready.arrivalAt!! + 1),
            ready.copy(arrivalAt = null),
            ready.copy(headline = ""),
            ready.copy(hazardStartsAt = null),
            ready.copy(headline = "ğ".repeat(61)),
            ready.copy(detail = "ğ".repeat(151)),
            ready.copy(headline = "bozuk\u0000metin"),
        )
        invalid.forEach { weather ->
            assertThrows(IllegalArgumentException::class.java) {
                WearProtocol.encodeSnapshot(activeSnapshot().copy(weather = weather), version = 3)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            WearProtocol.encodeSnapshot(activeSnapshot().copy(generatedAt = ready.calculatedAt - 5_001, weather = ready), version = 3)
        }
    }

    @Test fun weatherIsV3OnlyAndAllV3PathsCapabilitiesAndFramesAreIndependent() {
        val weather = WearRouteWeather(WearWeatherStatus.READY, 50_000L, 3_650_000L,
            arrivalAt = 500_000L, headline = "Açık")
        val snapshot = activeSnapshot().copy(weather = weather)
        assertNull(WearProtocol.decodeSnapshot(WearProtocol.encodeSnapshot(snapshot, version = 2))!!.weather)
        assertEquals("/iz/v3/command", WearProtocol.commandPath(3))
        assertEquals("/iz/v3/result", WearProtocol.resultPath(3))
        assertEquals("/iz/v3/state", WearProtocol.statePath(3))
        assertEquals("iz_phone_v3", WearProtocol.phoneCapability(3))
        assertEquals(3, WearProtocol.commandVersion("/iz/v3/command"))
        assertEquals(3, WearProtocol.frameVersion(WearProtocol.encodeSnapshot(snapshot, 3)))
    }

    @Test fun malformedV3WeatherEnumsStringsTruncationAndTrailingDataAreRejected() {
        val weather = WearRouteWeather(WearWeatherStatus.READY, 50_000L, 3_650_000L,
            remainingMeters = 1_000.0, arrivalAt = 500_000L, headline = "Açık", detail = "Yol kuru")
        val frame = WearProtocol.encodeSnapshot(activeSnapshot().copy(weather = weather), 3)
        val weatherOffset = WearProtocol.encodeSnapshot(activeSnapshot(), 2).size
        assertNull(WearProtocol.decodeSnapshot(frame.copyOf().also { it[weatherOffset + 1] = 127 }))
        val thresholdOffset = weatherOffset + 1 + 1 + 8 + 8 + 8 + 1 + 8 + 1
        assertNull(WearProtocol.decodeSnapshot(frame.copyOf().also { it[thresholdOffset] = 127 }))
        val headlineLengthOffset = thresholdOffset + 1
        assertNull(WearProtocol.decodeSnapshot(frame.copyOf().also {
            it[headlineLengthOffset] = 0
            it[headlineLengthOffset + 1] = 121
        }))
        rejectIncomplete(frame, WearProtocol::decodeSnapshot)
    }
    private fun activeSnapshot() = WearSnapshot(
        generatedAt = 61_000L, journeyId = "trip-1", mode = WearMode.WALK, startedAt = 1_000L,
        temporary = true, deadlineAt = 901_000L, recording = true, distanceMeters = 512.75,
        elapsedMillis = 60_000L, averageSpeedKmh = 30.765, maxSpeedKmh = 40.0,
        currentSpeedKmh = 0.0, stepCount = 735L,
    )

    private fun rejectIncomplete(bytes: ByteArray, decode: (ByteArray) -> Any?) {
        for (size in bytes.indices) assertNull("Accepted truncated frame of $size bytes", decode(bytes.copyOf(size)))
        assertNull("Accepted trailing data", decode(bytes + byteArrayOf(0)))
    }

    private fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
