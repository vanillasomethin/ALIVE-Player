package com.alive.player.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
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
import android.widget.ImageView
import android.widget.LinearLayout
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
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

class PairingActivity : Activity() {

    private val executor   = Executors.newSingleThreadExecutor()
    private val uiHandler  = Handler(Looper.getMainLooper())
    private var polling    = false

    // Poll every 5s while waiting for admin confirmation
    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!polling) return
            val prefs = DevicePrefs(this@PairingActivity)
            val token = prefs.getDeviceToken() ?: return
            executor.execute {
                try {
                    val paired = DeviceApiProvider().checkPairingStatus(token)
                    if (paired) {
                        prefs.confirmPairing()
                        HeartbeatScheduler.schedule(applicationContext)
                        PlanFetchScheduler.schedule(applicationContext)
                        UpdateScheduler.schedule(applicationContext)
                        runOnUiThread { showSuccess() }
                    } else {
                        uiHandler.postDelayed(this, POLL_INTERVAL_MS)
                    }
                } catch (_: Exception) {
                    // Network error during poll — retry later
                    uiHandler.postDelayed(this, POLL_INTERVAL_MS)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        applyOrientationPref()

        // Already fully paired — go straight to playback
        if (DevicePrefs(this).isPaired()) {
            startPlayback()
            return
        }

        setContentView(R.layout.activity_pairing)

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

        // If already claimed but not yet confirmed by admin, resume showing the code
        val existingToken = prefs.getDeviceToken()
        val existingCode  = prefs.getPairingCode()
        if (existingToken != null && existingCode != null) {
            showCode(existingCode)
            startPolling()
            return
        }

        // First boot — auto-claim
        autoClaim(prefs)
    }

    private fun autoClaim(prefs: DevicePrefs) {
        showLoading()
        val hardwareKey = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            ?: "unknown-device"

        executor.execute {
            try {
                val response = if (BuildConfig.DEMO_MODE) {
                    DemoApiProvider.claimDevice(hardwareKey)
                } else {
                    DeviceApiProvider().claimDevice(hardwareKey)
                }

                if (BuildConfig.DEMO_MODE || response.pairingCode.isEmpty()) {
                    // Demo mode or device already admin-confirmed (e.g. reinstall on existing device)
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
                    runOnUiThread { startPlayback() }
                } else {
                    // Needs admin confirmation — store pending state and show code
                    prefs.storePairingPending(response.token, response.deviceId, response.pairingCode)
                    runOnUiThread {
                        showCode(response.pairingCode)
                        startPolling()
                    }
                }
            } catch (ex: Exception) {
                runOnUiThread {
                    showError(ex.message?.take(120) ?: ex.javaClass.simpleName)
                }
            }
        }
    }

    private fun startPolling() {
        polling = true
        uiHandler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
    }

    private fun showLoading() {
        card(R.id.card_loading, true)
        card(R.id.card_code,    false)
        card(R.id.card_success, false)
        card(R.id.card_error,   false)
        status("")
    }

    private fun showCode(code: String) {
        card(R.id.card_loading, false)
        card(R.id.card_code,    true)
        card(R.id.card_success, false)
        card(R.id.card_error,   false)
        findViewById<TextView>(R.id.pairing_code_value).text = code
        findViewById<ImageView>(R.id.qr_admin_image).setImageBitmap(
            generatePairingQr("${BuildConfig.API_BASE_URL}/admin/pair?code=$code")
        )
        status("")
    }

    /** QR code linking straight to the admin "connect this screen" page, prefilled with [code]. */
    private fun generatePairingQr(content: String, sizePx: Int = 300): Bitmap {
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx)
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
        for (x in 0 until sizePx) {
            for (y in 0 until sizePx) {
                bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return bitmap
    }

    private fun showSuccess() {
        polling = false
        uiHandler.removeCallbacks(pollRunnable)
        card(R.id.card_loading, false)
        card(R.id.card_code,    false)
        card(R.id.card_success, true)
        card(R.id.card_error,   false)
        status("")
        uiHandler.postDelayed({ startPlayback() }, 2_000)
    }

    private fun showError(message: String) {
        card(R.id.card_loading, false)
        card(R.id.card_code,    false)
        card(R.id.card_success, false)
        card(R.id.card_error,   true)
        findViewById<TextView>(R.id.error_message).text = message
        status("")
        findViewById<Button>(R.id.retry_button).setOnClickListener {
            autoClaim(DevicePrefs(this))
        }
    }

    private fun card(id: Int, visible: Boolean) {
        findViewById<LinearLayout>(id).visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun status(text: String) {
        val tv = findViewById<TextView?>(R.id.pairing_status) ?: return
        tv.text = text
    }

    private fun startPlayback() {
        startActivity(Intent(this, PlaybackActivity::class.java))
        finish()
    }

    override fun onDestroy() {
        polling = false
        uiHandler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val POLL_INTERVAL_MS = 5_000L
    }
}
