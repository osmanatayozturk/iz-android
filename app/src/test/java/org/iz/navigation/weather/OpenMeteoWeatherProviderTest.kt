package org.iz.navigation.weather

import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OpenMeteoWeatherProviderTest {
    @get:Rule val temporary = TemporaryFolder()
    private val from = 1_704_067_200_000L

    @Test fun multiLocationResponseUsesRequestIndexesKeepsNullEntriesAndActualCoordinates() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("[$forecastObject,null]"))
            val requested = listOf(WeatherCoordinate(41.00001, 29.00001), WeatherCoordinate(41.5, 29.5))

            val result = provider(server, temporary.newFolder("multi")).hourly(requested, from, from + 3_600_000)

            assertEquals(2, result.size)
            assertEquals(requested, result.map { it.coordinate })
            assertEquals(1, result.first().hours.size)
            assertTrue(result.last().hours.isEmpty())
            assertEquals(from, result.first().hours.single().time)
            assertEquals(12.5, result.first().hours.single().reading.temperatureC!!, 0.0)
        }
    }

    @Test fun successfulForecastIsPersistedAndReusedWithoutNetwork() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(forecastObject))
            val directory = temporary.newFolder("cache")
            val coordinate = WeatherCoordinate(41.0, 29.0)
            provider(server, directory).hourly(listOf(coordinate), from, from + 3_600_000)

            val cached = provider(server, directory).hourly(listOf(coordinate), from, from + 3_600_000)

            assertEquals(1, server.requestCount)
            assertEquals(coordinate, cached.single().coordinate)
            assertEquals(from, cached.single().fetchedAt)
        }
    }

    @Test fun oversizedSuccessfulBodyFailsInsteadOfParsingATruncatedForecast() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(" ".repeat(2_100_000)))
            val error = assertThrows(WeatherServiceException::class.java) {
                runBlocking { provider(server, temporary.newFolder("large")).hourly(listOf(WeatherCoordinate(41.0, 29.0)), from, from + 3_600_000) }
            }
            assertTrue(error.message!!.contains("Tahmin"))
        }
    }

    @Test fun stalePersistentForecastIsRefetched() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(forecastObject))
            server.enqueue(MockResponse().setBody(forecastObject.replace("12.5", "13.5")))
            val directory = temporary.newFolder("stale-cache")
            var now = from
            val coordinate = WeatherCoordinate(41.0, 29.0)
            provider(server, directory) { now }.hourly(listOf(coordinate), from, from + 3_600_000)

            now += 15 * 60 * 1_000L + 1L
            val refreshed = provider(server, directory) { now }.hourly(listOf(coordinate), from, from + 3_600_000)

            assertEquals(13.5, refreshed.single().hours.single().reading.temperatureC!!, 0.0)
            assertEquals(2, server.requestCount)
        }
    }

    private fun provider(server: MockWebServer, directory: File, clock: () -> Long = { from }) = OpenMeteoWeatherProvider(
        endpoint = server.url("/forecast").toString(),
        storageDirectory = directory,
        client = OkHttpClient.Builder().retryOnConnectionFailure(false).build(),
        clock = clock,
    )

    private val forecastObject = """
        {"latitude":41.125,"longitude":29.625,"hourly":{
          "time":[1704067200],
          "temperature_2m":[12.5],
          "precipitation_probability":[40],
          "precipitation":[0.1],
          "wind_speed_10m":[9.0],
          "wind_direction_10m":[180],
          "wind_gusts_10m":[15.0]
        }}
    """.trimIndent()
}
