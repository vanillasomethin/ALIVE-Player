package com.alive.player.download

import android.content.Context
import android.os.StatFs
import com.alive.player.data.AppDatabase
import com.alive.player.data.Asset
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

    private fun tmpFile(contentId: String, version: String): File =
        File(cacheRoot(), "tmp/${contentId}_${version}.tmp")

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

        val tmp = tmpFile(contentId, version)
        tmp.parentFile?.mkdirs()

        val resumeOffset = if (tmp.exists()) tmp.length() else 0L

        val conn = URL(uri).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 30_000
            conn.readTimeout = 60_000
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
        // renameTo can fail cross-filesystem (e.g. external→internal); fall back to copy+delete
        if (!tmp.renameTo(final)) {
            try {
                tmp.copyTo(final, overwrite = true)
                tmp.delete()
            } catch (_: Exception) {
                tmp.delete()
                return@withContext null
            }
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
        fun getCachedFile(
            context: Context,
            contentId: String,
            version: String,
            sha256: String,
            ext: String,
        ): File? {
            val cacheRoot = context.getExternalFilesDir("cache") ?: context.cacheDir
            val file = File(cacheRoot, "assets/$contentId/$version/$sha256.$ext")
            // The hash is embedded in the filename and was verified during download.
            // Re-hashing the file on every play call blocks the main thread for large videos.
            return if (file.exists()) file else null
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
