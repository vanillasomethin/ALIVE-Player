package com.alive.player.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import com.alive.player.settings.DevicePrefs

fun Activity.applyOrientationPref() {
    requestedOrientation = orientationConstant(DevicePrefs(this).getOrientationMode())
}

fun Activity.cycleOrientation() {
    val prefs = DevicePrefs(this)
    val next = when (prefs.getOrientationMode()) {
        DevicePrefs.ORIENTATION_PORTRAIT         -> DevicePrefs.ORIENTATION_REVERSE_PORTRAIT
        DevicePrefs.ORIENTATION_REVERSE_PORTRAIT -> DevicePrefs.ORIENTATION_PORTRAIT
        else                                     -> DevicePrefs.ORIENTATION_PORTRAIT
    }
    prefs.setOrientationMode(next)
    requestedOrientation = orientationConstant(next)
}

private fun orientationConstant(mode: String) = when (mode) {
    DevicePrefs.ORIENTATION_REVERSE_PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
    else                                     -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
}
