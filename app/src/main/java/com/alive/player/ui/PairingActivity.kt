package com.alive.player.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import com.alive.player.network.PairingRegisterResponse
import com.alive.player.network.PairingStatusResponse
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
    private var pollingRunnable: Runnable? = null
    private var countdownRunnable: Runnable? = null
    private var expiresAtEpochSeconds: Long = 0
    private var pairingId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pairing)

        val claimCodeView = findViewById<TextView>(R.id.claim_code_value)
        val countdownView = findViewById<TextView>(R.id.claim_code_countdown)
        val refreshButton = findViewById<Button>(R.id.claim_refresh_button)
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
            countdownView.text = "Demo mode — auto-pairs in 8s"
            findViewById<View>(R.id.demo_badge).visibility = View.VISIBLE
        }

        val prefs = DevicePrefs(this)
        if (prefs.isPaired()) {
            startPlayback()
            return
        }

        fun updateCountdown() {
            if (BuildConfig.DEMO_MODE) return
            val nowSeconds = System.currentTimeMillis() / 1000
            val remaining = (expiresAtEpochSeconds - nowSeconds).coerceAtLeast(0)
            val minutes = remaining / 60
            val seconds = remaining % 60
            countdownView.text = String.format("Expires in %d:%02d", minutes, seconds)
        }

        fun startCountdown() {
            countdownRunnable?.let { handler.removeCallbacks(it) }
            val runnable = object : Runnable {
                override fun run() {
                    updateCountdown()
                    if (!BuildConfig.DEMO_MODE &&
                        expiresAtEpochSeconds <= System.currentTimeMillis() / 1000
                    ) {
                        status.text = "Code expired. Refreshing..."
                        registerPairing(claimCodeView, countdownView, progress, status, prefs)
                        return
                    }
                    handler.postDelayed(this, 1000)
                }
            }
            countdownRunnable = runnable
            handler.post(runnable)
        }

        refreshButton.setOnClickListener {
            registerPairing(claimCodeView, countdownView, progress, status, prefs)
        }

        registerPairing(claimCodeView, countdownView, progress, status, prefs) {
            startCountdown()
        }
    }

    private fun startPlayback() {
        startActivity(Intent(this, PlaybackActivity::class.java))
        finish()
    }

    private fun registerPairing(
        claimCodeView: TextView,
        countdownView: TextView,
        progress: ProgressBar,
        status: TextView,
        prefs: DevicePrefs,
        onRegistered: (() -> Unit)? = null,
    ) {
        progress.visibility = View.VISIBLE
        status.text = if (BuildConfig.DEMO_MODE) "Demo mode — generating code…" else "Requesting pairing code…"

        executor.execute {
            try {
                val response: PairingRegisterResponse = if (BuildConfig.DEMO_MODE) {
                    DemoApiProvider.registerPairing()
                } else {
                    DeviceApiProvider().registerPairing()
                }

                pairingId = response.pairingId
                expiresAtEpochSeconds = response.expiresAtEpochSeconds

                runOnUiThread {
                    claimCodeView.text = response.code
                    status.text = if (BuildConfig.DEMO_MODE) {
                        "Demo — auto-pairing in 8s"
                    } else {
                        "Enter this code in the Alive dashboard"
                    }
                    progress.visibility = View.GONE
                    onRegistered?.invoke()
                }

                schedulePolling(claimCodeView, countdownView, progress, status, prefs, response.pollAfterSeconds)

            } catch (ex: Exception) {
                runOnUiThread {
                    progress.visibility = View.GONE
                    // Show the actual error so it's debuggable
                    val cause = ex.message?.take(80) ?: ex.javaClass.simpleName
                    status.text = "Registration failed: $cause"
                }
            }
        }
    }

    private fun schedulePolling(
        claimCodeView: TextView,
        countdownView: TextView,
        progress: ProgressBar,
        status: TextView,
        prefs: DevicePrefs,
        pollAfterSeconds: Int,
    ) {
        pollingRunnable?.let { handler.removeCallbacks(it) }
        val runnable = Runnable {
            pollPairingStatus(claimCodeView, countdownView, progress, status, prefs)
        }
        pollingRunnable = runnable
        handler.postDelayed(runnable, pollAfterSeconds * 1000L)
    }

    private fun pollPairingStatus(
        claimCodeView: TextView,
        countdownView: TextView,
        progress: ProgressBar,
        status: TextView,
        prefs: DevicePrefs,
    ) {
        val currentPairingId = pairingId ?: return
        executor.execute {
            try {
                val response: PairingStatusResponse = if (BuildConfig.DEMO_MODE) {
                    DemoApiProvider.fetchPairingStatus(currentPairingId)
                } else {
                    DeviceApiProvider().fetchPairingStatus(currentPairingId)
                }

                when (response.status) {
                    "CLAIMED" -> {
                        val token = response.deviceToken
                        val deviceId = response.deviceId
                        if (!token.isNullOrBlank() && !deviceId.isNullOrBlank()) {
                            prefs.storePairing(token, deviceId)

                            if (BuildConfig.DEMO_MODE) {
                                // Seed a demo plan so playback starts immediately
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
                        } else {
                            runOnUiThread { status.text = "Pairing succeeded but token missing." }
                        }
                    }
                    "EXPIRED" -> runOnUiThread {
                        status.text = "Code expired. Refreshing…"
                        registerPairing(claimCodeView, countdownView, progress, status, prefs)
                    }
                    else -> {
                        runOnUiThread { status.text = "Waiting for pairing…" }
                        schedulePolling(claimCodeView, countdownView, progress, status, prefs, response.pollAfterSeconds)
                    }
                }
            } catch (ex: Exception) {
                runOnUiThread { status.text = "Polling error: ${ex.message?.take(60)}" }
                schedulePolling(claimCodeView, countdownView, progress, status, prefs, 15)
            }
        }
    }

    override fun onDestroy() {
        pollingRunnable?.let { handler.removeCallbacks(it) }
        countdownRunnable?.let { handler.removeCallbacks(it) }
        executor.shutdownNow()
        super.onDestroy()
    }
}
