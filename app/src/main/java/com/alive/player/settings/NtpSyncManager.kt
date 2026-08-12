package com.alive.player.settings

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.provider.Settings
import com.alive.player.admin.AliveDeviceAdminReceiver
import com.alive.player.admin.OwnerSetup
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.math.abs

/**
 * Keeps the device's idea of "now" correct.
 *
 * This matters far more than timestamp accuracy: TLS certificates are only valid
 * inside a date window, so a screen whose system clock has drifted (budget TV panels
 * have no RTC battery — a power cut resets them to the firmware build date) fails
 * certificate validation on EVERY https call and goes completely dark. Observed in
 * the field on a Realtek panel that had reset to Jan 2025: every plan fetch died with
 * "CertPathValidatorException: Trust anchor for certification path not found", and the
 * TV's own Google services were failing handshakes too.
 *
 * NTP is plain UDP with no certificates, so it still works on such a device — which
 * makes it the one channel that can break the deadlock. Two rules follow, and both
 * were previously broken:
 *
 *  1. [syncIfNeeded] must run BEFORE the first server call, not after a successful
 *     one. The old code only synced after a successful plan fetch, so the devices
 *     that needed it most could never reach it.
 *  2. Storing an app-level offset is not enough — TLS validates against the SYSTEM
 *     clock, which an ordinary app cannot set. On the Device-Owner fleet we can:
 *     [correctSystemClock] pushes the true time into the system clock so HTTPS
 *     recovers by itself. Non-owner devices still get the offset (so proof-of-play
 *     timestamps stay honest) plus a clear operator-facing diagnosis via
 *     [isClockBadlyWrong], since only a human can fix the clock there.
 */
object NtpSyncManager {

    /**
     * Tried in order until one answers. pool.ntp.org is DNS round-robin over volunteer
     * servers and a large share of them simply do not reply — measured from a store
     * network, querying the pool by hostname timed out while the individual address it
     * had just resolved answered fine. Relying on it alone (as this did) meant the
     * sync silently failed most of the time, which is why a clock-broken screen never
     * recovered. The anycast services are tried first because they are single stable
     * addresses with no dead members.
     */
    private val NTP_HOSTS = listOf("time.google.com", "time.cloudflare.com", "pool.ntp.org")
    private const val NTP_PORT = 123
    /** Per-address budget. Kept short because several addresses may be tried. */
    private const val QUERY_TIMEOUT_MS = 3_000
    /** Bounds worst-case wall time when everything is unreachable (hosts × this × timeout). */
    private const val MAX_ADDRESSES_PER_HOST = 2
    // NTP epoch (Jan 1, 1900) → Unix epoch (Jan 1, 1970) in seconds
    private const val OFFSET_1900_TO_1970 = ((365L * 70) + 17) * 24 * 60 * 60

    /** Re-sync no more often than this on a healthy device (pool.ntp.org etiquette). */
    private const val SYNC_INTERVAL_MS = 6L * 60 * 60 * 1000

    /**
     * Drift beyond this is treated as "the clock is wrong", not jitter. Two minutes is
     * comfortably inside every certificate validity window yet far above normal skew.
     */
    private const val MAX_TOLERATED_DRIFT_MS = 2L * 60 * 1000

    /**
     * The HTTP Date fallback may only overwrite the system clock when it is wrong by
     * more than this. Deliberately coarse: that source is unauthenticated and only
     * second-accurate, so it is for rescuing a clock that has reset to the firmware
     * date (months out), not for correcting ordinary skew.
     */
    private const val HTTP_CORRECTION_THRESHOLD_MS = 60L * 60 * 1000

    /**
     * Sync unless a healthy sync happened recently. [force] bypasses the interval —
     * callers pass it when the last server call failed, which is exactly when a
     * clock problem is worth re-checking immediately.
     */
    fun syncIfNeeded(context: Context, force: Boolean = false) {
        val prefs = DevicePrefs(context)
        val sinceLast = System.currentTimeMillis() - prefs.getLastNtpSyncMs()

        // A large stored offset is never throttled. It means one of two things and both
        // want re-checking: the clock is still wrong (keep trying to correct it), or
        // something else corrected it and our offset is now stale. The latter is not
        // theoretical — during field testing Android's own time service fixed a panel's
        // clock, which would have left a 586-day offset in effect for the whole throttle
        // window and thrown every proof-of-play timestamp 19 months into the future.
        val offsetSuspect = abs(prefs.getClockOffsetMs()) > MAX_TOLERATED_DRIFT_MS

        // sinceLast < 0 means the clock jumped backwards since the last sync — never
        // skip in that case, it is itself a symptom of the drift this guards against.
        if (!force && !offsetSuspect && sinceLast in 0 until SYNC_INTERVAL_MS) return
        sync(context)
    }

    /**
     * Query NTP, store the offset, and on Device-Owner devices correct the system
     * clock when it is badly wrong. No-ops silently on network failure; the cached
     * offset stays in effect.
     */
    fun sync(context: Context) {
        // NTP first (authoritative, millisecond-accurate), then the HTTP Date header.
        // The fallback exists because NTP is not always reachable: a store network was
        // measured blocking UDP/123 outright while still answering plain HTTP, so
        // NTP-only recovery would have left that screen stuck forever.
        val fromNtp = query()
        val (serverTimeMs, requestTimeMs, rttMs) = fromNtp ?: httpDateQuery() ?: return

        val clockOffsetMs = serverTimeMs - requestTimeMs - rttMs / 2

        // How much the sample is worth trusting decides both what we store and whether
        // we overwrite the clock. NTP is accurate, so a couple of minutes is reason to
        // act on it. The HTTP Date header is unauthenticated, one-second granular, and
        // comes from whatever answered — a real proxy was measured 6.5 minutes adrift —
        // so it is only ever believed when it says the clock is grossly wrong. Believing
        // it about a small difference would make a healthy clock worse, not better:
        // every proof-of-play timestamp would inherit the proxy's own error.
        val trustThreshold =
            if (fromNtp != null) MAX_TOLERATED_DRIFT_MS else HTTP_CORRECTION_THRESHOLD_MS
        val clockIsGrosslyWrong = abs(clockOffsetMs) > trustThreshold

        val prefs = DevicePrefs(context)
        // Zero rather than a small coarse correction: below the threshold the system
        // clock is the better of the two sources, so defer to it outright.
        prefs.setClockOffsetMs(if (fromNtp != null || clockIsGrosslyWrong) clockOffsetMs else 0L)
        prefs.setLastNtpSyncMs(System.currentTimeMillis())

        if (clockIsGrosslyWrong) {
            correctSystemClock(context, trueTimeMs = serverTimeMs + rttMs / 2)
        }
    }

    /**
     * Last-resort time source: the `Date:` header of a plain-HTTP response, which
     * arrives even from a proxy's 403 and needs no certificate validation.
     *
     * Deliberately second choice, and only ever trusted to get the clock inside the
     * certificate validity window: it is unauthenticated (anything on the path can set
     * it) and has one-second granularity. Once it lets TLS succeed, a normal NTP sync
     * refines it. Not used to set the clock backwards past the tolerance either — the
     * same [MAX_TOLERATED_DRIFT_MS] gate applies to it as to NTP.
     */
    private fun httpDateQuery(): Triple<Long, Long, Long>? {
        val requestTimeMs = System.currentTimeMillis()
        val probe = com.alive.player.network.NetworkProbe.probe(
            com.alive.player.BuildConfig.API_BASE_URL
        ) ?: return null
        val responseTimeMs = System.currentTimeMillis()
        if (probe.serverDateMs <= 0) return null
        return Triple(probe.serverDateMs, requestTimeMs, responseTimeMs - requestTimeMs)
    }

    /** (server time, local send time, round-trip) from the first server that answers. */
    private fun query(): Triple<Long, Long, Long>? {
        for (host in NTP_HOSTS) {
            val addresses = runCatching { InetAddress.getAllByName(host) }.getOrNull() ?: continue
            // IPv4 first: the fleet's panels have link-local IPv6 only, so an AAAA
            // address would fail to route and burn a whole timeout for nothing.
            for (address in addresses.sortedBy { it is java.net.Inet6Address }
                .take(MAX_ADDRESSES_PER_HOST)) {
                queryOne(address)?.let { return it }
            }
        }
        return null
    }

    private fun queryOne(address: InetAddress): Triple<Long, Long, Long>? = try {
        val buffer = ByteArray(48)
        buffer[0] = 0x1B.toByte() // LI=0, VN=3, Mode=3 (client)

        DatagramSocket().use { socket ->
            socket.soTimeout = QUERY_TIMEOUT_MS
            val requestTimeMs = System.currentTimeMillis()
            socket.send(DatagramPacket(buffer, buffer.size, address, NTP_PORT))
            socket.receive(DatagramPacket(buffer, buffer.size))
            val responseTimeMs = System.currentTimeMillis()

            // Transmit timestamp is at bytes 40-47 (seconds + fraction)
            val seconds = buffer.readUint32Be(40) - OFFSET_1900_TO_1970
            val fraction = buffer.readUint32Be(44)
            val ntpTimeMs = seconds * 1000L + fraction * 1000L / 0x100000000L

            // A server that answers with a zero/garbage timestamp would otherwise be
            // taken as authoritative and could set the clock to 1900.
            if (seconds <= 0) null
            else Triple(ntpTimeMs, requestTimeMs, responseTimeMs - requestTimeMs)
        }
    } catch (_: Exception) {
        null // try the next address/host
    }

    /**
     * True when the clock is off by enough to break TLS. Used to replace the opaque
     * "Trust anchor for certification path not found" with something an operator on
     * site can act on. Reflects the last successful NTP sync.
     */
    fun isClockBadlyWrong(context: Context): Boolean =
        abs(DevicePrefs(context).getClockOffsetMs()) > MAX_TOLERATED_DRIFT_MS

    /** True "now" per the last NTP sync, regardless of the system clock. */
    fun now(context: Context): Long =
        System.currentTimeMillis() + DevicePrefs(context).getClockOffsetMs()

    fun nowIso(context: Context): String =
        java.time.Instant.ofEpochMilli(now(context)).toString()

    /**
     * Device-Owner-only: push the true time into the system clock so TLS starts
     * working again without a site visit. Silently unavailable elsewhere — an
     * ordinary app cannot hold SET_TIME.
     *
     * setTime() is refused while automatic time is enabled, so auto-time is turned
     * off first. That is not a regression on the affected devices: they reached this
     * state precisely because their firmware ships auto-time with no NTP server
     * configured, so it was never actually setting the clock.
     */
    private fun correctSystemClock(context: Context, trueTimeMs: Long) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return   // setTime is API 28+
        if (!OwnerSetup.isDeviceOwner(context)) return
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return
        val admin = ComponentName(context, AliveDeviceAdminReceiver::class.java)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                dpm.setAutoTimeEnabled(admin, false)
            } else {
                @Suppress("DEPRECATION")
                dpm.setGlobalSetting(admin, Settings.Global.AUTO_TIME, "0")
            }
            dpm.setTime(admin, trueTimeMs)
        }.onSuccess {
            // The system clock now agrees with NTP, so the stored offset must go to
            // zero — otherwise every timestamp would be double-corrected.
            DevicePrefs(context).setClockOffsetMs(0L)
        }
    }

    private fun ByteArray.readUint32Be(offset: Int): Long =
        ((this[offset].toLong() and 0xFF) shl 24) or
        ((this[offset + 1].toLong() and 0xFF) shl 16) or
        ((this[offset + 2].toLong() and 0xFF) shl 8) or
        (this[offset + 3].toLong() and 0xFF)
}
