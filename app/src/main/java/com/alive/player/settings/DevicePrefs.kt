package com.alive.player.settings

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class DevicePrefs(private val context: Context) {
    /**
     * Lazy, and process-wide cached, because building it is genuinely expensive:
     * MasterKey.Builder().build() hits the Android Keystore and
     * EncryptedSharedPreferences.create() does crypto plus file I/O. DevicePrefs is
     * constructed on hot paths — every remote key press, every countdown tick, every
     * media item — and doing keystore work there wedges the main thread on slow TV
     * hardware (blank screen, remote stops responding).
     *
     * Only the credential accessors below touch this. Everything else (orientation,
     * remote config, diagnostics, fetch status) lives in the plain [statusPrefs], so
     * those reads never pay the keystore cost at all.
     */
    private val prefs: android.content.SharedPreferences
        get() = encrypted(context)

    // Plain prefs for non-sensitive diagnostic state. getSharedPreferences is itself
    // cached by the framework, so this stays cheap.
    private val statusPrefs = context.getSharedPreferences(STATUS_PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Called after admin confirms the device via the pairing code.
     * Sets paired_at so [isPaired] returns true and the device proceeds to playback.
     */
    fun storePairing(token: String, deviceId: String) {
        prefs.edit()
            .putString(KEY_DEVICE_TOKEN, token)
            .putString(KEY_DEVICE_ID, deviceId)
            .putLong(KEY_PAIRED_AT, System.currentTimeMillis())
            .remove(KEY_PAIRING_CODE)    // no longer needed once confirmed
            .apply()
        invalidateUploadedFcmToken()
    }

    /**
     * Called right after /api/device/claim when the device is not yet admin-confirmed.
     * Stores token + pairingCode but does NOT set paired_at, so [isPaired] stays false.
     */
    fun storePairingPending(token: String, deviceId: String, pairingCode: String) {
        prefs.edit()
            .putString(KEY_DEVICE_TOKEN, token)
            .putString(KEY_DEVICE_ID, deviceId)
            .putString(KEY_PAIRING_CODE, pairingCode)
            // Pending means NOT confirmed — a paired_at left over from a previous
            // pairing must not survive, or isPaired() would report a pending device
            // as confirmed and keep playing a dead identity's cached plan.
            .remove(KEY_PAIRED_AT)
            .apply()
        invalidateUploadedFcmToken()
    }

    /** Any pairing-identity change may mean a fresh server row that has never seen
     *  our FCM token — forget the ack so the next heartbeat re-uploads exactly once. */
    private fun invalidateUploadedFcmToken() {
        statusPrefs.edit().remove(KEY_FCM_TOKEN_UPLOADED).apply()
    }

    /** Marks the device as admin-confirmed so [isPaired] returns true. */
    fun confirmPairing() {
        prefs.edit()
            .putLong(KEY_PAIRED_AT, System.currentTimeMillis())
            .remove(KEY_PAIRING_CODE)
            .apply()
    }

    fun getDeviceToken(): String? = prefs.getString(KEY_DEVICE_TOKEN, null)
    fun getDeviceId(): String? = prefs.getString(KEY_DEVICE_ID, null)
    fun getPairedAt(): Long? = prefs.getLong(KEY_PAIRED_AT, -1L).takeIf { it >= 0 }
    fun getPairingCode(): String? = prefs.getString(KEY_PAIRING_CODE, null)

    /** True only when device has a token AND has been confirmed by admin (paired_at is set). */
    fun isPaired(): Boolean = !getDeviceToken().isNullOrBlank() && getPairedAt() != null

    fun setFetchStatus(status: FetchStatus) {
        statusPrefs.edit()
            .putString(KEY_FETCH_STATUS, status.name)
            .putString(KEY_FETCH_MESSAGE, status.message)
            .putLong(KEY_FETCH_TIME, System.currentTimeMillis())
            .apply()
    }

    fun getFetchStatus(): FetchStatus? {
        val name = statusPrefs.getString(KEY_FETCH_STATUS, null) ?: return null
        val message = statusPrefs.getString(KEY_FETCH_MESSAGE, "") ?: ""
        val time = statusPrefs.getLong(KEY_FETCH_TIME, 0)
        return try { FetchStatus.valueOf(name).also { it.message = message; it.timeMs = time } }
        catch (_: Exception) { null }
    }

    fun markPlanUpdated() {
        statusPrefs.edit().putLong(KEY_PLAN_UPDATED_MS, System.currentTimeMillis()).apply()
    }

    fun getPlanUpdatedMs(): Long = statusPrefs.getLong(KEY_PLAN_UPDATED_MS, 0L)

    fun setOrientationMode(mode: String) {
        statusPrefs.edit().putString(KEY_ORIENTATION, mode).apply()
    }

    /**
     * Portrait is the standard ALIVE mount, so it is the hardcoded default — a freshly
     * flashed screen renders portrait before it has ever talked to the server. The
     * server's Device.orientation still overrides this on the first plan fetch, and the
     * on-device rotate control flips between portrait and portrait-flipped for panels
     * mounted the other way up.
     */
    fun getOrientationMode(): String =
        statusPrefs.getString(KEY_ORIENTATION, ORIENTATION_PORTRAIT) ?: ORIENTATION_PORTRAIT

    fun setFcmToken(token: String) {
        statusPrefs.edit().putString(KEY_FCM_TOKEN, token).apply()
    }

    fun getFcmToken(): String? = statusPrefs.getString(KEY_FCM_TOKEN, null)

    /**
     * The token the SERVER last acknowledged, as opposed to the one FCM last issued
     * ([setFcmToken]). onNewToken's one-shot upload is lossy — it fires before pairing
     * on a fresh install and swallows network failures — so HeartbeatWorker compares
     * these two and re-uploads whenever they differ. Until they match, targeted pushes
     * (plan_updated, decommission) go to a token the device no longer holds.
     */
    fun setUploadedFcmToken(token: String) {
        statusPrefs.edit().putString(KEY_FCM_TOKEN_UPLOADED, token).apply()
    }

    fun getUploadedFcmToken(): String? = statusPrefs.getString(KEY_FCM_TOKEN_UPLOADED, null)

    fun setClockOffsetMs(offsetMs: Long) {
        statusPrefs.edit().putLong(KEY_CLOCK_OFFSET_MS, offsetMs).apply()
    }

    fun getClockOffsetMs(): Long = statusPrefs.getLong(KEY_CLOCK_OFFSET_MS, 0L)

    /** When NTP last answered. Throttles re-syncs; see NtpSyncManager.syncIfNeeded. */
    fun setLastNtpSyncMs(ms: Long) { statusPrefs.edit().putLong(KEY_LAST_NTP_SYNC_MS, ms).apply() }

    fun getLastNtpSyncMs(): Long = statusPrefs.getLong(KEY_LAST_NTP_SYNC_MS, 0L)

    // ── Remote-configurable player behavior (server-driven, no APK rebuild needed) ──

    fun setRetryIntervalMs(ms: Long) { statusPrefs.edit().putLong(KEY_RETRY_INTERVAL_MS, ms).apply() }
    fun getRetryIntervalMs(): Long = statusPrefs.getLong(KEY_RETRY_INTERVAL_MS, DEFAULT_RETRY_INTERVAL_MS)

    fun setTransitionDurationMs(ms: Long) { statusPrefs.edit().putLong(KEY_TRANSITION_DURATION_MS, ms).apply() }
    fun getTransitionDurationMs(): Long = statusPrefs.getLong(KEY_TRANSITION_DURATION_MS, DEFAULT_TRANSITION_DURATION_MS)

    fun setKioskKeyLockEnabled(enabled: Boolean) { statusPrefs.edit().putBoolean(KEY_KIOSK_KEY_LOCK, enabled).apply() }
    fun isKioskKeyLockEnabled(): Boolean = statusPrefs.getBoolean(KEY_KIOSK_KEY_LOCK, true)

    fun setDownloadConnectTimeoutMs(ms: Int) { statusPrefs.edit().putInt(KEY_DL_CONNECT_TIMEOUT_MS, ms).apply() }
    fun getDownloadConnectTimeoutMs(): Int = statusPrefs.getInt(KEY_DL_CONNECT_TIMEOUT_MS, DEFAULT_DL_CONNECT_TIMEOUT_MS)

    fun setDownloadReadTimeoutMs(ms: Int) { statusPrefs.edit().putInt(KEY_DL_READ_TIMEOUT_MS, ms).apply() }
    fun getDownloadReadTimeoutMs(): Int = statusPrefs.getInt(KEY_DL_READ_TIMEOUT_MS, DEFAULT_DL_READ_TIMEOUT_MS)

    // ── OTA update state ───────────────────────────────────────────────────────────
    // A newer APK has been downloaded and verified; it is waiting either for a silent
    // install (device owner / self-installer-of-record) or for an operator to press
    // "Install update" in Settings. Deliberately in plain statusPrefs — nothing secret.

    /** Records a downloaded, hash-verified update. A NEW versionCode resets the
     *  needs-user-action flag: a fresh APK may install silently even if the last one
     *  couldn't (e.g. after the first manual update made us installer-of-record). */
    fun setUpdateReady(versionCode: Int, versionName: String?, apkPath: String) {
        val editor = statusPrefs.edit()
            .putInt(KEY_UPDATE_READY_VC, versionCode)
            .putString(KEY_UPDATE_READY_NAME, versionName)
            .putString(KEY_UPDATE_READY_APK, apkPath)
        if (statusPrefs.getInt(KEY_UPDATE_READY_VC, -1) != versionCode) {
            editor.remove(KEY_UPDATE_NEEDS_USER)
        }
        editor.apply()
    }

    fun getUpdateReadyVersionCode(): Int = statusPrefs.getInt(KEY_UPDATE_READY_VC, -1)
    fun getUpdateReadyVersionName(): String? = statusPrefs.getString(KEY_UPDATE_READY_NAME, null)
    fun getUpdateReadyApkPath(): String? = statusPrefs.getString(KEY_UPDATE_READY_APK, null)

    /** The one PackageInstaller session whose status broadcasts we act on. Persisted
     *  before commit so a status arriving after process death still matches. Statuses
     *  from any other session (e.g. ABORTED fired by our own stale-session cleanup in
     *  UpdateInstaller.commit) must be ignored, not misread as an operator cancel. */
    fun setPendingInstallSessionId(sessionId: Int) {
        statusPrefs.edit().putInt(KEY_UPDATE_SESSION_ID, sessionId).apply()
    }

    fun getPendingInstallSessionId(): Int = statusPrefs.getInt(KEY_UPDATE_SESSION_ID, -1)

    /** Set when a commit came back PENDING_USER_ACTION (or the operator cancelled the
     *  dialog) — tells the worker to stop re-committing and leave it to Settings. */
    fun markUpdateNeedsUserAction() {
        statusPrefs.edit().putBoolean(KEY_UPDATE_NEEDS_USER, true).apply()
    }

    fun updateNeedsUserAction(): Boolean = statusPrefs.getBoolean(KEY_UPDATE_NEEDS_USER, false)

    fun clearUpdateReady() {
        statusPrefs.edit()
            .remove(KEY_UPDATE_READY_VC)
            .remove(KEY_UPDATE_READY_NAME)
            .remove(KEY_UPDATE_READY_APK)
            .remove(KEY_UPDATE_NEEDS_USER)
            .remove(KEY_UPDATE_SESSION_ID)
            .apply()
    }

    // ── Diagnostics ────────────────────────────────────────────────────────────────

    /** Last detected playback stall (decoder froze). Surfaced in telemetry + diag overlay. */
    fun setLastStall(reason: String, atEpochMs: Long) {
        statusPrefs.edit()
            .putString(KEY_LAST_STALL, reason)
            .putLong(KEY_LAST_STALL_MS, atEpochMs)
            .apply()
    }

    fun getLastStall(): Pair<String, Long>? {
        val reason = statusPrefs.getString(KEY_LAST_STALL, null) ?: return null
        return reason to statusPrefs.getLong(KEY_LAST_STALL_MS, 0L)
    }

    /** Heartbeat written by the playback loop — proves the UI thread is still advancing. */
    fun markPlaybackAlive() {
        statusPrefs.edit().putLong(KEY_PLAYBACK_ALIVE_MS, System.currentTimeMillis()).apply()
    }

    fun getPlaybackAliveMs(): Long = statusPrefs.getLong(KEY_PLAYBACK_ALIVE_MS, 0L)

    fun clearAll() {
        prefs.edit().clear().apply()
        statusPrefs.edit().clear().apply()
    }

    companion object {
        /** Built once per process; see the [prefs] kdoc for why this must not be per-instance. */
        @Volatile private var encryptedInstance: android.content.SharedPreferences? = null

        private fun encrypted(context: Context): android.content.SharedPreferences =
            encryptedInstance ?: synchronized(this) {
                encryptedInstance ?: EncryptedSharedPreferences.create(
                    context.applicationContext,
                    PREFS_NAME,
                    MasterKey.Builder(context.applicationContext)
                        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                        .build(),
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                ).also { encryptedInstance = it }
            }

        private const val PREFS_NAME          = "alive_player_prefs"
        private const val STATUS_PREFS_NAME   = "alive_player_status"
        private const val KEY_DEVICE_TOKEN    = "device_token"
        private const val KEY_DEVICE_ID       = "device_id"
        private const val KEY_PAIRED_AT       = "paired_at"
        private const val KEY_PAIRING_CODE    = "pairing_code"
        private const val KEY_FETCH_STATUS    = "fetch_status"
        private const val KEY_FETCH_MESSAGE   = "fetch_message"
        private const val KEY_FETCH_TIME      = "fetch_time"
        private const val KEY_PLAN_UPDATED_MS = "plan_updated_ms"
        private const val KEY_ORIENTATION     = "orientation_mode"
        private const val KEY_FCM_TOKEN       = "fcm_token"
        private const val KEY_FCM_TOKEN_UPLOADED = "fcm_token_uploaded"
        private const val KEY_CLOCK_OFFSET_MS = "clock_offset_ms"
        private const val KEY_LAST_NTP_SYNC_MS = "last_ntp_sync_ms"
        private const val KEY_RETRY_INTERVAL_MS      = "cfg_retry_interval_ms"
        private const val KEY_TRANSITION_DURATION_MS = "cfg_transition_duration_ms"
        private const val KEY_KIOSK_KEY_LOCK         = "cfg_kiosk_key_lock"
        private const val KEY_DL_CONNECT_TIMEOUT_MS  = "cfg_dl_connect_timeout_ms"
        private const val KEY_DL_READ_TIMEOUT_MS     = "cfg_dl_read_timeout_ms"
        private const val KEY_UPDATE_READY_VC        = "update_ready_version_code"
        private const val KEY_UPDATE_READY_NAME      = "update_ready_version_name"
        private const val KEY_UPDATE_READY_APK       = "update_ready_apk_path"
        private const val KEY_UPDATE_NEEDS_USER      = "update_needs_user_action"
        private const val KEY_UPDATE_SESSION_ID      = "update_install_session_id"
        private const val KEY_LAST_STALL             = "diag_last_stall"
        private const val KEY_LAST_STALL_MS          = "diag_last_stall_ms"
        private const val KEY_PLAYBACK_ALIVE_MS      = "diag_playback_alive_ms"

        private const val DEFAULT_RETRY_INTERVAL_MS      = 30_000L
        private const val DEFAULT_TRANSITION_DURATION_MS = 600L
        private const val DEFAULT_DL_CONNECT_TIMEOUT_MS  = 30_000
        private const val DEFAULT_DL_READ_TIMEOUT_MS     = 60_000

        const val ORIENTATION_PORTRAIT         = "portrait"
        const val ORIENTATION_REVERSE_PORTRAIT = "reversePortrait"
        const val ORIENTATION_LANDSCAPE        = "landscape"
        const val ORIENTATION_DEFAULT          = "default"
    }
}

enum class FetchStatus(var message: String = "", var timeMs: Long = 0) {
    /** Plan fetched successfully with N items. */
    OK,
    /** Server returned a plan but it has no content items yet. */
    NO_CONTENT,
    /** No schedule has been assigned to this device in the admin. */
    NO_SCHEDULE,
    /** Network or server error. */
    ERROR,
    /** Currently in progress. */
    FETCHING,
}
