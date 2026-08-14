package com.alive.player.worker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression test for the stuck "Schedule synced — starting soon" card: a device with
 * an empty plan cached (screen never assigned a schedule) gets 304 Not Modified on
 * every poll, and the notModified branch used to report OK forever. The 304 path must
 * classify the *cached* body with the same rule the 200 path applies to a fresh one.
 */
class PlanJsonHasNoItemsTest {

    @Test
    fun `empty studio plan has no items`() {
        // Exact shape observed cached on the Foxsky repro device 2026-08-14
        assertTrue(planJsonHasNoItems("""{"scheduleId":null,"items":[],"timeline":[]}"""))
    }

    @Test
    fun `plan with items is not empty`() {
        val json = """
            {
              "scheduleId": "sch_1",
              "items": [{
                "contentId": "c1", "objectKey": "k1", "url": "https://cdn/x.mp4",
                "md5": "abc", "type": "video", "durationMs": 5000, "order": 0
              }],
              "timeline": []
            }
        """.trimIndent()
        assertFalse(planJsonHasNoItems(json))
    }

    @Test
    fun `missing items key counts as empty like the fresh-fetch path`() {
        assertTrue(planJsonHasNoItems("""{"windows":[],"fallback_items":[]}"""))
    }

    @Test
    fun `malformed json is not reported as no-schedule`() {
        assertFalse(planJsonHasNoItems("{not json"))
    }
}
