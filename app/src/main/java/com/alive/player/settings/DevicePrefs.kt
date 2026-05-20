package com.alive.player.settings

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class DevicePrefs(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    // Plain prefs for non-sensitive diagnostic state
    private val statusPrefs = context.getSharedPreferences(STATUS_PREFS_NAME, Context.MODE_PRIVATE)

    fun storePairing(token: String, deviceId: String) {
        prefs.edit()
            .putString(KEY_DEVICE_TOKEN, token)
            .putString(KEY_DEVICE_ID, deviceId)
            .putLong(KEY_PAIRED_AT, System.currentTimeMillis())
            .apply()
    }

    fun getDeviceToken(): String? = prefs.getString(KEY_DEVICE_TOKEN, null)
    fun getDeviceId(): String? = prefs.getString(KEY_DEVICE_ID, null)
    fun getPairedAt(): Long? = prefs.getLong(KEY_PAIRED_AT, -1L).takeIf { it >= 0 }
    fun isPaired(): Boolean = !getDeviceToken().isNullOrBlank()

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

    fun getOrientationMode(): String =
        statusPrefs.getString(KEY_ORIENTATION, ORIENTATION_PORTRAIT) ?: ORIENTATION_PORTRAIT

    fun clearAll() {
        prefs.edit().clear().apply()
        statusPrefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_NAME          = "alive_player_prefs"
        private const val STATUS_PREFS_NAME   = "alive_player_status"
        private const val KEY_DEVICE_TOKEN    = "device_token"
        private const val KEY_DEVICE_ID       = "device_id"
        private const val KEY_PAIRED_AT       = "paired_at"
        private const val KEY_FETCH_STATUS    = "fetch_status"
        private const val KEY_FETCH_MESSAGE   = "fetch_message"
        private const val KEY_FETCH_TIME      = "fetch_time"
        private const val KEY_PLAN_UPDATED_MS = "plan_updated_ms"
        private const val KEY_ORIENTATION     = "orientation_mode"

        const val ORIENTATION_PORTRAIT         = "portrait"
        const val ORIENTATION_REVERSE_PORTRAIT = "reversePortrait"
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
