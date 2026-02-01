package com.alive.player.service

import android.app.Service
import android.content.Intent
import android.os.IBinder

class PlaybackForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // TODO: start foreground, hold wake lock, and own playback session.
        return START_STICKY
    }
}
