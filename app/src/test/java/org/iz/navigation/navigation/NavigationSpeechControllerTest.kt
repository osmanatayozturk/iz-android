package org.iz.navigation.navigation

import org.junit.Assert.*
import org.junit.Test
import org.iz.navigation.weather.*

class NavigationSpeechControllerTest {
    @Test fun committedPreparationIsSilentAndBecomesReady() {
        val f = Fixture()
        assertEquals(EngineReadiness.UNCHECKED, f.subject.status.value.readiness)
        f.subject.prepareGuidance()
        assertEquals(EngineReadiness.INITIALIZING, f.subject.status.value.readiness)
        assertEquals(1, f.engines.size)
        assertTrue(f.focus.requests.isEmpty())
        f.engine.ready(EngineReadiness.READY)
        assertEquals(EngineReadiness.READY, f.subject.status.value.readiness)
        assertTrue(f.engine.spoken.isEmpty())
    }

    @Test fun initializationTimesOutAtTenSecondsWithoutAutomaticRetry() {
        val f = Fixture()
        f.subject.prepareGuidance()
        f.clock.advance(9_999)
        assertEquals(EngineReadiness.INITIALIZING, f.subject.status.value.readiness)
        f.clock.advance(1)
        assertEquals(EngineReadiness.INIT_TIMEOUT, f.subject.status.value.readiness)
        assertEquals(1, f.engine.shutdowns)
        repeat(3) { f.subject.speakTurn("Eski dönüş") }
        f.subject.speakWeather("Yağış")
        assertEquals(SpeechTestResult.UNAVAILABLE, f.subject.testVoice())
        assertEquals(1, f.engines.size)
        assertTrue(f.engine.spoken.isEmpty())
    }

    @Test fun languageAndEngineFailuresRemainDistinctAndDoNotPlayQueuedCue() {
        for (failure in listOf(EngineReadiness.ENGINE_ERROR, EngineReadiness.LANGUAGE_MISSING, EngineReadiness.LANGUAGE_UNSUPPORTED)) {
            val f = Fixture()
            f.subject.speakTurn("Sağa dön")
            f.engine.ready(failure)
            assertEquals(failure, f.subject.status.value.readiness)
            assertNull(f.subject.status.value.requestFailure)
            assertTrue(f.engine.spoken.isEmpty())
            assertEquals(1, f.engine.shutdowns)
        }
    }

    @Test fun retryRejectsOldInitAndDeadlineCallbacksAndNeverReplaysPendingText() {
        val f = Fixture()
        f.subject.speakTurn("Önceki oturum")
        val old = f.engine
        val deadline = f.clock.tasks.single().task
        f.subject.retry()
        assertEquals(2, f.engines.size)
        old.ready(EngineReadiness.READY)
        deadline()
        assertEquals(EngineReadiness.INITIALIZING, f.subject.status.value.readiness)
        f.engine.ready(EngineReadiness.READY)
        assertEquals(EngineReadiness.READY, f.subject.status.value.readiness)
        assertTrue(f.engine.spoken.isEmpty())
        assertTrue(old.spoken.isEmpty())
    }

    @Test fun newCommittedSessionMayRetryFailureAndClearsOldRequest() {
        val f = Fixture()
        f.subject.speakTurn("Eski")
        f.engine.ready(EngineReadiness.ENGINE_ERROR)
        f.subject.prepareGuidance()
        assertEquals(2, f.engines.size)
        f.engine.ready(EngineReadiness.READY)
        assertTrue(f.engine.spoken.isEmpty())
    }

    @Test fun manualTestIsBusyDuringPendingAndActiveTurnAndNeverQueued() {
        val f = Fixture()
        f.subject.speakTurn("Sağa dön")
        assertEquals(SpeechTestResult.BUSY, f.subject.testVoice())
        f.engine.ready(EngineReadiness.READY)
        assertEquals(SpeechTestResult.BUSY, f.subject.testVoice())
        assertEquals(listOf("Sağa dön"), f.engine.spoken.map { it.first })
        f.engine.finish(f.engine.spoken.single().second)
        assertEquals(1, f.engine.spoken.size)
        assertEquals(SpeechTestResult.ACCEPTED, f.subject.testVoice())
        assertEquals(2, f.engine.spoken.size)
    }

    @Test fun turnPreemptsTestAndLateCompletionAndFocusLossCannotStopReplacement() {
        val f = Fixture()
        f.ready()
        assertEquals(SpeechTestResult.ACCEPTED, f.subject.testVoice())
        val testId = f.engine.spoken.single().second
        val oldFocus = f.focus.requests.single()
        f.subject.speakTurn("Sola dön")
        val turnId = f.engine.spoken.last().second
        assertNotEquals(testId, turnId)
        assertEquals(1, oldFocus.releases)
        f.engine.finish(testId, error = true)
        oldFocus.lost()
        assertNull(f.subject.status.value.requestFailure)
        assertEquals(0, f.focus.requests.last().releases)
        assertEquals("Sola dön", f.engine.activeOutput?.first)
        assertEquals(SpeechTestResult.BUSY, f.subject.testVoice())
        f.engine.finish(turnId)
        assertEquals(1, f.focus.requests.last().releases)
    }

    @Test fun focusDenialAndEngineOutputFailureAreRequestFailuresWithReadyEngine() {
        val f = Fixture()
        f.ready()
        f.focus.allow = false
        f.subject.speakTurn("Dön")
        assertEquals(EngineReadiness.READY, f.subject.status.value.readiness)
        assertEquals(SpeechRequestFailure(SpeechChannel.TURN, SpeechFailure.AUDIO_FOCUS), f.subject.status.value.requestFailure)
        assertTrue(f.engine.spoken.isEmpty())
        f.focus.allow = true
        f.engine.accept = false
        assertEquals(SpeechTestResult.ACCEPTED, f.subject.testVoice())
        assertEquals(SpeechRequestFailure(SpeechChannel.TEST, SpeechFailure.OUTPUT), f.subject.status.value.requestFailure)
        assertEquals(1, f.focus.requests.last().releases)
        assertEquals(EngineReadiness.READY, f.subject.status.value.readiness)
    }

    @Test fun currentOutputErrorAndFocusLossDropRequestWithoutReplay() {
        val f = Fixture()
        f.ready()
        f.subject.speakTurn("Dön")
        f.engine.finish(f.engine.spoken.last().second, error = true)
        assertEquals(SpeechFailure.OUTPUT, f.subject.status.value.requestFailure?.reason)
        assertEquals(1, f.focus.requests.last().releases)
        f.subject.speakTurn("Yeni dönüş")
        assertNull(f.subject.status.value.requestFailure)
        f.focus.requests.last().lost()
        assertEquals(SpeechFailure.AUDIO_FOCUS, f.subject.status.value.requestFailure?.reason)
        f.clock.advance(30_000)
        assertEquals(2, f.engine.spoken.size)
    }

    @Test fun cancellationDropsPendingTurnAndDoesNotCancelIndependentWeather() {
        val f = Fixture()
        f.subject.speakTurn("İptal")
        f.subject.cancelTurns()
        f.engine.ready(EngineReadiness.READY)
        assertTrue(f.engine.spoken.isEmpty())
        f.subject.speakWeather("Yağış")
        f.subject.cancelTurns()
        assertEquals(0, f.focus.requests.single().releases)
        f.subject.cancelWeather()
        assertEquals(1, f.focus.requests.single().releases)
        assertNull("Cancelled owner must stop actual engine output", f.engine.activeOutput)
    }

    @Test fun weatherNeverInterruptsTurnAndSuspendingAutomaticWeatherKeepsManualTest() {
        val f = Fixture()
        f.ready()
        f.subject.speakTurn("Dön")
        f.subject.speakWeather("Yağış")
        assertEquals(listOf("Dön"), f.engine.spoken.map { it.first })
        f.subject.cancelTurns()
        assertEquals(SpeechTestResult.ACCEPTED, f.subject.testVoice())
        f.subject.suspendAutomaticWeather()
        assertEquals(0, f.focus.requests.last().releases)
    }

    @Test fun expiredAndCancelledRequestsCannotDrainOnLateInitialization() {
        val f = Fixture()
        f.subject.speakTurn("Süresi geçti")
        f.clock.now = 15_001
        f.engine.ready(EngineReadiness.READY)
        assertTrue(f.engine.spoken.isEmpty())
        assertTrue(f.focus.requests.isEmpty())
    }

    @Test fun engineFactoryExceptionIsReportedWithoutRetryLoop() {
        val f = Fixture(createFailure = true)
        f.subject.prepareGuidance()
        assertEquals(EngineReadiness.ENGINE_ERROR, f.subject.status.value.readiness)
        f.subject.speakTurn("Dön")
        assertEquals(1, f.factoryCalls)
    }

    @Test fun navigationTestCannotOutliveItsTurnOwnerButWeatherCancelDoesNotStealIt() {
        val f = Fixture()
        f.ready()
        assertEquals(SpeechTestResult.ACCEPTED, f.subject.testVoice())
        val id = f.engine.spoken.single().second
        f.subject.cancelWeather()
        assertEquals(0, f.focus.requests.single().releases)
        f.subject.cancelTurns()
        assertEquals(1, f.focus.requests.single().releases)
        assertNull("Cancelled navigation test must stop engine output", f.engine.activeOutput)
        f.engine.finish(id, error = true)
        assertNull(f.subject.status.value.requestFailure)
    }

    @Test fun explicitWeatherTestSurvivesAutomaticSuspensionButNotWeatherOwnerExit() {
        val f = Fixture()
        f.ready()
        assertEquals(SpeechTestResult.ACCEPTED, f.subject.testVoice(SpeechChannel.WEATHER))
        f.subject.cancelTurns()
        f.subject.suspendAutomaticWeather()
        assertEquals(0, f.focus.requests.single().releases)
        f.subject.cancelWeather()
        assertEquals(1, f.focus.requests.single().releases)
        assertNull("Cancelled owner must stop actual engine output", f.engine.activeOutput)
    }

    @Test fun synchronousFocusLossReleasesReturnedLeaseWithoutSpeakingAndCannotStopReplacement() {
        val f = Fixture()
        f.ready()
        f.focus.loseDuringAcquire = true
        f.subject.speakTurn("Must not play")
        assertTrue(f.engine.spoken.isEmpty())
        assertNull(f.engine.activeOutput)
        assertEquals(EngineReadiness.READY, f.subject.status.value.readiness)
        assertEquals(SpeechRequestFailure(SpeechChannel.TURN, SpeechFailure.AUDIO_FOCUS), f.subject.status.value.requestFailure)
        val rejectedLease = f.focus.requests.single()
        assertEquals(1, rejectedLease.releases)
        f.focus.loseDuringAcquire = false
        f.subject.speakTurn("Replacement")
        rejectedLease.lost()
        assertEquals("Replacement", f.engine.activeOutput?.first)
        assertEquals(1, rejectedLease.releases)
        assertEquals(0, f.focus.requests.last().releases)
        assertNull(f.subject.status.value.requestFailure)
    }

    @Test fun synchronousEngineInitializationDrainsCurrentCueOnceAndCancelsDeadline() {
        val f = Fixture(initializeImmediately = true)
        f.subject.speakTurn("Current cue")
        assertEquals(EngineReadiness.READY, f.subject.status.value.readiness)
        assertEquals(listOf("Current cue"), f.engine.spoken.map { it.first })
        assertEquals("Current cue", f.engine.activeOutput?.first)
        f.clock.advance(10_000)
        assertEquals(EngineReadiness.READY, f.subject.status.value.readiness)
        assertEquals(1, f.engine.spoken.size)
        assertEquals(0, f.engine.shutdowns)
    }

    @Test fun deniedFocusDoesNotConsumeCurrentCueAndFreshFixRegeneratesText() = rejectedCueRecovers(focusDenied = true)

    @Test fun rejectedOutputDoesNotConsumeCurrentCueAndSuccessfulDeliveryDeduplicates() = rejectedCueRecovers(focusDenied = false)

    private fun rejectedCueRecovers(focusDenied: Boolean) {
        val f = Fixture()
        f.ready()
        val policy = NavigationCuePolicy(f.clock::nowMillis)
        val route = cueRoute()
        val fix = NavigationFix(WeatherCoordinate(0.0, .003), 10_000, 5f, 10f, 90f)
        val progress = NavigationProgress(30.0, 70.0, 700.0, 0.0, 0, 150.0)
        val first = policy.offer(route, progress, fix)!!
        f.focus.allow = !focusDenied
        f.engine.accept = focusDenied
        f.subject.speakTurn(first)
        assertEquals(if (focusDenied) SpeechFailure.AUDIO_FOCUS else SpeechFailure.OUTPUT,
            f.subject.status.value.requestFailure?.reason)
        f.focus.allow = true
        f.engine.accept = true
        f.clock.advance(1_000)
        val current = policy.offer(route, progress.copy(nextManeuverDistanceMeters = 100.0), fix.copy(recordedAt = 11_000))
        assertNotNull("Rejected delivery must not consume a still-current eligible maneuver", current)
        assertNotEquals("Recovery must regenerate current distance rather than replay old text", first.text, current!!.text)
        f.subject.speakTurn(current)
        assertEquals(current.text, f.engine.activeOutput?.first)
        f.engine.finish(f.engine.spoken.last().second)
        assertNull(policy.offer(route, progress, fix.copy(recordedAt = 12_000)))
    }

    private fun cueRoute() = PlannedRoute("cue-route",
        listOf(RouteStop("A", WeatherCoordinate(0.0, 0.0)), RouteStop("B", WeatherCoordinate(0.0, .01))),
        listOf(RouteVertex(WeatherCoordinate(0.0, 0.0), 0.0), RouteVertex(WeatherCoordinate(0.0, .01), 100.0)),
        1112.0, 100.0, 1,
        maneuvers = listOf(RouteManeuver(10, "", "", emptyList(), 0, 1, 0.0, 100.0)))

    @Test fun missingTerminalCallbackExpiresAtThirtySecondsAndLateEventsCannotStealReplacement() {
        val f = Fixture()
        f.ready()
        val receipts = mutableListOf<CueDelivery>()
        f.subject.speakTurn(NavigationCueRequest("No callback") { receipts += it })
        val oldId = f.engine.spoken.single().second
        val oldFocus = f.focus.requests.single()
        val oldTimer = f.clock.tasks.last().task
        f.clock.advance(29_999)
        assertEquals(SpeechTestResult.BUSY, f.subject.testVoice())
        assertEquals(0, oldFocus.releases)
        f.clock.advance(1)
        assertEquals(1, oldFocus.releases)
        assertNull(f.engine.activeOutput)
        assertEquals(1, f.engine.stops)
        assertEquals(listOf(CueDelivery.EXPIRED), receipts)
        assertEquals(SpeechFailure.OUTPUT, f.subject.status.value.requestFailure?.reason)
        assertEquals(EngineReadiness.READY, f.subject.status.value.readiness)
        assertEquals(SpeechTestResult.ACCEPTED, f.subject.testVoice())
        val replacement = f.engine.activeOutput
        f.engine.finish(oldId)
        oldFocus.lost()
        oldTimer()
        assertEquals(replacement, f.engine.activeOutput)
        assertEquals(0, f.focus.requests.last().releases)
        assertEquals(listOf(CueDelivery.EXPIRED), receipts)
    }

    @Test fun delayedWatchdogIsReconciledBeforeNewRequestsAndBeforeLateCompletion() {
        for (complete in listOf(false, true)) {
            val f = Fixture()
            f.ready()
            val receipts = mutableListOf<CueDelivery>()
            f.subject.speakTurn(NavigationCueRequest("Stuck") { receipts += it })
            val id = f.engine.spoken.single().second
            f.clock.now = 30_000 // The scheduled Handler runnable has not executed.
            if (complete) f.engine.finish(id) else assertEquals(SpeechTestResult.ACCEPTED, f.subject.testVoice())
            assertEquals(listOf(CueDelivery.EXPIRED), receipts)
            assertEquals(1, f.focus.requests.first().releases)
            assertEquals(EngineReadiness.READY, f.subject.status.value.readiness)
        }
    }

    @Test fun preemptionAndCancellationSettlePendingAndActiveReceiptsExactlyOnce() {
        val f = Fixture()
        val first = mutableListOf<CueDelivery>()
        val second = mutableListOf<CueDelivery>()
        f.subject.speakTurn(NavigationCueRequest("Pending old") { first += it })
        f.subject.speakTurn(NavigationCueRequest("Pending new") { second += it })
        assertEquals(listOf(CueDelivery.CANCELLED), first)
        f.engine.ready(EngineReadiness.READY)
        val id = f.engine.spoken.single().second
        f.subject.cancelTurns()
        f.engine.finish(id, error = true)
        f.clock.advance(30_000)
        assertEquals(listOf(CueDelivery.CANCELLED), second)
        assertEquals(1, f.focus.requests.single().releases)
        assertEquals(1, f.engine.stops)
    }

    @Test fun retryQueueCannotRenewTheFirstOfferDeadline() {
        val f = Fixture()
        f.ready()
        val policy = NavigationCuePolicy(f.clock::nowMillis)
        val fix = NavigationFix(WeatherCoordinate(0.0, .003), 10_000, 5f, 10f, 90f)
        val progress = NavigationProgress(30.0, 70.0, 700.0, 0.0, 0, 150.0)
        f.focus.allow = false
        f.subject.speakTurn(policy.offer(cueRoute(), progress, fix)!!)
        f.clock.advance(9_000)
        f.subject.retry()
        f.clock.now = 14_000
        val current = policy.offer(cueRoute(), progress, fix)!!
        assertEquals(15_000L, current.expiresAtMillis)
        f.focus.allow = true
        f.subject.speakTurn(current)
        f.clock.now = 15_000
        f.engine.ready(EngineReadiness.READY)
        assertTrue(f.engine.spoken.isEmpty())
        assertTrue(f.focus.requests.isEmpty())
        assertFalse(current.isCurrent())
        assertNull(policy.offer(cueRoute(), progress, fix))
    }

    @Test fun ownerInvalidationWhileInitializingDropsCueBeforeFocusAcquisition() {
        val f = Fixture()
        var current = true
        val receipts = mutableListOf<CueDelivery>()
        f.subject.speakTurn(NavigationCueRequest("Old owner", stillCurrent = { current }) { receipts += it })
        current = false
        f.engine.ready(EngineReadiness.READY)
        assertTrue(f.focus.requests.isEmpty())
        assertTrue(f.engine.spoken.isEmpty())
        assertEquals(listOf(CueDelivery.CANCELLED), receipts)
    }

    @Test fun engineFailureRejectsReceiptAndRetryIsSilentUntilFreshOffer() {
        val f = Fixture()
        val receipts = mutableListOf<CueDelivery>()
        f.subject.speakTurn(NavigationCueRequest("Old pending") { receipts += it })
        f.engine.ready(EngineReadiness.LANGUAGE_MISSING)
        assertEquals(listOf(CueDelivery.REJECTED), receipts)
        f.subject.retry()
        f.engine.ready(EngineReadiness.READY)
        assertTrue(f.engine.spoken.isEmpty())
        f.subject.speakTurn("Fresh current offer")
        assertEquals(listOf("Fresh current offer"), f.engine.spoken.map { it.first })
    }

    @Test fun synchronousCompletionReleasesOnceAndCancelsOutputWatchdog() {
        val f = Fixture()
        f.ready()
        f.engine.completeInline = true
        val receipts = mutableListOf<CueDelivery>()
        f.subject.speakTurn(NavigationCueRequest("Immediate completion") { receipts += it })
        assertEquals(listOf(CueDelivery.COMPLETED), receipts)
        assertEquals(1, f.focus.requests.single().releases)
        f.clock.advance(30_000)
        assertNull(f.subject.status.value.requestFailure)
        assertEquals(listOf(CueDelivery.COMPLETED), receipts)
    }

    private fun localVoice(name: String = "local") = SpeechVoice(name, "tr", "TR", false, true)
    private fun networkVoice() = SpeechVoice("network", "tr", "TR", true, true)

    @Test fun preparedModeSelectsInstalledLocalVoiceAndDefaultRestoresValidPriorVoice() {
        val f = Fixture()
        f.subject.prepareGuidance(GuidanceVoiceMode.PREPARED_LOCAL)
        val local = localVoice()
        val network = networkVoice()
        f.engine.catalog = SpeechVoiceCatalog(listOf(network, local), network)
        f.engine.ready(EngineReadiness.READY)
        assertEquals(LocalVoiceCapability.AVAILABLE, f.subject.status.value.localVoice)
        assertEquals(listOf("local"), f.engine.selections)
        f.subject.speakTurn("Local request")
        assertEquals(listOf("Local request"), f.engine.spoken.map { it.first })
        f.subject.prepareGuidance(GuidanceVoiceMode.DEFAULT)
        assertEquals(listOf("local", "network"), f.engine.selections)
        assertEquals("network", f.engine.catalog?.current?.name)
        assertEquals(LocalVoiceCapability.NETWORK_REQUIRED, f.subject.status.value.localVoice)
        assertEquals(1, f.engines.size)
        assertEquals(0, f.engine.shutdowns)
    }

    @Test fun preparedModePreservesAlreadySelectedLocalTurkishVoice() {
        val f = Fixture()
        f.subject.prepareGuidance(GuidanceVoiceMode.PREPARED_LOCAL)
        val selected = localVoice("user-choice")
        f.engine.catalog = SpeechVoiceCatalog(listOf(localVoice(), selected), selected)
        f.engine.ready(EngineReadiness.READY)
        assertEquals(LocalVoiceCapability.AVAILABLE, f.subject.status.value.localVoice)
        assertTrue(f.engine.selections.isEmpty())
        assertEquals("user-choice", f.engine.catalog?.current?.name)
    }

    @Test fun preparedNetworkOnlyEngineRemainsReadyButCannotDispatchOrClaimLocalVoice() {
        val f = Fixture()
        f.subject.prepareGuidance(GuidanceVoiceMode.PREPARED_LOCAL)
        val network = networkVoice()
        f.engine.catalog = SpeechVoiceCatalog(listOf(network), network)
        f.engine.ready(EngineReadiness.READY)
        assertEquals(EngineReadiness.READY, f.subject.status.value.readiness)
        assertEquals(LocalVoiceCapability.NETWORK_REQUIRED, f.subject.status.value.localVoice)
        f.subject.speakTurn("Must not use network")
        assertEquals(SpeechTestResult.UNAVAILABLE, f.subject.testVoice())
        assertTrue(f.engine.spoken.isEmpty())
        assertTrue(f.focus.requests.isEmpty())
        f.subject.prepareGuidance(GuidanceVoiceMode.DEFAULT)
        f.subject.speakTurn("Explicit normal session")
        assertEquals(1, f.engine.spoken.size)
        assertTrue(f.engine.selections.isEmpty())
    }

    @Test fun preparedMissingAndUnknownCatalogsStayDistinctAndNeverDispatch() {
        for ((catalog, expected) in listOf(
            SpeechVoiceCatalog(emptyList(), null) to LocalVoiceCapability.MISSING,
            null to LocalVoiceCapability.UNKNOWN,
        )) {
            val f = Fixture()
            f.subject.prepareGuidance(GuidanceVoiceMode.PREPARED_LOCAL)
            f.engine.catalog = catalog
            f.engine.ready(EngineReadiness.READY)
            assertEquals(expected, f.subject.status.value.localVoice)
            f.subject.speakTurn("Unavailable local voice")
            assertTrue(f.engine.spoken.isEmpty())
            assertTrue(f.engine.selections.isEmpty())
            assertTrue(f.focus.requests.isEmpty())
        }
    }

    @Test fun notInstalledUnknownAndOtherLanguageVoicesAreNeverSelected() {
        val f = Fixture()
        f.subject.prepareGuidance(GuidanceVoiceMode.PREPARED_LOCAL)
        f.engine.catalog = SpeechVoiceCatalog(listOf(
            localVoice().copy(name = "needs-download", installed = false),
            localVoice().copy(name = "unknown-installation", installed = null),
            localVoice().copy(name = "unknown-network", requiresNetwork = null),
            localVoice().copy(name = "different-language", language = "en"),
        ), null)
        f.engine.ready(EngineReadiness.READY)
        assertEquals(LocalVoiceCapability.UNKNOWN, f.subject.status.value.localVoice)
        f.subject.speakTurn("Do not download")
        assertTrue(f.engine.selections.isEmpty())
        assertTrue(f.engine.spoken.isEmpty())
    }

    @Test fun selectionRequiresReadbackAndCannotClaimLocalFromSetVoiceSuccessAlone() {
        val f = Fixture()
        f.subject.prepareGuidance(GuidanceVoiceMode.PREPARED_LOCAL)
        f.engine.catalog = SpeechVoiceCatalog(listOf(localVoice(), networkVoice()), networkVoice())
        f.engine.applySelection = false
        f.engine.ready(EngineReadiness.READY)
        assertEquals(LocalVoiceCapability.SELECTION_FAILED, f.subject.status.value.localVoice)
        f.subject.speakTurn("Wrong readback")
        assertTrue(f.engine.spoken.isEmpty())
        assertTrue(f.focus.requests.isEmpty())
    }

    @Test fun lateInitializationCannotPublishObsoleteLocalCapabilityAfterRetry() {
        val f = Fixture()
        f.subject.prepareGuidance(GuidanceVoiceMode.PREPARED_LOCAL)
        val old = f.engine
        old.catalog = SpeechVoiceCatalog(listOf(localVoice()), localVoice())
        f.subject.retry()
        f.engine.catalog = SpeechVoiceCatalog(listOf(networkVoice()), networkVoice())
        old.ready(EngineReadiness.READY)
        assertEquals(EngineReadiness.INITIALIZING, f.subject.status.value.readiness)
        assertEquals(LocalVoiceCapability.UNKNOWN, f.subject.status.value.localVoice)
        f.engine.ready(EngineReadiness.READY)
        assertEquals(LocalVoiceCapability.NETWORK_REQUIRED, f.subject.status.value.localVoice)
        assertTrue(old.selections.isEmpty())
    }

    @Test fun modeChangedDuringInitializationUsesOnlyLatestCommittedPolicy() {
        val f = Fixture()
        f.subject.prepareGuidance(GuidanceVoiceMode.PREPARED_LOCAL)
        f.engine.catalog = SpeechVoiceCatalog(listOf(localVoice(), networkVoice()), networkVoice())
        f.subject.prepareGuidance(GuidanceVoiceMode.DEFAULT)
        f.engine.ready(EngineReadiness.READY)
        assertEquals(GuidanceVoiceMode.DEFAULT, f.subject.status.value.voiceMode)
        assertEquals(LocalVoiceCapability.NETWORK_REQUIRED, f.subject.status.value.localVoice)
        assertTrue(f.engine.selections.isEmpty())
    }

    @Test fun lateInitializationCannotBypassTheTenSecondMonotonicDeadline() {
        val f = Fixture()
        f.subject.speakTurn("Late initialization")
        f.clock.now = 10_000
        f.engine.ready(EngineReadiness.READY)
        assertEquals(EngineReadiness.INIT_TIMEOUT, f.subject.status.value.readiness)
        assertEquals(1, f.engine.shutdowns)
        assertTrue(f.engine.spoken.isEmpty())
        assertTrue(f.focus.requests.isEmpty())
    }

    @Test fun activeOutputBudgetStartsAtDispatchSeparatelyFromPendingExpiry() {
        val f = Fixture()
        f.subject.speakTurn("Queued before initialization")
        f.clock.now = 9_000
        f.engine.ready(EngineReadiness.READY)
        f.clock.advance(29_999)
        assertEquals(SpeechTestResult.BUSY, f.subject.testVoice())
        assertEquals(0, f.focus.requests.single().releases)
        f.clock.advance(1)
        assertEquals(1, f.focus.requests.single().releases)
        assertNull(f.engine.activeOutput)
        assertEquals(EngineReadiness.READY, f.subject.status.value.readiness)
    }

    @Test fun preparedDispatchRechecksActualVoiceInsteadOfTrustingEarlierReadiness() {
        val f = Fixture()
        f.subject.prepareGuidance(GuidanceVoiceMode.PREPARED_LOCAL)
        f.engine.catalog = SpeechVoiceCatalog(listOf(localVoice()), localVoice())
        f.engine.ready(EngineReadiness.READY)
        assertEquals(LocalVoiceCapability.AVAILABLE, f.subject.status.value.localVoice)
        f.engine.catalog = SpeechVoiceCatalog(listOf(networkVoice()), networkVoice())
        f.subject.speakTurn("Capability changed")
        assertEquals(LocalVoiceCapability.NETWORK_REQUIRED, f.subject.status.value.localVoice)
        assertTrue(f.engine.spoken.isEmpty())
        assertTrue(f.focus.requests.isEmpty())
    }

    @Test fun delayedInitDeadlineIsReconciledBeforeReturningBusyForExpiredTurn() {
        val f = Fixture()
        f.subject.speakTurn("Pending")
        f.clock.now = 10_000
        assertEquals(SpeechTestResult.UNAVAILABLE, f.subject.testVoice())
        assertEquals(EngineReadiness.INIT_TIMEOUT, f.subject.status.value.readiness)
        assertTrue(f.focus.requests.isEmpty())
    }

    @Test fun expiredReceiptIsExpiredEvenWhenItsOwnerIsAlsoObsolete() {
        val f = Fixture()
        f.ready()
        val results = mutableListOf<CueDelivery>()
        val request = NavigationCueRequest("Expired", expiresAtMillis = 0, stillCurrent = { false }) { results += it }
        f.subject.speakTurn(request)
        assertEquals(listOf(CueDelivery.EXPIRED), results)
        assertTrue(f.engine.spoken.isEmpty())
        assertTrue(f.focus.requests.isEmpty())
    }

    @Test fun guidanceEndDoesNotInitializeUncheckedEngineOrRetryFailedEngine() {
        val unchecked = Fixture()
        unchecked.subject.endGuidanceVoice()
        unchecked.subject.endGuidanceVoice()
        assertEquals(0, unchecked.factoryCalls)
        assertEquals(EngineReadiness.UNCHECKED, unchecked.subject.status.value.readiness)
        val failed = Fixture(createFailure = true)
        failed.subject.prepareGuidance(GuidanceVoiceMode.PREPARED_LOCAL)
        val oldReadiness = failed.subject.status.value.readiness
        failed.subject.endGuidanceVoice()
        assertEquals(1, failed.factoryCalls)
        assertEquals(oldReadiness, failed.subject.status.value.readiness)
        assertEquals(GuidanceVoiceMode.DEFAULT, failed.subject.status.value.voiceMode)
    }

    @Test fun idlePreparedGuidanceEndRestoresVerifiedDefaultSelectionWithoutReinitialization() {
        val f = preparedFixture()
        f.subject.endGuidanceVoice()
        assertEquals(GuidanceVoiceMode.DEFAULT, f.subject.status.value.voiceMode)
        assertEquals("network", f.engine.catalog?.current?.name)
        assertEquals(listOf("local", "network"), f.engine.selections)
        assertEquals(1, f.factoryCalls)
        assertEquals(0, f.engine.shutdowns)
        assertTrue(f.engine.spoken.isEmpty())
        f.subject.endGuidanceVoice()
        assertEquals(listOf("local", "network"), f.engine.selections)
    }

    @Test fun guidanceEndPreservesIndependentWeatherAndWeatherTestUntilIdleDispatch() {
        for (manual in listOf(false, true)) {
            val f = preparedFixture()
            if (manual) assertEquals(SpeechTestResult.ACCEPTED, f.subject.testVoice(SpeechChannel.WEATHER))
            else f.subject.speakWeather("Independent weather")
            val output = f.engine.activeOutput
            val lease = f.focus.requests.single()
            f.subject.endGuidanceVoice()
            assertEquals(GuidanceVoiceMode.DEFAULT, f.subject.status.value.voiceMode)
            assertEquals(output, f.engine.activeOutput)
            assertEquals(0, f.engine.stops)
            assertEquals(0, lease.releases)
            assertEquals(listOf("local"), f.engine.selections)
            f.engine.finish(output!!.second)
            assertEquals(1, lease.releases)
            f.subject.speakWeather("Next independent weather")
            assertEquals(listOf("local", "network"), f.engine.selections)
            assertEquals("Next independent weather", f.engine.activeOutput?.first)
            assertEquals(1, f.factoryCalls)
        }
    }

    @Test fun pendingWeatherInitializationSurvivesGuidanceEndWithoutLocalPolicyTakeover() {
        for (manual in listOf(false, true)) {
            val f = Fixture()
            f.subject.prepareGuidance(GuidanceVoiceMode.PREPARED_LOCAL)
            f.engine.catalog = SpeechVoiceCatalog(listOf(localVoice(), networkVoice()), networkVoice())
            if (manual) f.subject.testVoice(SpeechChannel.WEATHER) else f.subject.speakWeather("Pending weather")
            f.subject.endGuidanceVoice()
            assertEquals(GuidanceVoiceMode.DEFAULT, f.subject.status.value.voiceMode)
            assertEquals(EngineReadiness.INITIALIZING, f.subject.status.value.readiness)
            assertEquals(1, f.factoryCalls)
            f.engine.ready(EngineReadiness.READY)
            assertEquals(1, f.engine.spoken.size)
            assertTrue(f.engine.selections.isEmpty())
            assertEquals("network", f.engine.catalog?.current?.name)
        }
    }

    @Test fun guidanceEndCancelsOnlyTurnOwnerOnceAndOldCallbacksCannotStopWeather() {
        val f = preparedFixture()
        val results = mutableListOf<CueDelivery>()
        f.subject.speakTurn(NavigationCueRequest("Ending turn") { results += it })
        val oldId = f.engine.spoken.last().second
        val oldLease = f.focus.requests.last()
        val oldDeadline = f.clock.tasks.last().task
        f.subject.endGuidanceVoice()
        f.subject.endGuidanceVoice()
        assertEquals(listOf(CueDelivery.CANCELLED), results)
        assertEquals(1, oldLease.releases)
        assertEquals(GuidanceVoiceMode.DEFAULT, f.subject.status.value.voiceMode)
        f.subject.speakWeather("Surviving weather owner")
        val weather = f.engine.activeOutput
        oldLease.lost()
        oldDeadline()
        f.engine.finish(oldId, error = true)
        assertEquals(weather, f.engine.activeOutput)
        assertEquals(0, f.focus.requests.last().releases)
        assertEquals(listOf(CueDelivery.CANCELLED), results)
    }

    @Test fun guidanceEndDropsPendingTurnButRetainsExistingInitializationWithoutReplay() {
        val f = Fixture()
        f.subject.prepareGuidance(GuidanceVoiceMode.PREPARED_LOCAL)
        val results = mutableListOf<CueDelivery>()
        f.subject.speakTurn(NavigationCueRequest("Ending queued turn") { results += it })
        f.subject.endGuidanceVoice()
        assertEquals(listOf(CueDelivery.CANCELLED), results)
        assertEquals(GuidanceVoiceMode.DEFAULT, f.subject.status.value.voiceMode)
        assertEquals(1, f.factoryCalls)
        f.engine.ready(EngineReadiness.READY)
        assertTrue(f.engine.spoken.isEmpty())
        assertTrue(f.focus.requests.isEmpty())
    }

    @Test fun guidanceEndCannotSelectUninstalledOrUnknownPriorDefaultVoice() {
        for (installed in listOf(false, null)) {
            val f = preparedFixture()
            f.engine.catalog = SpeechVoiceCatalog(listOf(localVoice(), networkVoice().copy(installed = installed)), localVoice())
            f.subject.endGuidanceVoice()
            assertEquals(GuidanceVoiceMode.DEFAULT, f.subject.status.value.voiceMode)
            assertEquals(listOf("local"), f.engine.selections)
            assertEquals("local", f.engine.catalog?.current?.name)
            assertEquals(1, f.factoryCalls)
        }
    }

    @Test fun guidanceEndPreservesIndependentWeatherFailureAndCancelsNavigationOwnedTest() {
        val weather = preparedFixture()
        weather.engine.accept = false
        weather.subject.testVoice(SpeechChannel.WEATHER)
        val failure = weather.subject.status.value.requestFailure
        assertEquals(SpeechChannel.TEST, failure?.channel)
        weather.subject.endGuidanceVoice()
        assertEquals(failure, weather.subject.status.value.requestFailure)
        assertEquals(GuidanceVoiceMode.DEFAULT, weather.subject.status.value.voiceMode)
        val turn = preparedFixture()
        turn.subject.testVoice(SpeechChannel.TURN)
        turn.subject.endGuidanceVoice()
        assertNull(turn.engine.activeOutput)
        assertEquals(1, turn.focus.requests.single().releases)
        assertEquals(GuidanceVoiceMode.DEFAULT, turn.subject.status.value.voiceMode)
    }

    private fun preparedFixture(): Fixture {
        val f = Fixture()
        f.subject.prepareGuidance(GuidanceVoiceMode.PREPARED_LOCAL)
        f.engine.catalog = SpeechVoiceCatalog(listOf(localVoice(), networkVoice()), networkVoice())
        f.engine.ready(EngineReadiness.READY)
        return f
    }

    private class Fixture(private val createFailure: Boolean = false, private val initializeImmediately: Boolean = false) {
        val clock = Clock()
        val focus = Focus()
        val engines = mutableListOf<Engine>()
        var factoryCalls = 0
        val engine get() = engines.last()
        val subject = NavigationSpeechController({
            factoryCalls++
            if (createFailure) error("No engine")
            Engine(initializeImmediately).also { engines += it }
        }, focus, clock)
        fun ready() { subject.prepareGuidance(); engine.ready(EngineReadiness.READY) }
    }
    private class Engine(private val initializeImmediately: Boolean) : SpeechEngine {
        lateinit var ready: (EngineReadiness) -> Unit
        private lateinit var finished: (String, Boolean) -> Unit
        val spoken = mutableListOf<Pair<String, String>>()
        var activeOutput: Pair<String, String>? = null
        var accept = true
        var shutdowns = 0
        var stops = 0
        var completeInline = false
        var catalog: SpeechVoiceCatalog? = null
        val selections = mutableListOf<String>()
        var applySelection = true
        override fun voiceCatalog() = catalog
        override fun selectVoice(name: String): Boolean {
            selections += name
            val candidate = catalog?.voices?.firstOrNull { it.name == name } ?: return false
            if (applySelection) catalog = catalog?.copy(current = candidate)
            return true
        }
        override fun initialize(onReady: (EngineReadiness) -> Unit, onFinished: (String, Boolean) -> Unit) {
            ready = onReady; finished = onFinished
            if (initializeImmediately) onReady(EngineReadiness.READY)
        }
        override fun speak(text: String, id: String): Boolean {
            spoken += text to id
            if (accept) activeOutput = text to id
            if (accept && completeInline) finish(id)
            return accept
        }
        override fun stop() { stops++; activeOutput = null }
        override fun shutdown() { shutdowns++ }
        fun finish(id: String, error: Boolean = false) {
            if (activeOutput?.second == id) activeOutput = null
            finished(id, error)
        }
    }
    private class Focus : SpeechAudioFocus {
        class Request(val lost: () -> Unit) : SpeechFocusLease {
            var releases = 0
            override fun release() { releases++ }
        }
        var allow = true
        var loseDuringAcquire = false
        val requests = mutableListOf<Request>()
        override fun acquire(onLost: () -> Unit): SpeechFocusLease? =
            if (!allow) null else Request(onLost).also { requests += it; if (loseDuringAcquire) onLost() }
    }
    private class Clock : SpeechScheduler {
        data class Task(val due: Long, val task: () -> Unit, var cancelled: Boolean = false)
        var now = 0L
        val tasks = mutableListOf<Task>()
        override fun nowMillis() = now
        override fun after(delayMillis: Long, task: () -> Unit): () -> Unit {
            val entry = Task(now + delayMillis, task)
            tasks += entry
            return { entry.cancelled = true }
        }
        fun advance(delta: Long) {
            now += delta
            tasks.filter { !it.cancelled && it.due <= now }.forEach { it.cancelled = true; it.task() }
        }
    }
}
