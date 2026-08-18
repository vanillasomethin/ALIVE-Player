package com.alive.player.service

import android.content.Context
import java.io.File

/**
 * Cross-process liveness signal. The main process writes its current time here
 * from a background thread (not the main Looper) every few seconds; WatchdogService,
 * running in a separate OS process, reads it without needing a Binder call into the
 * main process — which matters precisely because a frozen/ANR'd main process can't
 * answer Binder calls but the plain file on shared storage is still readable.
 */
object ProcessHeartbeat {
    private const val FILE_NAME = "process_heartbeat"

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun write(context: Context) {
        runCatching { file(context).writeText(System.currentTimeMillis().toString()) }
    }

    /** Removes the signal entirely — for a deliberate, permanent stop (decommission).
     *  An absent file reads as null in [millisSinceLastWrite], which the watchdog
     *  treats as "no signal yet", never as a wedged process to kill. */
    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    /** Null if the heartbeat has never been written. */
    fun millisSinceLastWrite(context: Context): Long? {
        val f = file(context)
        if (!f.exists()) return null
        val last = runCatching { f.readText().trim().toLong() }.getOrNull() ?: return null
        return System.currentTimeMillis() - last
    }
}
