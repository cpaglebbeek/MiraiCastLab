package nl.icthorse.miraicastlab.auto

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import nl.icthorse.miraicastlab.core.LabCategory
import nl.icthorse.miraicastlab.core.LabStatus
import nl.icthorse.miraicastlab.core.LogRecord
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
import nl.icthorse.miraicastlab.ui.StatRow
import nl.icthorse.miraicastlab.ui.StatusChip

/** The marker set from spec section 12, in the order the spec lists them. */
private val MOTION_MARKERS = listOf(
    "PARK",
    "DRIVE STATE SIMULATED",
    "MOTION STATE SIMULATED",
    "VIDEO VISIBLE",
    "VIDEO BLOCKED",
    "AUDIO ONLY",
    "CONNECTION LOST",
)

/**
 * The transitions worth timing. Each is a cause the tester marks followed by an effect the tester
 * observes on the head unit; the elapsed time between them is the finding this experiment produces.
 */
private val MOTION_PAIRS = listOf(
    "DRIVE STATE SIMULATED" to "VIDEO BLOCKED",
    "DRIVE STATE SIMULATED" to "AUDIO ONLY",
    "DRIVE STATE SIMULATED" to "VIDEO VISIBLE",
    "DRIVE STATE SIMULATED" to "CONNECTION LOST",
    "MOTION STATE SIMULATED" to "VIDEO BLOCKED",
    "MOTION STATE SIMULATED" to "AUDIO ONLY",
    "MOTION STATE SIMULATED" to "CONNECTION LOST",
    "PARK" to "VIDEO VISIBLE",
)

/** One second of Android-side liveness. Everything here is measured, nothing is assumed. */
internal data class LivenessSample(
    val atIso: String,
    val elapsedMs: Long,
    val appFrames: Long,
    val fps: String,
    val sceneFrames: String,
    val displayCount: String,
    val externalDisplay: String,
    val presentationDisplay: String,
    val transport: String,
    val audio: String,
) {
    fun line(): String =
        atIso.takeLast(13).removeSuffix("Z") +
            "  fps=" + fps.padStart(5) +
            "  frames=" + appFrames.toString().padStart(6) +
            "  scene=" + sceneFrames +
            "  disp=" + displayCount +
            "  ext=" + externalDisplay +
            "  pres=" + presentationDisplay +
            "  net=" + transport +
            "  audio=" + audio
}

/** Result of timing one marker transition across the whole run. */
internal data class PairFinding(val from: String, val to: String, val deltasMs: List<Long>) {
    val status: LabStatus get() = if (deltasMs.isEmpty()) LabStatus.NOT_TESTED else LabStatus.OBSERVED
}

/**
 * Times every [MOTION_PAIRS] transition in a chronological marker list.
 *
 * A "from" marker arms the stopwatch; the next matching "to" marker stops it. A second "from"
 * before any "to" re-arms it, because the tester repeating a step means the earlier attempt did not
 * produce the effect - inventing a match there would fabricate a measurement.
 */
internal fun analyseMarkerPairs(markers: List<Pair<String, Long>>): List<PairFinding> =
    MOTION_PAIRS.map { (from, to) ->
        val deltas = mutableListOf<Long>()
        var armedAt: Long? = null
        for ((text, elapsed) in markers) {
            if (text == from) {
                armedAt = elapsed
            } else if (text == to) {
                val start = armedAt
                if (start != null) {
                    deltas.add(elapsed - start)
                    armedAt = null
                }
            }
        }
        PairFinding(from, to, deltas)
    }

/**
 * Spec section 12 - lab motion-state / video availability observation.
 *
 * This screen is a witness, not an actuator. Layer A runs an Android-side liveness recorder so that
 * "the picture stopped" can be separated from "the phone stopped rendering". Layer B is a set of
 * markers the tester presses while watching the head unit.
 *
 * There is no code here, and there will be no code here, that changes what the vehicle believes
 * about its own motion. No CAN traffic, no vehicle-state injection, no interlock defeat. If the head
 * unit hides video, that is the finding.
 */
@Composable
fun MotionStateScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current

    var recording by remember { mutableStateOf(true) }
    var samples by remember { mutableStateOf<List<LivenessSample>>(emptyList()) }
    var freeText by remember { mutableStateOf("") }

    val appFrames by MotionLiveness.appFrames.collectAsState()
    val sceneFrames by MotionLiveness.sceneFrames.collectAsState()
    val records by SessionLogger.records.collectAsState()

    val markerHistory = remember(records) { motionMarkers(records) }
    val findings = remember(markerHistory) { analyseMarkerPairs(markerHistory.map { it.first to it.second }) }

    DisposableEffect(Unit) {
        MotionLiveness.reset()
        SessionLogger.log(
            LabCategory.MOTION_STATE,
            "motion_state_recorder_opened",
            LabStatus.CONFIRMED,
            LabEnv.liveSnapshot(ctx),
        )
        onDispose {
            SessionLogger.log(
                LabCategory.MOTION_STATE,
                "motion_state_recorder_closed",
                LabStatus.CONFIRMED,
                mapOf("appFrames" to MotionLiveness.appFrames.value.toString()),
            )
        }
    }

    // Frame pump. Awaiting a frame makes the Recomposer schedule one, so the counter advances only
    // while the platform is genuinely delivering frames to this process. That is the whole point:
    // it is first-hand proof of rendering, not a timer pretending to be one.
    LaunchedEffect(recording) {
        while (recording) {
            withFrameNanos { }
            MotionLiveness.onFrame()
        }
    }

    // One sample per second (spec section 12 layer A).
    LaunchedEffect(recording) {
        var lastFrames = MotionLiveness.appFrames.value
        var lastElapsed = SystemClock.elapsedRealtime()
        var lastExternal: String? = null
        var lastTransport: String? = null
        while (recording) {
            delay(1000)
            val now = SystemClock.elapsedRealtime()
            val frames = MotionLiveness.appFrames.value
            val dt = (now - lastElapsed).coerceAtLeast(1L)
            val fpsTenths = (frames - lastFrames) * 10000L / dt
            val live = LabEnv.liveSnapshot(ctx)
            val sample = LivenessSample(
                atIso = LabEnv.nowIso(),
                elapsedMs = now,
                appFrames = frames,
                fps = (fpsTenths / 10).toString() + "." + (fpsTenths % 10),
                sceneFrames = MotionLiveness.sceneFramesText(),
                displayCount = live["displayCount"].orEmpty(),
                externalDisplay = live["externalDisplay"].orEmpty(),
                presentationDisplay = live["presentationDisplay"].orEmpty(),
                transport = live["transport"].orEmpty(),
                audio = live["audio"].orEmpty(),
            )
            samples = (samples + sample).takeLast(300)

            SessionLogger.log(
                LabCategory.MOTION_STATE,
                "liveness_sample",
                LabStatus.OBSERVED,
                mapOf(
                    "appFrames" to sample.appFrames.toString(),
                    "fps" to sample.fps,
                    "sceneFrames" to sample.sceneFrames,
                    "displayCount" to sample.displayCount,
                    "externalDisplay" to sample.externalDisplay,
                    "presentationDisplay" to sample.presentationDisplay,
                    "transport" to sample.transport,
                    "audio" to sample.audio,
                ),
            )

            // Transitions are the interesting part of a timeline; call them out as their own event
            // so they are findable in the log without scanning every heartbeat.
            if (lastExternal != null && lastExternal != sample.externalDisplay) {
                SessionLogger.log(
                    LabCategory.MOTION_STATE,
                    "external_display_changed",
                    LabStatus.OBSERVED,
                    mapOf("from" to lastExternal.orEmpty(), "to" to sample.externalDisplay),
                )
            }
            if (lastTransport != null && lastTransport != sample.transport) {
                SessionLogger.log(
                    LabCategory.MOTION_STATE,
                    "transport_changed",
                    LabStatus.OBSERVED,
                    mapOf("from" to lastTransport.orEmpty(), "to" to sample.transport),
                )
            }
            lastExternal = sample.externalDisplay
            lastTransport = sample.transport
            lastFrames = frames
            lastElapsed = now
        }
    }

    LabScaffold(
        title = "Lab motion-state test",
        onBack = onBack,
        subtitle = "Spec section 12 - observation and markers only",
    ) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 40.dp, top = 4.dp),
        ) {
            item { SafetyCard() }

            item {
                LabCard(title = "Layer A - Android-side liveness") {
                    StatRow(
                        appFrames.toString() to "frames",
                        (samples.lastOrNull()?.fps ?: "-") to "fps",
                        (sceneFrames?.toString() ?: "none") to "scene frames",
                        (samples.size).toString() to "samples",
                    )
                    Gap()
                    Row {
                        Dot(if (recording) LabColors.Confirmed else LabColors.NotTested, 12)
                        HGap()
                        Text(
                            if (recording) "recording, one sample per second" else "paused",
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (recording) LabColors.Confirmed else LabColors.TextDim,
                        )
                    }
                    if (sceneFrames == null) {
                        Gap(4)
                        Text(
                            "Scene frame source: none registered, so the recorder logs " +
                                "\"scene not running\" rather than a number it cannot see.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = LabColors.NotTested,
                        )
                    }
                    Gap()
                    Row {
                        LabButton(
                            if (recording) "Pause recorder" else "Resume recorder",
                            { recording = !recording },
                            Modifier.weight(1f),
                        )
                    }
                }
            }

            item {
                LabCard(title = "Timeline") {
                    if (samples.isEmpty()) {
                        Text("waiting for the first sample...", style = LabMono, color = LabColors.TextDim)
                    } else {
                        MonoBlock(samples.takeLast(25).reversed().joinToString("\n") { it.line() })
                        Gap()
                        Text(
                            "Newest first, last 25 of " + samples.size + " samples held in memory. " +
                                "Every sample is also written to the evidence file.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = LabColors.TextDim,
                        )
                    }
                }
            }

            item {
                LabCard(title = "Layer B - tester markers") {
                    Text(
                        "Press the moment you see it happen. Each press is timestamped against the " +
                            "same clock as the liveness samples.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = LabColors.TextDim,
                    )
                    Gap()
                    MOTION_MARKERS.chunked(2).forEach { pair ->
                        Row(Modifier.fillMaxWidth()) {
                            pair.forEach { marker ->
                                BigActionButton(
                                    text = marker,
                                    onClick = { markObservation(marker, ctx) },
                                    modifier = Modifier.weight(1f),
                                )
                                HGap(6)
                            }
                            if (pair.size == 1) {
                                // Keeps the last odd button the same width as the rest.
                                Row(Modifier.weight(1f)) {}
                            }
                        }
                    }
                    Gap(12)
                    LabTextField("Free-text marker", freeText) { freeText = it }
                    Gap(4)
                    LabButton(
                        "Add free-text marker",
                        {
                            val text = freeText.trim()
                            if (text.isNotEmpty()) {
                                markObservation(text, ctx)
                                freeText = ""
                            }
                        },
                        Modifier.fillMaxWidth(),
                    )
                }
            }

            item {
                LabCard(title = "Markers this run") {
                    if (markerHistory.isEmpty()) {
                        Text("none yet", style = LabMono, color = LabColors.NotTested)
                    } else {
                        MonoBlock(
                            markerHistory.takeLast(20).reversed().joinToString("\n") { entry ->
                                entry.third.takeLast(13).removeSuffix("Z") + "  " + entry.first
                            },
                        )
                    }
                }
            }

            item { FindingsCard(findings) }

            item {
                LabCard(title = "What this run cannot establish") {
                    Text(
                        "Head-unit behaviour under a simulated drive state can only be recorded with " +
                            "a bench receiver or a stationary vehicle in a controlled setting. Until " +
                            "the matching markers exist in a run, every transition below stays " +
                            "NOT_TESTED. It is never rewritten as UNSUPPORTED.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = LabColors.TextDim,
                    )
                }
            }
        }
    }
}

/**
 * The safety statement. Deliberately at the top, deliberately not dismissible, deliberately in the
 * UI and not only in a source comment: the person who needs to read it is the tester, not a reviewer.
 */
@Composable
private fun SafetyCard() {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(LabColors.Unsupported.copy(alpha = 0.12f))
            .border(2.dp, LabColors.Unsupported, RoundedCornerShape(14.dp))
            .padding(16.dp),
    ) {
        Text(
            "Bench or stationary vehicle only",
            style = MaterialTheme.typography.headlineMedium,
            color = LabColors.Unsupported,
        )
        Gap()
        Text(
            "This screen records what the head unit does. It does not change, and will not change, " +
                "anything about the vehicle. It sends no message to the car, simulates no vehicle " +
                "state, and defeats no safety interlock.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Gap()
        Text(
            "If the head unit hides video because it believes the vehicle is moving, that is the " +
                "result you are here to write down - not a problem to work around.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Gap()
        Text(
            "Run this on a bench receiver or in a stationary vehicle, with someone other than the " +
                "driver operating the phone. Never while driving.",
            style = MaterialTheme.typography.bodyLarge,
            color = LabColors.Inferred,
        )
    }
}

@Composable
private fun FindingsCard(findings: List<PairFinding>) {
    LabCard(title = "Findings - time between markers") {
        Text(
            "The elapsed time between a marked cause and the observed effect is the measurement " +
                "this experiment produces.",
            style = MaterialTheme.typography.bodyMedium,
            color = LabColors.TextDim,
        )
        Gap()
        findings.forEach { f ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(f.from + "  ->  " + f.to, style = LabMono, color = LabColors.Text)
                    if (f.deltasMs.isEmpty()) {
                        Text(
                            "not observed in this run",
                            style = MaterialTheme.typography.bodyMedium,
                            color = LabColors.NotTested,
                        )
                    } else {
                        val first = f.deltasMs.first()
                        val fastest = f.deltasMs.minOrNull() ?: first
                        val slowest = f.deltasMs.maxOrNull() ?: first
                        Text(
                            "n=" + f.deltasMs.size +
                                "  first=" + formatMs(first) +
                                "  fastest=" + formatMs(fastest) +
                                "  slowest=" + formatMs(slowest),
                            style = MaterialTheme.typography.bodyMedium,
                            color = LabColors.Observed,
                        )
                    }
                }
                StatusChip(f.status)
            }
        }
    }
}

/** Writes a tester marker and pins the Android-side state to it, so the pair is analysable later. */
private fun markObservation(text: String, ctx: android.content.Context) {
    SessionLogger.marker(text, LabCategory.MOTION_STATE)
    SessionLogger.log(
        LabCategory.MOTION_STATE,
        "marker_context",
        LabStatus.OBSERVED,
        LabEnv.liveSnapshot(ctx) + mapOf(
            "marker" to text,
            "appFrames" to MotionLiveness.appFrames.value.toString(),
            "sceneFrames" to MotionLiveness.sceneFramesText(),
        ),
    )
}

/** Marker text, elapsedRealtime and ISO timestamp for every motion-state marker in this run. */
private fun motionMarkers(records: List<LogRecord>): List<Triple<String, Long, String>> =
    records
        .filter { it.event == "manual_marker" && it.category == LabCategory.MOTION_STATE }
        .mapNotNull { r ->
            val text = r.details["marker"] ?: return@mapNotNull null
            Triple(text, r.elapsedRealtimeMs, r.timestamp)
        }
