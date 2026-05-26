package com.alive.player.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ProofEventDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(event: ProofEvent)

    // Exclude poison events (fail_count >= 3) and cap batch at 50 to avoid oversized payloads
    @Query("SELECT * FROM proof_events WHERE uploaded = 0 AND fail_count < 3 ORDER BY started_at_epoch_ms ASC LIMIT 50")
    suspend fun getPending(): List<ProofEvent>

    @Query("UPDATE proof_events SET uploaded = 1 WHERE event_id IN (:eventIds)")
    suspend fun markUploaded(eventIds: List<String>)

    @Query("UPDATE proof_events SET fail_count = fail_count + 1 WHERE event_id IN (:eventIds)")
    suspend fun incrementFailCount(eventIds: List<String>)

    @Query("DELETE FROM proof_events WHERE uploaded = 1")
    suspend fun deleteUploaded()

    @Query("DELETE FROM proof_events")
    suspend fun clearAll()
}
