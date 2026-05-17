package com.alive.player.playback

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.alive.player.data.AppDatabase
import com.alive.player.data.ProofEvent
import com.alive.player.schedule.PlanItem
import com.alive.player.worker.PopUploadWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.TimeUnit

class PlaybackEngine(private val context: Context) {

    private val sessionId = UUID.randomUUID().toString()
    private var currentItem: PlanItem? = null
    private var playStartMs: Long = 0L

    fun playItem(item: PlanItem) {
        val now = System.currentTimeMillis()
        currentItem?.let { emitPlayEnd(it, now) }
        currentItem = item
        playStartMs = now
        emitEvent(
            ProofEvent(
                eventId = UUID.randomUUID().toString(),
                contentVersionId = item.contentVersionId,
                type = "PLAY_START",
                timestampUtcEpochMs = now,
                durationMs = null,
                sessionId = sessionId,
            )
        )
    }

    fun stop() {
        val now = System.currentTimeMillis()
        currentItem?.let { emitPlayEnd(it, now) }
        currentItem = null
    }

    private fun emitPlayEnd(item: PlanItem, nowMs: Long) {
        emitEvent(
            ProofEvent(
                eventId = UUID.randomUUID().toString(),
                contentVersionId = item.contentVersionId,
                type = "PLAY_END",
                timestampUtcEpochMs = nowMs,
                durationMs = (nowMs - playStartMs).coerceAtLeast(0),
                sessionId = sessionId,
            )
        )
    }

    private fun emitEvent(event: ProofEvent) {
        CoroutineScope(Dispatchers.IO).launch {
            AppDatabase.get(context).proofEventDao().insert(event)
            scheduleUpload()
        }
    }

    private fun scheduleUpload() {
        val request = OneTimeWorkRequestBuilder<PopUploadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "pop_upload",
            ExistingWorkPolicy.KEEP,
            request,
        )
    }
}
