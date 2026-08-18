package com.alive.player.data

import android.content.Context
import android.content.Intent
import android.util.Log
import com.alive.player.service.PlaybackForegroundService
import com.alive.player.settings.DevicePrefs
import com.alive.player.ui.PairingActivity
import com.alive.player.worker.HeartbeatScheduler
import com.alive.player.worker.PlanFetchScheduler
import com.alive.player.worker.UpdateScheduler

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

    suspend fun wipe(context: Context, reason: String, removedRemotely: Boolean = true) {
        val appContext = context.applicationContext
        Log.i(TAG, "Decommissioning device: $reason")

        // Workers and playback first, so nothing repopulates what is being cleared
        // or uploads against a token that is about to vanish.
        runCatching { HeartbeatScheduler.cancel(appContext) }
        runCatching { PlanFetchScheduler.cancel(appContext) }
        runCatching { UpdateScheduler.cancel(appContext) }
        runCatching { appContext.stopService(Intent(appContext, PlaybackForegroundService::class.java)) }

        val db = AppDatabase.get(appContext)
        runCatching { db.planCacheDao().clear() }
        runCatching { db.proofEventDao().clearAll() }
        runCatching { db.assetDao().clearAll() }
        runCatching { db.downloadJobDao().clearAll() }
        runCatching { db.incidentDao().clearAll() }
        runCatching { appContext.getExternalFilesDir("cache")?.deleteRecursively() }
        runCatching { appContext.cacheDir.deleteRecursively() }
        DevicePrefs(appContext).clearAll()

        // Remote removal (deleted in admin via 410/FCM): leave a flag — set AFTER
        // clearAll so it survives — so PairingActivity tells the operator the screen was
        // removed and needs re-pairing, instead of dropping to pairing with no context.
        // Survives even when this launch is dropped (non-owner 29+): BootReceiver brings
        // pairing up next boot and the banner still shows. Manual reset skips this.
        if (removedRemotely) DevicePrefs(appContext).setDecommissioned()

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

    private const val TAG = "DeviceDecommissioner"
}
