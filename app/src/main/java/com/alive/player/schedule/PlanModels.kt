package com.alive.player.schedule

import org.json.JSONObject

data class PlanWindow(
    val startEpochMs: Long,
    val endEpochMs: Long,
    val items: List<PlanItem>,
)

data class PlanItem(
    val contentVersionId: String,
    val durationMs: Long,
    val type: String,
    val uri: String,
)

data class Plan(
    val windows: List<PlanWindow>,
    val fallbackItems: List<PlanItem>,
)

fun parsePlan(json: String): Plan {
    val root = JSONObject(json)

    fun parseItem(obj: JSONObject) = PlanItem(
        contentVersionId = obj.getString("content_version_id"),
        type = obj.getString("type"),
        uri = obj.getString("uri"),
        durationMs = obj.getLong("duration_ms"),
    )

    val windowsArr = root.optJSONArray("windows")
    val windows = buildList {
        if (windowsArr != null) {
            for (i in 0 until windowsArr.length()) {
                val w = windowsArr.getJSONObject(i)
                val itemsArr = w.optJSONArray("items")
                val items = buildList {
                    if (itemsArr != null) {
                        for (j in 0 until itemsArr.length()) {
                            add(parseItem(itemsArr.getJSONObject(j)))
                        }
                    }
                }
                add(PlanWindow(
                    startEpochMs = w.getLong("start_ts"),
                    endEpochMs = w.getLong("end_ts"),
                    items = items,
                ))
            }
        }
    }

    val fallbackArr = root.optJSONArray("fallback_items")
    val fallbackItems = buildList {
        if (fallbackArr != null) {
            for (i in 0 until fallbackArr.length()) {
                add(parseItem(fallbackArr.getJSONObject(i)))
            }
        }
    }

    return Plan(windows = windows, fallbackItems = fallbackItems)
}
