package com.atay.iz.ui

import android.app.Application
import android.os.Bundle
import android.os.Parcel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.atay.iz.osmcommunity.*
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OsmCommunityLifecycleTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: OsmCommunityDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var repository: OsmCommunityRepository
    private val sessions = FakeCommunitySessions()
    private val stores = mutableListOf<ViewModelStore>()

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(app, OsmCommunityDatabase::class.java).build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        repository = OsmCommunityRepository(app, sessions, FakeCommunityGateway(), db,
            RecordingCommunitySink(), RecordingCommunityScheduler(), scope = scope)
        repository.initialize()
    }

    @After fun tearDown() = runBlocking {
        withContext(Dispatchers.Main) { stores.forEach { it.clear() } }
        scope.cancel()
        db.close()
    }

    private suspend fun vm(handle: SavedStateHandle = SavedStateHandle()): Pair<OsmCommunityViewModel, ViewModelStore> = withContext(Dispatchers.Main) {
        val store = ViewModelStore().also { stores += it }
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = OsmCommunityViewModel(app, handle, repository) as T
        }
        ViewModelProvider(store, factory)[OsmCommunityViewModel::class.java] to store
    }

    private suspend fun awaitBody(id: String, body: String) = withTimeout(5_000) {
        while (db.communityDao().draft(1, id)?.draft?.body != body) delay(20)
    }

    @Test fun coldNotificationWaitsForRoomHydrationBeforeOpeningMessage() = runBlocking {
        scope.cancel()
        val locked = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val gate = launch(Dispatchers.IO) { db.withTransaction { locked.complete(Unit); release.await() } }
        locked.await()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        repository = OsmCommunityRepository(app, sessions, FakeCommunityGateway(), db,
            RecordingCommunitySink(), RecordingCommunityScheduler(), scope = scope)
        repository.initialize()
        val (model, _) = vm()
        try {
            assertFalse(repository.state.value.ready)
            withContext(Dispatchers.Main) { model.openNotification(31, 1) }
            assertNull(model.ui.value.detail)
        } finally { release.complete(Unit) }
        gate.join()
        withTimeout(5_000) { while (model.ui.value.detail?.summary?.id != 31L) delay(20) }
        assertEquals(1L, model.ui.value.accountId)
    }

    @Test fun finalSaveSurvivesViewModelClearWhileRoomIsBusy() = runBlocking {
        val draft = CommunityDraft(id = "finish", accountId = 1, recipientId = 2, title = "Konu", body = "old")
        repository.saveDraft(draft)
        val (model, store) = vm()
        withContext(Dispatchers.Main) { model.editDraft(draft) }
        val locked = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val gate = launch(Dispatchers.IO) { db.withTransaction { locked.complete(Unit); release.await() } }
        locked.await()
        try {
            withContext(Dispatchers.Main) { model.changeDraft(draft.copy(body = "latest")); model.persistEditor(); store.clear() }
        } finally { release.complete(Unit) }
        gate.join()
        awaitBody("finish", "latest")
    }

    @Test fun switchingToAnotherDraftDoesNotCancelPreviousFinalSave() = runBlocking {
        val first = CommunityDraft(id = "first", accountId = 1, title = "A", body = "old A")
        val second = CommunityDraft(id = "second", accountId = 1, title = "B", body = "old B")
        repository.saveDraft(first); repository.saveDraft(second)
        val (model, _) = vm()
        withContext(Dispatchers.Main) { model.editDraft(first) }
        val locked = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val gate = launch(Dispatchers.IO) { db.withTransaction { locked.complete(Unit); release.await() } }
        locked.await()
        try {
            withContext(Dispatchers.Main) {
                model.changeDraft(first.copy(body = "latest A")); model.folder(CommunityFolder.DRAFTS)
                model.editDraft(second); model.changeDraft(second.copy(body = "latest B")); model.persistEditor()
            }
        } finally { release.complete(Unit) }
        gate.join()
        awaitBody("first", "latest A"); awaitBody("second", "latest B")
    }

    @Test fun savedStateRestoresLastDebouncedEditAfterRecreation() = runBlocking {
        val draft = CommunityDraft(id = "restore", accountId = 1, title = "Konu", body = "old")
        repository.saveDraft(draft)
        val handle = SavedStateHandle()
        val (model, store) = vm(handle)
        val saved = withContext(Dispatchers.Main) {
            model.editDraft(draft); model.changeDraft(draft.copy(body = "last keystroke"))
            handle.keys().associateWith { handle.get<Any>(it) }.also { store.clear() }
        }
        val (restored, _) = vm(SavedStateHandle(saved))
        withTimeout(5_000) { while (restored.ui.value.editor?.body != "last keystroke") delay(20) }
        awaitBody("restore", "last keystroke")
    }

    @Test fun largeDraftKeepsSavedStateBoundedAndRestoresExactBodyFromRoom() = runBlocking {
        val draft = CommunityDraft(id = "large", accountId = 1, title = "Uzun mesaj", body = "old")
        val fullBody = "b".repeat(700_000)
        repository.saveDraft(draft)
        val handle = SavedStateHandle()
        val (model, store) = vm(handle)
        val saved = withContext(Dispatchers.Main) {
            model.editDraft(draft); model.changeDraft(draft.copy(body = fullBody))
            handle.keys().associateWith { handle.get<Any>(it) }
        }
        val payload = saved.values.filterIsInstance<Bundle>().single()
        val parcel = Parcel.obtain()
        try {
            parcel.writeBundle(payload)
            assertTrue("Large draft must not approach Android's saved-state transaction limit", parcel.dataSize() < 96 * 1024)
        } finally { parcel.recycle() }
        awaitBody("large", fullBody)
        withContext(Dispatchers.Main) { store.clear() }
        val (restored, _) = vm(SavedStateHandle(saved))
        withTimeout(5_000) { while (restored.ui.value.editor?.body != fullBody) delay(20) }
        assertEquals(fullBody, restored.ui.value.editor?.body)
    }

    @Test fun savedStateFromPreviousLoginCannotRestoreAfterSameAccountReconnect() = runBlocking {
        val draft = CommunityDraft(id = "old-login", accountId = 1, title = "Konu", body = "private")
        repository.saveDraft(draft)
        val handle = SavedStateHandle()
        val (model, store) = vm(handle)
        val saved = withContext(Dispatchers.Main) {
            model.editDraft(draft)
            handle.keys().associateWith { handle.get<Any>(it) }.also { store.clear() }
        }
        sessions.sessions.value = null
        sessions.sessions.value = session(1, 3)
        repository.setNotificationsEnabled(false)
        val (restored, _) = vm(SavedStateHandle(saved))
        withContext(Dispatchers.Main) { yield() }
        assertNull(restored.ui.value.editor)
        assertTrue(db.communityDao().drafts(1).isEmpty())
    }
}
