package com.partner.alive.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.widget.ImageView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.partner.alive.data.AppDatabase
import com.partner.alive.data.ProofEvent
import com.partner.alive.settings.DevicePrefs
import com.partner.alive.settings.FetchStatus
import com.partner.alive.download.AssetDownloader
import com.partner.alive.schedule.PlanItem
import com.partner.alive.worker.PopUploadWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.bumptech.glide.Glide
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.TimeUnit

class PlaybackEngine(private val context: Context) {

    private var currentItem: PlanItem? = null
    private var playStartMs: Long = 0L

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
    private var retrySecondsLeft = (RETRY_INTERVAL_MS / 1000).toInt()

    private fun startRetryCountdown() {
        retrySecondsLeft = (RETRY_INTERVAL_MS / 1000).toInt()
        tickCountdown()
        val retry = Runnable { startLoop() }
        retryRunnable = retry
        mainHandler.postDelayed(retry, RETRY_INTERVAL_MS)
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

    companion object {
        private const val RETRY_INTERVAL_MS = 30_000L
    }

    private fun advance(plan: com.partner.alive.schedule.Plan) {
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
                scheduleAdvanceTimer(item)
            }
            "image" -> {
                pv.visibility = android.view.View.GONE
                iv.visibility = android.view.View.VISIBLE
                wv.visibility = android.view.View.GONE

                Glide.with(context).load(resolvedUri).centerCrop().into(iv)
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

    private val videoEndListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                cancelAdvanceTimer()
                reloadAndAdvance()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            android.util.Log.e("PlaybackEngine", "Video error ${error.errorCode}: ${error.message}")
            cancelAdvanceTimer()
            // Brief delay before advancing so we don't spin instantly on a broken playlist
            mainHandler.postDelayed({ reloadAndAdvance() }, 2_000)
        }
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
        val now = System.currentTimeMillis()
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
                scheduleAdvanceTimer(item)
            }
            "image" -> {
                pv.visibility = android.view.View.GONE
                iv.visibility = android.view.View.VISIBLE
                wv.visibility = android.view.View.GONE

                Glide.with(context).load(resolvedUri).centerCrop().into(iv)
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
        val now = System.currentTimeMillis()
        currentItem?.let { emitCompleteEvent(it, now) }
        currentItem = null
        mainHandler.post {
            exoPlayer?.release()
            exoPlayer = null
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
