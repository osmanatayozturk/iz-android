package org.iz.navigation.offline

import java.io.IOException
import okhttp3.Call
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test

class OfflineLocalRouterTest {
    @Test fun reservedOriginNeverEscapesThroughOrdinaryOrPublicDownloadFactory() {
        var ordinary = 0; var publicDownload = 0
        val router = OfflineMapHttpRouter(Call.Factory { ordinary++; throw IOException("ordinary fixture") })
        val request = Request.Builder().url("https://iz-offline.invalid/" + "a".repeat(64) + "/style.json").build()
        assertThrows(IOException::class.java) { router.newCall(request).execute() }
        assertEquals("Reserved local origin must never reach ordinary transport", 0, ordinary)
        router.install(Call.Factory { publicDownload++; throw IOException("public fixture") }) {}.use {
            assertThrows(IOException::class.java) { router.newCall(request).execute() }
        }
        assertEquals("Reserved local origin must never reach public download transport", 0, publicDownload)
    }
}
