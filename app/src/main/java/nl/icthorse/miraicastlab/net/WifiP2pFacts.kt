package nl.icthorse.miraicastlab.net

import android.content.Context
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import nl.icthorse.miraicastlab.core.LabCategory
import nl.icthorse.miraicastlab.core.Observation
import kotlin.coroutines.resume

/**
 * Decoding and awaiting helpers for the Wi-Fi P2P framework.
 *
 * Everything here is deliberately built on the *public* WifiP2pManager surface only. The interesting
 * Miracast facts (WFD information elements, RTSP session parameters, HDCP, UIBC) live one layer
 * below this API in wpa_supplicant and are simply not routed to third-party apps; that boundary is a
 * required finding of the project (spec 2.1) and is expressed by [appInvisibleProtocolFacts].
 */
object WifiP2pFacts {

    /** Default wait for an asynchronous WifiP2pManager callback before we call it a timeout. */
    const val CALLBACK_TIMEOUT_MS = 4000L

    /**
     * The MAC Android hands to third-party apps for the *local* P2P device. Real hardware addresses
     * have been withheld from apps since Android 6; the framework substitutes this constant.
     */
    const val ANONYMISED_MAC = "02:00:00:00:00:00"

    /** WPS primary-device-type category IDs, from the Wi-Fi Simple Configuration spec, table 41. */
    private val PRIMARY_CATEGORIES = mapOf(
        1 to "Computer",
        2 to "Input Device",
        3 to "Printer/Scanner",
        4 to "Camera",
        5 to "Storage",
        6 to "Network Infrastructure",
        7 to "Display",
        8 to "Multimedia Device",
        9 to "Gaming Device",
        10 to "Telephone",
        11 to "Audio Device",
        255 to "Other",
    )

    /** Sub-categories that matter for this project: everything that can plausibly be a video sink. */
    private val DISPLAY_SUBCATEGORIES = mapOf(
        1 to "Television",
        2 to "Electronic Picture Frame",
        3 to "Projector",
        4 to "Monitor",
    )

    private val MULTIMEDIA_SUBCATEGORIES = mapOf(
        1 to "DAR",
        2 to "PVR",
        3 to "MCX",
        4 to "Set-top box",
        5 to "Media Server/Adapter/Extender",
        6 to "Portable Video Player",
    )

    /** One peer, flattened to the fields a third-party app is actually allowed to read. */
    data class PeerFact(
        val deviceName: String,
        val deviceAddress: String,
        val primaryDeviceType: String,
        val secondaryDeviceType: String,
        val primaryTypeLabel: String,
        /** True when the WPS primary device type category is 7 (Display). */
        val looksLikeDisplaySink: Boolean,
        /** True for category 8 (Multimedia) - weaker evidence, a set-top box or media extender. */
        val looksLikeMultimediaSink: Boolean,
        val statusCode: Int,
        val statusLabel: String,
        val isGroupOwner: Boolean,
        val serviceDiscoveryCapable: Boolean,
        val wpsMethods: String,
    ) {
        /** Compact single-line rendering for the live peer table and for log details. */
        fun line(): String =
            deviceName.ifBlank { "(no name)" } + "  " + deviceAddress + "  [" + statusLabel + "]" +
                "  " + primaryTypeLabel + (if (isGroupOwner) "  GO" else "")

        fun details(): Map<String, String> = mapOf(
            "deviceName" to deviceName,
            "deviceAddress" to deviceAddress,
            "primaryDeviceType" to primaryDeviceType,
            "secondaryDeviceType" to secondaryDeviceType,
            "primaryTypeLabel" to primaryTypeLabel,
            "status" to statusLabel,
            "isGroupOwner" to isGroupOwner.toString(),
            "wpsMethods" to wpsMethods,
            "serviceDiscoveryCapable" to serviceDiscoveryCapable.toString(),
        )
    }

    /** Reads every publicly readable field off a [WifiP2pDevice]; never throws. */
    fun factOf(device: WifiP2pDevice): PeerFact {
        val primary = safeString { device.primaryDeviceType }
        val category = primary.substringBefore('-').toIntOrNull()
        return PeerFact(
            deviceName = safeString { device.deviceName },
            deviceAddress = safeString { device.deviceAddress },
            primaryDeviceType = primary,
            secondaryDeviceType = safeString { device.secondaryDeviceType },
            primaryTypeLabel = primaryTypeLabel(primary),
            looksLikeDisplaySink = category == 7,
            looksLikeMultimediaSink = category == 8,
            statusCode = try { device.status } catch (t: Throwable) { -1 },
            statusLabel = statusLabel(try { device.status } catch (t: Throwable) { -1 }),
            isGroupOwner = try { device.isGroupOwner } catch (t: Throwable) { false },
            serviceDiscoveryCapable = try { device.isServiceDiscoveryCapable } catch (t: Throwable) { false },
            wpsMethods = wpsMethods(device),
        )
    }

    /**
     * Decodes "7-0050F204-1" into "Display / Television (7-1)".
     *
     * Category 7 is the single most useful field this API exposes for the project: a Miracast sink
     * advertises itself as a Display. It still does not prove the peer is the Toyota, which is why
     * every finding derived from it is INFERRED.
     */
    fun primaryTypeLabel(raw: String?): String {
        if (raw.isNullOrBlank()) return "unknown"
        val parts = raw.split('-')
        val cat = parts.getOrNull(0)?.toIntOrNull() ?: return raw
        val sub = parts.getOrNull(2)?.toIntOrNull()
        val catLabel = PRIMARY_CATEGORIES[cat] ?: ("category " + cat)
        val subLabel = when (cat) {
            7 -> DISPLAY_SUBCATEGORIES[sub]
            8 -> MULTIMEDIA_SUBCATEGORIES[sub]
            else -> null
        }
        return if (subLabel != null) catLabel + " / " + subLabel + " (" + raw + ")"
        else catLabel + " (" + raw + ")"
    }

    /** WifiP2pDevice status constants, decoded. */
    fun statusLabel(status: Int): String = when (status) {
        WifiP2pDevice.CONNECTED -> "CONNECTED"
        WifiP2pDevice.INVITED -> "INVITED"
        WifiP2pDevice.FAILED -> "FAILED"
        WifiP2pDevice.AVAILABLE -> "AVAILABLE"
        WifiP2pDevice.UNAVAILABLE -> "UNAVAILABLE"
        else -> "UNKNOWN(" + status + ")"
    }

    /**
     * The raw wpsConfigMethodsSupported bitmask is a hidden field. Only these three predicates are
     * public, so this is the complete WPS picture an ordinary app may have.
     */
    fun wpsMethods(device: WifiP2pDevice): String {
        val found = mutableListOf<String>()
        try { if (device.wpsPbcSupported()) found += "PBC" } catch (t: Throwable) { found += "PBC?" }
        try { if (device.wpsKeypadSupported()) found += "KEYPAD" } catch (t: Throwable) { found += "KEYPAD?" }
        try { if (device.wpsDisplaySupported()) found += "DISPLAY" } catch (t: Throwable) { found += "DISPLAY?" }
        return if (found.isEmpty()) "none advertised" else found.joinToString("+")
    }

    /** WifiP2pManager P2P state constants, decoded. */
    fun p2pStateLabel(state: Int): String = when (state) {
        WifiP2pManager.WIFI_P2P_STATE_ENABLED -> "ENABLED"
        WifiP2pManager.WIFI_P2P_STATE_DISABLED -> "DISABLED"
        else -> "UNKNOWN(" + state + ")"
    }

    /** ActionListener failure reasons, decoded. */
    fun actionFailureLabel(reason: Int): String = when (reason) {
        WifiP2pManager.P2P_UNSUPPORTED -> "P2P_UNSUPPORTED"
        WifiP2pManager.BUSY -> "BUSY"
        WifiP2pManager.NO_SERVICE_REQUESTS -> "NO_SERVICE_REQUESTS"
        WifiP2pManager.ERROR -> "ERROR"
        else -> "UNKNOWN(" + reason + ")"
    }

    /** Opens a P2P channel bound to the main looper. Returns null when the framework refuses. */
    fun openChannel(
        manager: WifiP2pManager,
        context: Context,
        onChannelDisconnected: () -> Unit = {},
    ): WifiP2pManager.Channel? = try {
        manager.initialize(
            context.applicationContext,
            Looper.getMainLooper(),
            WifiP2pManager.ChannelListener { onChannelDisconnected() },
        )
    } catch (t: Throwable) {
        null
    }

    /** Closes a channel without ever propagating a failure; a leaked channel is not worth a crash. */
    fun closeChannel(channel: WifiP2pManager.Channel?) {
        try {
            channel?.close()
        } catch (t: Throwable) {
            // Channel.close() exists from API 27 and this build's minSdk is 29, but a vendor ROM
            // that throws here must not take the screen down with it.
        }
    }

    /** Boxes a nullable callback argument so a real null is distinguishable from a timeout. */
    class Held<T>(val value: T)

    /**
     * Bridges one WifiP2pManager listener callback into a coroutine.
     * Returns null on timeout, which is a different fact from a callback that delivered null.
     */
    private suspend fun <T> await(
        timeoutMs: Long,
        register: ((T) -> Unit) -> Unit,
    ): T? = try {
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<T> { cont ->
                register { value -> if (cont.isActive) cont.resume(value) }
            }
        }
    } catch (ce: CancellationException) {
        // Our own coroutine was cancelled (screen left). Never swallow that.
        throw ce
    } catch (t: Throwable) {
        // A vendor framework that throws from request*() is data, not a crash: report it as absent.
        null
    }

    /** requestP2pState is API 29+ and needs no runtime permission. */
    suspend fun awaitP2pState(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel,
        timeoutMs: Long = CALLBACK_TIMEOUT_MS,
    ): Int? = await(timeoutMs) { cb ->
        manager.requestP2pState(channel) { state -> cb(state) }
    }

    /** Peer list. Caller must have verified the NEARBY permission first. */
    suspend fun awaitPeers(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel,
        timeoutMs: Long = CALLBACK_TIMEOUT_MS,
    ): WifiP2pDeviceList? = await(timeoutMs) { cb ->
        manager.requestPeers(channel) { list -> cb(list) }
    }

    /** Connection info. Caller must have verified the NEARBY permission first. */
    suspend fun awaitConnectionInfo(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel,
        timeoutMs: Long = CALLBACK_TIMEOUT_MS,
    ): WifiP2pInfo? = await(timeoutMs) { cb ->
        manager.requestConnectionInfo(channel) { info -> cb(info) }
    }

    /** Group info; the callback legitimately delivers null when no group is formed, hence [Held]. */
    suspend fun awaitGroupInfo(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel,
        timeoutMs: Long = CALLBACK_TIMEOUT_MS,
    ): Held<WifiP2pGroup?>? = await(timeoutMs) { cb ->
        manager.requestGroupInfo(channel) { group -> cb(Held(group)) }
    }

    /** Local device info; API 29+. Null-delivering callback, hence [Held]. */
    suspend fun awaitDeviceInfo(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel,
        timeoutMs: Long = CALLBACK_TIMEOUT_MS,
    ): Held<WifiP2pDevice?>? = await(timeoutMs) { cb ->
        manager.requestDeviceInfo(channel) { device -> cb(Held(device)) }
    }

    /**
     * The negative findings this project is required to produce (spec 2.1).
     *
     * These are NOT_TESTED and not UNSUPPORTED on purpose: the Miracast session may be running
     * perfectly while none of it is visible from here. The API layer is missing, not the capability.
     * Reporting them as UNSUPPORTED would be exactly the hallucination spec section 20 forbids.
     */
    fun appInvisibleProtocolFacts(): List<Observation> = listOf(
        Observation.notTested(
            "wifip2p.wfd_ie_visible",
            LabCategory.MIRACAST,
            "Wi-Fi Display information elements (the WFD IE carrying device type, session-management " +
                "port and coupled-sink flags) are parsed by wpa_supplicant beneath the Android " +
                "framework. No public API returns them, so this app cannot read them on any device.",
        ),
        Observation.notTested(
            "wifip2p.negotiated_resolution",
            LabCategory.MIRACAST,
            "Resolution is negotiated in the RTSP M3/M4 exchange inside the Wi-Fi Display stack. " +
                "Apps see only their own virtual displays, never the sink's negotiated mode.",
        ),
        Observation.notTested(
            "wifip2p.negotiated_framerate",
            LabCategory.MIRACAST,
            "Same RTSP capability exchange as the resolution; not surfaced to apps. Frame rate can " +
                "only be measured indirectly, by rendering the test pattern and filming the sink.",
        ),
        Observation.notTested(
            "wifip2p.rtsp_session",
            LabCategory.MIRACAST,
            "The RTSP control session (port 7236) is owned by the system Wi-Fi Display service. " +
                "This app holds no INTERNET permission and no API exposes the session state.",
        ),
        Observation.notTested(
            "wifip2p.hdcp_state",
            LabCategory.MIRACAST,
            "HDCP 2.x negotiation for Wi-Fi Display happens in the media stack and the sink. No " +
                "public Android API reports whether content protection was requested or established.",
        ),
        Observation.notTested(
            "wifip2p.uibc_advertised",
            LabCategory.MIRACAST,
            "UIBC (the Miracast input back channel) is advertised in the WFD IE and set up over " +
                "RTSP. It is invisible here. Whether the Mirai offers it can only be answered by " +
                "the INPUT TEST: touch the head unit and see whether any InputEvent arrives.",
        ),
        Observation.notTested(
            "wifip2p.audio_transport",
            LabCategory.MIRACAST,
            "Miracast audio (LPCM/AAC over the same RTP session) is carried by the system encoder. " +
                "Apps observe only the local AudioManager routing, not the transported format.",
        ),
    )

    private inline fun safeString(block: () -> String?): String = try {
        block() ?: ""
    } catch (t: Throwable) {
        ""
    }
}
