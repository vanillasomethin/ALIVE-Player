package com.alive.player.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object UpdateScheduler {
    private const val PERIODIC_WORK_NAME = "update_check_periodic"

    private val networkConstraint = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /** Call once after pairing and again on every boot — checks for a newer APK every 6 hours. */
    fun schedule(context: Context) {
        val periodic = PeriodicWorkRequestBuilder<UpdateCheckWorker>(6, TimeUnit.HOURS)
            .setConstraints(networkConstraint)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            periodic,
        )
    }

    /** Stop OTA update checks (e.g. the 5×-BACK kiosk exit, so a routine check doesn't
     *  kick off an install mid-servicing). Best-effort: a check already mid-install, or
     *  a PackageInstaller session already committed, still lands — WorkManager
     *  cancellation is cooperative and can't abort a committed system install.
     *  Reversed by schedule(). */
    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK_NAME)
    }
}
