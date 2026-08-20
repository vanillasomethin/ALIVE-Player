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

    /** Enqueue a one-shot fetch immediately (e.g. from the manual retry button). */
    fun scheduleImmediate(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            IMMEDIATE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<PlanFetchWorker>()
                .setConstraints(networkConstraint)
                .build(),
        )
    }

    /**
     * Fetch now, but yield to a fetch that is already queued or running.
     *
     * For callers that poll on a timer rather than reacting to a one-off event:
     * [scheduleImmediate]'s REPLACE would cancel an in-flight fetch every time it
     * fired, so a screen polling faster than a fetch completes could keep
     * restarting one and never finish it. KEEP coalesces instead — a tick that
     * lands while a fetch is pending is simply a no-op.
     */
    fun schedulePollIfIdle(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            IMMEDIATE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<PlanFetchWorker>()
                .setConstraints(networkConstraint)
                .build(),
        )
    }

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

    /** Stop plan polling (e.g. the 5×-BACK kiosk exit). Reversed by schedule(). */
    fun cancel(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(PERIODIC_WORK_NAME)
        wm.cancelUniqueWork(IMMEDIATE_WORK_NAME)
    }
}
