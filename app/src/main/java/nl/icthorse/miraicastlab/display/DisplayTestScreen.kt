package nl.icthorse.miraicastlab.display

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nl.icthorse.miraicastlab.core.LabCategory
import nl.icthorse.miraicastlab.core.LabStatus
import nl.icthorse.miraicastlab.core.Observation
import nl.icthorse.miraicastlab.core.SessionLogger
import nl.icthorse.miraicastlab.core.safeObserve
import nl.icthorse.miraicastlab.scene.PresentationHostActivity
import nl.icthorse.miraicastlab.ui.BigActionButton
import nl.icthorse.miraicastlab.ui.Gap
import nl.icthorse.miraicastlab.ui.HGap
import nl.icthorse.miraicastlab.ui.LabButton
import nl.icthorse.miraicastlab.ui.LabCard
import nl.icthorse.miraicastlab.ui.LabColors
import nl.icthorse.miraicastlab.ui.LabMono
import nl.icthorse.miraicastlab.ui.LabScaffold
import nl.icthorse.miraicastlab.ui.MonoBlock
import nl.icthorse.miraicastlab.ui.ObservationRow
import nl.icthorse.miraicastlab.ui.StatRow
import nl.icthorse.miraicastlab.ui.VerdictBanner
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val CLOCK: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

/** How many topology events stay on screen. The full history is in the JSONL evidence file. */
private const val MAX_EVENTS = 60

/**
 * Live display topology instrument (spec sections 2.2, 2.3, 8 step 3).
 *
 * The tester cannot start Miracast from this app - Smart View owns that. What this screen can do is
 * watch: a DisplayManager.DisplayListener registered here timestamps the exact moment a display
 * appears, changes or disappears, and the two MARK buttons stamp the tester's own actions into the
 * same log. Correlating those two streams is the only honest way this project can say "Smart View
 * produced a display", so both sides are logged with millisecond timestamps.
 */
@Composable
fun DisplayTestScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var facts by remember { mutableStateOf(DisplayFacts.collect(context)) }
    var lastSignature by remember { mutableStateOf(DisplayFacts.signature(facts)) }
    val events = remember { mutableStateListOf<String>() }
    var probeOutput by remember { mutableStateOf<List<Observation>>(emptyList()) }
    var probeRunning by remember { mutableStateOf(false) }

    val addEvent: (String) -> Unit = { line ->
        events.add(0, CLOCK.format(Instant.now()) + "  " + line)
        while (events.size > MAX_EVENTS) events.removeAt(events.size - 1)
    }

    // The listener is the primary instrument; it fires on the main looper and must be released
    // when the screen leaves composition, or it keeps a Context alive for the rest of the run.
    DisposableEffect(Unit) {
        val dm = DisplayFacts.manager(context)

        fun report(event: String, displayId: Int) {
            val fresh = DisplayFacts.collect(context)
            val f = fresh.firstOrNull { it.displayId == displayId }
            val details = buildMap {
                put("displayId", displayId.toString())
                put("displayCount", fresh.size.toString())
                put("externalCount", fresh.count { it.isExternal }.toString())
                f?.toLogDetails()?.forEach { (k, v) -> put(k, v) }
            }
            SessionLogger.log(LabCategory.DISPLAY, event, LabStatus.OBSERVED, details)
            facts = fresh
            lastSignature = DisplayFacts.signature(fresh)
            addEvent(
                event + "  id=" + displayId + "  " +
                    (f?.let { "\"" + it.name + "\" " + it.resolution + " " + it.stateName } ?: "(no longer listed)"),
            )
        }

        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = report("display_added", displayId)
            override fun onDisplayRemoved(displayId: Int) = report("display_removed", displayId)
            override fun onDisplayChanged(displayId: Int) = report("display_changed", displayId)
        }

        if (dm == null) {
            SessionLogger.log(
                LabCategory.DISPLAY,
                "display_listener_unavailable",
                LabStatus.UNSUPPORTED,
                mapOf("reason" to "getSystemService(DISPLAY_SERVICE) returned null"),
            )
            addEvent("DisplayManager unavailable - no live topology events")
            onDispose { }
        } else {
            dm.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
            SessionLogger.log(
                LabCategory.DISPLAY,
                "display_listener_registered",
                LabStatus.CONFIRMED,
                mapOf("displayCount" to facts.size.toString()),
            )
            addEvent("listener registered - watching for topology changes")
            onDispose {
                runCatching { dm.unregisterDisplayListener(listener) }
                SessionLogger.log(LabCategory.DISPLAY, "display_listener_unregistered", LabStatus.CONFIRMED)
            }
        }
    }

    // Backstop poll. Some vendors change a display's metrics (DeX vs mirroring) without emitting a
    // callback; a signature diff catches that and is logged as its own, distinct event type.
    LaunchedEffect(Unit) {
        SessionLogger.log(
            LabCategory.DISPLAY,
            "display_test_screen_opened",
            LabStatus.OBSERVED,
            mapOf("topology" to lastSignature, "displayCount" to facts.size.toString()),
        )
        while (true) {
            delay(2000)
            val fresh = DisplayFacts.collect(context)
            val sig = DisplayFacts.signature(fresh)
            if (sig != lastSignature) {
                SessionLogger.log(
                    LabCategory.DISPLAY,
                    "display_topology_changed_poll",
                    LabStatus.OBSERVED,
                    mapOf("before" to lastSignature, "after" to sig),
                )
                addEvent("poll: topology changed -> " + sig)
                lastSignature = sig
                facts = fresh
            }
        }
    }

    val external = facts.filter { it.isExternal }
    val presentationExternal = external.filter { it.inPresentationCategory }

    LabScaffold(
        title = "Display test",
        onBack = onBack,
        subtitle = facts.size.toString() + " display(s), " + external.size + " external",
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            if (external.isEmpty()) {
                VerdictBanner(
                    status = LabStatus.NOT_TESTED,
                    headline = "No external display attached",
                    detail = "Only DEFAULT_DISPLAY is present. Nothing is claimed about the head unit: " +
                        "start Smart View / DeX and watch this screen for a display_added event.",
                )
            } else {
                VerdictBanner(
                    status = LabStatus.OBSERVED,
                    headline = "External display present (" + external.size + ")",
                    detail = if (presentationExternal.isNotEmpty()) {
                        "In DISPLAY_CATEGORY_PRESENTATION, so an ordinary app may put content on it. " +
                            "The transport (Miracast / DeX / virtual) is not identifiable from DisplayManager."
                    } else {
                        "Not listed in DISPLAY_CATEGORY_PRESENTATION - typically FLAG_PRIVATE, e.g. this " +
                            "lab's own VirtualDisplay. Not evidence of a wireless sink."
                    },
                )
            }

            LabCard(title = "Topology") {
                StatRow(
                    facts.size.toString() to "displays",
                    external.size.toString() to "external",
                    presentationExternal.size.toString() to "presentation",
                )
                Gap()
                Text(
                    "signature: " + lastSignature,
                    style = LabMono,
                    color = LabColors.TextDim,
                )
            }

            LabCard(title = "Actions") {
                val target = presentationExternal.firstOrNull() ?: external.firstOrNull()
                BigActionButton(
                    text = "SEND TEST SCENE TO EXTERNAL DISPLAY",
                    subtitle = target?.let { "target: id=" + it.displayId + " " + it.resolution }
                        ?: "disabled - no external display",
                    enabled = target != null,
                    onClick = { target?.let { launchSceneOnDisplay(context, it.displayId, addEvent) } },
                )
                Gap()
                Text(
                    "The scene activity logs the display it actually landed on. If the system " +
                        "re-routes the launch to the phone, that mismatch is the finding.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LabColors.TextDim,
                )
                Gap(12)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LabButton(
                        text = "MARK: SMART VIEW STARTED",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            SessionLogger.marker("SMART VIEW STARTED (tester)", LabCategory.SMART_VIEW)
                            addEvent("MARKER: smart view started (tester)")
                        },
                    )
                    HGap()
                    LabButton(
                        text = "MARK: SMART VIEW STOPPED",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            SessionLogger.marker("SMART VIEW STOPPED (tester)", LabCategory.SMART_VIEW)
                            addEvent("MARKER: smart view stopped (tester)")
                        },
                    )
                }
                Gap(12)
                LabButton(
                    text = if (probeRunning) "RUNNING..." else "RUN DISPLAY + MEDIAROUTE PROBES",
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !probeRunning,
                    onClick = {
                        probeRunning = true
                        scope.launch {
                            val found = DisplayProbe.safeObserve(context) + MediaRouteProbe.safeObserve(context)
                            SessionLogger.logAll(found)
                            probeOutput = found
                            probeRunning = false
                            addEvent("probes run: " + found.size + " observations")
                        }
                    },
                )
            }

            facts.forEach { f ->
                LabCard(
                    title = (if (f.isExternal) "EXTERNAL " else "BUILT-IN ") +
                        "display " + f.displayId + " - " + f.name,
                ) {
                    f.uiLines().forEach { (k, v) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            Text(
                                k,
                                style = LabMono,
                                color = LabColors.TextDim,
                                modifier = Modifier.weight(0.42f),
                            )
                            Text(
                                v,
                                style = LabMono,
                                color = LabColors.Text,
                                modifier = Modifier.weight(0.58f),
                            )
                        }
                    }
                    if (f.isExternal) {
                        Gap()
                        LabButton(
                            text = "SEND SCENE TO DISPLAY " + f.displayId,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { launchSceneOnDisplay(context, f.displayId, addEvent) },
                        )
                    }
                }
            }

            LabCard(title = "Topology events (newest first)") {
                MonoBlock(
                    if (events.isEmpty()) "no events yet" else events.joinToString("\n"),
                )
            }

            if (probeOutput.isNotEmpty()) {
                LabCard(title = "Probe output (" + probeOutput.size + ")") {
                    // A LazyColumn cannot be nested in this vertically scrolling Column, so the
                    // rows are emitted directly.
                    probeOutput.forEach { ObservationRow(it) }
                }
            }

            Gap(24)
        }
    }
}

/**
 * Puts the diagnostic scene on a specific display using the public launch-display-id option.
 *
 * A successful startActivity only means the request was accepted: the system may still place the
 * activity on the default display (a private or untrusted display refuses foreign activities).
 * PresentationHostActivity logs the display it really got, so the pair of records tells the truth.
 */
private fun launchSceneOnDisplay(context: Context, displayId: Int, addEvent: (String) -> Unit) {
    try {
        val intent = Intent(context, PresentationHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
        context.startActivity(intent, options.toBundle())
        SessionLogger.log(
            LabCategory.DISPLAY,
            "scene_launch_requested",
            LabStatus.OBSERVED,
            mapOf(
                "requestedDisplayId" to displayId.toString(),
                "note" to "Accepted by the system; the host activity logs where it actually landed.",
            ),
        )
        addEvent("scene launch requested on display " + displayId)
    } catch (t: Throwable) {
        SessionLogger.log(
            LabCategory.DISPLAY,
            "scene_launch_failed",
            LabStatus.ERROR,
            mapOf(
                "requestedDisplayId" to displayId.toString(),
                "error" to (t::class.java.simpleName + ": " + (t.message ?: "no message")),
            ),
        )
        addEvent("scene launch FAILED on display " + displayId + ": " + t::class.java.simpleName)
    }
}
