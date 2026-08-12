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
    val hevcUrl: String? = null, // optional HEVC rendition -- see DecoderCapabilities.preferHevc()
    val hevcMd5: String? = null,
)

data class StudioTimelineSlot(
    val scheduleId: String,
    val priority: Int,
    val startAt: String,    // ISO
    val endAt: String,      // ISO
    val playlistId: String?,
    val name: String?,
)

data class PlayerConfig(
    val retryIntervalMs: Long?,
    val transitionDurationMs: Long?,
    val kioskKeyLockEnabled: Boolean?,
    val downloadConnectTimeoutMs: Int?,
    val downloadReadTimeoutMs: Int?,
)

data class FetchPlanResult(
    val rawJson: String?,
    val planHash: String?,
    val scheduleId: String?,
    val items: List<StudioPlanItem>,
    val timeline: List<StudioTimelineSlot>,
    val notModified: Boolean,
    val orientation: String? = null, // "LANDSCAPE" | "PORTRAIT" | "AUTO" — admin-assigned screen orientation
    val config: PlayerConfig? = null,
    val fallbackItems: List<StudioPlanItem> = emptyList(), // admin fallback playlist — needs downloading too
)

data class PopEventPayload(
    val id: String,
    val mediaId: String,
    val scheduleId: String?,
    val startedAt: String,  // ISO
    val endedAt: String,    // ISO
    val durationMs: Long,
    // Slot-loop attribution (slot-mode stores only) — see PlanItem.
    val slotPosition: Int? = null,
    val isFiller: Boolean = false,
)

data class IncidentPayload(
    val type: String,       // UNCAUGHT_EXCEPTION | STUCK_PLAYBACK | FALLBACK_TRIGGERED
    val atMs: Long,         // epoch ms the incident was recorded on-device
    val metadata: String?,  // stack trace excerpt or context, already clamped on insert
)

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String?,
    val apkUrl: String,
    val sha256: String,
)
