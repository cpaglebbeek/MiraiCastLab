package nl.icthorse.miraicastlab.scene

import android.content.Context
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.SystemClock
import android.view.Display
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.delay
import nl.icthorse.miraicastlab.core.LabCategory
import nl.icthorse.miraicastlab.core.LabStatus
import nl.icthorse.miraicastlab.core.Observation
import nl.icthorse.miraicastlab.core.SessionLogger
import nl.icthorse.miraicastlab.ui.LabScaffold

/** How often the Layer A liveness record is written while the scene runs. */
private const val LIVENESS_INTERVAL_MS = 5_000L

/**
 * The diagnostic test pattern (spec section 7) with its phone-side controller (spec section 10)
 * and the Layer A liveness record (spec section 12).
 *
 * The same composable serves two very different hosts:
 *
 * - On the default display it is a lab screen: scene on top, control panel below, back arrow.
 * - Inside [PresentationHostActivity] on a secondary display it is the picture and nothing else.
 *   Chrome on that surface would be chrome on the Toyota's screen, and the controls would be out
 *   of reach anyway. The phone keeps the controls; both render from the same [SceneState].
 *
 * Layer A in one sentence: while this screen is alive it writes, every five seconds, whether the
 * frame counter advanced, at what rate, and whether the display it is drawing on still exists.
 * That continuous record is what later makes "the picture stopped at 14:22:07" a fact instead of
 * a recollection - and it is observation only. Nothing here reads, infers or influences vehicle
 * state; tester-entered vehicle markers belong to the motion-state screen.
 */
@Composable
fun TestPatternScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current.density
    val engine = remember { SceneEngine() }

    val display: Display? = view.display
    val displayId = display?.displayId ?: Display.DEFAULT_DISPLAY
    val isExternal = displayId != Display.DEFAULT_DISPLAY

    // Facts about the surface are pushed into the engine rather than read inside the draw loop:
    // the draw loop must not touch the platform, or its own frame times stop meaning anything.
    SideEffect {
        engine.displayId = displayId
        engine.displayName = display?.name ?: "unknown"
        engine.refreshHz = display?.refreshRate ?: 0f
        engine.orientationLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    }

    // ---------------------------------------------------------------- the frame loop
    LaunchedEffect(engine) {
        val drained = ArrayList<SceneLogEvent>(4)
        while (true) {
            withFrameNanos { engine.onFrame(it) }
            // Logging touches the filesystem, so it happens after the frame callback returns and
            // never inside it. Deferring it is the difference between a frame-time measurement and
            // a measurement of our own logger.
            if (engine.hasPending()) {
                engine.drainInto(drained)
                for (event in drained) {
                    SessionLogger.log(event.category, event.event, LabStatus.OBSERVED, event.details)
                }
                drained.clear()
            }
        }
    }

    // ---------------------------------------------------------------- Layer A liveness record
    LaunchedEffect(engine, displayId) {
        val dm = runCatching {
            context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        }.getOrNull()
        var lastFrames = engine.frameCount
        var lastDraws = engine.drawCount
        var lastStampNanos = SystemClock.elapsedRealtimeNanos()
        while (true) {
            delay(LIVENESS_INTERVAL_MS)
            val frames = engine.frameCount
            val delta = frames - lastFrames
            lastFrames = frames
            val draws = engine.drawCount
            val drawDelta = draws - lastDraws
            lastDraws = draws
            val now = SystemClock.elapsedRealtimeNanos()
            val windowMs = (now - lastStampNanos) / 1_000_000L
            lastStampNanos = now
            // A display that has gone away returns null here; that is evidence the sink dropped,
            // not evidence that the platform lacks the capability.
            val live = runCatching { dm?.getDisplay(displayId) }.getOrNull()
            SessionLogger.log(
                LabCategory.MOTION_STATE,
                "scene_liveness",
                LabStatus.OBSERVED,
                mapOf(
                    "displayId" to displayId.toString(),
                    "rendering" to (delta > 0L).toString(),
                    "frameDelta" to delta.toString(),
                    "drawDelta" to drawDelta.toString(),
                    "drawsTotal" to draws.toString(),
                    "windowMs" to windowMs.toString(),
                    "framesTotal" to frames.toString(),
                    "fps" to SceneEngine.fmt1(engine.fps),
                    "p99FrameMs" to SceneEngine.fmt1(engine.p99FrameMs),
                    "longFrames" to engine.longFrames.toString(),
                    "paused" to (!engine.playing).toString(),
                    "pattern" to engine.pattern.name,
                    "surfacePx" to (engine.surfaceW.toString() + "x" + engine.surfaceH),
                    "displayPresent" to (live != null).toString(),
                    "displayState" to displayStateName(live?.state),
                    "attachedRenderers" to SceneState.renderers.value.sorted().joinToString(","),
                    "elapsedRealtimeNanos" to now.toString(),
                ),
            )
        }
    }

    // ---------------------------------------------------------------- lifecycle bookkeeping
    DisposableEffect(displayId) {
        // A car test is minutes of watching a screen without touching the phone; letting the panel
        // sleep would end the very session being measured.
        view.keepScreenOn = true
        SceneState.attachRenderer(displayId)
        SessionLogger.log(
            LabCategory.DISPLAY,
            "scene_started",
            LabStatus.OBSERVED,
            mapOf(
                "displayId" to displayId.toString(),
                "displayName" to (display?.name ?: "unknown"),
                "refreshHz" to SceneEngine.fmt1(display?.refreshRate ?: 0f),
                "external" to isExternal.toString(),
                "density" to density.toString(),
                // Both are logged so the report can show what the window manager reports in dp
                // next to the pixel size the Canvas was actually handed. On a foldable and on a
                // head unit those two disagree often enough that guessing one from the other is
                // not defensible.
                "configDp" to (configuration.screenWidthDp.toString() + "x" + configuration.screenHeightDp),
                "orientation" to if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait",
            ),
        )
        onDispose {
            view.keepScreenOn = false
            SceneState.detachRenderer(displayId)
            // Everything below was measured on this device during this run, so OBSERVED is the
            // right grade. The refresh rate is the display's own claim, hence CONFIRMED, and it is
            // reported separately from what we actually achieved.
            SessionLogger.logAll(
                listOf(
                    Observation.observed(
                        "scene.render_fps",
                        SceneEngine.fmt1(engine.fps),
                        LabCategory.DISPLAY,
                        "Rolling one-second average at the moment the scene closed, display " + displayId + ".",
                    ),
                    Observation.observed(
                        "scene.frame_time_p99_ms",
                        SceneEngine.fmt1(engine.p99FrameMs),
                        LabCategory.DISPLAY,
                        "99th percentile over " + engine.statsFrames + " frames since the last statistics reset.",
                    ),
                    Observation.observed(
                        "scene.draw_passes",
                        engine.drawCount,
                        LabCategory.DISPLAY,
                        "Completed Canvas draw passes. Divergence from frames_rendered means the picture froze while the loop ran.",
                    ),
                    Observation.observed(
                        "scene.frames_rendered",
                        engine.frameCount,
                        LabCategory.DISPLAY,
                        "Frames this renderer drew. Says nothing about how many the receiver displayed.",
                    ),
                    Observation.observed(
                        "scene.long_frames",
                        engine.longFrames,
                        LabCategory.DISPLAY,
                        "Frames longer than 1.5x the frame budget of " + SceneEngine.fmt1(engine.refreshHz) + " Hz.",
                    ),
                    Observation.observed(
                        "scene.surface_px",
                        engine.surfaceW.toString() + "x" + engine.surfaceH,
                        LabCategory.DISPLAY,
                        "Real pixel size of the surface the pattern was drawn on, display " + displayId + ".",
                    ),
                    Observation.observed(
                        "scene.touch_events",
                        engine.touchCount,
                        LabCategory.INPUT,
                        "Pointer-down events received by the scene surface on display " + displayId + ".",
                    ),
                ),
            )
            SessionLogger.log(
                LabCategory.DISPLAY,
                "scene_stopped",
                LabStatus.OBSERVED,
                mapOf(
                    "displayId" to displayId.toString(),
                    "framesRendered" to engine.frameCount.toString(),
                    "elapsedRealtimeNanos" to SystemClock.elapsedRealtimeNanos().toString(),
                ),
            )
        }
    }

    // ---------------------------------------------------------------- layout
    if (isExternal) {
        // Nothing but the picture: this surface is the artefact under test.
        SceneSurface(engine, Modifier.fillMaxSize())
        return
    }

    val fullscreen by SceneState.fullscreen.collectAsState()
    val stats by SceneState.stats.collectAsState()

    if (fullscreen) {
        // Mirroring the phone means the phone's screen is the source, so the pattern gets the whole
        // panel and the controls shrink to a strip the tester can still reach.
        Box(Modifier.fillMaxSize()) {
            SceneSurface(engine, Modifier.fillMaxSize())
            SceneControls(Modifier.align(Alignment.BottomCenter), compact = true)
        }
        return
    }

    LabScaffold(
        title = "Diagnostic test pattern",
        onBack = onBack,
        subtitle = "display " + displayId + " · " + stats.surfaceWidthPx + "x" + stats.surfaceHeightPx +
            " px · " + SceneEngine.fmt1(stats.fps) + " fps",
    ) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                SceneSurface(engine, Modifier.fillMaxSize())
            }
            SceneControls()
        }
    }
}

/** Display.STATE_* as text; unknown codes are reported as the raw number, never guessed. */
private fun displayStateName(state: Int?): String = when (state) {
    null -> "absent"
    Display.STATE_OFF -> "off"
    Display.STATE_ON -> "on"
    Display.STATE_DOZE -> "doze"
    Display.STATE_DOZE_SUSPEND -> "doze_suspend"
    Display.STATE_ON_SUSPEND -> "on_suspend"
    Display.STATE_VR -> "vr"
    Display.STATE_UNKNOWN -> "unknown"
    else -> state.toString()
}
