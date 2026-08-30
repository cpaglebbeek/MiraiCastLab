package nl.icthorse.miraicastlab.input

import android.content.Context
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import nl.icthorse.miraicastlab.core.LabCategory
import nl.icthorse.miraicastlab.core.LabStatus
import nl.icthorse.miraicastlab.core.SessionLogger
import nl.icthorse.miraicastlab.ui.BigActionButton
import nl.icthorse.miraicastlab.ui.Gap
import nl.icthorse.miraicastlab.ui.LabButton
import nl.icthorse.miraicastlab.ui.LabCard
import nl.icthorse.miraicastlab.ui.LabColors
import nl.icthorse.miraicastlab.ui.LabMono
import nl.icthorse.miraicastlab.ui.LabScaffold
import nl.icthorse.miraicastlab.ui.MonoBlock
import nl.icthorse.miraicastlab.ui.StatRow
import nl.icthorse.miraicastlab.ui.StatusChip
import nl.icthorse.miraicastlab.ui.VerdictBanner
import nl.icthorse.miraicastlab.ui.statusColor
import java.util.Locale

/** The two experiments live on one screen; the grid needs the whole surface, so it swaps in. */
private enum class InputMode { MONITOR, GRID }

private const val MAX_EVENTS_KEPT = 250

/**
 * Input / touch-back test (spec sections 2.6 and 11).
 *
 * The question this screen exists for: when the phone is mirrored to the Toyota head unit, does
 * touching the *car* screen produce input on the *phone*? Three separate experiments, because they
 * fail for different reasons:
 *
 *  A  Event monitor  - a raw capture surface that reports every event on every dispatch hook.
 *  B  Touch-back grid - the numbered target run of spec section 11, with a coordinate-transform fit.
 *  C  HID baseline    - what pointer hardware is visible right now, so the tester can prove the
 *                       monitor works with a USB-C/Bluetooth mouse before blaming the car.
 *
 * Nothing here ever concludes "the sink has no back channel". Silence is recorded as NOT_TESTED.
 */
@Composable
fun InputTestScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    var mode by remember { mutableStateOf(InputMode.MONITOR) }
    var devices by remember { mutableStateOf<List<InputDeviceFacts>>(emptyList()) }
    var baseline by remember { mutableStateOf<Set<String>>(emptySet()) }
    var refreshTick by remember { mutableStateOf(0) }
    var logMoves by remember { mutableStateOf(false) }
    var pointerCapture by remember { mutableStateOf(false) }
    var hotplugEvents by remember { mutableStateOf(0) }
    var activeCapture by remember { mutableStateOf<InputCaptureView?>(null) }

    val events = remember { mutableStateListOf<CapturedEvent>() }
    val run = remember { TouchBackRun() }

    // Inventory refresh. Off the main thread: getDeviceIds + per-device reads is a burst of binder
    // calls and this screen must stay responsive while events are streaming in.
    LaunchedEffect(refreshTick) {
        val snap = withContext(Dispatchers.Default) { InputFacts.snapshot() }
        devices = snap
        if (baseline.isEmpty()) {
            baseline = InputFacts.identities(snap)
            SessionLogger.log(
                LabCategory.INPUT,
                "input_baseline_snapshot",
                LabStatus.CONFIRMED,
                mapOf(
                    "device_count" to snap.size.toString(),
                    "devices" to snap.joinToString(" ; ") { it.oneLine() },
                ),
            )
        }
        activeCapture?.clearDeviceCache()
    }

    // A back channel that surfaces as real hardware announces itself here. Watching this listener is
    // the cheapest possible detector for "a new touchscreen/mouse appeared when Smart View started".
    DisposableEffect(Unit) {
        val im = context.getSystemService(Context.INPUT_SERVICE) as? InputManager
        val listener = object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) = note("input_device_added", deviceId)
            override fun onInputDeviceRemoved(deviceId: Int) = note("input_device_removed", deviceId)
            override fun onInputDeviceChanged(deviceId: Int) = note("input_device_changed", deviceId)

            private fun note(event: String, deviceId: Int) {
                val f = InputFacts.factsFor(deviceId)
                SessionLogger.log(
                    LabCategory.INPUT,
                    event,
                    LabStatus.OBSERVED,
                    mapOf(
                        "device_id" to deviceId.toString(),
                        "device" to (f?.oneLine() ?: "(already gone)"),
                        "in_baseline" to ((f?.identity ?: "") in baseline).toString(),
                    ),
                )
                hotplugEvents += 1
                refreshTick += 1
            }
        }
        if (im != null) {
            try {
                im.registerInputDeviceListener(listener, Handler(Looper.getMainLooper()))
            } catch (t: Throwable) {
                SessionLogger.log(
                    LabCategory.INPUT,
                    "input_device_listener_failed",
                    LabStatus.ERROR,
                    mapOf("error" to (t.message ?: t::class.java.simpleName)),
                )
            }
        } else {
            SessionLogger.log(
                LabCategory.INPUT,
                "input_manager_absent",
                LabStatus.UNSUPPORTED,
                mapOf("note" to "getSystemService(INPUT_SERVICE) returned null"),
            )
        }
        onDispose {
            try {
                im?.unregisterInputDeviceListener(listener)
            } catch (t: Throwable) {
                // Unregistering a listener that was never registered is harmless.
            }
        }
    }

    // Single sink for every captured event, wherever the capture surface currently lives.
    val handler = rememberUpdatedState<(CapturedEvent) -> Unit>({ e ->
        // MOVE floods the JSONL and buries the interesting transitions; it is still counted and
        // still shown live, only its persistence is opt-in.
        if (!e.isMove || logMoves) {
            SessionLogger.log(LabCategory.INPUT, "input_event", LabStatus.OBSERVED, e.details())
        }
        events.add(0, e)
        while (events.size > MAX_EVENTS_KEPT) events.removeAt(events.size - 1)
        run.onEvent(e)
    })

    LaunchedEffect(run.running, run.activeIndex) {
        while (run.running) {
            run.tick()
            delay(200)
        }
    }

    DisposableEffect(Unit) {
        SessionLogger.log(LabCategory.INPUT, "input_screen_opened", LabStatus.OBSERVED)
        onDispose {
            activeCapture?.onEvent = null
            SessionLogger.log(
                LabCategory.INPUT,
                "input_screen_closed",
                LabStatus.OBSERVED,
                mapOf("events_captured" to events.size.toString()),
            )
        }
    }

    val analysis = run.analyse()
    val newDeviceEvents = events.count { it.deviceIdentity !in baseline && it.deviceId >= 0 }
    val distinctDevices = events.filter { it.deviceId >= 0 }.map { it.deviceId }.toSet()
    val distinctSources = events.flatMap { it.sourceNames.split("|") }.filter { it != "-" }.toSet()

    LabScaffold(
        title = if (mode == InputMode.GRID) "Touch-back grid" else "Input / touch-back",
        subtitle = if (mode == InputMode.GRID) {
            "Touch the highlighted number on the CAR screen"
        } else {
            devices.size.toString() + " input devices - " + events.size + " events captured"
        },
        onBack = onBack,
    ) {
        when (mode) {
            InputMode.GRID -> GridExperiment(
                run = run,
                onEvent = { e -> handler.value.invoke(e) },
                onCaptureView = { activeCapture = it },
                onExit = {
                    // Leaving mid-run would let the timeout tick fabricate "no input" for targets
                    // that were never shown; end the run at what was actually presented instead.
                    run.endEarly()
                    mode = InputMode.MONITOR
                },
                baseline = baseline,
            )

            InputMode.MONITOR -> MonitorList(
                run = run,
                analysis = analysis,
                devices = devices,
                events = events,
                baseline = baseline,
                logMoves = logMoves,
                onLogMoves = { logMoves = it },
                pointerCapture = pointerCapture,
                onPointerCapture = { want ->
                    pointerCapture = want
                    activeCapture?.setPointerCapture(want)
                    SessionLogger.log(
                        LabCategory.INPUT,
                        "pointer_capture_request",
                        LabStatus.OBSERVED,
                        mapOf("requested" to want.toString()),
                    )
                },
                hotplugEvents = hotplugEvents,
                newDeviceEvents = newDeviceEvents,
                distinctDevices = distinctDevices.size,
                distinctSources = distinctSources,
                onEvent = { e -> handler.value.invoke(e) },
                onCaptureView = { activeCapture = it },
                onClearEvents = {
                    events.clear()
                    SessionLogger.log(LabCategory.INPUT, "input_monitor_cleared", LabStatus.OBSERVED)
                },
                onGrabFocus = {
                    val ok = activeCapture?.grabFocus() ?: false
                    SessionLogger.log(
                        LabCategory.INPUT,
                        "capture_focus_requested",
                        if (ok) LabStatus.OBSERVED else LabStatus.ERROR,
                        mapOf("focused" to ok.toString()),
                    )
                },
                onRefresh = { refreshTick += 1 },
                onStartGrid = {
                    run.start(baseline)
                    mode = InputMode.GRID
                },
                onResetRun = { run.reset() },
                onMarker = { text ->
                    SessionLogger.marker(text, LabCategory.INPUT)
                },
            )
        }
    }
}

/* ----------------------------------------------------------------------------------------------
 * A + C: monitor, event stream, HID baseline, inventory
 * -------------------------------------------------------------------------------------------- */

@Composable
private fun MonitorList(
    run: TouchBackRun,
    analysis: TouchBackAnalysis,
    devices: List<InputDeviceFacts>,
    events: List<CapturedEvent>,
    baseline: Set<String>,
    logMoves: Boolean,
    onLogMoves: (Boolean) -> Unit,
    pointerCapture: Boolean,
    onPointerCapture: (Boolean) -> Unit,
    hotplugEvents: Int,
    newDeviceEvents: Int,
    distinctDevices: Int,
    distinctSources: Set<String>,
    onEvent: (CapturedEvent) -> Unit,
    onCaptureView: (InputCaptureView) -> Unit,
    onClearEvents: () -> Unit,
    onGrabFocus: () -> Unit,
    onRefresh: () -> Unit,
    onStartGrid: () -> Unit,
    onResetRun: () -> Unit,
    onMarker: (String) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {

        item {
            VerdictBanner(analysis.status, analysis.headline, analysis.detail)
        }

        item {
            LabCard("A. Event monitor") {
                Text(
                    "Everything that reaches this surface is decoded and logged: touch, generic " +
                        "motion, hover, captured pointer and keys, with the originating InputDevice. " +
                        "Touch it yourself first to prove the monitor is alive.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LabColors.TextDim,
                )
                Gap(10)
                CaptureSurface(
                    heightDp = 190,
                    onEvent = onEvent,
                    onCaptureView = onCaptureView,
                    label = "CAPTURE SURFACE - touch / hover / type here",
                )
                Gap(10)
                StatRow(
                    events.size.toString() to "events",
                    distinctDevices.toString() to "devices",
                    distinctSources.size.toString() to "sources",
                    newDeviceEvents.toString() to "off-baseline",
                    hotplugEvents.toString() to "hotplug",
                )
                Gap(6)
                Text(
                    "off-baseline = events from an input device that did not exist when this screen " +
                        "opened. That is the fingerprint of a real back channel.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LabColors.TextDim,
                )
                Gap(10)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = logMoves, onCheckedChange = onLogMoves)
                    Text(
                        "  log MOVE/HOVER_MOVE to evidence file",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = pointerCapture, onCheckedChange = onPointerCapture)
                    Text(
                        "  request pointer capture (relative mouse)",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Gap(8)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LabButton("CLEAR", onClearEvents, Modifier.weight(1f))
                    LabButton("FOCUS", onGrabFocus, Modifier.weight(1f))
                    LabButton("RESCAN", onRefresh, Modifier.weight(1f))
                }
            }
        }

        item {
            LabCard("Live event stream") {
                if (events.isEmpty()) {
                    Text(
                        "No events yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = LabColors.TextDim,
                    )
                } else {
                    MonoBlock(events.take(14).joinToString("\n") { it.oneLine() })
                }
            }
        }

        item {
            LabCard("B. Touch-back target grid") {
                Text(
                    "Mirror the phone to the head unit first, then run the grid and touch each " +
                        "highlighted number ON THE CAR SCREEN. A target that produces nothing is " +
                        "recorded as NO INPUT OBSERVED (NOT_TESTED) - never as unsupported.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LabColors.TextDim,
                )
                Gap(10)
                Text(
                    "Per-target timeout: " + (run.perTargetTimeoutMs / 1000) + " s",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Gap(6)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LabButton("10 s", { run.perTargetTimeoutMs = 10_000L }, Modifier.weight(1f))
                    LabButton("20 s", { run.perTargetTimeoutMs = 20_000L }, Modifier.weight(1f))
                    LabButton("45 s", { run.perTargetTimeoutMs = 45_000L }, Modifier.weight(1f))
                }
                Gap(10)
                BigActionButton(
                    text = "START TOUCH-BACK GRID",
                    subtitle = run.total.toString() + " targets, " + run.cols + "x" + run.rows,
                    onClick = onStartGrid,
                )
                if (run.results.isNotEmpty()) {
                    Gap(10)
                    TouchBackSummary(analysis, run)
                    Gap(8)
                    LabButton("RESET RUN", onResetRun, Modifier.fillMaxWidth())
                }
            }
        }

        item {
            LabCard("C. HID baseline") {
                Text(
                    "Plug in a USB-C or Bluetooth mouse/keyboard and confirm it shows up here and " +
                        "in the monitor. If it does not, the monitor is at fault, not the car.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LabColors.TextDim,
                )
                Gap(10)
                val hid = devices.filter { (it.isPointerLike || it.isKeyboardLike) && it.looksExternal }
                if (hid.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusChip(LabStatus.NOT_TESTED)
                        Text(
                            "  no external pointer/keyboard device enumerated right now",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                } else {
                    hid.forEach { DeviceLine(it, baseline) }
                }
            }
        }

        item {
            LabCard("Input device inventory (" + devices.size + ")") {
                if (devices.isEmpty()) {
                    Text(
                        "Scanning...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = LabColors.TextDim,
                    )
                } else {
                    devices.forEach { DeviceLine(it, baseline) }
                }
            }
        }

        item {
            LabCard("Manual markers") {
                Text(
                    "Timestamped into the same log, so the report can tell a car touch apart from a " +
                        "phone touch afterwards.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LabColors.TextDim,
                )
                Gap(8)
                LabButton(
                    "MARK: TOUCHING CAR SCREEN ONLY",
                    { onMarker("TOUCHING CAR SCREEN ONLY") },
                    Modifier.fillMaxWidth(),
                )
                Gap(6)
                LabButton(
                    "MARK: TOUCHING PHONE (control)",
                    { onMarker("TOUCHING PHONE - control input") },
                    Modifier.fillMaxWidth(),
                )
                Gap(6)
                LabButton(
                    "MARK: MIRRORING SESSION ACTIVE",
                    { onMarker("MIRRORING SESSION ACTIVE") },
                    Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun DeviceLine(f: InputDeviceFacts, baseline: Set<String>) {
    Column(Modifier.padding(vertical = 5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                f.name,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            if (f.identity !in baseline && baseline.isNotEmpty()) {
                Text(
                    "NEW",
                    style = LabMono,
                    color = LabColors.Observed,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Text(f.oneLine(), style = LabMono, color = LabColors.TextDim)
        if (f.external == null && f.externalError != null) {
            Text(
                "isExternal() unreadable: " + f.externalError,
                style = LabMono,
                color = LabColors.Unsupported,
            )
        }
        if (f.axes.isNotEmpty()) {
            Text(
                "axes: " + f.axes.joinToString(", ") { it.axisName },
                style = LabMono,
                color = LabColors.TextDim,
            )
        }
    }
}

@Composable
private fun TouchBackSummary(analysis: TouchBackAnalysis, run: TouchBackRun) {
    Column(Modifier.fillMaxWidth()) {
        StatRow(
            analysis.percentReached.toString() + "%" to "reached",
            analysis.hits.toString() + "/" + analysis.attempted to "hits",
            analysis.newDeviceHits.toString() to "off-baseline",
            analysis.skipped.toString() to "skipped",
        )
        Gap(8)
        Text(
            "sources: " + analysis.sources.joinToString(", ").ifEmpty { "none" },
            style = LabMono,
            color = LabColors.TextDim,
        )
        Text(
            "devices: " + analysis.devices.joinToString(", ").ifEmpty { "none" },
            style = LabMono,
            color = LabColors.TextDim,
        )
        Gap(8)
        Text("Coordinate transform", style = MaterialTheme.typography.titleLarge)
        val t = analysis.transform
        if (t == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusChip(LabStatus.NOT_TESTED)
                Text("  " + analysis.transformNote, style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            MonoBlock(t.oneLine())
            Gap(4)
            Text(
                if (t.isNearIdentity) {
                    "Near-identity: received coordinates match the drawn targets 1:1."
                } else {
                    "Scaled/offset mapping between drawn targets and received coordinates."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = LabColors.TextDim,
            )
        }
        Gap(8)
        run.results.forEach { r ->
            Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    String.format(Locale.US, "%02d", r.index + 1),
                    style = LabMono,
                    color = LabColors.TextDim,
                )
                Text("  ", style = LabMono)
                StatusChip(if (r.hit) LabStatus.OBSERVED else LabStatus.NOT_TESTED)
                Text(
                    "  " + r.label + (if (r.hit) " " + r.sourceNames + " " + r.deviceName else ""),
                    style = LabMono,
                    color = LabColors.Text,
                )
            }
        }
    }
}

/* ----------------------------------------------------------------------------------------------
 * B: the target grid
 * -------------------------------------------------------------------------------------------- */

@Composable
private fun GridExperiment(
    run: TouchBackRun,
    onEvent: (CapturedEvent) -> Unit,
    onCaptureView: (InputCaptureView) -> Unit,
    onExit: () -> Unit,
    baseline: Set<String>,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (run.running) {
                    "TARGET " + (run.activeIndex + 1) + " / " + run.total
                } else {
                    "RUN FINISHED"
                },
                style = MaterialTheme.typography.headlineMedium,
                color = LabColors.Accent,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (run.running) (run.remainingMs / 1000).toString() + " s" else "-",
                style = MaterialTheme.typography.headlineMedium,
                color = if (run.remainingMs < 5000) LabColors.Inferred else LabColors.TextDim,
            )
        }

        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .onSizeChanged {
                    run.gridWidthPx = it.width.toFloat()
                    run.gridHeightPx = it.height.toFloat()
                },
        ) {
            TargetGrid(run)
            // On top, transparent, and consuming: the raw View must see the event before Compose
            // normalises it away, otherwise the device id and source - the whole point - are lost.
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    InputCaptureView(ctx).also { v ->
                        v.onEvent = { e -> onEvent(e) }
                        onCaptureView(v)
                    }
                },
            )
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LabButton("SKIP", { run.skipActive() }, Modifier.weight(1f), enabled = run.running)
            LabButton("RESET", { run.reset() }, Modifier.weight(1f))
            LabButton("RESULTS", onExit, Modifier.weight(1f))
        }
        Text(
            "Hits from a device already present before the run (" + baseline.size +
                " baseline devices) prove only that something touched the phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = LabColors.TextDim,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun TargetGrid(run: TouchBackRun) {
    val done = run.results.associateBy { it.index }
    Column(Modifier.fillMaxSize().padding(4.dp)) {
        for (row in 0 until run.rows) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                for (col in 0 until run.cols) {
                    val index = row * run.cols + col
                    val result = done[index]
                    val active = run.running && run.activeIndex == index
                    val fill = when {
                        active -> LabColors.Accent.copy(alpha = 0.22f)
                        result?.hit == true -> LabColors.Confirmed.copy(alpha = 0.16f)
                        result != null -> LabColors.NotTested.copy(alpha = 0.12f)
                        else -> LabColors.SurfaceHigh
                    }
                    val edge = when {
                        active -> LabColors.Accent
                        result?.hit == true -> statusColor(LabStatus.OBSERVED)
                        result != null -> statusColor(LabStatus.NOT_TESTED)
                        else -> LabColors.Line
                    }
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(3.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(fill)
                            .border(if (active) 4.dp else 1.dp, edge, RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                (index + 1).toString(),
                                fontSize = if (active) 56.sp else 40.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (active) LabColors.Accent else LabColors.TextDim,
                            )
                            if (result != null) {
                                Text(
                                    if (result.hit) "HIT" else if (result.skipped) "SKIP" else "NONE",
                                    style = LabMono,
                                    color = edge,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/* ---------------------------------------------------------------------------------------------- */

@Composable
private fun CaptureSurface(
    heightDp: Int,
    onEvent: (CapturedEvent) -> Unit,
    onCaptureView: (InputCaptureView) -> Unit,
    label: String,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(heightDp.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(LabColors.Ink)
            .border(1.dp, LabColors.Accent, RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = LabMono, color = LabColors.AccentDim)
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                InputCaptureView(ctx).also { v ->
                    v.onEvent = { e -> onEvent(e) }
                    onCaptureView(v)
                }
            },
        )
    }
}
