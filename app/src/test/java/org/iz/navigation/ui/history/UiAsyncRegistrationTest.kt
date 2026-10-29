package org.iz.navigation.ui.history

import org.junit.Assert.*
import org.junit.Test

class UiAsyncRegistrationTest {
    @Test fun samePublicIdsFromNewEditorRegistrationCannotAcceptOldOsResult() {
        val guard = UiExitGuard(); val old = Any(); val replacement = Any()
        val identical = UiEditState("same-editor", "account", 2, 1, true, false)
        guard.updateOwned(old, identical)
        val result = guard.captureAsyncOwner("same-page")
        guard.updateOwned(replacement, identical)
        assertFalse("Field equality must not authorize a result owned by a disposed/replaced registration",
            guard.acceptsAsyncOwner(result, "same-page"))
    }
    @Test fun configurationReattachKeepsResultIdentityButReplacementAndFreshProcessRejectIt() {
        val registrations = UiOwnerRegistrations()
        val key = UiOwnerRegistrations.Key("same-page", "same-page/ui", "account")
        val state = UiEditState(key.editorKey, key.accountKey, 0, 0, false, false)
        val oldGuard = UiExitGuard()
        val oldToken = registrations.acquire(key)
        oldGuard.updateOwned(oldToken, state)
        val pending = requireNotNull(oldGuard.captureAsyncOwner(key.entryKey))
        val requests = UiResultRequests()
        assertTrue(requests.begin("picker", pending))

        oldGuard.clearOwner(oldToken)
        registrations.release(key, oldToken, changingConfigurations = true)
        val recreatedGuard = UiExitGuard()
        val reattachedToken = registrations.acquire(key)
        recreatedGuard.updateOwned(reattachedToken, state)
        assertSame("Only a configuration detach can hand off the runtime identity", oldToken, reattachedToken)
        assertTrue(recreatedGuard.acceptsAsyncOwner(requests.settle("picker"), key.entryKey))

        recreatedGuard.clearOwner(reattachedToken)
        registrations.release(key, reattachedToken, changingConfigurations = false)
        val replacementToken = registrations.acquire(key)
        recreatedGuard.updateOwned(replacementToken, state)
        assertNotSame(reattachedToken, replacementToken)
        assertFalse("Same public IDs after actual removal must not revive the old result",
            recreatedGuard.acceptsAsyncOwner(pending, key.entryKey))
        val freshProcess = UiOwnerRegistrations()
        val freshGuard = UiExitGuard()
        freshGuard.updateOwned(freshProcess.acquire(key), state)
        assertFalse("No runtime owner identity crosses process recreation", freshGuard.acceptsAsyncOwner(pending, key.entryKey))
    }
}
