package com.alive.player.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import com.alive.player.BuildConfig
import com.alive.player.R
import com.alive.player.data.AppDatabase
import com.alive.player.data.PlanCache
import com.alive.player.network.DemoApiProvider
import com.alive.player.network.DeviceApiProvider
import com.alive.player.settings.DevicePrefs
import com.alive.player.worker.HeartbeatScheduler
import com.alive.player.worker.PlanFetchScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

class PairingActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pairing)

        val hardwareKeyView = findViewById<TextView>(R.id.claim_code_value)
        val retryButton = findViewById<Button>(R.id.claim_refresh_button)
        val progress = findViewById<ProgressBar>(R.id.claim_progress)
        val status = findViewById<TextView>(R.id.pairing_status)

        // "Seen." in brand red
        val headline = SpannableStringBuilder("Seen.\nRemembered.\nBought.")
        headline.setSpan(
            ForegroundColorSpan(Color.parseColor("#dc2626")),
            0, 5,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        findViewById<TextView>(R.id.pairing_headline).text = headline

        if (BuildConfig.DEMO_MODE) {
            findViewById<View>(R.id.demo_badge).visibility = View.VISIBLE
        }

        val prefs = DevicePrefs(this)
        if (prefs.isPaired()) {
            startPlayback()
            return
        }

        // Derive hardware key from ANDROID_ID (stable per device + app signing key)
        val hardwareKey = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            ?: "unknown-device"

        // Show the hardware key so the admin can identify this device in the dashboard
        hardwareKeyView.text = hardwareKey.chunked(4).joinToString("-").uppercase()

        retryButton.setOnClickListener {
            attemptClaim(hardwareKey, hardwareKeyView, progress, status, prefs)
        }

        attemptClaim(hardwareKey, hardwareKeyView, progress, status, prefs)
    }

    private fun attemptClaim(
        hardwareKey: String,
        hardwareKeyView: TextView,
        progress: ProgressBar,
        status: TextView,
        prefs: DevicePrefs,
    ) {
        progress.visibility = View.VISIBLE
        status.text = if (BuildConfig.DEMO_MODE) "Demo mode — connecting…" else "Connecting to Alive…"

        executor.execute {
            try {
                val response = if (BuildConfig.DEMO_MODE) {
                    DemoApiProvider.claimDevice(hardwareKey)
                } else {
                    DeviceApiProvider().claimDevice(hardwareKey)
                }

                prefs.storePairing(response.token, response.deviceId)

                if (BuildConfig.DEMO_MODE) {
                    // Seed a demo plan so playback starts immediately without a network fetch
                    CoroutineScope(Dispatchers.IO).launch {
                        AppDatabase.get(applicationContext).planCacheDao().upsert(
                            PlanCache(
                                id = 1,
                                planJson = DemoApiProvider.demoScheduleJson(),
                                etag = null,
                                fetchedAtEpochMs = System.currentTimeMillis(),
                            )
                        )
                    }
                } else {
                    HeartbeatScheduler.schedule(applicationContext)
                    PlanFetchScheduler.schedule(applicationContext)
                }

                runOnUiThread { startPlayback() }

            } catch (ex: Exception) {
                runOnUiThread {
                    progress.visibility = View.GONE
                    val cause = ex.message?.take(100) ?: ex.javaClass.simpleName
                    status.text = "Connection failed: $cause"
                }
            }
        }
    }

    private fun startPlayback() {
        startActivity(Intent(this, PlaybackActivity::class.java))
        finish()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
