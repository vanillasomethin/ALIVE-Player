package com.alive.player.network

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Simulates the Alive backend for demo/testing when DEMO_MODE = true.
 * No server required — claimDevice() returns a token immediately.
 * Injects a demo plan with public-domain images from picsum.photos.
 */
object DemoApiProvider {

    private val demoToken = "demo-token-${UUID.randomUUID()}"
    private val demoDeviceId = "DEMO-${UUID.randomUUID().toString().take(8).uppercase()}"

    /** Instant claim — returns a pre-baked demo token without hitting a server. */
    fun claimDevice(hardwareKey: String): ClaimDeviceResponse =
        ClaimDeviceResponse(deviceId = demoDeviceId, token = demoToken)

    /**
     * Returns a demo plan JSON in studio format with 4 public-domain images cycling every 8s.
     * Uses picsum.photos — requires internet access.
     */
    fun demoScheduleJson(): String {
        val items = JSONArray().apply {
            listOf(
                Triple("demo-img-1", "https://picsum.photos/seed/alive1/1920/1080", "jpg"),
                Triple("demo-img-2", "https://picsum.photos/seed/alive2/1920/1080", "jpg"),
                Triple("demo-img-3", "https://picsum.photos/seed/alive3/1920/1080", "jpg"),
                Triple("demo-img-4", "https://picsum.photos/seed/alive4/1920/1080", "jpg"),
            ).forEachIndexed { index, (id, url, _) ->
                put(
                    JSONObject()
                        .put("contentId", id)
                        .put("objectKey", "demo/$id.jpg")
                        .put("url", url)
                        .put("md5", "")
                        .put("type", "IMAGE")
                        .put("durationMs", 8_000)
                        .put("order", index + 1)
                )
            }
        }
        return JSONObject()
            .put("planHash", "demo-plan-hash")
            .put("scheduleId", null as Any?)
            .put("items", items)
            .put("timeline", JSONArray())
            .toString()
    }
}
