package org.iz.navigation.osmcommunity

import org.junit.Assert.*
import org.junit.Test

class CommunitySessionTest {
    @Test fun sessionToStringCannotExposeCredentialsOrIdentity() {
        val session = CommunitySession("secret-token", 41, "private-display-name", setOf("consume_messages"), 1)
        assertFalse(session.toString().contains("secret-token"))
        assertFalse(session.toString().contains("private-display-name"))
    }

    @Test fun laterScopeSetMutationCannotChangeAnExistingSessionGrant() {
        val scopes = mutableSetOf("consume_messages")
        val session = CommunitySession("token", 41, "Mapper", scopes, 1)
        scopes.add("send_messages")
        assertEquals(setOf("consume_messages"), session.scopes)
    }
}
