package org.iz.navigation.navigation

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.iz.navigation.data.Transport
import org.iz.navigation.offline.*
import org.iz.navigation.weather.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PreparedMapCommandsTest {
    @Test fun validPersistedSelectionDelegatesDownloadToTheRealController() = withFixture { f ->
        f.persist()
        val document = JSONObject(Files.readAllBytes(f.selectionFile).toString(Charsets.UTF_8))
        assertEquals(1, document.getInt("schema"))
        assertEquals("selection-route", document.getString("routeId"))
        assertEquals(100.0, document.getDouble("halfWidthMeters"), 0.0)

        assertTrue(f.commands.dispatch(f.current, "download"))

        assertEquals(1, f.actions.downloadCalls)
        assertEquals(1, f.native.created.size)
        assertEquals(FULL_LINE, f.native.created.single().request.line)
        assertEquals(200.0, f.native.created.single().request.widthMeters, 0.0)
        assertEquals(OfflinePackStatus.DOWNLOADING, f.controller.state.value!!.status)
        assertEquals(7L, f.packStore.read()!!.regionId)
        assertEquals(OfflinePackStatus.DOWNLOADING, f.packStore.read()!!.status)
    }

    @Test fun literalSelectionRestoresAndRetainsTheInteriorBendWithoutEncoderOracle() = withFixture { f ->
        f.writeLiteral()
        val restored = PreparedSelectionStore(f.directory.toFile())
        val selected = requireNotNull(restored.read(f.route, FINGERPRINT, f.controller.state.value))
        assertEquals(0.0, selected.fromMeters, 0.0)
        assertEquals(217.18, selected.toMeters, 0.0)
        assertEquals(100.0, selected.halfWidthMeters, 0.0)
        assertEquals(FULL_LINE, selectedLine(f.route, selected))
        val commands = PreparedMapCommands(restored, f.actions)

        assertTrue(commands.dispatch(f.current, "download"))

        assertEquals(1, f.native.created.size)
        assertEquals(FULL_LINE, f.native.created.single().request.line)
        assertEquals(3, f.native.created.single().request.line.size)
    }

    @Test fun validRestoredSelectionPermitsExplicitResumeWithoutAutoStartingOnRestore() = withFixture { f ->
        f.persist()
        assertTrue(f.commands.dispatch(f.current, "download"))
        assertTrue(f.commands.dispatch(f.current, "pause"))
        assertEquals(OfflinePackStatus.PAUSED, f.packStore.read()!!.status)
        val native = RecordingNative()
        val controller = OfflineCorridorController(FileOfflinePackStore(f.directory.toFile()), native)
        val actions = Actions(controller)
        val commands = PreparedMapCommands(PreparedSelectionStore(f.directory.toFile()), actions)
        assertTrue(native.created.isEmpty())
        assertTrue(native.resumed.isEmpty())
        assertEquals(OfflinePackStatus.PAUSED, controller.state.value!!.status)

        assertTrue(commands.dispatch(f.current, "resume"))

        assertEquals(1, actions.resumeCalls)
        assertEquals(listOf(7L), native.resumed)
        assertTrue(native.created.isEmpty())
        assertEquals(OfflinePackStatus.DOWNLOADING, FileOfflinePackStore(f.directory.toFile()).read()!!.status)
    }

    @Test fun actualDiskWriteFailureCannotMakeANullSelectionTransferable() = withFixture { f ->
        val blockedParent = f.directory.resolve("parent-is-file")
        val original = "keep this file".toByteArray(Charsets.UTF_8)
        Files.write(blockedParent, original)
        val failedStore = PreparedSelectionStore(blockedParent.toFile())
        assertThrows(IllegalStateException::class.java) {
            failedStore.write(f.route, FINGERPRINT, FULL_SELECTION)
        }
        val commands = PreparedMapCommands(failedStore, f.actions)
        val missingSelection = f.current.copy(selection = null)

        assertThrows(IllegalStateException::class.java) { commands.dispatch(missingSelection, "download") }
        assertThrows(IllegalStateException::class.java) { commands.dispatch(missingSelection, "resume") }

        assertArrayEquals(original, Files.readAllBytes(blockedParent))
        f.assertNoTransferDelegation()
        assertEquals(OfflinePackStatus.PREPARED, f.packStore.read()!!.status)
    }

    @Test fun nullMemorySelectionRejectsEvenWhenThePersistedSelectionExactlyMatches() = withFixture { f ->
        f.persist()
        assertNotNull(f.selections.read(f.route, FINGERPRINT, f.controller.state.value))
        f.assertRejected(f.current.copy(selection = null))
        f.assertNoTransferDelegation()
        assertEquals(OfflinePackStatus.PREPARED, f.packStore.read()!!.status)
    }

    @Test fun missingCurrentRouteOrFingerprintCannotReachTheFacade() = withFixture { f ->
        f.persist()
        f.assertRejected(f.current.copy(route = null))
        f.assertRejected(f.current.copy(fingerprint = null))
        f.assertNoTransferDelegation()
    }

    @Test fun changedMemorySelectionCannotReuseAnOlderMatchingDiskRecord() = withFixture { f ->
        f.persist()
        assertNotNull(f.selections.read(f.route, FINGERPRINT, f.controller.state.value))
        listOf(FULL_SELECTION.copy(fromMeters = 1.0), FULL_SELECTION.copy(toMeters = 100.0),
            FULL_SELECTION.copy(halfWidthMeters = 150.0)).forEach { changed ->
            f.assertRejected(f.current.copy(selection = changed))
        }
        f.assertNoTransferDelegation()
    }

    @Test fun removingSelectionAfterValidationIsDetectedOnTheNextTransfer() = withFixture { f ->
        f.persist()
        f.selections.requireForTransfer(f.current, f.controller.state.value)
        Files.delete(f.selectionFile)

        f.assertRejected(f.current)

        assertFalse(Files.exists(f.selectionFile))
        f.assertNoTransferDelegation()
    }

    @Test fun externalPersistedMetadataTamperingIsNotRepairedOrTrustedByTheGate() = withFixture { f ->
        val mutations = listOf<Pair<String, Any>>(
            "schema" to 99, "routeId" to "other-route", "fingerprint" to "b".repeat(64),
            "fromMeters" to 1.0, "toMeters" to 100.0, "halfWidthMeters" to 150.0,
        )
        mutations.forEach { (key, value) ->
            val document = literalSelection().put(key, value).toString().toByteArray(Charsets.UTF_8)
            Files.write(f.selectionFile, document)
            assertNull(key, f.selections.read(f.route, FINGERPRINT, f.controller.state.value))
            f.assertRejected(f.current)
            assertArrayEquals("Rejection cannot silently rewrite $key", document, Files.readAllBytes(f.selectionFile))
        }
        f.assertNoTransferDelegation()
    }

    @Test fun malformedOrIncompleteSelectionFileNeverDefaultsToAnAllowedTransfer() = withFixture { f ->
        listOf("", "{", "[]", "null", "not-json").forEach { bad ->
            Files.write(f.selectionFile, bad.toByteArray(Charsets.UTF_8))
            f.assertRejected(f.current)
        }
        listOf("schema", "routeId", "fingerprint", "fromMeters", "toMeters", "halfWidthMeters").forEach { missing ->
            val json = literalSelection().also { it.remove(missing) }
            Files.write(f.selectionFile, json.toString().toByteArray(Charsets.UTF_8))
            f.assertRejected(f.current)
        }
        f.assertNoTransferDelegation()
    }

    @Test fun actualLivePackIdentityGeometryAndFullWidthMustMatchThePersistedSelection() = withFixture { f ->
        f.persist()
        val mismatches = listOf(
            f.request.copy(routeId = "foreign-route"),
            f.request.copy(sourceFingerprint = "b".repeat(64)),
            f.request.copy(widthMeters = 100.0), // Half width cannot be mistaken for native full width200.
            f.request.copy(line = listOf(FULL_LINE.first(), FULL_LINE.last())), // Removed interior bend.
            f.request.copy(line = FULL_LINE.reversed()),
        )
        mismatches.forEach { changedLiveRequest ->
            f.controller.prepare(changedLiveRequest)
            // current.pack deliberately remains the old UI snapshot; actions.pack is the real live owner.
            f.assertRejected(f.current)
        }
        f.assertNoTransferDelegation()
    }

    @Test fun changedRouteVerticesCannotReuseMatchingIdAndFingerprintAlone() = withFixture { f ->
        f.persist()
        val changedRoute = f.route.copy(vertices = listOf(
            RouteVertex(WeatherCoordinate(0.0, 0.0), 0.0),
            RouteVertex(WeatherCoordinate(STEP, 0.0), 60.0),
            RouteVertex(WeatherCoordinate(STEP, STEP), 120.0),
        ))
        f.assertRejected(f.current.copy(route = changedRoute))
        f.assertNoTransferDelegation()
        assertEquals(FULL_LINE, f.packStore.read()!!.request.line)
    }

    @Test fun absentLivePackCannotBeReplacedByTheStatesOldPackSnapshot() = withFixture { f ->
        f.persist()
        assertNotNull(f.current.pack)
        assertTrue(f.controller.delete())
        assertNull(f.controller.state.value)
        assertTrue(Files.exists(f.selectionFile))

        f.assertRejected(f.current)

        f.assertNoTransferDelegation()
        assertNull(f.packStore.read())
    }

    @Test fun resumeRevalidatesDiskAfterARealDownloadAndPause() = withFixture { f ->
        f.persist()
        assertTrue(f.commands.dispatch(f.current, "download"))
        assertTrue(f.commands.dispatch(f.current, "pause"))
        assertEquals(OfflinePackStatus.PAUSED, f.controller.state.value!!.status)
        Files.delete(f.selectionFile)

        assertThrows(IllegalStateException::class.java) { f.commands.dispatch(f.current, "resume") }

        assertEquals(0, f.actions.resumeCalls)
        assertTrue(f.native.resumed.isEmpty())
        assertEquals(1, f.native.created.size)
        assertEquals(OfflinePackStatus.PAUSED, f.packStore.read()!!.status)
        assertFalse(Files.exists(f.selectionFile))
    }

    @Test fun pauseCancelAndDeleteDoNotDependOnAValidPersistedSelection() {
        listOf("pause", "cancel", "delete").forEach { command -> withFixture { f ->
            f.persist()
            assertTrue(f.commands.dispatch(f.current, "download"))
            Files.delete(f.selectionFile)

            assertTrue(command, f.commands.dispatch(f.current.copy(selection = null), command))

            assertEquals(1, f.native.created.size)
            when (command) {
                "pause" -> {
                    assertEquals(1, f.actions.pauseCalls)
                    assertEquals(OfflinePackStatus.PAUSED, f.packStore.read()!!.status)
                    assertEquals(listOf(7L), f.native.paused)
                }
                "cancel" -> {
                    assertEquals(1, f.actions.cancelCalls)
                    assertEquals(OfflinePackStatus.PARTIAL, f.packStore.read()!!.status)
                    assertEquals(listOf(7L), f.native.paused)
                }
                "delete" -> {
                    assertEquals(1, f.actions.deleteCalls)
                    assertNull(f.controller.state.value)
                    assertNull(f.packStore.read())
                    assertEquals(listOf(7L), f.native.deleted)
                }
            }
        } }
    }

    @Test fun inUseDeleteIsRejectedEvenWhenSelectionIsMissing() = withFixture { f ->
        f.persist()
        assertTrue(f.commands.dispatch(f.current, "download"))
        Files.delete(f.selectionFile)

        assertThrows(IllegalStateException::class.java) {
            f.commands.dispatch(f.current.copy(selection = null, inUse = true), "delete")
        }

        assertEquals(0, f.actions.deleteCalls)
        assertTrue(f.native.deleted.isEmpty())
        assertEquals(OfflinePackStatus.DOWNLOADING, f.packStore.read()!!.status)
        assertEquals(7L, f.controller.state.value!!.regionId)
    }

    @Test fun partialSelectionPreservesItsInterpolatedEndpointsAndInteriorRouteVertex() = withFixture { f ->
        // A full leg is about108.58879555 m. These are hand-checked quarter/three-quarter endpoints.
        val partial = PreparedCorridorSelection(27.1472, 190.0304, 100.0)
        val line = selectedLine(f.route, partial)
        assertEquals(3, line.size)
        assertEquals(0.0, line[0].latitude, 0.0)
        assertEquals(0.000244140625, line[0].longitude, 0.000000001)
        assertEquals(0.0, line[1].latitude, 0.0)
        assertEquals(0.0009765625, line[1].longitude, 0.0)
        assertEquals(0.000732421875, line[2].latitude, 0.000000001)
        assertEquals(0.0009765625, line[2].longitude, 0.0)
        f.controller.prepare(f.request.copy(line = line))
        f.selections.write(f.route, FINGERPRINT, partial)

        assertTrue(f.commands.dispatch(f.current.copy(selection = partial), "download"))

        assertEquals(1, f.native.created.size)
        assertEquals(3, f.native.created.single().request.line.size)
        assertEquals(0.000244140625, f.native.created.single().request.line.first().longitude, 0.000000001)
        assertEquals(0.000732421875, f.native.created.single().request.line.last().latitude, 0.000000001)
    }

    @Test fun selectionReadAcceptsExactly4096BytesButRejectsOneExtraByte() = withFixture { f ->
        val raw = literalSelection().toString()
        val size = raw.toByteArray(Charsets.UTF_8).size
        val exact = raw + " ".repeat(4_096 - size)
        Files.write(f.selectionFile, exact.toByteArray(Charsets.UTF_8))
        assertEquals(4_096L, Files.size(f.selectionFile))
        assertTrue(f.commands.dispatch(f.current, "download"))
        val downloadCalls = f.actions.downloadCalls
        Files.write(f.selectionFile, (exact + " ").toByteArray(Charsets.UTF_8))

        f.assertRejected(f.current)

        assertEquals(downloadCalls, f.actions.downloadCalls)
        assertEquals(0, f.actions.resumeCalls)
        assertEquals(1, f.native.created.size)
        assertEquals(4_097L, Files.size(f.selectionFile))
    }

    @Test fun selectionByteLimitDoesNotUseUnicodeCharacterCount() = withFixture { f ->
        val json = literalSelection().put("padding", "曲".repeat(1_500)).toString()
        val bytes = json.toByteArray(Charsets.UTF_8)
        assertTrue(json.length < 4_096)
        assertTrue(bytes.size > 4_096)
        Files.write(f.selectionFile, bytes)

        f.assertRejected(f.current)

        f.assertNoTransferDelegation()
    }

    @Test fun invalidSelectionExtentOrWidthCannotBeRestoredForTransfer() = withFixture { f ->
        listOf<Pair<String, Any>>("fromMeters" to -1.0, "toMeters" to 0.0,
            "toMeters" to 217.2, "halfWidthMeters" to 49.0, "halfWidthMeters" to 1001.0,
            "halfWidthMeters" to "NaN", "toMeters" to "Infinity").forEach { (field, bad) ->
            Files.write(f.selectionFile, literalSelection().put(field, bad).toString().toByteArray(Charsets.UTF_8))
            f.assertRejected(f.current)
        }
        f.assertNoTransferDelegation()
    }

    private class Fixture(val directory: Path) {
        val route = makeRoute()
        val request = OfflineCorridorRequest(route.id, FINGERPRINT, FULL_LINE, 200.0, 10, 14)
        val native = RecordingNative()
        val packStore = FileOfflinePackStore(directory.toFile())
        val controller = OfflineCorridorController(packStore, native)
        private val prepared = controller.prepare(request)
        val selections = PreparedSelectionStore(directory.toFile())
        val actions = Actions(controller)
        val commands = PreparedMapCommands(selections, actions)
        val current = PreparedRouteState(route, FINGERPRINT, prepared, FULL_SELECTION, generation = 1)
        val selectionFile: Path get() = directory.resolve("selection.json")
        fun persist() = selections.write(route, FINGERPRINT, FULL_SELECTION)
        fun writeLiteral() { Files.write(selectionFile, literalSelection().toString().toByteArray(Charsets.UTF_8)) }
        fun assertRejected(state: PreparedRouteState) {
            assertThrows(IllegalStateException::class.java) { commands.dispatch(state, "download") }
            assertThrows(IllegalStateException::class.java) { commands.dispatch(state, "resume") }
        }
        fun assertNoTransferDelegation() {
            assertEquals(0, actions.downloadCalls)
            assertEquals(0, actions.resumeCalls)
            assertTrue(native.created.isEmpty())
            assertTrue(native.resumed.isEmpty())
        }
    }

    /** A complete facade bridge into the real controller, not a replacement policy/allowedActions model. */
    private class Actions(private val controller: OfflineCorridorController) : PreparedMapActions {
        override val pack: OfflinePack? get() = controller.state.value
        var downloadCalls = 0
        var resumeCalls = 0
        var pauseCalls = 0
        var cancelCalls = 0
        var deleteCalls = 0
        override fun download(): Boolean { downloadCalls++; return controller.download() }
        override fun resume(): Boolean { resumeCalls++; return controller.resume() }
        override fun pause(): Boolean { pauseCalls++; return controller.pause() }
        override fun cancel(): Boolean { cancelCalls++; return controller.cancel() }
        override fun delete(): Boolean { deleteCalls++; return controller.delete() }
    }

    /** Only the native SDK boundary is substituted; no SDK, Context, HTTP or device is invoked. */
    private class RecordingNative : OfflineNativePort {
        val created = mutableListOf<OfflinePack>()
        val resumed = mutableListOf<Long>()
        val paused = mutableListOf<Long>()
        val deleted = mutableListOf<Long>()
        override fun create(plan: OfflinePack, events: OfflineRegionEvents) {
            created += plan
            events.created(7L)
        }
        override fun resume(id: Long, events: OfflineRegionEvents) { resumed += id }
        override fun pause(id: Long) { paused += id }
        override fun delete(id: Long) { deleted += id }
    }

    private fun withFixture(block: (Fixture) -> Unit) {
        val configured = System.getProperty("iz.unitTempRoot")
        val requestedRoot = when {
            configured != null -> Paths.get(configured)
            File.separatorChar == '\\' -> Paths.get("D:/CodexBuilds/iz-roadmap-five-20261102/unit-temp")
            else -> Paths.get(System.getProperty("java.io.tmpdir"))
        }.toAbsolutePath().normalize()
        val parent = Files.createDirectories(requestedRoot).toRealPath()
        val directory = Files.createTempDirectory(parent, "prepared-map-commands-").toRealPath()
        check(directory.parent == parent)
        try {
            block(Fixture(directory))
        } finally {
            // Owned flat files only; no recursive delete, symlink following or shared-temp cleanup.
            check(directory.toRealPath().parent == parent)
            Files.newDirectoryStream(directory).use { children ->
                children.forEach { child ->
                    check(child.parent == directory)
                    Files.delete(child)
                }
            }
            Files.delete(directory)
        }
    }

    private companion object {
        const val STEP = 0.0009765625 // Exactly1/1024 degree, so full route vertices are exact binary fixtures.
        val FINGERPRINT = "a".repeat(64)
        val FULL_SELECTION = PreparedCorridorSelection(0.0, 217.18, 100.0)
        val FULL_LINE = listOf(OfflinePoint(0.0, 0.0), OfflinePoint(0.0, STEP), OfflinePoint(STEP, STEP))
        fun makeRoute() = PlannedRoute("selection-route",
            listOf(RouteStop("A", WeatherCoordinate(0.0, 0.0)), RouteStop("C", WeatherCoordinate(STEP, STEP))),
            listOf(RouteVertex(WeatherCoordinate(0.0, 0.0), 0.0),
                RouteVertex(WeatherCoordinate(0.0, STEP), 60.0), RouteVertex(WeatherCoordinate(STEP, STEP), 120.0)),
            217.18, 120.0, 1_000, transport = Transport.MOTORCYCLE,
            maneuvers = listOf(RouteManeuver(15, "Turn left", "Turn left", emptyList(), 1, 2, 60.0, 120.0)))
        fun literalSelection() = JSONObject("""{
            "schema":1, "routeId":"selection-route", "fingerprint":"$FINGERPRINT",
            "fromMeters":0.0, "toMeters":217.18, "halfWidthMeters":100.0
        }""".trimIndent())
    }
}
