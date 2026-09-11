package com.atay.iz.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
internal interface MapEditDao {
    @Query("SELECT * FROM map_edit_drafts WHERE id = :id") suspend fun read(id: String): MapEditDraft?
    @Query("SELECT * FROM map_edit_drafts ORDER BY observedAt DESC") fun observeAll(): Flow<List<MapEditDraft>>
    @Query("SELECT * FROM map_edit_drafts") suspend fun all(): List<MapEditDraft>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(value: MapEditDraft)
    @Query("DELETE FROM map_edit_drafts WHERE id = :id") suspend fun delete(id: String)
    @Query("DELETE FROM map_edit_drafts") suspend fun clear()
    @Query("UPDATE map_edit_drafts SET status = 'UNKNOWN', error = 'Gönderim yarıda kaldı. OSM sonucunu kontrol et.' WHERE status = 'SENDING'")
    suspend fun recoverInterrupted(): Int
}
