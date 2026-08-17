package com.alive.player.worker

import android.content.Context
import android.provider.Settings
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.alive.player.data.AppDatabase
import com.alive.player.data.DeviceDecommissioner
import com.alive.player.network.ApiHttpException
import com.alive.player.network.DeviceApiProvider
import com.alive.player.network.IncidentPayload
import com.alive.player.settings.DevicePrefs

class HeartbeatWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val prefs = DevicePrefs(applicationContext)
        val token = prefs.getDeviceToken() ?: return Result.failure()
        val stall = prefs.getLastStall()
        // Ship the local incident backlog (crash stacks, stalls) with the heartbeat so
        // fleet-wide failure categorization is possible server-side. Oldest-first batch;
        // rows are deleted only after a 2xx, so nothing is lost on failure.
        val incidentDao = AppDatabase.get(applicationContext).incidentDao()
        val pending = incidentDao.oldestBatch(INCIDENT_BATCH_SIZE)
        val incidents = pending.map {
            IncidentPayload(type = it.type, atMs = it.timestampUtcEpochMs, metadata = it.metadataJson)
        }
        fun heartbeat(tok: String) = DeviceApiProvider().sendHeartbeat(
            deviceToken     = tok,
            freeStorageMb   = freeStorageMb(),
            playbackAliveMs = prefs.getPlaybackAliveMs(),
            lastStallReason = stall?.first,
            lastStallMs     = stall?.second,
            incidents       = incidents,
        )
        return try {
            try {
                heartbeat(token)
            } catch (ex: ApiHttpException) {
                // Marker-carrying 410 = this screen was deleted in the admin panel —
                // decommission rather than re-claim, which would resurrect it as a new
                // device. A bare 410 (no marker) is infra noise, not a deletion — see
                // ApiHttpException.isDecommission — and falls through to normal retry.
                if (ex.isDecommission) {
                    DeviceDecommissioner.wipe(applicationContext, "heartbeat returned 410 — deleted in admin panel")
                    return Result.success()
                }
                // See PopUploadWorker.reclaimToken — 401/403 means the token was rotated
                // (e.g. admin re-paired this device), so re-claim and retry once.
                if (ex.code == 401 || ex.code == 403) {
                    heartbeat(reclaimToken(prefs) ?: throw ex)
                } else {
                    throw ex
                }
            }
            if (pending.isNotEmpty()) incidentDao.deleteByIds(pending.map { it.id })
            Result.success()
        } catch (ex: Exception) {
            Result.retry()
        }
    }

    private companion object {
        const val INCIDENT_BATCH_SIZE = 25
    }

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
