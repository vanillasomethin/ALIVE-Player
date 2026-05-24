package com.alive.player.playback

import android.content.Context
import com.alive.player.data.AppDatabase
import com.alive.player.schedule.Plan
import com.alive.player.schedule.parsePlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object PlanLoader {
    suspend fun load(context: Context): Plan? = withContext(Dispatchers.IO) {
        val cache = AppDatabase.get(context).planCacheDao().get() ?: return@withContext null
        runCatching { parsePlan(cache.planJson) }.onFailure {
            android.util.Log.e("PlanLoader", "Failed to parse plan JSON", it)
        }.getOrNull()
    }
}
