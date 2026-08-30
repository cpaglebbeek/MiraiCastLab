package nl.icthorse.miraicastlab.scan

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecInfo.VideoCapabilities
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Range
import nl.icthorse.miraicastlab.core.DashboardKeys
import nl.icthorse.miraicastlab.core.LabCategory
import nl.icthorse.miraicastlab.core.LabStatus
import nl.icthorse.miraicastlab.core.Observation
import nl.icthorse.miraicastlab.core.Probe

/**
 * Codec inventory and H.264/HEVC/VP9 encoder characterisation (spec section 6).
 *
 * Scope warning that must survive into the report: everything here describes what THIS PHONE can
 * encode. A Wi-Fi Display sink negotiates its own subset over RTSP, and the Toyota Mirai head unit
 * was not measured, so the negotiated format stays NOT_TESTED no matter how capable the phone is.
 */
object CodecProbe : Probe {

    override val id = "codec"
    override val title = "Codecs & H.264 capability"

    private const val MIME_AVC = "video/avc"
    private const val MIME_HEVC = "video/hevc"
    private const val MIME_VP9 = "video/x-vnd.on2.vp9"

    private val C = LabCategory.CODEC

    /**
     * Feature flags worth interrogating. All of these are compile-time String constants, so naming
     * a flag introduced after API 29 is inlined at build time and safe to query on this minSdk;
     * a codec that does not know the flag simply answers false.
     */
    private val FEATURES = listOf(
        CodecCapabilities.FEATURE_AdaptivePlayback,
        CodecCapabilities.FEATURE_SecurePlayback,
        CodecCapabilities.FEATURE_TunneledPlayback,
        CodecCapabilities.FEATURE_DynamicTimestamp,
        CodecCapabilities.FEATURE_FrameParsing,
        CodecCapabilities.FEATURE_IntraRefresh,
        CodecCapabilities.FEATURE_MultipleFrames,
        CodecCapabilities.FEATURE_PartialFrame,
        CodecCapabilities.FEATURE_LowLatency,
        CodecCapabilities.FEATURE_QpBounds,
        CodecCapabilities.FEATURE_EncodingStatistics,
        CodecCapabilities.FEATURE_HdrEditing,
    )

    override suspend fun observe(context: Context): List<Observation> {
        val out = mutableListOf<Observation>()

        val infos: Array<MediaCodecInfo> = try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        } catch (t: Throwable) {
            // The dashboard contract keys must still be answered, and "the codec list itself threw"
            // says nothing about the codecs, so they are ERROR, not UNSUPPORTED.
            return listOf(
                Observation.error("codec.list", t, C),
                Observation.error(DashboardKeys.H264_ENCODERS, t, C),
                Observation.error(DashboardKeys.H264_MAX_SIZE, t, C),
            )
        }

        out += inventory(infos)
        out += analyseMime(infos, MIME_AVC, "codec.avc")
        out += analyseMime(infos, MIME_HEVC, "codec.hevc")
        out += analyseMime(infos, MIME_VP9, "codec.vp9")
        out += h264DashboardKeys(infos)

        out += Observation.notTested(
            "codec.sink_negotiated_format",
            C,
            "What the Toyota Mirai head unit accepts is decided by RTSP capability negotiation at " +
                "connect time and was not captured. Phone-side encoder capability is not sink " +
                "capability.",
        )
        return out
    }

    // ---------------------------------------------------------------- full inventory

    private fun inventory(infos: Array<MediaCodecInfo>): List<Observation> {
        val out = mutableListOf<Observation>()
        var encoders = 0
        var decoders = 0
        var hardware = 0
        var software = 0
        var vendor = 0
        val mimes = sortedSetOf<String>()

        infos.forEach { info ->
            try {
                val types = info.supportedTypes
                types.forEach { mimes += it }
                val isEnc = info.isEncoder
                if (isEnc) encoders++ else decoders++

                // isHardwareAccelerated / isSoftwareOnly / isVendor / isAlias are all API 29,
                // which equals this project's minSdk, so no version gate is needed.
                val hw = info.isHardwareAccelerated
                val sw = info.isSoftwareOnly
                val vnd = info.isVendor
                if (hw) hardware++
                if (sw) software++
                if (vnd) vendor++

                val detail = "types=[" + types.joinToString(",") + "]" +
                    " hardwareAccelerated=" + hw +
                    " softwareOnly=" + sw +
                    " vendor=" + vnd +
                    " alias=" + info.isAlias +
                    " canonical=" + info.canonicalName
                out += Observation.confirmed(
                    "codec." + (if (isEnc) "encoder" else "decoder") + "." + info.name,
                    detail,
                    C,
                )
            } catch (t: Throwable) {
                out += Observation.error("codec.info." + info.name, t, C)
            }
        }

        out += Observation.confirmed("codec.count_total", infos.size, C,
            "MediaCodecList(REGULAR_CODECS): the codecs an ordinary app may instantiate.")
        out += Observation.confirmed("codec.count_encoders", encoders, C)
        out += Observation.confirmed("codec.count_decoders", decoders, C)
        out += Observation.confirmed("codec.count_hardware_accelerated", hardware, C)
        out += Observation.confirmed("codec.count_software_only", software, C)
        out += Observation.confirmed("codec.count_vendor", vendor, C)
        out += Observation.confirmed("codec.mime_types", mimes.joinToString(", "), C,
            "Every MIME type any codec on this device claims.")
        return out
    }

    // ---------------------------------------------------------------- per-MIME analysis

    private fun supports(info: MediaCodecInfo, mime: String): Boolean = try {
        info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
    } catch (t: Throwable) {
        false
    }

    private fun analyseMime(
        infos: Array<MediaCodecInfo>,
        mime: String,
        prefix: String,
    ): List<Observation> {
        val out = mutableListOf<Observation>()
        val encoders = infos.filter { it.isEncoder && supports(it, mime) }
        val decoders = infos.filter { !it.isEncoder && supports(it, mime) }

        out += Observation.confirmed(prefix + ".mime", mime, C)
        if (encoders.isEmpty()) {
            out += Observation.unsupported(prefix + ".encoder_count", C,
                "No encoder on this device advertises " + mime + ".")
        } else {
            out += Observation.confirmed(prefix + ".encoder_count", encoders.size, C)
            out += Observation.confirmed(prefix + ".encoders",
                encoders.joinToString(", ") { it.name }, C)
        }
        if (decoders.isEmpty()) {
            out += Observation.unsupported(prefix + ".decoder_count", C,
                "No decoder on this device advertises " + mime + ".")
        } else {
            out += Observation.confirmed(prefix + ".decoder_count", decoders.size, C)
            out += Observation.confirmed(prefix + ".decoders",
                decoders.joinToString(", ") { it.name }, C)
        }

        encoders.forEach { out += describe(it, mime, prefix + ".enc." + it.name, full = true) }
        // Decoders get the short form: for a Miracast source the phone encodes, it does not decode.
        decoders.forEach { out += describe(it, mime, prefix + ".dec." + it.name, full = false) }

        // Aggregate maximum across encoders, which is the number a tester actually wants.
        val best = encoders.mapNotNull { info ->
            videoCaps(info, mime)?.let { vc -> maxSize(vc) }
        }.maxByOrNull { it.first.toLong() * it.second.toLong() }
        if (best != null) {
            out += Observation.confirmed(prefix + ".encoder_max_size",
                best.first.toString() + "x" + best.second, C,
                "Largest frame any encoder on this device advertises for " + mime + ".")
        }
        return out
    }

    private fun videoCaps(info: MediaCodecInfo, mime: String): VideoCapabilities? = try {
        info.getCapabilitiesForType(mime).videoCapabilities
    } catch (t: Throwable) {
        null
    }

    /**
     * @param full encoders get the whole picture (bitrate modes, quality, performance points);
     *             decoders get size and profiles only.
     */
    private fun describe(
        info: MediaCodecInfo,
        mime: String,
        prefix: String,
        full: Boolean,
    ): List<Observation> {
        val out = mutableListOf<Observation>()
        val caps: CodecCapabilities = try {
            info.getCapabilitiesForType(mime)
        } catch (t: Throwable) {
            return listOf(Observation.error(prefix + ".capabilities", t, C))
        }

        out += Observation.confirmed(prefix + ".hardware_accelerated", info.isHardwareAccelerated, C)
        out += guarded(prefix + ".max_instances") { caps.maxSupportedInstances }

        val vc = caps.videoCapabilities
        if (vc == null) {
            out += Observation.unsupported(prefix + ".video_capabilities", C,
                "getCapabilitiesForType(" + mime + ").videoCapabilities returned null.")
        } else {
            val size = maxSize(vc)
            if (size != null) {
                out += Observation.confirmed(prefix + ".max_size",
                    size.first.toString() + "x" + size.second, C,
                    "Largest advertised frame; the sink may still refuse it.")
                out += guarded(prefix + ".frame_rates_at_max") {
                    rangeText(vc.getSupportedFrameRatesFor(size.first, size.second))
                }
                val achievable = try {
                    vc.getAchievableFrameRatesFor(size.first, size.second)
                } catch (t: Throwable) {
                    null
                }
                out += if (achievable != null) {
                    Observation.confirmed(prefix + ".achievable_frame_rates_at_max",
                        rangeText(achievable), C,
                        "Device-measured, not merely advertised.")
                } else {
                    Observation.notTested(prefix + ".achievable_frame_rates_at_max", C,
                        "getAchievableFrameRatesFor returned null: this build ships no measured " +
                            "performance data for the codec.")
                }
            } else {
                out += Observation.error(prefix + ".max_size",
                    IllegalStateException("supported width/height ranges did not resolve"), C)
            }

            out += guarded(prefix + ".width_range") { rangeText(vc.supportedWidths) }
            out += guarded(prefix + ".height_range") { rangeText(vc.supportedHeights) }
            out += guarded(prefix + ".alignment") {
                vc.widthAlignment.toString() + "x" + vc.heightAlignment
            }
            out += guarded(prefix + ".bitrate_range_bps") { rangeText(vc.bitrateRange) }
            out += guarded(prefix + ".frame_rate_range") { rangeText(vc.supportedFrameRates) }
            // 1920x1080 is the classic Miracast profile; whether it is in range is the single most
            // useful yes/no on this screen.
            out += try {
                if (vc.isSizeSupported(1920, 1080)) {
                    Observation.confirmed(prefix + ".supports_1080p", "yes", C,
                        "Frame rates at 1920x1080: " + rangeText(vc.getSupportedFrameRatesFor(1920, 1080)))
                } else {
                    Observation.unsupported(prefix + ".supports_1080p", C,
                        "isSizeSupported(1920,1080) returned false.")
                }
            } catch (t: Throwable) {
                Observation.error(prefix + ".supports_1080p", t, C)
            }
            if (full) {
                out += try {
                    val points = vc.supportedPerformancePoints
                    if (points == null) {
                        Observation.notTested(prefix + ".performance_points", C,
                            "getSupportedPerformancePoints returned null: no declared performance data.")
                    } else {
                        Observation.confirmed(prefix + ".performance_points",
                            points.joinToString(", ") { it.toString() }, C,
                            "Size/frame-rate combinations the codec declares it can sustain.")
                    }
                } catch (t: Throwable) {
                    Observation.error(prefix + ".performance_points", t, C)
                }
            }
        }

        out += guarded(prefix + ".profiles") {
            caps.profileLevels.joinToString(", ") { pl ->
                profileName(mime, pl.profile) + "@" + levelName(mime, pl.level)
            }
        }
        out += guarded(prefix + ".color_formats") {
            caps.colorFormats.joinToString(", ") { colorFormatName(it) }
        }

        // KEY_LEVEL: the encoder advertises a default level only if it honours the key at all.
        out += try {
            val df: MediaFormat? = caps.defaultFormat
            val hasLevel = df != null && df.containsKey(MediaFormat.KEY_LEVEL)
            Observation(
                prefix + ".key_level_in_default_format",
                if (hasLevel) "yes" else "no",
                LabStatus.INFERRED,
                "Read from CodecCapabilities.getDefaultFormat(). A codec that publishes KEY_LEVEL " +
                    "by default is likely to honour it on configure(), but that was not exercised.",
                C,
            )
        } catch (t: Throwable) {
            Observation.error(prefix + ".key_level_in_default_format", t, C)
        }

        val supportedFeatures = mutableListOf<String>()
        val absentFeatures = mutableListOf<String>()
        FEATURES.forEach { f ->
            try {
                if (caps.isFeatureSupported(f)) supportedFeatures += f else absentFeatures += f
            } catch (t: Throwable) {
                // An unknown feature name throws on some vendor implementations; treat as absent
                // evidence about the query, not about the codec.
                absentFeatures += f + "(query-threw)"
            }
        }
        out += Observation.confirmed(prefix + ".features_supported",
            if (supportedFeatures.isEmpty()) "none" else supportedFeatures.joinToString(", "), C)
        out += Observation.confirmed(prefix + ".features_absent",
            absentFeatures.joinToString(", "), C,
            "CodecCapabilities.isFeatureSupported returned false for these.")

        if (full) {
            val ec = try {
                caps.encoderCapabilities
            } catch (t: Throwable) {
                null
            }
            if (ec == null) {
                out += Observation.unsupported(prefix + ".encoder_capabilities", C,
                    "getEncoderCapabilities() returned null.")
            } else {
                // The supported bitrate range lives on Video/AudioCapabilities, not on
                // EncoderCapabilities: the encoder object only carries quality, complexity and
                // bitrate MODES. Reading it from the right place keeps the value meaningful.
                out += guarded(prefix + ".encoder_bitrate_range_bps") {
                    caps.videoCapabilities?.bitrateRange?.let { rangeText(it) }
                        ?: caps.audioCapabilities?.bitrateRange?.let { rangeText(it) }
                }
                out += guarded(prefix + ".encoder_quality_range") { rangeText(ec.qualityRange) }
                out += guarded(prefix + ".encoder_complexity_range") { rangeText(ec.complexityRange) }
                val modes = listOf(
                    "CQ" to MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ,
                    "VBR" to MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR,
                    "CBR" to MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
                    "CBR_FD" to MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR_FD,
                ).filter { (_, mode) ->
                    try {
                        ec.isBitrateModeSupported(mode)
                    } catch (t: Throwable) {
                        false
                    }
                }.map { it.first }
                out += Observation.confirmed(prefix + ".encoder_bitrate_modes",
                    if (modes.isEmpty()) "none reported" else modes.joinToString(", "), C,
                    "CBR matters for a Wi-Fi Display link with a fixed bitrate budget.")
            }
        }
        return out
    }

    // ---------------------------------------------------------------- dashboard contract

    private fun h264DashboardKeys(infos: Array<MediaCodecInfo>): List<Observation> {
        val out = mutableListOf<Observation>()
        val encoders = infos.filter { it.isEncoder && supports(it, MIME_AVC) }

        out += if (encoders.isEmpty()) {
            Observation.unsupported(DashboardKeys.H264_ENCODERS, C,
                "MediaCodecList lists no video/avc encoder on this device.")
        } else {
            Observation.confirmed(DashboardKeys.H264_ENCODERS, encoders.size, C,
                encoders.joinToString(", ") { it.name })
        }

        val best = encoders.mapNotNull { info ->
            videoCaps(info, MIME_AVC)?.let { vc -> maxSize(vc)?.let { s -> info.name to s } }
        }.maxByOrNull { it.second.first.toLong() * it.second.second.toLong() }

        out += if (best == null) {
            if (encoders.isEmpty()) {
                Observation.unsupported(DashboardKeys.H264_MAX_SIZE, C,
                    "No video/avc encoder exists, so it has no maximum frame size.")
            } else {
                Observation.error(DashboardKeys.H264_MAX_SIZE,
                    IllegalStateException("video/avc encoders exist but reported no usable size range"),
                    C)
            }
        } else {
            Observation.confirmed(
                DashboardKeys.H264_MAX_SIZE,
                best.second.first.toString() + "x" + best.second.second,
                C,
                "Advertised by " + best.first + ". This is a phone-side encoder limit only; the " +
                    "resolution a Miracast sink negotiates is NOT_TESTED.",
            )
        }
        return out
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Largest frame the codec advertises. Width and height ranges are not independent, so both
     * "widest" and "tallest" are resolved through their conditional range and the larger area wins.
     */
    private fun maxSize(vc: VideoCapabilities): Pair<Int, Int>? = try {
        val wMax = vc.supportedWidths.upper
        val hMax = vc.supportedHeights.upper
        val hForW = try {
            vc.getSupportedHeightsFor(wMax).upper
        } catch (t: Throwable) {
            hMax
        }
        val wForH = try {
            vc.getSupportedWidthsFor(hMax).upper
        } catch (t: Throwable) {
            wMax
        }
        if (wMax.toLong() * hForW.toLong() >= wForH.toLong() * hMax.toLong()) {
            wMax to hForW
        } else {
            wForH to hMax
        }
    } catch (t: Throwable) {
        null
    }

    private fun rangeText(r: Range<*>?): String =
        if (r == null) "null" else r.lower.toString() + ".." + r.upper.toString()

    private inline fun guarded(key: String, block: () -> Any?): Observation = try {
        val v = block()
        if (v == null) Observation.unsupported(key, C, "Platform returned null.")
        else Observation.confirmed(key, v, C)
    } catch (t: Throwable) {
        Observation.error(key, t, C)
    }

    private fun hex(v: Int): String = "0x" + Integer.toHexString(v)

    private fun profileName(mime: String, p: Int): String = when {
        mime.equals(MIME_AVC, true) -> when (p) {
            CodecProfileLevel.AVCProfileBaseline -> "Baseline"
            CodecProfileLevel.AVCProfileMain -> "Main"
            CodecProfileLevel.AVCProfileExtended -> "Extended"
            CodecProfileLevel.AVCProfileHigh -> "High"
            CodecProfileLevel.AVCProfileHigh10 -> "High10"
            CodecProfileLevel.AVCProfileHigh422 -> "High422"
            CodecProfileLevel.AVCProfileHigh444 -> "High444"
            CodecProfileLevel.AVCProfileConstrainedBaseline -> "ConstrainedBaseline"
            CodecProfileLevel.AVCProfileConstrainedHigh -> "ConstrainedHigh"
            else -> "profile" + hex(p)
        }
        mime.equals(MIME_HEVC, true) -> when (p) {
            CodecProfileLevel.HEVCProfileMain -> "Main"
            CodecProfileLevel.HEVCProfileMain10 -> "Main10"
            CodecProfileLevel.HEVCProfileMainStill -> "MainStill"
            CodecProfileLevel.HEVCProfileMain10HDR10 -> "Main10HDR10"
            CodecProfileLevel.HEVCProfileMain10HDR10Plus -> "Main10HDR10Plus"
            else -> "profile" + hex(p)
        }
        mime.equals(MIME_VP9, true) -> when (p) {
            CodecProfileLevel.VP9Profile0 -> "Profile0"
            CodecProfileLevel.VP9Profile1 -> "Profile1"
            CodecProfileLevel.VP9Profile2 -> "Profile2"
            CodecProfileLevel.VP9Profile3 -> "Profile3"
            CodecProfileLevel.VP9Profile2HDR -> "Profile2HDR"
            CodecProfileLevel.VP9Profile3HDR -> "Profile3HDR"
            CodecProfileLevel.VP9Profile2HDR10Plus -> "Profile2HDR10Plus"
            CodecProfileLevel.VP9Profile3HDR10Plus -> "Profile3HDR10Plus"
            else -> "profile" + hex(p)
        }
        else -> "profile" + hex(p)
    }

    private fun levelName(mime: String, l: Int): String = when {
        mime.equals(MIME_AVC, true) -> when (l) {
            CodecProfileLevel.AVCLevel1 -> "L1"
            CodecProfileLevel.AVCLevel1b -> "L1b"
            CodecProfileLevel.AVCLevel11 -> "L1.1"
            CodecProfileLevel.AVCLevel12 -> "L1.2"
            CodecProfileLevel.AVCLevel13 -> "L1.3"
            CodecProfileLevel.AVCLevel2 -> "L2"
            CodecProfileLevel.AVCLevel21 -> "L2.1"
            CodecProfileLevel.AVCLevel22 -> "L2.2"
            CodecProfileLevel.AVCLevel3 -> "L3"
            CodecProfileLevel.AVCLevel31 -> "L3.1"
            CodecProfileLevel.AVCLevel32 -> "L3.2"
            CodecProfileLevel.AVCLevel4 -> "L4"
            CodecProfileLevel.AVCLevel41 -> "L4.1"
            CodecProfileLevel.AVCLevel42 -> "L4.2"
            CodecProfileLevel.AVCLevel5 -> "L5"
            CodecProfileLevel.AVCLevel51 -> "L5.1"
            CodecProfileLevel.AVCLevel52 -> "L5.2"
            CodecProfileLevel.AVCLevel6 -> "L6"
            CodecProfileLevel.AVCLevel61 -> "L6.1"
            CodecProfileLevel.AVCLevel62 -> "L6.2"
            else -> "level" + hex(l)
        }
        mime.equals(MIME_HEVC, true) -> when (l) {
            CodecProfileLevel.HEVCMainTierLevel1 -> "MainL1"
            CodecProfileLevel.HEVCHighTierLevel1 -> "HighL1"
            CodecProfileLevel.HEVCMainTierLevel2 -> "MainL2"
            CodecProfileLevel.HEVCHighTierLevel2 -> "HighL2"
            CodecProfileLevel.HEVCMainTierLevel21 -> "MainL2.1"
            CodecProfileLevel.HEVCHighTierLevel21 -> "HighL2.1"
            CodecProfileLevel.HEVCMainTierLevel3 -> "MainL3"
            CodecProfileLevel.HEVCHighTierLevel3 -> "HighL3"
            CodecProfileLevel.HEVCMainTierLevel31 -> "MainL3.1"
            CodecProfileLevel.HEVCHighTierLevel31 -> "HighL3.1"
            CodecProfileLevel.HEVCMainTierLevel4 -> "MainL4"
            CodecProfileLevel.HEVCHighTierLevel4 -> "HighL4"
            CodecProfileLevel.HEVCMainTierLevel41 -> "MainL4.1"
            CodecProfileLevel.HEVCHighTierLevel41 -> "HighL4.1"
            CodecProfileLevel.HEVCMainTierLevel5 -> "MainL5"
            CodecProfileLevel.HEVCHighTierLevel5 -> "HighL5"
            CodecProfileLevel.HEVCMainTierLevel51 -> "MainL5.1"
            CodecProfileLevel.HEVCHighTierLevel51 -> "HighL5.1"
            CodecProfileLevel.HEVCMainTierLevel52 -> "MainL5.2"
            CodecProfileLevel.HEVCHighTierLevel52 -> "HighL5.2"
            CodecProfileLevel.HEVCMainTierLevel6 -> "MainL6"
            CodecProfileLevel.HEVCHighTierLevel6 -> "HighL6"
            CodecProfileLevel.HEVCMainTierLevel61 -> "MainL6.1"
            CodecProfileLevel.HEVCHighTierLevel61 -> "HighL6.1"
            CodecProfileLevel.HEVCMainTierLevel62 -> "MainL6.2"
            CodecProfileLevel.HEVCHighTierLevel62 -> "HighL6.2"
            else -> "level" + hex(l)
        }
        mime.equals(MIME_VP9, true) -> when (l) {
            CodecProfileLevel.VP9Level1 -> "L1"
            CodecProfileLevel.VP9Level11 -> "L1.1"
            CodecProfileLevel.VP9Level2 -> "L2"
            CodecProfileLevel.VP9Level21 -> "L2.1"
            CodecProfileLevel.VP9Level3 -> "L3"
            CodecProfileLevel.VP9Level31 -> "L3.1"
            CodecProfileLevel.VP9Level4 -> "L4"
            CodecProfileLevel.VP9Level41 -> "L4.1"
            CodecProfileLevel.VP9Level5 -> "L5"
            CodecProfileLevel.VP9Level51 -> "L5.1"
            CodecProfileLevel.VP9Level52 -> "L5.2"
            CodecProfileLevel.VP9Level6 -> "L6"
            CodecProfileLevel.VP9Level61 -> "L6.1"
            CodecProfileLevel.VP9Level62 -> "L6.2"
            else -> "level" + hex(l)
        }
        else -> "level" + hex(l)
    }

    private fun colorFormatName(v: Int): String = when (v) {
        CodecCapabilities.COLOR_FormatSurface -> "Surface"
        CodecCapabilities.COLOR_FormatYUV420Flexible -> "YUV420Flexible"
        CodecCapabilities.COLOR_FormatYUV422Flexible -> "YUV422Flexible"
        CodecCapabilities.COLOR_FormatYUV444Flexible -> "YUV444Flexible"
        CodecCapabilities.COLOR_FormatRGBFlexible -> "RGBFlexible"
        CodecCapabilities.COLOR_FormatYUV420Planar -> "YUV420Planar"
        CodecCapabilities.COLOR_FormatYUV420SemiPlanar -> "YUV420SemiPlanar"
        CodecCapabilities.COLOR_FormatYUV420PackedPlanar -> "YUV420PackedPlanar"
        CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar -> "YUV420PackedSemiPlanar"
        CodecCapabilities.COLOR_Format32bitABGR8888 -> "32bitABGR8888"
        CodecCapabilities.COLOR_Format24bitBGR888 -> "24bitBGR888"
        CodecCapabilities.COLOR_FormatYUVP010 -> "YUVP010"
        else -> hex(v)
    }
}
