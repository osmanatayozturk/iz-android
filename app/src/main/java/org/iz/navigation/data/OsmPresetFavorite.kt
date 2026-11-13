package org.iz.navigation.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "osm_preset_favorites")
data class OsmPresetFavorite(@PrimaryKey val presetId: String)

const val MAX_OSM_PRESET_FAVORITES = 2048

fun validateOsmPresetFavoriteId(value: String) {
    require(value.matches(Regex("[A-Za-z0-9][A-Za-z0-9_./:+-]{0,499}"))) { "Geçersiz favori tür kimliği." }
}

internal fun validateOsmPresetFavorites(values: List<OsmPresetFavorite>) {
    require(values.size <= MAX_OSM_PRESET_FAVORITES && values.map { it.presetId }.toSet().size == values.size)
    values.forEach { validateOsmPresetFavoriteId(it.presetId) }
}

@Dao
internal interface OsmPresetFavoriteDao {
    @Query("SELECT * FROM osm_preset_favorites ORDER BY presetId") fun observeAll(): Flow<List<OsmPresetFavorite>>
    @Query("SELECT * FROM osm_preset_favorites ORDER BY presetId") suspend fun all(): List<OsmPresetFavorite>
    @Query("SELECT COUNT(*) FROM osm_preset_favorites") suspend fun count(): Int
    @Query("SELECT * FROM osm_preset_favorites WHERE presetId = :id") suspend fun get(id: String): OsmPresetFavorite?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun save(value: OsmPresetFavorite)
    @Query("DELETE FROM osm_preset_favorites WHERE presetId = :id") suspend fun delete(id: String)
    @Query("DELETE FROM osm_preset_favorites") suspend fun clear()
}
