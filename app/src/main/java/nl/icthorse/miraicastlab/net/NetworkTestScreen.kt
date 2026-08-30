package nl.icthorse.miraicastlab.net

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.icthorse.miraicastlab.core.LabCategory
import nl.icthorse.miraicastlab.core.LabPermissions
import nl.icthorse.miraicastlab.core.LabStatus
import nl.icthorse.miraicastlab.core.Observation
import nl.icthorse.miraicastlab.core.SessionLogger
import nl.icthorse.miraicastlab.core.safeObserve
import nl.icthorse.miraicastlab.ui.Gap
import nl.icthorse.miraicastlab.ui.LabButton
import nl.icthorse.miraicastlab.ui.LabCard
import nl.icthorse.miraicastlab.ui.LabColors
import nl.icthorse.miraicastlab.ui.LabMono
import nl.icthorse.miraicastlab.ui.MonoBlock
import nl.icthorse.miraicastlab.ui.ObservationRow
import nl.icthorse.miraicastlab.ui.LabScaffold
import nl.icthorse.miraicastlab.ui.StatRow
import nl.icthorse.miraicastlab.ui.StatusChip
import nl.icthorse.miraicastlab.ui.VerdictBanner
import nl.icthorse.miraicastlab.ui.statusColor

/** How often the interface and peer tables are re-read while the screen is open. */
private const val REFRESH_MS = 2000L

/**
 * Network & Wi-Fi Direct.
 *
 * The point of this screen is to be *open and watching* while the tester starts Smart View from the
 * quick settings panel. Two tables refresh every two seconds: the Wi-Fi Direct peer list and the
 * kernel interface list. The moment a Miracast session forms, a "p2p-*" interface appears - that
 * transition, with its timestamp, is the strongest evidence an unprivileged app can capture that
 * something happened, and it lands in the JSONL evidence file automatically.
 *
 * Everything the app *cannot* see is on the screen too, as NOT_TESTED rows with the reason. That is
 * a required output of the project (spec 2.1), not an apology.
 */
@Composable
fun NetworkTestScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = remember(context) { context.applicationContext }
    val scope = rememberCoroutineScope()
    val watch = remember { P2pWatch() }
    val invisible = remember { WifiP2pFacts.appInvisibleProtocolFacts() }

    var nearbyGranted by remember {
        mutableStateOf(LabPermissions.isGranted(context, LabPermissions.NEARBY))
    }
    var probeOut by remember { mutableStateOf<List<Observation>>(emptyList()) }
    var probing by remember { mutableStateOf(false) }
    var ifaces by remember { mutableStateOf(NetFacts.Snapshot(emptyList())) }
    var refreshes by remember { mutableStateOf(0) }

    val manager = remember(app) {
        try {
            app.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        } catch (t: Throwable) {
            null
        }
    }
    val channel = remember(manager) {
        manager?.let { m ->
            WifiP2pFacts.openChannel(m, app) {
                SessionLogger.log(LabCategory.NETWORK, "p2p_channel_disconnected", LabStatus.OBSERVED)
            }
        }
    }

    // The channel holds a binder to the framework; it must not outlive the screen.
    DisposableEffect(channel) {
        onDispose { WifiP2pFacts.closeChannel(channel) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        nearbyGranted = granted
        SessionLogger.log(
            LabCategory.NETWORK,
            "nearby_permission_result",
            LabStatus.OBSERVED,
            mapOf(
                "permission" to LabPermissions.NEARBY.permission,
                "granted" to granted.toString(),
            ),
        )
        if (granted && manager != null && channel != null) requestPeersInto(manager, channel, watch)
    }

    // Framework broadcasts. RECEIVER_NOT_EXPORTED is correct here: these are protected system
    // broadcasts, which are still delivered to a non-exported receiver.
    DisposableEffect(channel, nearbyGranted) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent == null) return
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val st = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                        watch.p2pState = st
                        watch.note("P2P framework -> " + WifiP2pFacts.p2pStateLabel(st))
                        SessionLogger.log(
                            LabCategory.NETWORK,
                            "p2p_state_changed",
                            LabStatus.OBSERVED,
                            mapOf("state" to WifiP2pFacts.p2pStateLabel(st)),
                        )
                    }

                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        watch.note("peer list changed")
                        SessionLogger.log(LabCategory.NETWORK, "p2p_peers_changed", LabStatus.OBSERVED)
                        if (nearbyGranted && manager != null && channel != null) {
                            requestPeersInto(manager, channel, watch)
                        }
                    }

                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        @Suppress("DEPRECATION")
                        val info = intent.getParcelableExtra<WifiP2pInfo>(
                            WifiP2pManager.EXTRA_WIFI_P2P_INFO,
                        )
                        val formed = info?.groupFormed == true
                        val owner = info?.isGroupOwner == true
                        watch.connection = if (info == null) {
                            "connection changed, no info extra"
                        } else {
                            "groupFormed=" + formed + "  isGroupOwner=" + owner
                        }
                        watch.note(if (formed) "P2P group FORMED" else "P2P group not formed")
                        SessionLogger.log(
                            LabCategory.NETWORK,
                            "p2p_connection_changed",
                            LabStatus.OBSERVED,
                            mapOf(
                                "groupFormed" to formed.toString(),
                                "isGroupOwner" to owner.toString(),
                            ),
                        )
                    }

                    WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION -> {
                        val st = intent.getIntExtra(WifiP2pManager.EXTRA_DISCOVERY_STATE, -1)
                        val started = st == WifiP2pManager.WIFI_P2P_DISCOVERY_STARTED
                        watch.discovering = started
                        watch.note(if (started) "discovery running" else "discovery stopped")
                        SessionLogger.log(
                            LabCategory.NETWORK,
                            "p2p_discovery_state_changed",
                            LabStatus.OBSERVED,
                            mapOf("discovering" to started.toString()),
                        )
                    }

                    WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                        @Suppress("DEPRECATION")
                        val device = intent.getParcelableExtra<WifiP2pDevice>(
                            WifiP2pManager.EXTRA_WIFI_P2P_DEVICE,
                        )
                        if (device != null) {
                            val f = WifiP2pFacts.factOf(device)
                            watch.localDevice = f.deviceName.ifBlank { "(no name)" } +
                                "  " + f.deviceAddress + "  " + f.statusLabel
                            SessionLogger.log(
                                LabCategory.NETWORK,
                                "p2p_this_device_changed",
                                LabStatus.OBSERVED,
                                f.details(),
                            )
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        SessionLogger.log(LabCategory.NETWORK, "network_screen_watching", LabStatus.OBSERVED)
        onDispose {
            try {
                app.unregisterReceiver(receiver)
            } catch (t: Throwable) {
                // Already gone; unregistering twice must not crash a diagnostic tool.
            }
            SessionLogger.log(LabCategory.NETWORK, "network_screen_stopped_watching", LabStatus.OBSERVED)
        }
    }

    // The 2 s heartbeat: interface table, peer table, and a logged transition whenever a p2p
    // interface comes up or goes away. This is the sensor that catches Smart View connecting.
    LaunchedEffect(Unit) {
        var lastP2pUp = false
        while (true) {
            val snap = withContext(Dispatchers.IO) { NetFacts.snapshot() }
            ifaces = snap
            refreshes += 1
            val up = snap.activeP2p.isNotEmpty()
            if (up != lastP2pUp) {
                lastP2pUp = up
                SessionLogger.log(
                    LabCategory.NETWORK,
                    if (up) "p2p_interface_up" else "p2p_interface_down",
                    LabStatus.OBSERVED,
                    mapOf(
                        "p2pInterfaces" to snap.p2pInterfaces.joinToString(",") { it.name },
                        "allInterfaces" to snap.interfaces.joinToString(",") { it.name },
                        "meaning" to "INFERRED evidence of a Wi-Fi Direct group, not proof of Miracast",
                    ),
                )
            }
            if (nearbyGranted && manager != null && channel != null) {
                requestPeersInto(manager, channel, watch)
            }
            delay(REFRESH_MS)
        }
    }

    val runProbes: () -> Unit = {
        scope.launch {
            probing = true
            val found = WifiP2pProbe.safeObserve(context) + ConnectivityProbe.safeObserve(context)
            SessionLogger.logAll(found)
            probeOut = found
            probing = false
        }
        Unit
    }

    LaunchedEffect(Unit) { runProbes() }

    val sinkCandidates = watch.peers.filter { it.looksLikeDisplaySink }
    val verdict: Pair<LabStatus, String> = when {
        manager == null ->
            LabStatus.UNSUPPORTED to "No Wi-Fi P2P service on this device"
        channel == null ->
            LabStatus.UNSUPPORTED to "The framework refused a Wi-Fi P2P channel"
        !nearbyGranted ->
            LabStatus.NOT_TESTED to "Peer discovery not permitted"
        watch.p2pState == WifiP2pManager.WIFI_P2P_STATE_DISABLED ->
            LabStatus.OBSERVED to "Wi-Fi Direct is currently disabled"
        sinkCandidates.isNotEmpty() ->
            LabStatus.INFERRED to (sinkCandidates.size.toString() + " peer(s) advertise a display sink")
        watch.peers.isNotEmpty() ->
            LabStatus.OBSERVED to (watch.peers.size.toString() + " Wi-Fi Direct peer(s) visible")
        ifaces.activeP2p.isNotEmpty() ->
            LabStatus.INFERRED to "A p2p interface is up: a Wi-Fi Direct group exists"
        else ->
            LabStatus.NOT_TESTED to "Nothing observed yet"
    }
    val verdictStatus = verdict.first
    val verdictHead = verdict.second

    LabScaffold(
        title = "Network & Wi-Fi Direct",
        onBack = onBack,
        subtitle = if (watch.discovering) "discovery running" else "watching, refresh " + REFRESH_MS + " ms",
    ) {
        LazyColumn(Modifier.fillMaxSize()) {

            item {
                VerdictBanner(
                    status = verdictStatus,
                    headline = verdictHead,
                    detail = "Wi-Fi Direct is the transport Miracast rides on. Nothing on this " +
                        "screen proves a Miracast session by itself; it proves a P2P session.",
                )
            }

            item {
                LabCard("Live state") {
                    StatRow(
                        watch.peers.size.toString() to "peers",
                        sinkCandidates.size.toString() to "sink candidates",
                        ifaces.activeP2p.size.toString() to "p2p ifaces up",
                        watch.peerEvents.toString() to "peer events",
                        refreshes.toString() to "refreshes",
                    )
                    Gap(10)
                    KeyLine("P2P framework", if (watch.p2pState < 0) "not reported yet" else WifiP2pFacts.p2pStateLabel(watch.p2pState))
                    KeyLine("Discovery", if (watch.discovering) "RUNNING" else "stopped")
                    KeyLine("Connection", watch.connection)
                    KeyLine("This device", watch.localDevice)
                    KeyLine("Last event", watch.lastEvent)
                }
            }

            item {
                LabCard("Permission: " + LabPermissions.NEARBY.permission.substringAfterLast('.')) {
                    Row {
                        StatusChip(if (nearbyGranted) LabStatus.CONFIRMED else LabStatus.NOT_TESTED)
                    }
                    Gap(8)
                    Text(LabPermissions.NEARBY.why, style = MaterialTheme.typography.bodyMedium)
                    Gap(4)
                    Text(
                        "Needed for: " + LabPermissions.NEARBY.neededFor,
                        style = MaterialTheme.typography.bodyMedium,
                        color = LabColors.TextDim,
                    )
                    Gap(8)
                    if (nearbyGranted) {
                        Text(
                            "Granted. Peer enumeration is live.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = statusColor(LabStatus.CONFIRMED),
                        )
                    } else {
                        Text(
                            "Denied or not yet asked. Every peer-dependent finding on this screen " +
                                "stays NOT_TESTED - it will never be reported as \"no sink exists\".",
                            style = MaterialTheme.typography.bodyMedium,
                            color = statusColor(LabStatus.NOT_TESTED),
                        )
                        Gap(8)
                        LabButton(
                            text = "GRANT PERMISSION",
                            onClick = { permissionLauncher.launch(LabPermissions.NEARBY.permission) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            item {
                LabCard("Peer discovery") {
                    Text(
                        "Discovery makes the phone advertise and scan on the P2P social channels. " +
                            "It can disturb an already-running Smart View session, so for a " +
                            "coexistence test start discovery FIRST or leave it off entirely and " +
                            "watch the interface table instead.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = LabColors.TextDim,
                    )
                    Gap(10)
                    Row(Modifier.fillMaxWidth()) {
                        LabButton(
                            text = "START DISCOVERY",
                            onClick = {
                                if (manager != null && channel != null) {
                                    startDiscovery(manager, channel, watch)
                                }
                            },
                            enabled = nearbyGranted && manager != null && channel != null && !watch.discovering,
                            modifier = Modifier.weight(1f),
                        )
                        Text("  ")
                        LabButton(
                            text = "STOP DISCOVERY",
                            onClick = {
                                if (manager != null && channel != null) {
                                    stopDiscovery(manager, channel, watch)
                                }
                            },
                            enabled = nearbyGranted && manager != null && channel != null,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Gap(8)
                    LabButton(
                        text = if (probing) "RUNNING PROBES..." else "RE-RUN NETWORK PROBES",
                        onClick = runProbes,
                        enabled = !probing,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Gap(8)
                    LabButton(
                        text = "MARK: SMART VIEW CONNECTED",
                        onClick = {
                            SessionLogger.marker(
                                "tester marker: Smart View reported connected; p2p ifaces up = " +
                                    ifaces.activeP2p.joinToString(",") { it.name }.ifBlank { "none" },
                                LabCategory.NETWORK,
                            )
                            watch.note("tester marker recorded")
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            item {
                LabCard("Wi-Fi Direct peers (" + watch.peers.size + ")") {
                    if (!nearbyGranted) {
                        Text(
                            "NOT_TESTED - " + LabPermissions.NEARBY.permission + " is not granted, " +
                                "so Android will not enumerate peers.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = statusColor(LabStatus.NOT_TESTED),
                        )
                    } else if (watch.peers.isEmpty()) {
                        Text(
                            "No peers in the framework cache. This is not evidence that no sink is " +
                                "nearby: without discovery running the cache stays empty.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = LabColors.TextDim,
                        )
                    } else {
                        watch.peers.forEach { p ->
                            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Row {
                                    StatusChip(
                                        if (p.looksLikeDisplaySink) LabStatus.INFERRED else LabStatus.OBSERVED,
                                    )
                                    Text(
                                        "  " + p.deviceName.ifBlank { "(no name)" },
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                }
                                Text(p.deviceAddress, style = LabMono, color = LabColors.TextDim)
                                Text(
                                    p.primaryTypeLabel + "  |  " + p.statusLabel +
                                        (if (p.isGroupOwner) "  |  GROUP OWNER" else "") +
                                        "  |  WPS " + p.wpsMethods,
                                    style = LabMono,
                                    color = LabColors.TextDim,
                                )
                                if (p.looksLikeDisplaySink) {
                                    Text(
                                        "INFERRED: advertises WPS category 7 (Display). Candidate " +
                                            "Miracast sink; identity unverified from an app.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = statusColor(LabStatus.INFERRED),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                LabCard("Network interfaces (" + ifaces.interfaces.size + ")") {
                    val err = ifaces.error
                    if (err != null) {
                        Text(
                            "Enumeration failed: " + err,
                            style = MaterialTheme.typography.bodyMedium,
                            color = statusColor(LabStatus.ERROR),
                        )
                    } else {
                        Text(
                            if (ifaces.activeP2p.isEmpty()) {
                                "No p2p* interface is up. Keep this screen open and start Smart " +
                                    "View: the row appears within about two seconds of the group forming."
                            } else {
                                "p2p interface UP: " + ifaces.activeP2p.joinToString(", ") { it.name } +
                                    " - INFERRED evidence of an active Wi-Fi Direct group."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (ifaces.activeP2p.isEmpty()) LabColors.TextDim
                            else statusColor(LabStatus.INFERRED),
                        )
                        Gap(8)
                        MonoBlock(
                            ifaces.interfaces.joinToString("\n") { it.line() }
                                .ifBlank { "no interfaces enumerated" },
                        )
                        Gap(6)
                        Text(
                            "Addresses are reduced to scope classes on purpose; the lab records " +
                                "that an address exists, never which one.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = LabColors.TextDim,
                        )
                    }
                }
            }

            item {
                LabCard("What a third-party app cannot see") {
                    Text(
                        "These are NOT_TESTED, never UNSUPPORTED. The Miracast session may be " +
                            "running perfectly while none of it is reachable from here: the API is " +
                            "missing, not the capability.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = LabColors.TextDim,
                    )
                    Gap(8)
                    invisible.forEach { ObservationRow(it) }
                }
            }

            item {
                LabCard("Probe output (" + probeOut.size + ")") {
                    if (probeOut.isEmpty()) {
                        Text(
                            if (probing) "Running..." else "No probe output yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = LabColors.TextDim,
                        )
                    } else {
                        probeOut.forEach { ObservationRow(it) }
                    }
                }
            }

            item { Gap(24) }
        }
    }
}

/** One aligned label/value line for the live-state card. */
@Composable
private fun KeyLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = LabMono, color = LabColors.TextDim, modifier = Modifier.weight(0.42f))
        Text(value, style = LabMono, modifier = Modifier.weight(0.58f))
    }
}

/**
 * Mutable observation state for the screen, kept outside composition so the BroadcastReceiver and
 * the polling loop write to one object instead of racing over captured state handles.
 *
 * It also owns the peer diff: appearances, disappearances and status transitions are logged with
 * their timestamps, which is what turns "I saw the car in the list" into evidence.
 */
private class P2pWatch {
    var peers by mutableStateOf<List<WifiP2pFacts.PeerFact>>(emptyList())
    var p2pState by mutableStateOf(-1)
    var discovering by mutableStateOf(false)
    var lastEvent by mutableStateOf("waiting")
    var connection by mutableStateOf("no connection event yet")
    var localDevice by mutableStateOf("not reported yet")
    var peerEvents by mutableStateOf(0)

    /** Address (or name, when the address is withheld) to last seen fact. */
    private val known = LinkedHashMap<String, WifiP2pFacts.PeerFact>()

    fun note(text: String) {
        lastEvent = text
    }

    fun submit(devices: List<WifiP2pDevice>) {
        val facts = devices.map { WifiP2pFacts.factOf(it) }
        val current = LinkedHashMap<String, WifiP2pFacts.PeerFact>()
        facts.forEach { f ->
            current[f.deviceAddress.ifBlank { f.deviceName.ifBlank { "peer-" + current.size } }] = f
        }

        for (entry in current) {
            val previous = known[entry.key]
            val f = entry.value
            if (previous == null) {
                peerEvents += 1
                SessionLogger.log(
                    LabCategory.NETWORK,
                    "p2p_peer_appeared",
                    LabStatus.OBSERVED,
                    f.details() + mapOf(
                        "displaySinkCandidate" to f.looksLikeDisplaySink.toString(),
                        "evidenceNote" to "device type is advertised by the peer; it identifies a " +
                            "category, not a specific vehicle",
                    ),
                )
            } else if (previous.statusLabel != f.statusLabel) {
                peerEvents += 1
                SessionLogger.log(
                    LabCategory.NETWORK,
                    "p2p_peer_status_changed",
                    LabStatus.OBSERVED,
                    f.details() + mapOf("previousStatus" to previous.statusLabel),
                )
            }
        }
        for (entry in known) {
            if (!current.containsKey(entry.key)) {
                peerEvents += 1
                SessionLogger.log(
                    LabCategory.NETWORK,
                    "p2p_peer_disappeared",
                    LabStatus.OBSERVED,
                    entry.value.details(),
                )
            }
        }

        known.clear()
        known.putAll(current)
        peers = facts
    }
}

// The permission is checked by the caller before every one of these runs; the lint suppression
// documents that, it does not paper over a missing check.

@SuppressLint("MissingPermission")
private fun requestPeersInto(
    manager: WifiP2pManager,
    channel: WifiP2pManager.Channel,
    watch: P2pWatch,
) {
    try {
        manager.requestPeers(channel) { list ->
            watch.submit(list?.deviceList?.toList() ?: emptyList())
        }
    } catch (t: Throwable) {
        watch.note("requestPeers threw: " + t::class.java.simpleName)
    }
}

@SuppressLint("MissingPermission")
private fun startDiscovery(
    manager: WifiP2pManager,
    channel: WifiP2pManager.Channel,
    watch: P2pWatch,
) {
    SessionLogger.log(LabCategory.NETWORK, "p2p_discovery_requested", LabStatus.OBSERVED)
    try {
        manager.discoverPeers(
            channel,
            object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    watch.discovering = true
                    watch.note("discovery started")
                    SessionLogger.log(
                        LabCategory.NETWORK,
                        "p2p_discovery_started",
                        LabStatus.OBSERVED,
                    )
                }

                override fun onFailure(reason: Int) {
                    watch.discovering = false
                    val label = WifiP2pFacts.actionFailureLabel(reason)
                    watch.note("discovery failed: " + label)
                    SessionLogger.log(
                        LabCategory.NETWORK,
                        "p2p_discovery_failed",
                        // Only P2P_UNSUPPORTED is a statement about the capability. BUSY and ERROR
                        // say something about this moment, not about the device.
                        if (reason == WifiP2pManager.P2P_UNSUPPORTED) LabStatus.UNSUPPORTED
                        else LabStatus.OBSERVED,
                        mapOf("reason" to label),
                    )
                }
            },
        )
    } catch (t: Throwable) {
        watch.note("discoverPeers threw: " + t::class.java.simpleName)
        SessionLogger.log(
            LabCategory.NETWORK,
            "p2p_discovery_error",
            LabStatus.ERROR,
            mapOf("throwable" to (t.message ?: t::class.java.simpleName)),
        )
    }
}

@SuppressLint("MissingPermission")
private fun stopDiscovery(
    manager: WifiP2pManager,
    channel: WifiP2pManager.Channel,
    watch: P2pWatch,
) {
    try {
        manager.stopPeerDiscovery(
            channel,
            object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    watch.discovering = false
                    watch.note("discovery stopped")
                    SessionLogger.log(
                        LabCategory.NETWORK,
                        "p2p_discovery_stopped",
                        LabStatus.OBSERVED,
                    )
                }

                override fun onFailure(reason: Int) {
                    val label = WifiP2pFacts.actionFailureLabel(reason)
                    watch.note("stop failed: " + label)
                    SessionLogger.log(
                        LabCategory.NETWORK,
                        "p2p_discovery_stop_failed",
                        LabStatus.OBSERVED,
                        mapOf("reason" to label),
                    )
                }
            },
        )
    } catch (t: Throwable) {
        watch.note("stopPeerDiscovery threw: " + t::class.java.simpleName)
    }
}
