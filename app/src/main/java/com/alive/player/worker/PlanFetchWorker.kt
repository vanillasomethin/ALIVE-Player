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
        // Use stored planHash (kept in etag column) to detect unchanged plans
        val lastPlanHash = existing?.etag
        return@withContext try {
            val result = DeviceApiProvider().fetchPlan(token, lastPlanHash)
            if (!result.notModified && result.rawJson != null) {
                dao.upsert(
                    PlanCache(
                        id = 1,
                        planJson = result.rawJson,
                        etag = result.planHash,
                        fetchedAtEpochMs = System.currentTimeMillis(),
                    )
                )
                // Enqueue downloads for each item in the plan
                for (item in result.items) {
                    val ext = extFromUrl(item.url) ?: continue
                    DownloadWorker.enqueue(
                        applicationContext,
                        item.contentId,
                        "current",
                        item.md5,
                        item.url,
                        ext,
                    )
                }
            }
            Result.success()
        } catch (ex: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.success()
        }
    }

    private fun extFromUrl(url: String): String? {
        val path = url.substringBefore("?").substringAfterLast("/")
        val ext = path.substringAfterLast(".", "")
        return ext.takeIf { it.isNotBlank() && it.length <= 4 }
    }
}
