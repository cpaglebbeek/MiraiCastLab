package nl.icthorse.miraicastlab.scene

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import nl.icthorse.miraicastlab.core.LabCategory
import nl.icthorse.miraicastlab.core.LabStatus
import nl.icthorse.miraicastlab.core.SessionLogger

/**
 * The patterns the diagnostic scene can show.
 *
 * Each one isolates a different failure mode of the link. The tester cycles them with one button
 * while the receiver keeps the same session, so a difference between patterns is attributable to
 * the picture content and not to a reconnect.
 */
enum class ScenePattern(val label: String, val purpose: String) {
    DIAGNOSTIC("DIAGNOSTIC", "Everything at once: pacing, timing, colour, scaling and touch."),
    MOTION("MOTION", "Large object with a long trail: judder and dropped frames."),
    STRIPES("STRIPES", "1-pixel stripe fields: rescaling, sharpening and interlacing."),
    COLOUR("COLOUR", "Primaries plus grey ramp with near-black/near-white steps: level clipping."),
    LATENCY("LATENCY", "High-contrast single-frame flashes for camera-based latency measurement."),
    GRID("GRID", "Alignment grid with 5%/10% safe areas: overscan and cropping."),
    ;

    companion object {
        val ORDER: List<ScenePattern> = values().toList()
    }
}

/**
 * Rendering statistics published by the renderer at ~2 Hz.
 *
 * Deliberately a snapshot rather than a set of individual flows: the control panel needs a
 * consistent set of numbers, and one object per half second costs nothing while per-field state
 * updates at 60 Hz would cost a recomposition per frame.
 */
data class SceneStats(
    val fps: Float = 0f,
    val avgFrameMs: Float = 0f,
    val p99FrameMs: Float = 0f,
    val maxFrameMs: Float = 0f,
    val framesRendered: Long = 0L,
    val framesInStatsWindow: Long = 0L,
    val longFrames: Long = 0L,
    val surfaceWidthPx: Int = 0,
    val surfaceHeightPx: Int = 0,
    val displayId: Int = -1,
    val displayName: String = "",
    val refreshHz: Float = 0f,
    val lastTouch: String = "none",
    val touchCount: Long = 0L,
)

/**
 * Process-wide control state for the diagnostic scene (spec section 10, "phone-side control mode").
 *
 * Why a singleton and not a ViewModel: the scene can be rendered by
 * [nl.icthorse.miraicastlab.scene.PresentationHostActivity] on a secondary display while the
 * controls are operated in [MainActivity] on the phone. Those are two Activities, two Compose
 * trees and two lifecycles; the only thing they share is the process. A ViewModel scoped to either
 * one would leave the other rendering from stale state.
 *
 * Every mutator logs, because spec section 10 requires every user action to be timestamped and the
 * report has to be able to say "the pattern changed at 14:22:03, the picture went black at 14:22:07".
 */
object SceneState {

    private val _playing = MutableStateFlow(true)
    val playing: StateFlow<Boolean> = _playing.asStateFlow()

    private val _speed = MutableStateFlow(1f)
    val speed: StateFlow<Float> = _speed.asStateFlow()

    private val _pattern = MutableStateFlow(ScenePattern.DIAGNOSTIC)
    val pattern: StateFlow<ScenePattern> = _pattern.asStateFlow()

    private val _showGrid = MutableStateFlow(false)
    val showGrid: StateFlow<Boolean> = _showGrid.asStateFlow()

    private val _inputCapture = MutableStateFlow(false)
    val inputCapture: StateFlow<Boolean> = _inputCapture.asStateFlow()

    /** elapsedRealtimeNanos of the last audio pulse trigger; 0 means "never". */
    private val _audioPulseAt = MutableStateFlow(0L)
    val audioPulseAt: StateFlow<Long> = _audioPulseAt.asStateFlow()

    /** elapsedRealtimeNanos when the latency flash sequence started; 0 means "idle". */
    private val _latencyStartedAt = MutableStateFlow(0L)
    val latencyStartedAt: StateFlow<Long> = _latencyStartedAt.asStateFlow()

    /** Bumped to tell every renderer to clear its frame statistics. */
    private val _statsEpoch = MutableStateFlow(0)
    val statsEpoch: StateFlow<Int> = _statsEpoch.asStateFlow()

    /** Live statistics of the most recently drawn frame, published by the renderer. */
    private val _stats = MutableStateFlow(SceneStats())
    val stats: StateFlow<SceneStats> = _stats.asStateFlow()

    /** Display ids that currently have a live renderer attached. */
    private val _renderers = MutableStateFlow<Set<Int>>(emptySet())
    val renderers: StateFlow<Set<Int>> = _renderers.asStateFlow()

    /** Phone-side chrome: whether the control panel is expanded over the scene. */
    private val _controlsExpanded = MutableStateFlow(true)
    val controlsExpanded: StateFlow<Boolean> = _controlsExpanded.asStateFlow()

    /** Phone-side chrome: hide the lab title bar so the mirrored picture is the pattern alone. */
    private val _fullscreen = MutableStateFlow(false)
    val fullscreen: StateFlow<Boolean> = _fullscreen.asStateFlow()

    /** How many flashes the latency sequence emits, one per second starting at t+1s. */
    const val LATENCY_FLASHES = 5

    // ------------------------------------------------------------------ controls

    fun togglePlay() {
        val next = !_playing.value
        _playing.value = next
        action("play_pause", "playing" to next.toString())
    }

    fun setSpeed(value: Float) {
        val clamped = value.coerceIn(0.25f, 4f)
        if (clamped == _speed.value) return
        _speed.value = clamped
        action("speed", "speed" to clamped.toString())
    }

    fun nextPattern() {
        val order = ScenePattern.ORDER
        val next = order[(order.indexOf(_pattern.value) + 1) % order.size]
        _pattern.value = next
        action("next_pattern", "pattern" to next.name, "purpose" to next.purpose)
    }

    fun setPattern(p: ScenePattern) {
        if (p == _pattern.value) return
        _pattern.value = p
        action("set_pattern", "pattern" to p.name, "purpose" to p.purpose)
    }

    fun toggleGrid() {
        val next = !_showGrid.value
        _showGrid.value = next
        action("grid", "showGrid" to next.toString())
    }

    /**
     * Flips the pulse marker. The audio module is not required to observe this; the scene renders a
     * full-edge flash and logs the exact nanosecond, which is enough to line a microphone recording
     * up against a video of the head-unit screen afterwards.
     */
    fun triggerAudioPulse() {
        val at = SystemClock.elapsedRealtimeNanos()
        _audioPulseAt.value = at
        action("audio_pulse", "elapsedRealtimeNanos" to at.toString())
    }

    fun startLatencySequence() {
        if (_latencyStartedAt.value != 0L) return
        val at = SystemClock.elapsedRealtimeNanos()
        _latencyStartedAt.value = at
        action(
            "latency_sequence_start",
            "elapsedRealtimeNanos" to at.toString(),
            "flashes" to LATENCY_FLASHES.toString(),
            "intervalMs" to "1000",
        )
    }

    /** Called by the renderer once the last flash has been drawn. */
    internal fun endLatencySequence() {
        if (_latencyStartedAt.value == 0L) return
        _latencyStartedAt.value = 0L
        SessionLogger.log(
            LabCategory.DISPLAY,
            "latency_sequence_end",
            LabStatus.OBSERVED,
            mapOf("elapsedRealtimeNanos" to SystemClock.elapsedRealtimeNanos().toString()),
        )
    }

    fun resetStats() {
        _statsEpoch.value = _statsEpoch.value + 1
        action("reset_frame_stats", "epoch" to _statsEpoch.value.toString())
    }

    fun toggleInputCapture() {
        val next = !_inputCapture.value
        _inputCapture.value = next
        action("input_capture", "capturing" to next.toString())
    }

    fun setControlsExpanded(expanded: Boolean) {
        if (expanded == _controlsExpanded.value) return
        _controlsExpanded.value = expanded
        action("controls_expanded", "expanded" to expanded.toString())
    }

    fun setFullscreen(full: Boolean) {
        if (full == _fullscreen.value) return
        _fullscreen.value = full
        action("scene_fullscreen", "fullscreen" to full.toString())
    }

    /**
     * A tester-entered note. Free text only: this is an observation of what the tester sees, which
     * is the only kind of vehicle-related evidence this app is permitted to record.
     */
    fun addMarker(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        SessionLogger.marker(trimmed, LabCategory.MOTION_STATE)
        action("manual_marker", "marker" to trimmed)
    }

    // ------------------------------------------------------------------ renderer side

    internal fun publishStats(stats: SceneStats) {
        _stats.value = stats
    }

    internal fun attachRenderer(displayId: Int) {
        _renderers.value = _renderers.value + displayId
    }

    internal fun detachRenderer(displayId: Int) {
        _renderers.value = _renderers.value - displayId
    }

    // ------------------------------------------------------------------ logging

    /**
     * One shape for every control action so the report can filter on `event = scene_control`.
     * elapsedRealtimeNanos is added on top of the logger's own millisecond stamp because the
     * latency work needs sub-millisecond ordering between a control action and a rendered frame.
     */
    private fun action(name: String, vararg details: Pair<String, String>) {
        SessionLogger.log(
            LabCategory.USER,
            "scene_control",
            LabStatus.OBSERVED,
            buildMap {
                put("action", name)
                put("elapsedRealtimeNanos", SystemClock.elapsedRealtimeNanos().toString())
                details.forEach { (k, v) -> put(k, v) }
            },
        )
    }
}
