package com.alive.player.worker

import android.content.Context
import android.provider.Settings
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
        var token = prefs.getDeviceToken() ?: return@withContext Result.failure()
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
                    slotPosition = event.slotPosition,
                    isFiller = event.isFiller,
                )
            }

            try {
                DeviceApiProvider().uploadEvents(token, payloads)
                // Rows are only marked/deleted AFTER the server 200. A kill between
                // the 200 and this point just re-sends; the server dedupes by id.
                dao.markUploaded(pending.map { it.eventId })
                dao.deleteUploaded()
            } catch (ex: ApiHttpException) {
                // A rotated JWT (e.g. admin re-claimed/re-paired the device) shows up as
                // 401/403 with no server-side fix possible except re-claiming. Re-claim
                // with the same hardwareKey to obtain the new token and loop back to
                // retry this same (still-unmarked) batch immediately.
                if (ex.code == 401 || ex.code == 403) {
                    val newToken = reclaimToken(prefs)
                    if (newToken != null) {
                        token = newToken
                        return@repeat
                    }
                    return@withContext Result.retry()
                }
                // Only a genuine payload rejection counts toward quarantine.
                // Timeout (408), rate-limit (429) and all 5xx are transient — leave
                // failCount untouched so no proof-of-play is ever silently dropped
                // because of a bad network day.
                if (ex.code in 400..499 && ex.code != 408 && ex.code != 429) {
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

    /**
     * Re-calls POST /api/device/claim with this device's hardwareKey to obtain a fresh
     * token after a 401/403, per the client checklist in ALIVE_PLAYER_API.md. An empty
     * pairingCode means the device is still admin-confirmed (just token rotation); a
     * non-empty one means it was actually unclaimed, so there's no usable token yet —
     * stash the pending pairing state (PairingActivity will pick it up) and let the
     * caller retry later instead.
     */
    private fun reclaimToken(prefs: DevicePrefs): String? {
        val hardwareKey = Settings.Secure.getString(
            applicationContext.contentResolver, Settings.Secure.ANDROID_ID
        ) ?: return null
        return try {
            val response = DeviceApiProvider().claimDevice(hardwareKey)
            if (response.pairingCode.isNotEmpty()) {
                prefs.storePairingPending(response.token, response.deviceId, response.pairingCode)
                null
            } else {
                prefs.storePairing(response.token, response.deviceId)
                response.token
            }
        } catch (ex: Exception) {
            null
        }
    }

    companion object {
        private const val MAX_BATCHES_PER_RUN = 200          // 200 × 50 = 10k events
        private const val QUARANTINE_RETENTION_MS = 30L * 24 * 60 * 60 * 1000
    }
}
