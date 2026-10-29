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
}
