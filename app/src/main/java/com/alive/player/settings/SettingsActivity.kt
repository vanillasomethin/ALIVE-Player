package com.alive.player.settings

import android.app.Activity
import android.os.Bundle
import com.alive.player.ui.applyOrientationPref
import com.alive.player.worker.UpdateGate

class SettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        applyOrientationPref()
        super.onCreate(savedInstanceState)
        fragmentManager.beginTransaction()
            .replace(android.R.id.content, SettingsFragment())
            .commit()
    }

    // An operator is looking at this screen — the only window in which the OTA
    // install-confirm dialog may be shown (see UpdateGate / UpdateInstallReceiver).
    // The gate closes in onPause, which includes the moment that very dialog opens
    // on top — by then it has already launched, so nothing is lost.
    override fun onResume() {
        super.onResume()
        UpdateGate.userActionAllowed = true
    }

    override fun onPause() {
        UpdateGate.userActionAllowed = false
        super.onPause()
    }
}
