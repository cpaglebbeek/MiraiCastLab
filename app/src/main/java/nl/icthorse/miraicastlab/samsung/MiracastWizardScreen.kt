package nl.icthorse.miraicastlab.samsung

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.SystemClock
import android.view.Display
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.icthorse.miraicastlab.core.LabCategory
import nl.icthorse.miraicastlab.core.LabStatus
import nl.icthorse.miraicastlab.core.LogRecord
import nl.icthorse.miraicastlab.core.Observation
import nl.icthorse.miraicastlab.core.ProbeRegistry
import nl.icthorse.miraicastlab.core.SessionLogger
import nl.icthorse.miraicastlab.core.safeObserve
import nl.icthorse.miraicastlab.scene.PresentationHostActivity
import nl.icthorse.miraicastlab.ui.BigActionButton
import nl.icthorse.miraicastlab.ui.Gap
import nl.icthorse.miraicastlab.ui.LabButton
import nl.icthorse.miraicastlab.ui.LabCard
import nl.icthorse.miraicastlab.ui.LabColors
import nl.icthorse.miraicastlab.ui.LabMono
import nl.icthorse.miraicastlab.ui.LabScaffold
import nl.icthorse.miraicastlab.ui.MonoBlock
import nl.icthorse.miraicastlab.ui.ObservationRow
import nl.icthorse.miraicastlab.ui.StatusChip
import nl.icthorse.miraicastlab.ui.VerdictBanner
import nl.icthorse.miraicastlab.ui.statusColor

/**
 * The guided Miracast test (spec section 8).
 *
 * The wizard exists because of one hard constraint: **this app cannot start a Miracast session.**
 * Android gives no third-party API for it, and nothing here pretends otherwise. What the app *can*
 * do is take a precise before-picture, hand the tester off to the system's own picker, then watch
 * the platform change underneath them and timestamp every transition. The session is established by
 * a human; the evidence is gathered by the app.
 *
 * Grading discipline throughout: a state change the app measured is OBSERVED, a judgement the
 * tester made by looking at the car screen is OBSERVED *and attributed to the tester in the note*,
 * and anything neither of them produced stays NOT_TESTED.
 */

private enum class WizardStep(val number: Int, val shortLabel: String, val title: String) {
    BASELINE(1, "BASE", "Scan baseline"),
    PROMPT(2, "START", "Start Smart View"),
    POLL(3, "WATCH", "Watch for changes"),
    SCENE(4, "SCENE", "Launch test scene"),
    TESTS(5, "TESTS", "Visual tests"),
    INPUT(6, "INPUT", "Input from the car"),
    EXPORT(7, "DONE", "Findings"),
}

/** The probes that make up the baseline. Referenced by id so this module imports no siblings. */
private val BASELINE_PROBE_IDS = listOf("display", "mediaroute", "wifip2p", "connectivity", "audio")

/** Spec section 8 step 5: the five visual tests, in the order the tester runs them. */
private val VISUAL_TESTS = listOf(
    "still_image" to "Still image",
    "animation" to "Animation",
    "high_motion" to "High motion",
    "av_sync" to "Audio / video sync",
    "latency_pulse" to "Latency pulse",
)

/** The only verdicts a tester may record. Free text would be unanalysable in the report. */
private val VERDICT_OPTIONS = listOf("VISIBLE", "DISTORTED", "BLACK", "AUDIO ONLY", "NOT SHOWN")

private const val POLL_WINDOW_MS = 120_000L

@Composable
fun MiracastWizardScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var step by remember { mutableStateOf(WizardStep.BASELINE) }

    // Step 1
    val baseline = remember { mutableStateListOf<Observation>() }
    var baselineSnapshot by remember { mutableStateOf<WizardSnapshot?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var scanProgress by remember { mutableStateOf("") }

    // Step 2
    val intentFacts = remember(context) { castIntents(context) }
    var launchMessage by remember { mutableStateOf<String?>(null) }

    // Step 3
    val changes = remember { mutableStateListOf<SnapshotDiff>() }
    var polling by remember { mutableStateOf(false) }
    var pollEverRan by remember { mutableStateOf(false) }
    var secondsLeft by remember { mutableStateOf(0) }
    var latestSnapshot by remember { mutableStateOf<WizardSnapshot?>(null) }

    // Step 4
    var sceneMessage by remember { mutableStateOf<String?>(null) }
    var sceneLaunched by remember { mutableStateOf(false) }

    // Step 5
    val verdicts = remember { mutableStateMapOf<String, String>() }

    // Step 6
    var inputWindowStart by remember { mutableStateOf(0L) }
    var inputWindowOpen by remember { mutableStateOf(false) }

    // Step 7
    var summaryLogged by remember { mutableStateOf(false) }

    val records by SessionLogger.records.collectAsState()

    // ------------------------------------------------------------------ actions

    fun runBaseline() {
        if (scanning) return
        scanning = true
        baseline.clear()
        scope.launch {
            val found = mutableListOf<Observation>()
            BASELINE_PROBE_IDS.forEach { id ->
                scanProgress = "probing " + id + "..."
                val probe = ProbeRegistry.byId(id)
                if (probe == null) {
                    found += Observation.notTested(
                        "wizard.baseline." + id,
                        LabCategory.MIRACAST,
                        "Probe '" + id + "' is not registered in this build, so this part of the " +
                            "baseline is missing. That is a build gap, not a device limitation.",
                    )
                } else {
                    found += withContext(Dispatchers.Default) { probe.safeObserve(context) }
                }
            }
            val snap = withContext(Dispatchers.IO) { takeWizardSnapshot(context) }
            baselineSnapshot = snap
            latestSnapshot = snap
            baseline.addAll(found)
            SessionLogger.logAll(found)
            SessionLogger.log(
                LabCategory.MIRACAST, "wizard_baseline_captured", LabStatus.OBSERVED,
                mapOf(
                    "probes" to BASELINE_PROBE_IDS.joinToString(","),
                    "observations" to found.size.toString(),
                    "snapshotKeys" to snap.values.size.toString(),
                ) + snap.values,
            )
            scanProgress = ""
            scanning = false
        }
    }

    fun startPolling() {
        if (polling) return
        if (baselineSnapshot == null) {
            baselineSnapshot = takeWizardSnapshot(context)
            latestSnapshot = baselineSnapshot
        }
        changes.clear()
        pollEverRan = true
        polling = true
        SessionLogger.log(
            LabCategory.MIRACAST, "wizard_poll_started", LabStatus.OBSERVED,
            mapOf("windowMs" to POLL_WINDOW_MS.toString()),
        )
    }

    fun openInputWindow() {
        inputWindowStart = SystemClock.elapsedRealtime()
        inputWindowOpen = true
        SessionLogger.log(
            LabCategory.INPUT, "wizard_input_window_opened", LabStatus.OBSERVED,
            mapOf("instruction" to "tester touches numbered targets on the head-unit display"),
        )
    }

    fun recordVerdict(testId: String, testLabel: String, verdict: String) {
        verdicts[testId] = verdict
        SessionLogger.log(
            LabCategory.MIRACAST, "wizard_visual_test", LabStatus.OBSERVED,
            mapOf(
                "test" to testId,
                "label" to testLabel,
                "verdict" to verdict,
                "source" to "tester",
                "note" to "Verdict entered by the tester after looking at the head-unit screen. " +
                    "The app measured nothing here; it recorded a human observation.",
            ),
        )
    }

    // ------------------------------------------------------------------ derived

    val inputRecords: List<LogRecord> = if (!inputWindowOpen) {
        emptyList()
    } else {
        records.filter {
            it.category == LabCategory.INPUT &&
                it.elapsedRealtimeMs >= inputWindowStart &&
                it.event != "wizard_input_window_opened"
        }
    }

    val netChange: List<SnapshotDiff> = run {
        val b = baselineSnapshot
        val l = latestSnapshot
        if (b == null || l == null) emptyList() else diffSnapshots(b, l)
    }

    val externalAppeared: Boolean = run {
        val b = baselineSnapshot?.values?.get("display.count")?.toIntOrNull()
        val l = latestSnapshot?.values?.get("display.count")?.toIntOrNull()
        b != null && l != null && l > b
    }

    fun buildSummary(): List<Observation> {
        val cat = LabCategory.MIRACAST
        val out = mutableListOf<Observation>()

        out += if (baseline.isEmpty()) {
            Observation.notTested("wizard.baseline", cat, "Step 1 was never run.")
        } else {
            Observation.observed(
                "wizard.baseline", baseline.size.toString() + " observations", cat,
                "Captured from probes: " + BASELINE_PROBE_IDS.joinToString(", "),
            )
        }

        out += if (!pollEverRan) {
            Observation.notTested(
                "wizard.state_changes", cat,
                "Step 3 was never run, so no before/after comparison exists.",
            )
        } else {
            Observation.observed(
                "wizard.state_changes", changes.size.toString() + " transitions", cat,
                if (changes.isEmpty()) {
                    "The platform state did not move during the watch window. If Smart View was " +
                        "started in that window, the session left no trace an app can read."
                } else {
                    changes.takeLast(12).joinToString("; ") {
                        it.key + ": " + it.before + " -> " + it.after
                    }
                },
            )
        }

        out += when {
            !pollEverRan -> Observation.notTested(
                "wizard.external_display_appeared", cat, "Step 3 was never run.",
            )

            externalAppeared -> Observation.observed(
                "wizard.external_display_appeared", "true", cat,
                "A display was added during the watch window: " +
                    (latestSnapshot?.values?.get("display.external") ?: "unknown") +
                    ". The transport is not identified by this fact.",
            )

            else -> Observation.observed(
                "wizard.external_display_appeared", "false", cat,
                "No display was added during the watch window. If the head unit was showing the " +
                    "phone, it was mirroring without creating a logical Android display - or the " +
                    "session was never established.",
            )
        }

        out += if (sceneLaunched) {
            Observation.observed(
                "wizard.scene_launched", "true", cat,
                sceneMessage ?: "Test scene launch requested.",
            )
        } else {
            Observation.notTested(
                "wizard.scene_launched", cat, "Step 4 was never run.",
            )
        }

        VISUAL_TESTS.forEach { (testId, label) ->
            val v = verdicts[testId]
            out += if (v == null) {
                Observation.notTested(
                    "wizard.visual_test." + testId, cat,
                    label + " was not judged by the tester in this run.",
                )
            } else {
                Observation.observed(
                    "wizard.visual_test." + testId, v, cat,
                    label + " - verdict entered by the tester from the head-unit screen. " +
                        "Not an app measurement.",
                )
            }
        }

        out += when {
            !inputWindowOpen -> Observation.notTested(
                "wizard.input_from_sink", cat, "Step 6 was never run.",
            )

            inputRecords.isNotEmpty() -> Observation.observed(
                "wizard.input_from_sink", inputRecords.size.toString() + " input events in window", cat,
                "Input events were logged while the tester was touching the head unit. They are " +
                    "NOT automatically attributable to the head unit: unless the input module " +
                    "recorded a source display or device id that rules out the phone's own " +
                    "touchscreen and any attached peripheral, this is not UIBC evidence.",
            )

            else -> Observation.notTested(
                "wizard.input_from_sink", cat,
                "No input events arrived during the window. This does not establish that the sink " +
                    "has no input back channel: the test scene may not have been running on the " +
                    "external display, or the tester may not have touched it. Re-run with the scene " +
                    "confirmed visible on the head unit before drawing any conclusion.",
            )
        }

        out += Observation.notTested(
            "wizard.transport_identified", cat,
            "No step of this wizard can name the transport. A presentation display, a MediaRouter " +
                "route and a p2p interface look identical for Miracast, DeX, an HDMI dongle and a " +
                "virtual display. Identifying it needs a sniffer or a bench sink.",
        )

        return out
    }

    fun persistSummary() {
        val s = buildSummary()
        SessionLogger.logAll(s)
        SessionLogger.log(
            LabCategory.REPORT, "wizard_summary_persisted", LabStatus.CONFIRMED,
            mapOf("lines" to s.size.toString(), "testRunId" to SessionLogger.testRunId),
        )
        summaryLogged = true
    }

    // ------------------------------------------------------------------ polling loop

    LaunchedEffect(polling) {
        if (!polling) return@LaunchedEffect
        val deadline = SystemClock.elapsedRealtime() + POLL_WINDOW_MS
        var previous = latestSnapshot ?: withContext(Dispatchers.IO) { takeWizardSnapshot(context) }
        while (polling && SystemClock.elapsedRealtime() < deadline) {
            delay(1000)
            val now = withContext(Dispatchers.IO) { takeWizardSnapshot(context) }
            val diffs = diffSnapshots(previous, now)
            if (diffs.isNotEmpty()) {
                changes.addAll(diffs)
                diffs.forEach { d ->
                    SessionLogger.log(
                        LabCategory.MIRACAST, "wizard_state_change", LabStatus.OBSERVED,
                        mapOf("key" to d.key, "before" to d.before, "after" to d.after),
                    )
                }
            }
            previous = now
            latestSnapshot = now
            secondsLeft = ((deadline - SystemClock.elapsedRealtime()) / 1000L).toInt().coerceAtLeast(0)
        }
        if (polling) {
            SessionLogger.log(
                LabCategory.MIRACAST, "wizard_poll_finished", LabStatus.OBSERVED,
                mapOf("changes" to changes.size.toString()),
            )
        }
        polling = false
        secondsLeft = 0
    }

    LaunchedEffect(step) {
        if (step == WizardStep.EXPORT && !summaryLogged) persistSummary()
    }

    // ------------------------------------------------------------------ UI

    LabScaffold(
        title = "Miracast test wizard",
        subtitle = "Step " + step.number + " of 7 - " + step.title,
        onBack = onBack,
    ) {
        LazyColumn(Modifier.fillMaxSize()) {
            item { StepIndicator(step) { step = it } }

            when (step) {
                WizardStep.BASELINE -> baselineStep(
                    baseline = baseline,
                    snapshot = baselineSnapshot,
                    scanning = scanning,
                    progress = scanProgress,
                    onScan = { runBaseline() },
                )

                WizardStep.PROMPT -> promptStep(
                    intents = intentFacts,
                    message = launchMessage,
                    onLaunch = { fact -> launchMessage = launchIntentFact(context, fact) },
                )

                WizardStep.POLL -> pollStep(
                    polling = polling,
                    secondsLeft = secondsLeft,
                    changes = changes,
                    netChange = netChange,
                    everRan = pollEverRan,
                    onStart = { startPolling() },
                    onStop = { polling = false },
                )

                WizardStep.SCENE -> sceneStep(
                    message = sceneMessage,
                    latest = latestSnapshot,
                    onLaunch = {
                        sceneMessage = launchTestScene(context)
                        sceneLaunched = true
                    },
                )

                WizardStep.TESTS -> testsStep(
                    verdicts = verdicts,
                    onVerdict = { id, label, v -> recordVerdict(id, label, v) },
                    onRelaunchScene = { sceneMessage = launchTestScene(context); sceneLaunched = true },
                    message = sceneMessage,
                )

                WizardStep.INPUT -> inputStep(
                    windowOpen = inputWindowOpen,
                    inputRecords = inputRecords,
                    onOpen = { openInputWindow() },
                    onClose = {
                        inputWindowOpen = false
                        SessionLogger.log(
                            LabCategory.INPUT, "wizard_input_window_closed", LabStatus.OBSERVED,
                            mapOf("events" to inputRecords.size.toString()),
                        )
                    },
                    onRelaunchScene = { sceneMessage = launchTestScene(context); sceneLaunched = true },
                )

                WizardStep.EXPORT -> exportStep(
                    summary = buildSummary(),
                    persisted = summaryLogged,
                    onPersist = { persistSummary() },
                    onDone = onBack,
                )
            }

            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LabButton(
                        text = "PREVIOUS",
                        onClick = { step = previousStep(step) },
                        modifier = Modifier.weight(1f),
                        enabled = step != WizardStep.BASELINE,
                    )
                    LabButton(
                        text = "NEXT STEP",
                        onClick = { step = nextStep(step) },
                        modifier = Modifier.weight(1f),
                        enabled = step != WizardStep.EXPORT,
                    )
                }
            }
            item { Gap(24) }
        }
    }
}

// ---------------------------------------------------------------- step bodies

private fun LazyListScope.baselineStep(
    baseline: List<Observation>,
    snapshot: WizardSnapshot?,
    scanning: Boolean,
    progress: String,
    onScan: () -> Unit,
) {
    item {
        LabCard("Step 1 - baseline") {
            Text(
                "Capture what the phone looks like BEFORE anything is connected. Every later " +
                    "finding is a difference against this picture, so run it with the head unit " +
                    "idle and Smart View not yet started.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Gap()
            BigActionButton(
                text = if (scanning) "SCANNING..." else "SCAN BASELINE",
                onClick = onScan,
                enabled = !scanning,
                subtitle = if (scanning) progress else "display, media route, Wi-Fi P2P, connectivity, audio",
            )
        }
    }
    if (snapshot != null) {
        item {
            LabCard("Baseline state") {
                MonoBlock(snapshot.values.entries.joinToString("\n") { it.key + " = " + it.value })
            }
        }
    }
    if (baseline.isNotEmpty()) {
        item { LabCard("Baseline observations (" + baseline.size + ")") { Gap(0) } }
        items(baseline) { o ->
            Box(Modifier.padding(horizontal = 26.dp)) { ObservationRow(o) }
        }
    }
}

private fun LazyListScope.promptStep(
    intents: List<IntentFact>,
    message: String?,
    onLaunch: (IntentFact) -> Unit,
) {
    item {
        LabCard {
            Text(
                "START SMART VIEW AND CONNECT TO THE TOYOTA MIRAI",
                style = MaterialTheme.typography.displaySmall,
                color = LabColors.Accent,
            )
            Gap()
            Text(
                "MiraiCast Lab cannot do this for you. Android exposes no API that lets a " +
                    "third-party app open, join or configure a Miracast session. The buttons below " +
                    "only open a system panel; the connection is yours to make.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Gap()
            Text(
                "When the car screen shows the phone, come back and go to step 3.",
                style = MaterialTheme.typography.bodyLarge,
                color = LabColors.TextDim,
            )
        }
    }
    if (message != null) {
        item { LabCard { Text(message, style = MaterialTheme.typography.bodyLarge) } }
    }
    item {
        LabCard("Settings panels on this device") {
            val usable = intents.filter { it.key != ACTION_CONTROL_KEY }
            if (usable.none { it.resolvable }) {
                Text(
                    "No cast or wireless-display panel resolved on this device. That may mean the " +
                        "panel does not exist, or only that Android package-visibility filtering " +
                        "hid it from the query - pressing a button still settles it, because " +
                        "startActivity is not filtered.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LabColors.Inferred,
                )
                Gap()
            }
            usable.forEach { fact ->
                Column(Modifier.padding(vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            fact.describe,
                            style = LabMono,
                            color = LabColors.TextDim,
                            modifier = Modifier.weight(1f),
                        )
                        StatusChip(if (fact.resolvable) LabStatus.CONFIRMED else LabStatus.NOT_TESTED)
                    }
                    LabButton(
                        text = "OPEN: " + fact.label.uppercase(),
                        onClick = { onLaunch(fact) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = fact.intent != null,
                    )
                }
            }
        }
    }
}

private fun LazyListScope.pollStep(
    polling: Boolean,
    secondsLeft: Int,
    changes: List<SnapshotDiff>,
    netChange: List<SnapshotDiff>,
    everRan: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    item {
        LabCard("Step 3 - watch the platform") {
            Text(
                "Polls displays, media routes, Wi-Fi, Wi-Fi Direct, network interfaces, audio route " +
                    "and UI mode once a second for two minutes, and timestamps every value that " +
                    "moves. Start this, then start Smart View, so the transition lands inside the " +
                    "window.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Gap()
            if (polling) {
                BigActionButton(
                    text = "STOP WATCHING",
                    onClick = onStop,
                    subtitle = secondsLeft.toString() + " s left, " + changes.size + " changes so far",
                )
            } else {
                BigActionButton(
                    text = "START WATCHING (120 s)",
                    onClick = onStart,
                    subtitle = if (everRan) changes.size.toString() + " changes recorded" else "not started",
                )
            }
        }
    }
    if (everRan && !polling) {
        item {
            VerdictBanner(
                status = if (changes.isEmpty()) LabStatus.NOT_TESTED else LabStatus.OBSERVED,
                headline = if (changes.isEmpty()) {
                    "Nothing moved"
                } else {
                    changes.size.toString() + " state changes observed"
                },
                detail = if (changes.isEmpty()) {
                    "No platform value changed during the window. If Smart View was connected in " +
                        "that time, it produced no signal this app can read - which is a result, " +
                        "but not evidence that the session was absent."
                } else {
                    "Each line below is a value the app measured changing. None of them names the " +
                        "transport."
                },
            )
        }
    }
    if (netChange.isNotEmpty()) {
        item {
            LabCard("Net change vs baseline") {
                MonoBlock(
                    netChange.joinToString("\n") { it.key + ": " + it.before + "  ->  " + it.after },
                )
            }
        }
    }
    if (changes.isNotEmpty()) {
        item { LabCard("Change log (newest last)") { Gap(0) } }
        items(changes) { d ->
            Column(Modifier.padding(horizontal = 26.dp, vertical = 3.dp)) {
                Text(d.wallClock + "  " + d.key, style = LabMono, color = LabColors.Observed)
                Text(d.before + "  ->  " + d.after, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private fun LazyListScope.sceneStep(
    message: String?,
    latest: WizardSnapshot?,
    onLaunch: () -> Unit,
) {
    item {
        LabCard("Step 4 - put content on the car screen") {
            Text(
                "Launches the diagnostic scene with ActivityOptions.setLaunchDisplayId targeting a " +
                    "presentation-category display. If it appears on the head unit, that is direct " +
                    "evidence the sink hosts ordinary Android activities - the DeX question from " +
                    "spec 2.3. If no external display exists, the scene opens on the phone and " +
                    "proves nothing about the car.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Gap()
            Text(
                "External displays right now: " + (latest?.values?.get("display.external") ?: "unknown"),
                style = LabMono,
                color = LabColors.TextDim,
            )
            Gap()
            BigActionButton(
                text = "LAUNCH TEST SCENE",
                onClick = onLaunch,
                subtitle = "on the external display if one exists",
            )
        }
    }
    if (message != null) {
        item { LabCard { Text(message, style = MaterialTheme.typography.bodyLarge) } }
    }
}

private fun LazyListScope.testsStep(
    verdicts: Map<String, String>,
    onVerdict: (String, String, String) -> Unit,
    onRelaunchScene: () -> Unit,
    message: String?,
) {
    item {
        LabCard("Step 5 - what do you see on the car screen?") {
            Text(
                "The scene renders the patterns; you are the instrument here. Record what the HEAD " +
                    "UNIT shows, not what the phone shows. Every verdict is stored as a tester " +
                    "observation, attributed to you, never as an app measurement.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Gap()
            LabButton(
                text = "RE-LAUNCH SCENE ON CAR DISPLAY",
                onClick = onRelaunchScene,
                modifier = Modifier.fillMaxWidth(),
            )
            if (message != null) {
                Gap()
                Text(message, style = MaterialTheme.typography.bodyMedium, color = LabColors.TextDim)
            }
        }
    }
    VISUAL_TESTS.forEach { (testId, label) ->
        item {
            LabCard(label) {
                val current = verdicts[testId]
                Text(
                    if (current == null) "no verdict recorded" else "recorded: " + current,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (current == null) LabColors.TextDim else statusColor(LabStatus.OBSERVED),
                )
                Gap()
                VERDICT_OPTIONS.chunked(2).forEach { rowItems ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        rowItems.forEach { v ->
                            LabButton(
                                text = v,
                                onClick = { onVerdict(testId, label, v) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (rowItems.size == 1) Spacer(Modifier.weight(1f))
                    }
                    Gap(6)
                }
            }
        }
    }
}

private fun LazyListScope.inputStep(
    windowOpen: Boolean,
    inputRecords: List<LogRecord>,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    onRelaunchScene: () -> Unit,
) {
    item {
        LabCard("Step 6 - touch the car screen") {
            Text(
                "TOUCH THE NUMBERED TARGETS ON THE MIRAI DISPLAY",
                style = MaterialTheme.typography.titleLarge,
                color = LabColors.Accent,
            )
            Gap()
            Text(
                "This is the UIBC question from spec 2.1. While the window is open, every " +
                    "INPUT-category record is collected. Two warnings that decide whether the " +
                    "result means anything:\n\n" +
                    "1. The test scene must actually be running on the head-unit display, or there " +
                    "is nothing there to touch. Relaunch it first if unsure.\n\n" +
                    "2. Input records are not self-attributing. A touch on the phone, a Bluetooth " +
                    "mouse or a USB keyboard produce records that look the same. Only touches you " +
                    "made on the car screen, with nothing else attached, count as back-channel " +
                    "evidence.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Gap()
            LabButton(
                text = "RE-LAUNCH SCENE ON CAR DISPLAY",
                onClick = onRelaunchScene,
                modifier = Modifier.fillMaxWidth(),
            )
            Gap()
            if (windowOpen) {
                BigActionButton(
                    text = "CLOSE INPUT WINDOW",
                    onClick = onClose,
                    subtitle = inputRecords.size.toString() + " input events collected",
                )
            } else {
                BigActionButton(
                    text = "OPEN INPUT WINDOW",
                    onClick = onOpen,
                    subtitle = "starts collecting INPUT records from now",
                )
            }
        }
    }
    if (windowOpen) {
        item {
            VerdictBanner(
                status = if (inputRecords.isEmpty()) LabStatus.NOT_TESTED else LabStatus.OBSERVED,
                headline = if (inputRecords.isEmpty()) {
                    "No input yet"
                } else {
                    inputRecords.size.toString() + " input events in window"
                },
                detail = if (inputRecords.isEmpty()) {
                    "Silence here does NOT mean the sink has no input back channel. It means no " +
                        "input reached this app during the window, for any of several reasons."
                } else {
                    "Check the source of each event before treating any of it as UIBC evidence."
                },
            )
        }
    }
    if (inputRecords.isNotEmpty()) {
        items(inputRecords) { r ->
            Column(Modifier.padding(horizontal = 26.dp, vertical = 3.dp)) {
                Text(r.event, style = LabMono, color = LabColors.Observed)
                Text(
                    r.details.entries.joinToString(" ") { it.key + "=" + it.value },
                    style = MaterialTheme.typography.bodyMedium,
                    color = LabColors.TextDim,
                )
            }
        }
    }
}

private fun LazyListScope.exportStep(
    summary: List<Observation>,
    persisted: Boolean,
    onPersist: () -> Unit,
    onDone: () -> Unit,
) {
    item {
        LabCard("Step 7 - what this run established") {
            Text(
                "Each line carries its own evidence grade. NOT_TESTED lines are not failures: they " +
                    "name exactly which instrument or which piece of hardware is still missing.",
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
    items(summary) { o ->
        Box(Modifier.padding(horizontal = 26.dp)) { ObservationRow(o) }
    }
    item {
        LabCard {
            Text(
                if (persisted) {
                    "Summary written to the session log and the JSONL evidence file."
                } else {
                    "Summary not yet written."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = LabColors.TextDim,
            )
            Gap()
            BigActionButton(text = "SAVE SUMMARY TO LOG", onClick = onPersist)
            Gap()
            LabButton(
                text = "BACK TO DASHBOARD",
                onClick = onDone,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// ---------------------------------------------------------------- small pieces

@Composable
private fun StepIndicator(current: WizardStep, onSelect: (WizardStep) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WizardStep.entries.forEach { s ->
            val done = s.number < current.number
            val active = s == current
            val tint = when {
                active -> LabColors.Accent
                done -> LabColors.AccentDim
                else -> LabColors.Line
            }
            Column(
                Modifier
                    .weight(1f)
                    .clickable { onSelect(s) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .size(26.dp)
                        .clip(RoundedCornerShape(50))
                        .background(if (active) tint else LabColors.Surface)
                        .border(2.dp, tint, RoundedCornerShape(50)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        s.number.toString(),
                        style = LabMono,
                        color = if (active) LabColors.Ink else tint,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(
                    s.shortLabel,
                    style = LabMono,
                    color = if (active) LabColors.Text else LabColors.TextDim,
                )
            }
        }
    }
}

private fun previousStep(s: WizardStep): WizardStep =
    WizardStep.entries.firstOrNull { it.number == s.number - 1 } ?: s

private fun nextStep(s: WizardStep): WizardStep =
    WizardStep.entries.firstOrNull { it.number == s.number + 1 } ?: s

/** Picks the display the scene should open on: presentation category first, any secondary second. */
private fun externalTargetDisplayId(context: Context): Int? {
    val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return null
    val presentation = runCatching {
        dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).firstOrNull()
    }.getOrNull()
    if (presentation != null) return presentation.displayId
    return runCatching {
        dm.displays.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }?.displayId
    }.getOrNull()
}

/**
 * Starts the diagnostic scene, on the external display when one exists.
 *
 * setLaunchDisplayId is the entire mechanism by which an unprivileged app puts content on a
 * Miracast or DeX screen. If it works, that is a real capability finding; if the scene lands on the
 * phone instead, the return string says so rather than letting the tester assume success.
 */
private fun launchTestScene(context: Context): String {
    val displayId = externalTargetDisplayId(context)
    val intent = Intent(context, PresentationHostActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        if (displayId != null) {
            val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
            context.startActivity(intent, options.toBundle())
            SessionLogger.log(
                LabCategory.MIRACAST, "wizard_scene_launched", LabStatus.OBSERVED,
                mapOf("displayId" to displayId.toString(), "target" to "external"),
            )
            "Scene launched on display #" + displayId +
                ". Confirm on the head unit before recording any verdict."
        } else {
            context.startActivity(intent)
            SessionLogger.log(
                LabCategory.MIRACAST, "wizard_scene_launched", LabStatus.OBSERVED,
                mapOf(
                    "displayId" to "default",
                    "target" to "phone",
                    "note" to "No external display existed at launch time.",
                ),
            )
            "No external display exists, so the scene opened on the phone. Nothing about the car " +
                "can be concluded from it."
        }
    } catch (t: Throwable) {
        SessionLogger.log(
            LabCategory.MIRACAST, "wizard_scene_launch_failed", LabStatus.ERROR,
            mapOf(
                "error" to t::class.java.simpleName,
                "message" to (t.message ?: "no message"),
                "displayId" to (displayId?.toString() ?: "default"),
            ),
        )
        "Scene launch failed: " + t::class.java.simpleName + " - " + (t.message ?: "no message")
    }
}
