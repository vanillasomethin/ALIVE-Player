package com.alive.player.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.alive.player.admin.OwnerSetup
import com.alive.player.settings.DevicePrefs
import com.alive.player.service.PlaybackForegroundService
import com.alive.player.ui.PairingActivity
import com.alive.player.worker.HeartbeatScheduler
import com.alive.player.worker.PlanFetchScheduler
import com.alive.player.worker.UpdateScheduler

/**
 * Restarts playback automatically after a device reboot.
 * - Paired device → starts PlaybackForegroundService
 * - Unpaired device → launches PairingActivity
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        // Re-assert HOME claim every boot — cheap no-op on non-owner installs.
        OwnerSetup.onDeviceOwnerReady(context)

        if (DevicePrefs(context).isPaired()) {
            HeartbeatScheduler.schedule(context)
            PlanFetchScheduler.schedule(context)
            UpdateScheduler.schedule(context)
            val serviceIntent = Intent(context, PlaybackForegroundService::class.java)
            context.startForegroundService(serviceIntent)
        } else {
            val activityIntent = Intent(context, PairingActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(activityIntent)
        }
    }
}
