package com.alive.player.network

import android.content.Context
import android.provider.Settings
import com.alive.player.settings.DevicePrefs

/**
 * Re-calls POST /api/device/claim to obtain a fresh token after a 401/403, per the
 * client checklist in ALIVE_PLAYER_API.md.
 *
 * The device's CURRENT token is presented in the Authorization header. That is what
 * makes the call succeed at all for a paired screen: hardwareKey (ANDROID_ID) is an
 * identifier, not a credential, so the server mints a replacement only for a caller
 * that already holds one. Without it every re-claim is refused with 403
 * `reclaim_denied` and raises an admin alert — which is exactly what the fleet was
 * doing before this helper existed.
 *
 * An empty pairingCode in the reply means the device is still admin-confirmed (plain
 * token rotation). A non-empty one means it was actually unclaimed, so there is no
 * usable token yet — the pending pairing state is stashed for PairingActivity and the
 * caller gets null, to retry later rather than carry on unauthenticated.
 *
 * Returns null on any failure. Callers treat that as "could not recover this time".
 *
 * Lives here, shared, because HeartbeatWorker, PlanFetchWorker and PopUploadWorker all
 * need it and previously each carried a byte-identical private copy. Three copies of
 * an auth path drift, and the bearer-token omission above had to be fixed in all three.
 */
fun reclaimDeviceToken(context: Context, prefs: DevicePrefs): String? {
    val hardwareKey = Settings.Secure.getString(
        context.contentResolver, Settings.Secure.ANDROID_ID
    ) ?: return null
    return try {
        val response = DeviceApiProvider().claimDevice(
            hardwareKey  = hardwareKey,
            currentToken = prefs.getDeviceToken(),
        )
        if (response.pairingCode.isNotEmpty()) {
            prefs.storePairingPending(response.token, response.deviceId, response.pairingCode)
            null
        } else {
            prefs.storePairing(response.token, response.deviceId)
            response.token
        }
    } catch (ex: Exception) {
        null
    }
}
