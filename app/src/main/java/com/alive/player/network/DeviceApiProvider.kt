package com.alive.player.network

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
        val responseStream = if (connection.responseCode in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream
        }
        val responseText = responseStream?.bufferedReader()?.readText().orEmpty()
        if (connection.responseCode !in 200..299) {
            throw IllegalStateException("Request failed (${connection.responseCode}): $responseText")
        }
        val response = if (responseText.isBlank()) JSONObject() else JSONObject(responseText)
        return PairingStatusResponse(
            status = response.getString("status"),
            deviceToken = response.optString("device_token", null),
            deviceId = response.optString("device_id", null),
            pollAfterSeconds = response.optInt("poll_after_seconds", 15),
        )
    }

    fun sendHeartbeat(deviceToken: String) {
        val payload = JSONObject()
            .put("timestamp", java.time.Instant.now().toString())
        postJson("/device/ping", payload, deviceToken)
    }

    private fun postJson(path: String, payload: JSONObject, token: String?): JSONObject {
        val url = URL(baseUrl + path)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        if (!token.isNullOrBlank()) {
            connection.setRequestProperty("Authorization", "Bearer $token")
        }
        connection.doOutput = true
        val body = payload.toString().toByteArray(StandardCharsets.UTF_8)
        connection.outputStream.use { it.write(body) }

        val responseStream = if (connection.responseCode in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream
        }
        val responseText = responseStream?.bufferedReader()?.readText().orEmpty()
        if (connection.responseCode !in 200..299) {
            throw IllegalStateException("Request failed (${connection.responseCode}): $responseText")
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
