package org.iz.navigation.integration.osm

import org.iz.navigation.osmcommunity.CommunitySession
import org.iz.navigation.osmcommunity.OsmCommunitySessionSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Retain already-granted capabilities through all later OAuth entry points. */
internal object OsmOAuthScopes {
    fun requested(existing: Set<String> = emptySet(), mapEdits: Boolean = false,
                  community: Boolean = false): Set<String> = buildSet {
        addAll(existing)
        addAll(setOf("read_prefs", "write_notes"))
        if (mapEdits) add("write_api")
        if (community) addAll(setOf("consume_messages", "send_messages"))
    }
}

/** Public auth UI can change without dropping the account or its private cache. */
internal class OsmCommunityAuthSource(
    private val onInvalidSession: (CommunitySession) -> Unit,
) : OsmCommunitySessionSource {
    private val mutableSessions = MutableStateFlow<CommunitySession?>(null)
    override val sessions = mutableSessions.asStateFlow()

    fun update(credentials: OsmCredentials?, generation: Long) {
        val previous = mutableSessions.value
        if (credentials == null) {
            mutableSessions.value = null
        } else if (previous?.generation != generation || previous.token != credentials.token ||
            previous.userId != credentials.user.id || previous.scopes != credentials.scopes ||
            previous.displayName != credentials.user.displayName || previous.expiresAt != credentials.expiresAt) {
            mutableSessions.value = CommunitySession(credentials.token, credentials.user.id,
                credentials.user.displayName, credentials.scopes, generation, credentials.expiresAt)
        }
    }

    fun isCurrent(expected: CommunitySession): Boolean = mutableSessions.value?.let {
        it.userId == expected.userId && it.generation == expected.generation && it.token == expected.token
    } == true

    override fun rejectSession(expected: CommunitySession) {
        if (isCurrent(expected)) onInvalidSession(expected)
    }
}
