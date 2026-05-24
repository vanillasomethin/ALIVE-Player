package com.partner.alive.worker

import android.content.Context

object UpdateScheduler {
    // Updates are now handled via Google Play In-App Updates (InAppUpdateHelper).
    // BootReceiver and PairingActivity call this; kept as a no-op so callers compile unchanged.
    fun schedule(@Suppress("UNUSED_PARAMETER") context: Context) = Unit
}
