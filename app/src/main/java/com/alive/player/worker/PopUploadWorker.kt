package com.alive.player.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.alive.player.data.AppDatabase
import com.alive.player.network.DeviceApiProvider
import com.alive.player.network.PopEventPayload
import com.alive.player.settings.DevicePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

class PopUploadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = DevicePrefs(applicationContext)
        val token = prefs.getDeviceToken() ?: return@withContext Result.failure()
        val deviceId = prefs.getDeviceId() ?: return@withContext Result.failure()
        val dao = AppDatabase.get(applicationContext).proofEventDao()
        val pending = dao.getPending()
        if (pending.isEmpty()) return@withContext Result.success()

        val payloads = pending.map { event ->
            PopEventPayload(
                eventId = event.eventId,
                deviceId = deviceId,
                contentVersionId = event.contentVersionId,
                eventType = event.type,
                timestampIso = Instant.ofEpochMilli(event.timestampUtcEpochMs).toString(),
                durationMs = event.durationMs,
                sessionId = event.sessionId,
            )
        }

        return@withContext try {
            DeviceApiProvider().uploadEvents(token, payloads)
            dao.markUploaded(pending.map { it.eventId })
            dao.deleteUploaded()
            Result.success()
        } catch (ex: Exception) {
            // Keep events in DB — WorkManager exponential backoff will retry.
            if (runAttemptCount < 5) Result.retry() else Result.success()
        }
    }
}
