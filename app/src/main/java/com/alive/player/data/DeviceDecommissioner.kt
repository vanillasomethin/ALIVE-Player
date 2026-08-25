package com.alive.player.data

import android.content.Context
import android.content.Intent
import android.util.Log
import com.alive.player.service.PlaybackForegroundService
import com.alive.player.service.ProcessHeartbeat
import com.alive.player.service.WatchdogService
import com.alive.player.settings.DevicePrefs
import com.alive.player.ui.PairingActivity
import com.alive.player.worker.HeartbeatScheduler
import com.alive.player.worker.PlanFetchScheduler
import com.alive.player.worker.UpdateScheduler
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Fully retires this screen's local identity and content: cancels the periodic
 * workers, stops playback, wipes the Room tables and media cache, clears the
 * pairing, and returns to PairingActivity (which claims a fresh identity and shows
 * a new pairing code).
 *
 * Triggered when the screen is deleted in the admin panel — via the `decommission`
 * FCM push for screens that are reachable, or via the 410 the device API answers
 * once the row is gone (PlanFetch/Heartbeat/PopUpload/UpdateCheck workers all
 * handle it). Without this, a deleted screen kept playing its cached plan
 * indefinitely and would silently re-register itself through the 401 re-claim
 * path. Also the implementation behind the Settings "Reset Device" button, so
 * manual and remote decommission behave identically.
 */
object DeviceDecommissioner {

    // NonCancellable: the scheduler cancels below cancel the very worker that is
    // running this wipe (a 410 arrives inside PlanFetch/Heartbeat/PopUpload/
    // UpdateCheck), which cancels this coroutine — without this every subsequent
    // suspending dao.clear() would throw CancellationException (swallowed by
    // runCatching) and the "wiped" device would keep its plan/PoP/asset rows.
    suspend fun wipe(context: Context, reason: String): Unit = withContext(NonCancellable) {
        val appContext = context.applicationContext
        Log.i(TAG, "Decommissioning device: $reason")

        // Freshen the liveness signal before anything else: if the wipe starts while
        // the heartbeat is already >90s stale (playback down, 410 arriving via a
        // worker), a live watchdog's next 20s check would SIGKILL this process
        // mid-wipe — after the prefs clear but before the DB clears — stranding an
        // unpaired zombie that still plays its cached plan. A fresh write buys the
        // full watchdog threshold, far longer than the wipe needs to reach the
        // watchdog stop below.
        ProcessHeartbeat.write(appContext)

        // Pairing state goes FIRST: requestStop on a service that is not running is
        // itself a START (onCreate runs before the stop action is seen), and
        // PlaybackForegroundService.onCreate arms the watchdog + heartbeat writer
        // only when paired — cleared-first, that brief zombie lifecycle can't re-arm
        // what this wipe is about to tear down. Workers that fire mid-wipe see no
        // token and fail without reaching the reclaim path.
        DevicePrefs(appContext).clearAll()

        // Playback and the watchdog next, so nothing repopulates what is being
        // cleared or uploads against a token that no longer exists. The watchdog must go
        // down BEFORE playback (same order as PlaybackActivity.exitKiosk): it reads
        // the process-heartbeat file, and with playback stopped the writer stops —
        // left running, ~90s later it would kill the main process (tearing down the
        // pairing screen this wipe is about to show) and restart playback on a
        // device that no longer exists. Both stops ride requestStop (a normal START
        // with an action) so a still-outstanding startForegroundService() promise
        // from BootReceiver can't turn into a RemoteServiceException ~5s later.
        stopServiceSafely(appContext, WatchdogService::class.java) { WatchdogService.requestStop(appContext) }
        stopServiceSafely(appContext, PlaybackForegroundService::class.java) { PlaybackForegroundService.requestStop(appContext) }
        // Stale-heartbeat belt-and-braces: if anything restarts the watchdog later
        // (START_STICKY revival), an absent file reads as "no signal yet", not as a
        // wedged main process to kill.
        ProcessHeartbeat.clear(appContext)
        runCatching { HeartbeatScheduler.cancel(appContext) }
        runCatching { PlanFetchScheduler.cancel(appContext) }
        runCatching { UpdateScheduler.cancel(appContext) }

        val db = AppDatabase.get(appContext)
        runCatching { db.planCacheDao().clear() }
        runCatching { db.proofEventDao().clearAll() }
        runCatching { db.assetDao().clearAll() }
        runCatching { db.downloadJobDao().clearAll() }
        runCatching { db.incidentDao().clearAll() }
        runCatching { appContext.getExternalFilesDir("cache")?.deleteRecursively() }
        runCatching { appContext.cacheDir.deleteRecursively() }

        // CLEAR_TASK tears down PlaybackActivity so the dead plan can't stay on
        // screen. Legal from the background on Device-Owner installs and API < 29;
        // where it is dropped (non-owner 29+), the next boot lands on pairing via
        // BootReceiver.
        runCatching {
            appContext.startActivity(
                Intent(appContext, PairingActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
            )
        }
    }

    /** requestStop rides startForegroundService, which Android 12+ can reject from
     *  the background on non-owner installs. When it throws, no start promise of
     *  ours can be outstanding (the same restriction would have blocked it), so a
     *  plain stopService cannot hit the RemoteServiceException window and is the
     *  safe fallback — a no-op when the service isn't running. */
    private fun stopServiceSafely(appContext: Context, service: Class<*>, requestStop: () -> Unit) {
        runCatching { requestStop() }.onFailure {
            runCatching { appContext.stopService(Intent(appContext, service)) }
        }
    }

    private const val TAG = "DeviceDecommissioner"
}
