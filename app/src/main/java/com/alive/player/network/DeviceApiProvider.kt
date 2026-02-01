package com.alive.player.network

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

class DeviceApiProvider(
    private val baseUrl: String = "https://api.example.com",
) {
    fun claimDevice(claimCode: String): ClaimResponse {
        val payload = JSONObject()
            .put("claim_code", claimCode)
            .put("device_model", android.os.Build.MODEL ?: "unknown")
            .put("device_serial", android.os.Build.SERIAL ?: "unknown")
            .put("firmware_version", android.os.Build.VERSION.RELEASE ?: "unknown")

        val response = postJson("/device/claim", payload, null)
        return ClaimResponse(
            deviceToken = response.getString("device_token"),
            deviceId = response.optString("device_id", ""),
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
