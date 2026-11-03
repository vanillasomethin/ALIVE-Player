package com.alive.player.remote

import android.app.ActivityManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.StatFs
import android.view.Display
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import com.alive.player.playback.DecoderCapabilities
import com.alive.player.settings.DevicePrefs
import org.json.JSONArray
import org.json.JSONObject

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
        return o
    }

    /** The hardware decoder playback would pick for [mime], after this TV's exclusions. */
    private fun firstHardwareDecoder(mime: String): String? = runCatching {
        MediaCodecSelector.DEFAULT.getDecoderInfos(mime, false, false)
            .firstOrNull { it.hardwareAccelerated && !DecoderCapabilities.isBroken(it.name, mime) }?.name
    }.getOrNull()
}
