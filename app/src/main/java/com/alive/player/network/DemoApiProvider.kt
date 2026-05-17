package com.alive.player.network

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Simulates the Alive backend for demo/testing when DEMO_MODE = true.
 * No server required — generates a local claim code and auto-approves it
 * after AUTO_PAIR_DELAY_MS. Injects a demo plan with public-domain images.
 */
object DemoApiProvider {

    private const val AUTO_PAIR_DELAY_MS = 8_000L
    private val demoToken = "demo-token-${UUID.randomUUID()}"
    private val demoDeviceId = "DEMO-${UUID.randomUUID().toString().take(8).uppercase()}"
    private var pairApprovedAt = 0L

    fun generatePairingCode(): String {
        val letters = ('A'..'Z').toList()
        val digits = ('0'..'9').toList()
        return "${letters.random()}${letters.random()}-${digits.random()}${digits.random()}${digits.random()}${digits.random()}"
    }

    fun registerPairing(): PairingRegisterResponse {
        val code = generatePairingCode()
        pairApprovedAt = System.currentTimeMillis() + AUTO_PAIR_DELAY_MS
        return PairingRegisterResponse(
            pairingId = "demo-${UUID.randomUUID()}",
            code = code,
            expiresAtEpochSeconds = (System.currentTimeMillis() / 1000) + 300,
            pollAfterSeconds = 3,
        )
    }

    fun fetchPairingStatus(pairingId: String): PairingStatusResponse {
        val now = System.currentTimeMillis()
        return if (pairApprovedAt > 0 && now >= pairApprovedAt) {
            PairingStatusResponse(
                status = "CLAIMED",
                deviceToken = demoToken,
                deviceId = demoDeviceId,
                pollAfterSeconds = 5,
            )
        } else {
            PairingStatusResponse(
                status = "PENDING",
                deviceToken = null,
                deviceId = null,
                pollAfterSeconds = 3,
            )
        }
    }

    /**
     * Returns a demo plan JSON with 4 public-domain images cycling every 8s.
     * Uses picsum.photos — requires internet access.
     */
    fun demoScheduleJson(): String {
        val now = System.currentTimeMillis()
        val items = JSONArray().apply {
            listOf(
                Triple("demo-img-1", "https://picsum.photos/seed/alive1/1920/1080", "jpg"),
                Triple("demo-img-2", "https://picsum.photos/seed/alive2/1920/1080", "jpg"),
                Triple("demo-img-3", "https://picsum.photos/seed/alive3/1920/1080", "jpg"),
                Triple("demo-img-4", "https://picsum.photos/seed/alive4/1920/1080", "jpg"),
            ).forEach { (id, uri, ext) ->
                put(
                    JSONObject()
                        .put("content_version_id", id)
                        .put("type", "image")
                        .put("uri", uri)
                        .put("duration_ms", 8_000)
                        .put("ext", ext)
                )
            }
        }
        return JSONObject()
            .put("windows", JSONArray())          // no time-windowed schedule
            .put("fallback_items", items)         // always use this fallback loop
            .toString()
    }
}
