package nl.icthorse.miraicastlab.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRouting
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import nl.icthorse.miraicastlab.core.LabCategory
import nl.icthorse.miraicastlab.core.LabStatus
import nl.icthorse.miraicastlab.core.SessionLogger
import java.util.Locale
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

// ---------------------------------------------------------------------------------------------
// Synthesis constants. Everything is generated in Kotlin: the app ships no audio assets, so no
// third-party or copyrighted material can end up in a test recording (spec sections 4 and 7).
// ---------------------------------------------------------------------------------------------

/** Every mode runs at one rate so a click's frame index is directly convertible to milliseconds. */
internal const val SR = 48000

/** 5 ms blocks. Small enough that the write timestamp of a click is tight, large enough not to underrun. */
internal const val BLOCK_FRAMES = 240

private const val TWO_PI = 2.0 * PI

/** -2.5 dBFS. Headroom on purpose: a clipped click is a smeared click, and vehicle amps colour hot signals. */
private const val PEAK = 0.75 * 32767.0

/** Clicks start only after the track has been streaming for 0.5 s; AudioTrack.getTimestamp() is junk before that. */
private const val WARMUP_FRAMES = 24_000L

private const val MONO_PERIOD = 48_000L      // 1 s beep cycle
private const val MONO_ON = 19_200L          // 400 ms on
private const val STEREO_HALF = 96_000L      // 2 s per channel
private const val STEREO_PERIOD = STEREO_HALF * 2

/** Which channel the tester should be hearing right now (drives the huge on-screen label). */
enum class StereoChannel { NONE, LEFT, RIGHT, BOTH }

/**
 * The six audio test modes of spec section 2.5.
 *
 * @param intervalFrames spacing of transient events; 0 for continuous modes.
 * @param burstFrames    length of one transient event.
 * @param flashMs        how long the full-bleed visual marker stays on.
 * @param lowLatency     request PERFORMANCE_MODE_LOW_LATENCY; only sensible for the transient modes.
 */
enum class ToneMode(
    val label: String,
    val subtitle: String,
    val intervalFrames: Long,
    val burstFrames: Long,
    val flashMs: Long,
    val lowLatency: Boolean,
) {
    SILENCE(
        "SILENCE",
        "Digital silence, route held open",
        0L, 0L, 0L, false,
    ),
    MONO_TONE(
        "MONO TONE",
        "1 kHz beep, 400 ms on / 600 ms off, identical both channels",
        0L, 0L, 0L, false,
    ),
    STEREO_IDENTIFY(
        "STEREO L/R IDENTIFY",
        "880 Hz left 2 s, then 588 Hz right 2 s",
        0L, 0L, 0L, false,
    ),
    CONTINUOUS_TONE(
        "CONTINUOUS TONE",
        "Unbroken 440 Hz, both channels - dropout and glitch hunting",
        0L, 0L, 0L, false,
    ),
    LATENCY_CLICK(
        "LATENCY CLICK",
        "4 ms click every 2 s + instant screen flash",
        96_000L, 192L, 90L, true,
    ),
    AV_SYNC_PULSE(
        "A/V SYNC PULSE",
        "40 ms blip every 3 s, flash delayed to the estimated audio output moment",
        144_000L, 1_920L, 220L, true,
    );

    /** True when the mode emits transients that carry a timestamped visual marker. */
    val hasPulses: Boolean get() = intervalFrames > 0L
}

/** The screen paints a full-bleed high-contrast frame while this cue is non-null. */
data class FlashCue(
    val index: Int,
    val mode: ToneMode,
    val onRealtimeNanos: Long,
    val onMonotonicNanos: Long,
)

/**
 * One transient event with every timestamp we can honestly obtain.
 *
 * Time bases are deliberately kept separate and both recorded:
 *  - monotonic (System.nanoTime / CLOCK_MONOTONIC) is the base of AudioTimestamp.nanoTime and of
 *    the Compose frame clock, so it is the only base in which the deltas below are meaningful;
 *  - elapsedRealtimeNanos is the base SessionLogger uses, so an external recording can be lined up
 *    with the rest of the evidence file.
 *
 * NONE of these numbers is end-to-end latency to the vehicle. They describe what happened inside
 * this phone. See [PulseEvent.disclaimer].
 */
data class PulseEvent(
    val index: Int,
    val mode: ToneMode,
    val clickFrame: Long,
    val writeMonotonicNanos: Long,
    val writeRealtimeNanos: Long,
    /** Estimated moment the click leaves the audio HAL, from AudioTrack.getTimestamp(). */
    val audioOutMonotonicNanos: Long?,
    val timestampSource: String,
    /** Compose frame clock time of the first frame carrying the flash; filled in by the UI. */
    val visualFrameNanos: Long? = null,
) {
    val writeToAudioOutMs: Double?
        get() = audioOutMonotonicNanos?.let { (it - writeMonotonicNanos) / 1_000_000.0 }

    val writeToDrawMs: Double?
        get() = visualFrameNanos?.let { (it - writeMonotonicNanos) / 1_000_000.0 }

    /** Negative: the flash was drawn before the click was audible. Positive: after. */
    val drawMinusAudioOutMs: Double?
        get() = if (visualFrameNanos != null && audioOutMonotonicNanos != null) {
            (visualFrameNanos - audioOutMonotonicNanos) / 1_000_000.0
        } else {
            null
        }

    companion object {
        const val disclaimer =
            "Internal timestamps only: PCM write, AudioTrack.getTimestamp() and the Compose frame " +
                "clock. End-to-end latency to the head unit is NOT_TESTED without an external recording."
    }
}

private fun fmt(v: Double?): String =
    if (v == null) "-" else String.format(Locale.US, "%+.1f", v)

/**
 * Streaming tone synthesiser for the audio test screen.
 *
 * One AudioTrack, one dedicated urgent-audio thread, no assets. The engine owns every resource it
 * opens and [release] must be called from the screen's DisposableEffect: a leaked AudioTrack keeps
 * the audio route to the vehicle open after the tester has left the screen, which would corrupt
 * every later route observation.
 */
class ToneEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val handler = Handler(Looper.getMainLooper())
    private val lock = Any()

    private val _mode = MutableStateFlow<ToneMode?>(null)
    val mode: StateFlow<ToneMode?> = _mode.asStateFlow()

    private val _stereoChannel = MutableStateFlow(StereoChannel.NONE)
    val stereoChannel: StateFlow<StereoChannel> = _stereoChannel.asStateFlow()

    private val _flash = MutableStateFlow<FlashCue?>(null)
    val flash: StateFlow<FlashCue?> = _flash.asStateFlow()

    private val _pulses = MutableStateFlow<List<PulseEvent>>(emptyList())

    /** Newest first, capped; this is a live view, the evidence file keeps them all. */
    val pulses: StateFlow<List<PulseEvent>> = _pulses.asStateFlow()

    private val _routedDevice = MutableStateFlow<String?>(null)

    /** Device AudioTrack.getRoutedDevice() reports while we are actually playing. Hard evidence. */
    val routedDevice: StateFlow<String?> = _routedDevice.asStateFlow()

    private val _trackInfo = MutableStateFlow<String?>(null)
    val trackInfo: StateFlow<String?> = _trackInfo.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    @Volatile
    private var running = false

    @Volatile
    private var worker: Thread? = null

    private var pulseCounter = 0

    // -------------------------------------------------------------------------------- lifecycle

    /** Stops whatever is playing and starts [next]. Safe to call repeatedly from the UI thread. */
    fun start(next: ToneMode) {
        stop()
        _error.value = null
        _mode.value = next
        _stereoChannel.value = when (next) {
            ToneMode.MONO_TONE, ToneMode.CONTINUOUS_TONE -> StereoChannel.BOTH
            else -> StereoChannel.NONE
        }
        SessionLogger.log(
            LabCategory.AUDIO,
            "audio_mode_start",
            LabStatus.OBSERVED,
            mapOf(
                "mode" to next.name,
                "sampleRate" to SR.toString(),
                "channels" to "2",
                "encoding" to "PCM_16BIT",
            ),
        )
        running = true
        val t = Thread({ playbackLoop(next) }, "MiraiCastLab-tone")
        t.priority = Thread.MAX_PRIORITY
        worker = t
        t.start()
    }

    /** Stops playback and releases the AudioTrack. Idempotent. */
    fun stop() {
        val t = synchronized(lock) {
            if (!running && worker == null) return
            running = false
            val w = worker
            worker = null
            w
        }
        runCatching { t?.join(1500) }
        _mode.value?.let { m ->
            SessionLogger.log(
                LabCategory.AUDIO,
                "audio_mode_stop",
                LabStatus.OBSERVED,
                mapOf("mode" to m.name),
            )
        }
        _mode.value = null
        _stereoChannel.value = StereoChannel.NONE
        _flash.value = null
        _trackInfo.value = null
    }

    /** Final teardown: stops audio and cancels the flash coroutines. */
    fun release() {
        stop()
        scope.cancel()
    }

    // ------------------------------------------------------------------------------- audio path

    private fun playbackLoop(m: ToneMode) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        var track: AudioTrack? = null
        var routing: AudioRouting.OnRoutingChangedListener? = null
        try {
            val minBuf = AudioTrack.getMinBufferSize(
                SR,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuf <= 0) {
                fail("AudioTrack.getMinBufferSize returned $minBuf for 48 kHz stereo PCM16")
                return
            }
            // Four blocks of slack over the platform minimum: enough not to underrun on a folded
            // device that is also encoding a Miracast stream, small enough to keep the click tight.
            val bufBytes = maxOf(minBuf, BLOCK_FRAMES * 4 * 4)

            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                // USAGE_MEDIA + capture-by-all: this is the stream that follows the phone to the
                // car, and an opt-in capture policy keeps AudioPlaybackCapture experiments possible.
                .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_ALL)
                .build()

            val fmt = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SR)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build()

            val builder = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(fmt)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufBytes)
            if (m.lowLatency) {
                // Best effort. The platform silently falls back to PERFORMANCE_MODE_NONE when the
                // route cannot do it, which is itself a finding worth having in the log.
                runCatching { builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY) }
            }

            val t = builder.build()
            track = t
            if (t.state != AudioTrack.STATE_INITIALIZED) {
                fail("AudioTrack state=${t.state}, not STATE_INITIALIZED")
                return
            }

            val listener = AudioRouting.OnRoutingChangedListener { router ->
                val dev = runCatching { (router as? AudioTrack)?.routedDevice }.getOrNull()
                val desc = dev?.let { describeAudioDevice(it) } ?: "unknown"
                _routedDevice.value = desc
                SessionLogger.log(
                    LabCategory.AUDIO,
                    "audio_track_routing_changed",
                    LabStatus.OBSERVED,
                    mapOf("routedDevice" to desc, "mode" to m.name),
                )
            }
            routing = listener
            runCatching { t.addOnRoutingChangedListener(listener, handler) }

            t.play()

            val routed = runCatching { t.routedDevice }.getOrNull()
            _routedDevice.value = routed?.let { describeAudioDevice(it) }
            val perf = runCatching { t.performanceMode }.getOrNull()
            _trackInfo.value = buildString {
                append("48000 Hz stereo PCM16, buffer ").append(bufBytes).append(" B")
                append(", session ").append(t.audioSessionId)
                append(", perfMode ").append(perf?.toString() ?: "?")
                runCatching { append(", bufFrames ").append(t.bufferSizeInFrames) }
            }
            SessionLogger.log(
                LabCategory.AUDIO,
                "audio_track_opened",
                LabStatus.CONFIRMED,
                mapOf(
                    "mode" to m.name,
                    "bufferBytes" to bufBytes.toString(),
                    "minBufferBytes" to minBuf.toString(),
                    "bufferFrames" to runCatching { t.bufferSizeInFrames.toString() }.getOrDefault("?"),
                    "sessionId" to t.audioSessionId.toString(),
                    "performanceMode" to (perf?.toString() ?: "unavailable"),
                    "requestedLowLatency" to m.lowLatency.toString(),
                    "routedDevice" to (_routedDevice.value ?: "unknown"),
                ),
            )

            val block = ShortArray(BLOCK_FRAMES * 2)
            var frame = 0L
            while (running) {
                val clickFrame = fillBlock(block, frame, m)
                val preMono = System.nanoTime()
                val written = t.write(block, 0, block.size)
                val postMono = System.nanoTime()
                val postReal = SystemClock.elapsedRealtimeNanos()
                if (written < 0) {
                    fail("AudioTrack.write returned $written")
                    return
                }
                if (clickFrame >= 0L && running) {
                    onClickWritten(t, m, clickFrame, preMono, postMono, postReal)
                }
                frame += BLOCK_FRAMES
                if (m == ToneMode.STEREO_IDENTIFY) {
                    val pos = frame % STEREO_PERIOD
                    _stereoChannel.value =
                        if (pos < STEREO_HALF) StereoChannel.LEFT else StereoChannel.RIGHT
                }
            }
        } catch (t: Throwable) {
            fail(t::class.java.simpleName + ": " + (t.message ?: "no message"))
        } finally {
            val tr = track
            if (tr != null) {
                routing?.let { runCatching { tr.removeOnRoutingChangedListener(it) } }
                runCatching { tr.pause() }
                runCatching { tr.flush() }
                runCatching { tr.stop() }
                runCatching { tr.release() }
            }
            // The last known route deliberately stays in _routedDevice: after stop() it is still
            // the honest answer to "where did the audio go during the test".
        }
    }

    private fun fail(message: String) {
        _error.value = message
        SessionLogger.log(
            LabCategory.AUDIO,
            "audio_track_failure",
            LabStatus.ERROR,
            mapOf("detail" to message),
        )
    }

    // ------------------------------------------------------------------------------- transients

    /**
     * Called immediately after the block containing a click has been handed to the audio HAL.
     *
     * Records the write moment, derives the estimated moment the click actually leaves the HAL,
     * and arms the visual marker. For [ToneMode.LATENCY_CLICK] the flash fires now (spec 2.5:
     * "a large visual marker at the same moment an audio pulse occurs"), which means it visually
     * leads the audible click by roughly the output latency - that lead is exactly what the numbers
     * below quantify. For [ToneMode.AV_SYNC_PULSE] the flash is deliberately delayed to the
     * estimated output moment so a camera recording sees flash and blip together.
     */
    private fun onClickWritten(
        track: AudioTrack,
        m: ToneMode,
        clickFrame: Long,
        preMono: Long,
        postMono: Long,
        postReal: Long,
    ) {
        val index = ++pulseCounter

        var source = "none"
        var audioOut: Long? = null

        val ts = AudioTimestamp()
        val haveTs = runCatching { track.getTimestamp(ts) }.getOrDefault(false)
        if (haveTs && ts.framePosition > 0L) {
            // ts.nanoTime is CLOCK_MONOTONIC, the same base as System.nanoTime().
            audioOut = ts.nanoTime +
                ((clickFrame - ts.framePosition) * 1_000_000_000.0 / SR).toLong()
            source = "AudioTrack.getTimestamp"
        } else {
            val head = runCatching { track.playbackHeadPosition.toLong() and 0xFFFF_FFFFL }
                .getOrNull()
            if (head != null && head > 0L) {
                audioOut = postMono + ((clickFrame - head) * 1_000_000_000.0 / SR).toLong()
                source = "playbackHeadPosition (coarser)"
            }
        }

        val event = PulseEvent(
            index = index,
            mode = m,
            clickFrame = clickFrame,
            writeMonotonicNanos = postMono,
            writeRealtimeNanos = postReal,
            audioOutMonotonicNanos = audioOut,
            timestampSource = source,
        )
        synchronized(lock) {
            _pulses.value = (listOf(event) + _pulses.value).take(40)
        }

        SessionLogger.log(
            LabCategory.AUDIO,
            "av_pulse_written",
            LabStatus.OBSERVED,
            mapOf(
                "pulse" to index.toString(),
                "mode" to m.name,
                "clickFrame" to clickFrame.toString(),
                "writeCallNanos" to (postMono - preMono).toString(),
                "writeMonotonicNanos" to postMono.toString(),
                "writeElapsedRealtimeNanos" to postReal.toString(),
                "audioOutMonotonicNanos" to (audioOut?.toString() ?: "unavailable"),
                "writeToAudioOutMs" to fmt(event.writeToAudioOutMs),
                "timestampSource" to source,
                "disclaimer" to PulseEvent.disclaimer,
            ),
        )

        val compensateMs = if (m == ToneMode.AV_SYNC_PULSE && audioOut != null) {
            ((audioOut - System.nanoTime()) / 1_000_000L).coerceIn(0L, 400L)
        } else {
            0L
        }
        scope.launch {
            if (compensateMs > 0L) delay(compensateMs)
            if (!running) return@launch
            _flash.value = FlashCue(index, m, SystemClock.elapsedRealtimeNanos(), System.nanoTime())
            delay(m.flashMs)
            if (_flash.value?.index == index) _flash.value = null
        }
    }

    /**
     * Called by the UI from withFrameNanos with the Compose frame clock time of the first frame
     * that carries the flash. That is a draw-time stamp, not photon-out: panel composition and
     * vsync still follow, and on the head unit the encoder and the receiver follow after that.
     */
    fun recordVisualFlip(index: Int, frameNanos: Long) {
        var updated: PulseEvent? = null
        synchronized(lock) {
            val list = _pulses.value
            val i = list.indexOfFirst { it.index == index }
            if (i < 0 || list[i].visualFrameNanos != null) return
            val next = list[i].copy(visualFrameNanos = frameNanos)
            updated = next
            _pulses.value = list.toMutableList().also { it[i] = next }
        }
        val e = updated ?: return
        SessionLogger.log(
            LabCategory.AUDIO,
            "av_pulse_drawn",
            LabStatus.OBSERVED,
            mapOf(
                "pulse" to index.toString(),
                "mode" to e.mode.name,
                "frameMonotonicNanos" to frameNanos.toString(),
                "writeToDrawMs" to fmt(e.writeToDrawMs),
                "drawMinusAudioOutMs" to fmt(e.drawMinusAudioOutMs),
                "disclaimer" to PulseEvent.disclaimer,
            ),
        )
    }

    // -------------------------------------------------------------------------------- synthesis

    /** Phase-continuous because every frequency here is an integer number of cycles per second. */
    private fun sine(freq: Int, frame: Long): Double =
        sin(TWO_PI * freq * (frame % SR).toDouble() / SR)

    /** Linear attack/release so a gate edge does not itself sound like a click. */
    private fun ramp(pos: Long, len: Long, rampFrames: Long): Double = when {
        pos < rampFrames -> pos.toDouble() / rampFrames
        pos > len - rampFrames -> ((len - pos).toDouble() / rampFrames).coerceAtLeast(0.0)
        else -> 1.0
    }

    /** Position inside the current transient cycle, or -1 before the warm-up window has passed. */
    private fun clickPos(frame: Long, m: ToneMode): Long =
        if (!m.hasPulses || frame < WARMUP_FRAMES) -1L
        else (frame - WARMUP_FRAMES) % m.intervalFrames

    /**
     * Fills one interleaved stereo block and returns the absolute frame index of a transient that
     * starts inside it, or -1. Absolute frame indices are the currency of the whole latency
     * measurement: they are the only quantity shared with AudioTimestamp.framePosition.
     */
    private fun fillBlock(out: ShortArray, startFrame: Long, m: ToneMode): Long {
        var clickFrame = -1L
        when (m) {
            ToneMode.SILENCE -> out.fill(0)

            ToneMode.MONO_TONE -> for (i in 0 until BLOCK_FRAMES) {
                val f = startFrame + i
                val pos = f % MONO_PERIOD
                val a = if (pos < MONO_ON) {
                    sine(1000, f) * ramp(pos, MONO_ON, 480L) * PEAK
                } else {
                    0.0
                }
                val s = a.toInt().toShort()
                out[i * 2] = s
                out[i * 2 + 1] = s
            }

            ToneMode.STEREO_IDENTIFY -> for (i in 0 until BLOCK_FRAMES) {
                val f = startFrame + i
                val pos = f % STEREO_PERIOD
                if (pos < STEREO_HALF) {
                    val a = sine(880, f) * ramp(pos, STEREO_HALF, 960L) * PEAK
                    out[i * 2] = a.toInt().toShort()
                    out[i * 2 + 1] = 0
                } else {
                    val p = pos - STEREO_HALF
                    val a = sine(588, f) * ramp(p, STEREO_HALF, 960L) * PEAK
                    out[i * 2] = 0
                    out[i * 2 + 1] = a.toInt().toShort()
                }
            }

            ToneMode.CONTINUOUS_TONE -> for (i in 0 until BLOCK_FRAMES) {
                val f = startFrame + i
                // 50 ms fade-in only; after that it never stops, which is the point of this mode.
                val env = if (f < 2400L) f.toDouble() / 2400.0 else 1.0
                val s = (sine(440, f) * env * PEAK).toInt().toShort()
                out[i * 2] = s
                out[i * 2 + 1] = s
            }

            ToneMode.LATENCY_CLICK -> for (i in 0 until BLOCK_FRAMES) {
                val f = startFrame + i
                val pos = clickPos(f, m)
                if (pos == 0L) clickFrame = f
                val a = if (pos in 0 until m.burstFrames) {
                    // Hard-attack 2 kHz burst with a fast exponential decay: transient-sharp, so it
                    // is unambiguous in an external recording's waveform.
                    sine(2000, pos) * exp(-pos.toDouble() / 48.0) * PEAK
                } else {
                    0.0
                }
                val s = a.toInt().toShort()
                out[i * 2] = s
                out[i * 2 + 1] = s
            }

            ToneMode.AV_SYNC_PULSE -> for (i in 0 until BLOCK_FRAMES) {
                val f = startFrame + i
                val pos = clickPos(f, m)
                if (pos == 0L) clickFrame = f
                val a = if (pos in 0 until m.burstFrames) {
                    // 40 ms of 1 kHz: long enough to survive a phone camera's audio compression,
                    // short enough to mark a single video frame.
                    sine(1000, pos) * ramp(pos, m.burstFrames, 48L) * PEAK
                } else {
                    0.0
                }
                val s = a.toInt().toShort()
                out[i * 2] = s
                out[i * 2 + 1] = s
            }
        }
        return clickFrame
    }
}

/** Human label for the AudioManager mode constants that exist on every supported level. */
internal fun describeAudioMode(mode: Int): String = when (mode) {
    AudioManager.MODE_NORMAL -> "MODE_NORMAL"
    AudioManager.MODE_RINGTONE -> "MODE_RINGTONE"
    AudioManager.MODE_IN_CALL -> "MODE_IN_CALL"
    AudioManager.MODE_IN_COMMUNICATION -> "MODE_IN_COMMUNICATION"
    AudioManager.MODE_INVALID -> "MODE_INVALID"
    4 -> "MODE_CALL_SCREENING"
    5 -> "MODE_CALL_REDIRECT"
    6 -> "MODE_COMMUNICATION_REDIRECT"
    else -> "mode_$mode"
}
