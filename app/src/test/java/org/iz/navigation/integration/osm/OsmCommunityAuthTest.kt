package org.iz.navigation.integration.osm

import org.junit.Assert.*
import org.junit.Test

class OsmCommunityAuthTest {
    private val base = setOf("read_prefs", "write_notes")
    private val messages = setOf("consume_messages", "send_messages")

    @Test fun communityUpgradeRetainsExistingMapContributionPermission() {
        assertEquals(base + messages + "write_api",
            OsmOAuthScopes.requested(base + "write_api", community = true))
    }

    @Test fun normalLoginAndMapUpgradeKeepMessagingGrants() {
        assertEquals(base + messages, OsmOAuthScopes.requested(base + messages))
        assertEquals(base + messages + "write_api",
            OsmOAuthScopes.requested(base + messages, mapEdits = true))
    }

    @Test fun deniedUpgradeKeepsTheSameUsableAccountAndToken() {
        val source = OsmCommunityAuthSource {}
        val old = OsmCredentials("old-token", OsmUser(41, "İz kullanıcı"), null, base)
        source.update(old, 0)
        val first = source.sessions.value!!
        // Starting/declining OAuth invalidates old requests, not the working account.
        source.update(old, 1)
        assertEquals(41L, source.sessions.value!!.userId)
        assertEquals("old-token", source.sessions.value!!.token)
        assertFalse(source.isCurrent(first))
        assertFalse(source.sessions.value!!.scopes.contains("send_messages"))
    }

    @Test fun oldUnauthorizedResponseCannotDisconnectNewAccountOrGrant() {
        var rejections = 0
        val source = OsmCommunityAuthSource { rejections++ }
        val first = OsmCredentials("first", OsmUser(41, "First"), null, base)
        source.update(first, 1)
        val expected = source.sessions.value!!
        source.update(OsmCredentials("second", OsmUser(42, "Second"), null, base + messages), 2)
        source.rejectSession(expected)
        assertEquals(0, rejections)
        source.rejectSession(source.sessions.value!!)
        assertEquals(1, rejections)
        source.update(null, 3)
        assertNull(source.sessions.value)
        source.rejectSession(expected)
        assertEquals(1, rejections)
    }

    @Test fun harmlessUiPublicationDoesNotCreateANewSession() {
        val source = OsmCommunityAuthSource {}
        val account = OsmCredentials("private-token", OsmUser(41, "First"), null, base)
        source.update(account, 1)
        val session = source.sessions.value
        source.update(account, 1)
        assertSame(session, source.sessions.value)
        assertFalse(source.sessions.value.toString().contains("private-token"))
    }
}
