package com.alive.player.schedule

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
