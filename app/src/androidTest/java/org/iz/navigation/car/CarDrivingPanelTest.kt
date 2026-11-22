package org.iz.navigation.car

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.iz.navigation.IzApplication
import org.iz.navigation.data.Journey
import org.iz.navigation.data.JourneyStatus
import org.iz.navigation.data.Transport
import org.iz.navigation.navigation.NavigationFix
import org.iz.navigation.navigation.NavigationState
import org.iz.navigation.speed.RoadSpeedLimit
import org.iz.navigation.speed.RoadSpeedSource
import org.iz.navigation.speed.RoadSpeedState
import org.iz.navigation.weather.WeatherCoordinate
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CarDrivingPanelTest {
    private fun onPanel(test: (CarDrivingPanel) -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val app = ApplicationProvider.getApplicationContext<IzApplication>()
            val panel = CarDrivingPanel(app)
            FrameLayout(app).addView(panel)
            test(panel)
        }
    }

    private fun fresh(): NavigationState {
        val now = System.currentTimeMillis()
        val coordinate = WeatherCoordinate(0.25, -140.0)
        return NavigationState(fix = NavigationFix(coordinate, now, 5f, 10f), gpsStale = false,
            roadSpeed = RoadSpeedState(36.0, RoadSpeedLimit(50.0, RoadSpeedSource.OSM, false,
                now, coordinate, Transport.CAR, "synthetic-road"), true))
    }

    private fun CarDrivingPanel.show(state: NavigationState) = update(state, null, Rect(0, 0, 1600, 1200), 32)
    private fun CarDrivingPanel.speeds() = getChildAt(1) as ViewGroup

    @Test fun staleMissingAndExpiredFixRemoveCompactMeasurementsAndEmptyPanel() = onPanel { panel ->
        val current = fresh()
        for (unusable in listOf(current.copy(gpsStale = true), current.copy(fix = null),
            current.copy(fix = current.fix!!.copy(recordedAt = System.currentTimeMillis() - 6_000)))) {
            panel.show(current)
            assertEquals(View.VISIBLE, panel.speeds().getChildAt(0).visibility)
            assertEquals(View.VISIBLE, panel.speeds().getChildAt(1).visibility)
            panel.show(unusable)
            assertEquals(View.GONE, panel.speeds().getChildAt(0).visibility)
            assertEquals(View.GONE, panel.speeds().getChildAt(1).visibility)
            assertEquals(View.GONE, panel.speeds().visibility)
            assertEquals(View.GONE, panel.visibility)
            assertEquals(0, panel.reservedTop)
        }
        panel.show(fresh())
        assertEquals(View.VISIBLE, panel.visibility)
        assertEquals(View.VISIBLE, panel.speeds().getChildAt(0).visibility)
        assertEquals(View.VISIBLE, panel.speeds().getChildAt(1).visibility)
    }

    @Test fun confirmedRecordingWithoutMeasurementsKeepsOnlyItsIndicator() = onPanel { panel ->
        val state = fresh().copy(recording = true, gpsStale = true,
            journey = Journey(transport = Transport.CAR, status = JourneyStatus.CONFIRMED))
        panel.show(state)
        assertEquals(View.VISIBLE, panel.visibility)
        assertEquals(View.GONE, panel.getChildAt(0).visibility)
        assertEquals(View.GONE, panel.speeds().visibility)
        assertEquals(View.GONE, panel.speeds().getChildAt(0).visibility)
        assertEquals(View.GONE, panel.speeds().getChildAt(1).visibility)
        assertEquals(View.VISIBLE, panel.getChildAt(2).visibility)
        assertTrue(panel.measuredHeight > 0 && panel.reservedTop > 0)
    }

    @Test fun temporaryCandidateWithoutMeasurementsHasNoRecordingIndicatorOrEmptyPanel() = onPanel { panel ->
        panel.show(fresh().copy(recording = true, gpsStale = true,
            journey = Journey(transport = Transport.CAR, status = JourneyStatus.TEMPORARY)))
        assertEquals(View.GONE, panel.getChildAt(2).visibility)
        assertEquals(View.GONE, panel.visibility)
    }

    @Test fun numericLimitCanRemainVisibleWhenOnlyOwnSpeedIsMissing() = onPanel { panel ->
        val state = fresh()
        panel.show(state.copy(fix = state.fix!!.copy(speedMps = null)))
        assertEquals(View.GONE, panel.speeds().getChildAt(0).visibility)
        assertEquals(View.VISIBLE, panel.speeds().getChildAt(1).visibility)
        assertEquals(View.VISIBLE, panel.speeds().visibility)
        assertEquals(View.VISIBLE, panel.visibility)
        val params = panel.speeds().getChildAt(1).layoutParams as android.widget.LinearLayout.LayoutParams
        assertEquals(0, params.leftMargin)
    }

    @Test fun compactLimitRequiresANumericUsableValue() = onPanel { panel ->
        val state = fresh()
        for (value in listOf(null, Double.NaN, Double.POSITIVE_INFINITY, 0.0, -50.0)) {
            panel.show(state.copy(roadSpeed = state.roadSpeed.copy(limit = state.roadSpeed.limit!!.copy(valueKmh = value))))
            assertEquals(View.VISIBLE, panel.speeds().getChildAt(0).visibility)
            assertEquals(View.GONE, panel.speeds().getChildAt(1).visibility)
            assertEquals(View.VISIBLE, panel.visibility)
        }
    }

    @Test fun activeGuidanceRestoresItsExistingUnknownMeasurementLabels() = onPanel { panel ->
        val current = fresh()
        panel.show(current.copy(gpsStale = true))
        panel.show(current.copy(guidance = true, gpsStale = true))
        assertEquals(View.VISIBLE, panel.visibility)
        assertEquals(View.VISIBLE, panel.speeds().visibility)
        assertEquals(View.VISIBLE, panel.speeds().getChildAt(0).visibility)
        assertEquals(View.VISIBLE, panel.speeds().getChildAt(1).visibility)
        assertTrue((panel.speeds().getChildAt(0) as TextView).text.toString().contains("— km/sa"))
        assertTrue((panel.speeds().getChildAt(1) as TextView).text.toString().contains("Hız sınırı"))
    }
}
