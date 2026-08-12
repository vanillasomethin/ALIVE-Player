package com.alive.player.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "plan_cache")
data class PlanCache(
    @PrimaryKey val id: Long = 1,
    @ColumnInfo(name = "plan_json") val planJson: String,
    @ColumnInfo(name = "etag") val etag: String?,
    @ColumnInfo(name = "fetched_at_epoch_ms") val fetchedAtEpochMs: Long,
)

@Entity(
    tableName = "assets",
    indices = [Index(value = ["content_id", "version"], unique = true)],
)
data class Asset(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "content_id") val contentId: String,
    @ColumnInfo(name = "version") val version: String,
    @ColumnInfo(name = "sha256") val sha256: String,
    @ColumnInfo(name = "path") val path: String,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "last_accessed_epoch_ms") val lastAccessedEpochMs: Long,
)

@Entity(
    tableName = "download_jobs",
    indices = [Index(value = ["asset_key"], unique = true)],
)
data class DownloadJob(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "asset_key") val assetKey: String,
    @ColumnInfo(name = "state") val state: String,
    @ColumnInfo(name = "retries") val retries: Int,
    @ColumnInfo(name = "bytes_downloaded") val bytesDownloaded: Long,
    // Expected total size from Content-Length; 0 until the download connects.
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long = 0L,
    @ColumnInfo(name = "error") val error: String?,
)

@Entity(
    tableName = "proof_events",
    indices = [Index(value = ["event_id"], unique = true)],
)
data class ProofEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "event_id") val eventId: String,
    @ColumnInfo(name = "media_id") val mediaId: String,
    @ColumnInfo(name = "schedule_id") val scheduleId: String?,
    @ColumnInfo(name = "started_at_epoch_ms") val startedAtEpochMs: Long,
    @ColumnInfo(name = "ended_at_epoch_ms") val endedAtEpochMs: Long,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    @ColumnInfo(name = "uploaded") val uploaded: Boolean = false,
    @ColumnInfo(name = "fail_count") val failCount: Int = 0,
    // Slot-loop attribution, echoed from the plan item that played (slot-mode stores
    // only): which loop position, and whether this was a bonus/filler play rather than
    // a guaranteed booked one. Null/false for ordinary schedule playback.
    @ColumnInfo(name = "slot_position") val slotPosition: Int? = null,
    @ColumnInfo(name = "is_filler") val isFiller: Boolean = false,
)

@Entity(tableName = "incidents")
data class Incident(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "type") val type: String,
    @ColumnInfo(name = "timestamp_utc_epoch_ms") val timestampUtcEpochMs: Long,
    @ColumnInfo(name = "metadata_json") val metadataJson: String?,
)
