package com.alive.player.network

import com.alive.player.data.DeviceDecommissioner
import com.alive.player.settings.DevicePrefs
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
     */
    override fun onNewToken(token: String) {
        val prefs = DevicePrefs(applicationContext)
        prefs.setFcmToken(token)
        val deviceToken = prefs.getDeviceToken() ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try { DeviceApiProvider().updateFcmToken(deviceToken, token) } catch (_: Exception) {}
        }
    }

    /**
     * Incoming FCM data message from the dashboard.
     * type=plan_updated  → kick an immediate plan fetch (bypasses the 15-min wait).
     * type=decommission  → this screen was deleted in the admin panel: wipe cached
     *                      plan/media and pairing, return to the pairing screen.
     *                      (Screens that miss the push converge via the 410 the
     *                      device API answers on their next call.)
     */
    override fun onMessageReceived(message: RemoteMessage) {
        when (message.data["type"]) {
            "plan_updated" -> PlanFetchScheduler.scheduleImmediate(applicationContext)
            "decommission" -> CoroutineScope(Dispatchers.IO).launch {
                DeviceDecommissioner.wipe(applicationContext, "decommission push — deleted in admin panel")
            }
        }
    }
}
