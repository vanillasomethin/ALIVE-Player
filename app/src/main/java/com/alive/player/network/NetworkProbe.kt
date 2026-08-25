package com.alive.player.network

import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Plain-HTTP probe, used for the two things HTTPS cannot tell us once its certificate
 * has been rejected.
 *
 * 1. **What is actually wrong.** A screen behind a filtering proxy and a screen with a
 *    drifted clock both fail with the same opaque
 *    `CertPathValidatorException: Trust anchor for certification path not found`. Plain
 *    HTTP has no certificates to reject, so it still gets an answer — and a `Via:`
 *    header or a `403` from something that is not our origin identifies an intercepting
 *    proxy outright. Observed in the field: a store network returned
 *    `403 Forbidden / Via: HTTP/1.1 forward.http.proxy:3128` for this device while other
 *    clients on the same subnet reached the origin directly.
 * 2. **The time.** The response carries a `Date:` header even on a 403 or a redirect,
 *    which recovers a device whose clock has drifted on a network that blocks NTP
 *    (UDP/123 blocked and HTTPS untrusted is a real combination — see NtpSyncManager).
 *
 * Redirects are deliberately not followed: `http://` → `https://` is the normal reply
 * from the origin, and the redirect response itself already carries everything needed.
 */
object NetworkProbe {

    data class Result(
        val statusCode: Int,
        /** Server's `Date:` as epoch ms, or 0 when absent/unparseable. */
        val serverDateMs: Long,
        /** Non-null when a proxy inserted itself — the single clearest interception signal. */
        val via: String?,
    ) {
        /**
         * True when something other than our origin answered. A `Via:` header is
         * conclusive; a bare 403 is included because a filtering proxy commonly denies
         * without identifying itself, and our origin has no reason to 403 a HEAD of `/`.
         */
        val looksIntercepted: Boolean get() = via != null || statusCode == 403
    }

    /**
     * Deliberately a raw socket rather than HttpURLConnection.
     *
     * targetSdk 28+ denies cleartext traffic by default, so an `http://` URL through
     * HttpURLConnection throws "Cleartext HTTP traffic not permitted" before a packet
     * is sent — the probe silently returned nothing on the very device it was written
     * to diagnose. The alternative, opening cleartext for our domain in a network
     * security config, would relax the policy for *all* code paths just to read one
     * response header. NetworkSecurityPolicy is enforced by the HTTP libraries, not the
     * socket layer, so speaking HTTP/1.1 directly keeps the app's TLS policy fully
     * locked down while still getting the answer.
     *
     * Only response headers are read; the request carries no credentials, and nothing
     * here is trusted beyond "who answered" and "what time do they think it is".
     */
    fun probe(baseUrl: String, timeoutMs: Int = 6_000): Result? {
        val host = runCatching { URL(baseUrl).host }.getOrNull() ?: return null
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, 80), timeoutMs)
                socket.soTimeout = timeoutMs
                socket.getOutputStream().apply {
                    write("HEAD / HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n".toByteArray())
                    flush()
                }

                val reader = socket.getInputStream().bufferedReader()
                val statusCode = reader.readLine()
                    ?.split(' ')?.getOrNull(1)?.toIntOrNull()
                    ?: return null

                var serverDateMs = 0L
                var via: String? = null
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) break            // end of headers
                    val separator = line.indexOf(':')
                    if (separator <= 0) continue
                    val name = line.substring(0, separator).trim().lowercase(Locale.US)
                    val value = line.substring(separator + 1).trim()
                    when (name) {
                        "date" -> serverDateMs = parseHttpDate(value)
                        "via"  -> via = value
                    }
                }
                Result(statusCode, serverDateMs, via)
            }
        } catch (_: Exception) {
            null
        }
    }

    /** RFC 1123, e.g. "Tue, 11 Aug 2026 06:35:53 GMT". 0 when unparseable. */
    private fun parseHttpDate(value: String): Long = runCatching {
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("GMT") }
            .parse(value)?.time ?: 0L
    }.getOrDefault(0L)
}
