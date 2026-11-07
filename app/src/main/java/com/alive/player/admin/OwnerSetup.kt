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

    /** Remote access to a screen runs over Tailscale; ADB is never exposed on a shop LAN. */
    private const val TAILSCALE_PKG = "com.tailscale.ipn"

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

        // Lock-task (kiosk pinning) allowlist: PlaybackActivity calls startLockTask()
        // when running under Device Owner, which pins the app in front (HOME/RECENTS
        // can't leave it) with no consent toast. The TV Settings package is allowlisted
        // too: lock task blocks launching any non-allowlisted package, and the in-app
        // "Wi-Fi settings"/"Android settings" servicing buttons must still open while
        // pinned. Tailscale is allowlisted for the same reason: it is how a screen is
        // reached at all, and on a KTC/ASAANO panel in lock task every attempt to open
        // it — by an engineer over ADB or by hand with the remote — is refused with
        // START_RETURN_LOCK_TASK_MODE_VIOLATION, which strands the screen (2026-10-31,
        // AH Store). An app that cannot be opened cannot be signed in.
        // Verified on a Google-TV (ASAANO/KTC) panel; note its OEM build does NOT resume
        // lock task across a reboot, so this pins at runtime but does not remove the
        // boot-time launcher flash there.
        runCatching {
            dpm.setLockTaskPackages(
                admin,
                arrayOf(context.packageName, "com.android.tv.settings", TAILSCALE_PKG),
            )
        }

        // Tailscale must come back by itself after a power cut, and only Device Owner
        // can arrange that. Android never auto-starts a VPN at boot: VpnService.prepare()
        // needs a human to confirm a dialog, so the tunnel stays down until someone opens
        // the app. The usual remedy — Android's always-on VPN — is unreachable on these
        // panels: Settings has no VPN screen at all (android.settings.VPN_SETTINGS
        // resolves to nothing on KTC/ASAANO), and writing the legacy global
        // always_on_vpn_app key makes Tailscale autostart but never raises tun0. This
        // API is the only route left, and without it a rebooted screen is unreachable
        // until someone stands in front of it (2026-10-31, Margin's Super Market).
        //
        // lockdown = false on purpose: lockdown blocks all non-VPN traffic whenever the
        // tunnel is down, which on a shop's connection would stop the ads rather than
        // merely stop remote access. Losing remote access is recoverable; a dark screen
        // in a shop is not.
        //
        // Throws if Tailscale isn't installed on this screen, or if the build refuses
        // always-on, so it must not be allowed to abort the HOME claim above.
        runCatching { dpm.setAlwaysOnVpnPackage(admin, TAILSCALE_PKG, false) }
    }

    /**
     * Remote-triggered reboot (e.g. an admin-issued FCM `reboot` command). No-ops on
     * non-owner installs — DevicePolicyManager.reboot() throws SecurityException without
     * device-owner, and there's no other way to reboot the device programmatically.
     */
    fun rebootDevice(context: Context) {
        if (!isDeviceOwner(context)) return
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return
        runCatching { dpm.reboot(adminComponent(context)) }
    }
}
