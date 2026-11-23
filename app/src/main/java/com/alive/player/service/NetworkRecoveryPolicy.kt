package com.alive.player.service

/**
 * When a screen that has lost its internet should act on its own.
 *
 * Every remote command (FCM, the heartbeat, Tailscale) needs the TV's internet, so a TV whose
 * Wi-Fi has wedged cannot be told to fix itself: it has to notice. This is the whole decision,
 * kept free of Android so the escalation and its caps are unit-tested.
 *
 * Escalation: offline [WIFI_RESET_AFTER_MS] -> switch Wi-Fi off and on; still offline
 * [REBOOT_AFTER_MS] -> reboot the TV (a power cut by software, what the plug would do). Both
 * need Device Owner; without it nothing is attempted. A reboot is capped at [MAX_REBOOTS] per
 * [REBOOT_WINDOW_MS], persisted by the caller across the reboot, so a shop whose router is
 * simply down is not rebooted in a loop.
 */
object NetworkRecoveryPolicy {
    const val WIFI_RESET_AFTER_MS = 10 * 60_000L
    const val WIFI_RESET_COOLDOWN_MS = 15 * 60_000L
    const val REBOOT_AFTER_MS = 30 * 60_000L
    const val REBOOT_WINDOW_MS = 24 * 3_600_000L
    const val MAX_REBOOTS = 2

    enum class Action { NONE, RESET_WIFI, REBOOT }

    data class State(
        /** When the current outage began, or null while online. In memory only. */
        val offlineSinceMs: Long? = null,
        val lastWifiResetMs: Long = 0L,
        /** Times of reboots this policy triggered, newest last. Persisted by the caller. */
        val rebootTimesMs: List<Long> = emptyList(),
    )

    data class Step(val state: State, val action: Action)

    fun next(state: State, nowMs: Long, online: Boolean, deviceOwner: Boolean): Step {
        if (online) return Step(state.copy(offlineSinceMs = null), Action.NONE)
        val since = state.offlineSinceMs ?: nowMs
        val s = state.copy(
            offlineSinceMs = since,
            rebootTimesMs = state.rebootTimesMs.filter { nowMs - it < REBOOT_WINDOW_MS },
        )
        if (!deviceOwner) return Step(s, Action.NONE)
        val offlineFor = nowMs - since
        if (offlineFor >= REBOOT_AFTER_MS && s.rebootTimesMs.size < MAX_REBOOTS) {
            return Step(s.copy(rebootTimesMs = s.rebootTimesMs + nowMs), Action.REBOOT)
        }
        if (offlineFor >= WIFI_RESET_AFTER_MS && nowMs - s.lastWifiResetMs >= WIFI_RESET_COOLDOWN_MS) {
            return Step(s.copy(lastWifiResetMs = nowMs), Action.RESET_WIFI)
        }
        return Step(s, Action.NONE)
    }
}
