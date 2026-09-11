package org.iz.navigation.integration.osm

import org.iz.navigation.data.*
import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class OsmMapEditClientTest {
    private val draft = MapEditDraft(latitude = 41.0, longitude = 29.0, name = "Kafe & Çay", surveyConfirmed = true)
    private fun client(reply: (Request) -> String) = OsmMapEditClient(OkHttpClient.Builder().addInterceptor { chain ->
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(reply(chain.request()).toResponseBody()).build()
    }.build())

    @Test fun actualClientSendsOnlyReviewedTagsInOneShotUploadToFixedHost() = runBlocking {
        var sent: Request? = null
        val api = client { sent = it; "<diffResult><node old_id=\"-1\" new_id=\"123\" new_version=\"1\"/></diffResult>" }
        assertEquals(OsmUploadedNode(123, 1), api.uploadNode("fictional-token", draft, 22))
        assertEquals("https://api.openstreetmap.org/api/0.6/changeset/22/upload", sent!!.url.toString())
        assertEquals("Bearer fictional-token", sent!!.header("Authorization"))
        assertTrue(sent!!.body!!.isOneShot())
        val xml = Buffer().also { sent!!.body!!.writeTo(it) }.readUtf8()
        assertTrue(xml.contains("Kafe &amp; Çay"))
        assertFalse(xml.contains("observedAt"))
    }

    @Test fun rejectedOrMalformedDiffCannotBeClaimedAsCreatedNode() = runBlocking {
        listOf("<diffResult/>", "<diffResult><node old_id=\"-2\" new_id=\"3\" new_version=\"1\"/></diffResult>",
            "<diffResult><node old_id=\"-1\" new_id=\"3\" new_version=\"2\"/></diffResult>").forEach { body ->
            try { client { body }.uploadNode("token", draft, 22); fail("Invalid diff accepted") } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun onlyCreateNodesAreUsedToReconcileMissingReply() = runBlocking {
        val api = client { """<osmChange><create><node id="9" version="1" changeset="7" uid="41" lat="41" lon="29"><tag k="amenity" v="cafe"/></node></create><modify><node id="10" version="2" changeset="7" uid="41" lat="41" lon="29"/></modify></osmChange>""" }
        assertEquals(listOf(9L), api.downloadChangeset(7).map { it.id })
    }

    @Test fun externalEntitiesAreRejectedBeforeParsing() {
        assertThrows(IllegalArgumentException::class.java) { OsmMapEditXml.parse("<!DOCTYPE osm [<!ENTITY x SYSTEM 'file:///private'>]><osm>&x;</osm>") }
    }

    @Test fun duplicateQueryIncludesAreasAndFreshApiAndNeverHasBearerToken() = runBlocking {
        val requests = mutableListOf<Request>()
        val api = client { request ->
            requests += request
            if (request.url.host == "api.openstreetmap.org")
                """{"elements":[{"type":"node","id":99,"lat":41,"lon":29,"tags":{"amenity":"cafe","name":"Yeni kayıt"}}]}"""
            else """{"elements":[{"type":"way","id":77,"center":{"lat":41,"lon":29},"tags":{"amenity":"cafe","name":"Mevcut alan"}}]}"""
        }
        assertEquals(setOf(OsmRef(OsmType.WAY, 77), OsmRef(OsmType.NODE, 99)), api.nearby(draft).map { it.ref }.toSet())
        assertTrue(requests.all { it.header("Authorization") == null })
        val query = Buffer().also { requests.first().body!!.writeTo(it) }.readUtf8()
        assertTrue(query.contains("pivot.containing"))
        assertTrue(requests.last().url.toString().contains("/map.json?bbox="))
    }

    @Test fun incompleteOverpassResultCannotMeanNoDuplicates() {
        assertThrows(IllegalArgumentException::class.java) {
            parseDuplicateCandidates("""{"elements":[],"remark":"runtime error: timeout"}""", MapPlacePreset.CAFE)
        }
    }

    @Test fun networkFailureNeverRetriesMapWrite() = runBlocking {
        var calls = 0
        val api = OsmMapEditClient(OkHttpClient.Builder().addInterceptor { calls++; throw IOException("lost reply") }.build())
        try { api.uploadNode("token", draft, 7); fail("Failure expected") } catch (_: IOException) { }
        assertEquals(1, calls)
    }
}
