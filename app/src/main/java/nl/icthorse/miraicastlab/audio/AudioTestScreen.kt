package nl.icthorse.miraicastlab.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import nl.icthorse.miraicastlab.core.LabCategory
import nl.icthorse.miraicastlab.core.LabStatus
import nl.icthorse.miraicastlab.core.Observation
import nl.icthorse.miraicastlab.core.SessionLogger
import nl.icthorse.miraicastlab.core.safeObserve
import nl.icthorse.miraicastlab.ui.BigActionButton
import nl.icthorse.miraicastlab.ui.Gap
import nl.icthorse.miraicastlab.ui.HGap
import nl.icthorse.miraicastlab.ui.LabButton
import nl.icthorse.miraicastlab.ui.LabCard
import nl.icthorse.miraicastlab.ui.LabColors
import nl.icthorse.miraicastlab.ui.LabScaffold
import nl.icthorse.miraicastlab.ui.MonoBlock
import nl.icthorse.miraicastlab.ui.ObservationRow
import nl.icthorse.miraicastlab.ui.StatusChip
import nl.icthorse.miraicastlab.ui.VerdictBanner
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

private fun stamp(): String = LocalTime.now().format(CLOCK)

private fun ms(v: Double?): String =
    if (v == null) "     -  " else String.format(Locale.US, "%+8.1f", v)

/**
 * Audio test (spec section 2.5).
 *
 * Two independent jobs on one screen:
 *  1. watch the audio route move to the vehicle and back, live, with timestamps;
 *  2. emit purpose-built signals - silence, mono, per-channel, continuous, and two kinds of
 *     transient - so the tester can hear what arrives and film what arrives.
 *
 * Everything the screen measures is measured inside this phone. The end-to-end number the project
 * actually wants comes from an external recording, and the screen says so where a reader might
 * otherwise mistake an internal figure for it.
 */
@Composable
fun AudioTestScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val am = remember(context) {
        runCatching { context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }.getOrNull()
    }
    val engine = remember { ToneEngine() }

    val mode by engine.mode.collectAsState()
    val channel by engine.stereoChannel.collectAsState()
    val flash by engine.flash.collectAsState()
    val pulses by engine.pulses.collectAsState()
    val routed by engine.routedDevice.collectAsState()
    val trackInfo by engine.trackInfo.collectAsState()
    val engineError by engine.error.collectAsState()

    var outputs by remember { mutableStateOf<List<String>>(emptyList()) }
    var routeChanges by remember { mutableStateOf<List<String>>(emptyList()) }
    var probeObs by remember { mutableStateOf<List<Observation>>(emptyList()) }

    // The engine owns an AudioTrack and a thread; both die with the screen, or the route to the
    // vehicle stays open behind the tester's back and poisons every later observation.
    DisposableEffect(engine) {
        onDispose { engine.release() }
    }

    // Live route watch. This is how the tester sees the audio move to the car: registration also
    // fires once with the current device list, which seeds the panel.
    DisposableEffect(am) {
        val manager = am
        if (manager == null) {
            onDispose { }
        } else {
            val handler = Handler(Looper.getMainLooper())
            var seeding = true
            fun refresh() {
                outputs = runCatching {
                    manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                        .map { describeAudioDevice(it) }
                }.getOrDefault(emptyList())
            }
            val callback = object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                    val initial = seeding
                    seeding = false
                    addedDevices.forEach { d ->
                        val desc = describeAudioDevice(d)
                        val tag = if (initial) "PRESENT" else "ADDED  "
                        routeChanges = (listOf(stamp() + "  " + tag + "  " + desc) + routeChanges)
                            .take(60)
                        SessionLogger.log(
                            LabCategory.AUDIO,
                            if (initial) "audio_device_present" else "audio_device_added",
                            LabStatus.OBSERVED,
                            mapOf(
                                "device" to desc,
                                "type" to audioDeviceTypeName(d.type),
                                "sink" to runCatching { d.isSink.toString() }.getOrDefault("?"),
                            ),
                        )
                    }
                    refresh()
                }

                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                    removedDevices.forEach { d ->
                        val desc = describeAudioDevice(d)
                        routeChanges = (listOf(stamp() + "  REMOVED  " + desc) + routeChanges)
                            .take(60)
                        SessionLogger.log(
                            LabCategory.AUDIO,
                            "audio_device_removed",
                            LabStatus.OBSERVED,
                            mapOf("device" to desc, "type" to audioDeviceTypeName(d.type)),
                        )
                    }
                    refresh()
                }
            }
            runCatching { manager.registerAudioDeviceCallback(callback, handler) }
            onDispose { runCatching { manager.unregisterAudioDeviceCallback(callback) } }
        }
    }

    // Snapshot the routing facts on entry so the report shows the state the tester was looking at.
    LaunchedEffect(Unit) {
        SessionLogger.log(
            LabCategory.AUDIO,
            "audio_test_screen_opened",
            LabStatus.OBSERVED,
            mapOf("audioManager" to (am != null).toString()),
        )
        val obs = AudioRouteProbe.safeObserve(context)
        probeObs = obs
        SessionLogger.logAll(obs)
    }

    // The flash is armed on the audio thread; this records the frame clock time of the first frame
    // that actually carries it. withFrameNanos is the only honest hook we have for "when was it
    // drawn" - it is still a draw stamp, not a photon stamp, and it can lag the state change by one
    // frame (about 8 ms at 120 Hz).
    val flashIndex = flash?.index
    LaunchedEffect(flashIndex) {
        val i = flashIndex ?: return@LaunchedEffect
        withFrameNanos { frameNanos -> engine.recordVisualFlip(i, frameNanos) }
    }

    val activeObs = probeObs.firstOrNull { it.key == "audio.active_output" }
    val verdictStatus = when {
        routed != null -> LabStatus.OBSERVED
        activeObs != null -> activeObs.status
        else -> LabStatus.NOT_TESTED
    }
    val verdictHead = routed ?: activeObs?.value ?: "Unknown"
    val verdictDetail = when {
        routed != null ->
            "AudioTrack.getRoutedDevice() during actual playback. Direct evidence of where this " +
                "app's media stream went."
        activeObs != null -> activeObs.note ?: "From AudioRouteProbe."
        else -> "No AudioManager and no playback yet; nothing is claimed."
    }

    Box(Modifier.fillMaxSize()) {
        LabScaffold(
            title = "Audio test",
            subtitle = "Routes, test tones and A/V pulse",
            onBack = onBack,
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                VerdictBanner(verdictStatus, "Active output: " + verdictHead, verdictDetail)

                if (mode == ToneMode.STEREO_IDENTIFY) {
                    ChannelCallout(channel)
                }

                LabCard("Playback mode") {
                    Text(
                        mode?.let { it.label + " - " + it.subtitle }
                            ?: "Stopped. Nothing is being written to the audio path.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (mode == null) LabColors.TextDim else LabColors.Accent,
                    )
                    if (trackInfo != null) {
                        Gap(6)
                        MonoBlock(trackInfo!!)
                    }
                    if (engineError != null) {
                        Gap(6)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusChip(LabStatus.ERROR)
                            HGap()
                            Text(engineError!!, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Gap()
                    ToneMode.entries.forEach { m ->
                        BigActionButton(
                            text = m.label + if (mode == m) "  (running)" else "",
                            subtitle = m.subtitle,
                            onClick = { engine.start(m) },
                        )
                    }
                    Gap()
                    LabButton(
                        "STOP AUDIO",
                        onClick = { engine.stop() },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = mode != null,
                    )
                }

                LabCard("A/V pulse timestamps") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusChip(LabStatus.NOT_TESTED)
                        HGap()
                        Text(
                            "End-to-end latency to the vehicle",
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                    Gap(4)
                    Text(
                        PulseEvent.disclaimer + " Point a camera at the Mirai screen with its " +
                            "speakers audible, run LATENCY CLICK, and measure the flash-to-click " +
                            "distance in the recording. The columns below are the phone-internal " +
                            "reference points for that measurement, nothing more.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = LabColors.TextDim,
                    )
                    Gap(8)
                    if (pulses.isEmpty()) {
                        Text(
                            "No pulses yet. Run LATENCY CLICK or A/V SYNC PULSE.",
                            color = LabColors.TextDim,
                        )
                    } else {
                        MonoBlock(
                            buildString {
                                append("  #  frame     wr->out   wr->draw  draw-out  source\n")
                                pulses.forEach { p ->
                                    append(String.format(Locale.US, "%3d", p.index))
                                    append("  ")
                                    append(String.format(Locale.US, "%8d", p.clickFrame))
                                    append(" ").append(ms(p.writeToAudioOutMs))
                                    append(" ").append(ms(p.writeToDrawMs))
                                    append(" ").append(ms(p.drawMinusAudioOutMs))
                                    append("  ").append(p.timestampSource)
                                    append('\n')
                                }
                            },
                        )
                        Gap(6)
                        Text(
                            "wr->out: PCM write to the estimated moment the click leaves the audio " +
                                "HAL. wr->draw: PCM write to the frame carrying the flash. " +
                                "draw-out: negative means the flash was drawn before the click was " +
                                "audible. All in milliseconds, all inside this phone.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = LabColors.TextDim,
                        )
                    }
                }

                LabCard("Route changes (live)") {
                    Text(
                        "AudioDeviceCallback. A car connecting or disconnecting should appear here " +
                            "within a second.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = LabColors.TextDim,
                    )
                    Gap(6)
                    if (routeChanges.isEmpty()) {
                        Text("No callbacks received yet.", color = LabColors.TextDim)
                    } else {
                        MonoBlock(routeChanges.joinToString("\n"))
                    }
                }

                LabCard("Output endpoints (" + outputs.size + ")") {
                    if (outputs.isEmpty()) {
                        Text("None enumerated.", color = LabColors.TextDim)
                    } else {
                        MonoBlock(outputs.joinToString("\n"))
                    }
                }

                LabCard("Tester markers") {
                    Text(
                        "Record what you actually heard. These land in the evidence file with a " +
                            "timestamp next to the machine observations.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = LabColors.TextDim,
                    )
                    Gap(8)
                    val markers = listOf(
                        "Audible in the vehicle",
                        "NOT audible in the vehicle",
                        "Left channel correct in the vehicle",
                        "Right channel correct in the vehicle",
                        "Channels swapped in the vehicle",
                        "Audio dropout heard",
                        "Head unit blanked the video",
                    )
                    markers.forEach { text ->
                        LabButton(
                            text,
                            onClick = {
                                SessionLogger.marker(
                                    text + " [mode=" + (mode?.name ?: "none") +
                                        ", route=" + (routed ?: "unknown") + "]",
                                    LabCategory.AUDIO,
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Gap(4)
                    }
                }

                LabCard("Probe findings") {
                    if (probeObs.isEmpty()) {
                        Text("Running AudioRouteProbe...", color = LabColors.TextDim)
                    } else {
                        probeObs.forEach { ObservationRow(it) }
                    }
                }

                Gap(24)
            }
        }

        // Full-bleed marker. Drawn over the whole window including the title bar so a camera
        // filming the Mirai screen sees an unambiguous frame-level transition.
        val cue = flash
        if (cue != null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.White),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "#" + cue.index,
                        color = Color.Black,
                        fontSize = 120.sp,
                        fontWeight = FontWeight.Black,
                    )
                    Text(
                        cue.mode.label,
                        color = Color.Black,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

/**
 * The huge which-channel-now label. The tester is looking at a phone propped in a car while
 * listening to door speakers, so this has to be readable from the passenger seat.
 */
@Composable
private fun ChannelCallout(channel: StereoChannel) {
    val text = when (channel) {
        StereoChannel.LEFT -> "LEFT"
        StereoChannel.RIGHT -> "RIGHT"
        StereoChannel.BOTH -> "BOTH"
        StereoChannel.NONE -> "..."
    }
    val tint = when (channel) {
        StereoChannel.LEFT -> LabColors.Observed
        StereoChannel.RIGHT -> LabColors.Inferred
        else -> LabColors.TextDim
    }
    LabCard {
        Text(
            "You should now hear ONLY:",
            style = MaterialTheme.typography.bodyLarge,
            color = LabColors.TextDim,
        )
        Text(
            text,
            color = tint,
            fontSize = 96.sp,
            fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "880 Hz on the left, 588 Hz on the right, 2 s each. If the vehicle plays the wrong " +
                "side, the channel mapping of the link is inverted - mark it below.",
            style = MaterialTheme.typography.bodyMedium,
            color = LabColors.TextDim,
        )
    }
}
