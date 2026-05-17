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

object PlanFetchScheduler {
    private const val PERIODIC_WORK_NAME = "plan_fetch_periodic"
    private const val IMMEDIATE_WORK_NAME = "plan_fetch_immediate"

    private val networkConstraint = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /** Call once after pairing to start the 15-minute polling cadence. */
    fun schedule(context: Context) {
        val periodic = PeriodicWorkRequestBuilder<PlanFetchWorker>(15, TimeUnit.MINUTES)
            .setConstraints(networkConstraint)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            periodic,
        )
        // Also kick off an immediate fetch so the device gets a plan right after pairing.
        val immediate = OneTimeWorkRequestBuilder<PlanFetchWorker>()
            .setConstraints(networkConstraint)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            IMMEDIATE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            immediate,
        )
    }
}
