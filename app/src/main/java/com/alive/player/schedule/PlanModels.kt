package com.alive.player.schedule

import com.alive.player.playback.DecoderCapabilities
import org.json.JSONObject

data class PlanWindow(val startEpochMs: Long, val endEpochMs: Long, val items: List<PlanItem>)

data class PlanItem(
    val contentVersionId: String,
    val durationMs: Long,
    val type: String,       // "image" or "video"
    val uri: String,
    val sha256: String? = null,  // stores md5 value from studio
    val ext: String? = null,
    val scheduleId: String? = null,
)

data class Plan(
    val windows: List<PlanWindow>,
    val fallbackItems: List<PlanItem>,
    val transition: String = "NONE", // "NONE" | "FADE" | "SLIDE" -- set per playlist in admin
)

private fun extFromUrl(url: String): String? {
    val path = url.substringBefore("?").substringAfterLast("/")
    val ext = path.substringAfterLast(".", "")
    return ext.takeIf { it.isNotBlank() && it.length <= 4 }
}

private fun isoToEpochMs(iso: String): Long =
    java.time.Instant.parse(iso).toEpochMilli()

// Mirrors the rendition choice PlanFetchWorker makes when downloading, so the file
// AssetDownloader resolves for playback (keyed by contentId + this md5) is the one
// that actually got downloaded. preferredRendition (server-learned, from
// Device.renditionTier) and DecoderCapabilities.preferHevc() (local heuristic) are
// both pure functions of inputs both call sites already have, so they always agree
// without any shared state.
//
// preferredRendition == null/"HEVC" (the default): no negative signal yet for this
// device -- defer entirely to the local heuristic, exactly as before this existed.
// "H264_MAIN"/"H264_BASELINE": this device has already demonstrably failed a higher
// tier (a real playback stall or a failed "Test screen" check) -- hard override, do
// NOT fall back to the local heuristic even if it would otherwise pick HEVC.
private fun resolveRendition(o: JSONObject, type: String, preferredRendition: String?): Pair<String, String?> {
    if (type != "video") {
        return o.getString("url") to o.optString("md5", null).takeIf { !it.isNullOrBlank() }
    }
    val baselineUrl = o.optString("baselineUrl", null)
    val baselineMd5 = o.optString("baselineMd5", null)
    if (preferredRendition == "H264_BASELINE" && !baselineUrl.isNullOrBlank() && !baselineMd5.isNullOrBlank()) {
        return baselineUrl to baselineMd5
    }
    if (preferredRendition == "H264_MAIN" || preferredRendition == "H264_BASELINE") {
        // Baseline unavailable for this content yet (not re-transcoded) -- fall to the
        // guaranteed Main asset, never HEVC, since the override exists specifically to
        // stop offering a tier this device has already failed.
        return o.getString("url") to o.optString("md5", null).takeIf { !it.isNullOrBlank() }
    }
    val hevcUrl = o.optString("hevcUrl", null)
    val hevcMd5 = o.optString("hevcMd5", null)
    val useHevc = !hevcUrl.isNullOrBlank() && !hevcMd5.isNullOrBlank() && DecoderCapabilities.preferHevc()
    return if (useHevc) {
        hevcUrl to hevcMd5
    } else {
        o.getString("url") to o.optString("md5", null).takeIf { !it.isNullOrBlank() }
    }
}

fun parsePlan(json: String): Plan {
    val root = JSONObject(json)

    // Support both studio format (items + timeline) and legacy format (windows + fallback_items)
    val hasStudioFormat = root.has("items")

    if (hasStudioFormat) {
        val scheduleId = root.optString("scheduleId", null)
        val preferredRendition = root.optString("preferredRendition", null)

        val itemsArr = root.optJSONArray("items") ?: org.json.JSONArray()
        val items = buildList {
            for (i in 0 until itemsArr.length()) {
                val o = itemsArr.getJSONObject(i)
                val type = o.getString("type").lowercase()
                val (url, md5) = resolveRendition(o, type, preferredRendition)
                add(PlanItem(
                    contentVersionId = o.getString("contentId"),
                    durationMs = o.getLong("durationMs"),
                    type = type,
                    uri = url,
                    sha256 = md5,
                    ext = extFromUrl(url) ?: when (type) {
                        "video" -> "mp4"
                        "image" -> "jpg"
                        else    -> null
                    },
                    scheduleId = scheduleId,
                ))
            }
        }

        val timelineArr = root.optJSONArray("timeline") ?: org.json.JSONArray()
        val windows = buildList {
            for (i in 0 until timelineArr.length()) {
                val slot = timelineArr.getJSONObject(i)
                add(PlanWindow(
                    startEpochMs = isoToEpochMs(slot.getString("startAt")),
                    endEpochMs = isoToEpochMs(slot.getString("endAt")),
                    items = items,
                ))
            }
        }

        // Server-designated fallback playlist (admin "Fallback playlist" setting):
        // played when no schedule window is active. Absent/empty keeps the historical
        // behaviour of looping the scheduled items round the clock.
        val fallbackArr = root.optJSONArray("fallback")
        val fallbackItems = if (fallbackArr != null && fallbackArr.length() > 0) buildList {
            for (i in 0 until fallbackArr.length()) {
                val o = fallbackArr.getJSONObject(i)
                val type = o.getString("type").lowercase()
                val (url, md5) = resolveRendition(o, type, preferredRendition)
                add(PlanItem(
                    contentVersionId = o.getString("contentId"),
                    durationMs = o.getLong("durationMs"),
                    type = type,
                    uri = url,
                    sha256 = md5,
                    ext = extFromUrl(url) ?: when (type) {
                        "video" -> "mp4"
                        "image" -> "jpg"
                        else    -> null
                    },
                    scheduleId = null,
                ))
            }
        } else items

        return Plan(windows = windows, fallbackItems = fallbackItems, transition = root.optString("transition", "NONE"))
    }

    // Legacy format: windows + fallback_items
    fun parseItemLegacy(obj: org.json.JSONObject) = PlanItem(
        contentVersionId = obj.getString("content_version_id"),
        type = obj.getString("type"),
        uri = obj.getString("uri"),
        durationMs = obj.getLong("duration_ms"),
        sha256 = obj.optString("sha256", null).takeIf { !it.isNullOrBlank() },
        ext = obj.optString("ext", null).takeIf { !it.isNullOrBlank() },
    )

    val windowsArr = root.optJSONArray("windows")
    val windows = buildList {
        if (windowsArr != null) {
            for (i in 0 until windowsArr.length()) {
                val w = windowsArr.getJSONObject(i)
                val wItemsArr = w.optJSONArray("items")
                val wItems = buildList {
                    if (wItemsArr != null) {
                        for (j in 0 until wItemsArr.length()) {
                            add(parseItemLegacy(wItemsArr.getJSONObject(j)))
                        }
                    }
                }
                add(PlanWindow(
                    startEpochMs = w.getLong("start_ts"),
                    endEpochMs = w.getLong("end_ts"),
                    items = wItems,
                ))
            }
        }
    }

    val fallbackArr = root.optJSONArray("fallback_items")
    val fallbackItems = buildList {
        if (fallbackArr != null) {
            for (i in 0 until fallbackArr.length()) {
                add(parseItemLegacy(fallbackArr.getJSONObject(i)))
            }
        }
    }

    return Plan(windows = windows, fallbackItems = fallbackItems)
}
