package com.alive.player.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [PlanCache::class, Asset::class, DownloadJob::class, ProofEvent::class, Incident::class],
    version = 5,
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

        /**
         * v4 → v5: slot-loop attribution on proof_events. A real migration, not a
         * destructive fallback, precisely because proof_events carries the un-uploaded
         * proof-of-play backlog — see the note on fallbackToDestructiveMigration below.
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE proof_events ADD COLUMN slot_position INTEGER")
                db.execSQL("ALTER TABLE proof_events ADD COLUMN is_filler INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "alive_player.db",
                )
                    .addMigrations(MIGRATION_4_5)
                    // Deliberate choice, not an oversight: this fleet self-updates via a
                    // silent OTA (see release.yml + UpdateCheckWorker) with no user watching
                    // the screen, so a missing-Migration crash on launch would blank an
                    // unattended kiosk fleet until the next release — worse than wiping a
                    // local cache. The only entity where that cache loss is real user data
                    // is proof_events (unlaid PoP backlog from offline runs); everything
                    // else re-downloads/re-fetches. If a future version bump touches
                    // proof_events, add a real Migration for it instead of relying on this.
                    .fallbackToDestructiveMigration()
                    .build().also { INSTANCE = it }
            }
    }
}
