package org.iz.navigation.ui.backup

import androidx.lifecycle.SavedStateHandle
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.iz.navigation.data.BackupCopyException
import org.iz.navigation.data.BackupCopyPhase
import org.iz.navigation.data.BackupPreparationException
import org.iz.navigation.data.PreparedDiaryBackup
import org.iz.navigation.ui.history.UiAsyncOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BackupExportFlowTest {
    @Test fun waitsForPreparationAndVerificationAndIgnoresDuplicateActions() = runTest {
        val test = Fixture(backgroundScope)
        val prepared = CompletableDeferred<Unit>()
        val verified = CompletableDeferred<Unit>()
        test.operations.beforePrepare = { prepared.await() }
        test.operations.beforeSave = { verified.await() }
        test.operations.privateCleanupSucceeds = false

        assertTrue(test.start(includeHealth = true))
        runCurrent()
        assertEquals(BackupExportPhase.PREPARING, test.flow.phase)
        assertFalse(test.start(includeHealth = false))
        test.openPicker()
        assertTrue(test.launches.isEmpty())

        prepared.complete(Unit)
        runCurrent()
        assertEquals(BackupExportPhase.READY, test.flow.phase)
        test.openPicker()
        test.openPicker()
        assertEquals(BackupExportPhase.PICKER, test.flow.phase)
        assertEquals(listOf(test.owner), test.launches.map { it.first })
        assertEquals(listOf(true), test.operations.healthChoices)

        test.select(NEW_DOCUMENT)
        runCurrent()
        assertEquals(BackupExportPhase.SAVING, test.flow.phase)
        assertEquals(listOf(test.operations.stage to NEW_DOCUMENT), test.operations.writes)
        assertTrue(test.notices.isEmpty())
        assertTrue(test.operations.discards.isEmpty())

        test.select(NEW_DOCUMENT)
        verified.complete(Unit)
        runCurrent()

        assertEquals(listOf(test.operations.stage to NEW_DOCUMENT), test.operations.writes)
        assertEquals(listOf(NEW_DOCUMENT to test.launches.single().second), test.operations.inspections)
        assertEquals(listOf(test.operations.stage), test.operations.discards)
        assertTrue(test.operations.deletions.isEmpty())
        assertEquals(
            listOf(BackupExportNotice(BackupExportResult.SAVED, privateCleanupFailed = true)),
            test.notices.filter { it.result != null },
        )
        assertEquals(BackupExportPhase.IDLE, test.flow.phase)
    }

    @Test fun cancelledPickerDiscardsItsStageAndLateResultCannotTouchRetry() = runTest {
        val test = Fixture(backgroundScope)
        assertTrue(test.start())
        runCurrent()
        test.openPicker()
        test.select(null)
        runCurrent()

        assertEquals(listOf(BackupExportNotice(BackupExportResult.CANCELLED)), test.notices)
        assertEquals(listOf(test.operations.stage), test.operations.discards)
        assertTrue(test.operations.writes.isEmpty())
        assertEquals(BackupExportPhase.IDLE, test.flow.phase)

        val oldOwner = test.owner
        val retryOwner = UiAsyncOwner.capture(oldOwner.entryKey, null)
        assertEquals(oldOwner, retryOwner)
        assertNotSame(oldOwner, retryOwner)
        assertTrue(test.flow.start(test.operations, retryOwner, includeHealth = false))
        runCurrent()
        test.openPicker()
        test.flow.onPickerResult(oldOwner, OLD_DOCUMENT, ownerStillCurrent = true)
        runCurrent()

        assertEquals(BackupExportPhase.PICKER, test.flow.phase)
        assertEquals(BackupExportNotice(destinationMayRemain = true), test.notices.last())
        assertTrue(test.operations.inspections.isEmpty())
        assertTrue(test.operations.writes.isEmpty())
        assertTrue(test.operations.deletions.isEmpty())

        test.flow.onPickerResult(retryOwner, NEW_DOCUMENT, ownerStillCurrent = true)
        runCurrent()

        assertEquals(listOf(NEW_DOCUMENT to test.launches.last().second), test.operations.inspections)
        assertEquals(listOf(test.operations.stage to NEW_DOCUMENT), test.operations.writes)
        assertTrue(test.operations.deletions.isEmpty())
        assertEquals(BackupExportNotice(BackupExportResult.SAVED), test.notices.last())
    }

    @Test fun writeFailureRemovesOnlyTheProvenNewDocument() = runTest {
        assertFailedCopy(BackupCopyPhase.WRITE, BackupExportResult.WRITE_FAILED, deletionSucceeds = true)
    }

    @Test fun verificationFailureWarnsWhenTheOwnedDocumentCouldNotBeRemoved() = runTest {
        assertFailedCopy(BackupCopyPhase.VERIFY, BackupExportResult.VERIFY_FAILED, deletionSucceeds = false)
    }

    @Test fun existingUnprovenDestinationIsNeitherWrittenNorRemoved() = runTest {
        val test = Fixture(backgroundScope)
        assertTrue(test.start())
        runCurrent()
        test.openPicker()
        test.select(OLD_DOCUMENT)
        runCurrent()

        assertEquals(listOf(OLD_DOCUMENT to test.launches.single().second), test.operations.inspections)
        assertTrue(test.operations.writes.isEmpty())
        assertTrue(test.operations.deletions.isEmpty())
        assertEquals(listOf(test.operations.stage), test.operations.discards)
        assertEquals(
            listOf(BackupExportNotice(BackupExportResult.DESTINATION_UNCONFIRMED, destinationMayRemain = true)),
            test.notices,
        )
        assertEquals(BackupExportPhase.IDLE, test.flow.phase)
    }

    @Test fun preparationLimitStopsBeforePickerAndReportsPrivateCleanupFailure() = runTest {
        val test = Fixture(backgroundScope)
        val detail = "Günlük verisi 32 MB sınırını aşıyor."
        test.operations.beforePrepare = { throw BackupPreparationException(true, detail, IOException("limit")) }

        assertTrue(test.start())
        runCurrent()
        test.openPicker()

        assertEquals(
            listOf(BackupExportNotice(
                BackupExportResult.PREPARATION_FAILED, privateCleanupFailed = true, preparationDetail = detail,
            )),
            test.notices,
        )
        assertTrue(test.launches.isEmpty())
        assertTrue(test.operations.inspections.isEmpty())
        assertTrue(test.operations.writes.isEmpty())
        assertTrue(test.operations.discards.isEmpty())
        assertEquals(BackupExportPhase.IDLE, test.flow.phase)
    }

    private fun TestScope.assertFailedCopy(
        phase: BackupCopyPhase,
        expectedResult: BackupExportResult,
        deletionSucceeds: Boolean,
    ) {
        val test = Fixture(backgroundScope)
        test.operations.beforeSave = { throw BackupCopyException(phase, IOException("copy")) }
        test.operations.destinationCleanupSucceeds = deletionSucceeds

        assertTrue(test.start())
        runCurrent()
        test.openPicker()
        test.select(NEW_DOCUMENT)
        runCurrent()

        assertEquals(listOf(NEW_DOCUMENT to test.launches.single().second), test.operations.inspections)
        assertEquals(listOf(test.operations.stage to NEW_DOCUMENT), test.operations.writes)
        assertEquals(listOf(NEW_DOCUMENT), test.operations.deletions)
        assertEquals(listOf(test.operations.stage), test.operations.discards)
        assertEquals(
            listOf(BackupExportNotice(expectedResult, destinationMayRemain = !deletionSucceeds)),
            test.notices,
        )
        assertEquals(BackupExportPhase.IDLE, test.flow.phase)
    }

    private class Fixture(scope: CoroutineScope) {
        val operations = FakeOperations()
        val flow = BackupExportFlow<String>(scope, SavedStateHandle())
        val owner = UiAsyncOwner.capture("settings", null)
        val launches = mutableListOf<Pair<UiAsyncOwner, String>>()
        val notices = mutableListOf<BackupExportNotice>()

        init {
            scope.launch { flow.notices.collect { notices.add(it) } }
        }

        fun start(includeHealth: Boolean = false) = flow.start(operations, owner, includeHealth)

        fun openPicker() {
            flow.openPicker { requestOwner, title ->
                launches.add(requestOwner to title)
                true
            }
        }

        fun select(destination: String?) = flow.onPickerResult(owner, destination, ownerStillCurrent = true)
    }

    private class FakeOperations : BackupExportOperations<String> {
        val stage = PreparedDiaryBackup("31613f15-62f3-4484-a78f-5544ccc17ea9", 64L, "a".repeat(64))
        val healthChoices = mutableListOf<Boolean>()
        val inspections = mutableListOf<Pair<String, String>>()
        val writes = mutableListOf<Pair<PreparedDiaryBackup, String>>()
        val deletions = mutableListOf<String>()
        val discards = mutableListOf<PreparedDiaryBackup>()
        var beforePrepare: suspend () -> Unit = {}
        var beforeSave: suspend () -> Unit = {}
        var destinationCleanupSucceeds = true
        var privateCleanupSucceeds = true

        override suspend fun prepareExport(includeHealth: Boolean): PreparedDiaryBackup {
            healthChoices.add(includeHealth)
            beforePrepare()
            return stage
        }

        override suspend fun inspectDestination(target: String, title: String): (suspend () -> Boolean)? {
            inspections.add(target to title)
            if (target != NEW_DOCUMENT) return null
            return {
                deletions.add(target)
                destinationCleanupSucceeds
            }
        }

        override suspend fun savePrepared(prepared: PreparedDiaryBackup, target: String) {
            writes.add(prepared to target)
            beforeSave()
        }

        override suspend fun discardPrepared(prepared: PreparedDiaryBackup): Boolean {
            discards.add(prepared)
            return privateCleanupSucceeds
        }

        override suspend fun cleanupAbandonedExports() = true
    }

    private companion object {
        const val NEW_DOCUMENT = "new-document"
        const val OLD_DOCUMENT = "old-document"
    }
}
