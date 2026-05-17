package com.alive.player.network

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

class DeviceApiProvider(
    private val baseUrl: String = "https://api.example.com",
) {
    fun registerPairing(): PairingRegisterResponse {
        val payload = JSONObject()
            .put("device_model", android.os.Build.MODEL ?: "unknown")
            .put("device_serial", android.os.Build.SERIAL ?: "unknown")
            .put("firmware_version", android.os.Build.VERSION.RELEASE ?: "unknown")
        val response = postJson("/v1/device/pairing/register", payload, null)
        return PairingRegisterResponse(
            pairingId = response.getString("pairing_id"),
            code = response.getString("code"),
            expiresAtEpochSeconds = response.getLong("expires_at_epoch_seconds"),
            pollAfterSeconds = response.getInt("poll_after_seconds"),
        )
    }

    fun fetchPairingStatus(pairingId: String): PairingStatusResponse {
        val url = URL("$baseUrl/v1/device/pairing/status?pairing_id=$pairingId")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        val code = connection.responseCode
        val responseText = (if (code in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.readText().orEmpty()
        if (code !in 200..299) {
            throw IllegalStateException("fetchPairingStatus failed ($code): $responseText")
        }
        val json = if (responseText.isBlank()) JSONObject() else JSONObject(responseText)
        return PairingStatusResponse(
            status = json.getString("status"),
            deviceToken = json.optString("device_token", null),
            deviceId = json.optString("device_id", null),
            pollAfterSeconds = json.optInt("poll_after_seconds", 15),
        )
    }

    fun fetchPlan(deviceToken: String, etag: String?): FetchPlanResult {
        val url = URL("$baseUrl/api/device/plan?hours=72")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.setRequestProperty("Authorization", "Bearer $deviceToken")
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        if (!etag.isNullOrBlank()) {
            connection.setRequestProperty("If-None-Match", etag)
        }
        return when (val code = connection.responseCode) {
            304 -> FetchPlanResult(planJson = null, etag = etag, notModified = true)
            in 200..299 -> {
                val planJson = connection.inputStream.bufferedReader().readText()
                val newEtag = connection.getHeaderField("ETag")
                FetchPlanResult(planJson = planJson, etag = newEtag, notModified = false)
            }
            else -> {
                val error = connection.errorStream?.bufferedReader()?.readText().orEmpty()
                throw IllegalStateException("fetchPlan failed ($code): $error")
            }
        }
    }

    fun uploadEvents(deviceToken: String, events: List<PopEventPayload>) {
        val eventsArray = JSONArray()
        for (e in events) {
            val obj = JSONObject()
                .put("event_id", e.eventId)
                .put("device_id", e.deviceId)
                .put("content_version_id", e.contentVersionId)
                .put("event_type", e.eventType)
                .put("timestamp_utc", e.timestampIso)
                .put("session_id", e.sessionId)
            if (e.durationMs != null) obj.put("duration_ms", e.durationMs)
            eventsArray.put(obj)
        }
        postJson("/api/device/events", JSONObject().put("events", eventsArray), deviceToken)
    }

    fun sendHeartbeat(deviceToken: String) {
        val payload = JSONObject().put("timestamp", java.time.Instant.now().toString())
        postJson("/device/ping", payload, deviceToken)
    }

    private fun postJson(path: String, payload: JSONObject, token: String?): JSONObject {
        val url = URL(baseUrl + path)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        if (!token.isNullOrBlank()) {
            connection.setRequestProperty("Authorization", "Bearer $token")
        }
        connection.doOutput = true
        val body = payload.toString().toByteArray(StandardCharsets.UTF_8)
        connection.outputStream.use { it.write(body) }
        val code = connection.responseCode
        val responseText = (if (code in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.readText().orEmpty()
        if (code !in 200..299) {
            throw IllegalStateException("POST $path failed ($code): $responseText")
        }
        return if (responseText.isBlank()) JSONObject() else JSONObject(responseText)
    }
}

data class PairingRegisterResponse(
    val pairingId: String,
    val code: String,
    val expiresAtEpochSeconds: Long,
    val pollAfterSeconds: Int,
)

data class PairingStatusResponse(
    val status: String,
    val deviceToken: String?,
    val deviceId: String?,
    val pollAfterSeconds: Int,
)
