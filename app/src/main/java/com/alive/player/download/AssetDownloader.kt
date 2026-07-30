package com.alive.player.download

import android.content.Context
import android.os.StatFs
import com.alive.player.data.AppDatabase
import com.alive.player.data.Asset
import com.alive.player.settings.DevicePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class AssetDownloader(private val context: Context) {

    private fun cacheRoot(): File =
        context.getExternalFilesDir("cache") ?: context.cacheDir

    private fun finalFile(contentId: String, version: String, sha256: String, ext: String): File =
        File(cacheRoot(), "assets/$contentId/$version/$sha256.$ext")

    // Staged NEXT TO the final file, not in a separate tmp/ dir, so the promotion is an
    // atomic same-filesystem rename. The old cross-directory staging could fall back to
    // copyTo(), and a copy that died partway (disk full is routine on cheap TV boxes)
    // left a truncated file sitting at the final path. Nothing ever re-verified it —
    // getCachedFile() trusts the filename — so ExoPlayer would play the partial file and
    // freeze at exactly the byte where the copy stopped.
    private fun tmpFile(contentId: String, version: String, sha256: String, ext: String): File =
        File(finalFile(contentId, version, sha256, ext).parentFile, "$sha256.$ext.part")

    suspend fun download(
        contentId: String,
        version: String,
        sha256: String,
        uri: String,
        ext: String,
    ): File? = withContext(Dispatchers.IO) {
        val final = finalFile(contentId, version, sha256, ext)
        if (final.exists() && hashMatches(final, sha256)) {
            AppDatabase.get(context).assetDao()
                .touch(contentId, version, System.currentTimeMillis())
            return@withContext final
        }

        val tmp = tmpFile(contentId, version, sha256, ext)
        tmp.parentFile?.mkdirs()

        val resumeOffset = if (tmp.exists()) tmp.length() else 0L

        val conn = URL(uri).openConnection() as HttpURLConnection
        try {
            val prefs = DevicePrefs(context)
            conn.connectTimeout = prefs.getDownloadConnectTimeoutMs()
            conn.readTimeout = prefs.getDownloadReadTimeoutMs()
            if (resumeOffset > 0) {
                conn.setRequestProperty("Range", "bytes=$resumeOffset-")
            }
            conn.connect()

            val responseCode = conn.responseCode

            // Pre-flight space check: refuse download if free space < content + 50 MB buffer
            val contentLength = conn.contentLengthLong.takeIf { it > 0 } ?: 0L
            if (contentLength > 0) {
                val stat = StatFs(tmp.parentFile?.absolutePath ?: cacheRoot().path)
                val freeBytes = stat.availableBlocksLong * stat.blockSizeLong
                if (freeBytes < contentLength + 50L * 1024 * 1024) {
                    return@withContext null
                }
                AppDatabase.get(context).downloadJobDao()
                    .updateSize("${contentId}_$version", contentLength)
            }

            when {
                responseCode == HttpURLConnection.HTTP_PARTIAL && resumeOffset > 0 -> {
                    // Server honoured the Range header — append to existing tmp
                    conn.inputStream.use { FileOutputStream(tmp, true).use { out -> it.copyTo(out) } }
                }
                responseCode == HttpURLConnection.HTTP_OK -> {
                    // Full response (first download, or server ignored Range) — overwrite
                    tmp.parentFile?.mkdirs()
                    conn.inputStream.use { tmp.outputStream().use { out -> it.copyTo(out) } }
                }
                else -> return@withContext null
            }
        } catch (_: Exception) {
            tmp.delete()
            return@withContext null
        } finally {
            conn.disconnect()
        }

        if (!hashMatches(tmp, sha256)) {
            tmp.delete()
            return@withContext null
        }

        final.parentFile?.mkdirs()
        // Same directory as the staging file, so this is an atomic same-filesystem rename:
        // the final path either doesn't exist or holds the complete, hash-verified file.
        // Never fall back to a copy — a partial copy at the final path is unplayable and
        // undetectable (see tmpFile()).
        if (!tmp.renameTo(final)) {
            tmp.delete()
            return@withContext null
        }

        val db = AppDatabase.get(context)
        db.assetDao().upsert(
            Asset(
                contentId = contentId,
                version = version,
                sha256 = sha256,
                path = final.absolutePath,
                sizeBytes = final.length(),
                lastAccessedEpochMs = System.currentTimeMillis(),
            )
        )
        db.downloadJobDao().delete("${contentId}_$version")

        final
    }

    suspend fun evictLru(maxBytes: Long = 2L * 1024 * 1024 * 1024) = withContext(Dispatchers.IO) {
        val db = AppDatabase.get(context)
        val assets = db.assetDao().allByLru()
        var total = assets.sumOf { it.sizeBytes }
        for (asset in assets) {
            if (total <= maxBytes) break
            File(asset.path).delete()
            db.assetDao().delete(asset.contentId, asset.version)
            total -= asset.sizeBytes
        }
    }

    companion object {
        fun cachedFileFor(context: Context, contentId: String, version: String, sha256: String, ext: String): File {
            val cacheRoot = context.getExternalFilesDir("cache") ?: context.cacheDir
            return File(cacheRoot, "assets/$contentId/$version/$sha256.$ext")
        }

        fun getCachedFile(
            context: Context,
            contentId: String,
            version: String,
            sha256: String,
            ext: String,
        ): File? {
            val file = cachedFileFor(context, contentId, version, sha256, ext)
            // The hash is embedded in the filename and was verified during download, and
            // promotion to this path is an atomic rename — so a present file is complete.
            // Re-hashing on every play call would block the main thread for large videos.
            // A zero-length file is still rejected: it can't be valid media, and returning
            // it would make ExoPlayer fail instead of falling through to a re-download.
            return if (file.exists() && file.length() > 0) file else null
        }

        /**
         * Drop a cached file that playback could not decode (stalled or errored), so the
         * next plan fetch re-downloads it with a fresh hash check. Recovers devices whose
         * cache was corrupted by the pre-atomic-rename partial-copy bug.
         */
        fun evictCorrupt(context: Context, contentId: String, version: String, sha256: String, ext: String) {
            runCatching { cachedFileFor(context, contentId, version, sha256, ext).delete() }
        }

        /**
         * Verify file integrity. Supports both MD5 (32 hex chars, from studio) and
         * SHA-256 (64 hex chars) based on the length of the expected hash string.
         */
        private fun hashMatches(file: File, expected: String): Boolean {
            val algorithm = if (expected.length <= 32) "MD5" else "SHA-256"
            val digest = MessageDigest.getInstance(algorithm)
            file.inputStream().use { input ->
                val buf = ByteArray(8 * 1024)
                var n: Int
                while (input.read(buf).also { n = it } != -1) {
                    digest.update(buf, 0, n)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            return actual.equals(expected, ignoreCase = true)
        }
    }
}
