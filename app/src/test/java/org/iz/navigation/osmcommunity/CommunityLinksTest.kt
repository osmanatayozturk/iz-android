package org.iz.navigation.osmcommunity

import org.junit.Assert.*
import org.junit.Test

class CommunityLinksTest {
    @Test fun displayNameIsOneEncodedPathSegmentOnOfficialSite() {
        assertEquals("https://www.openstreetmap.org/user/A%20B%2F%C3%87%3F%23%25", CommunityLinks.profile("A B/Ç?#%"))
        assertEquals("https://www.openstreetmap.org/user/A%2FB/follow", CommunityLinks.follow("A/B"))
        assertEquals("https://www.openstreetmap.org/user/Mapper", CommunityLinks.mute("Mapper"))
    }

    @Test fun officialAccountActionsOpenKnownWebPages() {
        assertEquals("https://www.openstreetmap.org/dashboard", CommunityLinks.following())
        assertEquals("https://www.openstreetmap.org/profile/description", CommunityLinks.editProfile())
        assertEquals("https://www.openstreetmap.org/messages/muted", CommunityLinks.mutedInbox())
    }

    @Test fun externalLinksAcceptOnlyExplicitHttpOrHttpsWithoutCredentialsOrControlCharacters() {
        assertEquals("https://example.org/path?q=1#part", CommunityLinks.safeExternalUrl("https://example.org/path?q=1#part"))
        assertEquals("http://example.org/", CommunityLinks.safeExternalUrl("http://example.org"))
        for (value in listOf("javascript:alert(1)", "intent://example.org", "content://private", "file:///sdcard/a",
            "data:text/html,test", "//example.org", "https://u:p@example.org", "https://example.org\n.evil")) {
            assertNull(value, CommunityLinks.safeExternalUrl(value))
        }
    }

    @Test fun imagesRequireHttpsAndNoCredentials() {
        assertEquals("https://example.org/a.png", CommunityLinks.safeImageUrl("https://example.org/a.png"))
        assertNull(CommunityLinks.safeImageUrl("http://example.org/a.png"))
        assertNull(CommunityLinks.safeImageUrl("https://u:p@example.org/a.png"))
        assertNull(CommunityLinks.safeImageUrl(null))
    }

    @Test fun dotSegmentsCannotEscapeTheProfilePath() {
        for (name in listOf(".", "..")) {
            try { CommunityLinks.profile(name); fail("Dot segment accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
