package com.alive.player.service

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import com.alive.player.R

/**
 * Runs in a separate OS process (":watchdog", see AndroidManifest) so it keeps working
 * even if the main process's main thread fully freezes (ANR) — PlaybackWatchdog, by
 * contrast, runs a Handler on that same main Looper and would freeze right along with
 * it. This service can't detect a frozen main thread by calling into it (a frozen
 * process can't answer Binder calls); instead it reads the plain heartbeat file the
 * main process writes from a background thread (ProcessHeartbeat), and if that file
 * goes stale, kills the main process outright and relaunches it.
 *
 * Killing a sibling process of the same app is safe and doesn't need any special
 * permission: all of an app's declared processes (default + ":watchdog") share one
 * Linux UID, and the kill(2) syscall permits a same-UID sender.
 */
class WatchdogService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val checkRunnable = object : Runnable {
        override fun run() {
            checkAndRecover()
            handler.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        handler.postDelayed(checkRunnable, CHECK_INTERVAL_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Stop requests arrive as a normal START (see requestStop) rather than
        // stopService(), so onCreate's startForeground() above always runs first and
        // settles the startForegroundService() promise. Calling stopService() while
        // that promise is still outstanding — i.e. before this process has finished
        // spawning — makes the system throw RemoteServiceException into the app about
        // five seconds later, crashing it. Reproduced on a HiSilicon panel by firing
        // the kiosk exit a few seconds after a cold start.
        if (intent?.action == ACTION_STOP) {
            // startForeground() again, unconditionally: onCreate only runs for the FIRST
            // start, so when this service is already alive nothing else would settle the
            // promise created by this particular startForegroundService() call. Calling
            // it on an already-foreground service just refreshes the notification.
            startForeground(NOTIF_ID, buildNotification())
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(checkRunnable)
        super.onDestroy()
    }

    private fun checkAndRecover() {
        val staleMs = ProcessHeartbeat.millisSinceLastWrite(applicationContext) ?: return
        if (staleMs < STALE_THRESHOLD_MS) return

        val mainPid = findMainProcessPid() ?: return
        // The heartbeat is only ever stale because the main process is wedged (its
        // background heartbeat writer only stops if the whole process is frozen or
        // dead) — kill it outright and let BootReceiver/HOME-relaunch/our own restart
        // bring it back clean rather than trying to nudge a possibly-corrupted state.
        Process.killProcess(mainPid)
        // runCatching: on Android 15 a background FGS start can throw
        // ForegroundServiceStartNotAllowedException on non-owner installs. Crashing
        // here would START_STICKY-restart this service into a perpetual kill/crash
        // loop; failing quietly leaves recovery to BootReceiver/HOME-relaunch instead.
        runCatching {
            applicationContext.startForegroundService(
                Intent(applicationContext, PlaybackForegroundService::class.java)
            )
        }
    }

    private fun findMainProcessPid(): Int? {
        val am = applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
        return am.runningAppProcesses
            ?.firstOrNull { it.processName == applicationContext.packageName }
            ?.pid
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_watchdog_channel_name),
                NotificationManager.IMPORTANCE_MIN,
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
            .setContentText(getString(R.string.notification_watchdog_active))
            .setSmallIcon(android.R.drawable.ic_menu_revert)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "alive_watchdog"
        private const val NOTIF_ID = 2
        private const val CHECK_INTERVAL_MS = 20_000L
        private const val STALE_THRESHOLD_MS = 90_000L
        private const val ACTION_STOP = "com.alive.player.action.WATCHDOG_STOP"

        fun ensureRunning(context: Context) {
            context.startForegroundService(Intent(context, WatchdogService::class.java))
        }

        /** Shut the watchdog down without racing its own startup — see onStartCommand. */
        fun requestStop(context: Context) {
            context.startForegroundService(
                Intent(context, WatchdogService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
