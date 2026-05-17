package com.alive.player.network

data class ClaimRequest(
    val claimCode: String,
    val deviceModel: String,
    val deviceSerial: String,
    val firmwareVersion: String,
)

data class ClaimResponse(
    val deviceToken: String,
    val deviceId: String,
)

data class FetchPlanResult(
    val planJson: String?,
    val etag: String?,
    val notModified: Boolean,
)

data class PopEventPayload(
    val eventId: String,
    val deviceId: String,
    val contentVersionId: String,
    val eventType: String,
    val timestampIso: String,
    val durationMs: Long?,
    val sessionId: String,
)

data class HeartbeatRequest(
    val timestampIso: String,
)
