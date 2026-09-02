package com.alive.player.service

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pins the property WatchdogService's ANR detection depends on: the persisted
 * heartbeat is the MAIN thread's last self-report, never the writer thread's own
 * clock. If someone "simplifies" the relay back into writeText(now()), a frozen
 * main thread looks alive forever and these fail.
 */
class ProcessHeartbeatTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun reset() = ProcessHeartbeat.resetForTest()

    @After
    fun cleanup() = ProcessHeartbeat.resetForTest()

    @Test
    fun `relay persists the main-thread stamp, not the write-time clock`() {
        val f = tmp.newFile()
        ProcessHeartbeat.noteMainThreadAliveForTest(1_000L)
        ProcessHeartbeat.writeMainThreadStamp(f)
        assertEquals(1_000L, f.readText().trim().toLong())
    }

    @Test
    fun `stamp does not advance across writes while the main thread is silent`() {
        // The ANR scenario: the IO writer keeps running, the main thread does not.
        val f = tmp.newFile()
        ProcessHeartbeat.noteMainThreadAliveForTest(1_000L)
        ProcessHeartbeat.writeMainThreadStamp(f)
        ProcessHeartbeat.writeMainThreadStamp(f)
        ProcessHeartbeat.writeMainThreadStamp(f)
        assertEquals(1_000L, f.readText().trim().toLong())
    }

    @Test
    fun `relay write before any stamp leaves the file untouched`() {
        // Must not write the epoch over whatever is already there — the watchdog
        // would read that as ancient and kill the process it was protecting.
        val f = tmp.newFile()
        f.writeText("123456")
        ProcessHeartbeat.writeMainThreadStamp(f)
        assertEquals("123456", f.readText())
    }

    @Test
    fun `grace write stamps the current clock`() {
        val f = tmp.newFile()
        val before = System.currentTimeMillis()
        ProcessHeartbeat.writeGraceStamp(f)
        val written = f.readText().trim().toLong()
        assertTrue(written >= before)
        assertFalse(written > System.currentTimeMillis())
    }

    @Test
    fun `grace write floors a frozen relay stamp`() {
        // Decommission racing an ANR: the grace promises the full stale threshold,
        // so the relay loop, still running until requestStop lands, must not be
        // able to replace the fresh grace with the frozen pre-ANR stamp.
        val f = tmp.newFile()
        ProcessHeartbeat.noteMainThreadAliveForTest(1_000L) // frozen long ago
        val before = System.currentTimeMillis()
        ProcessHeartbeat.writeGraceStamp(f)
        ProcessHeartbeat.writeMainThreadStamp(f) // relay fires after the grace
        assertTrue(f.readText().trim().toLong() >= before)
    }
}
