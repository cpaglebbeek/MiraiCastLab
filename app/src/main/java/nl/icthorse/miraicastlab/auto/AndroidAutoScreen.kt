package nl.icthorse.miraicastlab.auto

import android.app.UiModeManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.icthorse.miraicastlab.core.LabCategory
import nl.icthorse.miraicastlab.core.LabStatus
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
import nl.icthorse.miraicastlab.ui.MonoBlock
import nl.icthorse.miraicastlab.ui.StatusChip
import nl.icthorse.miraicastlab.ui.VerdictBanner
import nl.icthorse.miraicastlab.ui.statusColor

/**
 * The spec section 9 experiment matrix, as an instrument rather than a checklist.
 *
 * For every case the app captures what it can see the instant the tester starts it (car mode, USB,
 * Wi-Fi band, displays, presentation route, audio, Wi-Fi Direct interfaces) and the tester supplies
 * what only a human in the vehicle can see (did it connect, in which order, did the picture stay up,
 * did touch work, when did it drop). The two halves are stored separately and graded separately.
 *
 * Nothing on this screen talks to the head unit. Sessions are started and stopped by the tester
 * through the phone's own Android Auto and Smart View UI; this screen only witnesses and timestamps.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AndroidAutoScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var cases by remember { mutableStateOf<Map<String, MatrixCase>>(emptyMap()) }
    var loadedFrom by remember { mutableStateOf<String?>(null) }
    var carriedOver by remember { mutableStateOf(false) }
    var env by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var tick by remember { mutableStateOf(0L) }
    var expanded by remember { mutableStateOf<String?>(null) }
    var carModeEvents by remember { mutableStateOf(0) }

    // Restore any previously saved matrix. A run started today reopens its own file; otherwise the
    // newest earlier file is offered and flagged, so nothing silently masquerades as this run's data.
    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) { MatrixStore.load() }
        cases = loaded.cases
        loadedFrom = loaded.sourceFile
        carriedOver = loaded.fromEarlierRun
        if (loaded.cases.isNotEmpty()) {
            SessionLogger.log(
                LabCategory.ANDROID_AUTO,
                "aa_matrix_loaded",
                LabStatus.CONFIRMED,
                mapOf(
                    "file" to (loaded.sourceFile ?: "?"),
                    "cases" to loaded.cases.size.toString(),
                    "fromEarlierRun" to loaded.fromEarlierRun.toString(),
                ),
            )
        }
    }

    // Live environment strip. One second is fast enough to watch a session come up and slow enough
    // to leave the CPU alone during a screen-capture test running on the same device.
    LaunchedEffect(Unit) {
        while (true) {
            env = LabEnv.snapshot(ctx)
            tick = SystemClock.elapsedRealtime()
            delay(1000)
        }
    }

    // Car-mode transitions are the only Android Auto lifecycle event a normal app can timestamp.
    DisposableEffect(ctx) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                val action = intent?.action ?: return
                val entering = action == UiModeManager.ACTION_ENTER_CAR_MODE
                carModeEvents += 1
                SessionLogger.log(
                    LabCategory.ANDROID_AUTO,
                    if (entering) "car_mode_enter" else "car_mode_exit",
                    LabStatus.OBSERVED,
                    mapOf(
                        "action" to action,
                        "at" to LabEnv.nowIso(),
                        "uiModeType" to LabEnv.uiModeType(ctx),
                        "usb" to LabEnv.usbSummary(ctx),
                        "wifi" to LabEnv.wifiSummary(ctx),
                    ),
                )
                env = LabEnv.snapshot(ctx)
            }
        }
        val filter = IntentFilter().apply {
            addAction(UiModeManager.ACTION_ENTER_CAR_MODE)
            addAction(UiModeManager.ACTION_EXIT_CAR_MODE)
        }
        val registered = runCatching {
            ContextCompat.registerReceiver(ctx, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        }.isSuccess
        SessionLogger.log(
            LabCategory.ANDROID_AUTO,
            "car_mode_watch_started",
            if (registered) LabStatus.CONFIRMED else LabStatus.ERROR,
            mapOf("registered" to registered.toString()),
        )
        onDispose {
            runCatching { ctx.unregisterReceiver(receiver) }
            SessionLogger.log(
                LabCategory.ANDROID_AUTO,
                "car_mode_watch_stopped",
                LabStatus.CONFIRMED,
                mapOf("events" to carModeEvents.toString()),
            )
        }
    }

    /** Applies an edit, persists the whole matrix and logs the change. */
    fun mutate(rowId: String, event: String, detail: Map<String, String>, block: (MatrixCase) -> MatrixCase) {
        val current = cases[rowId] ?: MatrixCase(rowId)
        val updated = block(current)
        cases = cases + (rowId to updated)
        SessionLogger.log(
            LabCategory.ANDROID_AUTO,
            event,
            LabStatus.OBSERVED,
            detail + mapOf("row" to rowId, "at" to LabEnv.nowIso()),
        )
        val toSave = cases.values.toList()
        scope.launch { withContext(Dispatchers.IO) { MatrixStore.save(toSave) } }
    }

    LabScaffold(
        title = "Android Auto x Miracast matrix",
        onBack = onBack,
        subtitle = "Spec section 9 - six cases, observation only",
    ) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 40.dp, top = 4.dp),
        ) {
            item { ScopeCard() }
            item { LiveEnvironmentCard(env, carModeEvents) }

            if (carriedOver) {
                item {
                    LabCard(title = "Carried over from an earlier run") {
                        Text(
                            "The rows below were read from " + (loadedFrom ?: "an earlier file") +
                                ", which belongs to a previous test run. Their automatic captures " +
                                "describe that run's environment, not this one. Re-run any case you " +
                                "intend to cite.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = LabColors.Inferred,
                        )
                    }
                }
            }

            items(MatrixRow.values().toList(), key = { it.rowId }) { row ->
                MatrixRowCard(
                    row = row,
                    case = cases[row.rowId] ?: MatrixCase(row.rowId),
                    expanded = expanded == row.rowId,
                    nowElapsed = tick,
                    onToggle = { expanded = if (expanded == row.rowId) null else row.rowId },
                    onStart = {
                        val snapshot = LabEnv.snapshot(ctx)
                        mutate(row.rowId, "aa_matrix_case_started", snapshot) {
                            it.copy(
                                startedAt = LabEnv.nowIso(),
                                startedElapsedMs = SystemClock.elapsedRealtime(),
                                capture = snapshot,
                            )
                        }
                        expanded = row.rowId
                    },
                    onRecapture = {
                        val snapshot = LabEnv.snapshot(ctx)
                        mutate(row.rowId, "aa_matrix_case_recaptured", snapshot) {
                            it.copy(capture = snapshot)
                        }
                    },
                    onSuccess = { v ->
                        mutate(row.rowId, "aa_matrix_connection_success", mapOf("value" to v)) {
                            it.copy(connectionSuccess = v)
                        }
                    },
                    onOrder = { v ->
                        mutate(row.rowId, "aa_matrix_connection_order", mapOf("value" to v)) {
                            it.copy(connectionOrder = v)
                        }
                    },
                    onAnimation = { v ->
                        mutate(row.rowId, "aa_matrix_animation_visible", mapOf("value" to v)) {
                            it.copy(animationVisible = v)
                        }
                    },
                    onFailureReason = { v ->
                        mutate(row.rowId, "aa_matrix_failure_reason", mapOf("value" to v)) {
                            it.copy(failureReason = v)
                        }
                    },
                    onTouch = { v ->
                        mutate(row.rowId, "aa_matrix_touch_behaviour", mapOf("value" to v)) {
                            it.copy(touchBehaviour = v)
                        }
                    },
                    onNotes = { v ->
                        mutate(row.rowId, "aa_matrix_notes", mapOf("value" to v)) { it.copy(notes = v) }
                    },
                    onTimerStart = {
                        val started = SystemClock.elapsedRealtime()
                        mutate(row.rowId, "aa_matrix_timer_started", mapOf("elapsedRealtimeMs" to started.toString())) {
                            it.copy(timerStartedElapsedMs = started, timeUntilDisconnectMs = -1L, disconnectAt = "")
                        }
                    },
                    onDisconnected = {
                        val current = cases[row.rowId] ?: MatrixCase(row.rowId)
                        val base = if (current.timerStartedElapsedMs > 0L) {
                            current.timerStartedElapsedMs
                        } else {
                            current.startedElapsedMs
                        }
                        val delta = if (base > 0L) SystemClock.elapsedRealtime() - base else -1L
                        mutate(
                            row.rowId,
                            "aa_matrix_disconnect_observed",
                            mapOf(
                                "timeUntilDisconnectMs" to delta.toString(),
                                "measuredFrom" to if (current.timerStartedElapsedMs > 0L) "timer" else "case_start",
                            ),
                        ) {
                            it.copy(disconnectAt = LabEnv.nowIso(), timeUntilDisconnectMs = delta)
                        }
                    },
                    onClear = {
                        mutate(row.rowId, "aa_matrix_case_cleared", emptyMap()) { MatrixCase(row.rowId) }
                    },
                )
            }

            item { CoexistenceSummary(cases) }
            item { EvidenceCard(cases) }
        }
    }
}

/** Explains, on screen, what this instrument does and does not do. */
@Composable
private fun ScopeCard() {
    LabCard(title = "What this screen does") {
        Text(
            "It records. Start and stop Android Auto and Miracast yourself, on the phone and on the " +
                "head unit, exactly as a normal user would. When you tap a case, the app writes down " +
                "what it can see at that instant; you then tell it what the vehicle did.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Gap()
        Text(
            "The app sends nothing to the vehicle and never will. It does not spoof a projection " +
                "protocol and does not work around Android Auto's app-category restrictions.",
            style = MaterialTheme.typography.bodyMedium,
            color = LabColors.TextDim,
        )
    }
}

/** The always-on environment strip. This is what makes a change visible while it is happening. */
@Composable
private fun LiveEnvironmentCard(env: Map<String, String>, carModeEvents: Int) {
    val carMode = env["carMode"] == "true"
    LabCard(title = "Current environment") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(if (carMode) LabColors.Confirmed else LabColors.NotTested, 12)
            HGap()
            Text(
                if (carMode) "CAR MODE ACTIVE" else "no car mode",
                style = MaterialTheme.typography.titleLarge,
                color = if (carMode) LabColors.Confirmed else LabColors.TextDim,
            )
            HGap(16)
            Text(
                "enter/exit events: " + carModeEvents,
                style = LabMono,
                color = LabColors.TextDim,
            )
        }
        Gap()
        if (env.isEmpty()) {
            Text("sampling...", style = LabMono, color = LabColors.TextDim)
        } else {
            MonoBlock(env.entries.joinToString("\n") { it.key + " = " + it.value })
        }
        Gap()
        Text(
            "Sampled once per second while this screen is open.",
            style = MaterialTheme.typography.bodyMedium,
            color = LabColors.TextDim,
        )
    }
}

@Composable
private fun MatrixRowCard(
    row: MatrixRow,
    case: MatrixCase,
    expanded: Boolean,
    nowElapsed: Long,
    onToggle: () -> Unit,
    onStart: () -> Unit,
    onRecapture: () -> Unit,
    onSuccess: (String) -> Unit,
    onOrder: (String) -> Unit,
    onAnimation: (String) -> Unit,
    onFailureReason: (String) -> Unit,
    onTouch: (String) -> Unit,
    onNotes: (String) -> Unit,
    onTimerStart: () -> Unit,
    onDisconnected: () -> Unit,
    onClear: () -> Unit,
) {
    LabCard {
        Column(Modifier.fillMaxWidth().clickable { onToggle() }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.label,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                StatusChip(case.status)
            }
            Text(row.question, style = MaterialTheme.typography.bodyMedium, color = LabColors.TextDim)
            Text(
                case.statusNote,
                style = MaterialTheme.typography.bodyMedium,
                color = statusColor(case.status),
            )
            if (case.timeUntilDisconnectMs >= 0L) {
                Text(
                    "time until disconnect: " + formatMs(case.timeUntilDisconnectMs),
                    style = LabMono,
                    color = LabColors.Observed,
                )
            }
        }

        if (!expanded) {
            Gap()
            Text(
                if (case.started) "Tap to open" else "Tap to open, then start the case",
                style = MaterialTheme.typography.bodyMedium,
                color = LabColors.TextDim,
            )
            return@LabCard
        }

        Gap(12)
        if (!case.started) {
            BigActionButton(
                text = "START CASE " + row.rowId,
                onClick = onStart,
                subtitle = "captures the environment now",
            )
        } else {
            Text("started at " + case.startedAt, style = LabMono, color = LabColors.TextDim)
            Gap(4)
            MonoBlock(
                if (case.capture.isEmpty()) {
                    "no capture stored"
                } else {
                    case.capture.entries.joinToString("\n") { it.key + " = " + it.value }
                },
            )
            Gap(4)
            Row {
                LabButton("Re-capture", onRecapture, Modifier.weight(1f))
                HGap()
                LabButton("Clear case", onClear, Modifier.weight(1f))
            }

            Gap(12)
            Text("Connection succeeded?", style = MaterialTheme.typography.titleLarge)
            ChoiceRow(MatrixAnswers.SUCCESS, case.connectionSuccess, onSuccess)

            Gap(8)
            Text("Connection order", style = MaterialTheme.typography.titleLarge)
            ChoiceRow(MatrixAnswers.ORDER, case.connectionOrder, onOrder)

            Gap(8)
            Text("Test animation on the head unit", style = MaterialTheme.typography.titleLarge)
            ChoiceRow(MatrixAnswers.ANIMATION, case.animationVisible, onAnimation)

            Gap(8)
            LabTextField("Failure reason (what the vehicle said or did)", case.failureReason, onFailureReason)
            Gap(4)
            LabTextField("Touch behaviour (head unit touch, phone touch, none)", case.touchBehaviour, onTouch)
            Gap(4)
            LabTextField("Free notes", case.notes, onNotes)

            Gap(12)
            Text("Disconnect timer", style = MaterialTheme.typography.titleLarge)
            Text(
                "Start it the moment the session is up. Stop it the moment the picture drops.",
                style = MaterialTheme.typography.bodyMedium,
                color = LabColors.TextDim,
            )
            if (case.timerRunning) {
                val running = nowElapsed - case.timerStartedElapsedMs
                Text(
                    "running: " + formatMs(if (running > 0L) running else 0L),
                    style = MaterialTheme.typography.headlineMedium,
                    color = LabColors.Observed,
                )
            } else if (case.timeUntilDisconnectMs >= 0L) {
                Text(
                    "recorded: " + formatMs(case.timeUntilDisconnectMs) + " at " + case.disconnectAt,
                    style = LabMono,
                    color = LabColors.Observed,
                )
            } else {
                Text("not started", style = LabMono, color = LabColors.NotTested)
            }
            Gap(4)
            Row {
                LabButton("Start timer", onTimerStart, Modifier.weight(1f))
                HGap()
                LabButton("Disconnected now", onDisconnected, Modifier.weight(1f))
            }
        }
    }
}

/**
 * The point of the whole matrix. Rows D and F are the coexistence question; until a tester has
 * answered them this stays NOT_TESTED, with no hint at which way it is expected to fall.
 */
@Composable
private fun CoexistenceSummary(cases: Map<String, MatrixCase>) {
    val d = cases["D"] ?: MatrixCase("D")
    val f = cases["F"] ?: MatrixCase("F")
    val answered = listOf(d, f).filter { it.status == LabStatus.OBSERVED }

    LabCard(title = "Coexistence result") {
        if (answered.isEmpty()) {
            VerdictBanner(
                LabStatus.NOT_TESTED,
                "Coexistence not established",
                "Neither case D (USB Android Auto + Miracast) nor case F (wireless Android Auto + " +
                    "Miracast) has a tester verdict. The app makes no prediction.",
            )
        } else {
            answered.forEach { c ->
                VerdictBanner(
                    LabStatus.OBSERVED,
                    "Case " + c.rowId + ": connection " + c.connectionSuccess,
                    listOfNotNull(
                        c.connectionOrder.takeIf { it.isNotEmpty() }?.let { "order: " + it },
                        c.animationVisible.takeIf { it.isNotEmpty() }?.let { "animation: " + it },
                        c.failureReason.takeIf { it.isNotEmpty() }?.let { "reason: " + it },
                        c.timeUntilDisconnectMs.takeIf { it >= 0L }
                            ?.let { "time until disconnect: " + formatMs(it) },
                    ).joinToString(" | ").ifEmpty { "no further detail recorded" },
                )
            }
            if (answered.size < 2) {
                Gap()
                Text(
                    "Only one transport has been tested. Whether the result differs between wired " +
                        "and wireless Android Auto remains NOT_TESTED.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LabColors.NotTested,
                )
            }
        }
    }
}

@Composable
private fun EvidenceCard(cases: Map<String, MatrixCase>) {
    val file = remember { MatrixStore.fileForCurrentRun() }
    val done = cases.values.count { it.status == LabStatus.OBSERVED }
    LabCard(title = "Evidence") {
        Text(
            done.toString() + " of 6 cases have a tester verdict. The remaining " + (6 - done) +
                " are NOT_TESTED.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Gap()
        MonoBlock(file?.absolutePath ?: "evidence directory unavailable")
        Gap()
        Text(
            "Written on every edit, and every edit is also a record in the session log.",
            style = MaterialTheme.typography.bodyMedium,
            color = LabColors.TextDim,
        )
    }
}

// ---------------------------------------------------------------------- small shared widgets

/** A row of exclusive choices. Tapping the selected one clears it, so a mis-tap is recoverable. */
@Composable
internal fun ChoiceRow(options: List<String>, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        options.forEach { option ->
            val active = option == selected
            Box(
                Modifier
                    .weight(1f)
                    .padding(end = 6.dp, top = 6.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (active) LabColors.Accent.copy(alpha = 0.20f) else LabColors.Ink)
                    .border(
                        1.dp,
                        if (active) LabColors.Accent else LabColors.Line,
                        RoundedCornerShape(10.dp),
                    )
                    .clickable { onSelect(if (active) "" else option) }
                    .padding(vertical = 14.dp, horizontal = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    option,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (active) LabColors.Accent else LabColors.TextDim,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LabTextField(label: String, value: String, onChange: (String) -> Unit) {
    // Every keystroke is reported upward and therefore persisted. That is deliberate: losing a
    // tester's free-text observation because the app died mid-test is worse than the write cost,
    // and the write is off the main thread.
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, style = MaterialTheme.typography.bodyMedium) },
        modifier = Modifier.fillMaxWidth(),
        textStyle = LabMono,
        singleLine = false,
        maxLines = 4,
    )
}

/** Milliseconds as m:ss.mmm - the resolution a tester's thumb can actually justify. */
internal fun formatMs(ms: Long): String {
    if (ms < 0L) return "-"
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val millis = ms % 1000
    return minutes.toString() + ":" +
        (if (seconds < 10) "0" else "") + seconds + "." +
        millis.toString().padStart(3, '0')
}
