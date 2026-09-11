package org.iz.navigation.weather

import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherHttpTest {
    @Test fun cancellationWhileResponseBodyIsBlockedCancelsTheOkHttpCallPromptly() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("x".repeat(100)).throttleBody(1, 1, TimeUnit.SECONDS))
            val client = OkHttpClient.Builder().callTimeout(120, TimeUnit.SECONDS).build()
            val request = Request.Builder().url(server.url("/slow")).build()
            val job = launch(Dispatchers.Default) { executeBounded(client, request, 1_000) }
            withTimeout(2_000) { while (server.requestCount == 0) delay(10) }

            val elapsed = measureTimeMillis { job.cancelAndJoin() }

            assertTrue("Cancellation took ${elapsed}ms", elapsed < 2_000)
            withTimeout(2_000) { while (client.dispatcher.runningCallsCount() != 0) delay(10) }
            assertEquals(0, client.dispatcher.runningCallsCount())
        }
    }

    @Test fun boundedExecutorRejectsADeclaredOversizedResponse() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("123456"))
            val error = org.junit.Assert.assertThrows(java.io.IOException::class.java) {
                runBlocking {
                    executeBounded(
                        OkHttpClient(),
                        Request.Builder().url(server.url("/large")).build(),
                        5,
                    )
                }
            }
            assertTrue(error.message!!.contains("çok büyük"))
        }
    }

    @Test fun callTimeoutReachesTheCallerInsteadOfHanging() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val client = OkHttpClient.Builder().callTimeout(300, TimeUnit.MILLISECONDS).build()
            val elapsed = measureTimeMillis {
                org.junit.Assert.assertThrows(java.io.IOException::class.java) {
                    runBlocking { executeBounded(client, Request.Builder().url(server.url("/timeout")).build(), 1_000) }
                }
            }
            assertTrue("Timeout took ${elapsed}ms", elapsed < 2_000)
        }
    }
}
