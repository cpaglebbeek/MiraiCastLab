package nl.icthorse.miraicastlab.core

/**
 * The contract between the probes and the dashboard.
 *
 * The dashboard (spec section 5.1) must show a fixed set of facts. Rather than let it reach into
 * each module, every probe promises to emit these observation keys. A key that is missing renders
 * as "-" and is reported as a gap by [missingFrom], which is how an unimplemented or failed probe
 * becomes visible instead of silently blank.
 */
object DashboardKeys {

    const val MANUFACTURER = "device.manufacturer"
    const val MODEL = "device.model"
    const val ANDROID_RELEASE = "device.android_release"
    const val SDK_INT = "device.sdk_int"
    const val ONE_UI = "device.one_ui_version"
    const val FINGERPRINT = "device.build_fingerprint"
    const val ROOT = "device.root_detected"
    const val IS_SAMSUNG = "device.is_samsung"

    const val WIFI_STATE = "net.wifi_state"
    const val WIFI_SSID = "net.wifi_ssid"
    const val ACTIVE_TRANSPORT = "net.active_transport"
    const val P2P_SUPPORTED = "wifip2p.supported"
    const val P2P_STATE = "wifip2p.state"
    const val P2P_PEERS = "wifip2p.peer_count"

    const val DISPLAY_COUNT = "display.count"
    const val EXTERNAL_PRESENT = "display.external_present"
    const val DEFAULT_RESOLUTION = "display.default_resolution"
    const val EXTERNAL_RESOLUTION = "display.external_resolution"
    const val EXTERNAL_NAMES = "display.external_names"

    const val AUDIO_OUTPUTS = "audio.output_devices"
    const val AUDIO_ACTIVE = "audio.active_output"

    const val AA_INSTALLED = "androidauto.installed"
    const val AA_PROJECTION = "androidauto.projection_state"

    const val SMARTVIEW_ACTIVE = "smartview.session_active"
    const val MIRACAST_OBSERVATION = "miracast.session_evidence"

    const val H264_ENCODERS = "codec.h264_encoder_count"
    const val H264_MAX_SIZE = "codec.h264_max_size"

    /** Every key the dashboard reads, in display order. */
    val ALL = listOf(
        MANUFACTURER, MODEL, ANDROID_RELEASE, SDK_INT, ONE_UI, FINGERPRINT, ROOT, IS_SAMSUNG,
        WIFI_STATE, WIFI_SSID, ACTIVE_TRANSPORT, P2P_SUPPORTED, P2P_STATE, P2P_PEERS,
        DISPLAY_COUNT, EXTERNAL_PRESENT, DEFAULT_RESOLUTION, EXTERNAL_RESOLUTION, EXTERNAL_NAMES,
        AUDIO_OUTPUTS, AUDIO_ACTIVE,
        AA_INSTALLED, AA_PROJECTION,
        SMARTVIEW_ACTIVE, MIRACAST_OBSERVATION,
        H264_ENCODERS, H264_MAX_SIZE,
    )

    /** Keys the dashboard wanted but no probe produced. A non-empty result is a coverage gap. */
    fun missingFrom(observations: List<Observation>): List<String> {
        val present = observations.map { it.key }.toSet()
        return ALL.filterNot { it in present }
    }
}
