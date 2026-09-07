package com.alive.player.service

import android.content.Context
import android.os.Looper
import java.io.File

/**
 * Cross-process liveness signal. The value persisted here is the last instant the
 * MAIN thread proved itself alive ([noteMainThreadAlive], stamped from the main
 * Looper), relayed to disk by a background thread ([writeMainThreadStamp]) so the
 * main thread never touches storage — a write stalled behind a download flush storm
 * would jank playback. WatchdogService, running in a separate OS process, reads it
 * without needing a Binder call into the main process — which matters precisely
 * because a frozen/ANR'd main process can't answer Binder calls but the plain file
 * is still readable.
 *
 * The relay is the point: if the main thread wedges (ANR) the stamp stops advancing
 * even though the background writer keeps running, and if the whole process freezes
 * the file stops being written — either way the file goes stale and the watchdog
 * kills the process. Persisting the writer thread's own clock instead would hide
 * the first failure mode entirely, which is exactly the bug this replaced.
 */
object ProcessHeartbeat {
    private const val FILE_NAME = "process_heartbeat"

    @Volatile private var mainThreadAliveAtMs = 0L

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    /** A claim that the main thread is currently running — so it may only be made
     *  FROM the main Looper, and that is enforced: stamped from anywhere else it
     *  would report a wedged main thread as alive, which is the bug this object
     *  exists to make impossible. Cheap enough for any cadence (no I/O). */
    fun noteMainThreadAlive() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "main-thread liveness may only be stamped from the main Looper"
        }
        mainThreadAliveAtMs = System.currentTimeMillis()
    }

    /** Grace write of the CURRENT time, from any thread — for the moments when the
     *  main thread hasn't had a chance to prove itself yet but a stale file would
     *  get the process killed mid-operation (boot launch, decommission wipe). */
    fun writeGraceStamp(context: Context) {
        writeGraceStamp(file(context))
    }

    /** Persist the last main-thread-proven instant. A no-op until the first
     *  [noteMainThreadAlive] or [writeGraceStamp], so an early relay write can't
     *  replace a grace stamp with the epoch and trigger the very kill it exists
     *  to prevent. */
    fun writeMainThreadStamp(context: Context) {
        writeMainThreadStamp(file(context))
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

    internal fun writeGraceStamp(target: File) {
        val now = System.currentTimeMillis()
        // Also floor the relay stamp: the grace promises the watchdog's full stale
        // threshold, but if the main thread is ANR'd while the relay loop is still
        // running (decommission racing a wedge), the next relay write would replace
        // this fresh grace with the frozen stamp and the watchdog would kill the
        // process mid-operation. Floored, the relay can never persist anything
        // older than the grace it would be overwriting.
        if (now > mainThreadAliveAtMs) mainThreadAliveAtMs = now
        persist(target, now)
    }

    internal fun writeMainThreadStamp(target: File) {
        val stamp = mainThreadAliveAtMs
        if (stamp == 0L) return
        persist(target, stamp)
    }

    // writeText alone is open(O_TRUNC)-then-write: a process death between the two
    // steps — a window that recurs every relay period, forever, on a 24/7 device —
    // leaves a 0-byte file, which millisSinceLastWrite reads as "no signal", and
    // that permanently disarms the watchdog against the very freeze it exists to
    // catch. Write beside the target and rename into place instead: rename(2) is
    // atomic on the same filesystem, so the watchdog process only ever sees the
    // old value or the new one.
    private fun persist(target: File, valueMs: Long) {
        runCatching {
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(valueMs.toString())
            if (!tmp.renameTo(target)) target.writeText(valueMs.toString())
        }
    }

    internal fun noteMainThreadAliveForTest(nowMs: Long) {
        mainThreadAliveAtMs = nowMs
    }

    internal fun resetForTest() {
        mainThreadAliveAtMs = 0L
    }
}
