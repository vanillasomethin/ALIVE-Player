package com.alive.player.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.alive.player.data.AppDatabase
import com.alive.player.data.PlanCache
import com.alive.player.network.DeviceApiProvider
import com.alive.player.settings.DevicePrefs
import com.alive.player.settings.FetchStatus
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
        val lastPlanHash = existing?.etag

        prefs.setFetchStatus(FetchStatus.FETCHING.also { it.message = "Contacting server…" })

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
}
