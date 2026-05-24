package com.partner.alive.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.partner.alive.data.AppDatabase
import com.partner.alive.data.Incident
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class PlaybackWatchdog(
    private val engine: PlaybackEngine,
    private val context: Context,
    private val onFallback: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var lastPositionMs = -1L
    private var stuckTicks = 0
    private var restartAttempts = 0
    private var checkRunnable: Runnable? = null

    companion object {
        private const val CHECK_INTERVAL_MS = 5_000L
        private const val STUCK_TICKS_THRESHOLD = 6
        private const val MAX_RESTART_ATTEMPTS = 3
    }

    fun start() { scheduleCheck() }

    fun stop() { checkRunnable?.let { handler.removeCallbacks(it) } }

    private fun scheduleCheck() {
        val r = Runnable { check(); scheduleCheck() }
        checkRunnable = r
        handler.postDelayed(r, CHECK_INTERVAL_MS)
    }

    private fun check() {
        val pos = engine.getCurrentPositionMs()
        if (pos == null) { stuckTicks = 0; lastPositionMs = -1; return }
        if (pos == lastPositionMs) {
            stuckTicks++
            if (stuckTicks >= STUCK_TICKS_THRESHOLD) {
                stuckTicks = 0
                onStuck()
            }
        } else {
            stuckTicks = 0
            restartAttempts = 0
        }
        lastPositionMs = pos
    }

    private fun onStuck() {
        logIncident("STUCK_PLAYBACK")
        if (restartAttempts < MAX_RESTART_ATTEMPTS) {
            restartAttempts++
            engine.restartCurrentItem()
        } else {
            restartAttempts = 0
            logIncident("FALLBACK_TRIGGERED")
            onFallback()
        }
    }

    private fun logIncident(type: String) {
        CoroutineScope(Dispatchers.IO).launch {
            AppDatabase.get(context).incidentDao().insert(
                Incident(type = type, timestampUtcEpochMs = System.currentTimeMillis(), metadataJson = null)
            )
        }
    }
}
