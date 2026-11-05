package com.alive.player.remote

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.alive.player.ui.PairingActivity
import com.alive.player.ui.PlaybackActivity
import com.alive.player.worker.KioskResumeWorker
import java.lang.ref.WeakReference

/**
 * Admin `exit_kiosk` / `resume_kiosk`: the dashboard's stand-in for the remote's BACK x5,
 * for TVs whose remotes barely respond. Named and narrow like every other command here:
 * it can only take the player off the screen and bring it back, nothing else.
 *
 * An exit takes the shop's screen off air, so it always arrives with a return time and
 * [KioskResumeWorker] brings the player back by itself when it is up. The 5x-BACK exit has
 * no such timer (a technician is standing there); a remote one has nobody there to
 * remember it.
 */
object KioskControl {
    private var current: WeakReference<PlaybackActivity>? = null

    const val EXIT_MINUTES_MIN = 5
    const val EXIT_MINUTES_MAX = 240

    /**
     * The return time from an `exit_kiosk` message, or null when it is missing or out of
     * range. Null means DO NOTHING, never "use a default": the server always sends one, and
     * a garbled message must not be able to park a shop's screen off air. Mirrors
     * `readExitMinutes()` in the website's `src/lib/kiosk-exit.ts`.
     */
    fun exitMinutes(raw: String?): Int? =
        raw?.toIntOrNull()?.takeIf { it in EXIT_MINUTES_MIN..EXIT_MINUTES_MAX }

    /** PlaybackActivity registers itself while it is the screen's window. */
    fun attach(activity: PlaybackActivity) { current = WeakReference(activity) }
    fun detach(activity: PlaybackActivity) { if (current?.get() === activity) current = null }

    /**
     * Runs the same teardown as the 5x-BACK gesture. False when the playback screen is not
     * up (already exited, or the TV is showing its own launcher), in which case nothing is
     * scheduled: there is nothing to come back from.
     */
    fun exit(context: Context, minutes: Int): Boolean {
        val activity = current?.get()?.takeIf { !it.isFinishing && !it.isDestroyed } ?: return false
        KioskResumeWorker.schedule(context, minutes)
        Handler(Looper.getMainLooper()).post { activity.exitKiosk() }
        return true
    }

    /**
     * Brings the player back to the front. Goes through PairingActivity, which forwards a
     * paired screen to PlaybackActivity (a singleTask) whose onCreate / onNewIntent re-claim
     * HOME and the periodic workers, so this is also the undo for the exit. Harmless when the
     * player is already in front. Legal from the background for the same reason the boot
     * check's launch is: Device Owner is exempt, and below Android 10 there is no limit.
     */
    fun resume(context: Context) {
        KioskResumeWorker.cancel(context)
        runCatching {
            context.startActivity(
                Intent(context, PairingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
