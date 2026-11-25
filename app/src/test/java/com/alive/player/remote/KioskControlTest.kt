package com.alive.player.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The return time on a remote exit: in range or nothing happens, never a default. */
class KioskControlTest {
    @Test fun `a time inside the range is used`() = assertEquals(30, KioskControl.exitMinutes("30"))
    @Test fun `the lower edge is allowed`() = assertEquals(5, KioskControl.exitMinutes("5"))
    @Test fun `the upper edge is allowed`() = assertEquals(240, KioskControl.exitMinutes("240"))
    @Test fun `just under the lower edge is ignored`() = assertNull(KioskControl.exitMinutes("4"))
    @Test fun `just over the upper edge is ignored`() = assertNull(KioskControl.exitMinutes("241"))
    @Test fun `zero is ignored, never stay out for good`() = assertNull(KioskControl.exitMinutes("0"))
    @Test fun `a negative is ignored`() = assertNull(KioskControl.exitMinutes("-30"))
    @Test fun `a missing time is ignored, not defaulted`() = assertNull(KioskControl.exitMinutes(null))
    @Test fun `text is ignored`() = assertNull(KioskControl.exitMinutes("soon"))
    @Test fun `a fraction is ignored`() = assertNull(KioskControl.exitMinutes("30.5"))

    // The exit must not "land" on Android's placeholder home, which just relaunches us.
    @Test fun `the placeholder home is recognised`() =
        assertTrue(KioskControl.isPlaceholderHome("com.android.tv.settings.system.FallbackHome"))
    @Test fun `a real launcher is not the placeholder`() =
        assertFalse(KioskControl.isPlaceholderHome("com.google.android.apps.tv.launcherx.home.HomeActivity"))
    @Test fun `a missing class name is not the placeholder`() = assertFalse(KioskControl.isPlaceholderHome(null))
}
