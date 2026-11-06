package org.iz.navigation.integration

import org.iz.navigation.navigation.DriveCameraTarget
import org.iz.navigation.weather.WeatherCoordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveCameraAnimatorTest {
    private val first = DriveCameraTarget(WeatherCoordinate(41.0, 29.0), 17.5, 90.0, 45.0,
        listOf(28.0, 50.0, 28.0, 112.0))
    private val second = first.copy(coordinate = WeatherCoordinate(41.001, 29.001), zoom = 16.5)

    @Test fun oneCompleteTargetIsEasedAndCompletionReleasesOwnership() {
        val native = FakeCamera()
        assertFalse(native.animator.cancel())
        native.animator.ease(first)
        assertSame(first, native.calls.single().target)
        assertEquals(500, native.calls.single().durationMillis)
        native.calls.single().ended()
        assertFalse(native.animator.cancel())
        assertEquals(0, native.cancels)
        assertEquals(1, native.calls.size)
    }

    @Test fun delayedCallbackFromTheSupersededEaseCannotClearTheCurrentEase() {
        val native = FakeCamera()
        native.animator.ease(first)
        native.animator.ease(second)
        assertEquals(1, native.cancels)
        native.calls.first().ended()
        assertTrue(native.animator.cancel())
        assertEquals(2, native.cancels)
        native.calls.last().ended()
        assertFalse(native.animator.cancel())
        assertEquals(2, native.cancels)
        assertEquals(listOf(first, second), native.calls.map { it.target })
    }

    @Test fun nativeCancellationMayCallBackSynchronouslyWithoutRestartingCamera() {
        val native = FakeCamera(endOnCancel = true)
        native.animator.ease(first)
        assertTrue(native.animator.cancel())
        assertFalse(native.animator.cancel())
        assertEquals(1, native.cancels)
        assertEquals(1, native.calls.size)
    }

    @Test fun nativeNoOpCanCompleteBeforeEaseReturns() {
        val native = FakeCamera(endOnStart = true)
        native.animator.ease(first)
        assertFalse(native.animator.cancel())
        assertEquals(0, native.cancels)
        assertEquals(1, native.calls.size)
    }

    private class FakeCamera(endOnStart: Boolean = false, endOnCancel: Boolean = false) {
        data class Call(val target: DriveCameraTarget, val durationMillis: Int, val ended: () -> Unit)

        val calls = mutableListOf<Call>()
        var cancels = 0
            private set
        val animator = DriveCameraAnimator(startEase = { target, durationMillis, ended ->
            calls += Call(target, durationMillis, ended)
            if (endOnStart) ended()
        }, cancelEase = {
            cancels++
            if (endOnCancel) calls.last().ended()
        })
    }
}
