package org.iz.navigation.ui.history

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue

/** A fresh process ViewModel epoch gives platform registrations fresh saveable keys. Config keeps the epoch/fence. */
@Composable
fun <I, O> rememberUiResultLauncher(contract: ActivityResultContract<I, O>, requests: UiResultRequests,
    launcher: String, captureOwner: () -> UiAsyncOwner?,
    onLaunchFailure: (UiAsyncOwner) -> Unit = {}, onResult: (UiAsyncOwner?, O) -> Unit): UiOwnedResultLauncher<I> {
    val currentCapture by rememberUpdatedState(captureOwner)
    val currentResult by rememberUpdatedState(onResult)
    val currentFailure by rememberUpdatedState(onLaunchFailure)
    val platform = key(requests.epoch, launcher) {
        rememberLauncherForActivityResult(contract) { result ->
            val owner = requests.settle(launcher)
            currentResult(owner, result)
        }
    }
    return UiOwnedResultLauncher { ownerOverride, input, prepare ->
        val current = currentCapture()
        val owner = ownerOverride ?: current
        if (owner == null || owner != current || !requests.begin(launcher, owner)) false
        else try {
            val value = input(owner)
            prepare(owner)
            platform.launch(value)
            true
        } catch (error: Exception) {
            requests.abort(launcher, owner)
            currentFailure(owner)
            throw error
        }
    }
}

class UiOwnedResultLauncher<I> internal constructor(private val launchOwned: (UiAsyncOwner?, (UiAsyncOwner) -> I, (UiAsyncOwner) -> Unit) -> Boolean) {
    fun launch(input: I, prepare: (UiAsyncOwner) -> Unit = {}): Boolean = launchOwned(null, { input }, prepare)
    fun launchWithOwner(owner: UiAsyncOwner, input: I, prepare: (UiAsyncOwner) -> Unit = {}): Boolean = launchOwned(owner, { input }, prepare)
    fun launchPrepared(input: (UiAsyncOwner) -> I): Boolean = launchOwned(null, input, {})
}
