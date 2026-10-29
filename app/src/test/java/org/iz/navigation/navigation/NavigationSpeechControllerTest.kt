package org.iz.navigation.navigation

import org.junit.Assert.*
import org.junit.Test

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
        override fun initialize(onReady: (EngineReadiness) -> Unit, onFinished: (String, Boolean) -> Unit) {
            ready = onReady; finished = onFinished
            if (initializeImmediately) onReady(EngineReadiness.READY)
        }
        override fun speak(text: String, id: String): Boolean {
            spoken += text to id
            if (accept) activeOutput = text to id
            return accept
        }
        override fun stop() { activeOutput = null }
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
