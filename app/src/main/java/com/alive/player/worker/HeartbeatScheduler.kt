package com.alive.player.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object HeartbeatScheduler {
    private const val HEARTBEAT_WORK_NAME = "heartbeat_periodic"

    fun schedule(context: Context) {
        // WorkManager enforces a hard 15-minute floor for PeriodicWorkRequest — anything
        // shorter is silently clamped up to 15 min, so request the real number here
        // rather than a value the platform will quietly override.
        val request = PeriodicWorkRequestBuilder<HeartbeatWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            HEARTBEAT_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /** Stop the periodic heartbeat (e.g. the 5×-BACK kiosk exit). Reversed by schedule(). */
    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(HEARTBEAT_WORK_NAME)
    }
}
