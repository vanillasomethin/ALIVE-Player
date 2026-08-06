package com.alive.player.worker

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.alive.player.data.AppDatabase
import com.alive.player.data.PlanCache
import com.alive.player.network.DeviceApiProvider
import com.alive.player.playback.DecoderCapabilities
import com.alive.player.settings.DevicePrefs
import com.alive.player.settings.FetchStatus
import com.alive.player.settings.NtpSyncManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PlanFetchWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // Reject captive-portal networks that pass the CONNECTED check but can't reach our server
        if (!isValidatedNetwork(applicationContext)) {
            return@withContext if (runAttemptCount < 3) Result.retry() else Result.success()
        }

        val prefs = DevicePrefs(applicationContext)
        val token = prefs.getDeviceToken() ?: return@withContext Result.failure()
        val dao = AppDatabase.get(applicationContext).planCacheDao()
        val existing = dao.get()
        val lastPlanHash = existing?.etag

        prefs.setFetchStatus(FetchStatus.FETCHING.also { it.message = "Contacting server…" })

        return@withContext try {
            val result = DeviceApiProvider().fetchPlan(token, lastPlanHash)

            // Admin-assigned orientation isn't part of planHash (it shouldn't force a
            // content re-download), so apply it on every successful fetch, not just when
            // the plan content changed. AUTO/unset means "defer to the on-device 5-tap
            // rotate control" -- only an explicit choice overrides the local preference.
            when (result.orientation?.uppercase()) {
                "PORTRAIT"         -> prefs.setOrientationMode(DevicePrefs.ORIENTATION_PORTRAIT)
                "PORTRAIT_FLIPPED" -> prefs.setOrientationMode(DevicePrefs.ORIENTATION_REVERSE_PORTRAIT)
                "LANDSCAPE"        -> prefs.setOrientationMode(DevicePrefs.ORIENTATION_LANDSCAPE)
            }

            // Same reasoning as orientation: fleet-wide behavior knobs aren't part of
            // planHash, so apply them on every successful fetch regardless of whether
            // the plan content changed.
            result.config?.let { cfg ->
                cfg.retryIntervalMs?.let { prefs.setRetryIntervalMs(it) }
                cfg.transitionDurationMs?.let { prefs.setTransitionDurationMs(it) }
                cfg.kioskKeyLockEnabled?.let { prefs.setKioskKeyLockEnabled(it) }
                cfg.downloadConnectTimeoutMs?.let { prefs.setDownloadConnectTimeoutMs(it) }
                cfg.downloadReadTimeoutMs?.let { prefs.setDownloadReadTimeoutMs(it) }
            }

            if (!result.notModified && result.rawJson != null) {
                dao.upsert(
                    PlanCache(
                        id = 1,
                        planJson = result.rawJson,
                        etag = result.planHash,
                        fetchedAtEpochMs = System.currentTimeMillis(),
                    )
                )
                prefs.markPlanUpdated()
                // Fallback-playlist items must be cached too, or the fallback would
                // stream (or fail) exactly when it's needed.
                for (item in (result.items + result.fallbackItems).distinctBy { it.contentId }) {
                    // Fall back to type-based extension for CDN URLs with no path extension
                    val ext = extFromUrl(item.url)
                        ?: when (item.type.lowercase()) {
                            "video" -> "mp4"
                            "image" -> "jpg"
                            else    -> null
                        } ?: continue
                    // Devices with no reliable hardware AVC decoder (so AVC would fall
                    // back to a CPU-bound software decoder) prefer the HEVC rendition
                    // when the server offers one, since it can use a working hardware
                    // HEVC decoder instead. See DecoderCapabilities.preferHevc().
                    val useHevc = item.type.equals("video", ignoreCase = true) &&
                        !item.hevcUrl.isNullOrBlank() && !item.hevcMd5.isNullOrBlank() &&
                        DecoderCapabilities.preferHevc()
                    DownloadWorker.enqueue(
                        applicationContext,
                        item.contentId,
                        "current",
                        if (useHevc) item.hevcMd5!! else item.md5,
                        if (useHevc) item.hevcUrl!! else item.url,
                        ext,
                    )
                }
            }

            // Sync NTP after a successful server round-trip (network is confirmed reachable)
            NtpSyncManager.sync(applicationContext)

            when {
                result.notModified ->
                    prefs.setFetchStatus(FetchStatus.OK.also {
                        it.message = "Schedule up to date (${existing?.let { c ->
                            val age = (System.currentTimeMillis() - c.fetchedAtEpochMs) / 60_000
                            "fetched ${age}m ago"
                        } ?: "cached"})"
                    })
                result.items.isEmpty() ->
                    prefs.setFetchStatus(FetchStatus.NO_SCHEDULE.also {
                        it.message = "No schedule assigned — go to wearealive.in/admin"
                    })
                else ->
                    prefs.setFetchStatus(FetchStatus.OK.also {
                        it.message = "${result.items.size} item(s) ready"
                    })
            }

            Result.success()
        } catch (ex: Exception) {
            val msg = ex.message?.take(120) ?: ex.javaClass.simpleName
            prefs.setFetchStatus(FetchStatus.ERROR.also { it.message = msg })
            if (runAttemptCount < 3) Result.retry() else Result.success()
        }
    }

    private fun extFromUrl(url: String): String? {
        val path = url.substringBefore("?").substringAfterLast("/")
        val ext = path.substringAfterLast(".", "")
        return ext.takeIf { it.isNotBlank() && it.length <= 4 }
    }

    private fun isValidatedNetwork(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
