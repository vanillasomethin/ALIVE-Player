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

object UpdateScheduler {
    private const val PERIODIC_WORK_NAME = "auto_update_periodic"
    private const val IMMEDIATE_WORK_NAME = "auto_update_immediate"

    private val networkConstraint = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun schedule(context: Context) {
        val wm = WorkManager.getInstance(context)

        // Immediate check on every call (boot or first pair)
        wm.enqueueUniqueWork(
            IMMEDIATE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<UpdateWorker>()
                .setConstraints(networkConstraint)
                .build(),
        )

        // Periodic check every 6 hours as a fallback
        wm.enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<UpdateWorker>(6, TimeUnit.HOURS)
                .setConstraints(networkConstraint)
                .build(),
        )
    }
}
