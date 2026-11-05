package com.alive.player.remote

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.StatFs
import android.view.Display
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import com.alive.player.admin.OwnerSetup
import com.alive.player.playback.DecoderCapabilities
import com.alive.player.settings.DevicePrefs
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address

/**
 * What this TV is, sent with every heartbeat so Admin → Screens shows it without a site
 * visit: make, model, chip, RAM, storage, panel resolution, and which hardware video
 * decoders the player uses (and which it has learned to skip). Every field is best
 * effort: a TV that refuses one read still reports the rest.
 */
object DeviceInfo {
    fun json(context: Context): JSONObject {
        val o = JSONObject()
        fun put(k: String, v: Any?) { if (v != null && (v !is String || v.isNotBlank())) o.put(k, v) }
        put("manufacturer", Build.MANUFACTURER)
        put("model", Build.MODEL)
        put("brand", Build.BRAND)
        put("soc", runCatching {
            if (Build.VERSION.SDK_INT >= 31 && Build.SOC_MODEL != Build.UNKNOWN) {
                listOf(Build.SOC_MANUFACTURER, Build.SOC_MODEL).filter { it != Build.UNKNOWN }.joinToString(" ")
            } else Build.HARDWARE
        }.getOrNull())
        put("abi", Build.SUPPORTED_ABIS.firstOrNull())
        put("cpuCores", Runtime.getRuntime().availableProcessors())
        put("ramMb", runCatching {
            val mi = ActivityManager.MemoryInfo()
            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
            (mi.totalMem / (1024 * 1024)).toInt()
        }.getOrNull())
        put("storageTotalMb", runCatching { (StatFs(context.filesDir.path).totalBytes / (1024 * 1024)).toInt() }.getOrNull())
        put("display", runCatching {
            val d = (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)
            val m = d.mode
            "${maxOf(m.physicalWidth, m.physicalHeight)}x${minOf(m.physicalWidth, m.physicalHeight)}"
        }.getOrNull())
        val learned = DevicePrefs(context).getLearnedBrokenDecoders()
        DecoderCapabilities.loadLearned(learned)
        put("avcDecoder", firstHardwareDecoder(MimeTypes.VIDEO_H264))
        put("hevcDecoder", firstHardwareDecoder(MimeTypes.VIDEO_H265))
        if (learned.isNotEmpty()) o.put("excludedDecoders", JSONArray(learned.sorted()))
        o.put("versionCode", com.alive.player.BuildConfig.VERSION_CODE)
        // Will this TV bring the player up by itself after a power cut? Device Owner claims HOME
        // silently on every boot; failing that, being the TV's chosen Home app does the same. The
        // console shows 'Starts on its own' from these two, so a TV that opens its own home screen
        // is visible before it shows up as an offline screen.
        o.put("deviceOwner", runCatching { OwnerSetup.isDeviceOwner(context) }.getOrDefault(false))
        o.put("homeApp", runCatching {
            context.packageManager.resolveActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY,
            )?.activityInfo?.packageName == context.packageName
        }.getOrDefault(false))
        putNetwork(context, o)
        return o
    }

    /**
     * Where this TV sits on the SHOP's network: its address on Wi-Fi or Ethernet (VPNs such as
     * Tailscale are skipped, they are reported separately) and, on Wi-Fi, the signal strength.
     * Sent so Admin → Screens can show a weak signal or a changed address without a site visit.
     * Best effort and read-only: any failure just leaves the fields out.
     */
    private fun putNetwork(context: Context, o: JSONObject) {
        runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            for (n in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(n) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                val link = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                    else -> continue
                }
                // Site-local = 10/8, 172.16/12, 192.168/16: a shop LAN address, never a public one.
                val ip = cm.getLinkProperties(n)?.linkAddresses
                    ?.map { it.address }
                    ?.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
                    ?.hostAddress ?: continue
                o.put("lanIp", ip)
                o.put("link", link)
                if (link == "wifi") wifiRssi(context)?.let { o.put("wifiRssi", it) }
                return
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun wifiRssi(context: Context): Int? = runCatching {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wm.connectionInfo?.rssi?.takeIf { it in -127..-1 }
    }.getOrNull()

    /** The hardware decoder playback would pick for [mime], after this TV's exclusions. */
    private fun firstHardwareDecoder(mime: String): String? = runCatching {
        MediaCodecSelector.DEFAULT.getDecoderInfos(mime, false, false)
            .firstOrNull { it.hardwareAccelerated && !DecoderCapabilities.isBroken(it.name, mime) }?.name
    }.getOrNull()
}
