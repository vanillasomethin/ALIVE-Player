package com.alive.player.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.LayerDrawable
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import com.alive.player.R
import com.alive.player.settings.DevicePrefs

private const val TAG = "OrientationExt"

fun Activity.applyOrientationPref() {
    setRequestedOrientationSafely(orientationConstant(DevicePrefs(this).getOrientationMode()))
}

/**
 * Software-rotates [container] to honour the orientation pref, mirroring
 * PlaybackActivity.applyContentRotation(). Needed on panels (e.g. the Foxsky/KTC
 * bench TVs) whose OS ACCEPTS a requestedOrientation change without physically
 * rotating: the activity relayouts into a squeezed portrait strip of the landscape
 * panel — the pairing code rendered one clipped character per line there. So the
 * activity must stay panel-native (no requestedOrientation call) and rotate this
 * container instead. Rotation is relative to what the panel actually gave us, so a
 * panel that IS physically portrait rotates by 0 and nothing double-rotates.
 *
 * [container] must sit centered in a full-screen parent (layout_gravity="center"):
 * rotation pivots about the view centre, and a quarter turn swaps width/height so
 * the rotated bounding box covers the panel.
 *
 * Returns the width in px the container is laid out at. A quarter turn swaps the
 * axes, so portrait signage lays its content across the panel's SHORT side -- a
 * caller whose layout only fits on the long side needs to know that (see
 * PairingActivity.stackPairingContentIfNarrow).
 */
fun Activity.applyContentRotationTo(container: View): Int {
    val isLandscapeNow = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val rotation = when (DevicePrefs(this).getOrientationMode()) {
        DevicePrefs.ORIENTATION_PORTRAIT         -> if (isLandscapeNow) 90f  else 0f
        DevicePrefs.ORIENTATION_REVERSE_PORTRAIT -> if (isLandscapeNow) 270f else 180f
        DevicePrefs.ORIENTATION_LANDSCAPE        -> if (isLandscapeNow) 0f   else 90f
        else                                     -> 0f
    }

    val quarterTurn = rotation == 90f || rotation == 270f
    val lp = container.layoutParams
    val wantW = if (quarterTurn) resources.displayMetrics.heightPixels else ViewGroup.LayoutParams.MATCH_PARENT
    val wantH = if (quarterTurn) resources.displayMetrics.widthPixels  else ViewGroup.LayoutParams.MATCH_PARENT

    val contentWidthPx =
        if (quarterTurn) resources.displayMetrics.heightPixels
        else resources.displayMetrics.widthPixels

    if (container.rotation == rotation && lp.width == wantW && lp.height == wantH) return contentWidthPx

    container.rotation = rotation
    lp.width  = wantW
    lp.height = wantH
    container.layoutParams = lp
    return contentWidthPx
}

// Operator screens (settings) must be readable on the panel AS IT PHYSICALLY IS.
// Applying the signage portrait *preference* here is wrong: the vast majority of
// budget TV panels don't physically rotate when the OS accepts a portrait request,
// so the landscape-designed setup layout gets letterboxed into a squeezed portrait
// sliver in the middle of the panel — unreadable (field-confirmed on a 1280x720
// Realtek panel). These screens take the panel's NATIVE landscape orientation
// instead; PLAYBACK content and the pairing screen follow the portrait preference
// by software rotation (applyContentRotation()/applyContentRotationTo() above),
// which actually fills a non-rotating panel. Safe-wrapped like applyOrientationPref
// for the same OEM reason.
fun Activity.applySetupOrientation() {
    setRequestedOrientationSafely(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
}

// Cycles and persists the preference only. Callers decide how to apply it:
// applyOrientationPref() (OS requestedOrientation) for simple UI screens, or
// PlaybackActivity's applyContentRotation() for the actual media content --
// not every TV panel physically rotates just because the OS accepted the request.
fun Activity.cycleOrientation() {
    val prefs = DevicePrefs(this)
    val next = when (prefs.getOrientationMode()) {
        DevicePrefs.ORIENTATION_PORTRAIT         -> DevicePrefs.ORIENTATION_REVERSE_PORTRAIT
        DevicePrefs.ORIENTATION_REVERSE_PORTRAIT -> DevicePrefs.ORIENTATION_PORTRAIT
        else                                     -> DevicePrefs.ORIENTATION_PORTRAIT
    }
    prefs.setOrientationMode(next)
}

// Some OEM Android TV firmware (e.g. non-certified "smart TV" builds) hosts the
// activity in a window the framework doesn't consider strictly fullscreen, which makes
// setRequestedOrientation() throw AndroidRuntimeException and kill the app outright.
// Swallow it so playback keeps running in whatever orientation the OS already gave us.
private fun Activity.setRequestedOrientationSafely(orientation: Int) {
    try {
        requestedOrientation = orientation
    } catch (e: android.util.AndroidRuntimeException) {
        Log.w(TAG, "setRequestedOrientation($orientation) rejected by OS, keeping current orientation", e)
    }
}

private fun orientationConstant(mode: String) = when (mode) {
    DevicePrefs.ORIENTATION_PORTRAIT         -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    DevicePrefs.ORIENTATION_REVERSE_PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
    DevicePrefs.ORIENTATION_LANDSCAPE         -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    else                                     -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
}

/**
 * How far content must turn, relative to what the panel gave us, to reach the signage
 * orientation. The same rule PlaybackActivity.applyContentRotation() and
 * applyContentRotationTo() apply to their containers, kept here as a pure function so the
 * boot logo below can follow it and a test can pin it.
 */
internal fun signageRotationFor(mode: String, isLandscapeNow: Boolean): Float = when (mode) {
    DevicePrefs.ORIENTATION_PORTRAIT         -> if (isLandscapeNow) 90f  else 0f
    DevicePrefs.ORIENTATION_REVERSE_PORTRAIT -> if (isLandscapeNow) 270f else 180f
    DevicePrefs.ORIENTATION_LANDSCAPE        -> if (isLandscapeNow) 0f   else 90f
    else                                     -> 0f
}

/**
 * The alive wordmark on ink, turned the way the signage is mounted, painted as the window
 * background from the first line of onCreate.
 *
 * It used to be the theme's windowBackground, which the system paints BEFORE any of our code
 * runs, so it could never be turned: on a portrait screen the panel is physically landscape and
 * our rotation only turns views inside the layout, which left the logo sideways for the moment
 * after the TV maker's boot screen. The theme now paints plain ink for that first frame, and
 * this puts the logo up as soon as we know how the screen is mounted. Best effort: any failure
 * leaves the plain ink frame.
 */
fun Activity.paintSplash() {
    runCatching {
        val src = BitmapFactory.decodeResource(resources, R.drawable.alive_splash_logo) ?: return
        val wantW = (280 * resources.displayMetrics.density).toInt()
        val wantH = wantW * src.height / src.width
        val scaled = Bitmap.createScaledBitmap(src, wantW, wantH, true)
        val rotation = signageRotationFor(
            DevicePrefs(this).getOrientationMode(),
            resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE,
        )
        val turned = if (rotation == 0f) scaled
        else Bitmap.createBitmap(scaled, 0, 0, scaled.width, scaled.height, Matrix().apply { postRotate(rotation) }, true)
        val logo = BitmapDrawable(resources, turned).apply { gravity = Gravity.CENTER }
        window.setBackgroundDrawable(LayerDrawable(arrayOf(ColorDrawable(getColor(R.color.alive_ink)), logo)))
    }
}
