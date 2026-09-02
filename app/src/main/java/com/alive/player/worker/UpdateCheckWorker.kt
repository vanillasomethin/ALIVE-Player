package com.alive.player.worker

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.alive.player.BuildConfig
import com.alive.player.data.DeviceDecommissioner
import com.alive.player.download.AssetDownloader
import com.alive.player.network.ApiHttpException
import com.alive.player.network.DeviceApiProvider
import com.alive.player.settings.DevicePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Checks /api/device/update-check, downloads a newer APK via the existing
 * AssetDownloader (range-resume + SHA-256 verify, already hardened for media assets),
 * records it as "update ready", and installs it via UpdateInstaller — but ONLY when
 * the install can complete without disturbing playback:
 *
 *  - Silent-capable device (owner / self-installer-of-record on 12+): commit now,
 *    the update applies itself; PackageReplacedReceiver brings playback back up.
 *  - Otherwise: no commit from here. The Settings screen shows "Install update"
 *    (SettingsFragment) and the operator triggers the one confirm dialog in person.
 *
 * This is what stops the old behaviour of re-prompting the install dialog over
 * kiosk playback on every periodic check, forever, on non-owner devices.
 */
class UpdateCheckWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // Behind a captive portal (hotel/venue WiFi) the network is CONNECTED but not
        // VALIDATED — retry with backoff instead of consuming the run doing nothing,
        // otherwise the Settings button's one-shot would silently no-op forever there.
        if (!isValidatedNetwork(applicationContext)) return@withContext Result.retry()

        val prefs = DevicePrefs(applicationContext)
        val token = prefs.getDeviceToken() ?: return@withContext Result.failure()

        // The one-shot (Settings button) and the periodic check may fire together;
        // both write the same .part staging file. Serialize the whole check.
        checkMutex.withLock {
            try {
                val update = DeviceApiProvider().checkForUpdate(token)
                if (update == null || update.versionCode <= BuildConfig.VERSION_CODE) {
                    // Nothing newer than what is running — drop stale ready-state and
                    // cached APKs (covers "server rolled back" and "just got updated").
                    prefs.clearUpdateReady()
                    UpdateInstaller.clearAllUpdates(applicationContext)
                    return@withLock Result.success()
                }

                val apk = AssetDownloader(applicationContext).download(
                    contentId = UpdateInstaller.UPDATE_CONTENT_ID,
                    version   = update.versionCode.toString(),
                    sha256    = update.sha256,
                    uri       = update.apkUrl,
                    ext       = "apk",
                ) ?: return@withLock Result.retry()

                prefs.setUpdateReady(update.versionCode, update.versionName, apk.absolutePath)
                UpdateInstaller.pruneOldUpdates(applicationContext, keep = apk)

                // Commit only when the install can complete AND the screen comes back:
                //  - silently on a device that can also relaunch itself afterwards, or
                //  - with an operator present in Settings (UpdateGate) to see it through.
                // needsUserAction stops silent-looking devices that already proved
                // otherwise (a commit came back PENDING) from re-committing 4x/day.
                val canAuto = UpdateInstaller.canInstallSilently(applicationContext) &&
                    UpdateInstaller.canRelaunchUiAfterInstall(applicationContext) &&
                    !prefs.updateNeedsUserAction()
                // operatorRequested is intent, not presence: only the non-silent path
                // with the gate open means "a human asked for the confirm dialog".
                // A silent commit stays operatorRequested=false even if someone is
                // browsing Settings, so it can never bulldoze an in-flight twin.
                if (canAuto || UpdateGate.userActionAllowed) {
                    UpdateInstaller.commit(
                        applicationContext, apk,
                        operatorRequested = !canAuto && UpdateGate.userActionAllowed,
                    )
                }
                Result.success()
            } catch (ex: Exception) {
                // Marker-carrying 410 = deleted in the admin panel; same handling as
                // every worker (bare infra 410s retry — ApiHttpException.isDecommission).
                if (ex is ApiHttpException && ex.isDecommission) {
                    DeviceDecommissioner.wipe(applicationContext, "update check returned 410 — deleted in admin panel")
                    return@withLock Result.success()
                }
                if (runAttemptCount < 3) Result.retry() else Result.success()
            }
        }
    }

    companion object {
        /** Process-wide: serializes the one-shot and periodic checks (same process). */
        private val checkMutex = Mutex()
    }

    private fun isValidatedNetwork(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
