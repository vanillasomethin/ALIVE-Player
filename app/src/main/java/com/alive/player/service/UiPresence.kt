package com.alive.player.service

/**
 * Is the playback screen on the TV right now? Set by PlaybackActivity's onStart/onStop.
 *
 * Why it exists: after a power cut some TVs (a Selenview/HiSilicon box seen in the field)
 * start their OWN launcher themselves instead of going through the Home intent, so the
 * persistent HOME claim never gets a say and the playback service runs behind the
 * launcher's rows of app tiles. "We are the resolved Home app" is true there and proves
 * nothing about what is on screen; this flag does.
 */
object UiPresence {
    @Volatile
    var playbackVisible: Boolean = false
}
