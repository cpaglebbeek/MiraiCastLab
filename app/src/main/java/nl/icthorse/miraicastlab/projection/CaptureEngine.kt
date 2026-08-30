package nl.icthorse.miraicastlab.projection

import android.annotation.SuppressLint
import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import nl.icthorse.miraicastlab.core.LabCategory
import nl.icthorse.miraicastlab.core.LabPermissions
import nl.icthorse.miraicastlab.core.LabStatus
import nl.icthorse.miraicastlab.core.Observation
import nl.icthorse.miraicastlab.core.SessionLogger
import java.io.File
import java.util.ArrayDeque
import kotlin.math.max
import kotlin.math.min

/**
 * The MediaProjection experiment itself (spec section 2.4).
 *
 * What it proves: what this phone is able to capture and encode locally, without root. What it
 * cannot prove, and must never be read as proving: anything about what a Miracast sink such as the
 * Toyota head unit receives. That distinction is emitted as an explicit NOT_TESTED observation at
 * the end of every session so the claim cannot drift in the report.
 *
 * Lifecycle order matters and is not negotiable on Android 14+:
 *   consent dialog -> start FGS (type mediaProjection) -> getMediaProjection ->
 *   registerCallback -> createVirtualDisplay.
 * Getting that order wrong throws SecurityException, and a MediaProjection that is not released is
 * a crash on the next start, so every exit path funnels through [stop].
 *
 * All capture work happens on one HandlerThread. The main thread only reads [state].
 */
class CaptureEngine(context: Context) {

    private val app: Context = context.applicationContext

    enum class Phase { IDLE, STARTING, CAPTURING, ENCODING, STOPPING, STOPPED, FAILED }

    data class UiState(
        val phase: Phase = Phase.IDLE,
        val message: String = "Idle. No screen capture has been requested.",
        val target: CaptureTarget? = null,
        val frames: Long = 0,
        val fps: Double = 0.0,
        val capturedWidth: Int = 0,
        val capturedHeight: Int = 0,
        val pixelStride: Int = 0,
        val rowStride: Int = 0,
        val rowPaddingBytes: Int = 0,
        val lateFrames: Long = 0,
        val droppedFrames: Long = 0,
        val firstFrameMs: Long = -1,
        val elapsedMs: Long = 0,
        val encodeRunning: Boolean = false,
        val encodeSecondsLeft: Int = 0,
        val encode: H264Encoder.Result? = null,
        val audioStatus: LabStatus? = null,
        val audioDetail: String? = null,
        val error: String? = null,
        val stoppedByPlatform: Boolean = false,
    ) {
        val isCapturing: Boolean get() = phase == Phase.CAPTURING || phase == Phase.ENCODING
        val capturedResolution: String
            get() = if (capturedWidth <= 0) "-" else capturedWidth.toString() + "x" + capturedHeight
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    // --- owned resources; all touched from the main thread or the capture handler, never both ---
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var encoder: H264Encoder? = null

    @Volatile private var running = false

    // --- counters; written only on the capture handler thread ---
    private var frameCount = 0L
    private var lateCount = 0L
    private var dropCount = 0L
    private var startedAtMs = 0L
    private var firstFrameAtMs = -1L
    private var lastSummaryAtMs = 0L
    private var capturedWidth = 0
    private var capturedHeight = 0
    private var pixelStride = 0
    private var rowStride = 0
    private val frameTimestamps = ArrayDeque<Long>()

    /** The consent Intent, or null when the platform has no MediaProjectionManager at all. */
    fun screenCaptureIntent(): Intent? = try {
        manager()?.createScreenCaptureIntent()
    } catch (t: Throwable) {
        SessionLogger.log(
            LabCategory.MEDIAPROJECTION,
            "projection.create_intent_failed",
            LabStatus.ERROR,
            mapOf("error" to t::class.java.simpleName + ": " + (t.message ?: "")),
        )
        null
    }

    private fun manager(): MediaProjectionManager? =
        app.getSystemService(MediaProjectionManager::class.java)

    // ------------------------------------------------------------------ start

    /**
     * Turns a granted consent result into a running capture.
     *
     * The consent token is single-use: if this fails the tester must press the request button
     * again. That is recorded rather than papered over with a silent retry of the same token.
     */
    suspend fun start(resultCode: Int, data: Intent) {
        if (running) stop("restart requested")

        _state.update {
            UiState(phase = Phase.STARTING, message = "Consent granted. Starting foreground service...")
        }
        SessionLogger.log(
            LabCategory.MEDIAPROJECTION,
            "projection.consent_granted",
            LabStatus.OBSERVED,
            mapOf("resultCode" to resultCode.toString()),
        )

        val mgr = manager()
        if (mgr == null) {
            fail("MediaProjectionManager is absent on this device.", LabStatus.UNSUPPORTED)
            return
        }

        val target = CaptureTarget.measure(app)
        if (!target.isUsable) {
            fail("Could not measure the default display; refusing to guess a capture size.", LabStatus.ERROR)
            return
        }
        _state.update { it.copy(target = target) }
        SessionLogger.log(
            LabCategory.MEDIAPROJECTION,
            "projection.target_measured",
            LabStatus.CONFIRMED,
            target.toLogDetails(),
        )

        // Android 14+ requires the typed foreground service to already run before capture starts.
        ProjectionService.start(app)

        val thread = HandlerThread("miraicast-capture").apply { start() }
        handlerThread = thread
        val h = Handler(thread.looper)
        handler = h

        val proj = try {
            mgr.getMediaProjection(resultCode, data)
        } catch (t: Throwable) {
            fail("getMediaProjection threw: " + t::class.java.simpleName + " " + (t.message ?: ""), LabStatus.ERROR)
            return
        }
        if (proj == null) {
            fail("getMediaProjection returned null for a granted result.", LabStatus.UNSUPPORTED)
            return
        }
        projection = proj

        // Mandatory before createVirtualDisplay on API 34+, and useful evidence on every version:
        // the platform tells us when it revokes capture (screen off, another app, user stop).
        try {
            proj.registerCallback(projectionCallback, h)
        } catch (t: Throwable) {
            fail("registerCallback threw: " + t::class.java.simpleName, LabStatus.ERROR)
            return
        }

        val reader = ImageReader.newInstance(
            target.width,
            target.height,
            PixelFormat.RGBA_8888,
            MAX_IMAGES,
        )
        imageReader = reader
        reader.setOnImageAvailableListener(::onImageAvailable, h)

        resetCounters()
        startedAtMs = SystemClock.elapsedRealtime()

        // The FGS starts asynchronously; on API 34+ createVirtualDisplay throws SecurityException
        // until it is up. Retrying with backoff is legitimate - it is a race, not a denial.
        var display: VirtualDisplay? = null
        var lastError: Throwable? = null
        for (attempt in 0 until CREATE_ATTEMPTS) {
            try {
                display = proj.createVirtualDisplay(
                    VIRTUAL_DISPLAY_NAME,
                    target.width,
                    target.height,
                    target.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.surface,
                    virtualDisplayCallback,
                    h,
                )
                if (display != null) break
            } catch (t: Throwable) {
                lastError = t
                SessionLogger.log(
                    LabCategory.MEDIAPROJECTION,
                    "projection.create_virtual_display_retry",
                    LabStatus.OBSERVED,
                    mapOf(
                        "attempt" to (attempt + 1).toString(),
                        "error" to t::class.java.simpleName + ": " + (t.message ?: ""),
                    ),
                )
            }
            delay(CREATE_BACKOFF_MS)
        }

        val created = display
        if (created == null) {
            val why = lastError?.let { it::class.java.simpleName + ": " + (it.message ?: "") }
                ?: "createVirtualDisplay returned null"
            fail("VirtualDisplay could not be created. " + why, LabStatus.ERROR)
            return
        }

        virtualDisplay = created
        running = true
        _state.update {
            it.copy(
                phase = Phase.CAPTURING,
                message = "Capturing the default display into an ImageReader. Nothing leaves this device.",
                error = null,
            )
        }
        SessionLogger.log(
            LabCategory.MEDIAPROJECTION,
            "projection.virtual_display_created",
            LabStatus.OBSERVED,
            target.toLogDetails() + mapOf("displayId" to displayIdOf(created)),
        )
        lastSummaryAtMs = SystemClock.elapsedRealtime()
        h.postDelayed(ticker, TICK_MS)
    }

    private fun displayIdOf(vd: VirtualDisplay): String = try {
        vd.display?.displayId?.toString() ?: "unknown"
    } catch (t: Throwable) {
        "unknown"
    }

    // ------------------------------------------------------------ frame path

    private fun onImageAvailable(reader: ImageReader) {
        var image: Image? = null
        var pending = 0
        try {
            // Drain everything queued. Frames superseded before we looked at them are late frames:
            // the consumer, not the producer, was the bottleneck, and that is worth reporting.
            while (true) {
                val next = try {
                    reader.acquireNextImage()
                } catch (t: Throwable) {
                    dropCount++
                    null
                } ?: break
                if (image != null) {
                    image.close()
                    lateCount++
                }
                image = next
                pending++
            }
            val img = image
            if (img == null) {
                if (pending == 0) dropCount++
                return
            }

            val now = SystemClock.elapsedRealtime()
            if (firstFrameAtMs < 0) {
                firstFrameAtMs = now
                SessionLogger.log(
                    LabCategory.MEDIAPROJECTION,
                    "projection.first_frame",
                    LabStatus.OBSERVED,
                    mapOf("msSinceStart" to (now - startedAtMs).toString()),
                )
            }
            frameCount++
            frameTimestamps.addLast(now)
            while (frameTimestamps.isNotEmpty() && now - frameTimestamps.peekFirst() > FPS_WINDOW_MS) {
                frameTimestamps.removeFirst()
            }

            capturedWidth = img.width
            capturedHeight = img.height
            val plane = img.planes.firstOrNull()
            if (plane != null) {
                pixelStride = plane.pixelStride
                rowStride = plane.rowStride
            }
            // The buffer is deliberately not read, copied or persisted: this experiment measures
            // the capture path, and screen content is exactly the personal data we must not collect.
        } catch (t: Throwable) {
            dropCount++
            Log.w(TAG, "frame handling failed", t)
        } finally {
            try {
                image?.close()
            } catch (t: Throwable) {
                Log.w(TAG, "image close failed", t)
            }
        }
    }

    /** Publishes counters to the UI at a fixed rate and writes a summary observation every 5 s. */
    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            val now = SystemClock.elapsedRealtime()
            val windowFrames = frameTimestamps.size
            val fps = if (windowFrames <= 1) {
                0.0
            } else {
                val span = now - (frameTimestamps.peekFirst() ?: now)
                if (span <= 0) 0.0 else windowFrames * 1000.0 / span
            }
            _state.update {
                it.copy(
                    frames = frameCount,
                    fps = fps,
                    capturedWidth = capturedWidth,
                    capturedHeight = capturedHeight,
                    pixelStride = pixelStride,
                    rowStride = rowStride,
                    rowPaddingBytes = max(0, rowStride - pixelStride * max(1, capturedWidth)),
                    lateFrames = lateCount,
                    droppedFrames = dropCount,
                    firstFrameMs = if (firstFrameAtMs < 0) -1 else firstFrameAtMs - startedAtMs,
                    elapsedMs = now - startedAtMs,
                )
            }
            if (now - lastSummaryAtMs >= SUMMARY_MS) {
                lastSummaryAtMs = now
                SessionLogger.log(
                    LabCategory.MEDIAPROJECTION,
                    "projection.capture_summary",
                    LabStatus.OBSERVED,
                    summaryDetails(fps, now),
                )
            }
            handler?.postDelayed(this, TICK_MS)
        }
    }

    private fun summaryDetails(fps: Double, now: Long): Map<String, String> = mapOf(
        "elapsedMs" to (now - startedAtMs).toString(),
        "frames" to frameCount.toString(),
        "fpsWindow2s" to String.format("%.2f", fps),
        "capturedSize" to capturedWidth.toString() + "x" + capturedHeight,
        "pixelStride" to pixelStride.toString(),
        "rowStride" to rowStride.toString(),
        "lateFrames" to lateCount.toString(),
        "droppedFrames" to dropCount.toString(),
        "firstFrameMs" to (if (firstFrameAtMs < 0) "-1" else (firstFrameAtMs - startedAtMs).toString()),
        "encoding" to (encoder != null).toString(),
    )

    // ---------------------------------------------------------------- encode

    /**
     * Redirects the existing VirtualDisplay into an AVC encoder input surface for [durationMs],
     * then restores the ImageReader.
     *
     * Swapping the surface rather than creating a second VirtualDisplay is intentional: a
     * MediaProjection session may legitimately refuse a second display, and the consent token
     * cannot be replayed to get a new session.
     */
    suspend fun encodeClip(durationMs: Long = DEFAULT_CLIP_MS) {
        val display = virtualDisplay
        val target = _state.value.target
        val reader = imageReader
        if (!running || display == null || target == null || reader == null) {
            SessionLogger.log(
                LabCategory.MEDIAPROJECTION,
                "projection.encode_skipped",
                LabStatus.NOT_TESTED,
                mapOf("reason" to "no active capture session"),
            )
            return
        }
        if (encoder != null) return

        val outFile = SessionLogger.evidenceDir()
            ?.let { File(it, "capture-" + SessionLogger.testRunId.take(8) + ".h264") }
        val bitRate = suggestedBitRate(target.width, target.height)

        val enc = H264Encoder(
            width = target.width,
            height = target.height,
            bitRate = bitRate,
            frameRate = CLIP_FRAME_RATE,
            keyFrameIntervalSec = CLIP_KEYFRAME_INTERVAL_S,
            outFile = outFile,
        )

        val inputSurface: Surface = try {
            enc.start()
        } catch (t: Throwable) {
            SessionLogger.log(
                LabCategory.MEDIAPROJECTION,
                "projection.encoder_start_failed",
                LabStatus.ERROR,
                mapOf("error" to t::class.java.simpleName + ": " + (t.message ?: "")),
            )
            _state.update { it.copy(error = "Encoder failed to start: " + t::class.java.simpleName) }
            return
        }
        encoder = enc

        _state.update {
            it.copy(
                phase = Phase.ENCODING,
                encodeRunning = true,
                encodeSecondsLeft = (durationMs / 1000).toInt(),
                message = "Encoding a local H.264 test clip. The stream is written to app-private storage only.",
            )
        }
        SessionLogger.log(
            LabCategory.MEDIAPROJECTION,
            "projection.encode_started",
            LabStatus.OBSERVED,
            mapOf(
                "size" to target.resolution,
                "bitRate" to bitRate.toString(),
                "frameRate" to CLIP_FRAME_RATE.toString(),
                "iFrameIntervalSec" to CLIP_KEYFRAME_INTERVAL_S.toString(),
                "outFile" to (outFile?.absolutePath ?: "none"),
            ),
        )

        try {
            display.setSurface(inputSurface)
        } catch (t: Throwable) {
            enc.abandon()
            encoder = null
            _state.update { it.copy(phase = Phase.CAPTURING, encodeRunning = false) }
            SessionLogger.log(
                LabCategory.MEDIAPROJECTION,
                "projection.encode_surface_swap_failed",
                LabStatus.ERROR,
                mapOf("error" to t::class.java.simpleName),
            )
            return
        }

        val deadline = SystemClock.elapsedRealtime() + durationMs
        while (SystemClock.elapsedRealtime() < deadline && running) {
            delay(200)
            val left = ((deadline - SystemClock.elapsedRealtime()) / 1000).toInt()
            _state.update { it.copy(encodeSecondsLeft = max(0, left)) }
        }

        enc.signalEndOfStream()
        // Point the VirtualDisplay back at the ImageReader while the encoder input surface is
        // still valid: stopAndCollect releases that surface, and a display rendering into a
        // released surface is undefined behaviour. Frames already queued still drain to EOS.
        try {
            display.setSurface(reader.surface)
        } catch (t: Throwable) {
            Log.w(TAG, "restoring ImageReader surface failed", t)
        }
        val result = withContext(Dispatchers.IO) { enc.stopAndCollect() }
        encoder = null

        _state.update {
            it.copy(
                phase = if (running) Phase.CAPTURING else it.phase,
                encodeRunning = false,
                encodeSecondsLeft = 0,
                encode = result,
                message = "Encode finished. " + result.frames + " frames written locally.",
            )
        }
        SessionLogger.log(
            LabCategory.MEDIAPROJECTION,
            "projection.encode_result",
            if (result.frames > 0) LabStatus.OBSERVED else LabStatus.ERROR,
            result.toLogDetails(),
        )
        SessionLogger.logAll(encodeObservations(result))
    }

    private fun encodeObservations(r: H264Encoder.Result): List<Observation> {
        val positive = r.frames > 0
        val status = if (positive) LabStatus.OBSERVED else LabStatus.ERROR
        val evidencePath = r.outputPath
        return listOf(
            Observation("projection.encoder_name", r.codecName, LabStatus.CONFIRMED, null, LabCategory.CODEC),
            Observation(
                "projection.encoder_hardware_accelerated",
                r.hardwareAccelerated?.toString() ?: "unknown",
                if (r.hardwareAccelerated == null) LabStatus.ERROR else LabStatus.CONFIRMED,
                "MediaCodecInfo#isHardwareAccelerated for the encoder that actually ran.",
                LabCategory.CODEC,
            ),
            Observation(
                "projection.encoded_frames",
                r.frames.toString(),
                status,
                "Frames the encoder emitted from MediaProjection surface input.",
                LabCategory.MEDIAPROJECTION,
            ),
            Observation(
                "projection.encode_fps",
                String.format("%.2f", r.encodeFps),
                status,
                "Derived from encoder presentation timestamps over the clip.",
                LabCategory.MEDIAPROJECTION,
            ),
            Observation(
                "projection.encode_bitrate_measured",
                r.measuredBitRate.toString() + " bps (requested " + r.configuredBitRate + ")",
                status,
                "Requested bitrate is a hint; this is what was written.",
                LabCategory.CODEC,
            ),
            Observation(
                "projection.encode_avg_frame_bytes",
                r.averageFrameBytes.toString(),
                status,
                null,
                LabCategory.CODEC,
            ),
            evidencePath?.let {
                Observation(
                    "projection.encode_evidence_file",
                    it,
                    LabStatus.OBSERVED,
                    "Elementary stream on app-private storage. Never uploaded; pull it with adb.",
                    LabCategory.MEDIAPROJECTION,
                )
            } ?: Observation.notTested(
                "projection.encode_evidence_file",
                LabCategory.MEDIAPROJECTION,
                "No bytes were written" + (r.writeError?.let { e -> ": " + e } ?: "."),
            ),
        )
    }

    /** Roughly 0.1 bits per pixel per frame, the usual starting point for screen content. */
    private fun suggestedBitRate(w: Int, h: Int): Int =
        min(12_000_000, max(2_000_000, (w.toLong() * h * CLIP_FRAME_RATE / 10).toInt()))

    // ----------------------------------------------------------------- audio

    /**
     * Builds an [AudioPlaybackCaptureConfiguration] and an [AudioRecord] around it, reports whether
     * the platform accepted them, and releases immediately. Nothing is recorded: startRecording is
     * never called, so no audio frame ever exists in this process.
     */
    // Lint cannot see the guard: the RECORD_AUDIO check below returns NOT_TESTED before any
    // AudioRecord is constructed.
    @SuppressLint("MissingPermission")
    suspend fun probeAudioPlaybackCapture() {
        val proj = projection
        if (proj == null) {
            setAudio(
                LabStatus.NOT_TESTED,
                "No active MediaProjection. Playback capture needs a granted capture session.",
            )
            return
        }
        if (!LabPermissions.isGranted(app, Manifest.permission.RECORD_AUDIO)) {
            setAudio(
                LabStatus.NOT_TESTED,
                "RECORD_AUDIO is not granted, so AudioRecord cannot be constructed. " +
                    "This says nothing about whether playback capture would work.",
            )
            return
        }

        val outcome = withContext(Dispatchers.IO) {
            var record: AudioRecord? = null
            try {
                val config = AudioPlaybackCaptureConfiguration.Builder(proj)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()
                val format = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(AUDIO_SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build()
                val minBuffer = AudioRecord.getMinBufferSize(
                    AUDIO_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
                val built = AudioRecord.Builder()
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(max(minBuffer, AUDIO_MIN_BUFFER))
                    .setAudioPlaybackCaptureConfig(config)
                    .build()
                record = built
                if (built.state == AudioRecord.STATE_INITIALIZED) {
                    LabStatus.CONFIRMED to (
                        "AudioRecord constructed with AudioPlaybackCaptureConfiguration " +
                            "(" + AUDIO_SAMPLE_RATE + " Hz stereo PCM16, minBuffer=" + minBuffer +
                            " bytes). Released without recording. Only apps whose playback allows " +
                            "capture would ever be included."
                        )
                } else {
                    LabStatus.UNSUPPORTED to
                        ("AudioRecord was built but reported state=" + built.state +
                            " (not STATE_INITIALIZED).")
                }
            } catch (t: Throwable) {
                LabStatus.ERROR to (t::class.java.simpleName + ": " + (t.message ?: "no message"))
            } finally {
                try {
                    record?.release()
                } catch (t: Throwable) {
                    Log.w(TAG, "AudioRecord release failed", t)
                }
            }
        }
        setAudio(outcome.first, outcome.second)
    }

    private fun setAudio(status: LabStatus, detail: String) {
        _state.update { it.copy(audioStatus = status, audioDetail = detail) }
        SessionLogger.log(
            Observation(
                "projection.audio_playback_capture",
                if (status == LabStatus.CONFIRMED) "constructible" else status.name.lowercase(),
                status,
                detail,
                LabCategory.AUDIO,
            ),
        )
    }

    // ------------------------------------------------------------------ stop

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // Fires when the user taps the system "stop sharing" chip, when the platform revokes
            // the session, or after our own stop(). Either way capture is over.
            SessionLogger.log(
                LabCategory.MEDIAPROJECTION,
                "projection.callback_on_stop",
                LabStatus.OBSERVED,
                mapOf("selfInitiated" to (!running).toString()),
            )
            if (running) {
                _state.update { it.copy(stoppedByPlatform = true) }
                stop("MediaProjection.Callback#onStop")
            }
        }
    }

    private val virtualDisplayCallback = object : VirtualDisplay.Callback() {
        override fun onPaused() = logDisplayEvent("paused")
        override fun onResumed() = logDisplayEvent("resumed")
        override fun onStopped() = logDisplayEvent("stopped")
    }

    private fun logDisplayEvent(what: String) {
        SessionLogger.log(
            LabCategory.MEDIAPROJECTION,
            "projection.virtual_display_" + what,
            LabStatus.OBSERVED,
            mapOf("elapsedMs" to (SystemClock.elapsedRealtime() - startedAtMs).toString()),
        )
    }

    /**
     * Tears the whole pipeline down in dependency order and emits the session's findings.
     * Idempotent, never throws, safe from the main thread, the capture thread and onDispose.
     */
    fun stop(reason: String) {
        val wasRunning = running
        running = false
        _state.update { if (wasRunning) it.copy(phase = Phase.STOPPING) else it }

        handler?.removeCallbacksAndMessages(null)

        // Detach the display first: whatever surface it currently holds (ImageReader or encoder
        // input) is released a few lines below, and it must not still be a render target then.
        try {
            virtualDisplay?.setSurface(null)
        } catch (t: Throwable) {
            Log.w(TAG, "clearing virtual display surface failed", t)
        }

        encoder?.let {
            try {
                it.abandon()
            } catch (t: Throwable) {
                Log.w(TAG, "encoder abandon failed", t)
            }
        }
        encoder = null
        try {
            virtualDisplay?.release()
        } catch (t: Throwable) {
            Log.w(TAG, "virtual display release failed", t)
        }
        virtualDisplay = null

        try {
            imageReader?.setOnImageAvailableListener(null, null)
            imageReader?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "image reader close failed", t)
        }
        imageReader = null

        projection?.let { proj ->
            try {
                proj.unregisterCallback(projectionCallback)
            } catch (t: Throwable) {
                Log.w(TAG, "unregisterCallback failed", t)
            }
            try {
                // A MediaProjection that outlives its session makes the next getMediaProjection
                // throw. This single call is the difference between a rerunnable lab and a crash.
                proj.stop()
            } catch (t: Throwable) {
                Log.w(TAG, "projection stop failed", t)
            }
        }
        projection = null

        try {
            ProjectionService.stop(app)
        } catch (t: Throwable) {
            Log.w(TAG, "stopping projection service failed", t)
        }

        handlerThread?.quitSafely()
        handlerThread = null
        handler = null

        if (wasRunning) {
            SessionLogger.log(
                LabCategory.MEDIAPROJECTION,
                "projection.session_stopped",
                LabStatus.OBSERVED,
                summaryDetails(lastKnownFps(), SystemClock.elapsedRealtime()) + mapOf("reason" to reason),
            )
            SessionLogger.logAll(sessionObservations())
            _state.update {
                it.copy(
                    phase = Phase.STOPPED,
                    message = "Capture stopped (" + reason + "). Everything was released.",
                    fps = 0.0,
                    encodeRunning = false,
                    encodeSecondsLeft = 0,
                )
            }
        }
    }

    private fun lastKnownFps(): Double = _state.value.fps

    /** The findings this session is entitled to claim, and the one it explicitly is not. */
    private fun sessionObservations(): List<Observation> {
        val s = _state.value
        val sawFrames = frameCount > 0
        val out = mutableListOf<Observation>()
        out += Observation(
            "projection.capture_session",
            if (sawFrames) "frames received" else "no frames received",
            if (sawFrames) LabStatus.OBSERVED else LabStatus.ERROR,
            "MediaProjection consent granted and a VirtualDisplay was created.",
            LabCategory.MEDIAPROJECTION,
        )
        out += Observation(
            "projection.capture_frames",
            frameCount.toString(),
            if (sawFrames) LabStatus.OBSERVED else LabStatus.ERROR,
            "Images acquired from the ImageReader over " + s.elapsedMs + " ms.",
            LabCategory.MEDIAPROJECTION,
        )
        out += Observation(
            "projection.capture_fps",
            String.format("%.2f", s.fps),
            if (sawFrames) LabStatus.OBSERVED else LabStatus.NOT_TESTED,
            "Rolling " + (FPS_WINDOW_MS / 1000) + " s window at the moment capture stopped.",
            LabCategory.MEDIAPROJECTION,
        )
        out += Observation(
            "projection.captured_resolution",
            s.capturedResolution,
            if (sawFrames) LabStatus.OBSERVED else LabStatus.NOT_TESTED,
            "As reported by the Image, not as requested: pixelStride=" + s.pixelStride +
                " rowStride=" + s.rowStride + " padding=" + s.rowPaddingBytes + " bytes/row.",
            LabCategory.MEDIAPROJECTION,
        )
        out += Observation(
            "projection.first_frame_latency_ms",
            if (s.firstFrameMs < 0) "none" else s.firstFrameMs.toString(),
            if (s.firstFrameMs >= 0) LabStatus.OBSERVED else LabStatus.NOT_TESTED,
            "From the first createVirtualDisplay attempt to the first acquired image.",
            LabCategory.MEDIAPROJECTION,
        )
        out += Observation(
            "projection.frames_late_or_dropped",
            lateCount.toString() + " late / " + dropCount.toString() + " dropped",
            if (sawFrames) LabStatus.OBSERVED else LabStatus.NOT_TESTED,
            "Late = superseded before this app looked at them; dropped = acquire failed.",
            LabCategory.MEDIAPROJECTION,
        )
        // The single most important boundary in this module. Stated every session, in the log.
        out += Observation.notTested(
            "projection.sink_receives",
            LabCategory.MIRACAST,
            "MediaProjection proves only what this phone can capture and encode locally. What a " +
                "Miracast sink - the Toyota Mirai head unit included - actually receives, accepts " +
                "or displays was not measured here and stays untested.",
        )
        return out
    }

    private fun fail(message: String, status: LabStatus) {
        SessionLogger.log(
            LabCategory.MEDIAPROJECTION,
            "projection.start_failed",
            status,
            mapOf("detail" to message),
        )
        stop("start failed")
        _state.update {
            it.copy(phase = Phase.FAILED, message = message, error = message)
        }
    }

    private fun resetCounters() {
        frameCount = 0
        lateCount = 0
        dropCount = 0
        firstFrameAtMs = -1
        capturedWidth = 0
        capturedHeight = 0
        pixelStride = 0
        rowStride = 0
        frameTimestamps.clear()
    }

    companion object {
        private const val TAG = "MiraiCastLab"
        private const val VIRTUAL_DISPLAY_NAME = "MiraiCastLab-capture"
        private const val MAX_IMAGES = 3
        private const val TICK_MS = 250L
        private const val SUMMARY_MS = 5_000L
        private const val FPS_WINDOW_MS = 2_000L
        private const val CREATE_ATTEMPTS = 5
        private const val CREATE_BACKOFF_MS = 250L
        private const val DEFAULT_CLIP_MS = 5_000L
        private const val CLIP_FRAME_RATE = 30
        private const val CLIP_KEYFRAME_INTERVAL_S = 1
        private const val AUDIO_SAMPLE_RATE = 44_100
        private const val AUDIO_MIN_BUFFER = 8_192
    }
}
