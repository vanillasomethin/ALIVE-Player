package com.partner.alive.network

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

class DeviceApiProvider(
    private val baseUrl: String = com.partner.alive.BuildConfig.API_BASE_URL,
) {
    /** Device self-registration. Returns JWT + 6-char pairing code to display on screen. */
    fun claimDevice(hardwareKey: String, name: String? = null): ClaimDeviceResponse {
        val payload = JSONObject().put("hardwareKey", hardwareKey)
        if (name != null) payload.put("name", name)
        val resp = postJson("/api/device/claim", payload, null)
        return ClaimDeviceResponse(
            deviceId    = resp.getString("deviceId"),
            token       = resp.getString("token"),
            pairingCode = resp.optString("pairingCode", ""),
        )
    }

    /** Poll until admin confirms the device. Returns true when pairedAt is set. */
    fun checkPairingStatus(deviceToken: String): Boolean {
        val resp = getJson("/api/device/pairing-status", deviceToken)
        return resp.optBoolean("paired", false)
    }

    private fun getJson(path: String, token: String?): JSONObject {
        val url = URL(baseUrl + path)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        if (!token.isNullOrBlank()) conn.setRequestProperty("Authorization", "Bearer $token")
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.readText().orEmpty()
        if (code !in 200..299) throw IllegalStateException("GET $path failed ($code): $body")
        return if (body.isBlank()) JSONObject() else JSONObject(body)
    }

    /** Fetch the active schedule for the device. lastPlanHash is stored locally to skip re-processing. */
    fun fetchPlan(deviceToken: String, lastPlanHash: String?): FetchPlanResult {
        val url = URL("$baseUrl/api/device/plan")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("Authorization", "Bearer $deviceToken")
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.connect()
        return try {
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.readText().orEmpty()
                throw IllegalStateException("fetchPlan failed ($code): $err")
            }
            val body = conn.inputStream.bufferedReader().readText()
            val root = JSONObject(body)
            val planHash = root.optString("planHash", null)
            if (planHash != null && planHash == lastPlanHash) {
                return FetchPlanResult(
                    rawJson = null,
                    planHash = planHash,
                    scheduleId = null,
                    items = emptyList(),
                    timeline = emptyList(),
                    notModified = true,
                )
            }
            val itemsArr = root.optJSONArray("items") ?: JSONArray()
            val items = (0 until itemsArr.length()).map { i ->
                val o = itemsArr.getJSONObject(i)
                StudioPlanItem(
                    contentId = o.getString("contentId"),
                    objectKey = o.getString("objectKey"),
                    url = o.getString("url"),
                    md5 = o.getString("md5"),
                    type = o.getString("type"),
                    durationMs = o.getLong("durationMs"),
                    order = o.getInt("order"),
                )
            }
            val timelineArr = root.optJSONArray("timeline") ?: JSONArray()
            val timeline = (0 until timelineArr.length()).map { i ->
                val o = timelineArr.getJSONObject(i)
                StudioTimelineSlot(
                    scheduleId = o.getString("scheduleId"),
                    priority = o.getInt("priority"),
                    startAt = o.getString("startAt"),
                    endAt = o.getString("endAt"),
                    playlistId = o.optString("playlistId", null),
                    name = o.optString("name", null),
                )
            }
            FetchPlanResult(
                rawJson = body,
                planHash = planHash,
                scheduleId = root.optString("scheduleId", null),
                items = items,
                timeline = timeline,
                notModified = false,
            )
        } finally {
            conn.disconnect()
        }
    }

    fun uploadEvents(deviceToken: String, events: List<PopEventPayload>) {
        val arr = JSONArray()
        for (e in events) {
            val o = JSONObject()
                .put("id", e.id)
                .put("mediaId", e.mediaId)
                .put("startedAt", e.startedAt)
                .put("endedAt", e.endedAt)
                .put("durationMs", e.durationMs)
            if (e.scheduleId != null) o.put("scheduleId", e.scheduleId)
            arr.put(o)
        }
        postJson("/api/device/events", JSONObject().put("events", arr), deviceToken)
    }

    fun sendHeartbeat(deviceToken: String) {
        val payload = JSONObject().put("timestamp", java.time.Instant.now().toString())
        try { postJson("/api/device/ping", payload, deviceToken) } catch (_: Exception) {}
    }

    fun updateFcmToken(deviceToken: String, fcmToken: String) {
        postJson("/api/device/fcm-token", JSONObject().put("fcmToken", fcmToken), deviceToken)
    }

    private fun postJson(path: String, payload: JSONObject, token: String?): JSONObject {
        val url = URL(baseUrl + path)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        if (!token.isNullOrBlank()) conn.setRequestProperty("Authorization", "Bearer $token")
        conn.doOutput = true
        conn.outputStream.use { it.write(payload.toString().toByteArray(StandardCharsets.UTF_8)) }
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.readText().orEmpty()
        if (code !in 200..299) throw IllegalStateException("POST $path failed ($code): $body")
        return if (body.isBlank()) JSONObject() else JSONObject(body)
    }
}
