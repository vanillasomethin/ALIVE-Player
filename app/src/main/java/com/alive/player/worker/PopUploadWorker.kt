package com.alive.player.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class PopUploadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        // TODO: upload proof-of-play events in batches with backoff.
        return Result.success()
    }
}
