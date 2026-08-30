package nl.icthorse.miraicastlab.projection

import android.Manifest
import android.app.Activity
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import nl.icthorse.miraicastlab.core.LabCategory
import nl.icthorse.miraicastlab.core.LabPermissions
import nl.icthorse.miraicastlab.core.LabStatus
import nl.icthorse.miraicastlab.core.SessionLogger
import nl.icthorse.miraicastlab.ui.BigActionButton
import nl.icthorse.miraicastlab.ui.Dot
import nl.icthorse.miraicastlab.ui.Gap
import nl.icthorse.miraicastlab.ui.HGap
import nl.icthorse.miraicastlab.ui.LabButton
import nl.icthorse.miraicastlab.ui.LabCard
import nl.icthorse.miraicastlab.ui.LabColors
import nl.icthorse.miraicastlab.ui.LabScaffold
import nl.icthorse.miraicastlab.ui.MonoBlock
import nl.icthorse.miraicastlab.ui.StatRow
import nl.icthorse.miraicastlab.ui.StatusChip
import nl.icthorse.miraicastlab.ui.VerdictBanner
import nl.icthorse.miraicastlab.ui.statusColor

/**
 * The MediaProjection experiment (spec section 2.4).
 *
 * The tester is told what will be captured before the consent dialog appears, because this is the
 * one screen in the lab that can see everything on their phone. Nothing is written except an
 * optional local H.264 clip in app-private storage, and the app holds no INTERNET permission, so
 * nothing can leave the device even by accident.
 *
 * Leaving this screen tears the session down. That is deliberate: a MediaProjection surviving in
 * the background is both a privacy problem and a guaranteed crash on the next start.
 */
@Composable
fun MediaProjectionScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val engine = remember { CaptureEngine(context) }
    val ui by engine.state.collectAsState()

    var notificationsGranted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                LabPermissions.isGranted(context, Manifest.permission.POST_NOTIFICATIONS),
        )
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        notificationsGranted = granted
        SessionLogger.log(
            LabCategory.MEDIAPROJECTION,
            "projection.post_notifications_result",
            LabStatus.OBSERVED,
            mapOf("granted" to granted.toString()),
        )
    }

    val captureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            scope.launch { engine.start(result.resultCode, data) }
        } else {
            // A refused dialog says nothing about the capability, only about this run.
            SessionLogger.log(
                LabCategory.MEDIAPROJECTION,
                "projection.consent_refused",
                LabStatus.NOT_TESTED,
                mapOf("resultCode" to result.resultCode.toString()),
            )
        }
    }

    // Rigorous teardown: navigating away, process backgrounding into disposal, or a recomposition
    // that drops this screen must all release the projection.
    DisposableEffect(engine) {
        onDispose {
            engine.stop("screen disposed")
            SessionLogger.log(
                LabCategory.MEDIAPROJECTION,
                "projection.screen_disposed",
                LabStatus.CONFIRMED,
            )
        }
    }

    LabScaffold(
        title = "MediaProjection test",
        subtitle = "What this phone can capture and encode, locally",
        onBack = onBack,
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            val verdict = verdictFor(ui)
            VerdictBanner(verdict.first, verdict.second, ui.message)

            LabCard("Before you grant consent") {
                Text(
                    "Screen capture mirrors your entire display into this app: every notification, " +
                        "message and password field that is on screen while it runs.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Gap()
                Text(
                    "What this app does with it: counts frames and reads their geometry. Pixels are " +
                        "never copied, stored or inspected. The optional 5 second H.264 clip is the " +
                        "only thing written to disk, into app-private storage, for adb pull.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LabColors.TextDim,
                )
                Gap()
                Text(
                    "This build has no INTERNET permission, so nothing can be uploaded. Windows " +
                        "marked FLAG_SECURE are blanked by Android itself.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LabColors.TextDim,
                )
            }

            if (!notificationsGranted) {
                LabCard("Notification permission") {
                    Text(
                        "Android 13+ shows the mandatory capture notification only when " +
                            "POST_NOTIFICATIONS is granted. Capture may still start without it, but " +
                            "the tester loses the visible reminder that the screen is being captured.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = LabColors.TextDim,
                    )
                    Gap()
                    LabButton(
                        text = "GRANT NOTIFICATION PERMISSION",
                        onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Column(Modifier.padding(horizontal = 12.dp)) {
                BigActionButton(
                    text = "REQUEST SCREEN CAPTURE PERMISSION",
                    subtitle = "Opens the Android consent dialog",
                    enabled = !ui.isCapturing && ui.phase != CaptureEngine.Phase.STARTING,
                    onClick = {
                        val intent = engine.screenCaptureIntent()
                        if (intent == null) {
                            SessionLogger.log(
                                LabCategory.MEDIAPROJECTION,
                                "projection.consent_intent_unavailable",
                                LabStatus.UNSUPPORTED,
                                mapOf("reason" to "no MediaProjectionManager"),
                            )
                        } else {
                            SessionLogger.log(
                                LabCategory.MEDIAPROJECTION,
                                "projection.consent_requested",
                                LabStatus.OBSERVED,
                            )
                            captureLauncher.launch(intent)
                        }
                    },
                )
            }

            LabCard("Live capture") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Dot(if (ui.isCapturing) LabColors.Confirmed else LabColors.NotTested, 12)
                    HGap()
                    Text(
                        if (ui.isCapturing) "VirtualDisplay -> ImageReader active" else "No active capture",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                Gap()
                StatRow(
                    String.format("%.1f", ui.fps) to "fps (2 s window)",
                    ui.frames.toString() to "frames",
                    ui.capturedResolution to "captured",
                    (if (ui.firstFrameMs < 0) "-" else ui.firstFrameMs.toString()) to "ms to 1st frame",
                )
                Gap()
                StatRow(
                    ui.lateFrames.toString() to "late",
                    ui.droppedFrames.toString() to "dropped",
                    (ui.elapsedMs / 1000).toString() to "s elapsed",
                )
                Gap()
                MonoBlock(captureDetail(ui))
            }

            Column(Modifier.padding(horizontal = 12.dp)) {
                BigActionButton(
                    text = if (ui.encodeRunning) {
                        "ENCODING... " + ui.encodeSecondsLeft + " s"
                    } else {
                        "ENCODE 5 s H.264 TEST CLIP"
                    },
                    subtitle = "Feeds the VirtualDisplay into MediaCodec, writes a local .h264 file",
                    enabled = ui.isCapturing && !ui.encodeRunning,
                    onClick = { scope.launch { engine.encodeClip() } },
                )
                Gap()
                Row(Modifier.fillMaxWidth()) {
                    LabButton(
                        text = "CHECK AUDIO CAPTURE",
                        enabled = ui.isCapturing && !ui.encodeRunning,
                        onClick = { scope.launch { engine.probeAudioPlaybackCapture() } },
                        modifier = Modifier.weight(1f),
                    )
                    HGap(12)
                    LabButton(
                        text = "STOP CAPTURE",
                        enabled = ui.isCapturing,
                        onClick = { engine.stop("tester pressed stop") },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            ui.encode?.let { result ->
                LabCard("H.264 encode result") {
                    MonoBlock(result.toReport())
                    Gap()
                    Text(
                        "Requested bitrate is a hint to the encoder; the measured value is what it " +
                            "actually produced from this screen content.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = LabColors.TextDim,
                    )
                }
            }

            ui.audioStatus?.let { status ->
                LabCard("Audio playback capture") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusChip(status)
                        HGap()
                        Text(
                            "AudioPlaybackCaptureConfiguration",
                            style = MaterialTheme.typography.bodyLarge,
                            color = statusColor(status),
                        )
                    }
                    Gap()
                    Text(
                        ui.audioDetail ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = LabColors.TextDim,
                    )
                    Gap()
                    Text(
                        "The AudioRecord is constructed and immediately released. Recording is never " +
                            "started, so no audio frame exists in this process.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = LabColors.TextDim,
                    )
                }
            }

            LabCard("What this proves, and what it does not") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusChip(LabStatus.NOT_TESTED)
                    HGap()
                    Text("projection.sink_receives", style = MaterialTheme.typography.bodyLarge)
                }
                Gap()
                Text(
                    "A successful capture here proves what the phone can mirror and encode on its " +
                        "own. It is not evidence about Miracast: what the Toyota Mirai head unit " +
                        "receives, accepts or displays is negotiated by the sink and is not " +
                        "observable from inside this app. That stays NOT_TESTED until it is " +
                        "measured at the head unit.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LabColors.TextDim,
                )
            }

            ui.error?.let { error ->
                LabCard("Error") {
                    Text(error, style = MaterialTheme.typography.bodyLarge, color = LabColors.Error)
                }
            }
        }
    }
}

/** Banner grade. Nothing here is allowed to claim more than the counters support. */
private fun verdictFor(ui: CaptureEngine.UiState): Pair<LabStatus, String> = when (ui.phase) {
    CaptureEngine.Phase.IDLE -> LabStatus.NOT_TESTED to "No capture session has been requested"
    CaptureEngine.Phase.STARTING -> LabStatus.NOT_TESTED to "Starting capture"
    CaptureEngine.Phase.CAPTURING, CaptureEngine.Phase.ENCODING ->
        if (ui.frames > 0) {
            LabStatus.OBSERVED to "This phone is capturing its own display"
        } else {
            LabStatus.NOT_TESTED to "Session created, waiting for the first frame"
        }

    CaptureEngine.Phase.STOPPING -> LabStatus.OBSERVED to "Releasing the capture pipeline"
    CaptureEngine.Phase.STOPPED ->
        if (ui.frames > 0) {
            LabStatus.OBSERVED to "Capture ran and was released cleanly"
        } else {
            LabStatus.NOT_TESTED to "Capture stopped without a single frame"
        }

    CaptureEngine.Phase.FAILED -> LabStatus.ERROR to "The capture pipeline failed to start"
}

/** Raw geometry evidence, the numbers a report reader can check the fps figure against. */
private fun captureDetail(ui: CaptureEngine.UiState): String = buildString {
    val t = ui.target
    appendLine("source display     " + (t?.sourceResolution ?: "-") + "  @" + (t?.densityDpi ?: 0) + " dpi")
    appendLine("source read via    " + (t?.source ?: "-"))
    appendLine("virtual display    " + (t?.resolution ?: "-") + "  scale " +
        String.format("%.3f", t?.scale ?: 0f))
    appendLine("image format       RGBA_8888 (ImageReader, 3 buffers)")
    appendLine("captured size      " + ui.capturedResolution)
    appendLine("pixel stride       " + ui.pixelStride)
    appendLine("row stride         " + ui.rowStride + "  (" + ui.rowPaddingBytes + " padding bytes/row)")
    appendLine("frames             " + ui.frames + "  late " + ui.lateFrames + "  dropped " + ui.droppedFrames)
    appendLine("first frame after  " + (if (ui.firstFrameMs < 0) "-" else ui.firstFrameMs.toString() + " ms"))
    if (ui.stoppedByPlatform) {
        appendLine("stopped by         MediaProjection.Callback#onStop (platform or user)")
    }
}
