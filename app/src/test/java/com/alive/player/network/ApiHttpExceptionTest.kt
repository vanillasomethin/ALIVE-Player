package com.alive.player.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decommission wipe must fire only on a 410 whose body the studio itself
 * produced. Bodies here are verbatim copies of what the real endpoints answer
 * (plan/update-check: NextResponse.json({ error: 'Device deleted' }); events:
 * the respond() envelope) and of what Vercel's platform answers when the
 * deployment in front of those endpoints is broken.
 */
class ApiHttpExceptionTest {

    private fun ex(code: Int, body: String?) = ApiHttpException(code, "msg", body)

    @Test
    fun `plan and update-check style body decommissions`() {
        assertTrue(ex(410, """{"error":"Device deleted"}""").isDecommission)
    }

    @Test
    fun `events envelope body decommissions`() {
        val envelope = """{"data":{"error":"Device deleted"},"meta":{"route":"/api/device/events",""" +
            """"outcome":"unauthorized","timestamp":"2026-08-17T00:00:00.000Z"},"learningArtifactRef":null}"""
        assertTrue(ex(410, envelope).isDecommission)
    }

    @Test
    fun `whitespace around the marker still matches`() {
        assertTrue(ex(410, """{ "error" : "Device deleted" }""").isDecommission)
    }

    @Test
    fun `vercel platform 410 bodies do not decommission`() {
        // Vercel's JSON error shape: error is an object, not the marker string.
        assertFalse(
            ex(410, """{"error":{"code":"DEPLOYMENT_DELETED","message":"The deployment has been removed."}}""")
                .isDecommission
        )
        // Vercel's HTML error page.
        assertFalse(
            ex(410, "<html><body>410: The deployment has been removed.</body></html>").isDecommission
        )
    }

    @Test
    fun `empty or missing body does not decommission`() {
        assertFalse(ex(410, "").isDecommission)
        assertFalse(ex(410, null).isDecommission)
    }

    @Test
    fun `marker on a non-410 status does not decommission`() {
        assertFalse(ex(401, """{"error":"Device deleted"}""").isDecommission)
        assertFalse(ex(200, """{"error":"Device deleted"}""").isDecommission)
    }
}
