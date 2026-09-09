package com.alive.player.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import com.alive.player.data.AppDatabase
import com.alive.player.data.Incident
import com.alive.player.settings.DevicePrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
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

            else -> {
                // The coarse public status alone cannot tell a signature mismatch
                // (CONFLICT) from a duplicate-provider clash, nor a temporary admin
                // restriction (INCOMPATIBLE) from a wrong-ABI build. The legacy code
                // behind it can, so pass it through where the platform supplies one.
                val legacyStatus = intent.getIntExtra(
                    UpdateInstaller.EXTRA_LEGACY_STATUS,
                    UpdateInstaller.LEGACY_STATUS_ABSENT,
                )
                val permanent = UpdateInstaller.isPermanentInstallFailure(status, legacyStatus)
                val readyVersionCode = prefs.getUpdateReadyVersionCode()

                if (permanent) {
                    // Deterministic: the same APK fails the same way every retry, so
                    // left retryable a silent-capable device re-streams and re-commits
                    // it every periodic check, forever. Two flags, deliberately:
                    // needsUserAction so Settings offers the manual install, and the
                    // version-scoped poison which — unlike needsUserAction — survives
                    // a clearUpdateReady() triggered by one null update-check.
                    prefs.markUpdateNeedsUserAction()
                    prefs.markVersionPermanentlyFailed(readyVersionCode)
                }

                // Record EVERY install failure, not just the deterministic ones. A
                // screen retrying a transient failure every period for weeks is just
                // as stuck as one that gave up, and used to be equally invisible —
                // the incident only existed on the permanent branch, so the silent
                // forever-loop this bug is about produced no telemetry at all. The
                // permanent flag distinguishes "gave up, needs a new build" from
                // "still trying". goAsync: onReceive is main-thread and Room
                // (rightly) refuses main-thread writes. runCatching: crash-logging
                // must never itself crash a kiosk (disk-full is routine on these
                // boxes); the flags above are already set synchronously either way.
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        runCatching {
                            AppDatabase.get(context).incidentDao().insert(
                                Incident(
                                    type = if (permanent) {
                                        "UPDATE_INSTALL_PERMANENT_FAILURE"
                                    } else {
                                        "UPDATE_INSTALL_RETRYABLE_FAILURE"
                                    },
                                    timestampUtcEpochMs = System.currentTimeMillis(),
                                    metadataJson = JSONObject()
                                        .put("status", status)
                                        // Sentinel means the platform sent no legacy
                                        // code; report absence as null, not MIN_VALUE.
                                        .put(
                                            "legacyStatus",
                                            if (legacyStatus == UpdateInstaller.LEGACY_STATUS_ABSENT) {
                                                JSONObject.NULL
                                            } else {
                                                legacyStatus
                                            },
                                        )
                                        .put("permanent", permanent)
                                        .put("message", message ?: JSONObject.NULL)
                                        .put("readyVersionCode", readyVersionCode)
                                        .toString(),
                                )
                            )
                        }
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }
}
