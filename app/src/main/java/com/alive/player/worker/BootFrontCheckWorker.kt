package com.alive.player.worker

import android.content.Context
import android.content.Intent
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.ExistingWorkPolicy
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import com.alive.player.service.UiPresence
import com.alive.player.settings.DevicePrefs
import com.alive.player.ui.PairingActivity
import java.util.concurrent.TimeUnit

/**
 * One check, shortly after boot: if the playback screen is not showing, bring it up.
 *
 * BootReceiver used to skip its own launch whenever this app already resolved as the Home
 * app ("the HOME chain will start us"). On a TV that starts its own launcher at power-up
 * that is wrong: the claim is in place, the launcher is on screen anyway, the playback
 * service runs unseen behind it, and the console sees a screen that is "on" but dark.
 * Pressing Home on the remote worked, which is how it was found. Launching PairingActivity
 * (it forwards straight to PlaybackActivity, a singleTask) is harmless when we are
 * already in front, so this only ever acts when the screen is wrong.
 *
 * Legal from a worker for the same reason the receiver's launch is: Device Owner is exempt
 * from the background-start limit, and below Android 10 there is no limit.
 */
class BootFrontCheckWorker(
    appContext: Context,
    params: WorkerParameters,
) : Worker(appContext, params) {
    override fun doWork(): Result {
        if (!DevicePrefs(applicationContext).isPaired()) return Result.success()
        if (!UiPresence.playbackVisible) {
            runCatching {
                applicationContext.startActivity(
                    Intent(applicationContext, PairingActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "boot_front_check"
        // Long enough for a slow box to finish bringing its own launcher up (we must come
        // AFTER it to stay in front), short enough that nobody stands at a dark screen.
        private const val DELAY_SECONDS = 45L

        fun scheduleAfterBoot(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<BootFrontCheckWorker>()
                    .setInitialDelay(DELAY_SECONDS, TimeUnit.SECONDS)
                    .build(),
            )
        }

        /** The 5x-BACK kiosk exit: a technician is servicing the TV, do not pull it back. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
