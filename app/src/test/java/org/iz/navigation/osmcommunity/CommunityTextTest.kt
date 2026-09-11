package org.iz.navigation.osmcommunity

import org.junit.Assert.*
import org.junit.Test

class CommunityTextTest {
    @Test fun fullUnicodeTitlesUseCharactersRatherThanUtf16Units() {
        assertEquals(255, CommunityText.titleLength("🚲".repeat(255)))
        assertEquals("🚲".repeat(255), CommunityText.truncateTitle("🚲".repeat(256)))
    }

    @Test fun replyPrefixTruncationKeepsTurkishAndSurrogatePairsIntact() {
        val title = "Re: Çığ şüş İstanbul " + "🚲".repeat(255)
        val result = CommunityText.truncateTitle(title)
        assertTrue(result.startsWith("Re: Çığ şüş İstanbul "))
        assertEquals(255, CommunityText.titleLength(result))
        assertFalse(Character.isHighSurrogate(result.last()))
        assertEquals("Kısa konu", CommunityText.truncateTitle("Kısa konu"))
    }
}
