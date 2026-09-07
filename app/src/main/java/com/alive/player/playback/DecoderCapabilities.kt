package com.alive.player.playback

import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

// Shared with PlaybackEngine's decoder-exclusion filter and with the plan-fetch/parse
// paths (PlanFetchWorker, PlanModels.parsePlan) that decide which content rendition
// (H.264 vs HEVC) to download and play. See PlaybackEngine for the per-vendor bug
// writeups behind each excluded component.
object DecoderCapabilities {
    // A decoder can be broken for ONE codec yet fine for another, so the exclusion is
    // keyed by (component name -> the mime types it's broken for), not a flat name set.
    // This matters because some vendors ship a SINGLE multi-codec component:
    //  - Realtek's OMX.realtek.video.decoder handles BOTH video/avc and video/hevc. Its
    //    AVC path fails at init ("setPortMode on output to DynamicANWBuffer failed"), but
    //    its HEVC path works. Excluding it by name for every mime -- the old behaviour --
    //    also killed the one hardware-HEVC path a broken-AVC Realtek panel has, forcing it
    //    to software-decode AVC (1080p on a weak Realtek SoC = ~57% frozen, measured on a
    //    Kodak/SPPL RT41 on 2026-08-31). Excluding it for video/avc ONLY lets preferHevc()
    //    route those panels to the HEVC rendition and hardware-decode it. Hisilicon splits
    //    the codecs across separate components (OMX.hisi.video.decoder.avc vs .hevc), so a
    //    per-mime map is a strict superset of the old exact-name exclusion for it.
    private val BROKEN_BY_MIME: Map<String, Set<String>> = mapOf(
        "OMX.hisi.video.decoder.avc" to setOf(MimeTypes.VIDEO_H264),
        "OMX.realtek.video.decoder"  to setOf(MimeTypes.VIDEO_H264),
        // Realtek rtd2841a (2K D5STV): the Codec2 HEVC decoder starts, then dies with
        // CodecException "Error 0xe" on the first buffers of every clip (verified on
        // 720x1280@30 Main-profile HEVC well within its advertised caps). A runtime
        // codec error -- not an init failure -- so MediaCodecRenderer never falls back
        // on its own and playback black-screens in a retry loop. This is a distinct,
        // Codec2-named component from OMX.realtek.video.decoder above, so blocking its
        // HEVC path doesn't touch the OMX component's working HEVC on other Realtek SoCs.
        "c2.realtek.video.hevc.decoder" to setOf(MimeTypes.VIDEO_H265),
    )

    /** True when [decoderName] is known-broken for [mimeType] and must be excluded. */
    fun isBroken(decoderName: String, mimeType: String): Boolean =
        BROKEN_BY_MIME[decoderName]?.contains(mimeType) == true

    private fun hasReliableHardwareDecoder(mimeType: String): Boolean =
        MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, false, false)
            .any { it.hardwareAccelerated && !isBroken(it.name, mimeType) }

    /**
     * True when this device has no reliable hardware AVC decoder (so AVC content would
     * fall back to a CPU-bound software decoder) but does have a working hardware HEVC
     * decoder. Devices in this state should prefer an HEVC rendition when the server
     * offers one, since hardware-decoding it is far cheaper than software-decoding AVC.
     */
    fun preferHevc(): Boolean =
        !hasReliableHardwareDecoder(MimeTypes.VIDEO_H264) && hasReliableHardwareDecoder(MimeTypes.VIDEO_H265)
}
