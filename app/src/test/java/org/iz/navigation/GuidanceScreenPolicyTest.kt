package org.iz.navigation

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GuidanceScreenPolicyTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private class ActivityLifecycle : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this)
        fun moveTo(state: Lifecycle.State) { lifecycle.currentState = state }
    }

    @Test fun inactiveGuidanceNeverKeepsTheForegroundScreenOn() = runTest {
        val owner = ActivityLifecycle()
        val guidance = MutableStateFlow(false)
        var screenOn = false
        backgroundScope.launch { owner.lifecycle.keepScreenOnWhileGuiding(guidance) { screenOn = it } }
        owner.moveTo(Lifecycle.State.RESUMED)
        runCurrent()
        assertFalse(screenOn)
    }

    @Test fun startingAndEndingGuidanceUpdatesTheForegroundWindow() = runTest {
        val owner = ActivityLifecycle()
        val guidance = MutableStateFlow(false)
        var screenOn = false
        backgroundScope.launch { owner.lifecycle.keepScreenOnWhileGuiding(guidance) { screenOn = it } }
        owner.moveTo(Lifecycle.State.RESUMED)
        runCurrent()
        guidance.value = true
        runCurrent()
        assertTrue(screenOn)
        guidance.value = false
        runCurrent()
        assertFalse(screenOn)
    }

    @Test fun guidanceCannotKeepTheScreenOnBeforeResumeOrAfterPause() = runTest {
        val owner = ActivityLifecycle()
        val guidance = MutableStateFlow(true)
        var screenOn = false
        backgroundScope.launch { owner.lifecycle.keepScreenOnWhileGuiding(guidance) { screenOn = it } }
        owner.moveTo(Lifecycle.State.STARTED)
        runCurrent()
        assertFalse(screenOn)
        owner.moveTo(Lifecycle.State.RESUMED)
        runCurrent()
        assertTrue(screenOn)
        owner.moveTo(Lifecycle.State.STARTED)
        runCurrent()
        assertFalse(screenOn)
    }

    @Test fun returningToTheForegroundRestoresOnlyStillActiveGuidance() = runTest {
        val owner = ActivityLifecycle()
        val guidance = MutableStateFlow(true)
        var screenOn = false
        backgroundScope.launch { owner.lifecycle.keepScreenOnWhileGuiding(guidance) { screenOn = it } }
        owner.moveTo(Lifecycle.State.RESUMED)
        runCurrent()
        assertTrue(screenOn)
        owner.moveTo(Lifecycle.State.CREATED)
        runCurrent()
        assertFalse(screenOn)
        owner.moveTo(Lifecycle.State.RESUMED)
        runCurrent()
        assertTrue(screenOn)
        owner.moveTo(Lifecycle.State.CREATED)
        runCurrent()
        guidance.value = false
        runCurrent()
        assertFalse(screenOn)
        owner.moveTo(Lifecycle.State.RESUMED)
        runCurrent()
        assertFalse(screenOn)
    }

    @Test fun startingGuidanceInTheBackgroundWaitsUntilResume() = runTest {
        val owner = ActivityLifecycle()
        val guidance = MutableStateFlow(false)
        var screenOn = false
        backgroundScope.launch { owner.lifecycle.keepScreenOnWhileGuiding(guidance) { screenOn = it } }
        owner.moveTo(Lifecycle.State.CREATED)
        runCurrent()
        guidance.value = true
        runCurrent()
        assertFalse(screenOn)
        owner.moveTo(Lifecycle.State.RESUMED)
        runCurrent()
        assertTrue(screenOn)
    }

    @Test fun destroyingAndRecreatingActivityReleasesTheOldWindow() = runTest {
        val oldOwner = ActivityLifecycle()
        val guidance = MutableStateFlow(true)
        var oldScreenOn = false
        backgroundScope.launch { oldOwner.lifecycle.keepScreenOnWhileGuiding(guidance) { oldScreenOn = it } }
        oldOwner.moveTo(Lifecycle.State.RESUMED)
        runCurrent()
        assertTrue(oldScreenOn)
        oldOwner.moveTo(Lifecycle.State.DESTROYED)
        runCurrent()
        assertFalse(oldScreenOn)
        val newOwner = ActivityLifecycle()
        var newScreenOn = false
        backgroundScope.launch { newOwner.lifecycle.keepScreenOnWhileGuiding(guidance) { newScreenOn = it } }
        newOwner.moveTo(Lifecycle.State.CREATED)
        runCurrent()
        assertFalse(newScreenOn)
        newOwner.moveTo(Lifecycle.State.RESUMED)
        runCurrent()
        assertTrue(newScreenOn)
        assertFalse(oldScreenOn)
        guidance.value = false
        runCurrent()
        assertFalse(newScreenOn)
    }

    @Test fun cancellingTheActivityCollectorRestoresNormalTimeout() = runTest {
        val owner = ActivityLifecycle()
        val guidance = MutableStateFlow(true)
        var screenOn = false
        val collection = backgroundScope.launch { owner.lifecycle.keepScreenOnWhileGuiding(guidance) { screenOn = it } }
        owner.moveTo(Lifecycle.State.RESUMED)
        runCurrent()
        assertTrue(screenOn)
        collection.cancel()
        runCurrent()
        assertFalse(screenOn)
    }
}
