package com.alive.player.admin

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

/**
 * Marker receiver for Device Owner mode. No custom policies are declared in
 * device_admin.xml — owner status itself is what unlocks the persistent-HOME
 * claim (OwnerSetup) and silent PackageInstaller installs (UpdateCheckWorker).
 */
class AliveDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        super.onProfileProvisioningComplete(context, intent)
        OwnerSetup.onDeviceOwnerReady(context)
    }
}
