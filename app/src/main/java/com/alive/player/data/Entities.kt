package com.alive.player.data

data class PlanCache(
    val id: Long = 0,
    val planJson: String,
    val etag: String?,
    val fetchedAtEpochMs: Long,
)

data class Asset(
    val id: Long = 0,
    val contentId: String,
    val version: String,
    val sha256: String,
    val path: String,
    val sizeBytes: Long,
    val lastAccessedEpochMs: Long,
)

data class DownloadJob(
    val id: Long = 0,
    val assetKey: String,
    val state: String,
    val retries: Int,
    val bytesDownloaded: Long,
    val error: String?,
)

data class ProofEvent(
    val id: Long = 0,
    val eventId: String,
    val contentVersionId: String,
    val type: String,
    val timestampUtcEpochMs: Long,
    val durationMs: Long?,
    val sessionId: String,
)

data class Incident(
    val id: Long = 0,
    val type: String,
    val timestampUtcEpochMs: Long,
    val metadataJson: String?,
)
