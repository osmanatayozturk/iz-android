package org.iz.navigation.integration.osm

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.iz.navigation.data.MapEditDraft
import org.iz.navigation.data.MapEditStage
import org.iz.navigation.data.MapEditStatus
import org.iz.navigation.data.OsmType
import org.junit.Assert.*
import org.junit.Test

class OsmMapCorrectionTest {
    @Test fun nodeModificationPreservesCoordinatesAndAllOtherTags() = runBlocking {
        val fixture = Fixture()
        val review = fixture.review()
        assertEquals(listOf("Old" to "New & Local", "Map Street" to "Map Street", null to null),
            review.fields.map { it.before to it.after })
        assertTrue(fixture.publisher().send(review).success)
        val request = fixture.requests.single { it.url.encodedPath.endsWith("/upload") }
        assertTrue(request.body!!.isOneShot())
        val xml = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
        val modify = OsmMapEditXml.children(OsmMapEditXml.parse(xml), "modify").single()
        val node = OsmMapEditXml.children(modify, "node").single()
        assertEquals("7", node.getAttribute("id"))
        assertEquals("8", node.getAttribute("version"))
        assertEquals("22", node.getAttribute("changeset"))
        assertEquals(41.1234567, node.getAttribute("lat").toDouble(), 0.0)
        assertEquals(29.2345678, node.getAttribute("lon").toDouble(), 0.0)
        assertEquals(mapOf("name" to "New & Local", "addr:street" to "Map Street", "amenity" to "cafe",
            "operator" to "Existing\nOperator"), OsmMapEditXml.children(node, "tag").associate {
            it.getAttribute("k") to it.getAttribute("v")
        })
        assertEquals(9L, fixture.store.value.appliedVersion)
        assertNull(fixture.store.value.remoteNodeId)
    }

    @Test fun wayModificationPreservesOrderedNodeReferences() = runBlocking {
        val fixture = Fixture(OsmType.WAY)
        assertTrue(fixture.publisher().send(fixture.review()).success)
        val xml = Buffer().also { fixture.requests.single { it.url.encodedPath.endsWith("/upload") }.body!!.writeTo(it) }.readUtf8()
        val modify = OsmMapEditXml.children(OsmMapEditXml.parse(xml), "modify").single()
        val way = OsmMapEditXml.children(modify, "way").single()
        assertEquals(listOf("14", "3", "2", "14"), OsmMapEditXml.children(way, "nd").map { it.getAttribute("ref") })
        assertFalse(way.hasAttribute("lat"))
        assertEquals(1, fixture.creates)
    }

    @Test fun staleObjectOrAccountRequiresAnotherReviewBeforeAnyWrite() = runBlocking {
        val fixture = Fixture()
        val review = fixture.review()
        fixture.version = 9
        assertFalse(fixture.publisher().send(review).success)
        assertEquals(MapEditStatus.DRAFT, fixture.store.value.status)
        val fresh = fixture.review()
        fixture.account = OsmCredentials("different", OsmUser(42, "Other"), null, fixture.account.scopes)
        assertFalse(fixture.publisher().send(fresh).success)
        assertEquals(0, fixture.creates)
    }

    @Test fun accountSwapDuringChangesetCreationPreventsUpload() = runBlocking {
        val fixture = Fixture()
        val review = fixture.review()
        fixture.onCreate = { fixture.account = OsmCredentials("different", OsmUser(42, "Other"), null, fixture.account.scopes) }
        assertFalse(fixture.publisher().send(review).success)
        assertEquals(0, fixture.uploads)
        assertEquals(MapEditStatus.FAILED, fixture.store.value.status)
    }

    @Test fun accountSwapAtHttpDispatchDoesNotUploadOrLeaveUnknown() = runBlocking {
        val fixture = Fixture()
        val review = fixture.review()
        val checksAfterPersist = AtomicInteger()
        val other = OsmCredentials("different", OsmUser(42, "Other"), null, fixture.account.scopes)
        fixture.currentAccount = {
            val prepared = fixture.store.value.stage == MapEditStage.UPDATE_ELEMENT
            if (prepared && checksAfterPersist.incrementAndGet() > 1) other else fixture.account
        }
        assertFalse(fixture.publisher().send(review).success)
        assertEquals(1, fixture.creates)
        assertEquals(0, fixture.uploads)
        assertEquals(MapEditStatus.FAILED, fixture.store.value.status)
    }

    @Test fun versionConflictKeepsInputAndCannotReuseReview() = runBlocking {
        val fixture = Fixture()
        val review = fixture.review()
        fixture.uploadCode = 409
        assertFalse(fixture.publisher().send(review).success)
        assertEquals(MapEditStatus.FAILED, fixture.store.value.status)
        assertEquals("New & Local", fixture.store.value.name)
        assertFalse(fixture.publisher().send(review).success)
        assertEquals(1, fixture.uploads)
    }

    @Test fun interruptedWayResolvesOnlyExactModificationInSameChangesetAfterRestart() = runBlocking {
        val fixture = Fixture(OsmType.WAY)
        fixture.uploadInterrupted = true
        val review = fixture.review()
        assertFalse(fixture.publisher().send(review).success)
        assertEquals(MapEditStatus.UNKNOWN, fixture.store.value.status)
        assertEquals(MapEditStage.UPDATE_ELEMENT, fixture.store.value.stage)
        assertEquals(22L, fixture.store.value.changesetId)
        val inFlight = requireNotNull(fixture.inFlight)
        assertEquals(MapEditStatus.SENDING, inFlight.status)
        assertEquals(MapEditStage.UPDATE_ELEMENT, inFlight.stage)
        val restartedStore = Memory(inFlight.copy(status = MapEditStatus.UNKNOWN))
        val restarted = fixture.publisher(restartedStore)
        assertFalse(restarted.reconcile(restartedStore.value.id).success)
        assertEquals(MapEditStatus.UNKNOWN, restartedStore.value.status)
        fixture.download = "<osmChange><create>${fixture.element(9, 22, corrected = true)}</create></osmChange>"
        assertFalse(restarted.reconcile(restartedStore.value.id).success)
        fixture.download = "<osmChange><modify>${fixture.element(9, 22, corrected = true).replace("Existing", "Wrong")}</modify></osmChange>"
        assertFalse(restarted.reconcile(restartedStore.value.id).success)
        fixture.download = "<osmChange><modify>${fixture.element(9, 23, corrected = true)}</modify></osmChange>"
        assertFalse(restarted.reconcile(restartedStore.value.id).success)
        fixture.download = "<osmChange><modify>${fixture.element(10, 22, corrected = true)}</modify></osmChange>"
        assertFalse(restarted.reconcile(restartedStore.value.id).success)
        fixture.download = "<osmChange><modify>${fixture.element(9, 22, corrected = true)}</modify></osmChange>"
        assertTrue(restarted.reconcile(restartedStore.value.id).success)
        assertEquals(MapEditStatus.SENT, restartedStore.value.status)
        assertEquals(9L, restartedStore.value.appliedVersion)
        assertEquals(1, fixture.uploads)
        assertEquals(1, fixture.creates)
    }

    @Test fun lostChangesetCreateResponseCanBeRecoveredWithoutAnyModifyOrReplay() = runBlocking {
        val fixture = Fixture().apply { createInterrupted = true }
        assertFalse(fixture.publisher().send(fixture.review()).success)
        assertEquals(MapEditStatus.UNKNOWN, fixture.store.value.status)
        assertEquals(MapEditStage.CREATE_CHANGESET, fixture.store.value.stage)
        assertNull(fixture.store.value.changesetId)
        assertFalse(fixture.publisher().reconcile(fixture.store.value.id).success)
        assertEquals(MapEditStatus.FAILED, fixture.store.value.status)
        assertEquals(1, fixture.creates)
        assertEquals(0, fixture.uploads)
    }

    @Test fun unsupportedOpenWayNeverCreatesAChangeset() = runBlocking {
        val fixture = Fixture(OsmType.WAY).apply { refs = listOf(14, 3, 2) }
        try {
            fixture.review()
            fail("Open way accepted")
        } catch (_: OsmUnsupportedCorrection) {
            assertEquals(0, fixture.creates)
        }
    }

    @Test fun closedWayExplicitlyMarkedAsNotAnAreaCannotBeCorrected() = runBlocking {
        val fixture = Fixture(OsmType.WAY).apply { additionalTags = """<tag k="area" v="no"/>""" }
        try {
            fixture.review()
            fail("Explicitly linear closed way accepted")
        } catch (_: OsmUnsupportedCorrection) {
            assertEquals(0, fixture.creates)
        }
    }

    private class Fixture(private val type: OsmType = OsmType.NODE) {
        val store = Memory(MapEditDraft(latitude = 0.0, longitude = 0.0, name = "New & Local", street = "  ",
            surveyConfirmed = true, targetType = type, targetId = 7))
        var account = OsmCredentials("test-token", OsmUser(41, "Mapper"), null, setOf("write_api"))
        var currentAccount: () -> OsmCredentials = { account }
        var version = 8L
        var refs = listOf(14, 3, 2, 14)
        var additionalTags = ""
        var download = "<osmChange/>"
        var uploadCode = 200
        var uploadInterrupted = false
        var createInterrupted = false
        var creates = 0
        var uploads = 0
        var inFlight: MapEditDraft? = null
        var onCreate: () -> Unit = {}
        val requests = mutableListOf<Request>()
        private val kind = type.name.lowercase()
        private val client = OsmMapEditClient(OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            val path = request.url.encodedPath.removePrefix("/api/0.6/")
            val response = when (path) {
                "$kind/7" -> 200 to "<osm>${element(version, 11)}</osm>"
                "changeset/create" -> {
                    creates++
                    onCreate()
                    if (createInterrupted) throw IOException("Lost changeset response")
                    200 to "22"
                }
                "changeset/22/upload" -> {
                    uploads++
                    inFlight = store.value
                    if (uploadInterrupted) throw IOException("Lost write response")
                    uploadCode to "<diffResult><$kind old_id=\"7\" new_id=\"7\" new_version=\"9\"/></diffResult>"
                }
                "changeset/22" -> 200 to """<osm><changeset id="22" uid="41" open="false"/></osm>"""
                "changeset/22/download" -> 200 to download
                "changeset/22/close" -> 200 to ""
                else -> error("Unexpected $request")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(response.first).message("fixture")
                .body(response.second.toResponseBody()).build()
        }.build())

        fun publisher(memory: Memory = store) = OsmMapCorrectionPublisher(memory, { currentAccount() }, client, { 100L })
        suspend fun review() = publisher().preview(store.value)

        fun element(version: Long, changeset: Int, corrected: Boolean = false): String {
            val position = if (type == OsmType.NODE) " lat=\"41.1234567\" lon=\"29.2345678\"" else ""
            val nodes = if (type == OsmType.WAY) refs.joinToString("") { "<nd ref=\"$it\"/>" } else ""
            val name = if (corrected) "New &amp; Local" else "Old"
            return """<$kind id="7" version="$version" changeset="$changeset" uid="41" visible="true"$position>$nodes<tag k="name" v="$name"/><tag k="addr:street" v="Map Street"/><tag k="amenity" v="cafe"/><tag k="operator" v="Existing&#10;Operator"/>$additionalTags</$kind>"""
        }
    }

    private class Memory(var value: MapEditDraft) : OsmMapEditStore {
        override suspend fun get(id: String) = value.takeIf { it.id == id }
        override suspend fun begin(id: String, userId: Long, time: Long): MapEditDraft? = error("Legacy create path used")
        override suspend fun compareAndSet(expected: MapEditDraft, updated: MapEditDraft): Boolean {
            if (value != expected) return false
            value = updated
            return true
        }
    }
}
