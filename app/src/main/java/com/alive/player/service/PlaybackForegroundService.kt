package com.alive.player.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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

    // Proves the main thread is actually turning over — if this Looper wedges (ANR)
    // the stamp freezes and the heartbeat file the IO writer relays it into goes
    // stale, which is what lets WatchdogService catch an ANR and not just a
    // whole-process freeze.
    // Stamped at half the relay period: the file's content can lag real main-thread
    // aliveness by one stamp period plus one relay period, and keeping that worst
    // case near the old writer's ~10s preserves the staleness headroom the 90s
    // threshold was tuned against (NTP forward-steps eat into it).
    private val mainAliveHandler = Handler(Looper.getMainLooper())
    private val mainAliveRunnable = object : Runnable {
        override fun run() {
            ProcessHeartbeat.noteMainThreadAlive()
            mainAliveHandler.postDelayed(this, STAMP_PERIOD_MS)
        }
    }

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

        // Cross-process watchdog: keep it running, and feed it a liveness signal.
        // The main Looper stamps aliveness (cheap, no I/O) and a background thread
        // relays the stamp to disk — so the file goes stale when the main thread
        // ANRs (stamp freezes) OR when the whole process freezes (writer stops),
        // and a wedged main thread can no longer hide behind a healthy IO thread.
        // Gated on pairing: a stop request for a service that is NOT running is a
        // START (onCreate runs before requestStop's action is seen), so a
        // decommission wipe can spin this service up for a moment — unpaired, that
        // zombie lifecycle must not re-arm the watchdog the wipe just stopped or
        // recreate the heartbeat file it just cleared, or the watchdog kills the
        // fresh pairing screen ~90s later and resurrects playback. Pairing precedes
        // every legitimate start of this service (PlaybackActivity and BootReceiver
        // both check it), so nothing real loses its watchdog.
        if (com.alive.player.settings.DevicePrefs(applicationContext).isPaired()) {
            WatchdogService.ensureRunning(applicationContext)
            mainAliveRunnable.run() // onCreate is on the main Looper: stamp now, then every period
            heartbeatJob = CoroutineScope(Dispatchers.IO).launch {
                while (isActive) {
                    ProcessHeartbeat.writeMainThreadStamp(applicationContext)
                    delay(HEARTBEAT_PERIOD_MS)
                }
            }
        }
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Stop requests come in as a normal START (see requestStop) so onCreate's
        // startForeground() always runs first — stopService() while the
        // startForegroundService() promise is still outstanding crashes the app with
        // RemoteServiceException ~5s later. Same hazard fixed in WatchdogService.
        if (intent?.action == ACTION_STOP) {
            // See WatchdogService: onCreate only runs on first start, so settle this
            // start's promise explicitly before stopping.
            startForeground(1, buildNotification())
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        mainAliveHandler.removeCallbacks(mainAliveRunnable)
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
        private const val ACTION_STOP = "com.alive.player.action.PLAYBACK_STOP"
        private const val HEARTBEAT_PERIOD_MS = 10_000L
        private const val STAMP_PERIOD_MS = 5_000L

        /** Stop playback without racing this service's own startup — see onStartCommand. */
        fun requestStop(context: Context) {
            context.startForegroundService(
                Intent(context, PlaybackForegroundService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
