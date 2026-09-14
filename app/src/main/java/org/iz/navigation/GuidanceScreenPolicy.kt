package org.iz.navigation

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged

internal suspend fun Lifecycle.keepScreenOnWhileGuiding(
    guidance: Flow<Boolean>,
    setKeepScreenOn: (Boolean) -> Unit,
) {
    repeatOnLifecycle(Lifecycle.State.RESUMED) {
        try {
            guidance.distinctUntilChanged().collect { setKeepScreenOn(it) }
        } finally {
            setKeepScreenOn(false)
        }
    }
}
