package org.iz.navigation.car

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.iz.navigation.navigation.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CarVoiceObservationTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private class ConsumerLifecycle : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this)
        fun moveTo(state: Lifecycle.State) { lifecycle.currentState = state }
    }

    @Test fun visibleConsumerReceivesSpeechAndMuteWithoutHomeRefreshOrGpsRedraws() = runTest {
        val owner = ConsumerLifecycle()
        val speech = MutableStateFlow(SpeechStatus())
        val navigation = MutableStateFlow(NavigationState())
        val received = mutableListOf<String>()
        observeCarVoiceNotice(backgroundScope, owner.lifecycle, speech, navigation, received::add)
        owner.moveTo(Lifecycle.State.CREATED)
        runCurrent()
        assertTrue(received.isEmpty())
        assertEquals(0, speech.subscriptionCount.value)
        owner.moveTo(Lifecycle.State.STARTED)
        runCurrent()
        assertEquals(listOf("Ses motoru henüz kontrol edilmedi."), received)
        assertEquals(1, speech.subscriptionCount.value)
        assertEquals(1, navigation.subscriptionCount.value)
        speech.value = SpeechStatus(EngineReadiness.READY)
        runCurrent()
        assertTrue(received.last().contains("Sesin duyulduğu henüz doğrulanmadı"))
        val beforeFix = received.size
        navigation.value = navigation.value.copy(gpsStale = false, loading = true)
        runCurrent()
        assertEquals("Unrelated navigation updates must not redraw a voice row", beforeFix, received.size)
        navigation.value = navigation.value.copy(muted = true)
        runCurrent()
        assertEquals("Navigasyon sesi kapalı.", received.last())
        navigation.value = navigation.value.copy(muted = false)
        speech.value = SpeechStatus(EngineReadiness.LANGUAGE_MISSING)
        runCurrent()
        assertEquals("Türkçe ses verisi eksik.", received.last())
        owner.moveTo(Lifecycle.State.DESTROYED)
        runCurrent()
    }

    @Test fun stoppedConsumerUnsubscribesAndResumeUsesOnlyCurrentStateThenDestroyClosesJob() = runTest {
        val owner = ConsumerLifecycle()
        val speech = MutableStateFlow(SpeechStatus(EngineReadiness.READY))
        val navigation = MutableStateFlow(NavigationState())
        val received = mutableListOf<String>()
        val job = observeCarVoiceNotice(backgroundScope, owner.lifecycle, speech, navigation, received::add)
        owner.moveTo(Lifecycle.State.STARTED)
        runCurrent()
        val beforeStop = received.size
        owner.moveTo(Lifecycle.State.CREATED)
        runCurrent()
        assertEquals(0, speech.subscriptionCount.value)
        assertEquals(0, navigation.subscriptionCount.value)
        speech.value = SpeechStatus(EngineReadiness.ENGINE_ERROR)
        navigation.value = navigation.value.copy(muted = true)
        runCurrent()
        assertEquals(beforeStop, received.size)
        owner.moveTo(Lifecycle.State.STARTED)
        runCurrent()
        assertEquals(beforeStop + 1, received.size)
        assertEquals("Navigasyon sesi kapalı.", received.last())
        owner.moveTo(Lifecycle.State.DESTROYED)
        runCurrent()
        assertTrue(job.isCompleted)
        assertEquals(0, speech.subscriptionCount.value)
        assertEquals(0, navigation.subscriptionCount.value)
        speech.value = SpeechStatus(EngineReadiness.INIT_TIMEOUT)
        navigation.value = navigation.value.copy(muted = false)
        runCurrent()
        assertEquals(beforeStop + 1, received.size)
    }
}
