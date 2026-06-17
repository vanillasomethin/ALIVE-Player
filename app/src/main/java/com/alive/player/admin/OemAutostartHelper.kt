package com.alive.player.admin

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.os.Build

/**
 * Defense-in-depth for the window before Device Owner enrollment (or on a
 * device that's never enrolled): opens the OEM's own autostart-permission
 * screen so staff can grant it manually during setup. Once Device Owner mode
 * is active (OwnerSetup), this becomes unnecessary — the HOME claim and the
 * device-owner battery-optimization exemption cover it — but it costs nothing
 * to keep as a fallback for stock/non-owner installs.
 */
object OemAutostartHelper {

    private data class OemIntent(val pkg: String, val component: String)

    private val knownIntents = listOf(
        OemIntent("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"), // Xiaomi/MIUI
        OemIntent("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"), // Realme/Oppo/ColorOS
        OemIntent("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
        OemIntent("com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity"),
        OemIntent("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"), // OnePlus
        OemIntent("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"), // Vivo/iQOO
        OemIntent("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
    )

    /** Best-effort: opens the first matching OEM screen, or no-ops if none resolve. */
    fun openAutostartSettings(activity: Activity) {
        for (oem in knownIntents) {
            try {
                val intent = Intent().apply {
                    component = ComponentName(oem.pkg, oem.component)
                }
                activity.startActivity(intent)
                return
            } catch (_: ActivityNotFoundException) {
                // Not this OEM — try the next known intent.
            }
        }
    }

    private val restrictiveManufacturers =
        setOf("xiaomi", "redmi", "realme", "oppo", "oneplus", "vivo")

    /** Fire TV / stock Android TV have no equivalent restriction to work around. */
    fun isKnownRestrictiveOem(): Boolean =
        Build.MANUFACTURER.lowercase() in restrictiveManufacturers
}
