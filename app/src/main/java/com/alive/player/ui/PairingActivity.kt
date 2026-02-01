package com.alive.player.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import com.alive.player.R
import com.alive.player.network.DeviceApiProvider
import com.alive.player.settings.DevicePrefs
import com.alive.player.worker.HeartbeatScheduler
import java.util.concurrent.Executors

class PairingActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pairing)

        val claimInput = findViewById<EditText>(R.id.claim_code_input)
        val submitButton = findViewById<Button>(R.id.claim_submit_button)
        val progress = findViewById<ProgressBar>(R.id.claim_progress)
        val status = findViewById<TextView>(R.id.pairing_status)

        val prefs = DevicePrefs(this)
        if (prefs.isPaired()) {
            startPlayback()
            return
        }

        submitButton.setOnClickListener {
            val claimCode = claimInput.text.toString().trim()
            if (claimCode.isBlank()) {
                status.text = "Claim code required"
                return@setOnClickListener
            }

            submitButton.isEnabled = false
            progress.visibility = View.VISIBLE
            status.text = "Pairing..."

            executor.execute {
                try {
                    val api = DeviceApiProvider()
                    val response = api.claimDevice(claimCode)
                    prefs.storePairing(response.deviceToken, response.deviceId)
                    HeartbeatScheduler.schedule(applicationContext)
                    runOnUiThread { startPlayback() }
                } catch (ex: Exception) {
                    runOnUiThread {
                        status.text = "Pairing failed. Check the code and try again."
                        progress.visibility = View.GONE
                        submitButton.isEnabled = true
                    }
                }
            }
        }
    }

    private fun startPlayback() {
        val intent = Intent(this, PlaybackActivity::class.java)
        startActivity(intent)
        finish()
    }
}
