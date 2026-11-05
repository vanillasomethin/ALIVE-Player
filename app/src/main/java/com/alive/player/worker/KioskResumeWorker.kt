package com.alive.player.worker

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.alive.player.remote.KioskControl
import com.alive.player.service.UiPresence
import com.alive.player.settings.DevicePrefs
import java.util.concurrent.TimeUnit

/**
 * The return trip for a REMOTE kiosk exit: when the time the operator chose is up, bring
 * the player back to the front. Without it, an exit sent from the dashboard to a shop
 * nobody is standing in would leave that screen on the TV's own launcher until the next
 * reboot. Does nothing if the player is already showing (resumed by hand, or by Resume).
 */
class KioskResumeWorker(
    appContext: Context,
    params: WorkerParameters,
) : Worker(appContext, params) {
    override fun doWork(): Result {
        if (DevicePrefs(applicationContext).isPaired() && !UiPresence.playbackVisible) {
            KioskControl.resume(applicationContext)
        }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "kiosk_resume"

        /** REPLACE, so a second exit moves the return time instead of keeping the first. */
        fun schedule(context: Context, minutes: Int) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<KioskResumeWorker>()
                    .setInitialDelay(minutes.toLong(), TimeUnit.MINUTES)
                    .build(),
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
