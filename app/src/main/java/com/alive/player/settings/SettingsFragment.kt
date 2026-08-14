package com.alive.player.settings

import android.app.AlertDialog
import android.app.Fragment
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.alive.player.BuildConfig
import com.alive.player.R
import com.alive.player.data.AppDatabase
import com.alive.player.data.DeviceDecommissioner
import com.alive.player.ui.PairingActivity
import com.alive.player.worker.UpdateScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.activity_settings, container, false)
        val prefs = DevicePrefs(activity.applicationContext)

        view.findViewById<TextView>(R.id.tv_device_id).text =
            prefs.getDeviceId() ?: "—"

        view.findViewById<TextView>(R.id.tv_paired_at).text =
            prefs.getPairedAt()?.let { relativeTime(it) } ?: "—"

        view.findViewById<TextView>(R.id.tv_app_version).text =
            activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: "—"

        view.findViewById<Button>(R.id.reset_button).setOnClickListener { confirmReset() }
        view.findViewById<Button>(R.id.clear_cache_button).setOnClickListener { confirmClearCache() }

        // Operator servicing: jump to Android Wi-Fi / system settings without leaving the
        // kiosk (change network, check for device updates). NEW_TASK so it launches cleanly
        // from here; fall back to the top-level Settings if the specific screen is absent on
        // this OEM build. Works even when the OEM launcher is disabled for boot-direct, since
        // these are direct Settings intents, not the launcher. BACK returns to playback.
        view.findViewById<Button>(R.id.btn_wifi_settings).setOnClickListener {
            runCatching { activity?.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { runCatching { activity?.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
        }
        view.findViewById<Button>(R.id.btn_android_settings).setOnClickListener {
            runCatching { activity?.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }

        bindUpdateRow(view)

        val btnPortrait = view.findViewById<Button>(R.id.btn_orientation_portrait)
        val btnReverse  = view.findViewById<Button>(R.id.btn_orientation_reverse)
        fun highlightOrientation() {
            val isReverse = prefs.getOrientationMode() == DevicePrefs.ORIENTATION_REVERSE_PORTRAIT
            btnPortrait.alpha = if (!isReverse) 1f else 0.4f
            btnReverse.alpha  = if (isReverse)  1f else 0.4f
        }
        highlightOrientation()
        btnPortrait.setOnClickListener {
            prefs.setOrientationMode(DevicePrefs.ORIENTATION_PORTRAIT)
            highlightOrientation()
            activity.recreate()
        }
        btnReverse.setOnClickListener {
            prefs.setOrientationMode(DevicePrefs.ORIENTATION_REVERSE_PORTRAIT)
            highlightOrientation()
            activity.recreate()
        }

        CoroutineScope(Dispatchers.IO).launch {
            val ctx    = activity.applicationContext
            val db     = AppDatabase.get(ctx)
            val cache  = db.planCacheDao().get()
            val pending = db.proofEventDao().getPending().size
            val cacheDir = ctx.getExternalFilesDir("cache") ?: ctx.cacheDir
            val cacheMb  = dirSizeBytes(cacheDir) / 1024 / 1024
            val freeMb   = android.os.StatFs(cacheDir.path).availableBytes / 1024 / 1024
            val networkStatus = getNetworkStatus(ctx)

            withContext(Dispatchers.Main) {
                view.findViewById<TextView>(R.id.tv_last_plan).text =
                    cache?.fetchedAtEpochMs?.let { relativeTime(it) } ?: "—"
                view.findViewById<TextView>(R.id.tv_pending_uploads).text =
                    "$pending event${if (pending == 1) "" else "s"} queued"
                view.findViewById<TextView>(R.id.tv_storage).text =
                    "${cacheMb}MB used · ${freeMb}MB free"
                view.findViewById<TextView>(R.id.tv_network_status).text = networkStatus
            }
        }

        return view
    }

    // Re-bind on every return to the foreground: an update may have finished
    // downloading (or been installed) while this screen was covered.
    override fun onResume() {
        super.onResume()
        view?.let { bindUpdateRow(it) }
    }

    /**
     * Shows the "App update" row only when UpdateCheckWorker has recorded a newer,
     * hash-verified APK. Pressing Install commits the PackageInstaller session while
     * this screen holds UpdateGate open, so the system confirm dialog appears to the
     * operator standing here — the one moment it is allowed to appear at all.
     */
    private fun bindUpdateRow(view: View) {
        val ctx = activity.applicationContext
        val prefs = DevicePrefs(ctx)
        val row = view.findViewById<View>(R.id.row_app_update)

        val readyVc = prefs.getUpdateReadyVersionCode()
        if (readyVc <= BuildConfig.VERSION_CODE) {
            row.visibility = View.GONE
            return
        }

        row.visibility = View.VISIBLE
        val name = prefs.getUpdateReadyVersionName() ?: "1.0.$readyVc"
        view.findViewById<TextView>(R.id.tv_update_status).text = "Version $name ready to install"
        val installBtn = view.findViewById<Button>(R.id.btn_install_update)
        installBtn.isEnabled = true
        installBtn.setOnClickListener {
            // Deliberately NOT committing the cached APK directly: the one-shot worker
            // revalidates against the server first (a rolled-back build must not be
            // installable from stale local state), reuses the SHA-verified cached APK
            // when still current, and — because UpdateGate is open on this screen —
            // commits at the end, so the confirm dialog appears right here.
            // Disabled until onResume rebinds (returning from the dialog), so a double
            // press can't race two checks; the worker also serializes via its mutex.
            installBtn.isEnabled = false
            Toast.makeText(activity, "Preparing update…", Toast.LENGTH_SHORT).show()
            UpdateScheduler.checkNow(ctx)
        }
    }

    private fun getNetworkStatus(ctx: Context): String {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "Offline"
        val type = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)     -> "WiFi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
            else -> "Connected"
        }
        return if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) "Online ($type)" else "No internet ($type)"
    }

    private fun confirmClearCache() {
        AlertDialog.Builder(activity)
            .setTitle("Clear cache?")
            .setMessage("Downloaded content files will be deleted and re-downloaded from the server.")
            .setPositiveButton("Clear") { _, _ -> performClearCache() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performClearCache() {
        CoroutineScope(Dispatchers.IO).launch {
            val ctx = activity.applicationContext
            val db  = AppDatabase.get(ctx)
            db.downloadJobDao().clearAll()
            db.assetDao().clearAll()
            ctx.getExternalFilesDir("cache")?.deleteRecursively()
            ctx.cacheDir.deleteRecursively()
            withContext(Dispatchers.Main) {
                Toast.makeText(activity, "Cache cleared", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun confirmReset() {
        AlertDialog.Builder(activity)
            .setTitle("Reset device?")
            .setMessage("This will unpair the device and clear all local data.")
            .setPositiveButton("Reset") { _, _ -> performReset() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performReset() {
        val ctx = activity.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            // Same wipe the server-triggered decommission uses (410 / FCM push), so
            // manual reset and remote deletion cannot drift apart. It also cancels
            // the periodic workers and stops playback before clearing, then brings
            // up PairingActivity itself.
            DeviceDecommissioner.wipe(ctx, "operator reset from Settings")
        }
    }

    private fun relativeTime(epochMs: Long): String {
        val diffMs = System.currentTimeMillis() - epochMs
        return when {
            diffMs < 60_000     -> "just now"
            diffMs < 3_600_000  -> "${diffMs / 60_000}m ago"
            diffMs < 86_400_000 -> "${diffMs / 3_600_000}h ago"
            else                -> SimpleDateFormat("MMM d HH:mm", Locale.getDefault()).format(Date(epochMs))
        }
    }

    private fun dirSizeBytes(dir: File): Long =
        dir.walkTopDown().sumOf { if (it.isFile) it.length() else 0L }
}
