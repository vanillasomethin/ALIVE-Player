package com.alive.player.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.alive.player.data.AppDatabase
import com.alive.player.data.PlanCache
import com.alive.player.network.DeviceApiProvider
import com.alive.player.settings.DevicePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PlanFetchWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = DevicePrefs(applicationContext)
        val token = prefs.getDeviceToken() ?: return@withContext Result.failure()
        val dao = AppDatabase.get(applicationContext).planCacheDao()
        val existing = dao.get()
        return@withContext try {
            val result = DeviceApiProvider().fetchPlan(token, existing?.etag)
            if (!result.notModified && result.planJson != null) {
                dao.upsert(
                    PlanCache(
                        id = 1,
                        planJson = result.planJson,
                        etag = result.etag,
                        fetchedAtEpochMs = System.currentTimeMillis(),
                    )
                )
            }
            Result.success()
        } catch (ex: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.success()
        }
    }
}
