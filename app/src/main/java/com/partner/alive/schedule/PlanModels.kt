package com.partner.alive.schedule

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

data class Plan(val windows: List<PlanWindow>, val fallbackItems: List<PlanItem>)

private fun extFromUrl(url: String): String? {
    val path = url.substringBefore("?").substringAfterLast("/")
    val ext = path.substringAfterLast(".", "")
    return ext.takeIf { it.isNotBlank() && it.length <= 4 }
}

private fun isoToEpochMs(iso: String): Long =
    java.time.Instant.parse(iso).toEpochMilli()

fun parsePlan(json: String): Plan {
    val root = JSONObject(json)

    // Support both studio format (items + timeline) and legacy format (windows + fallback_items)
    val hasStudioFormat = root.has("items")

    if (hasStudioFormat) {
        val scheduleId = root.optString("scheduleId", null)

        val itemsArr = root.optJSONArray("items") ?: org.json.JSONArray()
        val items = buildList {
            for (i in 0 until itemsArr.length()) {
                val o = itemsArr.getJSONObject(i)
                val url  = o.getString("url")
                val type = o.getString("type").lowercase()
                add(PlanItem(
                    contentVersionId = o.getString("contentId"),
                    durationMs = o.getLong("durationMs"),
                    type = type,
                    uri = url,
                    sha256 = o.optString("md5", null).takeIf { !it.isNullOrBlank() },
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

        return Plan(windows = windows, fallbackItems = items)
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
