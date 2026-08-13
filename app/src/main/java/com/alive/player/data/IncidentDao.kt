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

    /** Oldest-first batch for heartbeat upload — FIFO so a backlog drains in order. */
    @Query("SELECT * FROM incidents ORDER BY timestamp_utc_epoch_ms ASC LIMIT :limit")
    suspend fun oldestBatch(limit: Int): List<Incident>

    /** Rows are deleted once the server acknowledges them (no `uploaded` column — a Room
     *  version bump here would destructively wipe the whole DB, incl. the PoP backlog). */
    @Query("DELETE FROM incidents WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM incidents")
    suspend fun clearAll()
}
