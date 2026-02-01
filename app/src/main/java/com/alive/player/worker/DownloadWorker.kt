package com.alive.player.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class DownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        // TODO: stream asset with range support, verify SHA-256, update Asset row.
        return Result.success()
    }
}
