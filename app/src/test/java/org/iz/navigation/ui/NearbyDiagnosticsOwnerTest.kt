package org.iz.navigation.ui

import java.net.UnknownHostException
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class NearbyDiagnosticsOwnerTest {
    @Test fun failedDnsAttemptHasSafeDiagnosticWithoutRetainingRawException() = runTest {
        val secret = "private-account https://secret.invalid/?token=secret 39.12345,32.98765"
        var calls = 0
        val owner = PlaceInformationDiscoveryOwner(this,
            { _, _ -> calls++; throw UnknownHostException(secret) },
            { fail("A failed request must not open information") }, { "entry" })
        owner.tap(0.0, 0.0, 17.0)
        runCurrent()
        assertEquals(1, calls)
        assertTrue(owner.state.retryable)
        assertFalse(owner.state.busy)
        // This assertion can run against the unmodified baseline: RED must be missing
        // diagnosis, rather than an unresolved symbol/compiler failure.
        val getter = owner.state.javaClass.methods.singleOrNull { it.name == "getDiagnostic" }
        assertNotNull("A failed nearby attempt must expose a safe request-scoped diagnostic", getter)
        val diagnostic = requireNotNull(getter).invoke(owner.state)
        assertNotNull(diagnostic)
        assertEquals("ND_DNS", diagnostic.javaClass.getMethod("getCode").invoke(diagnostic).toString())
        assertTrue((diagnostic.javaClass.getMethod("getElapsedMillis").invoke(diagnostic) as Long) >= 0)
        assertFalse(diagnostic.toString().contains(secret))
        assertFalse(diagnostic.javaClass.declaredFields.any { Throwable::class.java.isAssignableFrom(it.type) })
    }
}
