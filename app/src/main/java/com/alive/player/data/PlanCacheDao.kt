package com.alive.player.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PlanCacheDao {
    @Query("SELECT * FROM plan_cache WHERE id = 1 LIMIT 1")
    suspend fun get(): PlanCache?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(planCache: PlanCache)

    @Query("DELETE FROM plan_cache")
    suspend fun clear()
}
