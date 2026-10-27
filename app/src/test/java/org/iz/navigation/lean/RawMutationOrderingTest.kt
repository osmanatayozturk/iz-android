package org.iz.navigation.lean

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

class RawMutationOrderingTest {
    @Test fun firstMutationAwaitsReadinessWithoutHoldingInitializerRawLease() = runTest {
        val ready = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val mutation = async {
            LeanStore.withReadyRawMutation({ ready.await() }, { events += "invalidated" }) {
                events += "mutation"
            }
        }
        runCurrent()
        val initializer = async {
            LeanStore.withSnapshotLease {
                events += "initialized"
                ready.complete(Unit)
            }
        }
        withTimeout(1000) { initializer.await(); mutation.await() }
        assertEquals(listOf("initialized", "invalidated", "mutation"), events)
    }

    @Test fun nestedRestoreLeaseRemainsReentrantAfterReadiness() = runTest {
        var completed = false
        withTimeout(1000) {
            LeanStore.withReadyRawMutation({}, {}) {
                LeanStore.withReadyRawMutation({}, {}) {
                    LeanStore.withSnapshotLease { completed = true }
                }
            }
        }
        assertEquals(true, completed)
    }
}