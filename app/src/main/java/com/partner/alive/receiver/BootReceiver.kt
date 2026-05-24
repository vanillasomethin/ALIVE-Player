package com.partner.alive.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.partner.alive.settings.DevicePrefs
import com.partner.alive.service.PlaybackForegroundService
import com.partner.alive.ui.PairingActivity
import com.partner.alive.worker.HeartbeatScheduler
import com.partner.alive.worker.PlanFetchScheduler
import com.partner.alive.worker.UpdateScheduler

/**
 * Restarts playback automatically after a device reboot.
 * - Paired device → starts PlaybackForegroundService
 * - Unpaired device → launches PairingActivity
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

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
