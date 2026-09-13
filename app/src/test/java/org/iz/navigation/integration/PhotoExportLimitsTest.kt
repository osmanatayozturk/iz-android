package org.iz.navigation.integration

import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class PhotoExportLimitsTest {
    @Test fun readableSizesAreNotUpscaled() {
        assertEquals(32 to 24, photoExportSize(32, 24))
        assertEquals(1 to 1, photoExportSize(1, 1))
    }

    @Test fun largeAndExtremeDimensionsStayWithinAllocationBounds() {
        listOf(8000 to 6000, 6000 to 8000, Int.MAX_VALUE to Int.MAX_VALUE,
            Int.MAX_VALUE to 1, 1 to Int.MAX_VALUE).forEach { (width, height) ->
            val (w, h) = photoExportSize(width, height)
            assertTrue(w in 1..4096 && h in 1..4096)
            assertTrue(w.toLong() * h <= 8_000_000L)
            assertTrue(w <= width && h <= height)
        }
        val (width, height) = photoExportSize(8000, 6000)
        assertEquals(4.0 / 3.0, width.toDouble() / height, 0.002)
    }

    @Test fun invalidDimensionsAreRejected() {
        listOf(0 to 1, 1 to 0, -1 to 10).forEach { (w, h) ->
            assertThrows(IllegalArgumentException::class.java) { photoExportSize(w, h) }
        }
    }

    @Test fun outputBudgetRejectsBeforeWritingExcessBytes() {
        val bytes = ByteArrayOutputStream()
        val output = PhotoExportOutputStream(bytes, 4) {}
        output.write(byteArrayOf(1, 2, 3))
        output.write(4)
        assertThrows(IOException::class.java) { output.write(5) }
        assertThrows(IOException::class.java) { output.write(byteArrayOf(6, 7)) }
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), bytes.toByteArray())
        assertEquals(4L, output.bytesWritten)
    }

    @Test fun cancellationDoesNotWriteAnotherChunk() {
        val bytes = ByteArrayOutputStream()
        val output = PhotoExportOutputStream(bytes, 1024) { throw CancellationException("cancelled") }
        assertThrows(CancellationException::class.java) { output.write(byteArrayOf(1, 2, 3)) }
        assertEquals(0, bytes.size())
    }
}
