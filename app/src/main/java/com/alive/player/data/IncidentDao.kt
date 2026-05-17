package com.alive.player.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface IncidentDao {
    @Insert
    suspend fun insert(incident: Incident)

    @Query("SELECT * FROM incidents ORDER BY timestamp_utc_epoch_ms DESC LIMIT 50")
    suspend fun recent(): List<Incident>

    @Query("DELETE FROM incidents")
    suspend fun clearAll()
}
