package com.alive.player.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.alive.player.data.AppDatabase
import com.alive.player.network.ApiHttpException
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
        val dao = AppDatabase.get(applicationContext).proofEventDao()

        // Bound table growth: drop quarantined (server-rejected) events after 30 days.
        dao.deleteQuarantinedBefore(System.currentTimeMillis() - QUARANTINE_RETENTION_MS)

        // Drain the whole backlog in batches of 50 (getPending caps the batch),
        // so a multi-day offline queue clears in one connected run instead of
        // one batch per play event. Bounded to keep the worker finite.
        repeat(MAX_BATCHES_PER_RUN) {
            val pending = dao.getPending()
            if (pending.isEmpty()) return@withContext Result.success()

            val payloads = pending.map { event ->
                PopEventPayload(
                    id = event.eventId,
                    mediaId = event.mediaId,
                    scheduleId = event.scheduleId,
                    // Timestamps were recorded with NtpSyncManager.now(), i.e. they
                    // are already NTP-corrected — do NOT add the clock offset again.
                    startedAt = Instant.ofEpochMilli(event.startedAtEpochMs).toString(),
                    endedAt = Instant.ofEpochMilli(event.endedAtEpochMs).toString(),
                    durationMs = event.durationMs,
                )
            }

            try {
                DeviceApiProvider().uploadEvents(token, payloads)
                // Rows are only marked/deleted AFTER the server 200. A kill between
                // the 200 and this point just re-sends; the server dedupes by id.
                dao.markUploaded(pending.map { it.eventId })
                dao.deleteUploaded()
            } catch (ex: ApiHttpException) {
                // Only a genuine payload rejection counts toward quarantine.
                // Auth (401/403), timeout (408), rate-limit (429) and all 5xx are
                // transient — leave failCount untouched so no proof-of-play is
                // ever silently dropped because of a bad network day.
                if (ex.code in 400..499 && ex.code != 401 && ex.code != 403 &&
                    ex.code != 408 && ex.code != 429
                ) {
                    dao.incrementFailCount(pending.map { it.eventId })
                }
                return@withContext Result.retry()
            } catch (ex: Exception) {
                // Network/transport error — never quarantine, retry with backoff.
                return@withContext Result.retry()
            }
        }
        Result.success()
    }

    companion object {
        private const val MAX_BATCHES_PER_RUN = 200          // 200 × 50 = 10k events
        private const val QUARANTINE_RETENTION_MS = 30L * 24 * 60 * 60 * 1000
    }
}
