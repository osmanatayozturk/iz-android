package org.iz.navigation.integration.osm

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.iz.navigation.integration.OsmDirectedPoint
import org.iz.navigation.osmcommunity.CommunitySession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OsmGpxVisibleReadTest {
    @get:Rule val noBackup = TemporaryFolder()

    @Test fun twoVisibleWaitersShareOneGetWhenOnlyOneLeavesAndTheNextScreenUsesItsResult() = runBlocking {
        val server = HistoryServer()
        val controller = controller(server)
        try {
            val hub = launchRead { controller.observeVisibleHistory() }
            val get = server.nextGet()
            val gps = launchRead { controller.observeVisibleHistory() }
            hub.cancelAndJoin()
            assertFalse(get.cancelled.isCompleted)
            assertEquals(1, server.gets.size)
            get.reply.complete(listOf(server.entry))
            assertEquals(listOf(server.entry), controller.awaitReady().entries)
            assertFalse(gps.isCompleted)
            gps.cancelAndJoin()

            // Handoff after the first response must use the completed history without another GET.
            withTimeout(WAIT_MS) { controller.ensureHistory() }
            assertEquals(1, server.gets.size)
            assertEquals(1, server.dataReads.get())
            assertEquals(0, server.posts.get())
            assertEquals(0, server.puts.get())
        } finally {
            server.releasePending()
        }
    }

    @Test fun leavingTheLastWaiterCancelsTheInFlightGetBeforeAnyFollowUpAndReturnStartsANewGet() = runBlocking {
        val server = HistoryServer()
        val controller = controller(server)
        try {
            val autoRead = launchRead { controller.observeVisibleHistory() }
            val firstGet = server.nextGet()
            val explicitRefresh = launchRead { controller.refreshHistory() }
            autoRead.cancelAndJoin()
            assertFalse(firstGet.cancelled.isCompleted)
            assertEquals(1, server.gets.size)
            explicitRefresh.cancelAndJoin()
            withTimeout(WAIT_MS) { firstGet.cancelled.await() }
            assertEquals(0, server.dataReads.get())
            assertEquals(0, server.metadataReads.get())
            assertEquals(0, server.posts.get())
            assertEquals(0, server.puts.get())
            assertTrue(noBackup.root.walkTopDown().none { it.isFile })

            val returned = launchRead { controller.observeVisibleHistory() }
            val nextGet = server.nextGet()
            assertEquals(2, server.gets.size)
            nextGet.reply.complete(listOf(server.entry))
            assertEquals(listOf(server.entry), controller.awaitReady().entries)
            returned.cancelAndJoin()
            assertEquals(1, server.dataReads.get())
            assertEquals(0, server.metadataReads.get())
            assertEquals(0, server.posts.get())
            assertEquals(0, server.puts.get())
        } finally {
            server.releasePending()
        }
    }

    private fun controller(server: HistoryServer): OsmGpxController {
        val session = CommunitySession("synthetic-visible", 41L, "Synthetic", setOf(OSM_GPX_READ_SCOPE, OSM_GPX_WRITE_SCOPE), 1)
        return OsmGpxController(
            currentSession = { session },
            gateway = OsmGpxGateway { _, _, _, _ ->
                server.posts.incrementAndGet()
                error("Reading history must never POST")
            },
            store = OsmGpxFileReceipts(noBackup.root),
            authState = MutableStateFlow(OsmAuthUiState()),
            sessions = MutableStateFlow<CommunitySession?>(session),
            permissionRequest = {},
            historyGateway = server,
        )
    }

    private fun CoroutineScope.launchRead(read: suspend () -> Unit) =
        launch(start = CoroutineStart.UNDISPATCHED) { read() }

    private suspend fun OsmGpxController.awaitReady(): OsmGpxHistoryState.Ready =
        withTimeout(WAIT_MS) { history.filterIsInstance<OsmGpxHistoryState.Ready>().first() }

    private class Get {
        val reply = CompletableDeferred<List<OsmGpxHistoryEntry>>()
        val cancelled = CompletableDeferred<Unit>()
    }

    private class HistoryServer : OsmGpxHistoryGateway {
        val entry = OsmGpxHistoryEntry(301L, "synthetic.gpx", 1_740_000_000_000L, false, "trackable")
        private val arrived = Channel<Get>(Channel.UNLIMITED)
        val gets = CopyOnWriteArrayList<Get>()
        val dataReads = AtomicInteger()
        val metadataReads = AtomicInteger()
        val posts = AtomicInteger()
        val puts = AtomicInteger()

        suspend fun nextGet(): Get = withTimeout(WAIT_MS) { arrived.receive() }

        fun releasePending() {
            for (get in gets) get.reply.complete(emptyList())
        }

        override suspend fun list(token: String, expectedUserId: Long): List<OsmGpxHistoryEntry> {
            assertEquals("synthetic-visible", token)
            assertEquals(41L, expectedUserId)
            val get = Get()
            gets += get
            arrived.send(get)
            return try {
                get.reply.await()
            } catch (cancel: CancellationException) {
                get.cancelled.complete(Unit)
                throw cancel
            }
        }

        override suspend fun data(token: String, id: Long): OsmGpxRemoteData {
            assertEquals(entry.id, id)
            dataReads.incrementAndGet()
            return OsmGpxRemoteData(listOf(listOf(OsmDirectedPoint(.04, -.29), OsmDirectedPoint(.04, -.2898))),
                "b".repeat(64))
        }

        override suspend fun readMetadata(token: String, expectedUserId: Long, id: Long,
            canDispatch: () -> Boolean): OsmGpxRemoteMetadata {
            metadataReads.incrementAndGet()
            error("This account has no previous visibility attempt to inspect")
        }

        override suspend fun changeToTrackable(token: String, expectedUserId: Long, id: Long,
            canDispatch: () -> Boolean, beforePut: (OsmGpxRemoteMetadata) -> Boolean): Boolean {
            puts.incrementAndGet()
            error("Reading history must never PUT")
        }
    }

    private companion object {
        const val WAIT_MS = 5_000L
    }
}
