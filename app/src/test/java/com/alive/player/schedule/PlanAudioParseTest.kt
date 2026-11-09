package com.alive.player.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlanAudioParseTest {
    private fun plan(extra: String) = """
        {"items":[],"fallback":[{"contentId":"c1","objectKey":"k","url":"https://x/a.jpg","md5":"m","type":"IMAGE","durationMs":10000,"order":0$extra}]}
    """.trimIndent()

    @Test fun attachedTrackIsParsed() {
        val item = parsePlan(plan(""","audioUrl":"https://x/content/audio/c1-1.mp3","audioMd5":"abc"""")).fallbackOrFirst()
        assertEquals("https://x/content/audio/c1-1.mp3", item.audioUri)
        assertEquals("abc", item.audioSha)
        assertEquals("mp3", item.audioExt)
    }

    @Test fun noTrackMeansNoAudio() {
        val item = parsePlan(plan("")).fallbackOrFirst()
        assertNull(item.audioUri)
        assertNull(item.audioSha)
        assertNull(item.audioExt)
    }

    private fun Plan.fallbackOrFirst(): PlanItem = windows.flatMap { it.items }.firstOrNull() ?: fallbackItems.first()
}
