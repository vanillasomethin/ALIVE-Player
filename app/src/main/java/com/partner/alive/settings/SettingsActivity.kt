package com.partner.alive.settings

import android.app.Activity
import android.os.Bundle
import com.partner.alive.ui.applyOrientationPref

class SettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        applyOrientationPref()
        super.onCreate(savedInstanceState)
        fragmentManager.beginTransaction()
            .replace(android.R.id.content, SettingsFragment())
            .commit()
    }
}
