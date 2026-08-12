package com.alive.player.worker

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.alive.player.BuildConfig
import com.alive.player.data.AppDatabase
import com.alive.player.data.DeviceDecommissioner
import com.alive.player.data.PlanCache
import com.alive.player.network.ApiHttpException
import com.alive.player.network.DeviceApiProvider
import com.alive.player.network.FetchPlanResult
import com.alive.player.network.NetworkProbe
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

        // BEFORE the first https call, not after a successful one: a drifted system
        // clock fails TLS certificate validation, so a device in that state could
        // never reach a post-success sync — it just retried forever with a dead
        // screen. NTP is plain UDP and works regardless, and on Device-Owner
        // hardware this also puts the system clock right, so the fetch below can
        // actually succeed. Forced when the last attempt failed, since that is
        // exactly when a clock problem is worth re-checking immediately.
        NtpSyncManager.syncIfNeeded(
            applicationContext,
            force = prefs.getFetchStatus()?.name == FetchStatus.ERROR.name,
        )

        prefs.setFetchStatus(FetchStatus.FETCHING.also { it.message = "Contacting server…" })

        return@withContext try {
            val result = fetchPlanWithReclaim(prefs, token, lastPlanHash)

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
            // 410 Gone = this screen was deleted in the admin panel. Wipe and return
            // to pairing; anything else (including 401, which reclaim already tried
            // to fix) is a normal transient failure.
            if (ex is ApiHttpException && ex.code == 410) {
                DeviceDecommissioner.wipe(applicationContext, "plan fetch returned 410 — deleted in admin panel")
                return@withContext Result.success()
            }
            prefs.setFetchStatus(FetchStatus.ERROR.also { it.message = diagnose(ex) })
            if (runAttemptCount < 3) Result.retry() else Result.success()
        }
    }

    /**
     * Turns a failure into something the person standing at the screen can act on.
     *
     * A rejected certificate is the single most confusing failure this player has: a
     * filtering proxy and a drifted clock produce the *same* opaque
     * "Trust anchor for certification path not found", and they need opposite fixes
     * (whitelist the screen on the router vs. correct the date). A plain-HTTP probe
     * distinguishes them, because it still gets an answer when TLS cannot.
     */
    private fun diagnose(ex: Exception): String {
        if (!isTlsTrustFailure(ex)) return ex.message?.take(120) ?: ex.javaClass.simpleName

        val probe = NetworkProbe.probe(BuildConfig.API_BASE_URL)
        return when {
            probe?.looksIntercepted == true ->
                "This network is blocking the screen" +
                    (probe.via?.let { " (proxy: ${it.take(40)})" } ?: "") +
                    " — allow wearealive.in on the router"

            NtpSyncManager.isClockBadlyWrong(applicationContext) ->
                "Device date/time is wrong — correct it in TV settings to restore playback"

            else -> "Secure connection rejected — " +
                (ex.message?.take(80) ?: "server certificate not trusted")
        }
    }

    /** Certificate-chain rejection, anywhere in the cause chain. */
    private fun isTlsTrustFailure(ex: Throwable): Boolean {
        var cause: Throwable? = ex
        while (cause != null) {
            if (cause is javax.net.ssl.SSLHandshakeException ||
                cause is java.security.cert.CertificateException ||
                cause is java.security.cert.CertPathValidatorException
            ) return true
            cause = cause.cause
        }
        return false
    }

    /** See [PopUploadWorker.reclaimToken] — same 401/403 re-claim recovery, applied here
     *  to plan fetches so a rotated token doesn't stall schedule updates indefinitely. */
    private fun fetchPlanWithReclaim(prefs: DevicePrefs, token: String, lastPlanHash: String?): FetchPlanResult =
        try {
            DeviceApiProvider().fetchPlan(token, lastPlanHash)
        } catch (ex: ApiHttpException) {
            if (ex.code == 401 || ex.code == 403) {
                val newToken = reclaimToken(prefs) ?: throw ex
                DeviceApiProvider().fetchPlan(newToken, lastPlanHash)
            } else {
                throw ex
            }
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
