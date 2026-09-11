package com.atay.iz.osmcommunity

import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.flow.StateFlow

internal enum class CommunityMailbox { INBOX, OUTBOX }

internal data class OsmCommunityProfile(
    val id: Long,
    val displayName: String,
    val description: String = "",
    val imageUrl: String? = null,
    val accountCreatedAt: Long? = null,
    val changesetCount: Int = 0,
    val unreadCount: Int = 0,
)

internal data class OsmMessageSummary(
    val id: Long,
    val fromId: Long,
    val fromName: String,
    val toId: Long,
    val toName: String,
    val title: String,
    val sentAt: Long,
    val read: Boolean,
    val deleted: Boolean = false,
)

internal data class OsmMessageDetail(val summary: OsmMessageSummary, val body: String)

internal data class CommunityPage(val messages: List<OsmMessageSummary>, val nextFromId: Long?)

internal enum class CommunityDraftStatus { DRAFT, SENDING, UNKNOWN, FAILED }

internal data class CommunityDraft(
    val id: String = UUID.randomUUID().toString(),
    val accountId: Long,
    val recipientId: Long? = null,
    val recipientName: String = "",
    val title: String = "",
    val body: String = "",
    val status: CommunityDraftStatus = CommunityDraftStatus.DRAFT,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val error: String? = null,
)

// Deliberately not a data class: generated toString/copy/componentN must not expose credentials.
internal class CommunitySession(
    val token: String,
    val userId: Long,
    val displayName: String,
    scopes: Set<String>,
    val generation: Long,
    val expiresAt: Long? = null,
) {
    val scopes: Set<String> = scopes.toSet()
}

internal interface OsmCommunitySessionSource {
    val sessions: StateFlow<CommunitySession?>
    fun rejectSession(expected: CommunitySession)
}

internal class CommunityHttpException(
    val statusCode: Int,
    val retryAfterMillis: Long? = null,
) : IOException("OpenStreetMap HTTP $statusCode")
