package com.alive.player.worker

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.alive.player.BuildConfig
import com.alive.player.admin.OwnerSetup
import com.alive.player.download.AssetDownloader
import com.alive.player.network.DeviceApiProvider
import com.alive.player.settings.DevicePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

/**
 * Checks /api/device/update-check, downloads a newer APK via the existing
 * AssetDownloader (range-resume + SHA-256 verify, already hardened for media
 * assets and generic enough to reuse here unchanged), and installs it via
 * PackageInstaller. Fully silent only when device-owner + API 31+; otherwise
 * falls back to the standard system install-confirm dialog (UpdateInstallReceiver).
 */
class UpdateCheckWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!isValidatedNetwork(applicationContext)) return@withContext Result.success()

        val prefs = DevicePrefs(applicationContext)
        val token = prefs.getDeviceToken() ?: return@withContext Result.failure()

        try {
            val update = DeviceApiProvider().checkForUpdate(token) ?: return@withContext Result.success()
            if (update.versionCode <= BuildConfig.VERSION_CODE) return@withContext Result.success()

            val apk = AssetDownloader(applicationContext).download(
                contentId = "player-update",
                version   = update.versionCode.toString(),
                sha256    = update.sha256,
                uri       = update.apkUrl,
                ext       = "apk",
            ) ?: return@withContext Result.retry()

            installApk(applicationContext, apk)
            Result.success()
        } catch (ex: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.success()
        }
    }

    private fun installApk(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val sessionParams = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (OwnerSetup.isDeviceOwner(context) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            sessionParams.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }

        val sessionId = installer.createSession(sessionParams)
        installer.openSession(sessionId).use { session ->
            FileInputStream(apk).use { input ->
                session.openWrite("update", 0, apk.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
            val statusIntent = Intent(context, UpdateInstallReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                sessionId,
                statusIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(pendingIntent.intentSender)
        }
    }

    private fun isValidatedNetwork(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
