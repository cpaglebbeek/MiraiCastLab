package nl.icthorse.miraicastlab.report

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import nl.icthorse.miraicastlab.core.LabCategory
import nl.icthorse.miraicastlab.core.LabStatus
import nl.icthorse.miraicastlab.core.LogRecord
import nl.icthorse.miraicastlab.core.SessionLogger
import nl.icthorse.miraicastlab.ui.Dot
import nl.icthorse.miraicastlab.ui.Gap
import nl.icthorse.miraicastlab.ui.HGap
import nl.icthorse.miraicastlab.ui.LabButton
import nl.icthorse.miraicastlab.ui.LabColors
import nl.icthorse.miraicastlab.ui.LabMono
import nl.icthorse.miraicastlab.ui.LabScaffold
import nl.icthorse.miraicastlab.ui.StatusChip
import nl.icthorse.miraicastlab.ui.statusColor

/**
 * Live log viewer (spec section 13).
 *
 * The buffer holds only the current test run, so what is on screen is unambiguously one session.
 * Pause freezes a snapshot rather than stopping the logger: a test in a moving vehicle must keep
 * recording while the tester reads.
 */
@Composable
fun LogViewerScreen(onBack: () -> Unit) {
    val live by SessionLogger.records.collectAsState()

    var paused by remember { mutableStateOf(false) }
    var frozen by remember { mutableStateOf(emptyList<LogRecord>()) }
    var categories by remember { mutableStateOf(emptySet<LabCategory>()) }
    var statuses by remember { mutableStateOf(emptySet<LabStatus>()) }
    var query by remember { mutableStateOf("") }
    var autoScroll by remember { mutableStateOf(true) }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val source = if (paused) frozen else live

    // Newest first: in a vehicle the tester looks at what just happened, not at the run start.
    val filtered = remember(source, categories, statuses, query) {
        val q = query.trim().lowercase()
        source.filter { r ->
            (categories.isEmpty() || r.category in categories) &&
                (statuses.isEmpty() || r.status in statuses) &&
                (
                    q.isEmpty() || r.event.lowercase().contains(q) ||
                        r.details.any { (k, v) ->
                            k.lowercase().contains(q) || v.lowercase().contains(q)
                        }
                    )
        }.asReversed()
    }

    LaunchedEffect(filtered.size, autoScroll, paused) {
        if (autoScroll && !paused && filtered.isNotEmpty()) {
            listState.scrollToItem(0)
        }
    }

    LabScaffold(
        title = "Live log",
        subtitle = "run " + SessionLogger.testRunId.take(8) + " - " + live.size + " records",
        onBack = onBack,
    ) {
        Column(Modifier.fillMaxSize()) {

            // ---- run identity: which session am I looking at
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Dot(if (paused) LabColors.Inferred else LabColors.Confirmed)
                    HGap()
                    Text(
                        if (paused) "PAUSED - logger keeps recording" else "LIVE",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (paused) LabColors.Inferred else LabColors.Confirmed,
                    )
                }
                Text("testRunId " + SessionLogger.testRunId, style = LabMono, color = LabColors.TextDim)
                Text("started " + SessionLogger.runStartedAt.ifBlank { "unknown" }, style = LabMono, color = LabColors.TextDim)
                Text(
                    SessionLogger.currentLogFile()?.absolutePath ?: "evidence file unavailable (logger not initialised)",
                    style = LabMono,
                    color = LabColors.TextDim,
                )
                Gap(6)

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("search event and details", style = LabMono) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Gap(6)

                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LabStatus.values().forEach { s ->
                        ToggleChip(
                            text = s.label,
                            selected = s in statuses,
                            color = statusColor(s),
                            onClick = {
                                statuses = if (s in statuses) statuses - s else statuses + s
                            },
                        )
                        HGap(6)
                    }
                }
                Gap(6)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LabCategory.values().forEach { c ->
                        ToggleChip(
                            text = c.name,
                            selected = c in categories,
                            color = LabColors.Accent,
                            onClick = {
                                categories = if (c in categories) categories - c else categories + c
                            },
                        )
                        HGap(6)
                    }
                }
                Gap(6)

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "showing " + filtered.size + " of " + source.size + " records",
                        style = MaterialTheme.typography.bodyMedium,
                        color = LabColors.TextDim,
                    )
                    if (categories.isNotEmpty() || statuses.isNotEmpty() || query.isNotBlank()) {
                        LabButton("CLEAR FILTERS", {
                            categories = emptySet()
                            statuses = emptySet()
                            query = ""
                        })
                    }
                }
                Row(Modifier.fillMaxWidth()) {
                    LabButton(
                        if (paused) "RESUME" else "PAUSE",
                        {
                            // Freeze the snapshot we are currently showing, then flip.
                            if (!paused) frozen = live
                            paused = !paused
                        },
                        Modifier.weight(1f),
                    )
                    HGap()
                    LabButton(
                        if (autoScroll) "AUTOSCROLL ON" else "AUTOSCROLL OFF",
                        { autoScroll = !autoScroll },
                        Modifier.weight(1f),
                    )
                    HGap()
                    LabButton("NEWEST", { scope.launch { listState.scrollToItem(0) } }, Modifier.weight(1f))
                }
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(LabColors.Line),
            )

            if (filtered.isEmpty()) {
                Text(
                    if (source.isEmpty()) {
                        "No records in this test run yet. Run a scan or a test from the dashboard."
                    } else {
                        "No record matches the current filter. " + source.size + " records are hidden."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = LabColors.TextDim,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                LazyColumn(
                    state = listState,
                    // weight, not fillMaxSize: the filter header above already ate part of the column.
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    // A stable key per record keeps the list from rebuilding every emission; the
                    // sequence number is unique within a run and is always present on a record.
                    items(
                        items = filtered,
                        key = { r -> r.details["seq"] ?: (r.timestamp + "@" + r.elapsedRealtimeMs) },
                    ) { record -> LogRow(record) }
                }
            }
        }
    }
}

/** One log line: when, what, how strongly claimed, and the raw details map. */
@Composable
private fun LogRow(record: LogRecord) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                record.timestamp.substringAfter('T').removeSuffix("Z"),
                style = LabMono,
                color = LabColors.TextDim,
            )
            HGap(6)
            Text("+" + record.elapsedRealtimeMs + "ms", style = LabMono, color = LabColors.TextDim)
            HGap(6)
            Text(
                record.category.name,
                style = LabMono,
                color = LabColors.Accent,
                modifier = Modifier.weight(1f),
            )
            StatusChip(record.status)
        }
        Text(record.event, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        val details = record.details.filterKeys { it != "seq" }
        if (details.isNotEmpty()) {
            Text(
                details.entries.joinToString("  ") { it.key + "=" + it.value },
                style = LabMono,
                color = LabColors.Text,
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(LabColors.Line),
        )
    }
}

/** Multi-select filter chip. Hand-rolled to match [StatusChip] and to avoid experimental M3 chips. */
@Composable
private fun ToggleChip(
    text: String,
    selected: Boolean,
    color: Color,
    onClick: () -> Unit,
) {
    val tint = if (selected) color else LabColors.TextDim
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) tint.copy(alpha = 0.22f) else Color.Transparent)
            .border(1.dp, tint.copy(alpha = if (selected) 0.9f else 0.4f), RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text,
            style = LabMono,
            color = tint,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}
