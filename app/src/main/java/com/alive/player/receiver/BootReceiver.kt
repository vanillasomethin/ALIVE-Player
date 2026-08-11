package com.alive.player.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.alive.player.admin.OwnerSetup
import com.alive.player.settings.DevicePrefs
import com.alive.player.service.PlaybackForegroundService
import com.alive.player.service.ProcessHeartbeat
import com.alive.player.service.WatchdogService
import com.alive.player.ui.PairingActivity
import com.alive.player.worker.HeartbeatScheduler
import com.alive.player.worker.PlanFetchScheduler
import com.alive.player.worker.UpdateScheduler

/**
 * Restarts playback automatically after a device reboot.
 * - Paired device → brings the playback UI up + starts PlaybackForegroundService
 * - Unpaired device → launches PairingActivity
 *
 * On Device-Owner installs the primary boot path is the HOME claim (OwnerSetup):
 * the OS launches PairingActivity as the home app, which forwards straight to
 * PlaybackActivity. This receiver is the safety net for the one boot where that
 * chain is broken: after the 5×-BACK kiosk exit (PlaybackActivity.exitKiosk) the
 * HOME claim has been relinquished, so the OS boots into the stock launcher and
 * nothing would bring the player up. Detecting that case and launching the UI here
 * restores "boots straight into playback" from the very next reboot.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        // Sampled BEFORE re-asserting the claim below, so it reflects which app the
        // OS actually launched as HOME during THIS boot. Re-asserting first would
        // make us resolve as HOME even on the post-exit boot where the stock
        // launcher is what's really on screen.
        val wasHomeThisBoot = context.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo?.packageName == context.packageName

        // Re-assert HOME claim every boot — cheap no-op on non-owner installs.
        OwnerSetup.onDeviceOwnerReady(context)

        if (DevicePrefs(context).isPaired()) {
            HeartbeatScheduler.schedule(context)
            PlanFetchScheduler.schedule(context)
            UpdateScheduler.schedule(context)

            // Bring the playback UI up when the HOME chain didn't already do it this
            // boot. Goes via PairingActivity (the designed entry point — its paired
            // short-circuit forwards to PlaybackActivity with no network wait) rather
            // than PlaybackActivity directly, so this launch can never stack a second
            // PlaybackActivity on top of one the HOME chain is creating. Legal from a
            // receiver on Device-Owner installs (background-activity-launch exemption)
            // and on API < 29; silently dropped on non-owner API 29+, where only a
            // manual HOME-app selection can restore boot-to-playback.
            if (!wasHomeThisBoot) {
                runCatching {
                    context.startActivity(
                        Intent(context, PairingActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                }
            }

            // Belt-and-braces: also start the service directly, so the engine, process
            // heartbeat and watchdog run even where the activity launch was dropped.
            // runCatching because Android 15 (targetSdk 35) forbids BOOT_COMPLETED
            // receivers from starting mediaPlayback-type foreground services — there
            // the activity path above starts the service from the foreground instead,
            // and this direct start must fail quietly rather than crash the receiver.
            runCatching {
                context.startForegroundService(Intent(context, PlaybackForegroundService::class.java))
            }
            // Mark the main process alive BEFORE starting the watchdog: the heartbeat
            // file persists a pre-reboot wall-clock timestamp, so at boot it always
            // reads as >90s stale. Without this fresh write, the watchdog's first check
            // (~20s) can kill the app mid-launch on slow boots where the service start
            // above was denied (Android 15) and the activity chain is still coming up.
            ProcessHeartbeat.write(context)
            runCatching { WatchdogService.ensureRunning(context) }
        } else {
            val activityIntent = Intent(context, PairingActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(activityIntent) }
        }
    }
}
