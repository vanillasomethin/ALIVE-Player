package com.alive.player.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebView
import android.widget.ImageView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.alive.player.data.AppDatabase
import com.alive.player.data.ProofEvent
import com.alive.player.settings.DevicePrefs
import com.alive.player.settings.FetchStatus
import com.alive.player.download.AssetDownloader
import com.alive.player.schedule.PlanItem
import com.alive.player.worker.PopUploadWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.alive.player.settings.NtpSyncManager
import com.bumptech.glide.Glide
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.TimeUnit

class PlaybackEngine(private val context: Context) {

    private var currentItem: PlanItem? = null
    private var playStartMs: Long = 0L
    private var currentTransition: String = "NONE" // "NONE" | "FADE" | "SLIDE" -- set per playlist in admin

    private val mainHandler = Handler(Looper.getMainLooper())
    private var advanceRunnable: Runnable? = null
    private var retryRunnable: Runnable? = null

    private var exoPlayer: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var imageView: ImageView? = null
    private var webView: WebView? = null

    private var pendingItem: PlanItem? = null

    private var currentWindowIndex: Int = 0
    private var currentItemIndex: Int = 0

    /** Called when the engine has no plan. Receives a human-readable status line. */
    var onWaiting: ((String) -> Unit)? = null

    /** Called when the engine begins rendering content. */
    var onPlaying: (() -> Unit)? = null

    fun attachViews(playerView: PlayerView, imageView: ImageView, webView: WebView) {
        this.playerView = playerView
        this.imageView = imageView
        this.webView = webView

        if (exoPlayer == null) {
            exoPlayer = ExoPlayer.Builder(context).build()
        }
        playerView.player = exoPlayer

        pendingItem?.let {
            pendingItem = null
            renderItem(it)
        }
    }

    fun detachViews() {
        playerView?.player = null
        playerView = null
        imageView = null
        webView = null
    }

    fun startLoop() {
        retryRunnable?.let { mainHandler.removeCallbacks(it) }
        countdownRunnable?.let { mainHandler.removeCallbacks(it) }
        CoroutineScope(Dispatchers.IO).launch {
            val plan = PlanLoader.load(context)
            val fetchStatus = DevicePrefs(context).getFetchStatus()
            mainHandler.post {
                if (plan == null || (plan.windows.isEmpty() && plan.fallbackItems.isEmpty())) {
                    val statusLine = buildStatusLine(fetchStatus)
                    onWaiting?.invoke(statusLine)
                    startRetryCountdown()
                    return@post
                }
                onPlaying?.invoke()
                currentItemIndex = 0
                advance(plan)
            }
        }
    }

    private var countdownRunnable: Runnable? = null
    private var retrySecondsLeft = (DevicePrefs(context).getRetryIntervalMs() / 1000).toInt()

    private fun startRetryCountdown() {
        val retryIntervalMs = DevicePrefs(context).getRetryIntervalMs()
        retrySecondsLeft = (retryIntervalMs / 1000).toInt()
        tickCountdown()
        val retry = Runnable { startLoop() }
        retryRunnable = retry
        mainHandler.postDelayed(retry, retryIntervalMs)
    }

    private fun tickCountdown() {
        countdownRunnable?.let { mainHandler.removeCallbacks(it) }
        if (retrySecondsLeft > 0) {
            val fetchStatus = DevicePrefs(context).getFetchStatus()
            onWaiting?.invoke(buildStatusLine(fetchStatus, retrySecondsLeft))
            retrySecondsLeft--
            val tick = Runnable { tickCountdown() }
            countdownRunnable = tick
            mainHandler.postDelayed(tick, 1_000)
        }
    }

    private fun buildStatusLine(status: FetchStatus?, secondsLeft: Int? = null): String {
        val countdown = if (secondsLeft != null && secondsLeft > 0) " · retry in ${secondsLeft}s" else ""
        return when (status?.name) {
            FetchStatus.FETCHING.name    -> "Fetching schedule…"
            FetchStatus.NO_SCHEDULE.name -> "No schedule assigned\n${status.message}$countdown"
            FetchStatus.ERROR.name       -> "Fetch error: ${status.message}$countdown"
            FetchStatus.OK.name          -> "Schedule loaded — waiting for content$countdown"
            FetchStatus.NO_CONTENT.name  -> "${status.message}$countdown"
            null                         -> "Waiting for schedule…$countdown"
            else                         -> "Waiting…$countdown"
        }
    }

    private fun advance(plan: com.alive.player.schedule.Plan) {
        currentTransition = plan.transition
        val now = System.currentTimeMillis()
        val activeWindow = plan.windows.firstOrNull { it.startEpochMs <= now && now < it.endEpochMs }

        val itemList = if (activeWindow != null && activeWindow.items.isNotEmpty()) {
            activeWindow.items
        } else {
            plan.fallbackItems
        }

        if (itemList.isEmpty()) {
            onWaiting?.invoke("No content for current time slot")
            startRetryCountdown()
            return
        }

        val item = itemList[currentItemIndex % itemList.size]
        currentItemIndex = (currentItemIndex + 1) % itemList.size

        renderItem(item)
    }

    private fun renderItem(item: PlanItem) {
        val pv = playerView
        val iv = imageView
        val wv = webView

        playItem(item)

        if (pv == null || iv == null || wv == null) {
            pendingItem = item
            scheduleAdvanceTimer(item)
            return
        }

        val localFile = item.sha256?.let { sha256 ->
            item.ext?.let { ext ->
                AssetDownloader.getCachedFile(context, item.contentVersionId, "current", sha256, ext)
            }
        }
        val resolvedUri = if (localFile != null) android.net.Uri.fromFile(localFile) else android.net.Uri.parse(item.uri)

        val newView: View = when (item.type) {
            "video" -> pv
            "image" -> iv
            "web"   -> wv
            else    -> return
        }
        val oldView = listOf(pv, iv, wv).firstOrNull { it !== newView && it.visibility == android.view.View.VISIBLE }

        when (item.type) {
            "video" -> {
                val player = exoPlayer ?: return
                cancelAdvanceTimer()
                player.clearMediaItems()
                player.removeListener(videoEndListener)
                player.addListener(videoEndListener)
                val mimeType = when (item.ext?.lowercase()) {
                    "mp4"  -> MimeTypes.VIDEO_MP4
                    "webm" -> MimeTypes.VIDEO_WEBM
                    "mkv"  -> MimeTypes.VIDEO_MATROSKA
                    "mov"  -> "video/quicktime"
                    else   -> null
                }
                val mediaItem = if (mimeType != null) {
                    MediaItem.Builder().setUri(resolvedUri).setMimeType(mimeType).build()
                } else {
                    MediaItem.fromUri(resolvedUri)
                }
                player.setMediaItem(mediaItem)
                player.prepare()
                player.playWhenReady = true
                startStallWatchdog(item)
                scheduleAdvanceTimer(item)
            }
            "image" -> {
                Glide.with(context).clear(iv)
                Glide.with(context).load(resolvedUri).fitCenter().into(iv)
                scheduleAdvanceTimer(item)
            }
            "web" -> {
                wv.settings.javaScriptEnabled = false
                wv.loadUrl(item.uri)
                scheduleAdvanceTimer(item)
            }
        }

        transitionViews(oldView, newView)
    }

    /**
     * Cross-fades or slides from the previously-visible renderer to the new one, per the
     * playlist's transition setting. Same-view switches (e.g. video -> video) skip the
     * transition entirely -- ExoPlayer just swaps the media item in place.
     */
    private fun transitionViews(oldView: View?, newView: View) {
        if (oldView === newView) {
            newView.visibility = android.view.View.VISIBLE
            newView.alpha = 1f
            newView.translationX = 0f
            return
        }
        val durationMs = DevicePrefs(context).getTransitionDurationMs()
        when (currentTransition) {
            "FADE" -> {
                newView.alpha = 0f
                newView.translationX = 0f
                newView.visibility = android.view.View.VISIBLE
                newView.animate().alpha(1f).setDuration(durationMs).start()
                oldView?.animate()?.alpha(0f)?.setDuration(durationMs)?.withEndAction {
                    oldView.visibility = android.view.View.GONE
                    oldView.alpha = 1f
                }?.start()
            }
            "SLIDE" -> {
                val w = (newView.width.takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels).toFloat()
                newView.alpha = 1f
                newView.translationX = w
                newView.visibility = android.view.View.VISIBLE
                newView.animate().translationX(0f).setDuration(durationMs).start()
                oldView?.animate()?.translationX(-w)?.setDuration(durationMs)?.withEndAction {
                    oldView.visibility = android.view.View.GONE
                    oldView.translationX = 0f
                }?.start()
            }
            else -> {
                oldView?.visibility = android.view.View.GONE
                newView.alpha = 1f
                newView.translationX = 0f
                newView.visibility = android.view.View.VISIBLE
            }
        }
    }

    private val videoEndListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                cancelStallWatchdog()
                cancelAdvanceTimer()
                reloadAndAdvance()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            android.util.Log.e("PlaybackEngine", "Video error ${error.errorCode}: ${error.message}")
            cancelStallWatchdog()
            // A decode/source error on a cached file usually means the file is bad, not the
            // media — drop it so the next plan fetch re-downloads with a fresh hash check.
            currentItem?.let { evictCachedCopy(it) }
            cancelAdvanceTimer()
            // Brief delay before advancing so we don't spin instantly on a broken playlist
            mainHandler.postDelayed({ reloadAndAdvance() }, 2_000)
        }
    }

    // ── Decoder stall watchdog ───────────────────────────────────────────────────
    // A truncated video file doesn't raise onPlayerError — the decoder simply stops
    // advancing, so the screen freezes on one frame forever and no listener fires.
    // Poll currentPosition while a video is supposed to be playing; if it hasn't moved
    // for STALL_TIMEOUT_MS, treat it as a corrupt cache entry: evict, report, move on.
    private var stallRunnable: Runnable? = null
    private var lastPositionMs = -1L
    private var positionStuckSinceMs = 0L

    /** Set when a stall is detected, so diagnostics/telemetry can surface it. */
    var lastStallReason: String? = null
        private set

    private fun startStallWatchdog(item: PlanItem) {
        cancelStallWatchdog()
        lastPositionMs = -1L
        positionStuckSinceMs = 0L
        val tick = object : Runnable {
            override fun run() {
                val player = exoPlayer
                if (player == null || !player.isPlaying) {
                    // Buffering/paused is not a stall — reset the clock and keep watching.
                    positionStuckSinceMs = 0L
                    lastPositionMs = -1L
                    mainHandler.postDelayed(this, STALL_POLL_MS)
                    return
                }
                val pos = player.currentPosition
                val now = System.currentTimeMillis()
                if (pos != lastPositionMs) {
                    lastPositionMs = pos
                    positionStuckSinceMs = now
                } else if (positionStuckSinceMs > 0 && now - positionStuckSinceMs >= STALL_TIMEOUT_MS) {
                    val reason = "video stalled at ${pos}ms for ${(now - positionStuckSinceMs) / 1000}s: ${item.contentVersionId}"
                    android.util.Log.e("PlaybackEngine", reason)
                    lastStallReason = reason
                    DevicePrefs(context).setLastStall(reason, now)
                    cancelStallWatchdog()
                    cancelAdvanceTimer()
                    evictCachedCopy(item)
                    reloadAndAdvance()
                    return
                } else if (positionStuckSinceMs == 0L) {
                    positionStuckSinceMs = now
                }
                mainHandler.postDelayed(this, STALL_POLL_MS)
            }
        }
        stallRunnable = tick
        mainHandler.postDelayed(tick, STALL_POLL_MS)
    }

    private fun cancelStallWatchdog() {
        stallRunnable?.let { mainHandler.removeCallbacks(it) }
        stallRunnable = null
    }

    private fun evictCachedCopy(item: PlanItem) {
        val sha = item.sha256 ?: return
        val ext = item.ext ?: return
        AssetDownloader.evictCorrupt(context, item.contentVersionId, "current", sha, ext)
    }

    private companion object {
        const val STALL_POLL_MS    = 2_000L
        const val STALL_TIMEOUT_MS = 10_000L
    }

    private fun reloadAndAdvance() {
        CoroutineScope(Dispatchers.IO).launch {
            val plan = PlanLoader.load(context)
            mainHandler.post {
                if (plan != null) advance(plan)
            }
        }
    }

    private fun scheduleAdvanceTimer(item: PlanItem) {
        cancelAdvanceTimer()
        val runnable = Runnable { reloadAndAdvance() }
        advanceRunnable = runnable
        mainHandler.postDelayed(runnable, item.durationMs)
    }

    private fun cancelAdvanceTimer() {
        advanceRunnable?.let { mainHandler.removeCallbacks(it) }
        advanceRunnable = null
    }

    private fun playItem(item: PlanItem) {
        // Liveness heartbeat: advancing to a new item proves the playback loop and UI
        // thread are still running, which a frozen screen cannot fake.
        DevicePrefs(context).markPlaybackAlive()
        val now = NtpSyncManager.now(context)
        currentItem?.let { emitCompleteEvent(it, now) }
        currentItem = item
        playStartMs = now
        // No PLAY_START event — only emit on completion
    }

    fun getCurrentPositionMs(): Long? =
        if (exoPlayer?.isPlaying == true) exoPlayer?.currentPosition else null

    fun restartCurrentItem() {
        val item = currentItem ?: return
        val pv = playerView ?: return
        val iv = imageView ?: return
        val wv = webView ?: return

        val localFile = item.sha256?.let { sha256 ->
            item.ext?.let { ext ->
                AssetDownloader.getCachedFile(context, item.contentVersionId, "current", sha256, ext)
            }
        }
        val resolvedUri = if (localFile != null) android.net.Uri.fromFile(localFile) else android.net.Uri.parse(item.uri)

        when (item.type) {
            "video" -> {
                pv.visibility = android.view.View.VISIBLE
                iv.visibility = android.view.View.GONE
                wv.visibility = android.view.View.GONE

                val player = exoPlayer ?: return
                cancelAdvanceTimer()
                player.clearMediaItems()
                player.removeListener(videoEndListener)
                player.addListener(videoEndListener)
                val mimeType = when (item.ext?.lowercase()) {
                    "mp4"  -> MimeTypes.VIDEO_MP4
                    "webm" -> MimeTypes.VIDEO_WEBM
                    "mkv"  -> MimeTypes.VIDEO_MATROSKA
                    "mov"  -> "video/quicktime"
                    else   -> null
                }
                val mediaItem = if (mimeType != null) {
                    MediaItem.Builder().setUri(resolvedUri).setMimeType(mimeType).build()
                } else {
                    MediaItem.fromUri(resolvedUri)
                }
                player.setMediaItem(mediaItem)
                player.prepare()
                player.playWhenReady = true
                startStallWatchdog(item)
                scheduleAdvanceTimer(item)
            }
            "image" -> {
                pv.visibility = android.view.View.GONE
                iv.visibility = android.view.View.VISIBLE
                wv.visibility = android.view.View.GONE

                Glide.with(context).clear(iv)
                Glide.with(context).load(resolvedUri).fitCenter().into(iv)
                scheduleAdvanceTimer(item)
            }
            "web" -> {
                pv.visibility = android.view.View.GONE
                iv.visibility = android.view.View.GONE
                wv.visibility = android.view.View.VISIBLE

                wv.settings.javaScriptEnabled = false
                wv.loadUrl(item.uri)
                scheduleAdvanceTimer(item)
            }
        }
    }

    fun stop() {
        cancelAdvanceTimer()
        retryRunnable?.let { mainHandler.removeCallbacks(it) }
        retryRunnable = null
        countdownRunnable?.let { mainHandler.removeCallbacks(it) }
        countdownRunnable = null
        val now = NtpSyncManager.now(context)
        currentItem?.let { emitCompleteEvent(it, now) }
        currentItem = null
        // Capture and null-out before posting to avoid double-release if stop() is called twice
        val playerToRelease = exoPlayer
        exoPlayer = null
        if (playerToRelease != null) {
            mainHandler.post { playerToRelease.release() }
        }
    }

    private fun emitCompleteEvent(item: PlanItem, endMs: Long) {
        val duration = (endMs - playStartMs).coerceAtLeast(0)
        if (duration < 500) return  // skip very short plays (< 0.5s)
        emitEvent(
            ProofEvent(
                eventId = UUID.randomUUID().toString(),
                mediaId = item.contentVersionId,
                scheduleId = item.scheduleId,
                startedAtEpochMs = playStartMs,
                endedAtEpochMs = endMs,
                durationMs = duration,
            )
        )
    }

    private fun emitEvent(event: ProofEvent) {
        CoroutineScope(Dispatchers.IO).launch {
            AppDatabase.get(context).proofEventDao().insert(event)
            scheduleUpload()
        }
    }

    private fun scheduleUpload() {
        val request = OneTimeWorkRequestBuilder<PopUploadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "pop_upload",
            ExistingWorkPolicy.KEEP,
            request,
        )
    }
}
