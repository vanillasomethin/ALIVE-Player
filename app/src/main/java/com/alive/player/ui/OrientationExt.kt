package com.alive.player.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.util.Log
import com.alive.player.settings.DevicePrefs

private const val TAG = "OrientationExt"

fun Activity.applyOrientationPref() {
    setRequestedOrientationSafely(orientationConstant(DevicePrefs(this).getOrientationMode()))
}

// Setup/operator screens (pairing, settings) must be readable on the panel AS IT
// PHYSICALLY IS. Applying the signage portrait *preference* here is wrong: the vast
// majority of budget TV panels don't physically rotate when the OS accepts a portrait
// request, so the landscape-designed setup layout gets letterboxed into a squeezed
// portrait sliver in the middle of the panel — the pairing code then char-wraps one
// digit per line and runs off the card, unreadable (field-confirmed on a 1280x720
// Realtek panel). These screens take the panel's NATIVE landscape orientation instead;
// only PLAYBACK content follows the portrait preference, via
// PlaybackActivity.applyContentRotation() (which software-rotates so it actually fills
// a non-rotating panel). Safe-wrapped like applyOrientationPref for the same OEM reason.
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
