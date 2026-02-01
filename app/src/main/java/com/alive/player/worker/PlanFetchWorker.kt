package com.alive.player.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class PlanFetchWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        // TODO: call GET /device/plan with ETag and update PlanCache.
        return Result.success()
    }
}
