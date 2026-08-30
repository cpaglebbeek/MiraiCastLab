package nl.icthorse.miraicastlab.net

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pManager
import nl.icthorse.miraicastlab.core.DashboardKeys
import nl.icthorse.miraicastlab.core.LabCategory
import nl.icthorse.miraicastlab.core.LabPermissions
import nl.icthorse.miraicastlab.core.LabStatus
import nl.icthorse.miraicastlab.core.Observation
import nl.icthorse.miraicastlab.core.Probe

/**
 * Wi-Fi Direct / P2P - everything the public WifiP2pManager surface will tell an unprivileged app.
 *
 * This probe answers two halves of research question 2.1. The positive half: which P2P peers,
 * groups and device types are visible, and does any peer advertise itself as a display sink. The
 * negative half, which matters just as much for this project, is delegated to
 * [WifiP2pFacts.appInvisibleProtocolFacts]: the Miracast protocol details that no third-party app
 * can reach, recorded as NOT_TESTED with the reason, never as UNSUPPORTED.
 *
 * Permission discipline: without NEARBY_WIFI_DEVICES (API 33+) or ACCESS_FINE_LOCATION (<= API 32)
 * the framework simply refuses to enumerate peers. That is a missing measurement, not evidence of
 * absence, so every peer-dependent key degrades to NOT_TESTED naming the exact permission.
 */
object WifiP2pProbe : Probe {

    override val id = "wifip2p"
    override val title = "Wi-Fi Direct / P2P"

    @SuppressLint("MissingPermission") // Every permission-gated call sits behind an explicit isGranted check.
    override suspend fun observe(context: Context): List<Observation> {
        val out = mutableListOf<Observation>()
        val app = context.applicationContext

        // ---- 1. Does the platform claim the feature at all? -----------------------------------
        val hasFeature: Boolean? = try {
            app.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)
        } catch (t: Throwable) {
            out += Observation.error(DashboardKeys.P2P_SUPPORTED, t, LabCategory.NETWORK)
            null
        }

        if (hasFeature == false) {
            out += Observation.unsupported(
                DashboardKeys.P2P_SUPPORTED,
                LabCategory.NETWORK,
                "PackageManager reports FEATURE_WIFI_DIRECT is absent. Without Wi-Fi Direct there " +
                    "is no Miracast source path on this device.",
            )
            out += Observation.unsupported(
                DashboardKeys.P2P_STATE,
                LabCategory.NETWORK,
                "No Wi-Fi Direct feature, therefore no P2P state to read.",
            )
            out += Observation.unsupported(
                DashboardKeys.P2P_PEERS,
                LabCategory.NETWORK,
                "No Wi-Fi Direct feature, therefore no peer list can exist.",
            )
            out += WifiP2pFacts.appInvisibleProtocolFacts()
            return out
        }
        if (hasFeature == true) {
            out += Observation.confirmed(
                DashboardKeys.P2P_SUPPORTED,
                true,
                LabCategory.NETWORK,
                "PackageManager reports FEATURE_WIFI_DIRECT. This is the transport Miracast and " +
                    "wireless Android Auto both build on.",
            )
        }

        // ---- 2. Is there a manager and a channel? --------------------------------------------
        val manager = try {
            app.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        } catch (t: Throwable) {
            out += Observation.error("wifip2p.service_lookup", t, LabCategory.NETWORK)
            null
        }

        if (manager == null) {
            // The platform answered: the service is not there. That is genuine UNSUPPORTED.
            out += Observation.unsupported(
                DashboardKeys.P2P_STATE,
                LabCategory.NETWORK,
                "getSystemService(WIFI_P2P_SERVICE) returned null; the Wi-Fi P2P service is absent.",
            )
            out += Observation.unsupported(
                DashboardKeys.P2P_PEERS,
                LabCategory.NETWORK,
                "No WifiP2pManager, so no peer list can be requested.",
            )
            out += WifiP2pFacts.appInvisibleProtocolFacts()
            return out
        }

        val channel = WifiP2pFacts.openChannel(manager, app)
        if (channel == null) {
            out += Observation.unsupported(
                DashboardKeys.P2P_STATE,
                LabCategory.NETWORK,
                "WifiP2pManager.initialize() returned no channel; the framework refused to open a " +
                    "P2P channel for this app.",
            )
            out += Observation.unsupported(
                DashboardKeys.P2P_PEERS,
                LabCategory.NETWORK,
                "No P2P channel, so no peer list can be requested.",
            )
            out += WifiP2pFacts.appInvisibleProtocolFacts()
            return out
        }

        try {
            // ---- 3. P2P radio state. Needs no runtime permission (API 29+). -------------------
            val state = WifiP2pFacts.awaitP2pState(manager, channel)
            if (state == null) {
                out += Observation.error(
                    DashboardKeys.P2P_STATE,
                    IllegalStateException("requestP2pState did not call back within " + WifiP2pFacts.CALLBACK_TIMEOUT_MS + " ms"),
                    LabCategory.NETWORK,
                )
            } else {
                out += Observation.confirmed(
                    DashboardKeys.P2P_STATE,
                    WifiP2pFacts.p2pStateLabel(state),
                    LabCategory.NETWORK,
                    if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                        "Wi-Fi P2P is enabled. Note this tracks the P2P framework, not whether a " +
                            "group is currently formed."
                    } else {
                        "Wi-Fi P2P is disabled, usually because Wi-Fi itself is off. Peer discovery " +
                            "and Miracast cannot run in this state."
                    },
                )
            }

            // ---- 4. Everything below here is permission-gated. --------------------------------
            val nearby = LabPermissions.NEARBY
            if (!LabPermissions.isGranted(context, nearby)) {
                val why = "Not granted: " + nearby.permission +
                    ". Android refuses to enumerate P2P peers without it. This is a missing " +
                    "measurement, not evidence that no sink exists."
                out += Observation.notTested(DashboardKeys.P2P_PEERS, LabCategory.NETWORK, why)
                out += Observation.notTested("wifip2p.peer_list", LabCategory.NETWORK, why)
                out += Observation.notTested("wifip2p.group_present", LabCategory.NETWORK, why)
                out += Observation.notTested("wifip2p.connection_formed", LabCategory.NETWORK, why)
                out += Observation.notTested("wifip2p.local_device_name", LabCategory.NETWORK, why)
                out += Observation.notTested("wifip2p.display_sink_candidates", LabCategory.MIRACAST, why)
                out += WifiP2pFacts.appInvisibleProtocolFacts()
                return out
            }

            out += peerObservations(manager, channel)
            out += connectionObservations(manager, channel)
            out += groupObservations(manager, channel)
            out += localDeviceObservations(manager, channel)
        } finally {
            WifiP2pFacts.closeChannel(channel)
        }

        out += WifiP2pFacts.appInvisibleProtocolFacts()
        return out
    }

    // ------------------------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private suspend fun peerObservations(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel,
    ): List<Observation> {
        val out = mutableListOf<Observation>()
        val list = WifiP2pFacts.awaitPeers(manager, channel)
        if (list == null) {
            out += Observation.error(
                DashboardKeys.P2P_PEERS,
                IllegalStateException("requestPeers did not call back within " + WifiP2pFacts.CALLBACK_TIMEOUT_MS + " ms"),
                LabCategory.NETWORK,
            )
            return out
        }

        val peers = list.deviceList.map { WifiP2pFacts.factOf(it) }
        out += Observation.confirmed(
            DashboardKeys.P2P_PEERS,
            peers.size,
            LabCategory.NETWORK,
            "Peers currently in the framework's cache. A count of 0 does NOT mean no sink is " +
                "nearby: requestPeers only reports what discovery has already found. Run START " +
                "DISCOVERY on the Network screen before reading anything into this number.",
        )

        if (peers.isEmpty()) {
            out += Observation.confirmed(
                "wifip2p.peer_list",
                "empty",
                LabCategory.NETWORK,
                "Framework peer cache was empty at probe time.",
            )
            out += Observation.notTested(
                "wifip2p.display_sink_candidates",
                LabCategory.MIRACAST,
                "No peers were in the cache, so no device type could be inspected. Not a statement " +
                    "about the Toyota.",
            )
            return out
        }

        out += Observation.confirmed(
            "wifip2p.peer_list",
            peers.joinToString(" | ") { it.line() },
            LabCategory.NETWORK,
            "Fields readable from WifiP2pDevice by a third-party app.",
        )

        peers.forEachIndexed { i, p ->
            val prefix = "wifip2p.peer." + i + "."
            out += Observation.confirmed(prefix + "name", p.deviceName.ifBlank { "(no name)" }, LabCategory.NETWORK)
            out += Observation.confirmed(
                prefix + "address",
                p.deviceAddress.ifBlank { "(none)" },
                LabCategory.NETWORK,
                if (p.deviceAddress.equals(WifiP2pFacts.ANONYMISED_MAC, ignoreCase = true)) {
                    "Anonymised MAC. Android substitutes " + WifiP2pFacts.ANONYMISED_MAC + " when it " +
                        "will not disclose a hardware address to an app."
                } else {
                    "Peer P2P interface address as reported by the framework."
                },
            )
            out += Observation.confirmed(prefix + "primary_type", p.primaryTypeLabel, LabCategory.NETWORK)
            out += Observation.confirmed(
                prefix + "secondary_type",
                p.secondaryDeviceType.ifBlank { "(none advertised)" },
                LabCategory.NETWORK,
            )
            out += Observation.confirmed(
                prefix + "status",
                p.statusLabel,
                LabCategory.NETWORK,
                "WifiP2pDevice.status. CONNECTED here means a P2P group with this peer exists.",
            )
            out += Observation.confirmed(prefix + "group_owner", p.isGroupOwner, LabCategory.NETWORK)
            out += Observation.confirmed(
                prefix + "wps_methods",
                p.wpsMethods,
                LabCategory.NETWORK,
                "Derived from wpsPbcSupported()/wpsKeypadSupported()/wpsDisplaySupported(). The raw " +
                    "wpsConfigMethods bitmask is a hidden field and is not readable by apps.",
            )
            out += Observation.confirmed(
                prefix + "service_discovery_capable",
                p.serviceDiscoveryCapable,
                LabCategory.NETWORK,
            )

            if (p.looksLikeDisplaySink) {
                // INFERRED and never CONFIRMED: the WPS category says "a display", it does not say
                // "the Toyota Mirai", and it does not say the sink speaks Miracast.
                out += Observation(
                    key = prefix + "display_sink",
                    value = p.deviceName.ifBlank { p.deviceAddress },
                    status = LabStatus.INFERRED,
                    note = "WPS primary device type category 7 (Display): this peer advertises " +
                        "itself as a display device, which is what a Miracast sink does. It is not " +
                        "proof that this peer is the vehicle head unit, nor that it will accept a " +
                        "Wi-Fi Display session.",
                    category = LabCategory.MIRACAST,
                )
            } else if (p.looksLikeMultimediaSink) {
                out += Observation(
                    key = prefix + "multimedia_sink",
                    value = p.deviceName.ifBlank { p.deviceAddress },
                    status = LabStatus.INFERRED,
                    note = "WPS primary device type category 8 (Multimedia Device). Weaker evidence " +
                        "than category 7; set-top boxes and media extenders also use it.",
                    category = LabCategory.MIRACAST,
                )
            }
        }

        val sinks = peers.filter { it.looksLikeDisplaySink }
        out += if (sinks.isEmpty()) {
            Observation.confirmed(
                "wifip2p.display_sink_candidates",
                0,
                LabCategory.MIRACAST,
                "No visible peer advertised WPS category 7 (Display) at probe time. With discovery " +
                    "running this would be meaningful; from a cold cache it is not.",
            )
        } else {
            Observation(
                key = "wifip2p.display_sink_candidates",
                value = sinks.joinToString(", ") { it.deviceName.ifBlank { it.deviceAddress } },
                status = LabStatus.INFERRED,
                note = "Peers advertising WPS category 7 (Display). Candidate Miracast sinks only; " +
                    "identity and protocol support are unverified from an app.",
                category = LabCategory.MIRACAST,
            )
        }
        return out
    }

    @SuppressLint("MissingPermission")
    private suspend fun connectionObservations(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel,
    ): List<Observation> {
        val info = WifiP2pFacts.awaitConnectionInfo(manager, channel)
            ?: return listOf(
                Observation.error(
                    "wifip2p.connection_formed",
                    IllegalStateException("requestConnectionInfo did not call back within " + WifiP2pFacts.CALLBACK_TIMEOUT_MS + " ms"),
                    LabCategory.NETWORK,
                ),
            )
        val out = mutableListOf<Observation>()
        val formed = try { info.groupFormed } catch (t: Throwable) { false }
        out += Observation.confirmed(
            "wifip2p.connection_formed",
            formed,
            LabCategory.NETWORK,
            if (formed) {
                "A Wi-Fi Direct group is formed right now. If Smart View is running, this is that " +
                    "group - but the framework does not label it, so the link to Miracast is inferred."
            } else {
                "No Wi-Fi Direct group is formed at probe time."
            },
        )
        out += Observation.confirmed(
            "wifip2p.is_group_owner",
            try { info.isGroupOwner } catch (t: Throwable) { "unreadable" },
            LabCategory.NETWORK,
            "In a Miracast session the phone (the source) is normally the group owner.",
        )
        // Only whether an address exists, never the address itself.
        out += Observation.confirmed(
            "wifip2p.group_owner_address_present",
            try { info.groupOwnerAddress != null } catch (t: Throwable) { "unreadable" },
            LabCategory.NETWORK,
            "Presence only; the lab does not record addresses.",
        )
        if (formed) {
            out += Observation(
                key = "wifip2p.session_hint",
                value = "P2P group formed",
                status = LabStatus.INFERRED,
                note = "A formed P2P group is consistent with an active Smart View / Miracast " +
                    "session, and also with Wi-Fi Direct file transfer, wireless Android Auto or " +
                    "Wireless DeX. The group alone does not identify the protocol running over it.",
                category = LabCategory.MIRACAST,
            )
        }
        return out
    }

    @SuppressLint("MissingPermission")
    private suspend fun groupObservations(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel,
    ): List<Observation> {
        val held = WifiP2pFacts.awaitGroupInfo(manager, channel)
            ?: return listOf(
                Observation.error(
                    "wifip2p.group_present",
                    IllegalStateException("requestGroupInfo did not call back within " + WifiP2pFacts.CALLBACK_TIMEOUT_MS + " ms"),
                    LabCategory.NETWORK,
                ),
            )
        val group = held.value
        if (group == null) {
            return listOf(
                Observation.confirmed(
                    "wifip2p.group_present",
                    false,
                    LabCategory.NETWORK,
                    "requestGroupInfo delivered null: this app's channel sees no P2P group.",
                ),
            )
        }
        val out = mutableListOf<Observation>()
        out += Observation.confirmed("wifip2p.group_present", true, LabCategory.NETWORK)
        out += Observation.confirmed(
            "wifip2p.group_network_name",
            try { group.networkName ?: "(none)" } catch (t: Throwable) { "unreadable" },
            LabCategory.NETWORK,
            "Wi-Fi Direct group SSID. Miracast groups conventionally start with DIRECT-. The " +
                "passphrase is deliberately not read or logged.",
        )
        out += Observation.confirmed(
            "wifip2p.group_interface",
            try { group.getInterface() ?: "(none)" } catch (t: Throwable) { "unreadable" },
            LabCategory.NETWORK,
            "The kernel interface carrying the group; cross-check it against the interface list.",
        )
        out += Observation.confirmed(
            "wifip2p.group_is_owner",
            try { group.isGroupOwner } catch (t: Throwable) { "unreadable" },
            LabCategory.NETWORK,
        )
        out += Observation.confirmed(
            "wifip2p.group_client_count",
            try { group.clientList?.size ?: 0 } catch (t: Throwable) { "unreadable" },
            LabCategory.NETWORK,
        )
        val frequency = try { group.frequency } catch (t: Throwable) { -1 }
        val band = NetFacts.bandOf(frequency)
        if (band != null) {
            out += Observation.confirmed(
                "wifip2p.group_frequency_mhz",
                frequency,
                LabCategory.NETWORK,
                "Operating band " + band + ". Relevant to research question 3: wireless Android Auto " +
                    "and Miracast contend for the same radio, and a 5 GHz group is the usual " +
                    "high-bandwidth Miracast case.",
            )
        } else {
            out += Observation.notTested(
                "wifip2p.group_frequency_mhz",
                LabCategory.NETWORK,
                "WifiP2pGroup.getFrequency() returned no usable value on this device.",
            )
        }
        val ownerType = try { group.owner?.primaryDeviceType } catch (t: Throwable) { null }
        if (ownerType != null) {
            out += Observation.confirmed(
                "wifip2p.group_owner_type",
                WifiP2pFacts.primaryTypeLabel(ownerType),
                LabCategory.NETWORK,
            )
        }
        return out
    }

    @SuppressLint("MissingPermission")
    private suspend fun localDeviceObservations(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel,
    ): List<Observation> {
        val held = WifiP2pFacts.awaitDeviceInfo(manager, channel)
            ?: return listOf(
                Observation.error(
                    "wifip2p.local_device_name",
                    IllegalStateException("requestDeviceInfo did not call back within " + WifiP2pFacts.CALLBACK_TIMEOUT_MS + " ms"),
                    LabCategory.NETWORK,
                ),
            )
        val device = held.value
            ?: return listOf(
                Observation.unsupported(
                    "wifip2p.local_device_name",
                    LabCategory.NETWORK,
                    "requestDeviceInfo delivered null: the framework disclosed nothing about this " +
                        "device's own P2P identity.",
                ),
            )
        val fact = WifiP2pFacts.factOf(device)
        val out = mutableListOf<Observation>()
        out += Observation.confirmed(
            "wifip2p.local_device_name",
            fact.deviceName.ifBlank { "(none)" },
            LabCategory.NETWORK,
            "The name this phone advertises to P2P peers; it is what the Toyota would list.",
        )
        val anonymised = fact.deviceAddress.equals(WifiP2pFacts.ANONYMISED_MAC, ignoreCase = true)
        out += Observation.confirmed(
            "wifip2p.local_device_address_anonymised",
            anonymised,
            LabCategory.NETWORK,
            if (anonymised) {
                "Android returned " + WifiP2pFacts.ANONYMISED_MAC + " instead of this phone's real " +
                    "P2P MAC. Third-party apps have been denied hardware addresses since Android 6, " +
                    "so an app can never correlate its own P2P identity with what the head unit " +
                    "displays. Important for this project: any 'is that us?' matching must be done " +
                    "by device name, not by address."
            } else {
                "The framework disclosed a non-anonymised local P2P address (" + fact.deviceAddress +
                    "), which is unusual for a third-party app on a modern Android build."
            },
        )
        out += Observation.confirmed(
            "wifip2p.local_device_status",
            fact.statusLabel,
            LabCategory.NETWORK,
        )
        out += Observation.confirmed(
            "wifip2p.local_primary_type",
            fact.primaryTypeLabel,
            LabCategory.NETWORK,
            "How this phone advertises itself. A Miracast source is not a Display; expect a phone " +
                "or multimedia category here.",
        )
        return out
    }
}
