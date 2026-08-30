package nl.icthorse.miraicastlab.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import nl.icthorse.miraicastlab.core.DashboardKeys
import nl.icthorse.miraicastlab.core.LabCategory
import nl.icthorse.miraicastlab.core.LabPermissions
import nl.icthorse.miraicastlab.core.LabStatus
import nl.icthorse.miraicastlab.core.Observation
import nl.icthorse.miraicastlab.core.Probe

/**
 * Connectivity, Wi-Fi link parameters and kernel interfaces.
 *
 * Three jobs, in order of importance to the project:
 *
 * 1. Interface enumeration. A "p2p-*" interface coming up is the strongest signal an unprivileged
 *    app gets that a Wi-Fi Direct group - and therefore possibly Miracast - is live. That is
 *    [DashboardKeys]-adjacent key net.p2p_interface_present, and it is INFERRED, never CONFIRMED,
 *    because the interface says "a P2P group exists", not "Miracast is running over it".
 * 2. Wi-Fi radio parameters. Band and frequency matter because Miracast and wireless Android Auto
 *    compete for the same 5 GHz radio (research question 3).
 * 3. Transport decoding, so the report can say what the phone considered its active network at the
 *    moment of a test.
 *
 * Privacy: link addresses are reduced to scope classes and counts. The lab records that an address
 * exists, never which one.
 */
object ConnectivityProbe : Probe {

    override val id = "connectivity"
    override val title = "Connectivity & interfaces"

    /** Transport constants with their labels. USB is API 31+; the constant is inlined and reads false below that. */
    private val TRANSPORTS: List<Pair<Int, String>> = buildList {
        add(NetworkCapabilities.TRANSPORT_CELLULAR to "CELLULAR")
        add(NetworkCapabilities.TRANSPORT_WIFI to "WIFI")
        add(NetworkCapabilities.TRANSPORT_BLUETOOTH to "BLUETOOTH")
        add(NetworkCapabilities.TRANSPORT_ETHERNET to "ETHERNET")
        add(NetworkCapabilities.TRANSPORT_VPN to "VPN")
        add(NetworkCapabilities.TRANSPORT_WIFI_AWARE to "WIFI_AWARE")
        add(NetworkCapabilities.TRANSPORT_LOWPAN to "LOWPAN")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(NetworkCapabilities.TRANSPORT_USB to "USB")
        }
    }

    private val CAPABILITIES: List<Pair<Int, String>> = listOf(
        NetworkCapabilities.NET_CAPABILITY_INTERNET to "INTERNET",
        NetworkCapabilities.NET_CAPABILITY_VALIDATED to "VALIDATED",
        NetworkCapabilities.NET_CAPABILITY_NOT_METERED to "NOT_METERED",
        NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED to "NOT_RESTRICTED",
        NetworkCapabilities.NET_CAPABILITY_NOT_VPN to "NOT_VPN",
        NetworkCapabilities.NET_CAPABILITY_TRUSTED to "TRUSTED",
        NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING to "NOT_ROAMING",
        NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED to "NOT_SUSPENDED",
    )

    /** WifiManager's placeholder when it refuses to disclose the SSID. Literal, so it is version-safe. */
    private const val UNKNOWN_SSID = "<unknown ssid>"

    override suspend fun observe(context: Context): List<Observation> {
        val out = mutableListOf<Observation>()
        val app = context.applicationContext
        out += transportObservations(app)
        out += wifiObservations(context, app)
        out += interfaceObservations()
        return out
    }

    // ------------------------------------------------------------------------------------------

    private fun transportObservations(app: Context): List<Observation> {
        val out = mutableListOf<Observation>()
        val cm = try {
            app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        } catch (t: Throwable) {
            out += Observation.error("net.connectivity_service", t, LabCategory.NETWORK)
            null
        }
        if (cm == null) {
            out += Observation.unsupported(
                DashboardKeys.ACTIVE_TRANSPORT,
                LabCategory.NETWORK,
                "getSystemService(CONNECTIVITY_SERVICE) returned null.",
            )
            return out
        }

        val active = try { cm.activeNetwork } catch (t: Throwable) { null }
        if (active == null) {
            out += Observation.confirmed(
                DashboardKeys.ACTIVE_TRANSPORT,
                "none",
                LabCategory.NETWORK,
                "ConnectivityManager reports no active default network. Note that a Wi-Fi Direct " +
                    "group is not a default network, so an active Miracast session can coexist with " +
                    "'none' here.",
            )
            return out
        }

        val caps: NetworkCapabilities? = try { cm.getNetworkCapabilities(active) } catch (t: Throwable) { null }
        if (caps == null) {
            out += Observation.error(
                DashboardKeys.ACTIVE_TRANSPORT,
                IllegalStateException("getNetworkCapabilities returned null for the active network"),
                LabCategory.NETWORK,
            )
            return out
        }

        val transports = TRANSPORTS.filter { (id, _) ->
            try { caps.hasTransport(id) } catch (t: Throwable) { false }
        }.map { it.second }
        out += Observation.confirmed(
            DashboardKeys.ACTIVE_TRANSPORT,
            if (transports.isEmpty()) "none decoded" else transports.joinToString("+"),
            LabCategory.NETWORK,
            "Transports of the current default network.",
        )
        out += Observation.confirmed(
            "net.capabilities",
            CAPABILITIES.filter { (id, _) ->
                try { caps.hasCapability(id) } catch (t: Throwable) { false }
            }.joinToString(",") { it.second }.ifBlank { "none decoded" },
            LabCategory.NETWORK,
        )
        out += Observation.confirmed(
            "net.link_downstream_kbps",
            try { caps.linkDownstreamBandwidthKbps } catch (t: Throwable) { -1 },
            LabCategory.NETWORK,
            "The framework's own estimate, not a measurement.",
        )
        out += Observation.confirmed(
            "net.link_upstream_kbps",
            try { caps.linkUpstreamBandwidthKbps } catch (t: Throwable) { -1 },
            LabCategory.NETWORK,
            "The framework's own estimate, not a measurement.",
        )
        out += Observation.confirmed(
            "net.vpn_active",
            try { caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) } catch (t: Throwable) { "unreadable" },
            LabCategory.NETWORK,
            "A VPN on the default network does not affect Wi-Fi Direct, which bypasses it.",
        )

        val lp: LinkProperties? = try { cm.getLinkProperties(active) } catch (t: Throwable) { null }
        if (lp == null) {
            out += Observation.notTested(
                "net.link_interface",
                LabCategory.NETWORK,
                "getLinkProperties returned null for the active network.",
            )
        } else {
            out += Observation.confirmed(
                "net.link_interface",
                try { lp.interfaceName ?: "(unnamed)" } catch (t: Throwable) { "unreadable" },
                LabCategory.NETWORK,
                "Kernel interface backing the default network.",
            )
            val addresses = try { lp.linkAddresses.map { it.address } } catch (t: Throwable) { emptyList() }
            out += Observation.confirmed(
                "net.link_address_count",
                addresses.size,
                LabCategory.NETWORK,
                "Count only. Addresses themselves are intentionally not recorded.",
            )
            out += Observation.confirmed(
                "net.link_address_scopes",
                NetFacts.scopesOf(addresses),
                LabCategory.NETWORK,
                "Scope classes present on the default network's interface.",
            )
            out += Observation.confirmed(
                "net.dns_server_count",
                try { lp.dnsServers.size } catch (t: Throwable) { -1 },
                LabCategory.NETWORK,
                "Count only; resolver addresses are not recorded and the app has no INTERNET permission.",
            )
            out += Observation.confirmed(
                "net.route_count",
                try { lp.routes.size } catch (t: Throwable) { -1 },
                LabCategory.NETWORK,
            )
        }
        return out
    }

    // ------------------------------------------------------------------------------------------

    private fun wifiObservations(context: Context, app: Context): List<Observation> {
        val out = mutableListOf<Observation>()
        val wm = try {
            app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        } catch (t: Throwable) {
            out += Observation.error("net.wifi_service", t, LabCategory.NETWORK)
            null
        }
        if (wm == null) {
            out += Observation.unsupported(
                DashboardKeys.WIFI_STATE,
                LabCategory.NETWORK,
                "getSystemService(WIFI_SERVICE) returned null; this device exposes no Wi-Fi manager.",
            )
            out += Observation.unsupported(
                DashboardKeys.WIFI_SSID,
                LabCategory.NETWORK,
                "No WifiManager, so no connection information.",
            )
            return out
        }

        val stateInt = try { wm.wifiState } catch (t: Throwable) { WifiManager.WIFI_STATE_UNKNOWN }
        out += Observation.confirmed(
            DashboardKeys.WIFI_STATE,
            wifiStateLabel(stateInt),
            LabCategory.NETWORK,
            "Wi-Fi must be enabled for Wi-Fi Direct, Miracast and wireless Android Auto to exist at all.",
        )
        out += Observation.confirmed(
            "net.wifi_5ghz_supported",
            try { wm.is5GHzBandSupported() } catch (t: Throwable) { "unreadable" },
            LabCategory.NETWORK,
            "Miracast at useful resolutions and wireless Android Auto both want the 5 GHz radio. " +
                "Whether they can share it is exactly research question 3 and is answered by the " +
                "coexistence matrix, not by this flag.",
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            out += Observation.confirmed(
                "net.wifi_6ghz_supported",
                try { wm.is6GHzBandSupported() } catch (t: Throwable) { "unreadable" },
                LabCategory.NETWORK,
            )
        }

        val info = currentWifiInfo(app, wm)
        if (info == null) {
            out += Observation.notTested(
                DashboardKeys.WIFI_SSID,
                LabCategory.NETWORK,
                "No WifiInfo available. On a disconnected radio this is expected; it is not evidence " +
                    "about any network.",
            )
            return out
        }

        val rawSsid = try { info.ssid ?: "" } catch (t: Throwable) { "" }
        val ssid = rawSsid.trim('"')
        val locationGranted = LabPermissions.isGranted(context, android.Manifest.permission.ACCESS_FINE_LOCATION)
        out += when {
            rawSsid.isBlank() -> Observation.notTested(
                DashboardKeys.WIFI_SSID,
                LabCategory.NETWORK,
                "WifiInfo returned no SSID.",
            )
            ssid.equals(UNKNOWN_SSID, ignoreCase = true) || rawSsid.contains(UNKNOWN_SSID) ->
                Observation.notTested(
                    DashboardKeys.WIFI_SSID,
                    LabCategory.NETWORK,
                    "Android returned \"" + UNKNOWN_SSID + "\". Since Android 10 the SSID is gated " +
                        "behind ACCESS_FINE_LOCATION plus location services being on. This lab " +
                        "deliberately does not request location on Android 13+ (it asks for " +
                        "NEARBY_WIFI_DEVICES instead, which does not unlock the SSID), so this is a " +
                        "designed blind spot, not a device limitation. Fine location granted: " +
                        locationGranted + ".",
                )
            else -> Observation.confirmed(
                DashboardKeys.WIFI_SSID,
                ssid,
                LabCategory.NETWORK,
                "SSID of the infrastructure network. A Miracast group is a separate Wi-Fi Direct " +
                    "network and does not appear here.",
            )
        }

        out += Observation.confirmed(
            "net.wifi_bssid_disclosed",
            try { (info.bssid != null && info.bssid != "02:00:00:00:00:00") } catch (t: Throwable) { "unreadable" },
            LabCategory.NETWORK,
            "Whether the framework disclosed a real BSSID. The address itself is not recorded.",
        )

        val frequency = try { info.frequency } catch (t: Throwable) { -1 }
        val band = NetFacts.bandOf(frequency)
        if (band == null) {
            out += Observation.notTested(
                "net.wifi_band",
                LabCategory.NETWORK,
                "WifiInfo.getFrequency() returned " + frequency + ", which is not a usable channel " +
                    "frequency. Usually means the radio is not associated.",
            )
        } else {
            out += Observation.confirmed("net.wifi_frequency_mhz", frequency, LabCategory.NETWORK)
            out += Observation.confirmed(
                "net.wifi_band",
                band,
                LabCategory.NETWORK,
                "Band of the infrastructure link. A Wi-Fi Direct / Miracast group normally follows " +
                    "the radio the station interface is already on, so this constrains what band a " +
                    "concurrent Miracast session can use.",
            )
        }
        out += Observation.confirmed(
            "net.wifi_link_speed_mbps",
            try { info.linkSpeed } catch (t: Throwable) { -1 },
            LabCategory.NETWORK,
        )
        out += Observation.confirmed(
            "net.wifi_rssi_dbm",
            try { info.rssi } catch (t: Throwable) { "unreadable" },
            LabCategory.NETWORK,
        )
        out += Observation.confirmed(
            "net.wifi_tx_link_speed_mbps",
            try { info.txLinkSpeedMbps } catch (t: Throwable) { -1 },
            LabCategory.NETWORK,
        )
        out += Observation.confirmed(
            "net.wifi_rx_link_speed_mbps",
            try { info.rxLinkSpeedMbps } catch (t: Throwable) { -1 },
            LabCategory.NETWORK,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            out += Observation.confirmed(
                "net.wifi_standard",
                try { wifiStandardLabel(info.wifiStandard) } catch (t: Throwable) { "unreadable" },
                LabCategory.NETWORK,
            )
        }
        return out
    }

    /**
     * The sanctioned way to read WifiInfo differs by version: from Android 12 it comes off the
     * network's transport info, before that from the deprecated WifiManager.getConnectionInfo().
     * Both paths are tried; a null from both is reported as "no info", never as "no Wi-Fi".
     */
    @Suppress("DEPRECATION")
    private fun currentWifiInfo(app: Context, wm: WifiManager): WifiInfo? {
        try {
            val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val active = cm?.activeNetwork
            val caps = if (cm != null && active != null) cm.getNetworkCapabilities(active) else null
            val fromCaps = caps?.transportInfo as? WifiInfo
            if (fromCaps != null) return fromCaps
        } catch (t: Throwable) {
            // Fall through to the legacy path.
        }
        return try {
            wm.connectionInfo
        } catch (t: Throwable) {
            null
        }
    }

    // ------------------------------------------------------------------------------------------

    private fun interfaceObservations(): List<Observation> {
        val out = mutableListOf<Observation>()
        val snap = NetFacts.snapshot()
        if (snap.error != null) {
            out += Observation.error(
                "net.interfaces",
                IllegalStateException(snap.error),
                LabCategory.NETWORK,
            )
            out += Observation.notTested(
                "net.p2p_interface_present",
                LabCategory.NETWORK,
                "Interface enumeration failed, so nothing can be said about a P2P interface.",
            )
            return out
        }

        out += Observation.confirmed(
            "net.interface_count",
            snap.interfaces.size,
            LabCategory.NETWORK,
            "Interfaces the kernel exposes to this unprivileged uid.",
        )
        out += Observation.confirmed(
            "net.interfaces",
            snap.interfaces.joinToString(", ") { it.name + (if (it.isUp) "(up)" else "(down)") }
                .ifBlank { "none" },
            LabCategory.NETWORK,
        )

        val p2p = snap.activeP2p
        out += if (p2p.isNotEmpty()) {
            Observation(
                key = "net.p2p_interface_present",
                value = p2p.joinToString(", ") { it.name },
                status = LabStatus.INFERRED,
                note = "A p2p* interface is up. This is the strongest app-visible evidence that a " +
                    "Wi-Fi Direct group exists right now, and it is INFERRED evidence of a P2P " +
                    "session - not proof of Miracast. Wireless DeX, wireless Android Auto and " +
                    "Wi-Fi Direct file transfer bring up the same interface. Correlate it with the " +
                    "display topology change to distinguish them.",
                category = LabCategory.NETWORK,
            )
        } else if (snap.p2pInterfaces.isNotEmpty()) {
            Observation(
                key = "net.p2p_interface_present",
                value = "present but down: " + snap.p2pInterfaces.joinToString(", ") { it.name },
                status = LabStatus.CONFIRMED,
                note = "A persistent p2p control interface exists (many chipsets keep p2p0 around) " +
                    "but it is not up, so no group is formed.",
                category = LabCategory.NETWORK,
            )
        } else {
            Observation.confirmed(
                "net.p2p_interface_present",
                false,
                LabCategory.NETWORK,
                "No p2p* interface in the enumeration at probe time. Re-check while Smart View is " +
                    "connecting; this value is only meaningful as a before/after pair.",
            )
        }

        val softAp = snap.softApInterfaces
        out += if (softAp.isEmpty()) {
            Observation.confirmed(
                "net.softap_interface_present",
                false,
                LabCategory.NETWORK,
                "No Samsung SoftAP / secondary-radio interface (swlan*, ap0, wlan1) enumerated.",
            )
        } else {
            Observation(
                key = "net.softap_interface_present",
                value = softAp.joinToString(", ") { it.name + (if (it.isUp) "(up)" else "(down)") },
                status = LabStatus.INFERRED,
                note = "A SoftAP or secondary-radio interface exists. Wireless Android Auto uses one " +
                    "on many Samsung builds, so watching it during the coexistence matrix (spec 9) " +
                    "shows how the phone divides the radio.",
                category = LabCategory.NETWORK,
            )
        }

        // The full table, so the report contains the raw evidence and not just the conclusion.
        out += Observation.confirmed(
            "net.interface_table",
            snap.interfaces.joinToString(" | ") { it.line() }.ifBlank { "none" },
            LabCategory.NETWORK,
            "Raw interface table. Addresses are reduced to scope classes on purpose.",
        )
        return out
    }

    private fun wifiStateLabel(state: Int): String = when (state) {
        WifiManager.WIFI_STATE_DISABLED -> "DISABLED"
        WifiManager.WIFI_STATE_DISABLING -> "DISABLING"
        WifiManager.WIFI_STATE_ENABLED -> "ENABLED"
        WifiManager.WIFI_STATE_ENABLING -> "ENABLING"
        else -> "UNKNOWN(" + state + ")"
    }

    private fun wifiStandardLabel(standard: Int): String = when (standard) {
        1 -> "802.11a/b/g (legacy)"
        4 -> "802.11n (Wi-Fi 4)"
        5 -> "802.11ac (Wi-Fi 5)"
        6 -> "802.11ax (Wi-Fi 6)"
        7 -> "802.11ad"
        8 -> "802.11be (Wi-Fi 7)"
        0 -> "unknown"
        else -> "code " + standard
    }
}
