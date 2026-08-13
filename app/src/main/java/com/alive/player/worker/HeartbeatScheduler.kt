package com.alive.player.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object HeartbeatScheduler {
    private const val HEARTBEAT_WORK_NAME = "heartbeat_periodic"
    private const val IMMEDIATE_WORK_NAME = "heartbeat_immediate"

    private val networkConstraint = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun schedule(context: Context) {
        // WorkManager enforces a hard 15-minute floor for PeriodicWorkRequest — anything
        // shorter is silently clamped up to 15 min, so request the real number here
        // rather than a value the platform will quietly override.
        val request = PeriodicWorkRequestBuilder<HeartbeatWorker>(15, TimeUnit.MINUTES)
            .setConstraints(networkConstraint)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            HEARTBEAT_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /** Enqueue a one-shot heartbeat right now (e.g. an admin-triggered health_ping push). */
    fun scheduleImmediate(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            IMMEDIATE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<HeartbeatWorker>()
                .setConstraints(networkConstraint)
                .build(),
        )
    /** Stop the periodic heartbeat (e.g. the 5×-BACK kiosk exit). Reversed by schedule(). */
    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(HEARTBEAT_WORK_NAME)
    }
}
