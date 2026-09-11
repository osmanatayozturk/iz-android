package com.atay.iz.weather

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit, opt-in external service smoke test using public synthetic coordinates. */
@RunWith(AndroidJUnit4::class)
class WeatherLiveProviderTest {
    @Test fun motorcycleRouteAndHourlyForecastOverAndroidTls() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("weatherLiveProbe") == "true")
        runBlocking {
            withTimeout(180_000) {
                val context = ApplicationProvider.getApplicationContext<Context>()
                val departure = System.currentTimeMillis() + 3_600_000L
                val route = withContext(Dispatchers.Main.immediate) { ValhallaRoutePlanner(context).plan(listOf(
                    RouteStop("Kadıköy test başlangıcı", WeatherCoordinate(40.9906, 29.0233)),
                    RouteStop("Şile test hedefi", WeatherCoordinate(41.1764, 29.6128)),
                ), departure) }
                assertTrue(route.distanceMeters > 40_000.0)
                assertTrue(route.durationSeconds > 1_800.0)
                val samples = WeatherEngine.sampleVertices(route)
                assertTrue(samples.size in 2..48)
                val forecasts = withContext(Dispatchers.Main.immediate) { OpenMeteoWeatherProvider(context).hourly(samples.map { it.coordinate },
                    departure, departure + (route.durationSeconds * 1_000).toLong() + 4 * 3_600_000L) }
                assertEquals(samples.map { it.coordinate }, forecasts.map { it.coordinate })
                val options = WeatherEngine.compare(route, departure, forecasts, WeatherThresholds())
                assertEquals(7, options.size)
                assertTrue("Actual provider must cover selected departure and route arrival times", options.first().complete)
                assertNotNull(options.first().fetchedAt)
                Log.i("IzWeatherLiveProbe",
                    "routeMeters="+route.distanceMeters+" durationSeconds="+route.durationSeconds+
                    " samples="+samples.size+" forecasts="+forecasts.size+
                    " completeCandidates="+options.count { it.complete }+" fetchedAt="+options.first().fetchedAt)
            }
        }
    }
}
