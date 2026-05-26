package com.alive.player.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.alive.player.data.AppDatabase
import com.alive.player.network.DeviceApiProvider
import com.alive.player.network.PopEventPayload
import com.alive.player.settings.DevicePrefs
import com.alive.player.settings.NtpSyncManager
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
        // getPending() already caps at 50 and excludes events with failCount >= 3
        val pending = dao.getPending()
        if (pending.isEmpty()) return@withContext Result.success()

        val payloads = pending.map { event ->
            PopEventPayload(
                id = event.eventId,
                mediaId = event.mediaId,
                scheduleId = event.scheduleId,
                // Apply NTP-corrected timestamps for accurate billing
                startedAt = Instant.ofEpochMilli(
                    event.startedAtEpochMs + prefs.getClockOffsetMs()
                ).toString(),
                endedAt = Instant.ofEpochMilli(
                    event.endedAtEpochMs + prefs.getClockOffsetMs()
                ).toString(),
                durationMs = event.durationMs,
            )
        }

        return@withContext try {
            DeviceApiProvider().uploadEvents(token, payloads)
            val uploadedIds = pending.map { it.eventId }
            dao.markUploaded(uploadedIds)
            dao.deleteUploaded()
            Result.success()
        } catch (ex: Exception) {
            // Increment failCount so repeated-fail events are eventually quarantined
            dao.incrementFailCount(pending.map { it.eventId })
            if (runAttemptCount < 5) Result.retry() else Result.success()
        }
    }
}
