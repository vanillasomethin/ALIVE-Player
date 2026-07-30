package com.alive.player.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.alive.player.network.DeviceApiProvider
import com.alive.player.settings.DevicePrefs

class HeartbeatWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val prefs = DevicePrefs(applicationContext)
        val token = prefs.getDeviceToken() ?: return Result.failure()
        return try {
            val stall = prefs.getLastStall()
            DeviceApiProvider().sendHeartbeat(
                deviceToken     = token,
                freeStorageMb   = freeStorageMb(),
                playbackAliveMs = prefs.getPlaybackAliveMs(),
                lastStallReason = stall?.first,
                lastStallMs     = stall?.second,
            )
            Result.success()
        } catch (ex: Exception) {
            Result.retry()
        }
    }

    /**
     * Free space on the volume holding the media cache. Reported every heartbeat because
     * a full cache volume is what silently truncates downloads, and it is invisible
     * from the server otherwise.
     */
    private fun freeStorageMb(): Long? = runCatching {
        val dir = applicationContext.getExternalFilesDir("cache") ?: applicationContext.cacheDir
        val stat = android.os.StatFs(dir.absolutePath)
        (stat.availableBlocksLong * stat.blockSizeLong) / (1024 * 1024)
    }.getOrNull()
}
