package com.alive.player.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.util.Log
import com.alive.player.settings.DevicePrefs

private const val TAG = "OrientationExt"

fun Activity.applyOrientationPref() {
    setRequestedOrientationSafely(orientationConstant(DevicePrefs(this).getOrientationMode()))
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
