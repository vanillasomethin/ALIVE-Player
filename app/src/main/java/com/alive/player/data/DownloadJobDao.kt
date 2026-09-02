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

    @Query("UPDATE download_jobs SET size_bytes=:sizeBytes WHERE asset_key=:key")
    suspend fun updateSize(key: String, sizeBytes: Long)

    /** Terminal exit for a permanently-failed item — a FAILED row left behind
     *  would block [clearIfAllDone] for every future sync. */
    @Query("DELETE FROM download_jobs WHERE asset_key = :key")
    suspend fun delete(key: String)

    @Query("DELETE FROM download_jobs")
    suspend fun clearAll()

    /** Retires a finished batch: deletes every row iff none is still undone.
     *  One atomic statement so a concurrent RUNNING insert can never be swept —
     *  it either lands before this runs (and blocks it) or after (and survives). */
    @Query("DELETE FROM download_jobs WHERE NOT EXISTS (SELECT 1 FROM download_jobs WHERE state != 'DONE')")
    suspend fun clearIfAllDone()

    @Query("SELECT COUNT(*) FROM download_jobs WHERE state = 'DONE'")
    suspend fun doneCount(): Int

    @Query("SELECT COUNT(*) FROM download_jobs")
    suspend fun totalCount(): Int

    @Query("SELECT COALESCE(SUM(bytes_downloaded), 0) FROM download_jobs WHERE state = 'DONE'")
    suspend fun doneBytesSum(): Long

    @Query("SELECT COALESCE(SUM(size_bytes), 0) FROM download_jobs")
    suspend fun totalBytesSum(): Long
}
