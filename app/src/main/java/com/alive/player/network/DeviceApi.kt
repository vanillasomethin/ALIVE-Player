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

data class PlanResponse(
    val planJson: String,
    val etag: String?,
)

data class PopBatchRequest(
    val events: List<PopEventPayload>,
)

data class PopEventPayload(
    val eventId: String,
    val contentId: String,
    val eventType: String,
    val timestampIso: String,
)

interface DeviceApi {
    suspend fun claimDevice(request: ClaimRequest): ClaimResponse
    suspend fun fetchPlan(hours: Int, etag: String?): PlanResponse
    suspend fun uploadEvents(batch: PopBatchRequest)
}
