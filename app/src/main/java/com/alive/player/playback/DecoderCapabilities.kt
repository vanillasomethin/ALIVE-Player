package com.alive.player.playback

import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

// Shared with PlaybackEngine's decoder-exclusion filter and with the plan-fetch/parse
// paths (PlanFetchWorker, PlanModels.parsePlan) that decide which content rendition
// (H.264 vs HEVC) to download and play. See PlaybackEngine for the per-vendor bug
// writeups behind each excluded component.
object DecoderCapabilities {
    val BROKEN_HARDWARE_DECODER_NAMES = setOf(
        "OMX.hisi.video.decoder.avc",
        "OMX.realtek.video.decoder",
        // Realtek rtd2841a (2K D5STV): the Codec2 HEVC decoder starts, then dies with
        // CodecException "Error 0xe" on the first buffers of every clip (verified on
        // 720x1280@30 Main-profile HEVC well within its advertised caps). A runtime
        // codec error -- not an init failure -- so MediaCodecRenderer never falls back
        // on its own and playback black-screens in a retry loop.
        "c2.realtek.video.hevc.decoder",
    )

    private fun hasReliableHardwareDecoder(mimeType: String): Boolean =
        MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, false, false)
            .any { it.hardwareAccelerated && it.name !in BROKEN_HARDWARE_DECODER_NAMES }

    /**
     * True when this device has no reliable hardware AVC decoder (so AVC content would
     * fall back to a CPU-bound software decoder) but does have a working hardware HEVC
     * decoder. Devices in this state should prefer an HEVC rendition when the server
     * offers one, since hardware-decoding it is far cheaper than software-decoding AVC.
     */
    fun preferHevc(): Boolean =
        !hasReliableHardwareDecoder(MimeTypes.VIDEO_H264) && hasReliableHardwareDecoder(MimeTypes.VIDEO_H265)
}
