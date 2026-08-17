package com.alive.player.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.alive.player.service.PlaybackForegroundService
import com.alive.player.service.WatchdogService
import com.alive.player.settings.DevicePrefs
import com.alive.player.ui.PairingActivity
import com.alive.player.worker.HeartbeatScheduler
import com.alive.player.worker.PlanFetchScheduler
import com.alive.player.worker.UpdateScheduler

/**
 * Runs in the NEW package's process right after an OTA self-update installs.
 * Without this, a successful update left the screen dead: the old process is
 * killed by the install and nothing relaunched playback until the next reboot.
 *
 * Mirrors BootReceiver's paired path: reschedule the periodic workers (WorkManager
 * survives updates, but rescheduling is a harmless idempotent no-op), bring the
 * playback UI back via PairingActivity (the designed entry point — its paired
 * short-circuit forwards straight to PlaybackActivity), and start the services.
 * The activity start is legal on Device-Owner installs and API < 29; on non-owner
 * API 29+ it is silently dropped — there the operator who just confirmed the
 * install dialog is standing at the device and relaunches from the launcher.
 */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val prefs = DevicePrefs(context)
        // The update that produced this broadcast is by definition installed —
        // any recorded ready-state (and its APK) refers to it or to something older.
        prefs.getUpdateReadyApkPath()?.let { runCatching { java.io.File(it).delete() } }
        prefs.clearUpdateReady()

        if (!prefs.isPaired()) return

        HeartbeatScheduler.schedule(context)
        PlanFetchScheduler.schedule(context)
        UpdateScheduler.schedule(context)

        // When this app IS the current HOME (device-owner boxes), the OS's own HOME
        // resume after the install already relaunches PairingActivity → Playback;
        // starting it here too would stack a second PlaybackActivity in the same
        // task (both have standard launchMode) and could leave a black screen.
        // Same guard BootReceiver uses.
        val isCurrentHome = context.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo?.packageName == context.packageName
        if (!isCurrentHome) {
            runCatching {
                context.startActivity(
                    Intent(context, PairingActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            }
        }
        runCatching {
            context.startForegroundService(Intent(context, PlaybackForegroundService::class.java))
        }
        runCatching { WatchdogService.ensureRunning(context) }
    }
}
