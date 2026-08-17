package com.alive.player.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
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
import com.alive.player.admin.AliveDeviceAdminReceiver
import com.alive.player.admin.OwnerSetup
import com.alive.player.data.AppDatabase
import com.alive.player.service.PlaybackForegroundService
import com.alive.player.service.WatchdogService
import com.alive.player.settings.DevicePrefs
import com.alive.player.settings.FetchStatus
import com.alive.player.settings.SettingsActivity
import com.alive.player.worker.HeartbeatScheduler
import com.alive.player.worker.PlanFetchScheduler
import com.alive.player.worker.UpdateScheduler
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
    private lateinit var playerViewB: PlayerView
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

    // Kiosk EXIT hatch: 5 distinct BACK presses inside one EXIT_BACK_WINDOW_MS window
    // (anchored at the burst's FIRST press) → tear the kiosk down (see exitKiosk).
    // Counted in dispatchKeyEvent rather than onBackPressed so it fires even while
    // kioskKeyLockEnabled swallows BACK. Auto-repeat events (held button) are ignored.
    private var backPressCount = 0
    private var firstBackPressMs = 0L

    // Cached so dispatchKeyEvent never touches SharedPreferences (main thread, fires on
    // every key press). Refreshed by the 30s plan poll below.
    private var kioskKeyLockEnabled = true

    // Plan-change detection
    private var lastKnownPlanMs = 0L
    private val planPollRunnable = object : Runnable {
        override fun run() {
            // Admin-assigned orientation isn't part of the plan-changed signal below (it
            // doesn't bump planUpdatedMs), so re-apply it every tick -- cheap and a no-op
            // when unchanged, but picks up a remote orientation change within one poll.
            applyContentRotation()

            val prefs = DevicePrefs(this@PlaybackActivity)
            kioskKeyLockEnabled = prefs.isKioskKeyLockEnabled()

            val updatedMs = prefs.getPlanUpdatedMs()
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
                        // Kick on the ENGINE's state, never this activity's overlay view:
                        // onWaiting/onPlaying are single vars on the shared service engine,
                        // so an activity that is no longer the newest-bound one has a
                        // frozen overlay — judging by it, a buried instance called
                        // startLoop() every 5s forever (fleet-wide item-0 replay,
                        // 2026-08-14). The engine always knows whether it's really idle.
                        if (engine?.isWaitingForContent == true) {
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

            // The engine outlives this activity (it belongs to the foreground service) and
            // may already be mid-loop when we bind — an install/relaunch rebinds after
            // playback resumed, so onPlaying fired before these handlers existed and will
            // not fire again until the next startLoop(). Sync the overlay to the engine's
            // actual state or it stays up (layout default VISIBLE) over healthy playback.
            waitingOverlay.visibility =
                if (eng.isWaitingForContent) View.VISIBLE else View.GONE

            eng.attachViews(playerView, playerViewB, imageView, webView)
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
        playerViewB       = findViewById(R.id.player_view_b)
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

        // Polls start in onStart, not here — see onStart/onStop.

        // Resume full kiosk whenever playback is (re)opened — this is what makes the
        // 5×-BACK exit "temporary": launching from the apps menu re-claims HOME and
        // re-enables the periodic workers that exitKiosk() tore down. All idempotent,
        // so it's a harmless no-op on a normal (never-exited) launch.
        resumeKioskGuards()

        val serviceIntent = Intent(this, PlaybackForegroundService::class.java)
        startForegroundService(serviceIntent)
        bindService(serviceIntent, connection, BIND_AUTO_CREATE)
    }

    /**
     * The pollers live between onStart and onStop, NOT onCreate→onDestroy: a
     * backgrounded-but-alive instance (Settings on top, or a second instance stacked by
     * a relaunch before launchMode="singleTask" existed) must not keep polling — its
     * views no longer track the shared engine (see downloadPollRunnable), so its polls
     * act on stale state. Three such instances, each kicking engine.startLoop() every
     * 5s, were the fleet-wide "first 5s of item 0 on repeat" outage of 2026-08-14.
     */
    override fun onStart() {
        super.onStart()
        uiHandler.post(planPollRunnable)
        uiHandler.post(downloadPollRunnable)
    }

    override fun onStop() {
        // Only the pollers — a blanket removeCallbacksAndMessages would also kill
        // unrelated one-shots (banner auto-hide, retry re-enable) mid-flight.
        uiHandler.removeCallbacks(planPollRunnable)
        uiHandler.removeCallbacks(downloadPollRunnable)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        enterLockTaskIfOwner()
    }

    // singleTask relaunch (launcher icon, Pairing forward, OTA relaunch) lands here on
    // the existing instance instead of stacking a new one. Re-assert the kiosk guards
    // exactly like a fresh onCreate would — reopening the app is the documented way to
    // undo the 5×BACK kiosk exit, and that must keep working when the instance is reused.
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        resumeKioskGuards()
    }

    /**
     * Lock-task (kiosk pinning) — Device-Owner installs only. Pins this task so
     * HOME/RECENTS can't leave it, with no consent toast (the package is allowlisted
     * by OwnerSetup.setLockTaskPackages, which resumeKioskGuards has already run by
     * the time onResume fires). No-op on non-owner installs. exitKiosk() unpins, so
     * the 5×BACK escape and the in-app Settings buttons (allowlisted packages) keep
     * working. Note: at least one Google-TV OEM build does not resume lock task
     * across a reboot — there this pins at runtime but boot still shows the OEM
     * launcher briefly until BootReceiver relaunches playback.
     */
    private fun enterLockTaskIfOwner() {
        if (!OwnerSetup.isDeviceOwner(this)) return
        val am = getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager ?: return
        if (am.lockTaskModeState == android.app.ActivityManager.LOCK_TASK_MODE_NONE) {
            runCatching { startLockTask() }
        }
    }

    /** Re-assert the HOME claim + periodic workers. Idempotent; reverses exitKiosk(). */
    private fun resumeKioskGuards() {
        runCatching { OwnerSetup.onDeviceOwnerReady(this) }
        runCatching {
            HeartbeatScheduler.schedule(this)
            PlanFetchScheduler.schedule(this)
            UpdateScheduler.schedule(this)
        }
    }

    // Many budget/OEM Android TV panels accept requestedOrientation without physically
    // rotating -- the window shrinks to a portrait-shaped region in the middle of the
    // still-landscape panel instead of filling it. Rotating the content container itself
    // (and swapping its measured dimensions to match) fills the real panel regardless of
    // whether the hardware actually rotates, and gives us a "reverse portrait" that
    // reliably works instead of depending on OS-level SCREEN_ORIENTATION_REVERSE_PORTRAIT
    // support.
    private fun applyContentRotation() {
        // Rotation is relative: how far the content must turn to reach the requested
        // orientation FROM whatever the panel actually gave us. A fixed 90° was wrong
        // because these panels are inconsistent — some report portrait natively, some
        // landscape, and the same unit can differ between boots. Rotating blindly then
        // lands portrait content sideways half the time.
        val isLandscapeNow = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val rotation = when (DevicePrefs(this).getOrientationMode()) {
            DevicePrefs.ORIENTATION_PORTRAIT         -> if (isLandscapeNow) 90f  else 0f
            DevicePrefs.ORIENTATION_REVERSE_PORTRAIT -> if (isLandscapeNow) 270f else 180f
            DevicePrefs.ORIENTATION_LANDSCAPE        -> if (isLandscapeNow) 0f   else 90f
            else                                     -> 0f
        }

        // Quarter turns need the container's width/height swapped so its rotated
        // bounding box covers the panel; half turns and no-ops keep the panel's own
        // dimensions. The view is layout_gravity="center", which matters: rotation
        // pivots about the view's centre, so a top-left-aligned container with swapped
        // dimensions would swing its content off the edge of the screen.
        val quarterTurn = rotation == 90f || rotation == 270f
        val lp = contentRotator.layoutParams
        val wantW = if (quarterTurn) resources.displayMetrics.heightPixels else FrameLayout.LayoutParams.MATCH_PARENT
        val wantH = if (quarterTurn) resources.displayMetrics.widthPixels  else FrameLayout.LayoutParams.MATCH_PARENT

        if (contentRotator.rotation == rotation && lp.width == wantW && lp.height == wantH) return

        contentRotator.rotation = rotation
        lp.width  = wantW
        lp.height = wantH
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
                retryButton.requestFocus()
            }
            FetchStatus.ERROR.name -> {
                waitingProgress.visibility = View.GONE
                statusIcon.apply { visibility = View.VISIBLE; text = "✕"; setTextColor(Color.parseColor("#dc2626")) }
                waitingStatus.text = "Connection error"
                showDetail(fetchStatus.message.take(80).ifBlank { "Unknown error" })
                downloadProgress.visibility = View.GONE
                retryButton.visibility = View.VISIBLE
                retryButton.requestFocus()
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
                            // fetchStatus only describes the network fetch, so on its own it
                            // cannot explain why nothing is playing. statusLine carries the
                            // playback engine's actual reason (e.g. no content for the current
                            // time slot) — showing the generic "starting soon" instead left the
                            // screen claiming it was about to start, forever, with the real
                            // reason discarded. Prefer the engine's reason whenever it has one.
                            val engineReason = statusLine
                                .takeIf { it.isNotBlank() && !it.startsWith("Schedule loaded") }
                                ?.lines()?.firstOrNull()?.trim()
                            waitingProgress.visibility = View.GONE
                            statusIcon.apply {
                                visibility = View.VISIBLE
                                text = if (engineReason != null) "⚠" else "✓"
                                setTextColor(Color.parseColor(if (engineReason != null) "#f59e0b" else "#22c55e"))
                            }
                            waitingStatus.text = engineReason ?: "Schedule synced — starting soon"
                            if (engineReason != null) {
                                showDetail("Check the schedule's date range and daily hours in admin")
                                retryButton.visibility = View.VISIBLE
                                retryButton.requestFocus()
                            } else {
                                statusDetail.visibility = View.GONE
                                retryButton.visibility = View.GONE
                            }
                            downloadProgress.visibility = View.GONE
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
        // The panel's own orientation just changed, so the relative content rotation and
        // the swapped container dimensions are both stale. Recompute immediately —
        // waiting for the 30s poll would leave content sideways in the meantime.
        // Posted so displayMetrics reflects the new configuration, not the old one.
        window.decorView.post { applyContentRotation() }
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

    // Updates are delivered OTA (UpdateCheckWorker -> /api/device/update-check), not by
    // Play Store. Play's in-app update flow was removed: these screens are unattended
    // kiosks with no one to accept a prompt, most aren't Play-certified TVs anyway, and
    // its IMMEDIATE flow puts a full-screen Google UI over paid ad playback.

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
     *
     * Two deliberate exceptions punch through the lock:
     *   • MENU always opens Settings (see below).
     *   • BACK pressed 5× within EXIT_BACK_WINDOW_MS runs exitKiosk(), which unwinds the
     *     HOME claim + services + workers and drops to the Android launcher — the one
     *     path that genuinely leaves playback (a single HOME just bounces off the HOME
     *     claim; Settings is only a sub-screen). Reopening the app resumes kiosk.
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
                KeyEvent.KEYCODE_MENU -> {
                    // Dedicated, always-available escape hatch (unlike the nav keys below,
                    // not gated behind kioskKeyLockEnabled) -- MENU is a deliberate press,
                    // not something a remote gets bumped into accidentally the way BACK/HOME
                    // can be, so it's safe to always open Settings rather than requiring the
                    // triple-select gesture.
                    startActivity(Intent(this, SettingsActivity::class.java))
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_UP,
                KeyEvent.KEYCODE_VOLUME_DOWN,
                KeyEvent.KEYCODE_VOLUME_MUTE -> {
                    // Handle volume explicitly rather than trusting the fall-through:
                    // some OEM builds route volume through their launcher, and lock-task
                    // mode can suppress the system volume handling entirely — either way
                    // the operator's volume buttons must always work during playback.
                    // Auto-repeats deliberately count here (holding = keep adjusting).
                    val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                    val dir = when (event.keyCode) {
                        KeyEvent.KEYCODE_VOLUME_UP   -> android.media.AudioManager.ADJUST_RAISE
                        KeyEvent.KEYCODE_VOLUME_DOWN -> android.media.AudioManager.ADJUST_LOWER
                        else                         -> android.media.AudioManager.ADJUST_TOGGLE_MUTE
                    }
                    runCatching {
                        am.adjustStreamVolume(
                            android.media.AudioManager.STREAM_MUSIC,
                            dir,
                            android.media.AudioManager.FLAG_SHOW_UI,
                        )
                    }
                    return true
                }
                KeyEvent.KEYCODE_BACK -> {
                    // 5 distinct presses within one window is the deliberate EXIT gesture.
                    // Counted BEFORE the kiosk swallow below so it works even when the key
                    // lock is on. repeatCount > 0 events are key auto-repeat from a HELD
                    // button (~500ms then every ~50ms on IR/BLE/CEC remotes) — never count
                    // those, or holding BACK for one second would tear the kiosk down.
                    if (event.repeatCount == 0) {
                        val now = System.currentTimeMillis()
                        // Window anchored at the burst's FIRST press: 5 presses must all
                        // land inside EXIT_BACK_WINDOW_MS. (Anchoring on the previous
                        // press instead would let slow presses 2.9s apart accumulate to
                        // an exit over ~12s — an accidental-trigger vector.)
                        if (backPressCount == 0 || now - firstBackPressMs > EXIT_BACK_WINDOW_MS) {
                            backPressCount = 0
                            firstBackPressMs = now
                        }
                        if (++backPressCount >= EXIT_BACK_PRESSES) {
                            backPressCount = 0
                            exitKiosk()
                            return true
                        }
                        // Visible feedback from the 3rd press: makes the gesture
                        // discoverable for technicians and warns bystanders idly
                        // pressing a silently-swallowed key before anything drastic.
                        if (backPressCount >= 3) {
                            Toast.makeText(
                                this,
                                "Press BACK ${EXIT_BACK_PRESSES - backPressCount} more times to exit playback",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                    // Swallow during playback so a stray BACK can't navigate away
                    // (same kiosk hardening as the other nav keys below).
                    if (kioskKeyLockEnabled) return true
                }
                KeyEvent.KEYCODE_HOME,
                KeyEvent.KEYCODE_SEARCH,
                KeyEvent.KEYCODE_APP_SWITCH ->
                    // Remote-configurable (see PlayerConfig.kioskKeyLockEnabled) so a
                    // technician can get full remote control back for debugging without
                    // a rebuild. Read from a cached field, never from prefs — this runs on
                    // the main thread for every single key press.
                    if (kioskKeyLockEnabled) return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * The 5×-BACK escape hatch: fully tear the kiosk down so a technician can reach the
     * Android launcher, and make sure nothing pulls the app back to the foreground.
     *
     * Three layers keep this app in front and must ALL be undone (order matters):
     *   1. the Device-Owner HOME claim (OwnerSetup) — otherwise HOME just relaunches us
     *      and the launcher is never visible;
     *   2. WatchdogService — otherwise it resurrects playback ~90s after the heartbeat
     *      writer stops (see WatchdogService.STALE_THRESHOLD_MS);
     *   3. PlaybackForegroundService — the actual playback, wakelock and heartbeat writer.
     * The plan-fetch and update workers are cancelled too, so UpdateCheckWorker can't
     * re-claim HOME or OTA-relaunch while the device is being serviced (best-effort: an
     * install already committed to PackageInstaller still lands). The server HEARTBEAT
     * deliberately KEEPS running: it never starts services or activities, and it keeps
     * the exited device visible on the dashboard — lastSeen stays fresh while
     * playbackAliveAt goes stale, which is exactly the existing frozen-screen signal, so
     * ops can tell "exited/serviced" apart from "power cut". (An FCM plan_updated push
     * can still enqueue one plan fetch while exited; it's background-only and accepted.)
     *
     * This is deliberately temporary: reopening the app from the apps menu re-runs
     * resumeKioskGuards() and a reboot re-runs BootReceiver, both of which restore kiosk.
     */
    private fun exitKiosk() {
        // 0. Unpin first: while lock-task is active the HOME launch below would be
        //    blocked and the screen would stay pinned to this task.
        runCatching { stopLockTask() }
        // 1. Relinquish the persistent HOME claim (device-owner only; no-op otherwise).
        runCatching {
            if (OwnerSetup.isDeviceOwner(this)) {
                val dpm = getSystemService(DevicePolicyManager::class.java)
                val admin = ComponentName(this, AliveDeviceAdminReceiver::class.java)
                dpm?.clearPackagePersistentPreferredActivities(admin, packageName)
            }
        }
        // 1b. Also purge any USER "Always"-choice record pointing HOME at us — a past
        //     home-picker choice writes a preferred-activity entry that the DPM clear
        //     above does NOT touch, and it would bounce HOME straight back into the app,
        //     permanently defeating this hatch on that unit. Legal for our own package.
        runCatching { packageManager.clearPackagePreferredActivities(packageName) }
        // 2. Stop the cross-process watchdog first, so it can't restart playback after (3).
        //    requestStop, not stopService: stopping a foreground service whose
        //    startForegroundService() promise is still outstanding (process still
        //    spawning) crashes the app with RemoteServiceException ~5s later — hit
        //    for real on a HiSilicon panel when exiting shortly after a cold start.
        runCatching { WatchdogService.requestStop(this) }
        // 3. Stop playback: unbind, then stop the foreground service (its onDestroy releases
        //    the wakelock and stops the engine + the process-heartbeat writer).
        if (bound) { runCatching { unbindService(connection) }; bound = false }
        runCatching { PlaybackForegroundService.requestStop(this) }
        // 4. Cancel plan-fetch + update workers (HeartbeatScheduler stays — see kdoc).
        runCatching { PlanFetchScheduler.cancel(this) }
        runCatching { UpdateScheduler.cancel(this) }
        // 5. Drop to the real Android launcher. Target it EXPLICITLY: our own manifest
        //    HOME filter still exists, so an implicit HOME intent would show the system
        //    home-picker on the signage screen (and an "Always → ALIVE" choice there is
        //    the trap step 1b cleans up). Resolve a HOME activity in another package and
        //    launch it directly; fall back to the implicit intent only if none exists.
        runCatching {
            val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            // Flag 0, NOT MATCH_DEFAULT_ONLY: HOME apps resolve by CATEGORY_HOME and many
            // OEM TV launchers don't also declare CATEGORY_DEFAULT, so MATCH_DEFAULT_ONLY
            // silently drops them — leaving only our own PairingActivity, which sends the
            // fallback implicit intent straight into the "Select Home app" picker on the
            // signage screen (observed on HiSilicon/selenview boxes). Verified on-device.
            val launcher = packageManager
                .queryIntentActivities(homeIntent, 0)
                .firstOrNull { it.activityInfo?.packageName != packageName }
                ?.activityInfo
            startActivity(
                Intent(homeIntent).apply {
                    if (launcher != null) setClassName(launcher.packageName, launcher.name)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            )
        }
        finishAffinity()
    }

    private companion object {
        const val EXIT_BACK_PRESSES = 5
        const val EXIT_BACK_WINDOW_MS = 3_000L
    }
}
