package com.partner.alive.network

import com.partner.alive.settings.DevicePrefs
import com.partner.alive.worker.PlanFetchScheduler
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
     * type=plan_updated → kick an immediate plan fetch (bypasses the 15-min wait).
     */
    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data["type"] == "plan_updated") {
            PlanFetchScheduler.scheduleImmediate(applicationContext)
        }
    }
}
