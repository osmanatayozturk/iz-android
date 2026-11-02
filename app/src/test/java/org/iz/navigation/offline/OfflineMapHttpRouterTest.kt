package org.iz.navigation.offline

import java.io.IOException
import okhttp3.Call
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test

class OfflineMapHttpRouterTest {
    @Test fun noActiveJobBlocksProviderAndKeepsOrdinaryMapSeparate() {
        var delegated = 0
        val ordinary = Call.Factory { delegated++; throw IOException("ordinary fixture") }
        val router = OfflineMapHttpRouter(ordinary)
        assertThrows(IOException::class.java) {
            router.newCall(Request.Builder().url(OPEN_FREE_MAP_STYLE).build()).execute()
        }
        assertEquals(0, delegated)
        assertThrows(IOException::class.java) {
            router.newCall(Request.Builder().url("https://tile.openstreetmap.org/0/0/0.png").build())
        }
        assertEquals(1, delegated)
    }
    @Test fun retiringOldLeaseCannotClearNewJobAndNeverRevertsProviderToUnboundedDelegate() {
        var ordinaryCalls = 0; var activeCalls = 0; var retired = 0
        val ordinary = Call.Factory { ordinaryCalls++; throw IOException("ordinary fixture") }
        val bounded = Call.Factory { activeCalls++; throw IOException("bounded fixture") }
        val router = OfflineMapHttpRouter(ordinary)
        val old = router.install(bounded) { retired++ }
        assertThrows(IOException::class.java) { router.newCall(Request.Builder().url(OPEN_FREE_MAP_STYLE).build()) }
        assertEquals(1, activeCalls)
        old.close()
        val fresh = router.install(bounded) { retired++ }
        old.close()
        assertThrows(IOException::class.java) { router.newCall(Request.Builder().url(OPEN_FREE_MAP_STYLE).build()) }
        assertEquals(2, activeCalls)
        fresh.close()
        assertThrows(IOException::class.java) { router.newCall(Request.Builder().url(OPEN_FREE_MAP_STYLE).build()).execute() }
        assertEquals(0, ordinaryCalls)
        assertEquals(2, retired)
    }
}
