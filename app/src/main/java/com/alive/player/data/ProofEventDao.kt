package com.alive.player.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ProofEventDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(event: ProofEvent)

    @Query("SELECT * FROM proof_events WHERE uploaded = 0 ORDER BY started_at_epoch_ms ASC LIMIT 200")
    suspend fun getPending(): List<ProofEvent>

    @Query("UPDATE proof_events SET uploaded = 1 WHERE event_id IN (:eventIds)")
    suspend fun markUploaded(eventIds: List<String>)

    @Query("DELETE FROM proof_events WHERE uploaded = 1")
    suspend fun deleteUploaded()

    @Query("DELETE FROM proof_events")
    suspend fun clearAll()
}
