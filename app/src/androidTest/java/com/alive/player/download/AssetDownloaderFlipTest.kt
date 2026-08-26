package com.alive.player.download

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alive.player.data.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.ServerSocket
import java.security.MessageDigest
import kotlin.concurrent.thread

/**
 * On-device coverage for the two md5-flip cache behaviours: download() must reclaim
 * superseded sibling files the moment a new hash lands (they have no DB row, so no
 * other cleaner can ever find them), and newestCompleteCachedFile() must serve the
 * previous rendition while the new hash is still downloading.
 *
 * Runs against the app's real cache dir and Room DB under a throwaway contentId; the
 * "server" is a one-shot localhost socket so the test needs no network at all.
 */
@RunWith(AndroidJUnit4::class)
class AssetDownloaderFlipTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val contentId = "instr-test-md5-flip"
    private val version = "current"

    private fun versionDir(): File =
        File(context.getExternalFilesDir("cache") ?: context.cacheDir, "assets/$contentId/$version")

    private fun md5Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Serve [bytes] to exactly one HTTP GET on an ephemeral localhost port. */
    private fun serveOnce(bytes: ByteArray): Int {
        val server = ServerSocket(0)
        thread(isDaemon = true) {
            server.use { srv ->
                srv.accept().use { sock ->
                    val input = sock.getInputStream().bufferedReader()
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break   // end of request headers
                    }
                    sock.getOutputStream().use { out ->
                        out.write(
                            ("HTTP/1.1 200 OK\r\n" +
                                "Content-Length: ${bytes.size}\r\n" +
                                "Connection: close\r\n\r\n").toByteArray()
                        )
                        out.write(bytes)
                        out.flush()
                    }
                }
            }
        }
        return server.localPort
    }

    @After
    fun cleanup() = runBlocking {
        versionDir().parentFile?.deleteRecursively()
        AppDatabase.get(context).assetDao().delete(contentId, version)
    }

    @Test
    fun downloadReclaimsSupersededSiblings() = runBlocking {
        val dir = versionDir().apply { mkdirs() }
        // The pre-flip state: an old rendition's file (its DB row is about to be
        // REPLACEd away) and an abandoned staging file from an interrupted download.
        File(dir, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.mp4").writeBytes(ByteArray(64) { 1 })
        File(dir, "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb.mp4.part").writeBytes(ByteArray(16) { 2 })

        val newBytes = "new-rendition-after-md5-flip".toByteArray()
        val newMd5 = md5Hex(newBytes)
        val port = serveOnce(newBytes)

        val result = AssetDownloader(context)
            .download(contentId, version, newMd5, "http://127.0.0.1:$port/v.mp4", "mp4")

        assertNotNull("download must succeed", result)
        assertEquals("$newMd5.mp4", result!!.name)
        assertEquals(
            "only the just-downloaded file may remain in the version dir",
            listOf("$newMd5.mp4"),
            dir.listFiles()!!.map { it.name },
        )
        val row = AppDatabase.get(context).assetDao().get(contentId, version)
        assertNotNull("upsert must have recorded the new file", row)
        assertEquals(result.absolutePath, row!!.path)
    }

    @Test
    fun newestCompleteCachedFileServesPreviousRenditionDuringFlip() {
        val dir = versionDir().apply { mkdirs() }
        val older = File(dir, "cccccccccccccccccccccccccccccccc.mp4").apply {
            writeBytes(ByteArray(32) { 3 })
            setLastModified(1_000_000L)
        }
        val newer = File(dir, "dddddddddddddddddddddddddddddddd.mp4").apply {
            writeBytes(ByteArray(32) { 4 })
            setLastModified(2_000_000L)
        }
        // Must never be picked: in-flight staging file and an empty (invalid) file.
        File(dir, "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee.mp4.part").apply {
            writeBytes(ByteArray(8) { 5 })
            setLastModified(3_000_000L)
        }
        File(dir, "ffffffffffffffffffffffffffffffff.mp4").apply {
            writeBytes(ByteArray(0))
            setLastModified(4_000_000L)
        }

        assertEquals(
            newer,
            AssetDownloader.newestCompleteCachedFile(context, contentId, version),
        )

        newer.delete()
        assertEquals(
            older,
            AssetDownloader.newestCompleteCachedFile(context, contentId, version),
        )

        older.delete()
        assertNull(
            "nothing complete on disk ⇒ no fallback (caller streams)",
            AssetDownloader.newestCompleteCachedFile(context, contentId, version),
        )
        assertTrue(dir.listFiles()!!.isNotEmpty())  // .part + empty file still there, correctly ignored
    }
}
