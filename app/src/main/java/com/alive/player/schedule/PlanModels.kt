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
    // Nested (Master → Internal) playlist tree for the scheduled playlist, when the server
    // sends one. Traversed by SmilSequencer; empty = flat plan, legacy behaviour.
    val nested: List<PlanNode> = emptyList(),
    // Sequencer identity key (the server's planHash): cursors persist across the frequent
    // plan reloads (reloadAndAdvance) as long as the plan content is unchanged.
    val nestedKey: String? = null,
)

/**
 * One node of the nested playlist tree. Semantics (see docs/smil-reference-notes.md):
 * a Nested node plays ALL its children per visit — SMIL <seq>-in-<seq> — so the
 * depth-first flattening equals the play order.
 */
sealed class PlanNode {
    data class Media(val item: PlanItem) : PlanNode()
    data class Nested(val playlistId: String, val name: String?, val children: List<PlanNode>) : PlanNode()
}

private fun extFromUrl(url: String): String? {
    val path = url.substringBefore("?").substringAfterLast("/")
    val ext = path.substringAfterLast(".", "")
    return ext.takeIf { it.isNotBlank() && it.length <= 4 }
}

private fun isoToEpochMs(iso: String): Long =
    java.time.Instant.parse(iso).toEpochMilli()

// Mirrors the rendition choice PlanFetchWorker makes when downloading, so the file
// AssetDownloader resolves for playback (keyed by contentId + this md5) is the one
// that actually got downloaded. DecoderCapabilities.preferHevc() is a pure function of
// the device's codec list, so both call sites always agree without any shared state.
private fun resolveRendition(o: JSONObject, type: String): Pair<String, String?> {
    val hevcUrl = o.optString("hevcUrl", null)
    val hevcMd5 = o.optString("hevcMd5", null)
    val useHevc = type == "video" && !hevcUrl.isNullOrBlank() && !hevcMd5.isNullOrBlank() &&
        DecoderCapabilities.preferHevc()
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

        fun buildItem(o: JSONObject): PlanItem {
            val type = o.getString("type").lowercase()
            val (url, md5) = resolveRendition(o, type)
            return PlanItem(
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
            )
        }

        val itemsArr = root.optJSONArray("items") ?: org.json.JSONArray()
        val items = buildList {
            for (i in 0 until itemsArr.length()) {
                add(buildItem(itemsArr.getJSONObject(i)))
            }
        }

        // Optional nested (Master → Internal) tree. Entries are either media items
        // (kind "content", same fields as `items` entries) or nested playlists
        // (kind "playlist" with their own `items`). Unknown kinds are skipped so a
        // future server can add node types without breaking deployed players.
        fun parseNested(arr: org.json.JSONArray): List<PlanNode> = buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                when (o.optString("kind", "content")) {
                    "playlist" -> add(PlanNode.Nested(
                        playlistId = o.optString("playlistId", ""),
                        name = o.optString("name", null),
                        children = parseNested(o.optJSONArray("items") ?: org.json.JSONArray()),
                    ))
                    "content" -> add(PlanNode.Media(buildItem(o)))
                }
            }
        }
        val nested = root.optJSONArray("nested")?.let { parseNested(it) } ?: emptyList()

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
                // Fallback items play outside any schedule window — no scheduleId.
                add(buildItem(fallbackArr.getJSONObject(i)).copy(scheduleId = null))
            }
        } else items

        return Plan(
            windows = windows,
            fallbackItems = fallbackItems,
            transition = root.optString("transition", "NONE"),
            nested = nested,
            nestedKey = root.optString("planHash", null),
        )
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
