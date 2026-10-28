package com.alive.player.remote

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.provider.Settings
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * What the admin console needs to reach this TV without a site visit, reported with
 * every heartbeat: whether network ADB is on (and on which port), and the TV's
 * Tailscale address when Tailscale is running. ADB is meant to be reached ONLY over
 * Tailscale; this never switches ADB on and never opens a port.
 */
object RemoteAccess {

    data class Status(val adbEnabled: Boolean, val adbPort: Int?, val tailscaleIp: String?, val canEnableAdb: Boolean)

    fun status(context: Context): Status {
        val cr = context.contentResolver
        val usbOrTcp = runCatching { Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, 0) == 1 }.getOrDefault(false)
        // Android 11+ "Wireless debugging" is its own switch, on a port that changes.
        val wireless = runCatching { Settings.Global.getInt(cr, "adb_wifi_enabled", 0) == 1 }.getOrDefault(false)
        // `adb tcpip` uses a fixed port; Wireless debugging picks a new one each time it starts.
        val port = prop("service.adb.tcp.port") ?: if (wireless) prop("service.adb.tls.port") else null
        return Status(
            adbEnabled   = usbOrTcp || wireless,
            adbPort      = port,
            tailscaleIp  = tailscaleIp(),
            canEnableAdb = canWriteSecureSettings(context),
        )
    }

    /** WRITE_SECURE_SETTINGS cannot be requested at runtime: it is granted once over ADB at
     *  install (`adb shell pm grant com.alive.player android.permission.WRITE_SECURE_SETTINGS`).
     *  With it the console can switch Wireless debugging back on after a reboot. */
    fun canWriteSecureSettings(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** Admin `adb_on`: switches developer ADB and (Android 11+) Wireless debugging on. Never
     *  switches anything off and never opens a port beyond what adbd already does; a laptop
     *  must already be paired with this TV for the connection to be accepted. */
    fun enableAdb(context: Context): Boolean {
        if (!canWriteSecureSettings(context)) return false
        val cr = context.contentResolver
        return runCatching {
            Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 1)
            Settings.Global.putInt(cr, "adb_wifi_enabled", 1)
            true
        }.getOrDefault(false)
    }

    /** `adb tcpip 5555` sets this property. Hidden API, so on firmware that blocks it the
     *  port is simply unknown (the console then suggests the usual 5555). */
    private fun prop(name: String): Int? = runCatching {
        val sp = Class.forName("android.os.SystemProperties")
        val v = sp.getMethod("get", String::class.java).invoke(null, name) as? String
        v?.toIntOrNull()?.takeIf { it in 1..65535 }
    }.getOrNull()

    private fun tailscaleIp(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .map { it.hostAddress ?: "" }
            .firstOrNull { isTailscaleAddress(it) }
    }.getOrNull()

    /** Tailscale addresses come from the CGNAT range 100.64.0.0/10. */
    fun isTailscaleAddress(ip: String): Boolean {
        val p = ip.split('.')
        if (p.size != 4) return false
        val n = p.map { it.toIntOrNull() ?: return false }
        if (n.any { it !in 0..255 }) return false
        return n[0] == 100 && n[1] in 64..127
    }

    /** The TV's media volume as 0-100, or null when it cannot be read. */
    fun volumePct(context: Context): Int? = runCatching {
        val am = context.getSystemService(AudioManager::class.java) ?: return null
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) null else Math.round(am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / max)
    }.getOrNull()

    /** Sets the TV's media volume from 0-100 (an admin `volume` command). */
    fun setVolumePct(context: Context, pct: Int) {
        runCatching {
            val am = context.getSystemService(AudioManager::class.java) ?: return
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, levelFor(pct, max), 0)
        }
    }

    /** 0-100 onto the stream's own steps, rounded, clamped. */
    fun levelFor(pct: Int, max: Int): Int = Math.round(pct.coerceIn(0, 100) * max / 100f).coerceIn(0, max)
}
