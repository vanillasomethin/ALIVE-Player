package com.alive.player.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import com.alive.player.settings.DevicePrefs
import java.io.File

/**
 * Receives the result of a PackageInstaller session commit started by
 * UpdateInstaller (from UpdateCheckWorker's silent path or SettingsFragment's
 * operator path).
 *
 * The one rule enforced here: the system install-confirm dialog is launched ONLY
 * while an operator is in Settings (UpdateGate.userActionAllowed). A PENDING that
 * arrives during kiosk playback — e.g. a silent attempt the platform downgraded —
 * is swallowed and recorded, so playback is never interrupted and the device is
 * never asked twice. Settings surfaces the recorded state as an Install button.
 */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val prefs = DevicePrefs(context)

        // Only the session UpdateInstaller most recently committed speaks for the
        // update. Statuses from other sessions — our own stale-session cleanup firing
        // ABORTED, or the OS pruning old sessions — are noise and must not flip state.
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        if (sessionId != prefs.getPendingInstallSessionId()) return

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmIntent = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (UpdateGate.userActionAllowed && confirmIntent != null) {
                    confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(confirmIntent) }
                } else {
                    // Not a moment we may take the screen. Remember that this build
                    // needs a human so the worker stops re-committing; Settings will
                    // offer the install to the next operator on site.
                    prefs.markUpdateNeedsUserAction()
                }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                // Installed. Remove the spent APK and all ready-state; the relaunch
                // is handled by PackageReplacedReceiver in the NEW package's process.
                prefs.getUpdateReadyApkPath()?.let { runCatching { File(it).delete() } }
                prefs.clearUpdateReady()
            }

            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                // Operator saw the dialog and pressed Cancel — an explicit choice.
                // Keep the APK ready but stop any automatic re-commit; the Settings
                // button remains available whenever they change their mind.
                prefs.markUpdateNeedsUserAction()
            }

            // Other STATUS_FAILURE_*: keep ready-state; the next periodic check
            // re-verifies the download (cached, cheap) and retries the commit at
            // most once per period — still never visible over playback.
            else -> Unit
        }
    }
}
