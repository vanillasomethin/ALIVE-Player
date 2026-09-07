package com.alive.player.worker

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.alive.player.admin.OwnerSetup
import java.io.File
import java.io.FileInputStream

/**
 * Single place that talks to PackageInstaller for OTA self-updates. All installs —
 * silent and operator-confirmed alike — flow through UpdateCheckWorker, which is the
 * only caller of [commit] (the Settings button funnels into it via checkNow so the
 * server is revalidated before anything installs).
 *
 * The contract callers rely on: [commit] is only invoked when the install can
 * actually complete — either [canInstallSilently] (and the device can relaunch
 * itself, [canRelaunchUiAfterInstall]), or an operator is in Settings
 * (UpdateGate.userActionAllowed) ready to see the system confirm dialog. That is
 * what keeps install prompts from ever appearing over kiosk playback.
 */
object UpdateInstaller {

    /**
     * True when a committed session will install without a confirm dialog:
     * - Device Owner on ANY supported API level — AOSP's PackageInstallerSession
     *   has exempted the device owner from user confirmation on every release this
     *   app supports (isInstallerDeviceOwnerLocked predates O; API 31's
     *   setRequireUserAction only formalized the request side), so the zero-touch
     *   fleet on Android 8-11 self-updates unattended too, or
     * - Android 12+ where this app is its own installer-of-record — true from the
     *   first successful self-update onward — combined with the manifest-declared
     *   UPDATE_PACKAGES_WITHOUT_USER_ACTION permission.
     * Everything else needs the system confirm dialog.
     */
    fun canInstallSilently(context: Context): Boolean {
        if (OwnerSetup.isDeviceOwner(context)) return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return runCatching {
            context.packageManager.getInstallSourceInfo(context.packageName)
                .installingPackageName == context.packageName
        }.getOrDefault(false)
    }

    /**
     * True when, after the install kills every app process, something is guaranteed
     * to bring playback back up: the HOME claim (device owner), pre-Q background
     * activity starts, or an overlay grant. An unattended install on any other device
     * would trade a running ad loop for a dead screen — worse than staying outdated —
     * so UpdateCheckWorker refuses to auto-commit there and leaves it to the Settings
     * operator path (a human is present to relaunch if needed).
     */
    fun canRelaunchUiAfterInstall(context: Context): Boolean =
        OwnerSetup.isDeviceOwner(context) ||
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            android.provider.Settings.canDrawOverlays(context)

    /**
     * Streams [apk] into a fresh PackageInstaller session and commits it. Stale
     * sessions this app created earlier are abandoned first, so repeated checks can
     * never pile up sessions (each of which could surface its own prompt).
     * The result lands in UpdateInstallReceiver.
     *
     * Synchronized: the periodic worker, the one-shot worker and the Settings button
     * can all reach here; unserialized they would abandon each other's live session —
     * including one whose confirm dialog is on screen. Unattended, a session committed
     * within the last 10 minutes is treated as in-flight and left alone entirely;
     * older committed ones are reclaimed (e.g. a swallowed PENDING nobody will ever
     * answer). For an operator-requested install the in-flight guard yields — see
     * [inFlightGuardBlocks].
     *
     * [operatorRequested] is intent, not presence: true only when this commit exists
     * BECAUSE an operator asked for the dialog (the worker's non-silent path with the
     * gate open). The silent path always passes false — an operator merely browsing
     * Settings while a periodic silent install is mid-flight must not cause that
     * session to be abandoned and re-streamed.
     */
    @Synchronized
    fun commit(context: Context, apk: File, operatorRequested: Boolean) {
        val installer = context.packageManager.packageInstaller
        val prefs = com.alive.player.settings.DevicePrefs(context)

        val sessions = installer.mySessions
        // SessionInfo.isCommitted is API 29 and createdMillis API 30 — touching them
        // on API 26-29 panels throws NoSuchMethodError (an Error, so no catch in
        // the worker chain saves the install; commit dies on its first line forever).
        // Below R skip the in-flight probe entirely. That is safe without it:
        // checkMutex + @Synchronized serialize every caller (periodic, one-shot and
        // the Settings button), and the persisted session id keeps the receiver from
        // misreading ABORTED broadcasts of swept sessions. Worst case a re-commit
        // abandons an in-flight twin and re-streams the same SHA-verified APK —
        // convergent, and strictly better than the pre-guard crash.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val nowMs = System.currentTimeMillis()
            if (sessions.any { inFlightGuardBlocks(operatorRequested, it.isCommitted, it.createdMillis, nowMs) }) return
        }

        // Park the persisted id on 0 (never a real session id) for the sweep below:
        // abandonSession delivers an async ABORTED carrying the old id, and until the
        // new id is persisted further down that broadcast would still match — the
        // receiver would record a phantom operator Cancel (markUpdateNeedsUserAction)
        // for a session nobody cancelled. 0 also beats -1 here: -1 is what the
        // receiver's getIntExtra returns for a broadcast missing the extra entirely.
        prefs.setPendingInstallSessionId(0)
        sessions.forEach { info ->
            runCatching { installer.abandonSession(info.sessionId) }
        }

        val sessionParams = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && canInstallSilently(context)) {
            sessionParams.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }

        val sessionId = installer.createSession(sessionParams)
        // Persisted BEFORE commit: the receiver only honours statuses carrying this id,
        // so ABORTED broadcasts from the sessions abandoned above (or pruned by the OS)
        // can never be misread as an operator pressing Cancel.
        prefs.setPendingInstallSessionId(sessionId)
        installer.openSession(sessionId).use { session ->
            FileInputStream(apk).use { input ->
                session.openWrite("update", 0, apk.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
            val statusIntent = Intent(context, UpdateInstallReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                sessionId,
                statusIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(pendingIntent.intentSender)
        }
    }

    /**
     * Deletes cached update APKs for versions other than [keep]. Files live at
     * cache/assets/player-update/<versionCode>/<sha>.apk (AssetDownloader layout),
     * so pruning siblings of keep's version directory drops superseded downloads.
     * Also drops each pruned version's assets DB row — download() registers APKs
     * there, and orphaned rows would keep counting against evictLru's 2GB budget,
     * evicting real media early.
     */
    suspend fun pruneOldUpdates(context: Context, keep: File) {
        val versionDir = keep.parentFile ?: return
        val updateRoot = versionDir.parentFile ?: return
        val assetDao = com.alive.player.data.AppDatabase.get(context).assetDao()
        updateRoot.listFiles()?.forEach { dir ->
            if (dir.isDirectory && dir.name != versionDir.name) {
                runCatching { assetDao.delete(UPDATE_CONTENT_ID, dir.name) }
                runCatching { dir.deleteRecursively() }
            }
        }
    }

    /** Removes every cached update APK + its assets DB rows — nothing is pending. */
    suspend fun clearAllUpdates(context: Context) {
        val cacheRoot = context.getExternalFilesDir("cache") ?: context.cacheDir
        val updateRoot = File(cacheRoot, "assets/$UPDATE_CONTENT_ID")
        val assetDao = com.alive.player.data.AppDatabase.get(context).assetDao()
        updateRoot.listFiles()?.forEach { dir ->
            runCatching { assetDao.delete(UPDATE_CONTENT_ID, dir.name) }
        }
        runCatching { updateRoot.deleteRecursively() }
    }

    /** AssetDownloader contentId under which update APKs are cached. */
    const val UPDATE_CONTENT_ID = "player-update"

    internal const val IN_FLIGHT_WINDOW_MS = 10L * 60 * 1000

    /**
     * Whether an existing session may block this commit. Only a committed session
     * younger than [IN_FLIGHT_WINDOW_MS] can — and never for an operator-REQUESTED
     * install. The guard exists so the periodic and one-shot workers cannot abandon
     * each other's live silent install, and that protection must hold even while an
     * operator happens to be browsing Settings — which is why the parameter is the
     * caller's intent, not UpdateGate presence. When the operator taps Install, the
     * only session the guard could protect is one whose PENDING was swallowed during
     * playback (or whose dialog that same operator is looking at) — either way,
     * abandoning it and re-committing re-delivers PENDING_USER_ACTION and the
     * receiver (gate open) launches the dialog immediately. Blocking instead is how
     * the Install button used to silently no-op for up to ten minutes.
     */
    internal fun inFlightGuardBlocks(
        operatorRequested: Boolean,
        sessionCommitted: Boolean,
        sessionCreatedMs: Long,
        nowMs: Long,
    ): Boolean =
        !operatorRequested && sessionCommitted && nowMs - sessionCreatedMs < IN_FLIGHT_WINDOW_MS

    /**
     * Whether a PackageInstaller failure status is deterministic — the same build
     * will fail the same way on every retry, so re-committing it is a forever-loop,
     * not persistence. INVALID is a malformed APK; INCOMPATIBLE is a signature
     * mismatch (exactly what a signing-scheme migration produces fleet-wide).
     * Everything else (generic FAILURE, BLOCKED, CONFLICT, STORAGE) can genuinely
     * clear on its own — storage freed, restriction lifted — and stays retryable.
     */
    internal fun isPermanentInstallFailure(status: Int): Boolean =
        status == PackageInstaller.STATUS_FAILURE_INVALID ||
            status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE
}
