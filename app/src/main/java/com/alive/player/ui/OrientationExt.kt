package com.alive.player.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.util.Log
import android.view.View
import android.view.ViewGroup
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
 */
fun Activity.applyContentRotationTo(container: View) {
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

    if (container.rotation == rotation && lp.width == wantW && lp.height == wantH) return

    container.rotation = rotation
    lp.width  = wantW
    lp.height = wantH
    container.layoutParams = lp
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
