package com.alive.player.settings

import android.content.Context
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

object NtpSyncManager {

    private const val NTP_HOST = "pool.ntp.org"
    private const val NTP_PORT = 123
    // NTP epoch (Jan 1, 1900) → Unix epoch (Jan 1, 1970) in seconds
    private const val OFFSET_1900_TO_1970 = ((365L * 70) + 17) * 24 * 60 * 60

    /**
     * Sync the clock offset against pool.ntp.org.
     * Stores the result in DevicePrefs so it survives process restarts.
     * No-ops silently on network failure; cached offset is then used.
     */
    fun sync(context: Context) {
        try {
            val buffer = ByteArray(48)
            buffer[0] = 0x1B.toByte() // LI=0, VN=3, Mode=3 (client)

            val address = InetAddress.getByName(NTP_HOST)
            val socket = DatagramSocket()
            socket.soTimeout = 5_000

            val requestTimeMs = System.currentTimeMillis()
            socket.send(DatagramPacket(buffer, buffer.size, address, NTP_PORT))
            val recvPacket = DatagramPacket(buffer, buffer.size)
            socket.receive(recvPacket)
            val responseTimeMs = System.currentTimeMillis()
            socket.close()

            // Transmit timestamp is at bytes 40-47 (seconds + fraction)
            val seconds = buffer.readUint32Be(40) - OFFSET_1900_TO_1970
            val fraction = buffer.readUint32Be(44)
            val ntpTimeMs = seconds * 1000L + fraction * 1000L / 0x100000000L

            // Adjust for one-way network latency (RTT / 2)
            val rttMs = responseTimeMs - requestTimeMs
            val clockOffsetMs = ntpTimeMs - requestTimeMs - rttMs / 2

            DevicePrefs(context).setClockOffsetMs(clockOffsetMs)
        } catch (_: Exception) {
            // Cached offset remains in effect
        }
    }

    fun now(context: Context): Long =
        System.currentTimeMillis() + DevicePrefs(context).getClockOffsetMs()

    fun nowIso(context: Context): String =
        java.time.Instant.ofEpochMilli(now(context)).toString()

    private fun ByteArray.readUint32Be(offset: Int): Long =
        ((this[offset].toLong() and 0xFF) shl 24) or
        ((this[offset + 1].toLong() and 0xFF) shl 16) or
        ((this[offset + 2].toLong() and 0xFF) shl 8) or
        (this[offset + 3].toLong() and 0xFF)
}
