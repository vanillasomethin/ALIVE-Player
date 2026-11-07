package com.alive.player.ui

import com.alive.player.settings.DevicePrefs
import org.junit.Assert.assertEquals
import org.junit.Test

/** The rule the boot logo and the content containers share: turn relative to the panel. */
class SignageRotationTest {
    @Test fun `portrait on a landscape panel turns a quarter`() =
        assertEquals(90f, signageRotationFor(DevicePrefs.ORIENTATION_PORTRAIT, true), 0f)

    @Test fun `portrait on a panel that is already portrait does not turn`() =
        assertEquals(0f, signageRotationFor(DevicePrefs.ORIENTATION_PORTRAIT, false), 0f)

    @Test fun `reverse portrait turns the other way`() {
        assertEquals(270f, signageRotationFor(DevicePrefs.ORIENTATION_REVERSE_PORTRAIT, true), 0f)
        assertEquals(180f, signageRotationFor(DevicePrefs.ORIENTATION_REVERSE_PORTRAIT, false), 0f)
    }

    @Test fun `landscape on a landscape panel does not turn`() =
        assertEquals(0f, signageRotationFor(DevicePrefs.ORIENTATION_LANDSCAPE, true), 0f)

    @Test fun `an unknown mode does not turn`() =
        assertEquals(0f, signageRotationFor("whatever", true), 0f)
}
