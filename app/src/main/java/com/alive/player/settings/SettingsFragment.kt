package com.alive.player.settings

import android.app.AlertDialog
import android.app.Fragment
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import com.alive.player.R
import com.alive.player.data.AppDatabase
import com.alive.player.ui.PairingActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.activity_settings, container, false)
        val prefs = DevicePrefs(activity.applicationContext)

        view.findViewById<TextView>(R.id.tv_device_id).text =
            prefs.getDeviceId() ?: "—"

        view.findViewById<TextView>(R.id.tv_paired_at).text =
            prefs.getPairedAt()?.let { formatEpoch(it) } ?: "—"

        CoroutineScope(Dispatchers.IO).launch {
            val cache = AppDatabase.get(activity.applicationContext).planCacheDao().get()
            withContext(Dispatchers.Main) {
                view.findViewById<TextView>(R.id.tv_last_plan).text =
                    cache?.fetchedAtEpochMs?.let { formatEpoch(it) } ?: "—"
            }
        }

        view.findViewById<TextView>(R.id.tv_app_version).text =
            activity.packageManager
                .getPackageInfo(activity.packageName, 0).versionName ?: "—"

        view.findViewById<Button>(R.id.reset_button).setOnClickListener { confirmReset() }
        return view
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
        CoroutineScope(Dispatchers.IO).launch {
            val ctx = activity.applicationContext
            val db = AppDatabase.get(ctx)
            db.planCacheDao().clear()
            db.proofEventDao().clearAll()
            db.assetDao().clearAll()
            db.downloadJobDao().clearAll()
            db.incidentDao().clearAll()
            ctx.getExternalFilesDir("cache")?.deleteRecursively()
            ctx.cacheDir.deleteRecursively()
            DevicePrefs(ctx).clearAll()
            withContext(Dispatchers.Main) {
                val intent = Intent(ctx, PairingActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                ctx.startActivity(intent)
            }
        }
    }

    private fun formatEpoch(epochMs: Long): String =
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(epochMs))
}
