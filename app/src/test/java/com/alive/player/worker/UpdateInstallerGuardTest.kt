package com.alive.player.worker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the semantics of the in-flight guard PREDICATE: it blocks only unattended
 * commits, and only for committed sessions younger than the window. An
 * operator-REQUESTED commit is never blocked (the old always-block behaviour made
 * the Settings Install button silently no-op for ten minutes after a swallowed
 * PENDING), while a silent commit is always subject to the guard — an operator
 * merely browsing Settings must not let the periodic worker bulldoze an in-flight
 * install (which is why the parameter is caller intent, not UpdateGate presence).
 *
 * Honest scope note: these tests pin inFlightGuardBlocks itself. That commit()
 * consults it with the caller's real intent, and that UpdateCheckWorker derives
 * intent as `!canAuto && gate`, is wired by inspection — commit() needs
 * Context/PackageInstaller and an SDK_INT >= R branch, which plain JVM tests
 * cannot reach with this project's JUnit-only test setup.
 */
class UpdateInstallerGuardTest {

    private val now = 1_756_800_000_000L // any fixed instant

    @Test
    fun `operator-requested install is never blocked, even by a fresh committed session`() {
        assertFalse(
            UpdateInstaller.inFlightGuardBlocks(
                operatorRequested = true,
                sessionCommitted = true,
                sessionCreatedMs = now - 1_000,
                nowMs = now,
            )
        )
    }

    @Test
    fun `silent commit is blocked by a fresh committed session — operator browsing changes nothing`() {
        // The silent path passes operatorRequested=false regardless of UpdateGate,
        // so "operator happens to be in Settings" and "unattended" are the same case
        // here by construction.
        assertTrue(
            UpdateInstaller.inFlightGuardBlocks(
                operatorRequested = false,
                sessionCommitted = true,
                sessionCreatedMs = now - 1_000,
                nowMs = now,
            )
        )
    }

    @Test
    fun `session older than the window is reclaimable, not in-flight`() {
        assertFalse(
            UpdateInstaller.inFlightGuardBlocks(
                operatorRequested = false,
                sessionCommitted = true,
                sessionCreatedMs = now - UpdateInstaller.IN_FLIGHT_WINDOW_MS - 1,
                nowMs = now,
            )
        )
    }

    @Test
    fun `uncommitted session never blocks regardless of age or intent`() {
        assertFalse(
            UpdateInstaller.inFlightGuardBlocks(
                operatorRequested = false,
                sessionCommitted = false,
                sessionCreatedMs = now - 1_000,
                nowMs = now,
            )
        )
    }
}
