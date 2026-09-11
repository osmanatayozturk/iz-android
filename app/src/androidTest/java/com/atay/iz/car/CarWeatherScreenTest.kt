package com.atay.iz.car

import android.util.Log
import androidx.car.app.model.MessageTemplate
import androidx.car.app.OnDoneCallback
import androidx.car.app.testing.ScreenController
import androidx.lifecycle.Lifecycle
import java.util.concurrent.atomic.AtomicInteger
import androidx.car.app.testing.TestCarContext
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.atay.iz.IzApplication
import com.atay.iz.data.Transport
import com.atay.iz.weather.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Regression: injects only display state; never starts recording or HTTP. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 28)
class CarWeatherScreenTest {
    @Test fun readyRoutedForecastMustNotClaimForecastWasNeverReceived() {
        val app = ApplicationProvider.getApplicationContext<IzApplication>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val now = System.currentTimeMillis()
            val origin = WeatherCoordinate(41.0, 29.0)
            val destination = WeatherCoordinate(41.001, 29.001)
            val route = PlannedRoute(
                "qa-car-ready-weather", listOf(RouteStop("Origin", origin), RouteStop("Destination", destination)),
                listOf(RouteVertex(origin, 0.0), RouteVertex(destination, 60.0)),
                140.0, 60.0, now, transport = Transport.CAR,
            )
            val hour = now / 3_600_000L * 3_600_000L
            val reading = WeatherReading(20.0, 10.0, 0.0, 5.0, 8.0, 0.0)
            val forecasts = listOf(origin, destination).map { coordinate ->
                LocationForecast(coordinate, (-1..2).map { offset ->
                    WeatherHour(hour + offset * 3_600_000L, reading)
                }, now)
            }
            val assessment = WeatherEngine.assess(route, now, forecasts, WeatherThresholds())
            assertTrue("Fixture must contain a complete, current route forecast", assessment.complete)
            assertEquals(20.0, assessment.maxTemperatureC ?: Double.NaN, 0.0)
            assertEquals(now, assessment.fetchedAt)
            val ready = RideWeatherLiveState(
                status = RideWeatherStatus.READY, journeyId = "qa-car-weather-display",
                route = route, assessment = assessment, remainingMeters = 140.0,
                arrivalAt = now + 60_000L, gpsStale = false, message = null,
            )

            val manager = app.weatherManager
            // Preserve production API: reflection is confined to this instrumentation fixture.
            val field = RideWeatherManager::class.java.getDeclaredField("mutableState")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val state = field.get(manager) as MutableStateFlow<RideWeatherLiveState>
            val previous = state.value
            try {
                state.value = ready
                assertEquals("Injected fixture must be visible to the screen", ready, manager.state.value)
                val context = TestCarContext.createCarContext(app)
                val screen = CarWeatherScreen(context)
                val controller = ScreenController(screen).moveToState(Lifecycle.State.CREATED)
                val template = try { screen.onGetTemplate() as MessageTemplate }
                    finally { controller.moveToState(Lifecycle.State.DESTROYED) }
                val displayed = template.message.toString()
                assertTrue(displayed.contains("20 °C"))
                assertTrue(displayed.contains("Yağış %10"))
                assertTrue(displayed.contains("Rüzgâr 5 km/sa"))
                val diagnostic = "Android Auto READY weather: complete=${assessment.complete}, " +
                    "temperature=20.0, messageField=null, displayed='$displayed'"
                Log.i("IzQaRepro", diagnostic)
                assertFalse(
                    "$diagnostic. A successfully received forecast must not display the never-received fallback.",
                    displayed.contains("Tahmin henüz alınmadı"),
                )
            } finally {
                state.value = previous
            }
        }
    }

    @Test fun refreshActionRequestsForecastInsteadOfOnlyRedrawingThePage() {
        val app = ApplicationProvider.getApplicationContext<IzApplication>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val refreshes = AtomicInteger()
        var controller: ScreenController? = null
        try {
            instrumentation.runOnMainSync {
                val context = TestCarContext.createCarContext(app)
                val screen = CarWeatherScreen(context) { refreshes.incrementAndGet(); Unit }
                controller = ScreenController(screen).moveToState(Lifecycle.State.CREATED)
                val template = screen.onGetTemplate() as MessageTemplate
                val refresh = template.actions.single { it.title.toString() == "Yenile" }
                // Exercise the public host click delegate, not a private callback implementation.
                requireNotNull(refresh.onClickDelegate).sendClick(object : OnDoneCallback {})
            }
            instrumentation.waitForIdleSync()
            assertEquals("The host refresh action must request one forecast refresh", 1, refreshes.get())
        } finally {
            instrumentation.runOnMainSync {
                controller?.moveToState(Lifecycle.State.DESTROYED)
            }
        }
    }
}

