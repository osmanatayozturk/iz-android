package org.iz.navigation.placeinfo

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.iz.navigation.data.OsmRef
import org.iz.navigation.data.OsmType
import org.junit.Assert.*
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class PublicPlaceApiClientTest {
    private val clock = Clock.fixed(Instant.ofEpochMilli(123456), ZoneOffset.UTC)
    private fun xml(type: String = "node", id: Long = 42, body: String = "<tag k=\"name\" v=\"Public &amp; safe\"/>") =
        "<osm version=\"0.6\"><$type id=\"$id\" version=\"7\">$body</$type></osm>"

    @Test fun readsOnlyRequestedObjectTagsAndNeverSendsSharedCredentialsOrFetchesMembers() = runBlocking {
        MockWebServer().use { server ->
            val supplied = OkHttpClient.Builder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer synthetic-not-for-public-read").build())
            }.build()
            val reader = PublicPlaceApiClient(clock, supplied, allowLoopbackHttp = true)
            for (type in OsmType.entries) {
                server.enqueue(MockResponse().setBody(xml(type.name.lowercase(), body =
                    (if (type == OsmType.WAY) "<nd ref=\"5\"/>" else if (type == OsmType.RELATION) "<member type=\"way\" ref=\"6\" role=\"outer\"/>" else "") +
                        "<tag k=\"name\" v=\"Public &amp; safe\"/>")))
                val key = PlaceInfoKey(server.url("/api/0.6").toString(), OsmRef(type, 42))
                val snapshot = (reader.read(key) as PlaceInfoRead.Found).snapshot
                assertEquals(key, snapshot.key); assertEquals(7L, snapshot.version)
                assertEquals(mapOf("name" to "Public & safe"), snapshot.tags)
                assertEquals(123456L, snapshot.fetchedAtMillis)
                val request = server.takeRequest()
                assertEquals("GET", request.method)
                assertEquals("/api/0.6/${type.name.lowercase()}/42", request.path)
                assertNull(request.getHeader("Authorization")); assertNull(request.getHeader("Cookie"))
                assertEquals(0L, request.bodySize)
            }
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun missingAndGoneAreTypedUnavailableAndFailuresAreNotEmptySuccesses() = runBlocking {
        MockWebServer().use { server ->
            val reader = PublicPlaceApiClient(clock, allowLoopbackHttp = true)
            val key = PlaceInfoKey(server.url("/api/0.6").toString(), OsmRef(OsmType.NODE, 42))
            for (code in listOf(404, 410)) {
                server.enqueue(MockResponse().setResponseCode(code))
                assertEquals(PlaceInfoRead.Unavailable(key, code), reader.read(key))
            }
            server.enqueue(MockResponse().setResponseCode(500).setBody("server failure"))
            assertTrue(runCatching { reader.read(key) }.isFailure)
        }
    }

    @Test fun wrongIdentityDuplicateTagsNestedElementsAndEntitiesAreRejectedAsWholeResponses() = runBlocking {
        MockWebServer().use { server ->
            val reader = PublicPlaceApiClient(clock, allowLoopbackHttp = true)
            val key = PlaceInfoKey(server.url("/api/0.6").toString(), OsmRef(OsmType.NODE, 42))
            for (body in listOf(xml(id = 43), xml("way"), xml(body = "<tag k=\"name\" v=\"A\"/><tag k=\"name\" v=\"B\"/>"),
                xml(body = "<node id=\"42\" version=\"7\"/>"),
                "<!DOCTYPE osm [<!ENTITY x SYSTEM \"http://127.0.0.1:9/private\">]>" + xml(body = "<tag k=\"name\" v=\"&x;\"/>"),
                "<osm><node id=\"42\" version=\"7\"/><node id=\"43\" version=\"7\"/></osm>")) {
                server.enqueue(MockResponse().setBody(body))
                assertTrue(body.take(80), runCatching { reader.read(key) }.isFailure)
            }
            assertEquals(6, server.requestCount)
        }
    }

    @Test fun redirectsAndOversizeBodiesNeverTriggerAnotherRequest() = runBlocking {
        MockWebServer().use { server ->
            val reader = PublicPlaceApiClient(clock, allowLoopbackHttp = true)
            val key = PlaceInfoKey(server.url("/api/0.6").toString(), OsmRef(OsmType.NODE, 42))
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/other")))
            assertTrue(runCatching { reader.read(key) }.isFailure)
            server.enqueue(MockResponse().setBody("x".repeat(1_048_577)))
            assertTrue(runCatching { reader.read(key) }.isFailure)
            assertEquals(2, server.requestCount)
        }
    }
}
