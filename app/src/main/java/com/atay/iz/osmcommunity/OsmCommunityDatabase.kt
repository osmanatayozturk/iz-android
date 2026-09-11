package com.atay.iz.osmcommunity

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import java.io.File
import java.util.UUID

@Entity(tableName = "community_messages", primaryKeys = ["accountId", "mailbox", "id"])
internal data class CommunityMessageEntity(
    val accountId: Long, val mailbox: String, @Embedded val summary: OsmMessageSummary,
)

@Entity(tableName = "community_details", primaryKeys = ["accountId", "id"])
internal data class CommunityDetailEntity(
    val accountId: Long, @Embedded val summary: OsmMessageSummary, val body: String,
) {
    fun model() = OsmMessageDetail(summary, body)
}

@Entity(tableName = "community_profiles", primaryKeys = ["accountId", "id"])
internal data class CommunityProfileEntity(val accountId: Long, @Embedded val profile: OsmCommunityProfile)

@Entity(tableName = "community_drafts", primaryKeys = ["accountId", "id"])
internal data class CommunityDraftEntity(@Embedded val draft: CommunityDraft)

@Entity(tableName = "community_accounts", primaryKeys = ["accountId"])
internal data class CommunityAccountEntity(
    val accountId: Long,
    val notificationsEnabled: Boolean = true,
    val baselineReady: Boolean = false,
    val inboxWatermark: Long = 0,
    val nextInboxFromId: Long? = null,
    val nextOutboxFromId: Long? = null,
    val retryNotBefore: Long = 0,
    val cacheIdentity: String = UUID.randomUUID().toString(),
)

@Entity(tableName = "community_notified", primaryKeys = ["accountId", "messageId"])
internal data class CommunityNotifiedEntity(val accountId: Long, val messageId: Long)

@Entity(tableName = "community_retired_drafts", primaryKeys = ["accountId", "draftId"])
internal data class CommunityRetiredDraftEntity(val accountId: Long, val draftId: String)

internal class CommunityConverters {
    @TypeConverter fun status(value: String): CommunityDraftStatus = CommunityDraftStatus.valueOf(value)
    @TypeConverter fun status(value: CommunityDraftStatus): String = value.name
}

@Dao
internal interface CommunityDao {
    @Query("SELECT * FROM community_accounts") suspend fun accounts(): List<CommunityAccountEntity>
    @Query("SELECT * FROM community_accounts WHERE accountId = :accountId") suspend fun account(accountId: Long): CommunityAccountEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putAccount(value: CommunityAccountEntity)
    @Query("SELECT * FROM community_messages WHERE accountId = :accountId AND mailbox = :mailbox AND deleted = 0 ORDER BY id DESC")
    suspend fun messages(accountId: Long, mailbox: String): List<CommunityMessageEntity>
    @Query("SELECT * FROM community_messages WHERE accountId = :accountId AND mailbox = :mailbox AND id = :id")
    suspend fun message(accountId: Long, mailbox: String, id: Long): CommunityMessageEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putMessage(value: CommunityMessageEntity)
    @Query("DELETE FROM community_messages WHERE accountId = :accountId AND id = :id") suspend fun removeMessage(accountId: Long, id: Long)
    @Query("SELECT * FROM community_details WHERE accountId = :accountId AND id = :id") suspend fun detail(accountId: Long, id: Long): CommunityDetailEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putDetail(value: CommunityDetailEntity)
    @Query("DELETE FROM community_details WHERE accountId = :accountId AND id = :id") suspend fun removeDetail(accountId: Long, id: Long)
    @Query("SELECT * FROM community_profiles WHERE accountId = :accountId ORDER BY displayName COLLATE NOCASE")
    suspend fun profiles(accountId: Long): List<CommunityProfileEntity>
    @Query("SELECT * FROM community_profiles WHERE accountId = :accountId AND id = :id") suspend fun profile(accountId: Long, id: Long): CommunityProfileEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putProfile(value: CommunityProfileEntity)
    @Query("DELETE FROM community_profiles WHERE accountId = :accountId AND id = :id") suspend fun removeProfile(accountId: Long, id: Long)
    @Query("SELECT * FROM community_drafts WHERE accountId = :accountId ORDER BY updatedAt DESC") suspend fun drafts(accountId: Long): List<CommunityDraftEntity>
    @Query("SELECT * FROM community_drafts WHERE accountId = :accountId AND id = :id") suspend fun draft(accountId: Long, id: String): CommunityDraftEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putDraft(value: CommunityDraftEntity)
    @Query("DELETE FROM community_drafts WHERE accountId = :accountId AND id = :id") suspend fun removeDraft(accountId: Long, id: String)
    @Query("UPDATE community_drafts SET status = 'UNKNOWN', error = :error WHERE status = 'SENDING'") suspend fun recoverInterrupted(error: String)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun claimNotification(value: CommunityNotifiedEntity): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun retireDraft(value: CommunityRetiredDraftEntity)
    @Query("SELECT EXISTS(SELECT 1 FROM community_retired_drafts WHERE accountId = :accountId AND draftId = :draftId)")
    suspend fun isDraftRetired(accountId: Long, draftId: String): Boolean
    @Query("DELETE FROM community_messages") suspend fun clearMessages()
    @Query("DELETE FROM community_details") suspend fun clearDetails()
    @Query("DELETE FROM community_profiles") suspend fun clearProfiles()
    @Query("DELETE FROM community_drafts") suspend fun clearDrafts()
    @Query("DELETE FROM community_accounts") suspend fun clearAccounts()
    @Query("DELETE FROM community_notified") suspend fun clearNotifications()
    @Query("DELETE FROM community_retired_drafts") suspend fun clearRetiredDrafts()
}

@Database(entities = [CommunityMessageEntity::class, CommunityDetailEntity::class,
    CommunityProfileEntity::class, CommunityDraftEntity::class, CommunityAccountEntity::class,
    CommunityNotifiedEntity::class, CommunityRetiredDraftEntity::class], version = 1, exportSchema = true)
@TypeConverters(CommunityConverters::class)
internal abstract class OsmCommunityDatabase : RoomDatabase() {
    abstract fun communityDao(): CommunityDao

    companion object {
        fun open(context: Context): OsmCommunityDatabase = Room.databaseBuilder(context.applicationContext,
            OsmCommunityDatabase::class.java, File(context.noBackupFilesDir, "osm-community.db").absolutePath)
            .build()
    }
}
