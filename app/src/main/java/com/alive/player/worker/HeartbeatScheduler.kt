package com.alive.player.worker

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object HeartbeatScheduler {
    private const val HEARTBEAT_WORK_NAME = "heartbeat_work"

    fun schedule(context: Context) {
        val request = OneTimeWorkRequestBuilder<HeartbeatWorker>()
            .setInitialDelay(60, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            HEARTBEAT_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }
}
