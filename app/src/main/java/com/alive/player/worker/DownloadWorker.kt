package com.alive.player.worker

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.alive.player.data.AppDatabase
import com.alive.player.data.DownloadJob
import com.alive.player.data.Incident
import com.alive.player.download.AssetDownloader
import com.alive.player.playback.PlanLoader

class DownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!isValidatedNetwork(applicationContext)) {
            return if (runAttemptCount < 4) Result.retry() else Result.failure()
        }

        val contentId = inputData.getString(KEY_CONTENT_ID) ?: return Result.failure()
        val version = inputData.getString(KEY_VERSION) ?: return Result.failure()
        val sha256 = inputData.getString(KEY_SHA256) ?: return Result.failure()
        val uri = inputData.getString(KEY_URI) ?: return Result.failure()
        val ext = inputData.getString(KEY_EXT) ?: return Result.failure()

        val assetKey = "${contentId}_${version}"
        val db = AppDatabase.get(applicationContext)
        // This worker is the ONLY writer of job-row STATE (AssetDownloader fills in
        // size_bytes, nothing else — it used to delete rows on success, which made
        // the DONE update below dead code and left the progress overlay's counters
        // at zero for the entire sync). Rows are the overlay's per-sync progress
        // records; PlanFetchWorker pre-seeds them as QUEUED so the denominator is
        // stable from the moment the plan lands.
        //
        // UPDATE before INSERT, deliberately: if a stale terminal row from an
        // earlier sync exists, the update flips it to RUNNING in one statement —
        // sweep-proof from that instant. Insert-IGNORE-then-update would leave the
        // stale row DONE for a beat, and a concurrent finisher's clearIfAllDone
        // could sweep it mid-handoff, making this download invisible all sync. In
        // the other order the worst case is the row was already swept: the update
        // no-ops and the insert creates a fresh RUNNING row atomically.
        db.downloadJobDao().update(assetKey, "RUNNING", 0L, null)
        db.downloadJobDao().insert(
            DownloadJob(
                assetKey = assetKey,
                state = "RUNNING",
                retries = runAttemptCount,
                bytesDownloaded = 0L,
                error = null,
            )
        )

        val downloader = AssetDownloader(applicationContext)
        val file = downloader.download(contentId, version, sha256, uri, ext)

        return if (file != null) {
            // Cache hits and real downloads converge here — both terminate as DONE,
            // so "3 of 5" and the byte counters in the overlay actually advance.
            // Cache hits never connected, so no Content-Length ever set size_bytes;
            // stamp it from the file so done-bytes can't exceed total-bytes.
            db.downloadJobDao().updateSize(assetKey, file.length())
            db.downloadJobDao().update(assetKey, "DONE", file.length(), null)
            // Retire the batch once nothing is left undone, so finished syncs don't
            // inflate the next one's totals. Single atomic statement: a concurrent
            // worker's RUNNING insert either lands first (blocks the sweep) or
            // survives it — SQLite serializes writers.
            db.downloadJobDao().clearIfAllDone()
            downloader.evictLru()
            // Content-update cleanup policy: replaced videos are purged from disk as
            // soon as a plan update lands, not deferred to the 2 GB LRU ceiling. This
            // runs on every successful pass (a plan change enqueues a DownloadWorker
            // per item, cache hits included), so the keep-set is always the plan that
            // scheduled this download. Empty keep-set is skipped: a transiently
            // empty/unparseable plan must not wipe the whole cache.
            PlanLoader.load(applicationContext)?.let { plan ->
                val keep = (plan.windows.flatMap { it.items } + plan.fallbackItems)
                    .map { it.contentVersionId }
                    .toSet()
                if (keep.isNotEmpty()) downloader.pruneStale(keep)
            }
            Result.success()
        } else if (runAttemptCount < 4) {
            // FAILED-awaiting-retry: informative in the overlay, and the next
            // attempt's forced-RUNNING update recycles it.
            db.downloadJobDao().update(assetKey, "FAILED", 0L, "download failed or sha256 mismatch")
            Result.retry()
        } else {
            // Final attempt: this item is not coming and the work chain ends here.
            // A FAILED row left behind would block clearIfAllDone forever — overlay
            // pinned over playback, every later sync's DONE rows accumulating behind
            // it. Every row must have a terminal exit: delete it, retire the batch
            // if the rest is done, and surface the failure through the incident
            // channel (next heartbeat) instead of a stuck progress counter.
            db.downloadJobDao().delete(assetKey)
            db.downloadJobDao().clearIfAllDone()
            runCatching {
                db.incidentDao().insert(
                    Incident(
                        type = "DOWNLOAD_PERMANENT_FAILURE",
                        timestampUtcEpochMs = System.currentTimeMillis(),
                        metadataJson = """{"contentId":"$contentId","version":"$version"}""",
                    )
                )
            }
            Result.failure()
        }
    }

    private fun isValidatedNetwork(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    companion object {
        const val KEY_CONTENT_ID = "content_id"
        const val KEY_VERSION = "version"
        const val KEY_SHA256 = "sha256"
        const val KEY_URI = "uri"
        const val KEY_EXT = "ext"

        fun enqueue(
            context: Context,
            contentId: String,
            version: String,
            sha256: String,
            uri: String,
            ext: String,
        ) {
            val request = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setInputData(
                    workDataOf(
                        KEY_CONTENT_ID to contentId,
                        KEY_VERSION to version,
                        KEY_SHA256 to sha256,
                        KEY_URI to uri,
                        KEY_EXT to ext,
                    )
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "dl_${contentId}_${version}",
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}
