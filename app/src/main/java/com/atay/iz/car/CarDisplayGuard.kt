package com.atay.iz.car

import java.util.concurrent.CancellationException

/** Host output failures are isolated; cancellation still belongs to the coroutine owner. */
internal inline fun safelyUpdateCarDisplay(block: () -> Unit): Boolean = try {
    block()
    true
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: RuntimeException) {
    false
}
