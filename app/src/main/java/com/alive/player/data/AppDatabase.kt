package com.alive.player.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [PlanCache::class, Asset::class, DownloadJob::class, ProofEvent::class, Incident::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun planCacheDao(): PlanCacheDao
    abstract fun proofEventDao(): ProofEventDao
    abstract fun assetDao(): AssetDao
    abstract fun downloadJobDao(): DownloadJobDao
    abstract fun incidentDao(): IncidentDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "alive_player.db",
                ).build().also { INSTANCE = it }
            }
    }
}
