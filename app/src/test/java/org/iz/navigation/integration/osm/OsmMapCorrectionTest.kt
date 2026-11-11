package org.iz.navigation.integration.osm

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.iz.navigation.data.BackupJson
import org.iz.navigation.data.DiarySnapshot
import org.iz.navigation.data.MapEditDraft
import org.iz.navigation.data.MapEditStage
import org.iz.navigation.data.MapEditStatus
import org.iz.navigation.data.OsmType
import org.iz.navigation.data.withLocalChangeset
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OsmMapCorrectionTest {
    @Test fun copiedExactPublicProofInEitherZipShapeNeverAuthorizesClose() = runBlocking {
        for (legacy in listOf(false, true)) {
            val fixture = Fixture()
            val restored = if (legacy) fixture.restoreLegacySent() else fixture.restoreSent()
            fixture.download = fixture.exactCloseProof(legacy)
            fixture.requests.clear()
            assertFalse(fixture.controller().close(restored.id).success)
            assertEquals(0, fixture.closeRequests.size)
            assertEquals(restored, fixture.store.value)
            assertTrue(fixture.controller().reconcile(restored.id).success)
            assertFalse(fixture.store.value.localChangesetCreated)
            assertFalse(fixture.controller().close(restored.id).success)
            assertEquals(0, fixture.closeRequests.size)
        }
    }
    @Test fun localCorrectionNeedsExactRemoteProofBeforeClose() = runBlocking {
        val fixture = Fixture(OsmType.WAY)
        val restored = fixture.localSent()
        val exact = fixture.element(9, 22, corrected = true)
        val otherOwner = exact.replace("uid=\"41\"", "uid=\"42\"")
        val otherElement = exact.replace("id=\"7\"", "id=\"17\"")
        val otherType = Fixture().element(9, 22, corrected = true)
        val otherVersion = fixture.element(10, 22, corrected = true)
        val otherChangeset = fixture.element(9, 23, corrected = true)
        val otherFingerprint = exact.replace("Existing", "Wrong")
        val rejected = listOf(otherOwner, otherElement, otherType, otherVersion, otherChangeset, otherFingerprint)
        for (element in rejected) {
            fixture.download = "<osmChange><modify>$element</modify></osmChange>"
            assertFalse(fixture.controller().close(restored.id).success)
            assertEquals(0, fixture.closeRequests.size)
            assertEquals(restored, fixture.store.value)
        }
        fixture.download = "<osmChange><create>$exact</create></osmChange>"
        assertFalse(fixture.controller().close(restored.id).success)
        assertEquals(0, fixture.closeRequests.size)

        fixture.download = "<osmChange><modify>$exact</modify></osmChange>"
        fixture.remoteChangesetId = 23
        assertFalse(fixture.controller().close(restored.id).success)
        fixture.remoteChangesetId = 22
        fixture.remoteChangesetOwner = 42
        assertFalse(fixture.controller().close(restored.id).success)
        assertEquals(0, fixture.closeRequests.size)
    }

    @Test fun localCorrectionCanCloseWithExactProofAndActiveOwner() = runBlocking {
        val fixture = Fixture()
        val restored = fixture.localSent()
        fixture.download = "<osmChange><modify>${fixture.element(9, 22, corrected = true)}</modify></osmChange>"
        fixture.requests.clear()
        assertTrue(fixture.controller().close(restored.id).success)
        assertEquals(listOf("GET /api/0.6/changeset/22", "GET /api/0.6/changeset/22/download", "PUT /api/0.6/changeset/22/close"),
            fixture.requests.map { "${it.method} ${it.url.encodedPath}" })
        assertEquals("Bearer test-token", fixture.closeRequests.single().header("Authorization"))
        assertEquals(restored.copy(changesetClosed = true, error = null), fixture.store.value)
    }

    @Test fun forgedZipChangesetAndFingerprintDoNotAuthorizeClose() = runBlocking {
        val fixture = Fixture()
        val restored = fixture.restoreSent { it.copy(changesetId = 23L, reviewedFingerprint = "f".repeat(64)) }
        fixture.remoteChangesetId = 23
        fixture.download = "<osmChange><modify>${fixture.element(9, 23, corrected = true)}</modify></osmChange>"
        assertFalse(fixture.controller().close(restored.id).success)
        assertEquals(0, fixture.closeRequests.size)
        assertEquals(restored, fixture.store.value)
    }

    @Test fun provenAlreadyClosedChangesetOnlyUpdatesLocalState() = runBlocking {
        for (legacy in listOf(false, true)) {
            val fixture = Fixture()
            val restored = fixture.localSent(legacy)
            fixture.remoteOpen = false
            fixture.download = fixture.exactCloseProof(legacy)
            assertTrue(fixture.controller().close(restored.id).success)
            assertEquals(0, fixture.closeRequests.size)
            assertEquals(restored.copy(changesetClosed = true, error = null), fixture.store.value)
        }
    }

    @Test fun localNewPlaceNeedsExactlyOneMatchingCreatedNodeBeforeClose() = runBlocking {
        val fixture = Fixture()
        val restored = fixture.localSent(legacy = true)
        val node = fixture.createdNode()
        val rejected = listOf(
            "<modify>$node</modify>",
            "<create>$node</create><modify>${fixture.element(9, 22, corrected = true)}</modify>",
            "<create>$node$node</create>",
            "<create>$node<way id=\"17\"/></create>",
            "<create>${node.replace("id=\"7\"", "id=\"17\"")}</create>",
            "<create>${node.replace("version=\"1\"", "version=\"2\"")}</create>",
            "<create>${node.replace("uid=\"41\"", "uid=\"42\"")}</create>",
            "<create>${node.replace("changeset=\"22\"", "changeset=\"23\"")}</create>",
            "<create>${node.replace("41.1234567", "41.1234568")}</create>",
            "<create>${node.replace("-29.2345678", "-29.2345679")}</create>",
            "<create>${node.replace("cafe", "school")}</create>",
        )
        for (content in rejected) {
            fixture.download = "<osmChange>$content</osmChange>"
            fixture.controller().close(restored.id)
            assertEquals(content, 0, fixture.closeRequests.size)
            assertEquals(restored, fixture.store.value)
        }
        fixture.download = "<osmChange><create>$node</create></osmChange>"
        fixture.remoteChangesetOwner = 42
        assertFalse(fixture.controller().close(restored.id).success)
        fixture.remoteChangesetOwner = 41
        fixture.remoteChangesetId = 23
        assertFalse(fixture.controller().close(restored.id).success)
        assertEquals(0, fixture.closeRequests.size)
    }

    @Test fun localNewPlaceWithExactCreatedNodeCanClose() = runBlocking {
        val fixture = Fixture()
        val restored = fixture.localSent(legacy = true)
        fixture.download = "<osmChange><create>${fixture.createdNode()}</create></osmChange>"
        fixture.requests.clear()
        assertTrue(fixture.controller().close(restored.id).success)
        assertEquals(listOf("GET /api/0.6/changeset/22", "GET /api/0.6/changeset/22/download", "PUT /api/0.6/changeset/22/close"),
            fixture.requests.map { "${it.method} ${it.url.encodedPath}" })
        assertEquals("Bearer test-token", fixture.closeRequests.single().header("Authorization"))
        assertEquals(restored.copy(changesetClosed = true, error = null), fixture.store.value)
    }

    @Test fun closeNetworkGuardRejectsReplacedRowOrAccountAfterPreflight() = runBlocking {
        for ((legacy, replaceAccount) in listOf(false to false, false to true, true to false, true to true)) {
            val fixture = Fixture()
            val restored = fixture.localSent(legacy)
            MockWebServer().use { server ->
                server.start()
                server.enqueue(MockResponse().setBody("""<osm><changeset id="22" uid="41" open="true"/></osm>"""))
                server.enqueue(MockResponse().setBody(fixture.exactCloseProof(legacy)))
                server.enqueue(MockResponse().setBody(""))
                var queuedClose = false
                val gateway = OsmMapEditClient(OkHttpClient.Builder().addInterceptor { chain ->
                    val request = chain.request()
                    if (request.method == "PUT") {
                        queuedClose = true
                        if (replaceAccount) fixture.account = OsmCredentials("renewed-test-token", fixture.account.user, null, fixture.account.scopes)
                        else fixture.restoreFromZip(restored)
                    }
                    chain.proceed(request.newBuilder().url(server.url(request.url.encodedPath)).build())
                }.build())
                assertFalse(fixture.controller(gateway).close(restored.id).success)
                assertTrue(queuedClose)
                assertEquals(2, server.requestCount)
                assertFalse(fixture.store.value.changesetClosed)
                assertEquals(if (replaceAccount) restored else restored.copy(localAttemptId = null, localChangesetCreated = false),
                    fixture.store.value)
            }
        }
    }

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
        assertEquals(1, fixture.closeRequests.size)
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
        var closeCode = 200
        var uploadInterrupted = false
        var createInterrupted = false
        var creates = 0
        var uploads = 0
        var inFlight: MapEditDraft? = null
        var onCreate: () -> Unit = {}
        var remoteChangesetId = 22
        var remoteChangesetOwner = 41
        var remoteOpen = false
        val requests = mutableListOf<Request>()
        val closeRequests get() = requests.filter { it.method == "PUT" && it.url.encodedPath.endsWith("/close") }
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
                    uploadCode to if (store.value.targetType == null)
                        "<diffResult><node old_id=\"-1\" new_id=\"7\" new_version=\"1\"/></diffResult>"
                    else "<diffResult><$kind old_id=\"7\" new_id=\"7\" new_version=\"9\"/></diffResult>"
                }
                "changeset/22", "changeset/23" -> 200 to """<osm><changeset id="$remoteChangesetId" uid="$remoteChangesetOwner" open="$remoteOpen"/></osm>"""
                "changeset/22/download", "changeset/23/download" -> 200 to download
                "changeset/22/close", "changeset/23/close" -> closeCode to ""
                else -> error("Unexpected $request")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(response.first).message("fixture")
                .body(response.second.toResponseBody()).build()
        }.build())

        fun publisher(memory: Memory = store) = OsmMapCorrectionPublisher(memory, { currentAccount() }, client, { 100L })
        fun controller(gateway: OsmMapCorrectionGateway = client) = OsmMapEditController(store, { currentAccount() }, gateway)
        suspend fun review() = publisher().preview(store.value)

        suspend fun localSent(legacy: Boolean = false): MapEditDraft {
            closeCode = 503
            val result = if (legacy) {
                store.value = store.value.copy(targetType = null, targetId = null,
                    latitude = 41.12345674, longitude = -29.23456784)
                controller(object : OsmMapCorrectionGateway by client {
                    override suspend fun nearby(draft: MapEditDraft) = emptyList<OsmDuplicateCandidate>()
                }).send(store.value.id, emptySet())
            } else publisher().send(review())
            assertTrue(result.success)
            assertEquals(1, closeRequests.size)
            assertTrue(store.value.localChangesetCreated)
            assertNotNull(store.value.localAttemptId)
            assertFalse(store.value.changesetClosed)
            closeCode = 200
            remoteOpen = true
            requests.clear()
            return store.value
        }

        suspend fun restoreSent(rewrite: (MapEditDraft) -> MapEditDraft = { it }): MapEditDraft {
            val review = review()
            val sent = rewrite(store.value.copy(status = MapEditStatus.SENT, stage = MapEditStage.CLOSE_CHANGESET,
                submittedAt = 100L, submittedBy = 41L, changesetId = 22L, reviewedVersion = 8L,
                reviewedFingerprint = review.corrected.fingerprint(), appliedVersion = 9L))
            return restoreFromZip(sent)
        }

        fun restoreFromZip(sent: MapEditDraft): MapEditDraft {
            val manifest = BackupJson.encode(DiarySnapshot(mapEdits = listOf(sent)))
            assertEquals(10, manifest.getInt("version"))
            val row = manifest.getJSONArray("mapEdits").getJSONObject(0)
            assertFalse(row.has("localAttemptId"))
            assertFalse(row.has("localChangesetCreated"))
            row.put("localAttemptId", UUID.randomUUID().toString()).put("localChangesetCreated", true)
            if (sent.targetType == null) {
                listOf("targetType", "targetId", "reviewedVersion", "reviewedFingerprint", "appliedVersion").forEach { row.remove(it) }
            }
            val zipped = ByteArrayOutputStream()
            ZipOutputStream(zipped).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
            }
            store.value = ZipInputStream(ByteArrayInputStream(zipped.toByteArray())).use { zip ->
                assertEquals("manifest.json", zip.nextEntry.name)
                BackupJson.decode(JSONObject(zip.readBytes().toString(Charsets.UTF_8))).mapEdits.single()
            }
            assertEquals(sent.copy(localAttemptId = null, localChangesetCreated = false), store.value)
            remoteOpen = true
            return store.value
        }

        suspend fun restoreLegacySent(): MapEditDraft = restoreSent {
            it.copy(targetType = null, targetId = null, reviewedVersion = null, reviewedFingerprint = null,
                appliedVersion = null, remoteNodeId = 7L, remoteNodeVersion = 1L,
                latitude = 41.12345674, longitude = -29.23456784)
        }

        fun createdNode() = """<node id="7" version="1" changeset="22" uid="41" lat="41.1234567" lon="-29.2345678"><tag k="name" v="New &amp; Local"/><tag k="amenity" v="cafe"/></node>"""

        fun exactCloseProof(legacy: Boolean): String = if (legacy) {
            "<osmChange><create>${createdNode()}</create></osmChange>"
        } else {
            "<osmChange><modify>${element(9, 22, corrected = true)}</modify></osmChange>"
        }

        fun element(version: Long, changeset: Int, corrected: Boolean = false): String {
            val position = if (type == OsmType.NODE) " lat=\"41.1234567\" lon=\"29.2345678\"" else ""
            val nodes = if (type == OsmType.WAY) refs.joinToString("") { "<nd ref=\"$it\"/>" } else ""
            val name = if (corrected) "New &amp; Local" else "Old"
            return """<$kind id="7" version="$version" changeset="$changeset" uid="41" visible="true"$position>$nodes<tag k="name" v="$name"/><tag k="addr:street" v="Map Street"/><tag k="amenity" v="cafe"/><tag k="operator" v="Existing&#10;Operator"/>$additionalTags</$kind>"""
        }
    }

    private class Memory(@Volatile var value: MapEditDraft) : OsmMapEditStore {
        override fun isCurrentForDispatch(expected: MapEditDraft) = value == expected
        override suspend fun get(id: String) = value.takeIf { it.id == id }
        override suspend fun begin(id: String, userId: Long, time: Long): MapEditDraft? {
            if (value.id != id || value.targetType != null || value.status !in setOf(MapEditStatus.DRAFT, MapEditStatus.FAILED)) return null
            value = value.editableCopy().copy(status = MapEditStatus.SENDING, stage = MapEditStage.CREATE_CHANGESET,
                submittedBy = userId, submittedAt = time, localAttemptId = UUID.randomUUID().toString())
            return value
        }
        override suspend fun recordLocalChangeset(expected: MapEditDraft, changeset: Long): MapEditDraft? {
            if (value != expected) return null
            return expected.withLocalChangeset(changeset).also { value = it }
        }
        override suspend fun compareAndSet(expected: MapEditDraft, updated: MapEditDraft): Boolean {
            if (value != expected) return false
            value = updated
            return true
        }
    }
}
