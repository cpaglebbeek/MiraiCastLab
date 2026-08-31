package nl.icthorse.miraicastlab.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import nl.icthorse.miraicastlab.core.DashboardKeys
import nl.icthorse.miraicastlab.core.LabCategory
import nl.icthorse.miraicastlab.core.LabStatus
import nl.icthorse.miraicastlab.core.Observation
import nl.icthorse.miraicastlab.core.ProbeRegistry
import nl.icthorse.miraicastlab.core.SessionLogger
import nl.icthorse.miraicastlab.scan.ScanState

/**
 * The lab's home screen (spec section 5.1).
 *
 * Shows the current state of the device in the terms this project cares about, then nine large
 * buttons that each open one experiment. Everything here is derived from probe observations, so
 * the dashboard never contains its own idea of what the device can do.
 */
@Composable
fun DashboardScreen(onNavigate: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val records by SessionLogger.records.collectAsState()

    var scanning by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }

    // The dashboard and the device-scan screen read the SAME process-wide scan result. Keeping two
    // copies would let them disagree about the device in front of the tester, which is exactly the
    // kind of quiet inconsistency this app exists to avoid.
    val observations: List<Observation> by ScanState.last.collectAsState()

    // Scan on first open, so the dashboard is never empty when the tester opens the app in the car.
    // If a scan already ran on another screen, reuse it rather than repeating seconds of work.
    LaunchedEffect(Unit) {
        if (observations.isEmpty()) runScan(context, { scanning = it }, { progress = it })
    }

    fun value(key: String): String =
        observations.firstOrNull { it.key == key }?.value ?: "-"

    fun status(key: String): LabStatus =
        observations.firstOrNull { it.key == key }?.status ?: LabStatus.NOT_TESTED

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { inner ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(inner),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text("MiraiCast Lab", style = MaterialTheme.typography.displaySmall)
                    Text(
                        "Projection capability explorer - Galaxy Z Fold to Toyota Mirai 2025",
                        style = MaterialTheme.typography.bodyMedium,
                        color = LabColors.TextDim,
                    )
                }
            }

            if (scanning) {
                item {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(progress, style = LabMono, color = LabColors.TextDim)
                    }
                }
            }

            item {
                LabCard("Phone") {
                    Fact("Manufacturer / model", value(DashboardKeys.MANUFACTURER) + " " + value(DashboardKeys.MODEL), status(DashboardKeys.MODEL))
                    Fact("Android", value(DashboardKeys.ANDROID_RELEASE) + "  (API " + value(DashboardKeys.SDK_INT) + ")", status(DashboardKeys.SDK_INT))
                    Fact("One UI", value(DashboardKeys.ONE_UI), status(DashboardKeys.ONE_UI))
                    Fact("Root detected", value(DashboardKeys.ROOT), status(DashboardKeys.ROOT))
                    Fact("Build fingerprint", value(DashboardKeys.FINGERPRINT), status(DashboardKeys.FINGERPRINT))
                }
            }

            item {
                LabCard("Network & Wi-Fi Direct") {
                    Fact("Wi-Fi", value(DashboardKeys.WIFI_STATE) + "  " + value(DashboardKeys.WIFI_SSID), status(DashboardKeys.WIFI_STATE))
                    Fact("Active transport", value(DashboardKeys.ACTIVE_TRANSPORT), status(DashboardKeys.ACTIVE_TRANSPORT))
                    Fact("Wi-Fi Direct support", value(DashboardKeys.P2P_SUPPORTED), status(DashboardKeys.P2P_SUPPORTED))
                    Fact("P2P state / peers", value(DashboardKeys.P2P_STATE) + "  /  " + value(DashboardKeys.P2P_PEERS), status(DashboardKeys.P2P_STATE))
                }
            }

            item {
                LabCard("Displays") {
                    Fact("Active displays", value(DashboardKeys.DISPLAY_COUNT), status(DashboardKeys.DISPLAY_COUNT))
                    Fact("External display", value(DashboardKeys.EXTERNAL_PRESENT), status(DashboardKeys.EXTERNAL_PRESENT))
                    Fact("Built-in resolution", value(DashboardKeys.DEFAULT_RESOLUTION), status(DashboardKeys.DEFAULT_RESOLUTION))
                    Fact("External resolution", value(DashboardKeys.EXTERNAL_RESOLUTION), status(DashboardKeys.EXTERNAL_RESOLUTION))
                    Fact("External name(s)", value(DashboardKeys.EXTERNAL_NAMES), status(DashboardKeys.EXTERNAL_NAMES))
                }
            }

            item {
                LabCard("Audio, casting & Android Auto") {
                    Fact("Audio outputs", value(DashboardKeys.AUDIO_OUTPUTS), status(DashboardKeys.AUDIO_OUTPUTS))
                    Fact("Active output", value(DashboardKeys.AUDIO_ACTIVE), status(DashboardKeys.AUDIO_ACTIVE))
                    Fact("Smart View session", value(DashboardKeys.SMARTVIEW_ACTIVE), status(DashboardKeys.SMARTVIEW_ACTIVE))
                    Fact("Miracast evidence", value(DashboardKeys.MIRACAST_OBSERVATION), status(DashboardKeys.MIRACAST_OBSERVATION))
                    Fact("Android Auto installed", value(DashboardKeys.AA_INSTALLED), status(DashboardKeys.AA_INSTALLED))
                    Fact("Android Auto projection", value(DashboardKeys.AA_PROJECTION), status(DashboardKeys.AA_PROJECTION))
                    Fact("H.264 encoders", value(DashboardKeys.H264_ENCODERS) + " up to " + value(DashboardKeys.H264_MAX_SIZE), status(DashboardKeys.H264_ENCODERS))
                }
            }

            item {
                LabCard("Test run") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Dot(if (scanning) LabColors.Inferred else LabColors.Confirmed)
                        HGap()
                        Text(
                            if (scanning) "scan running" else "idle",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    Gap(6)
                    Text("run " + SessionLogger.testRunId.take(8), style = LabMono, color = LabColors.TextDim)
                    Text(records.size.toString() + " records", style = LabMono, color = LabColors.TextDim)
                    val gaps = DashboardKeys.missingFrom(observations)
                    if (gaps.isNotEmpty() && !scanning) {
                        Gap(6)
                        Text(
                            gaps.size.toString() + " dashboard fields not produced by any probe",
                            style = MaterialTheme.typography.bodyMedium,
                            color = LabColors.Unsupported,
                        )
                    }
                }
            }

            item {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    BigActionButton("RUN DEVICE SCAN", {
                        scope.launch { runScan(context, { scanning = it }, { progress = it }) }
                    }, enabled = !scanning, subtitle = "inventory every capability")
                    BigActionButton("MIRACAST TEST", { onNavigate("miracast") }, subtitle = "guided Smart View wizard")
                    BigActionButton("ROUTES A-D", { onNavigate("routes") }, subtitle = "can an app reach the display Samsung established?")
                    BigActionButton("DEX CONTROL + YOUTUBE", { onNavigate("dex_control") }, subtitle = "keyboard, mouse, hotkeys, and the Intent route that works")
                    BigActionButton("DISPLAY TEST", { onNavigate("display") }, subtitle = "topology, metrics, secondary display")
                    BigActionButton("NETWORK / WI-FI DIRECT", { onNavigate("network") }, subtitle = "P2P peers, transports, interfaces")
                    BigActionButton("AUDIO TEST", { onNavigate("audio") }, subtitle = "tone, stereo, latency pulse")
                    BigActionButton("INPUT TEST", { onNavigate("input") }, subtitle = "touch-back grid and event capture")
                    BigActionButton("MEDIA PROJECTION TEST", { onNavigate("projection") }, subtitle = "capture, VirtualDisplay, H.264")
                    BigActionButton("ANDROID AUTO COEXISTENCE TEST", { onNavigate("android_auto") }, subtitle = "the A-F experiment matrix")
                    BigActionButton("LAB MOTION-STATE TEST", { onNavigate("motion") }, subtitle = "observation only - no interlock bypass")
                    BigActionButton("TEST PATTERN", { onNavigate("scene") }, subtitle = "the diagnostic scene, full screen")
                    BigActionButton("EXPORT REPORT", { onNavigate("report") }, subtitle = "Markdown, JSON, CSV")
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        LabButton("SAMSUNG DEX", { onNavigate("dex") }, Modifier.weight(1f))
                        LabButton("LIVE LOG", { onNavigate("log") }, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/** One labelled fact with its evidence grade. */
@Composable
private fun Fact(label: String, value: String, status: LabStatus) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = LabColors.TextDim)
            Text(value, style = MaterialTheme.typography.bodyLarge)
        }
        StatusChip(status)
    }
}

/**
 * Runs every probe and publishes the result to [ScanState] so the whole app sees one scan.
 *
 * The duration is measured and logged: a scan that suddenly takes twice as long is usually a probe
 * that started timing out, and that is worth seeing before it is mistaken for a device change.
 */
private suspend fun runScan(
    context: android.content.Context,
    setScanning: (Boolean) -> Unit,
    setProgress: (String) -> Unit,
) {
    setScanning(true)
    SessionLogger.log(LabCategory.DEVICE, "device_scan_requested", LabStatus.CONFIRMED)
    val startedAt = android.os.SystemClock.elapsedRealtime()
    try {
        val found = ProbeRegistry.runAll(context) { probe, index, total ->
            setProgress("" + (index + 1) + "/" + total + "  " + probe.title)
        }
        ScanState.publish(found, android.os.SystemClock.elapsedRealtime() - startedAt)
    } finally {
        setScanning(false)
        setProgress("")
    }
}
