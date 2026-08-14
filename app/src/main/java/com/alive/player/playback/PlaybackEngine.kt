package com.alive.player.playback

import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebView
import android.widget.ImageView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
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
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
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

    // Double-buffered video: two PlayerViews and up to two ExoPlayers. The *active*
    // pair (exoPlayer on playerView) shows the current item; while a video plays, the
    // NEXT video is prepared on the other view (nextPlayer on the inactive view) so the
    // swap at the boundary is instant instead of leaving the finished clip's last frame
    // frozen for the ~0.5-1s a fresh prepare()+first-frame takes on these boxes. If the
    // preload is unavailable (never scheduled, not ready, or the panel can't run two
    // decoders at once) the code falls back to the original build-fresh-on-advance path,
    // so behaviour is never worse than before — only the freeze is removed when possible.
    private var exoPlayer: ExoPlayer? = null       // active player
    private var playerView: PlayerView? = null     // active view (pvA or pvB)
    private var pvA: PlayerView? = null
    private var pvB: PlayerView? = null
    private var imageView: ImageView? = null
    private var webView: WebView? = null

    // Preloaded next video (prepared, paused, first frame decoded on the inactive view).
    private var nextPlayer: ExoPlayer? = null
    private var nextPlayerView: PlayerView? = null
    private var preloadedItem: PlanItem? = null
    private var preloadReady = false
    private var preloadListener: Player.Listener? = null
    private var preloadRunnable: Runnable? = null

    // Last plan handed to advance(), so preload can peek the upcoming item without a
    // fresh disk read. Only an optimization: if it's stale the swap guard fails and we
    // build fresh, which is correct, just not gapless.
    private var lastPlan: com.alive.player.schedule.Plan? = null

    private var pendingItem: PlanItem? = null

    // The view to hide once the incoming video's first frame actually renders (set right
    // before ExoPlayer.prepare(), consumed by videoEndListener.onRenderedFirstFrame()).
    // playerView itself is never gated this way -- see transitionViews() -- so hiding
    // whatever was on top of it any earlier would expose a blank/frozen playerView for
    // however long prepare()/decode takes, instead of the real first frame.
    private var pendingOldView: View? = null

    private var currentWindowIndex: Int = 0
    private var currentItemIndex: Int = 0

    /** Called when the engine has no plan. Receives a human-readable status line. */
    var onWaiting: ((String) -> Unit)? = null

    /** Called when the engine begins rendering content. */
    var onPlaying: (() -> Unit)? = null

    /**
     * True while the engine is idle waiting for content (no plan, or no items for the
     * current slot); false while it is rendering. This is the state pollers must consult
     * before kicking startLoop(): onWaiting/onPlaying are single vars on this shared
     * engine, so only the newest-bound activity's views track reality — an older,
     * backgrounded activity judging by its own frozen waiting overlay called startLoop()
     * every 5s forever, pinning the whole fleet's screens to the start of item 0.
     * Main-thread only, like the rest of the engine's mutable state.
     */
    var isWaitingForContent = false
        private set

    // Some vendor hardware AVC decoders on budget Android TV boxes are unreliable with
    // modern Media3 regardless of source encoding:
    //  - Hisilicon (OMX.hisi.video.decoder.avc): accepts input but never drains output
    //    buffers -- stuck holding 6-9/9 output buffers, repeated flush()/start(), no
    //    ExoPlayer error, permanently blank screen. Confirmed independent of encoding
    //    (profile/level/bitrate/CFR all within spec) and PlayerView surface type.
    //  - Realtek (OMX.realtek.video.decoder): fails at decoder init with
    //    "setPortMode on output to DynamicANWBuffer failed", even for a Main/Level 4.1
    //    720p source that ExoPlayer's own capability check approves. Reproduced in a
    //    completely unrelated third-party app on the same unit, and survives a full
    //    device reboot -- not a stuck/transient state, a real incompatibility with
    //    Media3's dynamic output-buffer request on this vendor's OMX build.
    // Excluding both by exact component name (not a vendor-substring match) forces
    // MediaCodecSelector.DEFAULT's next candidate for AVC content -- trading CPU/power
    // efficiency for actually rendering frames -- while leaving these vendors' OTHER
    // hardware decoders (e.g. OMX.hisi.video.decoder.hevc, confirmed working) available.
    // A blanket "hisi"/"realtek" substring match previously excluded those too, forcing
    // software decode even for codecs that never had a confirmed hardware bug. The
    // exclusion set lives in DecoderCapabilities since PlanFetchWorker/PlanModels also
    // need it to decide whether to prefer an HEVC content rendition on these devices.
    private val safeMediaCodecSelector = MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
        val infos = MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
        infos.filterNot { it.name in DecoderCapabilities.BROKEN_HARDWARE_DECODER_NAMES }
    }

    fun attachViews(playerViewA: PlayerView, playerViewB: PlayerView, imageView: ImageView, webView: WebView) {
        this.pvA = playerViewA
        this.pvB = playerViewB
        this.playerView = playerViewA
        this.imageView = imageView
        this.webView = webView

        if (exoPlayer == null) {
            exoPlayer = buildPlayer()
        }
        playerViewA.player = exoPlayer
        // Both surfaces stay VISIBLE and fully opaque forever (the TextureView-never-hidden
        // rule). Which one the viewer sees is decided by z-order alone — transitionViews
        // brings the active surface to the front; the other keeps decoding underneath,
        // covered. Alpha is left at 1 so a preloaded clip's first frame reliably renders
        // into the covered surface (an alpha-0 TextureView's render behaviour is exactly
        // the kind of thing that's silently broken on these panels — don't rely on it).
        playerViewA.alpha = 1f
        playerViewB.alpha = 1f
        playerViewB.player = null

        pendingItem?.let {
            pendingItem = null
            renderItem(it)
        }
    }

    fun detachViews() {
        pvA?.player = null
        pvB?.player = null
        playerView = null
        pvA = null
        pvB = null
        imageView = null
        webView = null
    }

    private fun buildPlayer(): ExoPlayer =
        ExoPlayer.Builder(context, DefaultRenderersFactory(context).setMediaCodecSelector(safeMediaCodecSelector)).build()

    /** The player view that is NOT currently active — the preload/next-video target.
     *  When double-buffering is disabled (e.g. single-decoder panels), this returns the
     *  ACTIVE view so renderItem builds on it in place — the original single-surface
     *  behaviour, with no second decoder and no preload path ever engaged. */
    private fun inactivePlayerView(): PlayerView? {
        if (!com.alive.player.BuildConfig.DOUBLE_BUFFER_VIDEO) return playerView
        val a = pvA ?: return null
        val b = pvB ?: return a
        return if (playerView === a) b else a
    }

    private fun mediaItemFor(item: PlanItem, uri: android.net.Uri): MediaItem {
        val mimeType = when (item.ext?.lowercase()) {
            "mp4"  -> MimeTypes.VIDEO_MP4
            "webm" -> MimeTypes.VIDEO_WEBM
            "mkv"  -> MimeTypes.VIDEO_MATROSKA
            "mov"  -> "video/quicktime"
            else   -> null
        }
        return if (mimeType != null) {
            MediaItem.Builder().setUri(uri).setMimeType(mimeType).build()
        } else {
            MediaItem.fromUri(uri)
        }
    }

    private fun resolvedUriFor(item: PlanItem): android.net.Uri {
        val localFile = item.sha256?.let { sha256 ->
            item.ext?.let { ext ->
                AssetDownloader.getCachedFile(context, item.contentVersionId, "current", sha256, ext)
            }
        }
        return if (localFile != null) android.net.Uri.fromFile(localFile) else android.net.Uri.parse(item.uri)
    }

    fun startLoop() {
        retryRunnable?.let { mainHandler.removeCallbacks(it) }
        countdownRunnable?.let { mainHandler.removeCallbacks(it) }
        CoroutineScope(Dispatchers.IO).launch {
            val plan = PlanLoader.load(context)
            val fetchStatus = DevicePrefs(context).getFetchStatus()
            mainHandler.post {
                if (plan == null || (plan.windows.isEmpty() && plan.fallbackItems.isEmpty())) {
                    isWaitingForContent = true
                    val statusLine = buildStatusLine(fetchStatus)
                    onWaiting?.invoke(statusLine)
                    startRetryCountdown()
                    return@post
                }
                isWaitingForContent = false
                onPlaying?.invoke()
                // Deliberately NO index reset: startLoop is called for plan changes,
                // download-complete kicks and retry ticks, and resetting snapped playback
                // back to item 0 every time (with a stale caller, every 5 seconds — the
                // fleet-wide "first 5s of item 0 on repeat" outage). advance() takes the
                // index modulo the item list, so a leftover index is valid against any
                // plan and playback resumes from where the loop actually was.
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
        lastPlan = plan
        val now = System.currentTimeMillis()
        val activeWindow = plan.windows.firstOrNull { it.startEpochMs <= now && now < it.endEpochMs }

        val itemList = if (activeWindow != null && activeWindow.items.isNotEmpty()) {
            activeWindow.items
        } else {
            plan.fallbackItems
        }

        if (itemList.isEmpty()) {
            isWaitingForContent = true
            onWaiting?.invoke("No content for current time slot")
            startRetryCountdown()
            return
        }

        isWaitingForContent = false
        val item = itemList[currentItemIndex % itemList.size]
        currentItemIndex = (currentItemIndex + 1) % itemList.size

        renderItem(item)
    }

    private fun renderItem(item: PlanItem) {
        val iv = imageView
        val wv = webView
        val activeView = playerView

        playItem(item)

        if (activeView == null || iv == null || wv == null || pvA == null || pvB == null) {
            pendingItem = item
            scheduleAdvanceTimer(item)
            return
        }

        val resolvedUri = resolvedUriFor(item)
        // Whatever the viewer currently sees — hidden once the new item is on screen.
        val previouslyVisible: View? = when {
            iv.visibility == android.view.View.VISIBLE -> iv
            wv.visibility == android.view.View.VISIBLE -> wv
            else -> activeView
        }

        when (item.type) {
            "video" -> {
                cancelAdvanceTimer()
                cancelPreloadRunnable()

                // Fast path: this exact clip was preloaded on the inactive surface and its
                // first frame is already decoded — promote it. The boundary is gapless.
                if (preloadReady && preloadedItem === item && nextPlayer != null && nextPlayerView != null) {
                    swapInPreloaded(item, previouslyVisible)
                    return
                }

                // Fresh path (no usable preload): build a fresh player on the INACTIVE
                // surface. The outgoing clip's last frame keeps showing on the still-active
                // surface (keep_content_on_player_reset) until the new clip's first frame
                // renders, then transitionViews swaps them — never worse than the old
                // single-surface path, which is what this falls back to. A fresh instance
                // per item is deliberate: on Hisilicon/Realtek a wedged codec pipeline never
                // recovers on the same instance, so each item gets a fresh MediaCodec.
                releasePreload()
                cancelStallWatchdog()
                val target = inactivePlayerView() ?: activeView
                val outgoing = exoPlayer
                outgoing?.removeListener(videoEndListener)
                val player = buildPlayer()
                exoPlayer = player
                playerView = target
                target.player = player
                player.addListener(videoEndListener)
                pendingOldView = previouslyVisible
                player.setMediaItem(mediaItemFor(item, resolvedUri))
                player.prepare()
                player.playWhenReady = true
                startStallWatchdog(item)
                // Release the outgoing player after the swap is wired up; its last frame is
                // held by keep_content_on_player_reset until the new first frame covers it.
                outgoing?.let { old -> mainHandler.post { old.release() } }
                // Slot timer + next-clip preload are armed in onRenderedFirstFrame.
            }
            "image", "web" -> {
                // Advancing to a non-video: fully tear the video pipeline down so a stale
                // clip can't fire STATE_ENDED in the background and cut this item short.
                cancelPreloadRunnable()
                releasePreload()
                cancelStallWatchdog()
                exoPlayer?.let { old ->
                    old.removeListener(videoEndListener)
                    mainHandler.post { old.release() }
                }
                exoPlayer = null
                if (item.type == "image") {
                    showImage(iv, resolvedUri, item)
                    transitionViews(previouslyVisible, iv)
                } else {
                    wv.settings.javaScriptEnabled = false
                    wv.loadUrl(item.uri)
                    scheduleAdvanceTimer(item)
                    transitionViews(previouslyVisible, wv)
                }
            }
        }
    }

    /** Promote the already-prepared, first-frame-ready preloaded clip to active. */
    private fun swapInPreloaded(item: PlanItem, previouslyVisible: View?) {
        val player = nextPlayer ?: return
        val view = nextPlayerView ?: return
        preloadListener?.let { player.removeListener(it) }
        preloadListener = null
        nextPlayer = null
        nextPlayerView = null
        preloadedItem = null
        preloadReady = false

        val outgoing = exoPlayer
        outgoing?.removeListener(videoEndListener)

        exoPlayer = player
        playerView = view
        player.addListener(videoEndListener)
        player.playWhenReady = true   // was paused during preload; run it from frame 0

        cancelStallWatchdog()
        startStallWatchdog(item)

        // The incoming first frame is already on `view`; reveal it now and hide the old.
        transitionViews(previouslyVisible, view)
        outgoing?.let { old -> mainHandler.post { old.release() } }

        // First frame already up, so arm the slot timer immediately (no first-frame wait),
        // and line up the clip after this one.
        scheduleAdvanceTimer(item, item.durationMs + VIDEO_END_GRACE_MS)
        schedulePreloadForNext()
    }

    // ── Preload of the next clip ─────────────────────────────────────────────────
    // While a video plays, its successor (if also a video) is prepared on the inactive
    // surface a little before the current slot ends, so the swap at the boundary shows a
    // live first frame instead of the finished clip frozen for a fresh prepare()'s worth
    // of time. Preloading needs two decoders briefly; if the panel can't (init error on
    // the second codec) the preload listener discards it and advance() builds fresh.

    private fun schedulePreloadForNext() {
        cancelPreloadRunnable()
        if (!com.alive.player.BuildConfig.DOUBLE_BUFFER_VIDEO) return   // inert on single-decoder panels
        val current = currentItem ?: return
        val next = peekNextItem() ?: return
        if (next.type != "video") return   // images/web decode fast; no preload needed
        if (inactivePlayerView() == null) return
        // Fire PRELOAD_LEAD_MS before the current slot's timer, clamped so short slots
        // still preload (just with more decoder overlap).
        val lead = (current.durationMs + VIDEO_END_GRACE_MS - PRELOAD_LEAD_MS).coerceAtLeast(0L)
        val r = Runnable { preloadNext(next) }
        preloadRunnable = r
        mainHandler.postDelayed(r, lead)
    }

    /** The item advance() will select next — same math, without mutating the index. */
    private fun peekNextItem(): PlanItem? {
        val plan = lastPlan ?: return null
        val now = System.currentTimeMillis()
        val win = plan.windows.firstOrNull { it.startEpochMs <= now && now < it.endEpochMs }
        val list = if (win != null && win.items.isNotEmpty()) win.items else plan.fallbackItems
        if (list.isEmpty()) return null
        return list[currentItemIndex % list.size]   // currentItemIndex already points at next
    }

    private fun preloadNext(item: PlanItem) {
        val target = inactivePlayerView() ?: return
        if (preloadedItem === item && nextPlayer != null) return
        releasePreload()
        val player = buildPlayer()
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() { preloadReady = true }
            override fun onPlayerError(error: PlaybackException) {
                android.util.Log.w("PlaybackEngine", "preload failed (${error.errorCode}); will build fresh at boundary: ${error.message}")
                releasePreload()
            }
        }
        target.player = player
        player.addListener(listener)
        preloadListener = listener
        player.setMediaItem(mediaItemFor(item, resolvedUriFor(item)))
        player.prepare()
        player.playWhenReady = false   // decode the first frame, but don't run the clip
        nextPlayer = player
        nextPlayerView = target
        preloadedItem = item
        preloadReady = false
    }

    private fun cancelPreloadRunnable() {
        preloadRunnable?.let { mainHandler.removeCallbacks(it) }
        preloadRunnable = null
    }

    /** Tear down a preloaded (or preloading) player and clear its state. */
    private fun releasePreload() {
        cancelPreloadRunnable()
        val p = nextPlayer
        val l = preloadListener
        nextPlayer = null
        nextPlayerView = null
        preloadedItem = null
        preloadReady = false
        preloadListener = null
        if (p != null) {
            l?.let { p.removeListener(it) }
            mainHandler.post { p.release() }
        }
    }

    private fun isPlayerView(v: View?): Boolean = v != null && (v === pvA || v === pvB)

    /**
     * Reveals [newView] as the active renderer and hides [oldView], per the playlist's
     * transition setting.
     *
     * The active renderer is brought to the front rather than hiding the others, because
     * a PlayerView must never actually go GONE/INVISIBLE: on this fleet's Hisilicon/Realtek
     * TextureView implementation ExoPlayer silently never renders into a non-VISIBLE view
     * (no error, onRenderedFirstFrame just never fires), which permanently breaks video.
     * That's also exactly why preload works — a covered-but-VISIBLE player surface still
     * decodes its first frame. So player views are only ever *covered*, never hidden;
     * image/web (ordinary views) are GONE'd once covered so a player surface can show
     * through when it's next active.
     */
    private fun transitionViews(oldView: View?, newView: View) {
        newView.bringToFront()
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
                    hideCoveredView(oldView)
                }?.start()
            }
            "SLIDE" -> {
                val w = (newView.width.takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels).toFloat()
                newView.alpha = 1f
                newView.translationX = w
                newView.visibility = android.view.View.VISIBLE
                newView.animate().translationX(0f).setDuration(durationMs).start()
                oldView?.animate()?.translationX(-w)?.setDuration(durationMs)?.withEndAction {
                    hideCoveredView(oldView)
                    oldView.translationX = 0f
                }?.start()
            }
            else -> {
                newView.alpha = 1f
                newView.translationX = 0f
                newView.visibility = android.view.View.VISIBLE
                hideCoveredView(oldView)
            }
        }
    }

    /** A player view is left VISIBLE (just covered, per the TextureView rule); image/web
     *  are GONE'd so the player surface below can show through when next active. */
    private fun hideCoveredView(v: View?) {
        v ?: return
        if (isPlayerView(v)) {
            v.alpha = 1f
        } else {
            v.visibility = android.view.View.GONE
            v.alpha = 1f
        }
    }

    private val videoEndListener = object : Player.Listener {
        override fun onRenderedFirstFrame() {
            val old = pendingOldView
            pendingOldView = null
            val new = playerView ?: return
            transitionViews(old, new)
            // Arm the slot timer only now — a video's frames don't appear until the first
            // one renders, ~0.5-1s after prepare() on these boxes. Timed from prepare() (as
            // it used to be) the timer fired that much before the clip actually finished,
            // skipping the last ~1s of every video whose slot length matched its own length.
            // The + grace lets STATE_ENDED (natural end) win for full-length clips, so the
            // whole video plays; the timer only caps a video deliberately trimmed to a
            // shorter slot. Guarded on type because this listener is only attached for video.
            currentItem?.let { if (it.type == "video") scheduleAdvanceTimer(it, it.durationMs + VIDEO_END_GRACE_MS) }
            // Now that this clip is up, line up the next one on the inactive surface so the
            // coming boundary is gapless (no-op unless the next item is a video).
            schedulePreloadForNext()
        }

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
            pendingOldView = null
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
        // Safety margin for showImage()'s fallback timer when Glide never calls back.
        const val IMAGE_LOAD_GRACE_MS = 15_000L
        // Slack added to a video's slot timer so the clip's own STATE_ENDED (natural end)
        // wins for full-length videos — the timer then only enforces a deliberate trim.
        const val VIDEO_END_GRACE_MS = 750L
        // How long before a clip's slot ends to start preparing the next clip on the other
        // surface. Long enough to cover prepare()+first-frame decode (~0.5-1s here) with
        // margin; short enough to minimise the window where two video decoders run at once
        // (a real limit on budget SoCs — if the 2nd decoder fails to init the preload is
        // discarded and the boundary just builds fresh, i.e. the old behaviour).
        const val PRELOAD_LEAD_MS = 1_500L
        // How far a creative's aspect ratio may differ from the panel's before we stop
        // filling and letterbox it instead — i.e. how much of a creative we're willing to
        // crop away in exchange for edge-to-edge coverage. 1.35 ≈ "lose at most a quarter
        // of one dimension", calibrated against real fleet creatives on a 9:16 slot:
        // a 3648x5472 (2:3) photo scores 1.19 and fills with a barely-noticeable trim,
        // while a 512x512 square logo scores 1.78 and would lose 44% of its height —
        // that one gets letterboxed instead of having its wordmark chopped in half.
        const val MAX_FILL_ASPECT_RATIO = 1.35f
    }

    private fun reloadAndAdvance() {
        // Gapless fast path: the next clip is already preloaded with its first frame
        // decoded — advance from the in-memory plan so the swap happens with no disk read
        // between the outgoing last frame and the incoming first. peekNextItem() used this
        // same plan+index to choose what to preload, so advance() selects that same item
        // and hits swapInPreloaded(). If anything has drifted, the swap guard fails and it
        // builds fresh — correct, just not gapless.
        if (preloadReady && preloadedItem != null && lastPlan != null) {
            advance(lastPlan!!)
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            val plan = PlanLoader.load(context)
            mainHandler.post {
                if (plan != null) advance(plan)
            }
        }
    }

    private fun scheduleAdvanceTimer(item: PlanItem, delayMs: Long = item.durationMs) {
        cancelAdvanceTimer()
        val runnable = Runnable { reloadAndAdvance() }
        advanceRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs)
    }

    /**
     * Renders a still image and starts its slot timer only once the bitmap is actually on
     * screen. Glide decodes asynchronously, so starting the timer at request time (what
     * this used to do) billed the decode to the image's own slot: measured on the
     * HiSilicon bench panel, a 10s photo was visible for barely 3-4s. Worse, the cleared
     * ImageView is transparent and playerView is never hidden (see transitionViews), so
     * during the decode the PREVIOUS video kept showing through — the slot looked like it
     * belonged to the wrong creative.
     *
     * Scaling adapts to how badly the creative's shape matches the panel, because a fixed
     * choice is wrong half the time: centerCrop everywhere silently chopped the edges off
     * a landscape photo shown on a portrait screen, while fitCenter everywhere letterboxed
     * near-correct creatives into a small island of content surrounded by black.
     * So: fill (crop) while the mismatch is mild, and only fall back to letterboxing once
     * filling would hide more of the creative than MAX_FILL_ASPECT_RATIO allows.
     *
     * A safety timer (slot + grace) is armed up front so a broken or never-returning load
     * can't stall the playlist forever; the real timer replaces it on the callback.
     */
    private fun showImage(iv: ImageView, uri: android.net.Uri, item: PlanItem) {
        Glide.with(context).clear(iv)
        scheduleAdvanceTimer(item, item.durationMs + IMAGE_LOAD_GRACE_MS)
        Glide.with(context)
            .load(uri)
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(
                    e: GlideException?, model: Any?, target: Target<Drawable>, isFirstResource: Boolean,
                ): Boolean {
                    // Don't hold the slot open for an image that will never appear.
                    scheduleAdvanceTimer(item, 0L)
                    return false
                }

                override fun onResourceReady(
                    resource: Drawable, model: Any, target: Target<Drawable>?,
                    dataSource: DataSource, isFirstResource: Boolean,
                ): Boolean {
                    // post, not inline: the ImageView starts out GONE, so on the first
                    // showing this callback can run before it has ever been measured —
                    // width/height are 0 and the fit decision would be made blind.
                    iv.post { iv.scaleType = chooseScaleType(iv, resource) }
                    // Pixels are up now — give the creative its full, honest duration.
                    scheduleAdvanceTimer(item)
                    return false
                }
            })
            .into(iv)
    }

    /**
     * CENTER_CROP (fill the panel) unless the creative's aspect ratio differs from the
     * panel's by more than MAX_FILL_ASPECT_RATIO, in which case FIT_CENTER so a badly
     * mismatched creative is still shown whole rather than reduced to a cropped sliver.
     */
    private fun chooseScaleType(iv: ImageView, d: Drawable): ImageView.ScaleType {
        val vw = iv.width.toFloat()
        val vh = iv.height.toFloat()
        val iw = d.intrinsicWidth.toFloat()
        val ih = d.intrinsicHeight.toFloat()
        // Unknown geometry (unmeasured view, unsized drawable): letterbox. Cropping blind
        // risks chopping a paid creative in half, which is far worse than black bars.
        if (vw <= 0f || vh <= 0f || iw <= 0f || ih <= 0f) return ImageView.ScaleType.FIT_CENTER
        val viewAspect = vw / vh
        val imgAspect  = iw / ih
        val mismatch = maxOf(viewAspect / imgAspect, imgAspect / viewAspect)
        return if (mismatch <= MAX_FILL_ASPECT_RATIO) ImageView.ScaleType.CENTER_CROP
               else ImageView.ScaleType.FIT_CENTER
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

        // A restart replays the CURRENT clip on the active surface, so any preload staged
        // for the *next* clip is now stale — drop it (and its two-decoder pressure).
        releasePreload()

        val localFile = item.sha256?.let { sha256 ->
            item.ext?.let { ext ->
                AssetDownloader.getCachedFile(context, item.contentVersionId, "current", sha256, ext)
            }
        }
        val resolvedUri = if (localFile != null) android.net.Uri.fromFile(localFile) else android.net.Uri.parse(item.uri)

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
                // Same first-frame gating as renderItem() -- see videoEndListener.onRenderedFirstFrame().
                pendingOldView = listOf(pv, iv, wv).firstOrNull { it !== pv && it.visibility == android.view.View.VISIBLE }
                player.setMediaItem(mediaItem)
                player.prepare()
                player.playWhenReady = true
                startStallWatchdog(item)
                // Slot timer armed in onRenderedFirstFrame, not here — see the note in
                // renderItem()'s video branch and videoEndListener.onRenderedFirstFrame.
            }
            "image" -> {
                // playerView is never hidden -- see transitionViews()'s doc comment.
                iv.visibility = android.view.View.VISIBLE
                wv.visibility = android.view.View.GONE

                showImage(iv, resolvedUri, item)
            }
            "web" -> {
                iv.visibility = android.view.View.GONE
                wv.visibility = android.view.View.VISIBLE

                wv.settings.javaScriptEnabled = false
                wv.loadUrl(item.uri)
                scheduleAdvanceTimer(item)
            }
        }
    }

    fun stop() {
        // Not "waiting" — stopped. Leaving this true would let an activity poller
        // resurrect the loop on an engine whose service is shutting down.
        isWaitingForContent = false
        cancelAdvanceTimer()
        retryRunnable?.let { mainHandler.removeCallbacks(it) }
        retryRunnable = null
        countdownRunnable?.let { mainHandler.removeCallbacks(it) }
        countdownRunnable = null
        val now = NtpSyncManager.now(context)
        currentItem?.let { emitCompleteEvent(it, now) }
        currentItem = null
        cancelStallWatchdog()
        releasePreload()   // tear down any preloaded next-clip player + its scheduled runnable
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
