package com.atay.iz.osmcommunity

internal interface OsmCommunityGateway {
    suspend fun profile(token: String?, userId: Long? = null): OsmCommunityProfile
    suspend fun mailbox(token: String, box: CommunityMailbox, fromId: Long? = null): CommunityPage
    suspend fun message(token: String, id: Long): OsmMessageDetail
    suspend fun send(token: String, recipientId: Long?, recipientName: String, title: String, body: String): OsmMessageDetail
    suspend fun markRead(token: String, id: Long, read: Boolean)
    suspend fun delete(token: String, id: Long)
}
