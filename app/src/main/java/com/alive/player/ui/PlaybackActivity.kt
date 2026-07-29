package com.alive.player.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import androidx.media3.ui.PlayerView
import com.alive.player.BuildConfig
import com.alive.player.R
import com.alive.player.data.AppDatabase
import com.alive.player.service.PlaybackForegroundService
import com.alive.player.settings.DevicePrefs
import com.alive.player.settings.FetchStatus
import com.alive.player.settings.SettingsActivity
import com.alive.player.worker.InAppUpdateHelper
import com.alive.player.worker.PlanFetchScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PlaybackActivity : Activity() {

    private var engine: com.alive.player.playback.PlaybackEngine? = null
    private var bound = false

    private lateinit var playerView: PlayerView
    private lateinit var imageView: ImageView
    private lateinit var webView: WebView
    private lateinit var contentRotator: FrameLayout
    private lateinit var waitingOverlay: RelativeLayout
    private lateinit var waitingProgress: ProgressBar
    private lateinit var statusIcon: TextView
    private lateinit var waitingStatus: TextView
    private lateinit var statusDetail: TextView
    private lateinit var downloadProgress: ProgressBar
    private lateinit var retryButton: Button
    private lateinit var networkDot: View
    private lateinit var networkLabel: TextView
    private lateinit var offlineBadge: TextView
    private lateinit var diagOverlay: FrameLayout
    private lateinit var diagDeviceId: TextView
    private lateinit var diagIp: TextView
    private lateinit var diagVersion: TextView
    private lateinit var diagLastFetch: TextView
    private lateinit var diagStorage: TextView
    private lateinit var diagPending: TextView
    private lateinit var adminControls: LinearLayout
    private lateinit var btnRotate: Button
    private lateinit var btnRefresh: Button
    private lateinit var newContentBanner: LinearLayout
    private lateinit var downloadCorner: LinearLayout
    private lateinit var downloadCornerText: TextView
    private lateinit var downloadCornerBar: ProgressBar

    private lateinit var connectivityManager: ConnectivityManager
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = runOnUiThread { setNetworkState(online = true) }
        override fun onLost(network: Network) = runOnUiThread { setNetworkState(online = false) }
    }

    private val uiHandler = Handler(Looper.getMainLooper())

    // 5-tap gesture → Settings
    private var tapCount = 0
    private var lastTapMs = 0L

    // Kiosk escape hatch: triple-press Select (D-pad center) within 2s → Settings.
    // Needed because the 5-tap gesture only exists on the waiting overlay, which is
    // GONE during normal playback — otherwise there'd be no remote-only way to reach
    // Settings once content is actively playing.
    private var selectPressCount = 0
    private var lastSelectPressMs = 0L

    // Plan-change detection
    private var lastKnownPlanMs = 0L
    private val planPollRunnable = object : Runnable {
        override fun run() {
            // Admin-assigned orientation isn't part of the plan-changed signal below (it
            // doesn't bump planUpdatedMs), so re-apply it every tick -- cheap and a no-op
            // when unchanged, but picks up a remote orientation change within one poll.
            applyContentRotation()

            val updatedMs = DevicePrefs(this@PlaybackActivity).getPlanUpdatedMs()
            when {
                lastKnownPlanMs == 0L       -> lastKnownPlanMs = updatedMs
                updatedMs > lastKnownPlanMs -> {
                    lastKnownPlanMs = updatedMs
                    showNewContentBanner()
                    engine?.startLoop()
                }
            }
            uiHandler.postDelayed(this, 30_000)
        }
    }

    // Track bytes between download polls to calculate speed
    private var lastPollBytesMs  = 0L
    private var lastPollBytes    = 0L

    // Download corner polling
    private val downloadPollRunnable = object : Runnable {
        override fun run() {
            CoroutineScope(Dispatchers.IO).launch {
                val dao        = AppDatabase.get(applicationContext).downloadJobDao()
                val done       = dao.doneCount()
                val total      = dao.totalCount()
                val doneBytes  = dao.doneBytesSum()
                val totalBytes = dao.totalBytesSum()
                withContext(Dispatchers.Main) {
                    if (total > 0 && done < total) {
                        val pct       = if (total > 0) done * 100 / total else 0
                        val nowMs     = System.currentTimeMillis()
                        val speedKBps = if (lastPollBytesMs > 0 && nowMs > lastPollBytesMs) {
                            ((doneBytes - lastPollBytes) * 1000L / (nowMs - lastPollBytesMs) / 1024L)
                                .coerceAtLeast(0L)
                        } else 0L
                        lastPollBytesMs = nowMs
                        lastPollBytes   = doneBytes

                        val speedStr = if (speedKBps >= 1024) "${speedKBps / 1024} MB/s"
                                       else if (speedKBps > 0) "$speedKBps KB/s"
                                       else ""
                        val sizeStr  = if (totalBytes > 0) {
                            val doneMb  = doneBytes / 1024 / 1024
                            val totalMb = totalBytes / 1024 / 1024
                            " · ${doneMb}/${totalMb} MB"
                        } else ""

                        downloadCorner.visibility = View.VISIBLE
                        downloadCornerText.text   = "↓ $done/$total ($pct%)$sizeStr${if (speedStr.isNotEmpty()) " · $speedStr" else ""}"
                        downloadCornerBar.max     = total
                        downloadCornerBar.progress = done
                    } else {
                        lastPollBytesMs = 0L
                        lastPollBytes   = 0L
                        downloadCorner.visibility = View.GONE
                        if (waitingOverlay.visibility == View.VISIBLE) {
                            engine?.startLoop()
                        }
                    }
                }
            }
            uiHandler.postDelayed(this, 5_000)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val service = (binder as PlaybackForegroundService.LocalBinder).getService()
            val eng = service.engine
            engine = eng

            eng.onWaiting = { statusLine ->
                waitingOverlay.visibility = View.VISIBLE
                updateStatusCard(statusLine)
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

        playerView        = findViewById(R.id.player_view)
        imageView         = findViewById(R.id.image_view)
        webView           = findViewById(R.id.web_view)
        contentRotator    = findViewById(R.id.content_rotator)
        applyContentRotation()
        waitingOverlay    = findViewById(R.id.waiting_overlay)
        waitingProgress   = findViewById(R.id.waiting_progress)
        statusIcon        = findViewById(R.id.status_icon)
        waitingStatus     = findViewById(R.id.waiting_status)
        statusDetail      = findViewById(R.id.status_detail)
        downloadProgress  = findViewById(R.id.download_progress)
        retryButton       = findViewById(R.id.retry_now_button)
        networkDot        = findViewById(R.id.network_dot)
        networkLabel      = findViewById(R.id.network_label)
        offlineBadge      = findViewById(R.id.offline_badge)
        diagOverlay       = findViewById(R.id.diag_overlay)
        diagDeviceId      = findViewById(R.id.diag_device_id)
        diagIp            = findViewById(R.id.diag_ip)
        diagVersion       = findViewById(R.id.diag_version)
        diagLastFetch     = findViewById(R.id.diag_last_fetch)
        diagStorage       = findViewById(R.id.diag_storage)
        diagPending       = findViewById(R.id.diag_pending)
        adminControls     = findViewById(R.id.admin_controls)
        btnRotate         = findViewById(R.id.btn_rotate)
        btnRefresh        = findViewById(R.id.btn_refresh)
        newContentBanner  = findViewById(R.id.new_content_banner)
        downloadCorner    = findViewById(R.id.download_corner)
        downloadCornerText = findViewById(R.id.download_corner_text)
        downloadCornerBar = findViewById(R.id.download_corner_bar)

        // ── Retry / refresh ──────────────────────────────────────────────
        retryButton.setOnClickListener { triggerRefresh() }
        btnRefresh.setOnClickListener  { triggerRefresh() }

        // ── Rotate ───────────────────────────────────────────────────────
        btnRotate.setOnClickListener {
            cycleOrientation()
            applyContentRotation()
            flashControl(btnRotate)
        }

        // ── 5-tap → Settings ─────────────────────────────────────────────
        waitingOverlay.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - lastTapMs > 3_000) tapCount = 0
            lastTapMs = now
            if (++tapCount >= 5) {
                tapCount = 0
                startActivity(Intent(this, SettingsActivity::class.java))
            }
        }

        // ── Long-press → diagnostic overlay ──────────────────────────────
        waitingOverlay.setOnLongClickListener {
            showPinDialog()
            true
        }

        // ── Diag overlay dismiss ──────────────────────────────────────────
        diagOverlay.setOnClickListener { diagOverlay.visibility = View.GONE }

        // ── Connectivity ─────────────────────────────────────────────────
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityManager.registerNetworkCallback(
            NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build(),
            networkCallback,
        )
        val isOnline = connectivityManager
            .getNetworkCapabilities(connectivityManager.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        setNetworkState(isOnline)

        // ── Start polls ───────────────────────────────────────────────────
        uiHandler.post(planPollRunnable)
        uiHandler.post(downloadPollRunnable)

        val serviceIntent = Intent(this, PlaybackForegroundService::class.java)
        startForegroundService(serviceIntent)
        bindService(serviceIntent, connection, BIND_AUTO_CREATE)
    }

    // Many budget/OEM Android TV panels accept requestedOrientation without physically
    // rotating -- the window shrinks to a portrait-shaped region in the middle of the
    // still-landscape panel instead of filling it. Rotating the content container itself
    // (and swapping its measured dimensions to match) fills the real panel regardless of
    // whether the hardware actually rotates, and gives us a "reverse portrait" that
    // reliably works instead of depending on OS-level SCREEN_ORIENTATION_REVERSE_PORTRAIT
    // support.
    private fun applyContentRotation() {
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        val mode = DevicePrefs(this).getOrientationMode()
        val rotation = when (mode) {
            DevicePrefs.ORIENTATION_PORTRAIT         -> 90f
            DevicePrefs.ORIENTATION_REVERSE_PORTRAIT -> -90f
            else                                     -> 0f
        }
        if (contentRotator.rotation == rotation) return
        contentRotator.rotation = rotation
        val lp = contentRotator.layoutParams
        if (rotation == 0f) {
            lp.width  = FrameLayout.LayoutParams.MATCH_PARENT
            lp.height = FrameLayout.LayoutParams.MATCH_PARENT
        } else {
            // Swapped so the rotated bounding box exactly fills the physical (landscape) panel
            lp.width  = screenH
            lp.height = screenW
        }
        contentRotator.layoutParams = lp
    }

    private fun triggerRefresh() {
        retryButton.isEnabled = false
        waitingStatus.text = "Fetching schedule…"
        flashControl(btnRefresh)
        PlanFetchScheduler.scheduleImmediate(applicationContext)
        uiHandler.postDelayed({
            retryButton.isEnabled = true
            engine?.startLoop()
        }, 3_000)
    }

    private fun flashControl(view: View) {
        view.alpha = 1f
        uiHandler.postDelayed({ view.alpha = 1f; adminControls.alpha = 0.55f }, 800)
        adminControls.alpha = 1f
    }

    private fun showNewContentBanner() {
        newContentBanner.visibility = View.VISIBLE
        uiHandler.postDelayed({ newContentBanner.visibility = View.GONE }, 4_000)
    }

    private fun setNetworkState(online: Boolean) {
        val dotColor = if (online) Color.parseColor("#22c55e") else Color.parseColor("#dc2626")
        networkDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(dotColor)
        }
        networkLabel.text = if (online) "ONLINE" else "OFFLINE"
        offlineBadge.visibility =
            if (online || waitingOverlay.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    private fun updateStatusCard(statusLine: String) {
        val fetchStatus = DevicePrefs(this).getFetchStatus()
        when (fetchStatus?.name) {
            FetchStatus.NO_SCHEDULE.name -> {
                waitingProgress.visibility = View.GONE
                statusIcon.apply { visibility = View.VISIBLE; text = "⚠"; setTextColor(Color.parseColor("#f59e0b")) }
                waitingStatus.text = "No schedule assigned"
                showDetail("wearealive.in/admin → assign a schedule")
                downloadProgress.visibility = View.GONE
                retryButton.visibility = View.VISIBLE
            }
            FetchStatus.ERROR.name -> {
                waitingProgress.visibility = View.GONE
                statusIcon.apply { visibility = View.VISIBLE; text = "✕"; setTextColor(Color.parseColor("#dc2626")) }
                waitingStatus.text = "Connection error"
                showDetail(fetchStatus.message.take(80).ifBlank { "Unknown error" })
                downloadProgress.visibility = View.GONE
                retryButton.visibility = View.VISIBLE
            }
            FetchStatus.FETCHING.name -> {
                waitingProgress.visibility = View.VISIBLE
                statusIcon.visibility = View.GONE
                waitingStatus.text = "Checking for updates…"
                statusDetail.visibility = View.GONE
                downloadProgress.visibility = View.GONE
                retryButton.visibility = View.GONE
            }
            FetchStatus.OK.name, FetchStatus.NO_CONTENT.name -> {
                CoroutineScope(Dispatchers.IO).launch {
                    val dao        = AppDatabase.get(applicationContext).downloadJobDao()
                    val done       = dao.doneCount()
                    val total      = dao.totalCount()
                    val doneBytes  = dao.doneBytesSum()
                    val totalBytes = dao.totalBytesSum()
                    withContext(Dispatchers.Main) {
                        if (total > 0 && done < total) {
                            val pct = if (total > 0) done * 100 / total else 0
                            val sizeStr = if (totalBytes > 0) {
                                val doneMb  = doneBytes / 1024 / 1024
                                val totalMb = totalBytes / 1024 / 1024
                                " · ${doneMb}/${totalMb} MB"
                            } else ""
                            waitingProgress.visibility = View.GONE
                            statusIcon.apply { visibility = View.VISIBLE; text = "↓"; setTextColor(Color.parseColor("#60a5fa")) }
                            waitingStatus.text = "Downloading $done of $total ($pct%)"
                            showDetail("Please wait — content will play when ready$sizeStr")
                            downloadProgress.apply { visibility = View.VISIBLE; max = total; progress = done }
                            retryButton.visibility = View.GONE
                        } else {
                            waitingProgress.visibility = View.GONE
                            statusIcon.apply { visibility = View.VISIBLE; text = "✓"; setTextColor(Color.parseColor("#22c55e")) }
                            waitingStatus.text = "Schedule synced — starting soon"
                            statusDetail.visibility = View.GONE
                            downloadProgress.visibility = View.GONE
                            retryButton.visibility = View.GONE
                        }
                    }
                }
                return
            }
            else -> {
                waitingProgress.visibility = View.VISIBLE
                statusIcon.visibility = View.GONE
                waitingStatus.text = statusLine.lines().firstOrNull() ?: "Waiting for schedule…"
                val detail = statusLine.lines().drop(1).joinToString(" ").trim()
                if (detail.isNotEmpty()) showDetail(detail) else statusDetail.visibility = View.GONE
                downloadProgress.visibility = View.GONE
                retryButton.visibility = View.GONE
            }
        }
    }

    private fun showDetail(text: String) {
        statusDetail.text = text
        statusDetail.visibility = View.VISIBLE
    }

    private fun showPinDialog() {
        val pinInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            hint = "Last 4 chars of Device ID"
            textSize = 18f
        }
        AlertDialog.Builder(this)
            .setTitle("Device Diagnostics")
            .setMessage("Enter PIN (last 4 characters of the Device ID shown in admin)")
            .setView(pinInput)
            .setPositiveButton("Open") { _, _ ->
                val entered  = pinInput.text.toString().uppercase()
                val expected = DevicePrefs(this).getDeviceId()?.takeLast(4)?.uppercase() ?: ""
                if (entered == expected) loadAndShowDiagOverlay()
                else Toast.makeText(this, "Incorrect PIN", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun loadAndShowDiagOverlay() {
        CoroutineScope(Dispatchers.IO).launch {
            val prefs       = DevicePrefs(applicationContext)
            val db          = AppDatabase.get(applicationContext)
            val deviceId    = prefs.getDeviceId() ?: "—"
            val ip          = getLocalIpAddress()
            val version     = BuildConfig.VERSION_NAME
            val fetchStatus = prefs.getFetchStatus()
            val lastFetch   = if (fetchStatus != null && fetchStatus.timeMs > 0) relativeTime(fetchStatus.timeMs) else "—"
            val cacheDir    = applicationContext.getExternalFilesDir("cache") ?: applicationContext.cacheDir
            val cacheMb     = dirSizeBytes(cacheDir) / 1024 / 1024
            val freeMb      = android.os.StatFs(cacheDir.path).availableBytes / 1024 / 1024
            val pending     = db.proofEventDao().getPending().size

            withContext(Dispatchers.Main) {
                diagDeviceId.text  = "Device ID: $deviceId"
                diagIp.text        = "IP address: $ip"
                diagVersion.text   = "App version: $version"
                diagLastFetch.text = "Last fetch: $lastFetch"
                diagStorage.text   = "Cache: ${cacheMb}MB used · ${freeMb}MB free"
                diagPending.text   = "Pending uploads: $pending"
                diagOverlay.visibility = View.VISIBLE
            }
        }
    }

    private fun getLocalIpAddress(): String =
        runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList()
                ?.flatMap { it.inetAddresses.toList() }
                ?.firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
                ?.hostAddress
        }.getOrNull() ?: "—"

    private fun dirSizeBytes(dir: File): Long =
        dir.walkTopDown().sumOf { if (it.isFile) it.length() else 0L }

    private fun relativeTime(epochMs: Long): String {
        val diffMs = System.currentTimeMillis() - epochMs
        return when {
            diffMs < 60_000     -> "just now"
            diffMs < 3_600_000  -> "${diffMs / 60_000}m ago"
            diffMs < 86_400_000 -> "${diffMs / 3_600_000}h ago"
            else                -> SimpleDateFormat("MMM d HH:mm", Locale.getDefault()).format(Date(epochMs))
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Re-apply fullscreen after orientation change (system may clear UI flags)
        window.decorView.postDelayed({ onWindowFocusChanged(true) }, 100)
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

    override fun onResume() {
        super.onResume()
        InAppUpdateHelper.checkForUpdate(this)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == InAppUpdateHelper.REQUEST_CODE && resultCode != RESULT_OK) {
            // Update was cancelled or failed — Play will retry on next resume
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onDestroy() {
        engine?.onWaiting = null
        engine?.onPlaying = null
        engine?.detachViews()
        if (bound) {
            unbindService(connection)
            bound = false
        }
        runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
        uiHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    @Deprecated("Kiosk mode: back press is suppressed")
    override fun onBackPressed() {
        // intentionally no-op — kiosk mode
    }

    /**
     * Kiosk key hardening: swallow navigation keys so an accidental (or curious)
     * remote press doesn't exit the player. KEYCODE_HOME is a partial measure only —
     * on a normal (non-lock-task) foreground app, Android delivers HOME to the system
     * launcher before it ever reaches dispatchKeyEvent, so this can't reliably block
     * it. Being registered as the persistent HOME app (OwnerSetup) means the system
     * brings the player right back, but a moment of home-screen exposure is possible.
     * Fully preventing that needs startLockTask()/setLockTaskPackages() — a bigger,
     * separate change not included here.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    val now = System.currentTimeMillis()
                    if (now - lastSelectPressMs > 2_000) selectPressCount = 0
                    lastSelectPressMs = now
                    if (++selectPressCount >= 3) {
                        selectPressCount = 0
                        startActivity(Intent(this, SettingsActivity::class.java))
                        return true
                    }
                    // Only swallow the press when there's no interactive control to click
                    // (active playback, waiting overlay hidden). When the waiting overlay
                    // is showing (Retry Now, rotate, refresh buttons), let it through so
                    // the focused button actually receives the click.
                    if (waitingOverlay.visibility != View.VISIBLE) return true
                }
                KeyEvent.KEYCODE_BACK,
                KeyEvent.KEYCODE_HOME,
                KeyEvent.KEYCODE_MENU,
                KeyEvent.KEYCODE_SEARCH,
                KeyEvent.KEYCODE_APP_SWITCH ->
                    // Remote-configurable (see PlayerConfig.kioskKeyLockEnabled) so a
                    // technician can get full remote control back for debugging without
                    // a rebuild.
                    if (DevicePrefs(this).isKioskKeyLockEnabled()) return true
            }
        }
        return super.dispatchKeyEvent(event)
    }
}
