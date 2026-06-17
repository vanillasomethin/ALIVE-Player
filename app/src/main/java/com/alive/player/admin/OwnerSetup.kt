package com.alive.player.admin

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.alive.player.ui.PairingActivity

/**
 * Device-owner-only setup, safe to call on every boot. No-ops entirely on
 * devices that were never enrolled (e.g. dev/test installs run as a normal
 * app) — HOME claim and silent OTA installs simply aren't available there,
 * falling back to the LAUNCHER/LEANBACK_LAUNCHER entry and confirm-dialog
 * installs respectively.
 */
object OwnerSetup {

    private fun adminComponent(context: Context) =
        ComponentName(context, AliveDeviceAdminReceiver::class.java)

    fun isDeviceOwner(context: Context): Boolean {
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return false
        return dpm.isDeviceOwnerApp(context.packageName)
    }

    /** Call after provisioning completes and again on every boot — idempotent. */
    fun onDeviceOwnerReady(context: Context) {
        if (!isDeviceOwner(context)) return
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return
        val admin = adminComponent(context)

        // Silently make this app the persistent default Home app — no user
        // prompt, no dependence on OEM autostart/battery-kill behaviour, since
        // the OS always restarts the active Home app on boot.
        val homeFilter = IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        dpm.addPersistentPreferredActivity(
            admin,
            homeFilter,
            ComponentName(context, PairingActivity::class.java),
        )
    }
}
