package com.alive.player.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteAccessTest {

    @Test fun tailscaleRangeIsCgnatOnly() {
        assertTrue(RemoteAccess.isTailscaleAddress("100.64.0.1"))
        assertTrue(RemoteAccess.isTailscaleAddress("100.127.255.254"))
        assertFalse(RemoteAccess.isTailscaleAddress("100.63.0.1"))
        assertFalse(RemoteAccess.isTailscaleAddress("100.128.0.1"))
        assertFalse(RemoteAccess.isTailscaleAddress("192.168.1.10"))
        assertFalse(RemoteAccess.isTailscaleAddress("100.64.0"))
        assertFalse(RemoteAccess.isTailscaleAddress("100.64.0.256"))
    }

    @Test fun volumeMapsOntoStreamSteps() {
        assertEquals(0, RemoteAccess.levelFor(0, 15))
        assertEquals(15, RemoteAccess.levelFor(100, 15))
        assertEquals(8, RemoteAccess.levelFor(50, 15))
        assertEquals(15, RemoteAccess.levelFor(250, 15))
        assertEquals(0, RemoteAccess.levelFor(-5, 15))
    }
}
