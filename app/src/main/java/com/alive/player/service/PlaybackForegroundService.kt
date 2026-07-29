package com.alive.player.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.alive.player.R
import com.alive.player.playback.PlaybackEngine
import com.alive.player.playback.PlaybackWatchdog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PlaybackForegroundService : Service() {

    lateinit var engine: PlaybackEngine
        private set

    private lateinit var watchdog: PlaybackWatchdog
    private lateinit var wakeLock: PowerManager.WakeLock
    private var heartbeatJob: Job? = null

    inner class LocalBinder : Binder() {
        fun getService() = this@PlaybackForegroundService
    }

    private val binder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(1, buildNotification())
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "alive:playback").apply { acquire() }
        engine = PlaybackEngine(applicationContext)
        engine.startLoop()
        watchdog = PlaybackWatchdog(engine, applicationContext) {
            engine.startLoop()
        }
        watchdog.start()

        // Cross-process watchdog: keep it running, and feed it a liveness signal from
        // a background thread so it still gets written even if the main Looper wedges.
        WatchdogService.ensureRunning(applicationContext)
        heartbeatJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                ProcessHeartbeat.write(applicationContext)
                delay(10_000)
            }
        }
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        heartbeatJob?.cancel()
        watchdog.stop()
        engine.stop()
        if (wakeLock.isHeld) wakeLock.release()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_playing))
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "alive_playback"
    }
}
