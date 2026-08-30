package nl.icthorse.miraicastlab.scene

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import nl.icthorse.miraicastlab.core.LabCategory
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** One deferred log record produced inside the frame callback and flushed outside it. */
internal class SceneLogEvent(
    val category: LabCategory,
    val event: String,
    val details: Map<String, String>,
)

/**
 * All mutable state of the diagnostic scene, plus the per-frame integration step.
 *
 * Design constraints that shaped this class:
 *
 * - Exactly one field is Compose snapshot state: [tick]. The renderer reads it inside the draw
 *   lambda, so a new frame invalidates the *draw* phase only - no recomposition, no layout. Every
 *   other field is a plain var, written in the frame callback and read in the draw lambda, both on
 *   the main thread within the same frame. Making them snapshot state would buy nothing and cost a
 *   recomposition per field per frame.
 * - Nothing allocates per frame. Trails are pre-sized arrays, the sweep is a fixed table, numbers
 *   are rendered from a reusable CharArray, and the stripe patterns are ImageBitmaps built once.
 *   If the renderer allocated per frame the FPS number it reports would measure the garbage
 *   collector rather than the link.
 * - Frame timing uses the Choreographer frame time from withFrameNanos for deltas, and
 *   SystemClock.elapsedRealtimeNanos for the timestamp burned into the picture, because the latter
 *   is the monotonic clock the rest of the evidence is stamped with.
 */
internal class SceneEngine {

    /** Draw-phase invalidation signal. Written once per frame, read first in the draw lambda. */
    val tick = mutableLongStateOf(0L)

    // ------------------------------------------------------------------ frame accounting

    /** Frames this renderer has actually drawn. Advances even while paused: it measures rendering. */
    var frameCount = 0L
        private set

    /** Frames of *animation*. Frozen while paused, so "paused" is distinguishable from "stalled". */
    var animFrame = 0L
        private set

    /** Animated scene time in nanoseconds, already scaled by the speed control. */
    var sceneNanos = 0L
        private set

    /**
     * Draw passes the Canvas actually performed.
     *
     * Kept separately from [frameCount] on purpose. The frame loop advancing proves the coroutine
     * is alive; only this counter proves pixels were produced. If the two diverge, the draw-phase
     * invalidation broke and every FPS number here is a lie about a frozen picture - which is
     * precisely the failure the liveness record exists to catch.
     */
    var drawCount = 0L
        private set

    /** Called once per completed draw pass from the renderer. */
    fun onDrawn() {
        drawCount++
    }

    /** Monotonic timestamp burned into the picture; SystemClock.elapsedRealtimeNanos. */
    var stampNanos = 0L
        private set

    var lastDeltaNanos = 0L
        private set

    private var lastFrameNanos = 0L

    // Rolling one-second FPS window. Circular buffer of frame timestamps.
    private val ring = LongArray(FPS_RING)
    private var ringHead = 0
    private var ringTail = 0
    private var ringSize = 0

    var fps = 0f
        private set

    // Frame-time histogram in 0.5 ms buckets. A histogram rather than a sorted window because the
    // 99th percentile has to cover the whole run, and sorting per frame would itself cause judder.
    private val hist = IntArray(BUCKETS + 1)
    var statsFrames = 0L
        private set
    private var statsSumNanos = 0L
    var maxFrameNanos = 0L
        private set
    var longFrames = 0L
        private set
    var p99FrameMs = 0f
        private set
    var avgFrameMs = 0f
        private set
    private var statsEpoch = -1

    // ------------------------------------------------------------------ mirrored controls

    var pattern: ScenePattern = ScenePattern.DIAGNOSTIC
        private set
    var playing = true
        private set
    var speed = 1f
        private set
    var showGrid = false
        private set
    var inputCapture = false
        private set

    // ------------------------------------------------------------------ bouncing object

    /** Ball position in arena-normalised coordinates, 0..1. */
    var ballX = 0.5f
        private set
    var ballY = 0.35f
        private set

    // Irrational-ish ratio so the path does not repeat quickly and judder cannot hide in a cycle.
    private var vx = 0.311f
    private var vy = 0.472f

    val trailX = FloatArray(TRAIL)
    val trailY = FloatArray(TRAIL)
    var trailCount = 0
        private set
    private var trailHead = 0

    /** Trail sample [i] counting back from newest (0 = newest). */
    fun trailXAt(i: Int): Float = trailX[((trailHead - 1 - i) % TRAIL + TRAIL) % TRAIL]
    fun trailYAt(i: Int): Float = trailY[((trailHead - 1 - i) % TRAIL + TRAIL) % TRAIL]

    // ------------------------------------------------------------------ 60-position sweep

    /** Frame number at which each of the 60 sweep positions was last lit. */
    val sweepFrame = LongArray(SWEEP) { -1000L }
    var sweepIndex = 0
        private set

    private val sweepCos = FloatArray(SWEEP)
    private val sweepSin = FloatArray(SWEEP)

    // ------------------------------------------------------------------ pulse / latency

    /** Nanoseconds since the last audio pulse, or Long.MAX_VALUE if there has never been one. */
    var pulseAgeNanos = Long.MAX_VALUE
        private set

    /** True for exactly one drawn frame per latency flash. */
    var latencyFlash = false
        private set
    var latencyIndex = 0
        private set
    var latencyActive = false
        private set
    private var lastFlashIndex = 0

    // ------------------------------------------------------------------ touch

    var touchX = -1f
        private set
    var touchY = -1f
        private set
    var touchDown = false
        private set
    var touchCount = 0L
        private set
    var touchType = "none"
        private set
    private var lastTouchLogNanos = 0L

    // ------------------------------------------------------------------ surface facts

    var surfaceW = 0
        private set
    var surfaceH = 0
        private set
    var displayId = -1
    var displayName = "?"
    var refreshHz = 0f
    var orientationLandscape = false

    // ------------------------------------------------------------------ deferred logging

    private val pending = ArrayList<SceneLogEvent>(4)

    fun hasPending(): Boolean = pending.isNotEmpty()

    fun drainInto(out: MutableList<SceneLogEvent>) {
        if (pending.isEmpty()) return
        out.addAll(pending)
        pending.clear()
    }

    // ------------------------------------------------------------------ paints

    val pBig = textPaint(0xFFFFFFFF.toInt(), bold = true)
    val pMed = textPaint(0xFFE8EEF4.toInt(), bold = true)
    val pSmall = textPaint(0xFF93A3B4.toInt(), bold = false)

    /** Oversized paint for the latency frame number and the watermark. */
    val pHuge = textPaint(0xFFFFFFFF.toInt(), bold = true)

    /** Low-alpha paint for the "TEST LAB" watermark; must never obscure a measurement. */
    val pFaint = textPaint(0x30FFFFFF, bold = true)

    /** Monospace advance width for the medium paint, so numbers can be laid out without measuring. */
    var chW = 0f
        private set
    private var lastUnit = -1f

    init {
        for (i in 0 until SWEEP) {
            val a = (i.toFloat() / SWEEP) * TWO_PI - HALF_PI
            sweepCos[i] = cos(a)
            sweepSin[i] = sin(a)
        }
    }

    fun sweepCosAt(i: Int): Float = sweepCos[i]
    fun sweepSinAt(i: Int): Float = sweepSin[i]

    /** Rescales the text paints when the surface size changes. No-op on every other frame. */
    fun sizePaints(unit: Float) {
        if (unit == lastUnit) return
        lastUnit = unit
        pBig.textSize = 9.0f * unit
        pMed.textSize = 4.0f * unit
        pSmall.textSize = 2.7f * unit
        pHuge.textSize = 18.0f * unit
        pFaint.textSize = 14.0f * unit
        chW = pMed.measureText("0")
    }

    fun onSurfaceSize(w: Int, h: Int) {
        if (w == surfaceW && h == surfaceH) return
        surfaceW = w
        surfaceH = h
        pending.add(
            SceneLogEvent(
                LabCategory.DISPLAY,
                "scene_surface_size",
                mapOf(
                    "widthPx" to w.toString(),
                    "heightPx" to h.toString(),
                    "displayId" to displayId.toString(),
                ),
            ),
        )
    }

    // ------------------------------------------------------------------ input

    /**
     * Records a pointer sample. Logging is throttled to [TOUCH_LOG_INTERVAL_NANOS] while a finger
     * is moving; press and release are always logged so the record is never missing an edge.
     */
    fun onTouch(x: Float, y: Float, pressed: Boolean, type: String) {
        val edge = pressed != touchDown
        touchX = x
        touchY = y
        touchType = type
        if (edge && pressed) touchCount++
        touchDown = pressed
        if (!inputCapture) return
        val now = SystemClock.elapsedRealtimeNanos()
        if (!edge && now - lastTouchLogNanos < TOUCH_LOG_INTERVAL_NANOS) return
        lastTouchLogNanos = now
        pending.add(
            SceneLogEvent(
                LabCategory.INPUT,
                if (edge) (if (pressed) "scene_touch_down" else "scene_touch_up") else "scene_touch_move",
                mapOf(
                    "xPx" to x.toInt().toString(),
                    "yPx" to y.toInt().toString(),
                    "xNorm" to fmt3(if (surfaceW > 0) x / surfaceW else -1f),
                    "yNorm" to fmt3(if (surfaceH > 0) y / surfaceH else -1f),
                    "pointerType" to type,
                    "displayId" to displayId.toString(),
                    "frame" to frameCount.toString(),
                    "elapsedRealtimeNanos" to now.toString(),
                ),
            ),
        )
    }

    // ------------------------------------------------------------------ the frame step

    /**
     * Integrates one frame. Called from withFrameNanos on the main thread.
     *
     * @param frameTimeNanos the Choreographer frame time; monotonic, excludes deep sleep, and is
     *                       the only clock whose deltas actually describe frame pacing.
     */
    fun onFrame(frameTimeNanos: Long) {
        stampNanos = SystemClock.elapsedRealtimeNanos()

        // Mirror the shared controls once per frame. Reading a StateFlow value is a volatile read;
        // doing it here keeps the draw lambda free of any cross-thread reasoning.
        pattern = SceneState.pattern.value
        playing = SceneState.playing.value
        speed = SceneState.speed.value
        showGrid = SceneState.showGrid.value
        inputCapture = SceneState.inputCapture.value

        val epoch = SceneState.statsEpoch.value
        if (epoch != statsEpoch) {
            statsEpoch = epoch
            resetStats()
        }

        val delta = if (lastFrameNanos == 0L) 0L else frameTimeNanos - lastFrameNanos
        lastFrameNanos = frameTimeNanos
        lastDeltaNanos = delta
        frameCount++

        if (delta > 0L) {
            recordFrameTime(delta)
            pushFpsSample(frameTimeNanos)
        }

        // The sweep advances exactly one position per drawn frame. That is the whole point: a
        // photograph of the receiver with a known exposure shows how many distinct positions the
        // link actually delivered, independent of anything the phone claims about its own FPS.
        sweepIndex = (frameCount % SWEEP).toInt()
        sweepFrame[sweepIndex] = frameCount

        if (playing && delta > 0L) {
            val dt = (delta.toDouble() / 1_000_000_000.0).toFloat() * speed
            sceneNanos += (delta.toDouble() * speed).toLong()
            animFrame++
            advanceBall(dt)
            pushTrail()
        }

        updatePulse()
        updateLatency()

        // Publish statistics at ~2 Hz. Anything faster would recompose the control panel for no
        // readable benefit; anything slower would make the FPS readout feel dead.
        if (stampNanos - lastPublishNanos >= STATS_PUBLISH_INTERVAL_NANOS) {
            lastPublishNanos = stampNanos
            computeDerivedStats()
            SceneState.publishStats(
                SceneStats(
                    fps = fps,
                    avgFrameMs = avgFrameMs,
                    p99FrameMs = p99FrameMs,
                    maxFrameMs = maxFrameNanos / 1_000_000f,
                    framesRendered = frameCount,
                    framesInStatsWindow = statsFrames,
                    longFrames = longFrames,
                    surfaceWidthPx = surfaceW,
                    surfaceHeightPx = surfaceH,
                    displayId = displayId,
                    displayName = displayName,
                    refreshHz = refreshHz,
                    lastTouch = if (touchCount == 0L) "none" else touchLabel(),
                    touchCount = touchCount,
                ),
            )
        }

        tick.longValue = frameCount
    }

    private var lastPublishNanos = 0L

    private fun touchLabel(): String =
        touchX.toInt().toString() + "," + touchY.toInt().toString() + " " + touchType

    private fun advanceBall(dt: Float) {
        ballX += vx * dt
        ballY += vy * dt
        if (ballX < 0f) { ballX = -ballX; vx = abs(vx) }
        if (ballX > 1f) { ballX = 2f - ballX; vx = -abs(vx) }
        if (ballY < 0f) { ballY = -ballY; vy = abs(vy) }
        if (ballY > 1f) { ballY = 2f - ballY; vy = -abs(vy) }
        // A very large speed multiplier can overshoot the reflection; clamp rather than loop.
        ballX = ballX.coerceIn(0f, 1f)
        ballY = ballY.coerceIn(0f, 1f)
    }

    private fun pushTrail() {
        trailX[trailHead] = ballX
        trailY[trailHead] = ballY
        trailHead = (trailHead + 1) % TRAIL
        if (trailCount < TRAIL) trailCount++
    }

    private fun pushFpsSample(nowNanos: Long) {
        ring[ringHead] = nowNanos
        ringHead = (ringHead + 1) % FPS_RING
        if (ringSize < FPS_RING) ringSize++ else ringTail = (ringTail + 1) % FPS_RING
        while (ringSize > 1 && nowNanos - ring[ringTail] > 1_000_000_000L) {
            ringTail = (ringTail + 1) % FPS_RING
            ringSize--
        }
        val span = nowNanos - ring[ringTail]
        fps = if (ringSize > 1 && span > 0L) {
            (ringSize - 1) * 1_000_000_000f / span
        } else {
            0f
        }
    }

    private fun recordFrameTime(deltaNanos: Long) {
        statsFrames++
        statsSumNanos += deltaNanos
        if (deltaNanos > maxFrameNanos) maxFrameNanos = deltaNanos
        // "Long frame" = took more than 1.5x the display's frame budget. With no known refresh rate
        // fall back to 60 Hz, and say so in the report rather than pretending we measured it.
        val budget = if (refreshHz > 1f) (1_000_000_000f / refreshHz) else 16_666_667f
        if (deltaNanos > budget * 1.5f) longFrames++
        val bucket = (deltaNanos / 500_000L).toInt()
        hist[if (bucket in 0 until BUCKETS) bucket else BUCKETS]++
    }

    private fun computeDerivedStats() {
        avgFrameMs = if (statsFrames > 0) (statsSumNanos.toDouble() / statsFrames / 1_000_000.0).toFloat() else 0f
        if (statsFrames <= 0) { p99FrameMs = 0f; return }
        val target = (statsFrames * 99 + 99) / 100  // ceil(statsFrames * 0.99)
        var cumulative = 0L
        for (i in 0..BUCKETS) {
            cumulative += hist[i]
            if (cumulative >= target) {
                // Report the upper edge of the bucket: an honest over-estimate beats a flattering one.
                p99FrameMs = if (i == BUCKETS) BUCKETS * 0.5f else (i + 1) * 0.5f
                return
            }
        }
    }

    private fun resetStats() {
        java.util.Arrays.fill(hist, 0)
        statsFrames = 0
        statsSumNanos = 0
        maxFrameNanos = 0
        longFrames = 0
        p99FrameMs = 0f
        avgFrameMs = 0f
    }

    private fun updatePulse() {
        val at = SceneState.audioPulseAt.value
        pulseAgeNanos = if (at == 0L) Long.MAX_VALUE else stampNanos - at
    }

    /**
     * Drives the latency flash sequence: one single-frame full-screen flash per second.
     *
     * Single-frame is deliberate. A flash held for several frames tells you nothing about how many
     * frames the link dropped; a one-frame flash either arrives at the receiver or it does not, and
     * a camera pointed at both screens resolves the delay directly.
     */
    private fun updateLatency() {
        val start = SceneState.latencyStartedAt.value
        if (start == 0L) {
            latencyActive = false
            latencyFlash = false
            lastFlashIndex = 0
            return
        }
        latencyActive = true
        val t = stampNanos - start
        val index = (t / 1_000_000_000L).toInt()
        if (index > SceneState.LATENCY_FLASHES) {
            latencyFlash = false
            SceneState.endLatencySequence()
            return
        }
        if (index in 1..SceneState.LATENCY_FLASHES && index != lastFlashIndex) {
            lastFlashIndex = index
            latencyFlash = true
            latencyIndex = index
            pending.add(
                SceneLogEvent(
                    LabCategory.DISPLAY,
                    "latency_flash",
                    mapOf(
                        "index" to index.toString(),
                        "frame" to frameCount.toString(),
                        "elapsedRealtimeNanos" to stampNanos.toString(),
                        "sinceStartMs" to (t / 1_000_000L).toString(),
                        "displayId" to displayId.toString(),
                        "fps" to fmt1(fps),
                    ),
                ),
            )
        } else {
            latencyFlash = false
        }
    }

    // ------------------------------------------------------------------ generated bitmaps

    /**
     * Stripe source for the scrolling bars: three rows, one per spatial frequency.
     *
     * Row 0 alternates every pixel, row 1 every 2 px, row 2 every 4 px. Each row is uniform in y,
     * so the renderer may stretch a row vertically without destroying the pattern, but must blit
     * it 1:1 horizontally. Any receiver that rescales the picture turns row 0 into flat grey or
     * moire first, row 1 next; which row survives is a direct read-out of the effective resolution.
     */
    var stripesH: ImageBitmap? = null
        private set

    /** The same three frequencies as columns, for the vertically scrolling bar. */
    var stripesV: ImageBitmap? = null
        private set

    /** 1-pixel checkerboard, blitted 1:1. Collapses to grey the moment anything rescales. */
    var checker: ImageBitmap? = null
        private set

    fun ensureAssets() {
        if (stripesH == null) stripesH = buildStripesH()
        if (stripesV == null) stripesV = buildStripesV()
        if (checker == null) checker = buildChecker()
    }

    private fun buildStripesH(): ImageBitmap {
        val px = IntArray(STRIPE_LEN * 3)
        for (row in 0 until 3) {
            val period = 1 shl row // 1, 2, 4 px per stripe
            val base = row * STRIPE_LEN
            for (x in 0 until STRIPE_LEN) {
                px[base + x] = if ((x / period) % 2 == 0) WHITE else BLACK
            }
        }
        return Bitmap.createBitmap(px, STRIPE_LEN, 3, Bitmap.Config.ARGB_8888).asImageBitmap()
    }

    private fun buildStripesV(): ImageBitmap {
        val px = IntArray(3 * STRIPE_LEN)
        for (y in 0 until STRIPE_LEN) {
            for (col in 0 until 3) {
                val period = 1 shl col
                px[y * 3 + col] = if ((y / period) % 2 == 0) WHITE else BLACK
            }
        }
        return Bitmap.createBitmap(px, 3, STRIPE_LEN, Bitmap.Config.ARGB_8888).asImageBitmap()
    }

    private fun buildChecker(): ImageBitmap {
        val n = CHECKER
        val px = IntArray(n * n)
        for (y in 0 until n) {
            for (x in 0 until n) {
                px[y * n + x] = if ((x + y) % 2 == 0) WHITE else BLACK
            }
        }
        return Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888).asImageBitmap()
    }

    // ------------------------------------------------------------------ allocation-free numbers

    private val digits = CharArray(32)

    /**
     * Draws [value] and returns the x coordinate just past it.
     *
     * Numbers are formatted into a reusable CharArray rather than a String because this runs up to
     * a dozen times per frame at 60 Hz; the resulting garbage would show up as exactly the kind of
     * frame-time spike this scene exists to measure.
     *
     * @param decimals number of digits of [value] that are fractional (value 598, decimals 1 -> 59.8)
     */
    fun num(
        canvas: android.graphics.Canvas,
        value: Long,
        x: Float,
        y: Float,
        paint: Paint,
        minDigits: Int = 1,
        decimals: Int = 0,
    ): Float {
        var i = digits.size
        var v = if (value < 0) -value else value
        if (decimals > 0) {
            for (d in 0 until decimals) {
                digits[--i] = '0' + (v % 10).toInt()
                v /= 10
            }
            digits[--i] = '.'
        }
        var intDigits = 0
        do {
            digits[--i] = '0' + (v % 10).toInt()
            v /= 10
            intDigits++
        } while (v > 0L || intDigits < minDigits)
        if (value < 0) digits[--i] = '-'
        val count = digits.size - i
        canvas.drawText(digits, i, count, x, y, paint)
        return x + paint.measureText(digits, i, count)
    }

    /** Draws a constant label and returns the x coordinate just past it. */
    fun label(canvas: android.graphics.Canvas, s: String, x: Float, y: Float, paint: Paint): Float {
        canvas.drawText(s, x, y, paint)
        return x + paint.measureText(s)
    }

    companion object {
        const val SWEEP = 60
        const val TRAIL = 72
        private const val FPS_RING = 512
        private const val BUCKETS = 240          // 0.5 ms buckets -> 120 ms, plus one overflow slot
        private const val STRIPE_LEN = 8192      // covers any panel we could plausibly meet
        private const val CHECKER = 256
        // Not const: a const initializer may not contain a call, and 0xFFFFFFFF only fits an Int
        // after an explicit narrowing conversion.
        private val WHITE = 0xFFFFFFFF.toInt()
        private val BLACK = 0xFF000000.toInt()
        private const val TOUCH_LOG_INTERVAL_NANOS = 100_000_000L
        private const val STATS_PUBLISH_INTERVAL_NANOS = 500_000_000L
        private const val TWO_PI = 6.2831855f
        private const val HALF_PI = 1.5707964f

        private fun textPaint(color: Int, bold: Boolean): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            typeface = Typeface.create(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
            textAlign = Paint.Align.LEFT
        }

        fun fmt1(v: Float): String = (Math.round(v * 10f) / 10f).toString()
        fun fmt3(v: Float): String = (Math.round(v * 1000f) / 1000f).toString()

        /** Scales a float into the integer form [num] expects, e.g. 59.83 -> 598 with decimals = 1. */
        fun scaled(v: Float, decimals: Int): Long {
            var m = 1f
            for (i in 0 until decimals) m *= 10f
            val r = v * m
            return if (r.isNaN() || r.isInfinite()) 0L else Math.round(r).toLong()
        }
    }
}
