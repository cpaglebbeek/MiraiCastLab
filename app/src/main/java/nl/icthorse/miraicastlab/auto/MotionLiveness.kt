package nl.icthorse.miraicastlab.auto

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The liveness signal for the motion-state experiment (spec section 12, layer A).
 *
 * Two counters, kept apart on purpose:
 *
 * - [appFrames] is incremented by the motion-state screen from `withFrameNanos`, so it counts frames
 *   the platform actually delivered to this process. It is first-hand evidence that the app is still
 *   rendering while the head unit does whatever it does.
 * - [sceneFrames] is null until something publishes to it. The diagnostic scene lives in another
 *   module with its own lifecycle; when it is not running there is no number to report, and the
 *   recorder writes "scene not running" instead of a fabricated count. Publishing is a one-line
 *   opt-in for whoever wires the scene up later.
 */
internal object MotionLiveness {

    private val _appFrames = MutableStateFlow(0L)

    /** Frames delivered to this process since the last [reset]. */
    val appFrames: StateFlow<Long> = _appFrames.asStateFlow()

    private val _sceneFrames = MutableStateFlow<Long?>(null)

    /** Frame count published by the diagnostic scene, or null when no scene is publishing. */
    val sceneFrames: StateFlow<Long?> = _sceneFrames.asStateFlow()

    fun onFrame() {
        _appFrames.value = _appFrames.value + 1
    }

    fun reset() {
        _appFrames.value = 0L
    }

    /** Called by a running scene to make its own frame count observable here. */
    fun publishSceneFrame(count: Long) {
        _sceneFrames.value = count
    }

    /** Called when the scene stops, so the recorder goes back to "scene not running". */
    fun sceneStopped() {
        _sceneFrames.value = null
    }

    /** Text form used in the timeline and in the evidence log. Never a made-up number. */
    fun sceneFramesText(): String = _sceneFrames.value?.toString() ?: "scene not running"
}
