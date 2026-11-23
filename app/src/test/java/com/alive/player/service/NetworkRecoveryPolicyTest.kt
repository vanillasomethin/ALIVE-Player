package com.alive.player.service

import com.alive.player.service.NetworkRecoveryPolicy.Action
import com.alive.player.service.NetworkRecoveryPolicy.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkRecoveryPolicyTest {
    private val min = 60_000L
    private val t0 = 1_000_000_000L

    private fun offlineFor(minutes: Long, s: State = State(offlineSinceMs = t0), owner: Boolean = true) =
        NetworkRecoveryPolicy.next(s, t0 + minutes * min, online = false, deviceOwner = owner)

    @Test fun `online does nothing and clears the outage`() {
        val step = NetworkRecoveryPolicy.next(State(offlineSinceMs = t0), t0 + 99 * min, online = true, deviceOwner = true)
        assertEquals(Action.NONE, step.action)
        assertNull(step.state.offlineSinceMs)
    }

    @Test fun `first offline sample starts the clock and acts on nothing`() {
        val step = NetworkRecoveryPolicy.next(State(), t0, online = false, deviceOwner = true)
        assertEquals(Action.NONE, step.action)
        assertEquals(t0, step.state.offlineSinceMs)
    }

    @Test fun `a blip under ten minutes is left alone`() = assertEquals(Action.NONE, offlineFor(9).action)
    @Test fun `ten minutes offline resets wifi`() = assertEquals(Action.RESET_WIFI, offlineFor(10).action)

    @Test fun `wifi is not reset again inside the cooldown`() {
        val first = offlineFor(10)
        val again = NetworkRecoveryPolicy.next(first.state, t0 + 20 * min, online = false, deviceOwner = true)
        assertEquals(Action.NONE, again.action)
    }

    @Test fun `wifi is retried after the cooldown`() {
        val first = offlineFor(10)
        val again = NetworkRecoveryPolicy.next(first.state, t0 + 25 * min, online = false, deviceOwner = true)
        assertEquals(Action.RESET_WIFI, again.action)
    }

    @Test fun `thirty minutes offline reboots`() = assertEquals(Action.REBOOT, offlineFor(30).action)

    @Test fun `reboots are capped per day`() {
        val s = State(offlineSinceMs = t0, rebootTimesMs = listOf(t0 - 3_600_000L, t0 - 7_200_000L))
        assertEquals(Action.RESET_WIFI, offlineFor(30, s).action)
    }

    @Test fun `old reboots fall out of the window`() {
        val s = State(offlineSinceMs = t0, rebootTimesMs = listOf(t0 - 25 * 3_600_000L, t0 - 26 * 3_600_000L))
        assertEquals(Action.REBOOT, offlineFor(30, s).action)
    }

    @Test fun `without device owner nothing is attempted`() {
        assertEquals(Action.NONE, offlineFor(45, owner = false).action)
        assertEquals(Action.NONE, offlineFor(10, owner = false).action)
    }

    @Test fun `a reboot is recorded so the cap sees it`() {
        assertEquals(1, offlineFor(30).state.rebootTimesMs.size)
    }
}
