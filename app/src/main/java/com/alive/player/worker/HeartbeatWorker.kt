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
            DeviceApiProvider().sendHeartbeat(token)
            HeartbeatScheduler.schedule(applicationContext)
            Result.success()
        } catch (ex: Exception) {
            Result.retry()
        }
    }
}
