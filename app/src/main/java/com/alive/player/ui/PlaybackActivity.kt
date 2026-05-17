package com.alive.player.ui

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.view.WindowManager
import android.webkit.WebView
import android.widget.ImageView
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.media3.ui.PlayerView
import com.alive.player.R
import com.alive.player.service.PlaybackForegroundService
import com.alive.player.settings.DevicePrefs

class PlaybackActivity : Activity() {

    private var engine: com.alive.player.playback.PlaybackEngine? = null
    private var bound = false

    private lateinit var playerView: PlayerView
    private lateinit var imageView: ImageView
    private lateinit var webView: WebView
    private lateinit var waitingOverlay: RelativeLayout
    private lateinit var waitingStatus: TextView

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val service = (binder as PlaybackForegroundService.LocalBinder).getService()
            val eng = service.engine
            engine = eng

            eng.onWaiting = {
                waitingOverlay.visibility = View.VISIBLE
                waitingStatus.text = "Fetching schedule..."
            }
            eng.onPlaying = {
                waitingOverlay.visibility = View.GONE
            }

            eng.attachViews(playerView, imageView, webView)
            bound = true
        }

        override fun onServiceDisconnected(name: ComponentName) {
            bound = false
            engine = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_playback)

        playerView = findViewById(R.id.player_view)
        imageView = findViewById(R.id.image_view)
        webView = findViewById(R.id.web_view)
        waitingOverlay = findViewById(R.id.waiting_overlay)
        waitingStatus = findViewById(R.id.waiting_status)

        val deviceId = DevicePrefs(this).getDeviceId()
        if (!deviceId.isNullOrBlank()) {
            waitingStatus.text = "Device: $deviceId"
        }

        val serviceIntent = Intent(this, PlaybackForegroundService::class.java)
        startForegroundService(serviceIntent)
        bindService(serviceIntent, connection, BIND_AUTO_CREATE)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            )
        }
    }

    override fun onDestroy() {
        engine?.onWaiting = null
        engine?.onPlaying = null
        engine?.detachViews()
        if (bound) {
            unbindService(connection)
            bound = false
        }
        super.onDestroy()
    }

    @Deprecated("Kiosk mode: back press is suppressed")
    override fun onBackPressed() {
        // intentionally no-op — kiosk mode
    }
}
