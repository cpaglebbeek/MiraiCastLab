package nl.icthorse.miraicastlab.scene

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import nl.icthorse.miraicastlab.ui.Gap
import nl.icthorse.miraicastlab.ui.HGap
import nl.icthorse.miraicastlab.ui.LabButton
import nl.icthorse.miraicastlab.ui.LabColors
import nl.icthorse.miraicastlab.ui.LabMono
import nl.icthorse.miraicastlab.ui.Dot

/**
 * The phone-side controller (spec section 10).
 *
 * It reads and writes [SceneState] only, never the renderer, so the identical panel drives a scene
 * rendered on this phone and a scene rendered by PresentationHostActivity on the Toyota's display.
 * The tester keeps the phone in hand either way.
 *
 * Collapsing matters more than it looks: over Smart View the phone's own screen is the source, so
 * anything drawn here also lands on the head unit. Collapsed, the panel is a single strip and the
 * mirrored picture is very nearly the pattern alone.
 */
@Composable
internal fun SceneControls(modifier: Modifier = Modifier, compact: Boolean = false) {
    val expanded by SceneState.controlsExpanded.collectAsState()
    Column(
        modifier
            .fillMaxWidth()
            .background(LabColors.Surface)
            .border(1.dp, LabColors.Line),
    ) {
        ControlStrip(expanded = expanded)
        if (expanded) ExpandedControls(compact = compact)
    }
}

/** Always visible: the four things the tester needs while looking at the other screen. */
@Composable
private fun ControlStrip(expanded: Boolean) {
    val playing by SceneState.playing.collectAsState()
    val pattern by SceneState.pattern.collectAsState()
    val stats by SceneState.stats.collectAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LabButton(if (playing) "PAUSE" else "PLAY", onClick = { SceneState.togglePlay() })
        HGap(6)
        LabButton("PATTERN", onClick = { SceneState.nextPattern() })
        HGap(8)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(if (stats.fps > 1f) LabColors.Confirmed else LabColors.NotTested, 8)
                HGap(5)
                Text(fmt(stats.fps, 1) + " fps", style = LabMono, color = LabColors.Text)
            }
            Text(pattern.label, style = LabMono, color = LabColors.TextDim)
        }
        LabButton(if (expanded) "HIDE" else "CONTROLS", onClick = { SceneState.setControlsExpanded(!expanded) })
    }
}

@Composable
private fun ExpandedControls(compact: Boolean) {
    val stats by SceneState.stats.collectAsState()
    val speed by SceneState.speed.collectAsState()
    val showGrid by SceneState.showGrid.collectAsState()
    val inputCapture by SceneState.inputCapture.collectAsState()
    val pattern by SceneState.pattern.collectAsState()
    val latencyStart by SceneState.latencyStartedAt.collectAsState()
    val renderers by SceneState.renderers.collectAsState()
    val fullscreen by SceneState.fullscreen.collectAsState()

    var localSpeed by remember { mutableFloatStateOf(speed) }
    LaunchedEffect(speed) { localSpeed = speed }
    var markerText by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = if (compact) 300.dp else 460.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        // ---- measured facts, not claims
        Text(
            "p99 " + fmt(stats.p99FrameMs, 1) + " ms   avg " + fmt(stats.avgFrameMs, 2) +
                " ms   max " + fmt(stats.maxFrameMs, 1) + " ms",
            style = LabMono,
            color = LabColors.Text,
        )
        Text(
            "frames " + stats.framesRendered + "   long " + stats.longFrames +
                "   window " + stats.framesInStatsWindow,
            style = LabMono,
            color = LabColors.TextDim,
        )
        Text(
            "surface " + stats.surfaceWidthPx + "x" + stats.surfaceHeightPx + " px   display " +
                stats.displayId + " " + stats.displayName + "   " + fmt(stats.refreshHz, 1) + " Hz",
            style = LabMono,
            color = LabColors.TextDim,
        )
        Text(
            "renderers on display id " + (if (renderers.isEmpty()) "none" else renderers.sorted().joinToString(",")) +
                "   touches " + stats.touchCount + " last " + stats.lastTouch,
            style = LabMono,
            color = LabColors.TextDim,
        )
        Gap(6)

        Text("SPEED  " + fmt(localSpeed, 2) + "x", style = LabMono, color = LabColors.Text)
        Slider(
            value = localSpeed,
            onValueChange = { localSpeed = it },
            onValueChangeFinished = { SceneState.setSpeed(localSpeed) },
            valueRange = 0.25f..4f,
            steps = 14,
        )

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LabButton(if (showGrid) "GRID ON" else "GRID OFF", onClick = { SceneState.toggleGrid() }, modifier = Modifier.weight(1f))
            LabButton("PULSE", onClick = { SceneState.triggerAudioPulse() }, modifier = Modifier.weight(1f))
        }
        Gap(4)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LabButton(
                if (latencyStart == 0L) "LATENCY SEQ" else "LATENCY RUNNING",
                onClick = { SceneState.startLatencySequence() },
                modifier = Modifier.weight(1f),
                enabled = latencyStart == 0L,
            )
            LabButton("RESET STATS", onClick = { SceneState.resetStats() }, modifier = Modifier.weight(1f))
        }
        Gap(4)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LabButton(
                if (inputCapture) "CAPTURING INPUT" else "START INPUT CAPTURE",
                onClick = { SceneState.toggleInputCapture() },
                modifier = Modifier.weight(1f),
            )
            LabButton(
                if (fullscreen) "EXIT FULLSCREEN" else "FULLSCREEN",
                onClick = { SceneState.setFullscreen(!fullscreen) },
                modifier = Modifier.weight(1f),
            )
        }

        Gap(8)
        Text(pattern.label + " - " + pattern.purpose, style = LabMono, color = LabColors.TextDim)

        Gap(8)
        Text("OBSERVATION MARKER", style = LabMono, color = LabColors.Text, fontWeight = FontWeight.Bold)
        Text(
            "What you see on the receiver, in your words. Timestamped against the frame counter.",
            style = LabMono,
            color = LabColors.TextDim,
        )
        Gap(4)
        // Fixed markers for what the *picture* does. Vehicle-state markers deliberately live in the
        // motion-state screen: this panel records rendering, not anything about a vehicle.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LabButton("VIDEO VISIBLE", onClick = { SceneState.addMarker("VIDEO VISIBLE") }, modifier = Modifier.weight(1f))
            LabButton("VIDEO BLOCKED", onClick = { SceneState.addMarker("VIDEO BLOCKED") }, modifier = Modifier.weight(1f))
        }
        Gap(4)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LabButton("AUDIO ONLY", onClick = { SceneState.addMarker("AUDIO ONLY") }, modifier = Modifier.weight(1f))
            LabButton("CONNECTION LOST", onClick = { SceneState.addMarker("CONNECTION LOST") }, modifier = Modifier.weight(1f))
        }
        Gap(4)
        OutlinedTextField(
            value = markerText,
            onValueChange = { markerText = it },
            label = { Text("free-text marker") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Gap(4)
        LabButton(
            "ADD MARKER",
            onClick = {
                SceneState.addMarker(markerText)
                markerText = ""
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = markerText.isNotBlank(),
        )
        Gap(8)
    }
}

/** Fixed-decimal formatting for the panel; the scene itself formats without allocating. */
private fun fmt(v: Float, decimals: Int): String {
    if (v.isNaN() || v.isInfinite()) return "-"
    var m = 1f
    for (i in 0 until decimals) m *= 10f
    val scaled = Math.round(v * m).toLong()
    if (decimals == 0) return scaled.toString()
    val whole = scaled / m.toLong()
    var frac = (scaled % m.toLong()).toString()
    while (frac.length < decimals) frac = "0" + frac
    return whole.toString() + "." + frac
}
