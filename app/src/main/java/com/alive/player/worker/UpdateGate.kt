package com.alive.player.worker

/**
 * True only while SettingsActivity is resumed — i.e. an operator is physically at the
 * device. The install-confirm dialog for an OTA update may ONLY be launched while this
 * is true; during unattended kiosk playback a pending update must never steal the
 * screen (see UpdateInstallReceiver / UpdateCheckWorker).
 */
object UpdateGate {
    @Volatile var userActionAllowed = false
}
