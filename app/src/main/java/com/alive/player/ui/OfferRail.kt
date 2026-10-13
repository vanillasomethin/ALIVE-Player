package com.alive.player.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.speech.tts.TextToSpeech
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.alive.player.schedule.PlanItem
import com.alive.player.settings.DevicePrefs
import com.bumptech.glide.Glide
import org.json.JSONObject
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max

/**
 * The store's own offers, run as a ticker along the edge of the screen while the ad plays
 * resized beside it (studio Store.offerRail, sent as the plan's top-level `offerRail`).
 *
 * Layout: the rail lives INSIDE content_rotator, so "right" and "bottom" are the content's
 * edges whatever way the panel is physically mounted. A portrait screen gets a column on
 * the right that scrolls up; a landscape screen gets a strip along the bottom that scrolls
 * left, like a TV news ticker. The media views are never covered: they get a margin the
 * size of the rail and the content scales to fit what is left (resize_mode=fit,
 * fitCenter), so a brand's creative is smaller, never cropped or hidden.
 *
 * When it shows: mode "house" only while a house-ad position (isFiller) or unsold
 * schedule content plays; mode "all" during sold slots too. Decided per item at the item
 * boundary, where the picture changes anyway.
 *
 * Audio: Android text-to-speech on the TV reads ONE offer at most every audioEveryMin,
 * and only during house positions. Every non-Sound-Ad item already plays silent
 * (PlaybackEngine.resolveVolume), so it never talks over a brand; when the Sound Ad
 * starts, speech is cut immediately.
 */
class OfferRail(
    private val context: Context,
    private val host: FrameLayout,
    private val mediaViews: List<View>,
) {
    private data class Offer(val name: String, val weight: String?, val mrp: Int, val price: Int, val imageUrl: String?)
    private data class Config(val mode: String, val audio: Boolean, val audioEveryMs: Long, val offers: List<Offer>)

    private var rawJson: String? = null
    private var config: Config? = null
    private var root: LinearLayout? = null
    private var strip: LinearLayout? = null
    private var animator: ValueAnimator? = null
    private var vertical = true
    private var shown = false

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var lastSpokeMs = 0L
    private var nextToSpeak = 0

    /** Called on the main thread whenever the engine starts an item. */
    fun onItem(item: PlanItem?) {
        refreshConfig()
        val cfg = config
        val house = item != null && (item.isFiller || item.slotPosition == null)
        val want = item != null && cfg != null && cfg.offers.isNotEmpty() && (cfg.mode == "all" || house)
        if (want) show() else hide()

        if (item?.soundEligible == true) tts?.stop()
        if (want && house && cfg!!.audio && !item!!.soundEligible) maybeSpeak(cfg)
    }

    /** The engine is waiting / nothing on screen: no rail, no voice. */
    fun hide() {
        if (!shown) return
        shown = false
        animator?.cancel()
        root?.visibility = View.GONE
        setMediaInset(0)
        tts?.stop()
    }

    fun release() {
        hide()
        animator?.cancel()
        animator = null
        runCatching { tts?.shutdown() }
        tts = null
    }

    // ── Config from prefs (written by PlanFetchWorker on every poll) ──────────────────
    private fun refreshConfig() {
        val json = DevicePrefs(context).getOfferRail()
        if (json == rawJson) return
        rawJson = json
        config = json?.let { parse(it) }
        // Offers changed: rebuild the strip next time it shows.
        animator?.cancel()
        root?.let { host.removeView(it) }
        root = null; strip = null
        if (shown) { shown = false; setMediaInset(0) }
        if (config?.audio == true && tts == null) initTts()
    }

    private fun parse(json: String): Config? = runCatching {
        val o = JSONObject(json)
        val arr = o.optJSONArray("items") ?: return null
        val offers = (0 until arr.length()).map { i ->
            val x = arr.getJSONObject(i)
            Offer(
                name = x.optString("name"),
                weight = x.optString("weight").takeIf { it.isNotBlank() && it != "null" },
                mrp = x.optInt("mrp"), price = x.optInt("price"),
                imageUrl = x.optString("imageUrl").takeIf { it.isNotBlank() && it != "null" },
            )
        }.filter { it.name.isNotBlank() }
        Config(
            mode = o.optString("mode", "house"),
            audio = o.optBoolean("audio", false),
            audioEveryMs = max(2, o.optInt("audioEveryMin", 10)) * 60_000L,
            offers = offers,
        )
    }.onFailure { Log.w(TAG, "offerRail JSON unreadable", it) }.getOrNull()

    // ── Showing / hiding ─────────────────────────────────────────────────────────────
    private fun show() {
        val w = host.width.takeIf { it > 0 } ?: host.layoutParams.width.takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels
        val h = host.height.takeIf { it > 0 } ?: host.layoutParams.height.takeIf { it > 0 } ?: context.resources.displayMetrics.heightPixels
        val portrait = h > w
        if (root == null || vertical != portrait) build(portrait, w, h)
        if (shown) return
        shown = true
        root?.visibility = View.VISIBLE
        root?.bringToFront()
        setMediaInset(railSize(w, h))
        startTicker()
    }

    /** Width of the right-hand column (portrait) or height of the bottom strip (landscape). */
    private fun railSize(w: Int, h: Int) = if (vertical) (w * 0.27f).toInt() else (h * 0.2f).toInt()

    /** Shrinks every media surface by the rail's size on the rail's edge. */
    private fun setMediaInset(px: Int) {
        for (v in mediaViews) {
            val lp = v.layoutParams as? FrameLayout.LayoutParams ?: continue
            val right = if (vertical) px else 0
            val bottom = if (vertical) 0 else px
            if (lp.rightMargin == right && lp.bottomMargin == bottom) continue
            lp.rightMargin = right
            lp.bottomMargin = bottom
            v.layoutParams = lp
        }
    }

    // ── Building the rail ────────────────────────────────────────────────────────────
    private fun build(portrait: Boolean, w: Int, h: Int) {
        root?.let { host.removeView(it) }
        animator?.cancel()
        vertical = portrait
        val cfg = config ?: return
        val size = railSize(w, h)
        val unit = if (vertical) size / 10f else size / 6f   // type scale follows the rail

        val r = LinearLayout(context).apply {
            orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#120707"))
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                if (vertical) size else ViewGroup.LayoutParams.MATCH_PARENT,
                if (vertical) ViewGroup.LayoutParams.MATCH_PARENT else size,
                if (vertical) Gravity.END else Gravity.BOTTOM,
            )
        }
        // The label: a solid red band, like a channel's ticker tag.
        r.addView(TextView(context).apply {
            text = if (vertical) "TODAY'S\nOFFERS" else "TODAY'S OFFERS"
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_PX, unit * 1.05f)
            letterSpacing = 0.06f
            setBackgroundColor(Color.parseColor("#E11D2A"))
            val pad = (unit * 0.6f).toInt()
            setPadding(pad, pad, pad, pad)
            layoutParams = if (vertical) LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                else LinearLayout.LayoutParams((w * 0.14f).toInt(), ViewGroup.LayoutParams.MATCH_PARENT)
        })
        // The window the cards scroll through.
        val window = FrameLayout(context).apply {
            clipChildren = true
            layoutParams = LinearLayout.LayoutParams(
                if (vertical) ViewGroup.LayoutParams.MATCH_PARENT else 0,
                if (vertical) 0 else ViewGroup.LayoutParams.MATCH_PARENT, 1f,
            )
        }
        val s = LinearLayout(context).apply {
            orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                if (vertical) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT,
                if (vertical) ViewGroup.LayoutParams.WRAP_CONTENT else ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        cfg.offers.forEach { s.addView(card(it, unit, size)) }
        window.addView(s)
        r.addView(window)
        host.addView(r)
        root = r
        strip = s
    }

    private fun card(o: Offer, unit: Float, size: Int): View {
        val pad = (unit * 0.5f).toInt()
        val card = LinearLayout(context).apply {
            orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#2A0E0E")); cornerRadius = unit * 0.6f
            }
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(
                if (vertical) ViewGroup.LayoutParams.MATCH_PARENT else (size * 2.6f).toInt(),
                if (vertical) ViewGroup.LayoutParams.WRAP_CONTENT else ViewGroup.LayoutParams.MATCH_PARENT,
            ).apply { setMargins(pad, pad, pad, pad) }
        }
        if (o.imageUrl != null) {
            val img = ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                val side = if (vertical) (size * 0.62f).toInt() else (size * 0.72f).toInt()
                layoutParams = LinearLayout.LayoutParams(side, side)
                background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = unit * 0.4f }
                setPadding(pad / 2, pad / 2, pad / 2, pad / 2)
            }
            Glide.with(context).load(o.imageUrl).into(img)
            card.addView(img)
        }
        val text = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (vertical) Gravity.CENTER_HORIZONTAL else Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                if (vertical) ViewGroup.LayoutParams.MATCH_PARENT else 0,
                ViewGroup.LayoutParams.WRAP_CONTENT, if (vertical) 0f else 1f,
            ).apply { if (!vertical) leftMargin = pad }
        }
        fun tv(t: String, sizeMul: Float, color: String, bold: Boolean = false, lines: Int = 1) = TextView(context).apply {
            this.text = t
            setTextColor(Color.parseColor(color))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, unit * sizeMul)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            maxLines = lines
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = if (vertical) Gravity.CENTER_HORIZONTAL else Gravity.START
        }
        text.addView(tv(o.name, 0.95f, "#FFFFFF", bold = true, lines = 2))
        o.weight?.let { text.addView(tv(it, 0.75f, "#C9B8B8")) }
        text.addView(tv("₹${o.price}", 1.7f, "#FFD23F", bold = true))
        if (o.mrp > o.price) {
            text.addView(tv("MRP ₹${o.mrp}", 0.75f, "#9C8A8A").apply { paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG })
            text.addView(tv("SAVE ₹${o.mrp - o.price}", 0.8f, "#7CE38B", bold = true))
        }
        card.addView(text)
        return card
    }

    // ── The ticker ───────────────────────────────────────────────────────────────────
    private fun startTicker() {
        val strip = strip ?: return
        animator?.cancel()
        strip.post {
            val window = strip.parent as? View ?: return@post
            val viewLen = if (vertical) window.height else window.width
            // One set's length is measured once, on the strip as built (one copy). Then add
            // enough copies that the window is always full while one set scrolls past, and
            // scroll exactly one set before restarting: the seam is invisible.
            val setLen = (strip.tag as? Int) ?: (if (vertical) strip.height else strip.width).also { len ->
                if (len <= 0 || viewLen <= 0) return@post
                val copies = ceil(viewLen.toDouble() / len).toInt()
                val unit = unitFor()
                val size = railSize(host.width, host.height)
                repeat(copies) { config?.offers?.forEach { o -> strip.addView(card(o, unit, size)) } }
                strip.tag = len
            }
            // About one card every few seconds, whatever the screen size.
            val pxPerSec = viewLen / 14f
            animator = ValueAnimator.ofFloat(0f, -setLen.toFloat()).apply {
                duration = (setLen / pxPerSec * 1000).toLong().coerceAtLeast(4_000)
                interpolator = LinearInterpolator()
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.RESTART
                addUpdateListener { a -> val v = a.animatedValue as Float; if (vertical) strip.translationY = v else strip.translationX = v }
                start()
            }
        }
    }

    private fun unitFor(): Float {
        val size = railSize(host.width, host.height)
        return if (vertical) size / 10f else size / 6f
    }

    // ── Voice ────────────────────────────────────────────────────────────────────────
    private fun initTts() {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status != TextToSpeech.SUCCESS) { Log.w(TAG, "TTS unavailable ($status)"); return@TextToSpeech }
            val t = tts ?: return@TextToSpeech
            val ok = t.setLanguage(Locale("en", "IN"))
            if (ok == TextToSpeech.LANG_MISSING_DATA || ok == TextToSpeech.LANG_NOT_SUPPORTED) t.setLanguage(Locale.ENGLISH)
            t.setSpeechRate(0.92f)
            ttsReady = true
        }
    }

    private fun maybeSpeak(cfg: Config) {
        if (tts == null) initTts()
        val t = tts ?: return
        if (!ttsReady || cfg.offers.isEmpty()) return
        val now = System.currentTimeMillis()
        if (now - lastSpokeMs < cfg.audioEveryMs) return
        val o = cfg.offers[nextToSpeak % cfg.offers.size]
        nextToSpeak++
        lastSpokeMs = now
        val line = buildString {
            append("Today's offer at this store. ")
            append(o.name)
            o.weight?.let { append(", ").append(it) }
            append(". Only ").append(o.price).append(" rupees")
            if (o.mrp > o.price) append(", M R P ").append(o.mrp).append(" rupees")
            append(".")
        }
        t.speak(line, TextToSpeech.QUEUE_FLUSH, null, "offer-$now")
    }

    companion object { private const val TAG = "OfferRail" }
}
