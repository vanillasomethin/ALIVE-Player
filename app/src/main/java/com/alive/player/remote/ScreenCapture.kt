package com.alive.player.remote

import android.app.Activity
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Screenshot of what this screen is showing, for an admin `screenshot` command.
 * PixelCopy reads the playback window as composited, so the video (drawn into a
 * TextureView) is in the picture; no screen-capture permission or ADB is needed,
 * because the app only ever copies its own window.
 */
object ScreenCapture {

    private var current: WeakReference<Activity>? = null

    /** PlaybackActivity registers itself while it is the screen's window. */
    fun attach(activity: Activity) { current = WeakReference(activity) }
    fun detach(activity: Activity) { if (current?.get() === activity) current = null }

    /** A JPEG of the playback window, at most [maxWidth] wide, or null when nothing is showing. */
    suspend fun captureJpeg(maxWidth: Int = 1280, quality: Int = 70): ByteArray? {
        val activity = current?.get()?.takeIf { !it.isFinishing && !it.isDestroyed } ?: return null
        val window = activity.window ?: return null
        val view = window.peekDecorView() ?: return null
        if (view.width <= 0 || view.height <= 0) return null
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val ok = suspendCancellableCoroutine { cont ->
            runCatching {
                PixelCopy.request(window, bitmap, { result -> if (cont.isActive) cont.resume(result == PixelCopy.SUCCESS) }, Handler(Looper.getMainLooper()))
            }.onFailure { if (cont.isActive) cont.resume(false) }
        }
        if (!ok) { bitmap.recycle(); return null }
        val scaled = if (bitmap.width > maxWidth) {
            Bitmap.createScaledBitmap(bitmap, maxWidth, bitmap.height * maxWidth / bitmap.width, true).also { bitmap.recycle() }
        } else bitmap
        return ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
            scaled.recycle()
            out.toByteArray()
        }
    }
}
