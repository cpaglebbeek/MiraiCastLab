package nl.icthorse.miraicastlab.scan

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.icthorse.miraicastlab.core.DashboardKeys
import nl.icthorse.miraicastlab.core.LabCategory
import nl.icthorse.miraicastlab.core.LabStatus
import nl.icthorse.miraicastlab.core.ProbeRegistry
import nl.icthorse.miraicastlab.core.SessionLogger
import nl.icthorse.miraicastlab.ui.BigActionButton
import nl.icthorse.miraicastlab.ui.Dot
import nl.icthorse.miraicastlab.ui.Gap
import nl.icthorse.miraicastlab.ui.HGap
import nl.icthorse.miraicastlab.ui.LabButton
import nl.icthorse.miraicastlab.ui.LabCard
import nl.icthorse.miraicastlab.ui.LabColors
import nl.icthorse.miraicastlab.ui.LabMono
import nl.icthorse.miraicastlab.ui.LabScaffold
import nl.icthorse.miraicastlab.ui.ObservationRow
import nl.icthorse.miraicastlab.ui.StatRow
import nl.icthorse.miraicastlab.ui.StatusChip
import nl.icthorse.miraicastlab.ui.VerdictBanner
import nl.icthorse.miraicastlab.ui.statusColor

/**
 * Capability scanner screen (spec section 6).
 *
 * Runs every registered probe, then presents the result the way the evidence rule demands: grouped,
 * gradeable, filterable by status, and with the dashboard coverage gap shown rather than hidden.
 * The scan itself is read-only; nothing here changes device state.
 */
@Composable
fun DeviceScanScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Survives navigation away and back; a full scan is too expensive to repeat casually.
    val observations by ScanState.last.collectAsState()

    var running by remember { mutableStateOf(false) }
    var progressLabel by remember { mutableStateOf<String?>(null) }
    var progressFraction by remember { mutableStateOf(0f) }
    var filters by remember { mutableStateOf(emptySet<LabStatus>()) }
    var expanded by remember { mutableStateOf(emptySet<LabCategory>()) }

    val counts = remember(observations) {
        LabStatus.values().toList().associateWith { s -> observations.count { it.status == s } }
    }
    val visible = remember(observations, filters) {
        if (filters.isEmpty()) observations else observations.filter { it.status in filters }
    }
    val grouped = remember(visible) { visible.groupBy { it.category } }
    val categories = remember(grouped) { LabCategory.values().filter { grouped.containsKey(it) } }
    val missing = remember(observations) {
        if (observations.isEmpty()) emptyList() else DashboardKeys.missingFrom(observations)
    }
    val durationMs = ScanState.lastDurationMs

    val runScan: () -> Unit = {
        // Guarded rather than only relying on the disabled button: the button is not the only
        // possible caller and a second concurrent runAll would interleave two probe sets.
        if (!running) {
            running = true
            progressFraction = 0f
            progressLabel = "starting"
            val app = context.applicationContext
            scope.launch {
                val startedAt = SystemClock.elapsedRealtime()
                SessionLogger.log(
                    LabCategory.DEVICE,
                    "capability_scan_started",
                    LabStatus.OBSERVED,
                    mapOf("probes" to ProbeRegistry.all.size.toString()),
                )
                // Probes touch the filesystem and MediaCodecList; keep that off the main thread.
                val result = withContext(Dispatchers.Default) {
                    ProbeRegistry.runAll(app) { probe, index, total ->
                        progressLabel = probe.title
                        progressFraction = index.toFloat() / total.toFloat()
                    }
                }
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                ScanState.publish(result, elapsed)
                val gaps = DashboardKeys.missingFrom(result)
                SessionLogger.log(
                    LabCategory.DEVICE,
                    "capability_scan_duration",
                    LabStatus.OBSERVED,
                    mapOf(
                        "durationMs" to elapsed.toString(),
                        "observations" to result.size.toString(),
                        "dashboardGaps" to if (gaps.isEmpty()) "none" else gaps.joinToString(","),
                    ),
                )
                progressFraction = 1f
                progressLabel = null
                running = false
            }
        }
    }

    LabScaffold(
        title = "Capability scan",
        subtitle = ProbeRegistry.all.size.toString() + " probes, read-only",
        onBack = onBack,
    ) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 6.dp, bottom = 32.dp),
        ) {
            item {
                LabCard {
                    BigActionButton(
                        text = if (running) "SCANNING..." else "RUN FULL SCAN",
                        onClick = runScan,
                        enabled = !running,
                        subtitle = "Build, features, displays, network, audio, input, codecs",
                    )
                    if (running || progressLabel != null) {
                        Gap(10)
                        ProgressBar(progressFraction)
                        Gap(6)
                        Text(
                            (progressLabel ?: "") + "  " +
                                (progressFraction * 100f).toInt().toString() + "%",
                            style = LabMono,
                            color = LabColors.TextDim,
                        )
                    }
                    Gap(12)
                    StatRow(
                        observations.size.toString() to "observations",
                        categories.size.toString() to "categories",
                        secondsText(durationMs) to "seconds",
                    )
                }
            }

            if (observations.isEmpty()) {
                item {
                    LabCard(title = "No scan yet") {
                        Text(
                            "Nothing has been measured in this run. Every value on the dashboard " +
                                "comes from this scan, so run it before drawing any conclusion.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = LabColors.TextDim,
                        )
                    }
                }
            } else {
                item {
                    if (missing.isEmpty()) {
                        VerdictBanner(
                            LabStatus.CONFIRMED,
                            "Dashboard coverage complete",
                            "All " + DashboardKeys.ALL.size + " dashboard keys were produced by a probe.",
                        )
                    } else {
                        VerdictBanner(
                            LabStatus.NOT_TESTED,
                            missing.size.toString() + " dashboard keys missing",
                            "No probe produced: " + missing.joinToString(", ") +
                                ". A missing key is a coverage gap, not a negative result.",
                        )
                    }
                }

                item {
                    LabCard(title = "Filter") {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            verticalAlignment = Alignment.Top,
                        ) {
                            LabStatus.values().forEach { status ->
                                val selected = filters.isEmpty() || status in filters
                                Column(
                                    Modifier.padding(end = 10.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    StatusChip(
                                        status,
                                        Modifier
                                            .alpha(if (selected) 1f else 0.35f)
                                            .clickable {
                                                filters = if (status in filters) {
                                                    filters - status
                                                } else {
                                                    filters + status
                                                }
                                            },
                                    )
                                    Text(
                                        (counts[status] ?: 0).toString(),
                                        style = LabMono,
                                        color = statusColor(status),
                                    )
                                }
                            }
                        }
                        Gap(10)
                        Row(horizontalArrangement = Arrangement.Start) {
                            LabButton("ALL", onClick = { filters = emptySet() })
                            HGap(8)
                            LabButton(
                                "EXPAND ALL",
                                onClick = { expanded = LabCategory.values().toSet() },
                            )
                            HGap(8)
                            LabButton("COLLAPSE", onClick = { expanded = emptySet() })
                        }
                        if (filters.isNotEmpty()) {
                            Gap(8)
                            Text(
                                "Showing " + visible.size + " of " + observations.size +
                                    " observations.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = LabColors.TextDim,
                            )
                        }
                    }
                }

                categories.forEach { category ->
                    val rows = grouped[category].orEmpty()
                    val isOpen = category in expanded
                    item {
                        LabCard {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        expanded = if (isOpen) {
                                            expanded - category
                                        } else {
                                            expanded + category
                                        }
                                    },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    if (isOpen) "-" else "+",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = LabColors.Accent,
                                )
                                HGap(12)
                                Text(
                                    category.name,
                                    style = MaterialTheme.typography.titleLarge,
                                    modifier = Modifier.weight(1f),
                                )
                                // One dot per distinct grade in this group: the shape of the
                                // evidence is visible without opening the card.
                                rows.map { it.status }
                                    .distinct()
                                    .sortedBy { it.ordinal }
                                    .forEach { s ->
                                        Dot(statusColor(s))
                                        HGap(4)
                                    }
                                HGap(6)
                                Text(rows.size.toString(), style = LabMono, color = LabColors.TextDim)
                            }
                        }
                    }
                    if (isOpen) {
                        items(rows) { o ->
                            Column(Modifier.padding(horizontal = 26.dp)) {
                                ObservationRow(o)
                            }
                        }
                        item { Gap(6) }
                    }
                }

                item {
                    LabCard(title = "Reading these grades") {
                        Text(
                            "UNSUPPORTED means an API answered that the capability is absent here. " +
                                "NOT_TESTED means the test could not run at all - hardware absent, " +
                                "permission denied, or the query blocked - and never becomes " +
                                "UNSUPPORTED. ERROR means the probe itself failed and says nothing " +
                                "about the capability.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = LabColors.TextDim,
                        )
                    }
                }
            }
        }
    }
}

/** Minimal determinate bar. Hand-drawn so it cannot drift with Material3 API churn. */
@Composable
private fun ProgressBar(fraction: Float) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(LabColors.Line),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0.02f, 1f))
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(LabColors.Accent),
        )
    }
}

/** Locale-free "1.4" style seconds, because this string ends up in the report verbatim. */
private fun secondsText(ms: Long): String {
    if (ms <= 0L) return "-"
    return (ms / 1000L).toString() + "." + ((ms % 1000L) / 100L).toString()
}
