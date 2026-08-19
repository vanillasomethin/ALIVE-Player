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

        // Belt-and-braces push channel: every install joins the fleet topic, so a
        // plan_updated broadcast reaches this device even when the server holds a
        // stale/absent per-device token (fresh sideloads miss onNewToken's upload —
        // it fires before pairing). Topic membership is managed by Play services:
        // it survives token rotation and retries registration itself. The handler
        // is idempotent (an unaffected device's fetch just 304s), so over-delivery
        // is harmless. Targeted commands (decommission, reboot) stay token-only.
        runCatching {
            com.google.firebase.messaging.FirebaseMessaging.getInstance()
                .subscribeToTopic(FLEET_TOPIC)
        }

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

    companion object {
        /** FCM topic every player subscribes to; the studio broadcasts plan_updated here. */
        const val FLEET_TOPIC = "fleet"
    }
}
