package com.partner.alive.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.partner.alive.network.DeviceApiProvider
import com.partner.alive.settings.DevicePrefs

class HeartbeatWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val prefs = DevicePrefs(applicationContext)
        val token = prefs.getDeviceToken() ?: return Result.failure()
        return try {
            DeviceApiProvider().sendHeartbeat(token)
            Result.success()
        } catch (ex: Exception) {
            Result.retry()
        }
    }
}
