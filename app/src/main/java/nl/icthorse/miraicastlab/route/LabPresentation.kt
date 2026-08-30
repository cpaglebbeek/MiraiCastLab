package nl.icthorse.miraicastlab.route

import android.app.Activity
import android.app.Presentation
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.SystemClock
import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import nl.icthorse.miraicastlab.core.LabCategory
import nl.icthorse.miraicastlab.core.LabStatus
import nl.icthorse.miraicastlab.core.Outcome
import nl.icthorse.miraicastlab.core.SessionLogger
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Route C, mechanism 2: `android.app.Presentation` on a chosen [Display].
 *
 * This is the OTHER way an ordinary app can put pixels on a secondary display, and it is a
 * completely different platform path from `ActivityOptions#setLaunchDisplayId` (route C1): a
 * Presentation is a Dialog whose window is created against another display's window token, not an
 * activity placed by the activity manager. The two can succeed and fail independently, which is
 * why this project measures them separately and never reports one as evidence for the other.
 *
 * What is rendered is a *stimulus*, not a product. It is deliberately loud, because the only
 * instrument for "did it reach the head unit" is a human in the driver's seat glancing at a screen
 * a metre away:
 *
 *  - the display id and display name in the largest type that fits, so the human can tell WHICH
 *    display they are looking at without touching the phone;
 *  - a bar sweeping across the screen and a 2 Hz blink box, so a frozen frame (a sink that latched
 *    one image and stopped) is distinguishable from a live one at a glance;
 *  - a frame counter, which is the same evidence in a form the app itself can read back (see
 *    [frames]) - C3 samples it to tell "still drawing" from "still showing but frozen";
 *  - a wall-clock timestamp, so a photograph of the head unit can be correlated with the JSONL
 *    evidence file afterwards;
 *  - a border and corner markers, which make overscan or cropping by the sink visible.
 *
 * Self-contained on purpose: it does not use the `scene` module, so a failure here is a failure of
 * the Presentation path and not of somebody else's renderer. Plain Canvas drawing rather than
 * Compose, because a Compose subtree inside a Dialog on a foreign display needs lifecycle and
 * saved-state owners that the Presentation window does not provide by itself, and a crash in that
 * plumbing would be indistinguishable from "the platform refused the display".
 *
 * @param outerContext MUST be an Activity context. A Presentation shown from an application
 *        context has no window token and the platform rejects it (BadTokenException) unless the app
 *        holds an overlay permission, which this app does not and will not request.
 * @param target the display to present on. Kept as a property rather than relying on
 *        `Presentation.getDisplay()` so there is no ambiguity with `Context.getDisplay()`.
 */
class LabPresentation(
    outerContext: Context,
    private val target: Display,
) : Presentation(outerContext, target) {

    private var view: TestPatternView? = null

    /** Frames actually drawn since the window was attached. Read from other threads by C3. */
    @Volatile
    var frames: Long = 0L
        private set

    /** Set when the platform tells us the display went away underneath us. */
    @Volatile
    var displayRemoved: Boolean = false
        private set

    /** How often the platform reported a change (resolution, rotation, state) on this display. */
    @Volatile
    var displayChanges: Int = 0
        private set

    /** elapsedRealtime at which the window was created; null until then. */
    @Volatile
    var shownAtElapsedMs: Long? = null
        private set

    /** Id of the display this presentation was constructed for. */
    val targetDisplayId: Int get() = runCatching { target.displayId }.getOrDefault(-1)

    /** Name of the display this presentation was constructed for. */
    val targetDisplayName: String get() = runCatching { target.name }.getOrDefault("?")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // getContext() on a Presentation is a context adjusted to the TARGET display, so metrics
        // and density come from the sink and not from the phone panel.
        val v = TestPatternView(context, targetDisplayId, targetDisplayName) { frames = it }
        view = v
        setContentView(
            v,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(BG))
            // Nothing here needs the phone to stay awake, but a sink that stops receiving because
            // the phone dozed would be logged as "the link dropped", which would be a wrong
            // finding. Holding the screen on removes that confound. No permission is required.
            runCatching { w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }
        shownAtElapsedMs = SystemClock.elapsedRealtime()
        SessionLogger.log(
            LabCategory.DISPLAY,
            "lab_presentation_created",
            LabStatus.OBSERVED,
            mapOf(
                "displayId" to targetDisplayId.toString(),
                "displayName" to targetDisplayName,
                "flags" to runCatching { DisplayRole.decodeFlags(target).joinToString("|") }
                    .getOrDefault("unreadable"),
            ),
        )
    }

    /**
     * The platform's own signal that the sink is gone. This is stronger evidence than polling
     * DisplayManager, because it is the display server telling us rather than us guessing.
     */
    override fun onDisplayRemoved() {
        displayRemoved = true
        SessionLogger.log(
            LabCategory.DISPLAY,
            "lab_presentation_display_removed",
            LabStatus.OBSERVED,
            mapOf(
                "displayId" to targetDisplayId.toString(),
                "framesDrawn" to frames.toString(),
                "note" to "The platform removed the display under a live Presentation. Which side " +
                    "ended the session (phone, link or head unit) is not visible from here.",
            ),
        )
        super.onDisplayRemoved()
    }

    override fun onDisplayChanged() {
        displayChanges++
        SessionLogger.log(
            LabCategory.DISPLAY,
            "lab_presentation_display_changed",
            LabStatus.OBSERVED,
            mapOf(
                "displayId" to targetDisplayId.toString(),
                "changeCount" to displayChanges.toString(),
                "framesDrawn" to frames.toString(),
            ),
        )
        super.onDisplayChanged()
    }

    override fun onStop() {
        SessionLogger.log(
            LabCategory.DISPLAY,
            "lab_presentation_stopped",
            LabStatus.OBSERVED,
            mapOf(
                "displayId" to targetDisplayId.toString(),
                "framesDrawn" to frames.toString(),
                "displayRemoved" to displayRemoved.toString(),
            ),
        )
        view?.stop()
        super.onStop()
    }

    private companion object {
        val BG = Color.BLACK
    }
}

/**
 * The stimulus itself. Deliberately dumb: one Canvas, no resources, no theme, no dependency on
 * anything that could fail for its own reasons.
 */
private class TestPatternView(
    context: Context,
    private val displayId: Int,
    private val displayName: String,
    private val onFrame: (Long) -> Unit,
) : View(context) {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val startedAtMs = SystemClock.elapsedRealtime()
    private var frameCount = 0L
    private var running = true

    /** Cached to keep per-frame allocation down; the clock only needs ~10 Hz resolution. */
    private var clockText = ""
    private var clockStampedAtMs = 0L

    fun stop() { running = false }

    override fun onDetachedFromWindow() {
        running = false
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        canvas.drawColor(Color.BLACK)
        val elapsed = SystemClock.elapsedRealtime() - startedAtMs

        // Border + corner blocks: if the head unit overscans or letterboxes, this is where it shows.
        val inset = h * 0.02f
        stroke.color = EDGE
        stroke.strokeWidth = maxOf(3f, h * 0.010f)
        canvas.drawRect(inset, inset, w - inset, h - inset, stroke)
        fill.color = EDGE
        val c = h * 0.06f
        canvas.drawRect(inset, inset, inset + c, inset + c, fill)
        canvas.drawRect(w - inset - c, inset, w - inset, inset + c, fill)
        canvas.drawRect(inset, h - inset - c, inset + c, h - inset, fill)
        canvas.drawRect(w - inset - c, h - inset - c, w - inset, h - inset, fill)

        // Motion. A still photograph of the head unit cannot fake a sweeping bar, and a sink that
        // latched one frame and stopped will show it parked.
        val barW = w * 0.07f
        val phase = (elapsed % SWEEP_MS).toFloat() / SWEEP_MS
        val x = phase * (w + barW) - barW
        fill.color = BAR
        canvas.drawRect(x, h * 0.60f, x + barW, h * 0.72f, fill)

        // 2 Hz blink box: a second, independent motion cue with a different period, so a display
        // refreshing at some odd divided rate cannot accidentally look static in both.
        fill.color = if ((elapsed / 500L) % 2L == 0L) ON else OFF
        canvas.drawRect(w * 0.42f, h * 0.76f, w * 0.58f, h * 0.86f, fill)

        line(canvas, "DISPLAY $displayId", w / 2f, h * 0.20f, h * 0.16f, HEAD)
        line(canvas, displayName.take(40), w / 2f, h * 0.30f, h * 0.070f, SUB)
        line(canvas, "${width} x ${height} px", w / 2f, h * 0.39f, h * 0.060f, SUB)
        line(canvas, "FRAME ${frameCount}", w / 2f, h * 0.50f, h * 0.085f, DATA)

        if (elapsed - clockStampedAtMs > 90L) {
            clockText = runCatching { CLOCK.format(Instant.now()) }.getOrDefault("--:--:--")
            clockStampedAtMs = elapsed
        }
        line(canvas, "$clockText   t+${elapsed / 1000L}s", w / 2f, h * 0.93f, h * 0.055f, SUB)
        line(canvas, "MiraiCast Lab  route C2  android.app.Presentation", w / 2f, h * 0.10f, h * 0.040f, SUB)

        frameCount++
        onFrame(frameCount)
        // Self-driving redraw. postInvalidateOnAnimation schedules the next frame on the choreographer
        // instead of re-entering draw, and no-ops once the view is detached.
        if (running) postInvalidateOnAnimation()
    }

    private fun line(canvas: Canvas, s: String, cx: Float, cy: Float, size: Float, color: Int) {
        text.textSize = size
        text.color = color
        canvas.drawText(s, cx, cy, text)
    }

    private companion object {
        const val SWEEP_MS = 2000L
        val HEAD = 0xFFFFE100.toInt()
        val SUB = 0xFF9CE8FF.toInt()
        val DATA = 0xFFFFFFFF.toInt()
        val BAR = 0xFFFF3DA5.toInt()
        val EDGE = 0xFF00FF6A.toInt()
        val ON = 0xFFFFFFFF.toInt()
        val OFF = 0xFF202020.toInt()
        val CLOCK: DateTimeFormatter =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
    }
}

/**
 * Owns at most one live [LabPresentation] and grades what happened when it was started.
 *
 * Exists so C2 is runnable both from [RouteC.run] and from a Composable: a Composable holds one of
 * these in `remember`, calls [start] from a button and [stop] from `onDispose`. A leaked
 * Presentation outlives the screen, keeps drawing on the sink, and silently contaminates the next
 * experiment - so [start] always tears down its predecessor first and [stop] is idempotent.
 *
 * All methods touch a Dialog window and must therefore be called on the main thread.
 */
class LabPresentationController {

    @Volatile
    private var current: LabPresentation? = null

    /** The live presentation, or null. C3 reads its frame counter through this. */
    val presentation: LabPresentation? get() = current

    val isShowing: Boolean get() = runCatching { current?.isShowing == true }.getOrDefault(false)

    /**
     * Attempts to show a [LabPresentation] on [displayId] and returns the graded C2 outcome.
     *
     * Grading discipline, which is the whole point:
     * - show() returns normally -> OBSERVED. Not CONFIRMED: "the window system accepted a window on
     *   that display" is not "the head unit displayed it". Only a human can supply the second half.
     * - SecurityException -> UNSUPPORTED. A documented refusal naming a permission we do not hold.
     * - InvalidDisplayException -> depends on whether the display still exists. Gone => NOT_TESTED
     *   (circumstance), still listed => UNSUPPORTED (the platform refused a display it still
     *   advertises). Collapsing those two would be exactly the error this project exists to avoid.
     * - anything else -> ERROR. A crash says nothing about the capability.
     */
    fun start(activity: Activity, displayId: Int): Outcome {
        val dm = activity.getSystemService(DisplayManager::class.java)
            ?: return Outcome(
                experimentId = "C2",
                status = LabStatus.UNSUPPORTED,
                observation = "getSystemService(DisplayManager::class.java) returned null.",
                conclusion = "No display service on this device, so no Presentation can be built. " +
                    "This is a property of the platform build, not of the head unit.",
                evidence = mapOf("requestedDisplayId" to displayId.toString()),
                nextStep = "Nothing further on route C is testable on this device.",
            )

        val display = runCatching { dm.getDisplay(displayId) }.getOrNull()
            ?: return Outcome(
                experimentId = "C2",
                status = LabStatus.NOT_TESTED,
                observation = "DisplayManager.getDisplay($displayId) returned null; the display we " +
                    "were asked to target is not currently listed.",
                conclusion = "Nothing is established about Presentation. The test could not run - " +
                    "this is not evidence that Presentation is unavailable.",
                evidence = mapOf(
                    "requestedDisplayId" to displayId.toString(),
                    "displaysNow" to runCatching { dm.displays.joinToString("|") { it.displayId.toString() + ":" + it.name } }
                        .getOrDefault("unreadable"),
                ),
                nextStep = "Start Smart View / Wireless DeX, wait for a display_added event on the " +
                    "Display test screen, then re-run C2 against that display id.",
            )

        // Never stack two presentations: the second would draw over the first and the frame counters
        // would be indistinguishable in the evidence file.
        stop()

        val flags = runCatching { DisplayRole.decodeFlags(display) }.getOrDefault(emptyList())
        val inPresentationCategory = runCatching {
            dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).any { it.displayId == displayId }
        }.getOrDefault(false)
        val baseEvidence = mapOf(
            "requestedDisplayId" to displayId.toString(),
            "displayName" to runCatching { display.name }.getOrDefault("?"),
            "displayFlags" to (flags.joinToString("|").ifEmpty { "none" }),
            "inPresentationCategory" to inPresentationCategory.toString(),
            "displayValid" to runCatching { display.isValid.toString() }.getOrDefault("unreadable"),
            "displayState" to runCatching { display.state.toString() }.getOrDefault("unreadable"),
        )

        val p = LabPresentation(activity, display)
        try {
            p.show()
        } catch (e: SecurityException) {
            SessionLogger.log(
                LabCategory.DISPLAY, "lab_presentation_refused", LabStatus.UNSUPPORTED,
                baseEvidence + ("exception" to describe(e)),
            )
            return Outcome(
                "C2", LabStatus.UNSUPPORTED,
                "Presentation.show() threw " + describe(e) + " on display " + displayId + ".",
                "The platform refuses a Presentation on this display to an app with our privileges. " +
                    "The refusal names what is missing; that is a real negative for the ORDINARY_APP " +
                    "tier and says nothing about higher tiers.",
                baseEvidence + ("exception" to describe(e)),
                "Read the exception message for the permission named, and record it in the privilege-tier analysis.",
            )
        } catch (e: WindowManager.InvalidDisplayException) {
            // The documented failure for "the display went away". But it is also what you get for a
            // display the app may not present on, so the two must be told apart by re-checking.
            val stillThere = runCatching { dm.getDisplay(displayId)?.isValid == true }.getOrDefault(false)
            val status = if (stillThere) LabStatus.UNSUPPORTED else LabStatus.NOT_TESTED
            SessionLogger.log(
                LabCategory.DISPLAY, "lab_presentation_invalid_display", status,
                baseEvidence + mapOf("exception" to describe(e), "displayStillListed" to stillThere.toString()),
            )
            return Outcome(
                "C2", status,
                "Presentation.show() threw " + describe(e) + "; immediately afterwards " +
                    "DisplayManager " + (if (stillThere) "still lists" else "no longer lists") +
                    " display " + displayId + " as valid.",
                if (stillThere) {
                    "The platform refused a Presentation on a display it still advertises. That is a " +
                        "genuine negative for this display at this privilege tier."
                } else {
                    "The display disappeared before or during show(). The test did not run; no claim " +
                        "is made about whether Presentation would have worked."
                },
                baseEvidence + mapOf("exception" to describe(e), "displayStillListed" to stillThere.toString()),
                if (stillThere) "Compare with C1 on the same display: if C1 lands and C2 refuses, the " +
                    "two mechanisms differ and only C1 is usable here."
                else "Re-establish the wireless session and re-run C2.",
            )
        } catch (t: Throwable) {
            SessionLogger.log(
                LabCategory.DISPLAY, "lab_presentation_error", LabStatus.ERROR,
                baseEvidence + ("exception" to describe(t)),
            )
            return Outcome(
                "C2", LabStatus.ERROR,
                "Presentation.show() threw " + describe(t) + ".",
                "The mechanism itself failed to execute. Nothing may be concluded about whether an " +
                    "app can present on this display.",
                baseEvidence + ("exception" to describe(t)),
                "Re-run C2. If it throws BadTokenException the calling context was not an Activity, " +
                    "which is a defect in the caller and not a finding about the display.",
            )
        }

        current = p
        val showing = runCatching { p.isShowing }.getOrDefault(false)
        SessionLogger.log(
            LabCategory.DISPLAY, "lab_presentation_shown", LabStatus.OBSERVED,
            baseEvidence + ("isShowing" to showing.toString()),
        )
        return Outcome(
            "C2", LabStatus.OBSERVED,
            "Presentation.show() returned without throwing on display " + displayId + " (\"" +
                (baseEvidence["displayName"] ?: "?") + "\"); isShowing=" + showing +
                "; display flags=" + (baseEvidence["displayFlags"] ?: "none") +
                "; inPresentationCategory=" + inPresentationCategory + ".",
            "The window system accepted a window from an ordinary app on this display. That is all. " +
                "It is NOT evidence that anything appeared on the head unit, and not evidence about " +
                "what transport is behind this display. The on-screen frame counter and sweeping bar " +
                "exist so a human can supply the second half of the finding, and C3 reads the same " +
                "counter back to tell a live surface from a frozen one.",
            baseEvidence + ("isShowing" to showing.toString()),
            "Look at the head unit. Record what you see with a marker: nothing / a frozen frame / " +
                "a moving bar. Then run C3 to time how long it holds.",
        )
    }

    /** Dismisses the live presentation if there is one. Safe to call any number of times. */
    fun stop() {
        val p = current ?: return
        current = null
        val frames = p.frames
        runCatching { p.dismiss() }
            .onFailure {
                SessionLogger.log(
                    LabCategory.DISPLAY, "lab_presentation_dismiss_failed", LabStatus.ERROR,
                    mapOf("error" to describe(it)),
                )
            }
        SessionLogger.log(
            LabCategory.DISPLAY, "lab_presentation_dismissed", LabStatus.OBSERVED,
            mapOf(
                "displayId" to p.targetDisplayId.toString(),
                "framesDrawn" to frames.toString(),
                "displayRemoved" to p.displayRemoved.toString(),
            ),
        )
    }

    private companion object {
        fun describe(t: Throwable): String =
            t::class.java.simpleName + ": " + (t.message ?: "no message")
    }
}
