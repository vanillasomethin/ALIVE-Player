package com.alive.player.network

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/** Non-2xx HTTP response. Carries the status code so callers can distinguish
 *  payload rejections (4xx) from transient server/network trouble (5xx). */
class ApiHttpException(val code: Int, message: String) : Exception(message)

class DeviceApiProvider(
    private val baseUrl: String = com.alive.player.BuildConfig.API_BASE_URL,
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
        if (code !in 200..299) throw ApiHttpException(code, "GET $path failed ($code): $body")
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
                throw ApiHttpException(code, "fetchPlan failed ($code): $err")
            }
            val body = conn.inputStream.bufferedReader().readText()
            val root = JSONObject(body)
            val planHash = root.optString("planHash", null)
            // Not part of planHash: an orientation/config-only admin change shouldn't force
            // a content re-download, but must still be read on every poll (including the
            // notModified short-circuit below).
            val orientation = root.optString("orientation", null)
            val soundAdMuted = root.optBoolean("soundAdMuted", false)
            val config = root.optJSONObject("config")?.let {
                PlayerConfig(
                    retryIntervalMs = if (it.has("retryIntervalMs")) it.getLong("retryIntervalMs") else null,
                    transitionDurationMs = if (it.has("transitionDurationMs")) it.getLong("transitionDurationMs") else null,
                    kioskKeyLockEnabled = if (it.has("kioskKeyLockEnabled")) it.getBoolean("kioskKeyLockEnabled") else null,
                    downloadConnectTimeoutMs = if (it.has("downloadConnectTimeoutMs")) it.getInt("downloadConnectTimeoutMs") else null,
                    downloadReadTimeoutMs = if (it.has("downloadReadTimeoutMs")) it.getInt("downloadReadTimeoutMs") else null,
                )
            }
            if (planHash != null && planHash == lastPlanHash) {
                return FetchPlanResult(
                    rawJson = null,
                    planHash = planHash,
                    scheduleId = null,
                    items = emptyList(),
                    timeline = emptyList(),
                    notModified = true,
                    orientation = orientation,
                    config = config,
                    soundAdMuted = soundAdMuted,
                )
            }
            fun parseItems(arr: JSONArray) = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                StudioPlanItem(
                    contentId = o.getString("contentId"),
                    objectKey = o.getString("objectKey"),
                    url = o.getString("url"),
                    md5 = o.getString("md5"),
                    type = o.getString("type"),
                    durationMs = o.getLong("durationMs"),
                    order = o.getInt("order"),
                    hevcUrl = o.optString("hevcUrl", null),
                    hevcMd5 = o.optString("hevcMd5", null),
                )
            }
            val items = parseItems(root.optJSONArray("items") ?: JSONArray())
            val fallbackItems = parseItems(root.optJSONArray("fallback") ?: JSONArray())
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
                orientation = orientation,
                config = config,
                fallbackItems = fallbackItems,
                soundAdMuted = soundAdMuted,
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
            if (e.slotPosition != null) {
                o.put("slotPosition", e.slotPosition)
                o.put("isFiller", e.isFiller)
            }
            arr.put(o)
        }
        postJson("/api/device/events", JSONObject().put("events", arr), deviceToken)
    }

    /**
     * Server heartbeat. There is no dedicated ping route — the events endpoint
     * accepts an empty batch with telemetry and updates lastSeen/status=ONLINE.
     * Throws on failure so callers (HeartbeatWorker) can retry.
     */
    fun sendHeartbeat(
        deviceToken: String,
        freeStorageMb: Long? = null,
        playbackAliveMs: Long? = null,
        lastStallReason: String? = null,
        lastStallMs: Long? = null,
        incidents: List<IncidentPayload> = emptyList(),
    ) {
        val telemetry = JSONObject()
            .put("appVersion", com.alive.player.BuildConfig.VERSION_NAME)
            .put("androidVersion", android.os.Build.VERSION.RELEASE ?: "")
        if (freeStorageMb != null) telemetry.put("freeStorageMb", freeStorageMb)
        // Freeze diagnostics: a frozen screen still heartbeats, so lastSeen alone can't
        // detect it. playbackAliveMs is the last time content actually advanced.
        if (playbackAliveMs != null && playbackAliveMs > 0) telemetry.put("playbackAliveMs", playbackAliveMs)
        if (!lastStallReason.isNullOrBlank()) telemetry.put("lastStallReason", lastStallReason)
        if (lastStallMs != null && lastStallMs > 0) telemetry.put("lastStallMs", lastStallMs)
        val payload = JSONObject()
            .put("events", JSONArray())
            .put("telemetry", telemetry)
        if (incidents.isNotEmpty()) {
            val arr = JSONArray()
            for (inc in incidents) {
                arr.put(
                    JSONObject()
                        .put("type", inc.type)
                        .put("atMs", inc.atMs)
                        .apply { if (inc.metadata != null) put("metadata", inc.metadata) }
                )
            }
            payload.put("incidents", arr)
        }
        postJson("/api/device/events", payload, deviceToken)
    }

    fun updateFcmToken(deviceToken: String, fcmToken: String) {
        postJson("/api/device/fcm-token", JSONObject().put("fcmToken", fcmToken), deviceToken)
    }

    /** Returns null if no release is configured server-side. */
    fun checkForUpdate(deviceToken: String): UpdateInfo? {
        val resp = getJson("/api/device/update-check", deviceToken)
        if (!resp.optBoolean("updateAvailable", false)) return null
        return UpdateInfo(
            versionCode = resp.getInt("versionCode"),
            versionName = resp.optString("versionName", null),
            apkUrl      = resp.getString("apkUrl"),
            sha256      = resp.getString("sha256"),
        )
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
        if (code !in 200..299) throw ApiHttpException(code, "POST $path failed ($code): $body")
        return if (body.isBlank()) JSONObject() else JSONObject(body)
    }
}
