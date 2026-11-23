package com.alive.player.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.alive.player.admin.OwnerSetup
import com.alive.player.R
import com.alive.player.playback.PlaybackEngine
import com.alive.player.playback.PlaybackWatchdog
import com.alive.player.settings.DevicePrefs
import com.alive.player.worker.HeartbeatScheduler
import com.alive.player.worker.PlanFetchScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

class PlaybackForegroundService : Service() {

    lateinit var engine: PlaybackEngine
        private set

    private lateinit var watchdog: PlaybackWatchdog
    private lateinit var wakeLock: PowerManager.WakeLock
    private var heartbeatJob: Job? = null
    private var pushFallbackJob: Job? = null
    private var reportNudgeJob: Job? = null
    private var networkWatchdogJob: Job? = null

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
        if (DevicePrefs(applicationContext).isPaired()) {
            WatchdogService.ensureRunning(applicationContext)
            mainAliveRunnable.run() // onCreate is on the main Looper: stamp now, then every period
            heartbeatJob = CoroutineScope(Dispatchers.IO).launch {
                while (isActive) {
                    ProcessHeartbeat.writeMainThreadStamp(applicationContext)
                    delay(HEARTBEAT_PERIOD_MS)
                }
            }
            startPushFallbackPoll()
            startReportNudge()
            startNetworkWatchdog()
        }
    }

    /**
     * Keeps schedule changes flowing to screens that can never receive an FCM push.
     *
     * Some panels never complete Google device check-in: they cold-boot with no RTC
     * battery, the vendor service stamps the firmware build date (years in the past),
     * and GMS check-in runs ~50s into boot — inside that window — so its TLS handshake
     * is rejected. NTP corrects the clock minutes later, far too late, and Firebase
     * treats AUTHENTICATION_FAILED as terminal ("won't retry"), so the screen holds no
     * token for the whole boot session. Field-confirmed on two MStar panels: check-in
     * failed while their clock read 935 days in the past, while two sibling panels that
     * had only soft-rebooted (clock preserved) registered normally.
     *
     * With no token there is no plan_updated push, so such a screen would only notice a
     * schedule change on the periodic PlanFetchWorker — a 15-minute floor WorkManager
     * refuses to go below, and closer to 30 minutes worst case with no flex window.
     * Polling here, from the service that is already running for playback, closes that
     * gap without changing the cadence healthy screens rely on.
     *
     * Self-disabling: the token is re-read every tick, so the moment one exists (a boot
     * with a good clock, or check-in's 12-hourly retry succeeding) this stops issuing
     * fetches and the push path takes over again.
     */
    private fun startPushFallbackPoll() {
        pushFallbackJob = CoroutineScope(Dispatchers.IO).launch {
            // Stagger across the fleet: a mains outage brings every screen in a store
            // back at once, and they would otherwise poll on the same second forever.
            delay(Random.nextLong(PUSH_FALLBACK_INTERVAL_MS))
            while (isActive) {
                if (DevicePrefs(applicationContext).getFcmToken() == null) {
                    // Cheap when nothing changed: the fetch is a single conditional
                    // request that the server answers 304 off the cached plan hash.
                    PlanFetchScheduler.schedulePollIfIdle(applicationContext)
                }
                delay(PUSH_FALLBACK_INTERVAL_MS)
            }
        }
    }

    /**
     * Sends a heartbeat every few minutes from the service that is already running for
     * playback, instead of leaving "this screen is alive" to the periodic WorkManager job.
     *
     * That job has a 15-minute floor and the console calls a screen offline after 20, which
     * leaves five minutes of slack. WorkManager is free to run it late (battery and standby
     * policy, a network that is not yet validated after a router blip), and a TV that is
     * playing the whole time then reads as offline for no reason. While this service runs
     * the screen IS playing, so it is the most honest clock there is.
     *
     * It reuses the one-shot heartbeat (same worker, same token reclaim, same incident
     * batching) rather than a second code path, and the periodic job stays as the backstop
     * for when this service is not running. Staggered so a store coming back from a power
     * cut does not beat on the same second forever, like [startPushFallbackPoll].
     */
    private fun startReportNudge() {
        reportNudgeJob = CoroutineScope(Dispatchers.IO).launch {
            delay(Random.nextLong(REPORT_NUDGE_INTERVAL_MS))
            while (isActive) {
                runCatching { HeartbeatScheduler.scheduleImmediate(applicationContext) }
                delay(REPORT_NUDGE_INTERVAL_MS)
            }
        }
    }

    /**
     * Brings a screen back from a wedged network by itself. A TV that lost its Wi-Fi cannot
     * receive any remote command, so the only recovery is its own. Every minute it checks for
     * VALIDATED internet (not just a connected Wi-Fi, which stays "connected" while dead);
     * [NetworkRecoveryPolicy] decides when to cycle Wi-Fi and when to reboot, and needs Device
     * Owner for both. This service is stopped by Exit player and by the 5x-BACK exit, so a
     * technician at the TV is never fought. Reboot times are kept in prefs because a reboot
     * wipes memory, and the daily cap must survive it.
     */
    private fun startNetworkWatchdog() {
        networkWatchdogJob = CoroutineScope(Dispatchers.IO).launch {
            val prefs = applicationContext.getSharedPreferences("network_watchdog", Context.MODE_PRIVATE)
            var state = NetworkRecoveryPolicy.State(
                lastWifiResetMs = prefs.getLong("lastWifiResetMs", 0L),
                rebootTimesMs = prefs.getString("reboots", "").orEmpty().split(',').mapNotNull { it.toLongOrNull() },
            )
            // Let the TV finish booting and joining Wi-Fi before the first judgement.
            delay(NETWORK_WATCHDOG_INTERVAL_MS)
            while (isActive) {
                val now = System.currentTimeMillis()
                val step = NetworkRecoveryPolicy.next(
                    state, now, hasValidatedInternet(), OwnerSetup.isDeviceOwner(applicationContext),
                )
                state = step.state
                when (step.action) {
                    NetworkRecoveryPolicy.Action.NONE -> Unit
                    NetworkRecoveryPolicy.Action.RESET_WIFI -> {
                        prefs.edit().putLong("lastWifiResetMs", state.lastWifiResetMs).apply()
                        Log.w(TAG, "No internet for a while: switching Wi-Fi off and on")
                        resetWifi()
                    }
                    NetworkRecoveryPolicy.Action.REBOOT -> {
                        // Written (and committed) before the reboot so the daily cap sees it.
                        prefs.edit().putString("reboots", state.rebootTimesMs.joinToString(",")).commit()
                        Log.w(TAG, "Still no internet: rebooting the TV")
                        OwnerSetup.rebootDevice(applicationContext)
                    }
                }
                delay(NETWORK_WATCHDOG_INTERVAL_MS)
            }
        }
    }

    private fun hasValidatedInternet(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return true // unknown is not an outage
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** Only a Device Owner may toggle Wi-Fi on Android 10+; elsewhere this is a harmless no-op. */
    @Suppress("DEPRECATION")
    private suspend fun resetWifi() {
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        if (runCatching { wifi.setWifiEnabled(false) }.getOrDefault(false)) {
            delay(5_000)
            runCatching { wifi.setWifiEnabled(true) }
        } else {
            Log.w(TAG, "This TV would not let the player switch Wi-Fi off; the reboot step is the fallback")
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
        pushFallbackJob?.cancel()
        reportNudgeJob?.cancel()
        networkWatchdogJob?.cancel()
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
        private const val TAG = "NetworkWatchdog"
        private const val CHANNEL_ID = "alive_playback"
        private const val ACTION_STOP = "com.alive.player.action.PLAYBACK_STOP"
        private const val HEARTBEAT_PERIOD_MS = 10_000L
        private const val STAMP_PERIOD_MS = 5_000L

        /** Push-fallback poll cadence — see [startPushFallbackPoll]. */
        private const val PUSH_FALLBACK_INTERVAL_MS = 60_000L

        /** Heartbeat nudge cadence — see [startReportNudge]. Well inside the console's 20 min. */
        private const val REPORT_NUDGE_INTERVAL_MS = 5 * 60_000L

        /** How often the network watchdog looks for validated internet. */
        private const val NETWORK_WATCHDOG_INTERVAL_MS = 60_000L

        /** Stop playback without racing this service's own startup — see onStartCommand. */
        fun requestStop(context: Context) {
            context.startForegroundService(
                Intent(context, PlaybackForegroundService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
