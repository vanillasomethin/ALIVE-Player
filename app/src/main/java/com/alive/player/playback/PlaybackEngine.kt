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
import com.alive.player.schedule.SmilSequencer
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

    // Nested (Master → Internal) playlist traversal. Keyed by the server's planHash so
    // cursors persist across the per-item plan reloads (reloadAndAdvance) but reset the
    // moment the plan's content actually changes.
    private var sequencer: SmilSequencer? = null
    private var sequencerKey: String? = null

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

        if (!frozenGlassArmed) {
            frozenGlassArmed = true
            mainHandler.postDelayed(frozenGlassRunnable, FROZEN_GLASS_CHECK_MS)
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
        // The real container is whatever the resolved uri points at, which during a
        // rendition-flip fallback can differ from item.ext (e.g. the .mp4 transcode
        // still on disk while the plan now names a .mov original) — trust the uri's
        // filename over the plan when it carries an extension.
        val ext = uri.lastPathSegment?.substringAfterLast('.', "")?.takeIf { it.isNotBlank() }
            ?: item.ext
        val mimeType = when (ext?.lowercase()) {
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

    // The local file most recently resolved for each content, so a stall/decode-error
    // eviction targets the file that actually played. During a rendition flip the
    // fallback below serves the PREVIOUS hash's file; evicting by the item's (new)
    // hash would miss it and the same corrupt file would come around every loop.
    // Main-thread only, like the rest of the engine's mutable state.
    private val resolvedLocalFiles = HashMap<String, java.io.File>()

    private fun resolvedUriFor(item: PlanItem): android.net.Uri {
        val exact = item.sha256?.let { sha256 ->
            item.ext?.let { ext ->
                AssetDownloader.getCachedFile(context, item.contentVersionId, "current", sha256, ext)
            }
        }
        // Flip window: PlanFetchWorker commits the new plan before its downloads
        // finish, so the plan can name a hash that isn't on disk yet while the
        // previous rendition still is. Play the previous file rather than stream —
        // store Wi-Fi makes streaming stall-prone, and a black slot still emits
        // proof-of-play. DownloadWorker swaps the real file in for a later loop.
        val localFile = exact
            ?: AssetDownloader.newestCompleteCachedFile(context, item.contentVersionId, "current")
        return if (localFile != null) {
            resolvedLocalFiles[item.contentVersionId] = localFile
            android.net.Uri.fromFile(localFile)
        } else {
            resolvedLocalFiles.remove(item.contentVersionId)
            android.net.Uri.parse(item.uri)
        }
    }

    // ── Loop-kick debounce ───────────────────────────────────────────────
    // startLoop() is called from several independent tickers (30s plan poll, 5s
    // download poll while waiting, retry countdown). Each call ends in renderItem()
    // → prepare()/surface churn. A dashboard editing session that saves/force-syncs
    // repeatedly delivers several full plans a minute, and back-to-back re-kicks
    // wedged a MediaTek panel's EGL window (2026-08-19: last frame frozen on glass
    // while the loop kept advancing — "EGLNativeWindowType disconnect failed").
    // Leading edge stays immediate so ordinary kicks are untouched; anything more
    // inside the window collapses into ONE trailing kick. Worst added latency for
    // a waiting screen is the window itself.
    private var lastLoopKickElapsedMs = 0L
    private var pendingLoopKick: Runnable? = null

    fun startLoop() {
        val now = android.os.SystemClock.elapsedRealtime()
        val sinceLast = now - lastLoopKickElapsedMs
        if (sinceLast >= LOOP_KICK_MIN_INTERVAL_MS) {
            lastLoopKickElapsedMs = now
            startLoopNow()
        } else if (pendingLoopKick == null) {
            val kick = Runnable {
                pendingLoopKick = null
                lastLoopKickElapsedMs = android.os.SystemClock.elapsedRealtime()
                startLoopNow()
            }
            pendingLoopKick = kick
            mainHandler.postDelayed(kick, LOOP_KICK_MIN_INTERVAL_MS - sinceLast)
        }
        // else: a trailing kick is already queued — this bump is absorbed by it.
    }

    private fun startLoopNow() {
        retryRunnable?.let { mainHandler.removeCallbacks(it) }
        countdownRunnable?.let { mainHandler.removeCallbacks(it) }
        CoroutineScope(Dispatchers.IO).launch {
            val plan = PlanLoader.load(context)
            val fetchStatus = DevicePrefs(context).getFetchStatus()
            mainHandler.post {
                if (plan == null || (plan.windows.isEmpty() && plan.fallbackItems.isEmpty())) {
                    // Same exposure as advance()'s null-item branch: a video can be
                    // mid-prepare with its first-frame deadline armed when the plan
                    // empties under it. No render is coming to cancel it, so drop it
                    // here or it fires under the waiting overlay, records a bogus
                    // stall and evicts a file that was never the problem.
                    cancelFirstFrameDeadline()
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

        val scheduledActive = activeWindow != null && activeWindow.items.isNotEmpty()

        val item: PlanItem? = if (scheduledActive && plan.nested.isNotEmpty()) {
            // Nested plan: SmilSequencer walks the Master → Internal tree. Its traversal
            // order equals the server's flattened `items`, so behaviour matches legacy
            // players; the tree keeps per-playlist cursors for future semantics.
            if (sequencer == null || sequencerKey != plan.nestedKey) {
                sequencer = SmilSequencer(plan.nested)
                sequencerKey = plan.nestedKey
            }
            sequencer?.next()
        } else {
            val itemList = if (scheduledActive) activeWindow!!.items else plan.fallbackItems
            if (itemList.isEmpty()) null else {
                val picked = itemList[currentItemIndex % itemList.size]
                currentItemIndex = (currentItemIndex + 1) % itemList.size
                picked
            }
        }

        if (item == null) {
            // No render coming, so no first frame will ever cancel a leftover deadline
            // from the previous (unrendered) clip — drop it here or it fires while the
            // waiting overlay is up and re-runs this loop with a bogus stall recorded.
            cancelFirstFrameDeadline()
            isWaitingForContent = true
            onWaiting?.invoke("No content for current time slot")
            startRetryCountdown()
            return
        }

        isWaitingForContent = false
        renderItem(item)
    }

    /**
     * Sound Ad add-on: base ads always stay silent (volume 0). The one loop position
     * the server marks [PlanItem.soundEligible] gets audio for exactly one play per
     * hour -- not looped, per spec -- gated by [DevicePrefs.getLastSoundAdPlayMs],
     * and only if the store owner hasn't muted it via [DevicePrefs.getSoundAdMuted].
     * That mute flag is read from prefs (not the Plan object) because it isn't part
     * of planHash -- PlanFetchWorker applies it on every poll, same as orientation,
     * so a mute toggle takes effect even when the plan CONTENT hasn't changed and the
     * cached plan JSON wasn't rewritten. Called once per fresh ExoPlayer instance
     * (renderItem's video branch); restartCurrentItem reuses that same instance/volume
     * on a stall recovery rather than re-deciding, so a mid-play stall can't
     * double-consume the hourly allowance.
     */
    private fun resolveVolume(item: PlanItem): Float {
        val prefs = DevicePrefs(context)
        if (!item.soundEligible || prefs.getSoundAdMuted()) return 0f
        val now = System.currentTimeMillis()
        if (now - prefs.getLastSoundAdPlayMs() < SOUND_AD_INTERVAL_MS) return 0f
        prefs.setLastSoundAdPlayMs(now)
        return 1f
    }

    private fun renderItem(item: PlanItem) {
        val iv = imageView
        val wv = webView
        val activeView = playerView

        // Every new item invalidates the previous video's first-frame deadline — an
        // unrendered clip we advanced past (deadline/error/ENDED) must not have its
        // stale deadline fire mid-way through THIS item and cut it short. Ditto the
        // 2s error-reload: this render IS the advance it was waiting for.
        cancelFirstFrameDeadline()
        cancelErrorReload()
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
                // Sound Ad: the designated slot plays with audio, everything else
                // stays silent — set before setMediaItem so the first frame is right.
                player.volume = resolveVolume(item)
                player.setMediaItem(mediaItemFor(item, resolvedUri))
                player.prepare()
                player.playWhenReady = true
                startStallWatchdog(item)
                startFirstFrameDeadline(item)
                // Release the outgoing player after the swap is wired up; its last frame is
                // held by keep_content_on_player_reset until the new first frame covers it.
                outgoing?.let { old -> mainHandler.post { old.release() } }
                // Slot timer + next-clip preload are armed in onRenderedFirstFrame, not
                // here — arming at prepare() time chopped the last ~1s off clips whose
                // slot length equalled their own length.
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
            cancelFirstFrameDeadline()
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
                cancelFirstFrameDeadline()
                cancelStallWatchdog()
                cancelAdvanceTimer()
                reloadAndAdvance()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            android.util.Log.e("PlaybackEngine", "Video error ${error.errorCode}: ${error.message}")
            cancelFirstFrameDeadline()
            cancelStallWatchdog()
            pendingOldView = null
            // A decode/source error on a cached file usually means the file is bad, not the
            // media — drop it so the next plan fetch re-downloads with a fresh hash check.
            currentItem?.let { evictCachedCopy(it) }
            cancelAdvanceTimer()
            // Brief delay before advancing so we don't spin instantly on a broken
            // playlist. Tracked (not a bare lambda) so renderItem/stop can cancel it —
            // an orphaned reload surviving into the NEXT item's slot would cut that
            // item short ~2s in.
            cancelErrorReload()
            val reload = Runnable {
                errorReloadRunnable = null
                reloadAndAdvance()
            }
            errorReloadRunnable = reload
            mainHandler.postDelayed(reload, 2_000)
        }
    }

    // Pending 2s reload posted by onPlayerError; cancelled by the next render or stop().
    private var errorReloadRunnable: Runnable? = null

    private fun cancelErrorReload() {
        errorReloadRunnable?.let { mainHandler.removeCallbacks(it) }
        errorReloadRunnable = null
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
                    // Symmetry with the deadline/error fire paths: every fire path
                    // cancels the other two so no stale trigger survives the advance.
                    cancelFirstFrameDeadline()
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

    // ── First-frame deadline ─────────────────────────────────────────────────────
    // The slot timer is armed only in onRenderedFirstFrame (see that comment), which
    // leaves one uncovered state: a decoder that accepts input but never renders a
    // frame — no onPlayerError, isPlaying stays false, so the position-based stall
    // watchdog above resets forever and nothing ever advances. The blocklist in
    // safeMediaCodecSelector only names the decoders we KNOW do this; this deadline
    // bounds the ones it doesn't (and a network stream trickling in STATE_BUFFERING
    // indefinitely): no first frame within FIRST_FRAME_DEADLINE_MS of prepare() is
    // treated exactly like a stall — evict, record, move on.
    private var firstFrameDeadlineRunnable: Runnable? = null

    private fun startFirstFrameDeadline(item: PlanItem) {
        cancelFirstFrameDeadline()
        val deadline = Runnable {
            firstFrameDeadlineRunnable = null
            val reason = "no first frame ${FIRST_FRAME_DEADLINE_MS / 1000}s after prepare: ${item.contentVersionId}"
            android.util.Log.e("PlaybackEngine", reason)
            lastStallReason = reason
            DevicePrefs(context).setLastStall(reason, System.currentTimeMillis())
            cancelStallWatchdog()
            cancelAdvanceTimer()
            evictCachedCopy(item)
            reloadAndAdvance()
        }
        firstFrameDeadlineRunnable = deadline
        mainHandler.postDelayed(deadline, FIRST_FRAME_DEADLINE_MS)
    }

    private fun cancelFirstFrameDeadline() {
        firstFrameDeadlineRunnable?.let { mainHandler.removeCallbacks(it) }
        firstFrameDeadlineRunnable = null
    }

    private fun evictCachedCopy(item: PlanItem) {
        // Evict the file that actually played — during a rendition flip that is the
        // fallback (previous-hash) file, which the exact-hash path below can't name.
        resolvedLocalFiles.remove(item.contentVersionId)?.let { played ->
            runCatching { played.delete() }
            return
        }
        val sha = item.sha256 ?: return
        val ext = item.ext ?: return
        AssetDownloader.evictCorrupt(context, item.contentVersionId, "current", sha, ext)
    }

    // ── Frozen-glass watchdog ────────────────────────────────────────────
    // Catches the failure mode the heartbeat/stall watchdogs are blind to: the engine
    // advances items and reports plays, ExoPlayer decodes without error — but frames
    // stopped reaching the display (wedged EGL window; observed on MediaTek m7632 after
    // a burst of plan re-kicks, 2026-08-19: last frame stuck on glass while
    // proof-of-play kept flowing, cleared only by a reboot).
    //
    // Signal: SurfaceTexture.getTimestamp() — the presentation time of the most
    // recently LATCHED frame. It advances whenever a decoded frame actually reaches the
    // view's surface, so it is content-independent: a static-frame creative still
    // latches ~30 frames a second. (Pixel sampling was tried first and rejected — a
    // static creative is pixel-identical while perfectly healthy, and any sampler
    // period dividing the playlist loop aliases onto the same frame.)
    //
    // Evidence is accumulated as TIME SPENT PLAYING WITH NO LATCH, not a streak of
    // consecutive probes: the wedge this targets persists across item boundaries (the
    // loop keeps advancing), while clips here are 5-15s, so any per-item or
    // consecutive-probe counter would be reset by normal advancing before it could ever
    // trip. Only an observed latch advance clears the accumulator.
    //
    // Recovery REBUILDS THE VIEW HIERARCHY (Activity.recreate) rather than killing the
    // process. A kill was the first design and was rejected: the foreground service is
    // START_STICKY and would respawn the engine with no activity bound, which emits
    // full-duration COMPLETE proof-of-play — advertiser-billed plays — against a black
    // screen until the UI returns, and the alarm-driven relaunch is subject to
    // background-activity-start rules that silently drop it on non-owner installs.
    // recreate() builds a fresh TextureView (the thing that is actually wedged) while
    // the service, engine and playlist position survive, so the worst case of a false
    // positive is a ~1s visual blip instead of a killed screen. That asymmetry is what
    // makes the residual detection uncertainty acceptable.
    private var frozenGlassArmed = false
    private var lastLatchTimestampNs = 0L
    private var lastSurfaceTextureId = 0
    private var lastGlassCheckElapsedMs = 0L
    private var noLatchAccumMs = 0L
    private var latchEverAdvanced = false
    private var lastGlassRecoveryElapsedMs = 0L

    /**
     * Invoked on the main thread when frame delivery has provably stalled. Set by
     * PlaybackActivity to recreate itself; null when no activity is bound, in which
     * case detection is skipped entirely — there is no surface to rebuild and a
     * headless engine must never "recover" into billing plays for a screen that has
     * no UI at all.
     */
    var onGlassWedged: (() -> Unit)? = null

    private val frozenGlassRunnable = object : Runnable {
        override fun run() {
            runCatching { checkFrozenGlass() }
            mainHandler.postDelayed(this, FROZEN_GLASS_CHECK_MS)
        }
    }

    private fun cancelFrozenGlassWatchdog() {
        mainHandler.removeCallbacks(frozenGlassRunnable)
        frozenGlassArmed = false
        resetFrozenGlassState()
    }

    private fun resetFrozenGlassState() {
        noLatchAccumMs = 0L
        lastGlassCheckElapsedMs = 0L
        lastLatchTimestampNs = 0L
        lastSurfaceTextureId = 0
    }

    private fun checkFrozenGlass() {
        val nowElapsed = android.os.SystemClock.elapsedRealtime()
        val sinceLastCheck = if (lastGlassCheckElapsedMs == 0L) 0L else nowElapsed - lastGlassCheckElapsedMs
        lastGlassCheckElapsedMs = nowElapsed

        // "Not judgeable" conditions SKIP (no accumulation, no reset) rather than
        // clearing the accumulator. Clearing would make a mixed playlist un-judgeable
        // forever: renderItem nulls exoPlayer for every image item, so an
        // images-and-video loop would wipe the evidence several times a minute and the
        // 60s threshold could never be reached. Only positive evidence of health (a
        // latch advance) or an invalidated baseline (new surface) clears it.
        val pv = playerView ?: return
        val player = exoPlayer ?: return
        // No bound activity — nothing to rebuild, and nothing on screen to judge.
        if (onGlassWedged == null) return

        // Only a playing VIDEO is expected to produce a frame stream. Images, web items,
        // the waiting overlay and a paused/buffering player legitimately latch nothing.
        if (isWaitingForContent || currentItem?.type != "video" || !player.isPlaying) return

        // Display power state. The engine is service-owned and keeps decoding while the
        // panel is asleep or in HDMI-CEC standby; the compositor stops driving frames
        // then, so a healthy screen would look exactly like a wedge. isShown/
        // hasWindowFocus do NOT cover this — they are view/window attachment only.
        val display = (context.getSystemService(Context.DISPLAY_SERVICE) as? android.hardware.display.DisplayManager)
            ?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
        if (display != null && display.state != android.view.Display.STATE_ON) return
        // Window must be attached AND frontmost: a technician in Settings (MENU /
        // triple-select servicing flow) or any dialog on top stops frame delivery
        // without anything being wrong.
        if (!pv.isShown || !pv.hasWindowFocus()) return

        val texture = pv.videoSurfaceView as? android.view.TextureView
            ?: return  // SurfaceView build: signal unavailable, never judge
        val surfaceTexture = texture.surfaceTexture ?: return

        // A different SurfaceTexture means a fresh surface (double-buffer swap, view
        // rebuild): its timestamps are not comparable with the previous one, so start
        // a new baseline instead of reading the discontinuity as a stall.
        val textureId = System.identityHashCode(surfaceTexture)
        if (textureId != lastSurfaceTextureId) {
            lastSurfaceTextureId = textureId
            lastLatchTimestampNs = runCatching { surfaceTexture.timestamp }.getOrDefault(0L)
            noLatchAccumMs = 0L
            return
        }

        val latchNs = runCatching { surfaceTexture.timestamp }.getOrDefault(0L)
        if (latchNs != lastLatchTimestampNs) {
            // Frames are reaching the surface.
            lastLatchTimestampNs = latchNs
            latchEverAdvanced = true
            noLatchAccumMs = 0L
            return
        }

        // Never having seen this device advance the timestamp at all means the signal is
        // simply unavailable here (some vendor stacks never populate it) — not evidence
        // of a wedge. Without this the detector would "trip" permanently on such
        // hardware from the very first probe.
        if (!latchEverAdvanced) return

        noLatchAccumMs += sinceLastCheck
        if (noLatchAccumMs < FROZEN_GLASS_TRIP_MS) return
        if (nowElapsed - lastGlassRecoveryElapsedMs < FROZEN_GLASS_RECOVERY_COOLDOWN_MS &&
            lastGlassRecoveryElapsedMs != 0L
        ) {
            return
        }
        recoverFromFrozenGlass(nowElapsed)
    }

    private fun recoverFromFrozenGlass(nowElapsed: Long) {
        lastGlassRecoveryElapsedMs = nowElapsed
        val stalledMs = noLatchAccumMs
        resetFrozenGlassState()
        android.util.Log.e(
            "PlaybackEngine",
            "Frozen glass: no frame latched during ${stalledMs}ms of playing video — rebuilding views",
        )
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                AppDatabase.get(context).incidentDao().insert(
                    com.alive.player.data.Incident(
                        type = "FROZEN_GLASS_RECOVERED",
                        timestampUtcEpochMs = System.currentTimeMillis(),
                        metadataJson = """{"stalledMs":$stalledMs}""",
                    )
                )
            }
        }
        onGlassWedged?.invoke()
    }

    private companion object {
        const val STALL_POLL_MS    = 2_000L
        const val STALL_TIMEOUT_MS = 10_000L
        // Loop-kick debounce window (see startLoop). Longer than the 5s download poll
        // so a waiting screen still starts within one window of its last download.
        const val LOOP_KICK_MIN_INTERVAL_MS = 10_000L
        // Frozen-glass watchdog: probe cadence, and how much accumulated playing-time
        // with no frame latch counts as a wedge (60s ≈ 4-12 normal clips, far longer
        // than any legitimate delivery gap).
        const val FROZEN_GLASS_CHECK_MS = 10_000L
        const val FROZEN_GLASS_TRIP_MS = 60_000L
        // Minimum spacing between view rebuilds if the wedge survives one.
        const val FROZEN_GLASS_RECOVERY_COOLDOWN_MS = 5 * 60_000L
        // Upper bound from prepare() to first rendered frame before the clip is treated
        // as wedged. Normal prepare+decode is ~0.5-1s on these boxes; 20s leaves room
        // for a slow network fallback (item not yet downloaded) without letting a
        // silently-wedged decoder freeze the screen past one slot length.
        const val FIRST_FRAME_DEADLINE_MS = 20_000L
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
        const val SOUND_AD_INTERVAL_MS = 60 * 60 * 1000L
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

        // Same resolution as renderItem(): exact hash, then the rendition-flip
        // fallback, then streaming — see resolvedUriFor().
        val resolvedUri = resolvedUriFor(item)

        when (item.type) {
            "video" -> {
                val player = exoPlayer ?: return
                cancelAdvanceTimer()
                player.clearMediaItems()
                player.removeListener(videoEndListener)
                player.addListener(videoEndListener)
                val mediaItem = mediaItemFor(item, resolvedUri)
                // Same first-frame gating as renderItem() -- see videoEndListener.onRenderedFirstFrame().
                pendingOldView = listOf(pv, iv, wv).firstOrNull { it !== pv && it.visibility == android.view.View.VISIBLE }
                player.setMediaItem(mediaItem)
                player.prepare()
                player.playWhenReady = true
                startStallWatchdog(item)
                startFirstFrameDeadline(item)
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
        cancelErrorReload()
        // A debounced trailing kick outlives this stop() unless cancelled here: while
        // the engine waits for content the activity's 5s download poll queues one
        // roughly half the time, so a kiosk exit / service shutdown landed inside that
        // window would fire startLoopNow() on a stopped engine up to 10s later. With
        // views detached it takes renderItem's null-view branch and self-perpetuates
        // through scheduleAdvanceTimer, emitting full-duration COMPLETE proof-of-play
        // for content nothing is displaying (advertiser-billed) and keeping
        // playbackAliveAt fresh — which is exactly the signal ops relies on to see a
        // screen that stopped playing.
        pendingLoopKick?.let { mainHandler.removeCallbacks(it) }
        pendingLoopKick = null
        cancelFrozenGlassWatchdog()
        retryRunnable?.let { mainHandler.removeCallbacks(it) }
        retryRunnable = null
        countdownRunnable?.let { mainHandler.removeCallbacks(it) }
        countdownRunnable = null
        val now = NtpSyncManager.now(context)
        currentItem?.let { emitCompleteEvent(it, now) }
        currentItem = null
        cancelFirstFrameDeadline()
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
                slotPosition = item.slotPosition,
                isFiller = item.isFiller,
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
