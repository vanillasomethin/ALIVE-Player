package com.alive.player.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import com.alive.player.R
import com.alive.player.network.DeviceApiProvider
import com.alive.player.settings.DevicePrefs
import com.alive.player.worker.HeartbeatScheduler
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

        val prefs = DevicePrefs(this)
        if (prefs.isPaired()) {
            startPlayback()
            return
        }

        fun updateCountdown() {
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
                    if (expiresAtEpochSeconds <= System.currentTimeMillis() / 1000) {
                        status.text = "Code expired. Refreshing..."
                        registerPairing(
                            claimCodeView,
                            countdownView,
                            progress,
                            status,
                            prefs,
                        )
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
        val intent = Intent(this, PlaybackActivity::class.java)
        startActivity(intent)
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
        status.text = "Requesting pairing code..."
        executor.execute {
            try {
                val api = DeviceApiProvider()
                val response = api.registerPairing()
                pairingId = response.pairingId
                expiresAtEpochSeconds = response.expiresAtEpochSeconds
                runOnUiThread {
                    claimCodeView.text = response.code
                    status.text = "Enter this code in the console."
                    progress.visibility = View.GONE
                    onRegistered?.invoke()
                }
                schedulePolling(
                    claimCodeView,
                    countdownView,
                    progress,
                    status,
                    prefs,
                    response.pollAfterSeconds,
                )
            } catch (ex: Exception) {
                runOnUiThread {
                    status.text = "Failed to register. Check network and retry."
                    progress.visibility = View.GONE
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
                val api = DeviceApiProvider()
                val response = api.fetchPairingStatus(currentPairingId)
                when (response.status) {
                    "CLAIMED" -> {
                        val token = response.deviceToken
                        val deviceId = response.deviceId
                        if (!token.isNullOrBlank() && !deviceId.isNullOrBlank()) {
                            prefs.storePairing(token, deviceId)
                            HeartbeatScheduler.schedule(applicationContext)
                            runOnUiThread { startPlayback() }
                        } else {
                            runOnUiThread {
                                status.text = "Pairing succeeded but token missing."
                            }
                        }
                    }
                    "EXPIRED" -> {
                        runOnUiThread {
                            status.text = "Code expired. Refreshing..."
                            registerPairing(
                                claimCodeView,
                                countdownView,
                                progress,
                                status,
                                prefs,
                            )
                        }
                    }
                    else -> {
                        runOnUiThread {
                            status.text = "Waiting for pairing..."
                        }
                        schedulePolling(
                            claimCodeView,
                            countdownView,
                            progress,
                            status,
                            prefs,
                            response.pollAfterSeconds,
                        )
                    }
                }
            } catch (ex: Exception) {
                runOnUiThread {
                    status.text = "Polling failed. Retrying..."
                }
                schedulePolling(
                    claimCodeView,
                    countdownView,
                    progress,
                    status,
                    prefs,
                    15,
                )
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
