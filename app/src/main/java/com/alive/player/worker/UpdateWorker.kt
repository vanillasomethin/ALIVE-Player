package com.alive.player.worker

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.alive.player.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class UpdateWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val release = fetchLatestRelease() ?: return@withContext Result.success()
            val latestCode = release.optInt("versionCode", 0)
            if (latestCode <= BuildConfig.VERSION_CODE) return@withContext Result.success()

            val apkUrl = release.optString("apkUrl", "") .ifBlank { return@withContext Result.success() }
            val apkBytes = downloadBytes(apkUrl)
            installApk(apkBytes)
        } catch (_: Exception) {}
        Result.success()
    }

    private fun fetchLatestRelease(): JSONObject? {
        val url = URL("https://api.github.com/repos/vanillasomethin/alive-player/releases/latest")
        val conn = url.openConnection() as HttpURLConnection
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        return try {
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().readText()
            val root = JSONObject(body)
            // Find the APK asset and parse versionCode from the release tag (e.g. "v42")
            val assets = root.optJSONArray("assets") ?: return null
            var apkUrl: String? = null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.getString("name").endsWith(".apk")) {
                    apkUrl = asset.getString("browser_download_url")
                    break
                }
            }
            apkUrl ?: return null
            val tag = root.optString("tag_name", "") // e.g. "build-42" or "v42"
            val versionCode = tag.filter { it.isDigit() }.toIntOrNull() ?: 0
            JSONObject().put("apkUrl", apkUrl).put("versionCode", versionCode)
        } finally {
            conn.disconnect()
        }
    }

    private fun downloadBytes(urlString: String): ByteArray {
        val conn = URL(urlString).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        return try {
            conn.inputStream.readBytes()
        } finally {
            conn.disconnect()
        }
    }

    private fun installApk(apkBytes: ByteArray) {
        val installer = applicationContext.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)
        session.openWrite("alive-player.apk", 0, apkBytes.size.toLong()).use { out ->
            out.write(apkBytes)
            session.fsync(out)
        }
        val intent = Intent("com.alive.player.UPDATE_COMPLETE").apply {
            setPackage(applicationContext.packageName)
        }
        val pi = PendingIntent.getBroadcast(
            applicationContext, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        session.commit(pi.intentSender)
        session.close()
    }
}
