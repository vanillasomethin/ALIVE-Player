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
    val baselineUrl: String? = null, // optional H.264 Baseline@3.1 rendition -- server-forced
    val baselineMd5: String? = null, // fallback once a device fails higher tiers, see preferredRendition
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
    // Server-learned hard override, from Device.renditionTier. "HEVC" (default) means no
    // negative signal yet -- defer to DecoderCapabilities.preferHevc() as before. "H264_MAIN"
    // or "H264_BASELINE" mean this device has already demonstrably failed a higher tier --
    // ignore the local heuristic and use exactly that rendition. See PlanModels.resolveRendition.
    val preferredRendition: String? = null,
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
