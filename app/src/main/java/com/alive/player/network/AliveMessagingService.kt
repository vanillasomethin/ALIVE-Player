package com.alive.player.network

import com.alive.player.admin.OwnerSetup
import com.alive.player.settings.DevicePrefs
import com.alive.player.worker.HeartbeatScheduler
import com.alive.player.worker.PlanFetchScheduler
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AliveMessagingService : FirebaseMessagingService() {

    /**
     * Called when FCM assigns or rotates the device token.
     * Upload to backend so the server knows where to push plan_updated notifications.
     * This one-shot is lossy (fires before pairing on a fresh install; upload can
     * fail) — HeartbeatWorker re-uploads whenever issued != uploaded, so a miss
     * here heals within one heartbeat instead of lasting until the next rotation.
     */
    override fun onNewToken(token: String) {
        val prefs = DevicePrefs(applicationContext)
        prefs.setFcmToken(token)
        val deviceToken = prefs.getDeviceToken() ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                DeviceApiProvider().updateFcmToken(deviceToken, token)
                prefs.setUploadedFcmToken(token)
            } catch (_: Exception) {
                // HeartbeatWorker retries — issued token stays != uploaded token.
            }
        }
    }

    /**
     * Incoming FCM data message from the dashboard — ALIVE's equivalent of Xibo's XMR
     * command channel (collectNow/reboot/etc), riding on FCM instead of a separate relay.
     *   plan_updated → kick an immediate plan fetch (bypasses the 15-min wait)
     *   health_ping  → kick an immediate heartbeat/telemetry report
     *   reboot       → restart the device now (device-owner installs only; no-ops
     *                  otherwise) — only when the message provably arrived on this
     *                  device's own token, see below
     *   decommission → this screen may have been deleted in the admin panel: kick an
     *                  immediate plan fetch and let the authenticated API answer. A
     *                  genuinely deleted device gets the marker-carrying 410 and
     *                  PlanFetchWorker wipes — the same converge-path screens that
     *                  MISS the push already use — so the push is a hint, never the
     *                  authority.
     *
     * Destructive commands must never execute off a broadcast: every install
     * subscribes to the fleet topic (AliveApplication), so one server-side mistake —
     * or one forged send — publishing decommission/reboot fleet-wide would wipe or
     * reboot every unattended screen at once. The old guard here deny-listed the one
     * broadcast shape it knew (`from.startsWith("/topics/")`) and missed topic
     * *conditions* ("'x' in topics"), whose `from` is not slash-prefixed. Deny-listing
     * an under-documented field is the bug; what replaced it:
     *  - decommission carries no device-side authority at all any more (above), so
     *    every broadcast shape is moot — worst case is one extra plan fetch.
     *  - reboot, which has no server-side record to confirm against, executes only
     *    when `from` is exactly this app's own numeric sender id — the shape of a
     *    direct-to-token send and of nothing else FCM offers. Fails closed on null.
     */
    override fun onMessageReceived(message: RemoteMessage) {
        when (message.data["type"]) {
            "plan_updated" -> PlanFetchScheduler.scheduleImmediate(applicationContext)
            "health_ping"  -> HeartbeatScheduler.scheduleImmediate(applicationContext)
            "reboot"       -> if (isFromOwnSender(message.from, ownSenderId())) {
                OwnerSetup.rebootDevice(applicationContext)
            }
            "decommission" -> PlanFetchScheduler.scheduleImmediate(applicationContext)
        }
    }

    /** This app's own FCM sender id (the Firebase project number, from
     *  google-services.json) — what `RemoteMessage.from` carries on a direct-to-token
     *  send. Null (fail closed) if Firebase isn't initialised, which inside a running
     *  FirebaseMessagingService would itself be an anomaly worth failing closed on. */
    private fun ownSenderId(): String? =
        runCatching { com.google.firebase.FirebaseApp.getInstance().options.gcmSenderId }.getOrNull()
}

/**
 * True only when [from] is exactly this app's own FCM sender id — the shape of a
 * direct-to-token send. An allow-list, deliberately: `from` is under-documented, and
 * the previous `startsWith("/topics/")` deny-list proved the point by missing topic
 * conditions. Every broadcast shape — "/topics/x", "'x' in topics", whatever FCM adds
 * next — fails this check, as do null `from` and a missing sender id.
 */
internal fun isFromOwnSender(from: String?, ownSenderId: String?): Boolean =
    from != null && ownSenderId != null && from == ownSenderId
