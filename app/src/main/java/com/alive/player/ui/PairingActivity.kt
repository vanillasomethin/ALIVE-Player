package com.alive.player.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
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
import com.alive.player.worker.UpdateScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

class PairingActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Check before inflating to avoid a flash of the pairing UI
        if (DevicePrefs(this).isPaired()) {
            startPlayback()
            return
        }

        setContentView(R.layout.activity_pairing)

        val stepRegister = findViewById<LinearLayout>(R.id.card_step_register)
        val stepSuccess  = findViewById<LinearLayout>(R.id.card_step_success)
        val hwKeyView    = findViewById<TextView>(R.id.claim_code_value)
        val nameInput    = findViewById<EditText>(R.id.device_name_input)
        val registerBtn  = findViewById<Button>(R.id.register_button)
        val retryBtn     = findViewById<Button>(R.id.claim_refresh_button)
        val progress     = findViewById<ProgressBar>(R.id.claim_progress)
        val statusBar    = findViewById<TextView>(R.id.pairing_status)
        val successName  = findViewById<TextView>(R.id.success_device_name)
        val successId    = findViewById<TextView>(R.id.success_device_id)

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
        val hardwareKey = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            ?: "unknown-device"
        hwKeyView.text = hardwareKey.chunked(4).joinToString("-").uppercase()

        fun doRegister() {
            val name = nameInput.text.toString().trim().ifBlank { null }
            registerBtn.isEnabled = false
            retryBtn.visibility = View.GONE
            statusBar.text = ""
            attemptClaim(
                hardwareKey, name,
                stepRegister, stepSuccess,
                successName, successId,
                registerBtn, retryBtn,
                progress, statusBar, prefs,
            )
        }

        registerBtn.setOnClickListener { doRegister() }
        nameInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { doRegister(); true } else false
        }

        retryBtn.setOnClickListener {
            stepRegister.visibility = View.VISIBLE
            stepSuccess.visibility = View.GONE
            retryBtn.visibility = View.GONE
            registerBtn.isEnabled = true
        }
    }

    private fun attemptClaim(
        hardwareKey: String,
        name: String?,
        stepRegister: LinearLayout,
        stepSuccess: LinearLayout,
        successName: TextView,
        successId: TextView,
        registerBtn: Button,
        retryBtn: Button,
        progress: ProgressBar,
        statusBar: TextView,
        prefs: DevicePrefs,
    ) {
        progress.visibility = View.VISIBLE
        statusBar.text = if (BuildConfig.DEMO_MODE) "Demo mode — connecting…" else "Connecting…"

        executor.execute {
            try {
                val response = if (BuildConfig.DEMO_MODE) {
                    DemoApiProvider.claimDevice(hardwareKey)
                } else {
                    DeviceApiProvider().claimDevice(hardwareKey, name)
                }

                prefs.storePairing(response.token, response.deviceId)

                if (BuildConfig.DEMO_MODE) {
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
                    UpdateScheduler.schedule(applicationContext)
                }

                val friendlyId  = response.deviceId.take(12).uppercase()
                val displayName = name ?: "This Device"

                runOnUiThread {
                    progress.visibility = View.GONE
                    statusBar.text = ""
                    stepRegister.visibility = View.GONE
                    stepSuccess.visibility = View.VISIBLE
                    successName.text = displayName
                    successId.text = friendlyId

                    if (BuildConfig.DEMO_MODE) {
                        startPlayback()
                    } else {
                        // Pause so user can read and verify the device ID in admin
                        statusBar.text = "Starting in 5 seconds…"
                        stepSuccess.postDelayed({ startPlayback() }, 5_000)
                    }
                }

            } catch (ex: Exception) {
                runOnUiThread {
                    progress.visibility = View.GONE
                    val cause = ex.message?.take(120) ?: ex.javaClass.simpleName
                    statusBar.text = "Registration failed: $cause"
                    registerBtn.isEnabled = true
                    retryBtn.visibility = View.VISIBLE
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
