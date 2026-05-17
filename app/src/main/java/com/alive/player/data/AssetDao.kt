package com.alive.player.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AssetDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(asset: Asset)

    @Query("SELECT * FROM assets WHERE content_id = :id AND version = :v LIMIT 1")
    suspend fun get(id: String, v: String): Asset?

    @Query("SELECT * FROM assets ORDER BY last_accessed_epoch_ms ASC")
    suspend fun allByLru(): List<Asset>

    @Query("DELETE FROM assets WHERE content_id = :id AND version = :v")
    suspend fun delete(id: String, v: String)

    @Query("UPDATE assets SET last_accessed_epoch_ms = :ts WHERE content_id = :id AND version = :v")
    suspend fun touch(id: String, v: String, ts: Long)

    @Query("DELETE FROM assets")
    suspend fun clearAll()
}
