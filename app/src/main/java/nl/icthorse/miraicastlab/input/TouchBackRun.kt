package nl.icthorse.miraicastlab.input

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import nl.icthorse.miraicastlab.core.LabCategory
import nl.icthorse.miraicastlab.core.LabStatus
import nl.icthorse.miraicastlab.core.Observation
import nl.icthorse.miraicastlab.core.SessionLogger
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/** Outcome for one grid target (spec section 11). */
data class TargetResult(
    val index: Int,
    val expectedX: Float,
    val expectedY: Float,
    val hit: Boolean,
    val skipped: Boolean,
    val receivedX: Float,
    val receivedY: Float,
    val sourceNames: String,
    val deviceName: String,
    val deviceId: Int,
    val deviceIdentity: String,
    val newDevice: Boolean,
    val hook: String,
    val toolType: String,
    val uptimeDeltaMs: Long,
    val responseMs: Long,
) {
    val label: String
        get() = when {
            hit -> "HIT"
            skipped -> "SKIPPED"
            else -> "NO INPUT OBSERVED"
        }
}

/**
 * Least-squares affine mapping (independent per axis) from the grid centre the app drew to the
 * coordinate the app received. Scale 1.0 / offset 0.0 means the sink hands through phone-space
 * coordinates unchanged; anything else quantifies the remote display's mapping (letterboxing,
 * scaling, rotation would show up as a bad fit rather than a clean scale).
 */
data class CoordinateTransform(
    val n: Int,
    val scaleX: Float,
    val offsetX: Float,
    val rmsX: Float,
    val scaleY: Float,
    val offsetY: Float,
    val rmsY: Float,
) {
    fun oneLine(): String = String.format(
        Locale.US,
        "x' = %.4f*x + %.1f (rms %.1f px)  |  y' = %.4f*y + %.1f (rms %.1f px)  [n=%d]",
        scaleX, offsetX, rmsX, scaleY, offsetY, rmsY, n,
    )

    /** A near-identity transform is what a plain 1:1 mirror with a pass-through back channel gives. */
    val isNearIdentity: Boolean
        get() = abs(scaleX - 1f) < 0.02f && abs(scaleY - 1f) < 0.02f &&
            abs(offsetX) < 8f && abs(offsetY) < 8f
}

/** Everything the screen and the report need to state a verdict without overclaiming. */
data class TouchBackAnalysis(
    val total: Int,
    val attempted: Int,
    val hits: Int,
    val skipped: Int,
    val percentReached: Int,
    val sources: Set<String>,
    val devices: Set<String>,
    val newDeviceHits: Int,
    val transform: CoordinateTransform?,
    val transformNote: String,
    val status: LabStatus,
    val headline: String,
    val detail: String,
)

/**
 * State machine for the touch-back target grid.
 *
 * The evidence rule drives the design. A target that produced nothing is recorded NOT_TESTED with an
 * explicit note - "this session saw no back channel" is not "the sink has no back channel". And a
 * hit only counts as back-channel evidence when it arrives from an input device that was *not*
 * present in the baseline snapshot, because a hit from the phone's own touchscreen proves only that
 * the tester touched the phone.
 */
class TouchBackRun(
    val cols: Int = 3,
    val rows: Int = 5,
) {
    val total: Int = cols * rows

    var perTargetTimeoutMs: Long by mutableStateOf(20_000L)
    var activeIndex: Int by mutableStateOf(-1)
        private set
    var running: Boolean by mutableStateOf(false)
        private set
    var finished: Boolean by mutableStateOf(false)
        private set
    var remainingMs: Long by mutableStateOf(0L)
        private set
    var targetShownAt: Long by mutableStateOf(0L)
        private set

    /** Pixel size of the grid area; set by the composable via onSizeChanged. */
    var gridWidthPx: Float by mutableStateOf(0f)
    var gridHeightPx: Float by mutableStateOf(0f)

    /** Device identities present before the run; anything outside this set is new hardware. */
    var baseline: Set<String> = emptySet()

    val results = mutableStateListOf<TargetResult>()

    fun expectedCentre(index: Int): Pair<Float, Float> {
        val col = index % cols
        val row = index / cols
        val cw = if (gridWidthPx > 0f) gridWidthPx / cols else 0f
        val ch = if (gridHeightPx > 0f) gridHeightPx / rows else 0f
        return Pair(cw * (col + 0.5f), ch * (row + 0.5f))
    }

    fun start(baselineIdentities: Set<String>) {
        results.clear()
        baseline = baselineIdentities
        finished = false
        running = true
        activeIndex = 0
        targetShownAt = SystemClock.uptimeMillis()
        remainingMs = perTargetTimeoutMs
        SessionLogger.log(
            LabCategory.INPUT,
            "touch_back_run_start",
            LabStatus.OBSERVED,
            mapOf(
                "targets" to total.toString(),
                "grid" to (cols.toString() + "x" + rows.toString()),
                "timeout_ms" to perTargetTimeoutMs.toString(),
                "baseline_devices" to baseline.size.toString(),
            ),
        )
    }

    fun reset() {
        running = false
        finished = false
        activeIndex = -1
        remainingMs = 0L
        results.clear()
        SessionLogger.log(LabCategory.INPUT, "touch_back_run_reset", LabStatus.OBSERVED)
    }

    /**
     * Ends a run at the targets actually presented. Unpresented targets are simply absent from
     * [results] - inventing a "no input" record for a target the tester never saw would be a
     * fabricated observation.
     */
    fun endEarly() {
        if (!running) return
        running = false
        finished = true
        activeIndex = -1
        remainingMs = 0L
        val a = analyse()
        SessionLogger.log(
            LabCategory.INPUT,
            "touch_back_run_ended_early",
            a.status,
            mapOf(
                "presented" to results.size.toString(),
                "of_targets" to total.toString(),
                "hits" to a.hits.toString(),
                "verdict" to a.headline,
            ),
        )
        SessionLogger.logAll(observations())
    }

    fun tick() {
        if (!running) return
        val elapsed = SystemClock.uptimeMillis() - targetShownAt
        remainingMs = (perTargetTimeoutMs - elapsed).coerceAtLeast(0L)
        if (remainingMs == 0L) timeoutActive()
    }

    /**
     * Feeds one captured event into the run.
     * Returns true when the event was consumed as a hit for the active target.
     */
    fun onEvent(e: CapturedEvent): Boolean {
        if (!running) return false
        val idx = activeIndex
        if (idx < 0 || idx >= total) return false
        if (!e.isDownLike) return false

        val (ex, ey) = expectedCentre(idx)
        val result = TargetResult(
            index = idx,
            expectedX = ex,
            expectedY = ey,
            hit = true,
            skipped = false,
            receivedX = e.x,
            receivedY = e.y,
            sourceNames = e.sourceNames,
            deviceName = e.deviceName,
            deviceId = e.deviceId,
            deviceIdentity = e.deviceIdentity,
            newDevice = e.deviceIdentity !in baseline,
            hook = e.hook,
            toolType = e.toolType,
            uptimeDeltaMs = e.uptimeDeltaMs,
            responseMs = SystemClock.uptimeMillis() - targetShownAt,
        )
        results.add(result)
        SessionLogger.log(
            LabCategory.INPUT,
            "touch_back_target_hit",
            LabStatus.OBSERVED,
            logDetails(result) + mapOf("new_device" to result.newDevice.toString()),
        )
        advance()
        return true
    }

    fun skipActive() {
        val idx = activeIndex
        if (!running || idx < 0 || idx >= total) return
        val (ex, ey) = expectedCentre(idx)
        val r = miss(idx, ex, ey, skipped = true)
        results.add(r)
        SessionLogger.log(LabCategory.INPUT, "touch_back_target_skipped", LabStatus.NOT_TESTED, logDetails(r))
        advance()
    }

    private fun timeoutActive() {
        val idx = activeIndex
        if (!running || idx < 0 || idx >= total) return
        val (ex, ey) = expectedCentre(idx)
        val r = miss(idx, ex, ey, skipped = false)
        results.add(r)
        SessionLogger.log(
            LabCategory.INPUT,
            "touch_back_target_no_input",
            // Nothing arrived. That is an absence of evidence for this session, never evidence of
            // absence in the sink - hence NOT_TESTED, per spec section 20.
            LabStatus.NOT_TESTED,
            logDetails(r) + mapOf(
                "note" to "NO INPUT OBSERVED within timeout; this does not prove the sink lacks a back channel",
            ),
        )
        advance()
    }

    private fun miss(idx: Int, ex: Float, ey: Float, skipped: Boolean) = TargetResult(
        index = idx,
        expectedX = ex,
        expectedY = ey,
        hit = false,
        skipped = skipped,
        receivedX = Float.NaN,
        receivedY = Float.NaN,
        sourceNames = "-",
        deviceName = "-",
        deviceId = -1,
        deviceIdentity = "-",
        newDevice = false,
        hook = "-",
        toolType = "-",
        uptimeDeltaMs = 0L,
        responseMs = SystemClock.uptimeMillis() - targetShownAt,
    )

    private fun advance() {
        val next = activeIndex + 1
        if (next >= total) {
            running = false
            finished = true
            activeIndex = -1
            remainingMs = 0L
            val a = analyse()
            SessionLogger.log(
                LabCategory.INPUT,
                "touch_back_run_complete",
                a.status,
                mapOf(
                    "targets" to a.total.toString(),
                    "attempted" to a.attempted.toString(),
                    "hits" to a.hits.toString(),
                    "skipped" to a.skipped.toString(),
                    "percent_reached" to a.percentReached.toString(),
                    "sources" to a.sources.joinToString("|").ifEmpty { "none" },
                    "devices" to a.devices.joinToString("|").ifEmpty { "none" },
                    "new_device_hits" to a.newDeviceHits.toString(),
                    "transform" to (a.transform?.oneLine() ?: a.transformNote),
                    "verdict" to a.headline,
                ),
            )
            SessionLogger.logAll(observations())
        } else {
            activeIndex = next
            targetShownAt = SystemClock.uptimeMillis()
            remainingMs = perTargetTimeoutMs
        }
    }

    private fun logDetails(r: TargetResult): Map<String, String> = mapOf(
        "target" to (r.index + 1).toString(),
        "expected_x" to String.format(Locale.US, "%.1f", r.expectedX),
        "expected_y" to String.format(Locale.US, "%.1f", r.expectedY),
        "received_x" to String.format(Locale.US, "%.1f", r.receivedX),
        "received_y" to String.format(Locale.US, "%.1f", r.receivedY),
        "result" to r.label,
        "source" to r.sourceNames,
        "device" to r.deviceName,
        "device_id" to r.deviceId.toString(),
        "hook" to r.hook,
        "tool_type" to r.toolType,
        "uptime_delta_ms" to r.uptimeDeltaMs.toString(),
        "response_ms" to r.responseMs.toString(),
    )

    fun analyse(): TouchBackAnalysis {
        val hitList = results.filter { it.hit }
        val skippedCount = results.count { it.skipped }
        val attempted = results.size - skippedCount
        val percent = if (attempted <= 0) 0 else (hitList.size * 100) / attempted
        val sources = hitList.flatMap { it.sourceNames.split("|") }.filter { it.isNotBlank() }.toSet()
        val devices = hitList.map { it.deviceName + " (id " + it.deviceId + ")" }.toSet()
        val newDeviceHits = hitList.count { it.newDevice }

        val transform = fitTransform(hitList)
        val transformNote = when {
            hitList.size < 3 -> "Fewer than 3 hits: a scale/offset fit would be meaningless."
            transform == null -> "Hits were collinear (all in one row or column); a per-axis fit is undetermined."
            else -> "Fitted over " + transform.n + " hits."
        }

        val status: LabStatus
        val headline: String
        val detail: String
        when {
            results.isEmpty() -> {
                status = LabStatus.NOT_TESTED
                headline = "TOUCH-BACK GRID NOT RUN"
                detail = "No targets have been presented yet."
            }
            newDeviceHits > 0 -> {
                status = LabStatus.OBSERVED
                headline = "BACK-CHANNEL INPUT OBSERVED"
                detail = newDeviceHits.toString() + " of " + hitList.size +
                    " hits arrived from an input device that was not present in the baseline " +
                    "snapshot: " + hitList.filter { it.newDevice }.map { it.deviceName }.toSet()
                        .joinToString(", ") + ". Sources: " + sources.joinToString(", ") + "."
            }
            hitList.isNotEmpty() -> {
                // Input arrived, but from hardware that was already there. We cannot tell a car-side
                // touch injected onto the built-in touchscreen apart from the tester's own finger,
                // so no back-channel claim is made either way.
                status = LabStatus.NOT_TESTED
                headline = "INPUT SEEN, BUT NOT ISOLATED FROM THIS PHONE"
                detail = "All " + hitList.size + " hits came from devices already present before the run (" +
                    devices.joinToString(", ") + "). That is indistinguishable from the tester " +
                    "touching the phone, so it is no evidence for or against a sink back channel."
            }
            else -> {
                status = LabStatus.NOT_TESTED
                headline = "NO INPUT OBSERVED"
                detail = "None of the " + attempted + " presented targets produced any input event in " +
                    "this session. This does NOT prove the sink lacks a back channel (UIBC or " +
                    "otherwise): it may be disabled, unnegotiated, or routed elsewhere."
            }
        }

        return TouchBackAnalysis(
            total = total,
            attempted = attempted,
            hits = hitList.size,
            skipped = skippedCount,
            percentReached = percent,
            sources = sources,
            devices = devices,
            newDeviceHits = newDeviceHits,
            transform = transform,
            transformNote = transformNote,
            status = status,
            headline = headline,
            detail = detail,
        )
    }

    /** Report-ready findings for the run in its current state. */
    fun observations(): List<Observation> {
        val a = analyse()
        val out = mutableListOf<Observation>()
        out += Observation(
            "input.touch_back.verdict",
            a.headline,
            a.status,
            a.detail,
            LabCategory.INPUT,
        )
        out += Observation(
            "input.touch_back.targets_reached",
            a.hits.toString() + "/" + a.attempted + " (" + a.percentReached + "%)",
            if (a.results0()) LabStatus.NOT_TESTED else LabStatus.OBSERVED,
            "Skipped targets are excluded from the denominator (" + a.skipped + " skipped).",
            LabCategory.INPUT,
        )
        out += Observation(
            "input.touch_back.sources_seen",
            a.sources.joinToString("|").ifEmpty { "none" },
            if (a.sources.isEmpty()) LabStatus.NOT_TESTED else LabStatus.OBSERVED,
            "Distinct decoded InputDevice sources that delivered a hit.",
            LabCategory.INPUT,
        )
        out += Observation(
            "input.touch_back.devices_seen",
            a.devices.joinToString("|").ifEmpty { "none" },
            if (a.devices.isEmpty()) LabStatus.NOT_TESTED else LabStatus.OBSERVED,
            "Input devices that delivered a hit, resolved via InputDevice.getDevice().",
            LabCategory.INPUT,
        )
        out += Observation(
            "input.touch_back.new_device_hits",
            a.newDeviceHits.toString(),
            if (a.newDeviceHits > 0) LabStatus.OBSERVED else LabStatus.NOT_TESTED,
            "Hits from an input device absent from the pre-run snapshot - the signature of a real back channel.",
            LabCategory.INPUT,
        )
        val t = a.transform
        out += if (t != null) {
            Observation(
                "input.touch_back.coordinate_transform",
                t.oneLine(),
                LabStatus.OBSERVED,
                if (t.isNearIdentity) {
                    "Near-identity mapping: received coordinates match the drawn grid centres 1:1."
                } else {
                    "Non-identity mapping: the received coordinates are scaled/offset from the drawn grid."
                },
                LabCategory.INPUT,
            )
        } else {
            Observation(
                "input.touch_back.coordinate_transform",
                "-",
                LabStatus.NOT_TESTED,
                a.transformNote,
                LabCategory.INPUT,
            )
        }
        results.forEach { r ->
            out += Observation(
                "input.touch_back.target_" + String.format(Locale.US, "%02d", r.index + 1),
                r.label + " " + (if (r.hit) {
                    String.format(
                        Locale.US,
                        "at (%.0f,%.0f) expected (%.0f,%.0f) via %s on %s after %d ms",
                        r.receivedX, r.receivedY, r.expectedX, r.expectedY,
                        r.sourceNames, r.deviceName, r.responseMs,
                    )
                } else {
                    "expected (" + r.expectedX.toInt() + "," + r.expectedY.toInt() + ")"
                }),
                if (r.hit) LabStatus.OBSERVED else LabStatus.NOT_TESTED,
                if (r.hit) null else "This session saw no back channel for this target; not proof the sink lacks one.",
                LabCategory.INPUT,
            )
        }
        return out
    }

    /**
     * Per-axis least squares. Returns null when there are fewer than three hits or when the hit
     * coordinates carry no spread on an axis, because a fit through a degenerate set would look
     * like evidence while being arithmetic noise.
     */
    private fun fitTransform(hits: List<TargetResult>): CoordinateTransform? {
        val usable = hits.filter {
            !it.receivedX.isNaN() && !it.receivedY.isNaN()
        }
        if (usable.size < 3) return null
        val x = usable.map { it.expectedX.toDouble() }
        val y = usable.map { it.expectedY.toDouble() }
        val rx = usable.map { it.receivedX.toDouble() }
        val ry = usable.map { it.receivedY.toDouble() }
        val fx = leastSquares(x, rx) ?: return null
        val fy = leastSquares(y, ry) ?: return null
        return CoordinateTransform(
            n = usable.size,
            scaleX = fx[0].toFloat(),
            offsetX = fx[1].toFloat(),
            rmsX = fx[2].toFloat(),
            scaleY = fy[0].toFloat(),
            offsetY = fy[1].toFloat(),
            rmsY = fy[2].toFloat(),
        )
    }

    /** Returns [slope, intercept, rms] or null when the independent variable has no variance. */
    private fun leastSquares(xs: List<Double>, ys: List<Double>): DoubleArray? {
        val n = xs.size
        if (n < 2) return null
        val mx = xs.average()
        val my = ys.average()
        var sxx = 0.0
        var sxy = 0.0
        for (i in 0 until n) {
            val dx = xs[i] - mx
            sxx += dx * dx
            sxy += dx * (ys[i] - my)
        }
        if (sxx < 1e-6) return null
        val slope = sxy / sxx
        val intercept = my - slope * mx
        var sse = 0.0
        for (i in 0 until n) {
            val e = ys[i] - (slope * xs[i] + intercept)
            sse += e * e
        }
        return doubleArrayOf(slope, intercept, sqrt(sse / n))
    }
}

/** True when the run has produced no target results at all. */
private fun TouchBackAnalysis.results0(): Boolean = attempted == 0 && skipped == 0
