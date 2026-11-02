package org.iz.navigation.wearprotocol

import org.junit.Assert.*
import org.junit.Test

class WearAdvisoryProtocolTest {
    private fun snapshot()=WearSnapshot(generatedAt=1_000,statsDeferred=true,statisticsStatus="prepared later",
        navigation=WearNavigationSummary("session",true,900,false,false,false,false,false,10,"turn",30.0,900.0,2_000,"end"),
        advisory=WearGuidanceAdvisory("session",paused=true,preparedRoute=true,
            map=WearMapAvailability.PARTIAL,voice=WearVoiceAvailability.NETWORK_REQUIRED))
    @Test fun newAdvisoryRoundTripsWithoutBecomingMeasurementsOrChangingNavigation() {
        val input=snapshot()
        val encoded=WearProtocol.encodeSnapshot(input,7)
        assertEquals(input,WearProtocol.decodeSnapshot(encoded))
        assertEquals(7,WearProtocol.frameVersion(encoded))
        assertTrue(WearProtocol.decodeSnapshot(encoded)!!.statsDeferred)
    }
    @Test fun exactV6DowngradeKeepsDeferredStateAndOmitsNewAdvisory() {
        val input=snapshot()
        val v6=WearProtocol.encodeSnapshot(input,6)
        assertEquals(input.copy(advisory=null),WearProtocol.decodeSnapshot(v6))
        assertArrayEquals(v6,WearProtocol.encodeSnapshot(input.copy(advisory=null),6))
    }
    @Test fun mismatchedSessionAdvisoryCannotDescribeReplacementNavigation() {
        try {
            WearProtocol.encodeSnapshot(snapshot().copy(advisory=WearGuidanceAdvisory("old-session")),7)
            fail("mismatched advisory accepted")
        } catch (_: IllegalArgumentException) { }
    }
    @Test fun oldCurrentSnapshotRemainsRepresentableOnNewChannel() {
        val input=snapshot().copy(advisory=null)
        assertEquals(input,WearProtocol.decodeSnapshot(WearProtocol.encodeSnapshot(input,7)))
    }
    @Test fun futureVersionAndTrailingBytesAreRejected() {
        val bytes=WearProtocol.encodeSnapshot(snapshot(),6)
        assertNull(WearProtocol.decodeSnapshot(bytes+byteArrayOf(1)))
        bytes[4]=8
        assertNull(WearProtocol.decodeSnapshot(bytes))
    }
}