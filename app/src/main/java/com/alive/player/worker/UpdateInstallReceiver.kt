package com.alive.player.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller

/**
 * Receives the result of a PackageInstaller session commit started by
 * UpdateCheckWorker. On a Device Owner install (API 31+) this fires with
 * STATUS_SUCCESS and no user interaction. On older API levels or non-owner
 * installs, Android requires user confirmation (STATUS_PENDING_USER_ACTION) —
 * EXTRA_INTENT launches that standard system confirm dialog.
 */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirmIntent = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
            confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(confirmIntent)
        }
        // STATUS_SUCCESS / STATUS_FAILURE / STATUS_FAILURE_*: nothing else to
        // do — a successful install restarts the app via its own launch,
        // a failure is retried on the next periodic check.
    }
}
