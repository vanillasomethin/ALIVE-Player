package com.partner.alive.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.partner.alive.data.AppDatabase
import com.partner.alive.data.DownloadJob
import com.partner.alive.download.AssetDownloader

class DownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val contentId = inputData.getString(KEY_CONTENT_ID) ?: return Result.failure()
        val version = inputData.getString(KEY_VERSION) ?: return Result.failure()
        val sha256 = inputData.getString(KEY_SHA256) ?: return Result.failure()
        val uri = inputData.getString(KEY_URI) ?: return Result.failure()
        val ext = inputData.getString(KEY_EXT) ?: return Result.failure()

        val assetKey = "${contentId}_${version}"
        val db = AppDatabase.get(applicationContext)
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
            db.downloadJobDao().update(assetKey, "DONE", file.length(), null)
            downloader.evictLru()
            Result.success()
        } else {
            db.downloadJobDao().update(assetKey, "FAILED", 0L, "download failed or sha256 mismatch")
            if (runAttemptCount < 4) Result.retry() else Result.failure()
        }
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
