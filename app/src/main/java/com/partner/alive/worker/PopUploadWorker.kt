package com.partner.alive.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.partner.alive.data.AppDatabase
import com.partner.alive.network.DeviceApiProvider
import com.partner.alive.network.PopEventPayload
import com.partner.alive.settings.DevicePrefs
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
        val dao = AppDatabase.get(applicationContext).proofEventDao()
        val pending = dao.getPending()
        if (pending.isEmpty()) return@withContext Result.success()

        val payloads = pending.map { event ->
            PopEventPayload(
                id = event.eventId,
                mediaId = event.mediaId,
                scheduleId = event.scheduleId,
                startedAt = Instant.ofEpochMilli(event.startedAtEpochMs).toString(),
                endedAt = Instant.ofEpochMilli(event.endedAtEpochMs).toString(),
                durationMs = event.durationMs,
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
