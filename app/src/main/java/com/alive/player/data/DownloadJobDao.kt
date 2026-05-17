package com.alive.player.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface DownloadJobDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(job: DownloadJob)

    @Query("SELECT * FROM download_jobs WHERE asset_key = :key LIMIT 1")
    suspend fun get(key: String): DownloadJob?

    @Query("UPDATE download_jobs SET state=:state, bytes_downloaded=:bytes, error=:err WHERE asset_key=:key")
    suspend fun update(key: String, state: String, bytes: Long, err: String?)

    @Query("DELETE FROM download_jobs WHERE asset_key = :key")
    suspend fun delete(key: String)

    @Query("DELETE FROM download_jobs")
    suspend fun clearAll()
}
