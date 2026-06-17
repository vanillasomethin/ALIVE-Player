package com.alive.player.network

data class ClaimDeviceResponse(val deviceId: String, val token: String, val pairingCode: String)

data class StudioPlanItem(
    val contentId: String,
    val objectKey: String,
    val url: String,
    val md5: String,
    val type: String,       // "IMAGE" or "VIDEO"
    val durationMs: Long,
    val order: Int,
)

data class StudioTimelineSlot(
    val scheduleId: String,
    val priority: Int,
    val startAt: String,    // ISO
    val endAt: String,      // ISO
    val playlistId: String?,
    val name: String?,
)

data class FetchPlanResult(
    val rawJson: String?,
    val planHash: String?,
    val scheduleId: String?,
    val items: List<StudioPlanItem>,
    val timeline: List<StudioTimelineSlot>,
    val notModified: Boolean,
)

data class PopEventPayload(
    val id: String,
    val mediaId: String,
    val scheduleId: String?,
    val startedAt: String,  // ISO
    val endedAt: String,    // ISO
    val durationMs: Long,
)

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String?,
    val apkUrl: String,
    val sha256: String,
)
