package com.alive.player

import android.app.Application
import com.alive.player.data.AppDatabase
import com.alive.player.data.Incident
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

/**
 * Registered as the system HOME app (see AndroidManifest), so Android will relaunch us
 * after a process death regardless — but the default crash path can be slow (ANR-style
 * dialog on some OEM TV builds) and leaves no record of why we crashed. This handler
 * records the incident locally and forces a fast, clean process exit so the OS's
 * HOME-relaunch happens immediately instead of waiting on the default crash UI.
 */
class AliveApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                runBlocking {
                    AppDatabase.get(applicationContext).incidentDao().insert(
                        Incident(
                            type = "UNCAUGHT_EXCEPTION",
                            timestampUtcEpochMs = System.currentTimeMillis(),
                            metadataJson = throwable.stackTraceToString().take(4000),
                        )
                    )
                }
            } catch (_: Throwable) {
                // Never let crash-logging itself block the crash.
            }
            previousHandler?.uncaughtException(thread, throwable)
                ?: run {
                    android.os.Process.killProcess(android.os.Process.myPid())
                    exitProcess(1)
                }
        }
    }
}
