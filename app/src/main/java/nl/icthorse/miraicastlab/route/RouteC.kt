package nl.icthorse.miraicastlab.route

import android.app.Activity
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.hardware.display.DisplayManager
import android.os.SystemClock
import android.view.Display
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import nl.icthorse.miraicastlab.core.Experiment
import nl.icthorse.miraicastlab.core.LabCategory
import nl.icthorse.miraicastlab.core.LabStatus
import nl.icthorse.miraicastlab.core.LogRecord
import nl.icthorse.miraicastlab.core.Outcome
import nl.icthorse.miraicastlab.core.PrivilegeTier
import nl.icthorse.miraicastlab.core.Route
import nl.icthorse.miraicastlab.core.SessionLogger
import nl.icthorse.miraicastlab.scene.PresentationHostActivity

/**
 * Route C: **can this app put its OWN content on a display that something else already
 * established?**
 *
 * Route C is the interesting half of the owner's hypothesis, and it is deliberately split into two
 * mechanisms that share nothing but a display id:
 *
 *  - **C1** `ActivityOptions#setLaunchDisplayId` - the activity manager places one of our
 *    activities on a chosen display.
 *  - **C2** `android.app.Presentation` - the window manager creates a dialog window against a
 *    chosen display's token. See [LabPresentation].
 *
 * They can succeed and fail independently. Reporting one as evidence for the other would destroy
 * the finding, so this object never aggregates them into a single verdict, and neither does the
 * report: four outcomes go in, four outcomes come out.
 *
 * **C3** then asks whether whatever landed *stays* landed - a session that drops after three
 * seconds is a different finding from one that holds - and **C4** repeats C1 against
 * `DEFAULT_DISPLAY` as a control, which is the only way to separate "the mechanism does not work"
 * from "the mechanism works and this display is the constraint".
 *
 * ### What this file deliberately does not assume
 * Nothing here assumes that the targeted display *is* the DeX/Miracast surface. That question
 * belongs to [DisplayRole], which never grades a remote display above INFERRED, and the answer to
 * it is not needed to run these experiments: C1..C4 measure what the *platform* does with a display
 * id. Whether pixels reached a Toyota head unit is a separate observation that only a human in the
 * car can make, and every conclusion below stops short of claiming it.
 *
 * ### Why the payload is our own activity
 * `PresentationHostActivity` is `exported=false` and runs in our own uid. Using it means no
 * cross-app launch permission question can contaminate the C1 measurement. Launching *someone
 * else's* activity on a display is route D's question, and mixing the two is exactly how a project
 * ends up believing it proved something it did not. This is the one deliberate cross-module import
 * in `route`, sanctioned because the scene activity is the project's standard secondary-display
 * payload and already reports the display it actually landed on.
 */
object RouteC {

    // The event PresentationHostActivity writes about itself in onCreate, carrying the display it
    // really got. This string is the contract between the two files; if it changes there, C1 and C4
    // silently stop measuring, so it is named once here.
    private const val HOST_CREATED = "presentation_host_created"
    private const val HOST_DESTROYED = "presentation_host_destroyed"

    /** How long to wait for the launched activity to report itself before giving up. */
    private const val LAUNCH_SETTLE_MS = 5_000L
    private const val POLL_MS = 100L

    /** C3's observation window and sample interval. */
    private const val SURVIVAL_MS = 10_000L
    private const val SURVIVAL_SAMPLE_MS = 1_000L

    // ------------------------------------------------------------------ experiments

    /**
     * The four experiments, hypotheses written before anything runs. Each hypothesis names what
     * would falsify it, because a hypothesis that cannot fail is a slogan.
     */
    val experiments: List<Experiment> = listOf(
        Experiment(
            id = "C1",
            route = Route.C,
            title = "Place our own activity on a chosen display with setLaunchDisplayId()",
            hypothesis = "An ordinary, unprivileged app can place its OWN activity on a specified " +
                "non-default display by passing ActivityOptions.setLaunchDisplayId(id) to " +
                "startActivity. Falsified by any of: startActivity throws; no activity instance is " +
                "created; or the activity is created but reports a displayId different from the one " +
                "requested, i.e. the platform accepted the option and silently overrode it.",
            method = "ActivityOptions.makeBasic().setLaunchDisplayId(id).toBundle() -> " +
                "Context.startActivity(Intent(ctx, PresentationHostActivity), options)",
            procedure = "Record the current SessionLogger sequence number. Launch " +
                "PresentationHostActivity (exported=false, same uid) with FLAG_ACTIVITY_NEW_TASK | " +
                "FLAG_ACTIVITY_MULTIPLE_TASK so a fresh instance runs onCreate rather than a " +
                "singleTop instance being reused. Wait up to 5 s for the new " +
                "'$HOST_CREATED' record and compare the displayId it reports with the requested id. " +
                "Also record the target activity's resize mode and whether the display is in " +
                "DISPLAY_CATEGORY_PRESENTATION, because both influence whether the platform honours " +
                "the launch display id.",
            preconditions = listOf(
                "The app has a visible activity, so background-activity-launch restrictions do not apply.",
                "A display with the requested id is listed by DisplayManager. If it is not, this " +
                    "becomes a fallback test and is graded accordingly, not as a failure of the API.",
            ),
            tier = PrivilegeTier.ORDINARY_APP,
            nextOnPass = listOf("C3", "C2", "D1"),
            nextOnFail = listOf("C4", "C2"),
        ),
        Experiment(
            id = "C2",
            route = Route.C,
            title = "Put a window on a chosen display with android.app.Presentation",
            hypothesis = "An ordinary app can create and show an android.app.Presentation on a " +
                "non-default display from an Activity context. Falsified by show() throwing: " +
                "SecurityException (a named permission we do not hold), " +
                "WindowManager.InvalidDisplayException (the platform refuses this display), or any " +
                "other throwable. Explicitly NOT part of this hypothesis: that the head unit renders " +
                "what is shown - the window system accepting a window and a screen in a car showing " +
                "an image are two different claims.",
            method = "android.app.Presentation(activityContext, display).show(); custom Canvas view",
            procedure = "Resolve the display via DisplayManager.getDisplay(id), record its flags and " +
                "DISPLAY_CATEGORY_PRESENTATION membership (FLAG_PRESENTATION and FLAG_PRIVATE are " +
                "what decide whether a Presentation is permitted), then construct LabPresentation " +
                "and call show() inside a typed catch. On InvalidDisplayException, immediately " +
                "re-check whether the display is still listed and valid: 'the display vanished' is " +
                "NOT_TESTED, 'the platform refused a display it still advertises' is UNSUPPORTED. " +
                "The presentation draws a frame counter, a sweeping bar and a wall clock so a human " +
                "can tell a live surface from a frozen one and so C3 can read the counter back.",
            preconditions = listOf(
                "The calling context is an Activity. A Presentation from an application context has " +
                    "no window token and would fail for a reason that has nothing to do with the display.",
                "A display with the requested id is listed by DisplayManager.",
            ),
            tier = PrivilegeTier.ORDINARY_APP,
            nextOnPass = listOf("C3"),
            nextOnFail = listOf("C1", "C4"),
        ),
        Experiment(
            id = "C3",
            route = Route.C,
            title = "Does the placed content survive, and does it keep drawing?",
            hypothesis = "Once content is on the display, it stays there and keeps being drawn for " +
                "at least 10 s without further interaction. Falsified by: the display disappearing; " +
                "the host activity being destroyed; the presentation stopping; or the presentation " +
                "remaining 'showing' while its frame counter stops advancing, which is a frozen " +
                "surface and a materially different finding from a dropped one.",
            method = "Timed sampling of DisplayManager.getDisplay(id).isValid, the absence of a " +
                "'$HOST_DESTROYED' record, Presentation.isShowing and LabPresentation.frames",
            procedure = "After C1 and C2 have run, sample every 1 s for 10 s and record a timeline. " +
                "Only what C1 or C2 actually placed is sampled; if neither placed anything the " +
                "outcome is NOT_TESTED, because there is nothing whose survival could be measured. " +
                "Note for whoever is watching the head unit: if C1 and C2 both succeeded on the same " +
                "display, the Presentation window layers ABOVE the activity, so the pattern visible " +
                "in the car is C2's. The two are still told apart here, because they are sampled " +
                "through separate signals rather than by looking.",
            preconditions = listOf("C1 or C2 placed something on the display."),
            tier = PrivilegeTier.ORDINARY_APP,
            nextOnPass = listOf("D1"),
            nextOnFail = listOf("C1", "C2"),
        ),
        Experiment(
            id = "C4",
            route = Route.C,
            title = "Control: the same launch against DEFAULT_DISPLAY",
            hypothesis = "The C1 mechanism itself works on this device, independently of any " +
                "external display. Falsified if the same launch against DEFAULT_DISPLAY also fails " +
                "or is also redirected - in which case C1's result says nothing about the external " +
                "display, because the instrument is broken rather than the target.",
            method = "Identical to C1 with id = Display.DEFAULT_DISPLAY",
            procedure = "Run the C1 procedure verbatim against DEFAULT_DISPLAY, then compare. " +
                "C4 passing while C1 fails isolates the constraint to the DISPLAY; both failing " +
                "isolates it to the mechanism; both passing means the launch-display-id option is " +
                "honoured generally on this build. Note that this control brings an activity to the " +
                "front of the phone screen: press Back to return to the lab.",
            preconditions = listOf("None. This is the control and must be runnable in every configuration."),
            tier = PrivilegeTier.ORDINARY_APP,
            nextOnPass = listOf("C1"),
            nextOnFail = listOf("C2"),
        ),
    )

    // ------------------------------------------------------------------ runner

    /**
     * Runs C1 -> C2 -> C3 -> C4 in that order and returns one [Outcome] per experiment, always four,
     * always in that order, whatever happened.
     *
     * Order matters: C3 can only sample what C1 and C2 left behind, and C4 runs last because it
     * deliberately brings an activity to the front of the phone panel, which would otherwise sit on
     * top of the tester for the rest of the run.
     *
     * @param targetDisplayId the display route C should aim at - typically
     *        `DisplayRole.preferredTarget(context)?.displayId`.
     */
    suspend fun run(context: Context, targetDisplayId: Int): List<Outcome> {
        val activity = context.findActivity()
        SessionLogger.log(
            LabCategory.DISPLAY,
            "route_c_run_started",
            LabStatus.OBSERVED,
            mapOf(
                "targetDisplayId" to targetDisplayId.toString(),
                "hasActivityContext" to (activity != null).toString(),
                "displays" to describeDisplays(context),
                "targetRole" to describeTargetRole(context, targetDisplayId),
            ),
        )

        // --- C1 -----------------------------------------------------------------
        val c1 = launchOnDisplay(context, "C1", targetDisplayId, controlRun = false, c1ForCompare = null)
        record(c1.outcome)

        // --- C2 -----------------------------------------------------------------
        val controller = LabPresentationController()
        val c2 = if (activity == null) {
            Outcome(
                "C2", LabStatus.NOT_TESTED,
                "No Activity context was available to RouteC.run(); a Presentation was never constructed.",
                "Nothing is established about the Presentation path. This is a limitation of how the " +
                    "run was invoked, not a property of the platform or of the display.",
                mapOf("requestedDisplayId" to targetDisplayId.toString()),
                "Re-run route C from the Route C screen, which passes its Activity context.",
            )
        } else {
            // Dialog windows are main-thread only.
            withContext(Dispatchers.Main) { controller.start(activity, targetDisplayId) }
        }
        record(c2)

        // --- C3 -----------------------------------------------------------------
        val c3 = survival(context, targetDisplayId, c1, c2, controller)
        record(c3)

        // The presentation must not outlive the run: a leaked Presentation keeps drawing on the sink
        // and would be misread as C1's activity during the next experiment.
        withContext(Dispatchers.Main) { controller.stop() }

        // --- C4 -----------------------------------------------------------------
        val c4 = launchOnDisplay(
            context, "C4", Display.DEFAULT_DISPLAY, controlRun = true, c1ForCompare = c1.outcome,
        )
        record(c4.outcome)

        SessionLogger.log(
            LabCategory.DISPLAY,
            "route_c_run_finished",
            LabStatus.OBSERVED,
            mapOf(
                "C1" to c1.outcome.status.name,
                "C2" to c2.status.name,
                "C3" to c3.status.name,
                "C4" to c4.outcome.status.name,
                "cleanup" to "The C2 presentation was dismissed by this run. The C1 and C4 activities " +
                    "were NOT: an app cannot finish an activity it launched into another task. They " +
                    "are still on their displays - dismiss them with Back before running route D, or " +
                    "route D will be measuring a screen this run left behind.",
            ),
        )
        return listOf(c1.outcome, c2, c3, c4.outcome)
    }

    // ------------------------------------------------------------------ C1 / C4

    /** What a launch attempt left behind, so C3 can sample it without re-deriving anything. */
    private data class LaunchResult(
        val outcome: Outcome,
        /** SessionLogger sequence of the '$HOST_CREATED' record, if one appeared. */
        val createdSeq: Long?,
        /** The display the activity said it landed on, if it said. */
        val landedDisplayId: Int?,
    )

    /**
     * The shared body of C1 and C4.
     *
     * The single most important line in this function is that **a successful startActivity is not a
     * success**. `setLaunchDisplayId` is a request; the activity manager may honour it, may ignore
     * it, and reports neither. The only trustworthy answer comes from the launched activity saying
     * where it woke up, which is why we wait for its own log record rather than trusting the
     * absence of an exception.
     */
    private suspend fun launchOnDisplay(
        context: Context,
        experimentId: String,
        requestedId: Int,
        controlRun: Boolean,
        c1ForCompare: Outcome?,
    ): LaunchResult {
        val dm = context.getSystemService(DisplayManager::class.java)
        val display = runCatching { dm?.getDisplay(requestedId) }.getOrNull()
        val flags = display?.let { runCatching { DisplayRole.decodeFlags(it) }.getOrDefault(emptyList()) }
            ?: emptyList()
        val inPresentationCategory = runCatching {
            dm?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
                ?.any { it.displayId == requestedId } == true
        }.getOrDefault(false)
        val resize = resizeMode(context)

        val evidence = linkedMapOf(
            "requestedDisplayId" to requestedId.toString(),
            "displayListed" to (display != null).toString(),
            "displayName" to (display?.let { runCatching { it.name }.getOrDefault("?") } ?: "-"),
            "displayFlags" to (flags.joinToString("|").ifEmpty { "none" }),
            "inPresentationCategory" to inPresentationCategory.toString(),
            "targetActivityResizeMode" to resize.first,
            "targetActivityResizeModeSource" to resize.second,
            "isControl" to controlRun.toString(),
            // With no external display, C1 already aimed at DEFAULT_DISPLAY and the "control" is a
            // repeat of it. Saying so in the evidence stops a reader treating it as a comparison.
            "controlDuplicatesC1" to (
                controlRun && c1ForCompare?.evidence?.get("requestedDisplayId") == requestedId.toString()
                ).toString(),
        )

        val baselineSeq = maxSeq()
        val launchedAt = SystemClock.elapsedRealtime()
        try {
            val intent = Intent(context, PresentationHostActivity::class.java)
                // MULTIPLE_TASK forces a fresh instance: with launchMode=singleTop an existing
                // instance would be reused, onCreate would not run, no record would appear, and we
                // would misread "reused" as "never launched".
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            val options = ActivityOptions.makeBasic().setLaunchDisplayId(requestedId)
            context.startActivity(intent, options.toBundle())
            SessionLogger.log(
                LabCategory.DISPLAY, "route_c_launch_requested", LabStatus.OBSERVED,
                evidence + ("experiment" to experimentId),
            )
        } catch (e: SecurityException) {
            // A documented refusal naming what we lack. That is a real negative at this tier.
            val ev = evidence + ("exception" to describe(e))
            SessionLogger.log(LabCategory.DISPLAY, "route_c_launch_refused", LabStatus.UNSUPPORTED, ev)
            return LaunchResult(
                Outcome(
                    experimentId, LabStatus.UNSUPPORTED,
                    "startActivity with setLaunchDisplayId($requestedId) threw " + describe(e) + ".",
                    "The platform refuses this launch to an app with our privileges, and names what " +
                        "is missing. A real negative for the ORDINARY_APP tier on this display; it " +
                        "says nothing about adb, Shizuku or system-app tiers, which are analysis only " +
                        "in this build.",
                    ev,
                    "Record the permission named in the exception in the privilege-tier analysis, then run C2.",
                ),
                null, null,
            )
        } catch (t: Throwable) {
            val ev = evidence + ("exception" to describe(t))
            SessionLogger.log(LabCategory.DISPLAY, "route_c_launch_error", LabStatus.ERROR, ev)
            return LaunchResult(
                Outcome(
                    experimentId, LabStatus.ERROR,
                    "startActivity with setLaunchDisplayId($requestedId) threw " + describe(t) + ".",
                    "The mechanism failed to execute. Nothing may be concluded about whether an app " +
                        "can place an activity on this display.",
                    ev,
                    "Re-run. If it throws again with the same type, that type is itself the finding.",
                ),
                null, null,
            )
        }

        val created = awaitRecord(baselineSeq, HOST_CREATED, LAUNCH_SETTLE_MS)
        val waitedMs = SystemClock.elapsedRealtime() - launchedAt
        val actualRaw = created?.details?.get("displayId")
        val actualId = actualRaw?.toIntOrNull()
        val createdSeq = created?.details?.get("seq")?.toLongOrNull()
        val ev = evidence + linkedMapOf(
            "waitedMs" to waitedMs.toString(),
            "hostCreatedRecord" to (created != null).toString(),
            "reportedDisplayId" to (actualRaw ?: "-"),
        )

        return when {
            created == null -> {
                SessionLogger.log(LabCategory.DISPLAY, "route_c_launch_unobserved", LabStatus.NOT_TESTED, ev)
                LaunchResult(
                    Outcome(
                        experimentId, LabStatus.NOT_TESTED,
                        "startActivity returned normally, but no '$HOST_CREATED' record appeared " +
                            "within ${LAUNCH_SETTLE_MS} ms.",
                        "The measurement did not complete. The activity may have been created without " +
                            "logging, an existing instance may have been reused despite " +
                            "FLAG_ACTIVITY_MULTIPLE_TASK, or the launch may have been dropped. This is " +
                            "explicitly NOT evidence that setLaunchDisplayId is unsupported.",
                        ev,
                        "Check the Display test screen for a scene already running, dismiss it, and re-run.",
                    ),
                    null, null,
                )
            }

            actualId == null -> {
                SessionLogger.log(LabCategory.DISPLAY, "route_c_launch_unreadable", LabStatus.NOT_TESTED, ev)
                LaunchResult(
                    Outcome(
                        experimentId, LabStatus.NOT_TESTED,
                        "An activity was created (record '$HOST_CREATED' after ${waitedMs} ms) but it " +
                            "reported its display as \"" + actualRaw + "\", which is not a display id.",
                        "The launch happened; the question this experiment asks - requested id versus " +
                            "actual id - could not be answered. No claim either way.",
                        ev,
                        "The host activity could not read its own display. Re-run; if it repeats, that " +
                            "read path is the defect and must be fixed before C1 means anything.",
                    ),
                    createdSeq, null,
                )
            }

            actualId == requestedId -> {
                SessionLogger.log(LabCategory.DISPLAY, "route_c_launch_landed", LabStatus.OBSERVED, ev)
                LaunchResult(
                    Outcome(
                        experimentId, LabStatus.OBSERVED,
                        "Requested display $requestedId; the launched activity reported displayId=" +
                            "$actualId after ${waitedMs} ms. Display flags: " +
                            (ev["displayFlags"] ?: "none") + "; inPresentationCategory=" +
                            inPresentationCategory + "; target activity resize mode: " + resize.first + ".",
                        if (controlRun) {
                            "The launch-display-id mechanism itself works on this device build. " +
                                "As a control this establishes only that the instrument is sound; it " +
                                "says nothing about any external display." +
                                comparisonNote(c1ForCompare, controlPassed = true)
                        } else {
                            "The platform honoured setLaunchDisplayId for OUR OWN activity on this " +
                                "display. That is a statement about the activity manager, not about " +
                                "the head unit: it does not establish that the display is a Miracast " +
                                "or DeX surface, that anything appeared in the car, or that another " +
                                "app could be launched there - the last of those is route D's question."
                        },
                        ev,
                        if (controlRun) "Compare with C1 to locate the constraint."
                        else "Run C3 to find out whether it stays, and look at the head unit to find " +
                            "out whether anything arrived there.",
                    ),
                    createdSeq, actualId,
                )
            }

            else -> {
                // The API accepted an id and the platform used a different one, without telling us.
                SessionLogger.log(
                    LabCategory.DISPLAY, "route_c_launch_redirected", LabStatus.OBSERVED,
                    ev + ("redirect" to "$requestedId->$actualId"),
                )
                LaunchResult(
                    Outcome(
                        experimentId, LabStatus.OBSERVED,
                        "Requested display $requestedId; the launched activity reported displayId=" +
                            "$actualId after ${waitedMs} ms. startActivity threw nothing. Display " +
                            "flags: " + (ev["displayFlags"] ?: "none") + "; inPresentationCategory=" +
                            inPresentationCategory + "; target activity resize mode: " + resize.first + ".",
                        "The API accepted the display id and the platform silently overrode it. This " +
                            "is a major finding and the reason 'no exception' is never treated as " +
                            "success here: an app can request a display and be redirected without any " +
                            "error surface. Candidate causes to separate next, not to assume: the " +
                            "display is not in DISPLAY_CATEGORY_PRESENTATION (" +
                            inPresentationCategory + "), the display is private or untrusted and " +
                            "refuses foreign activities, or the activity is not resizeable (" +
                            resize.first + "). Which of those it is has not been established." +
                            if (controlRun) {
                                " This was the CONTROL, against the built-in display: the override " +
                                    "happens even there, so C1's result cannot be attributed to the " +
                                    "external display. The mechanism is not honoured on this build at all."
                            } else {
                                ""
                            },
                        ev + ("redirect" to "$requestedId->$actualId"),
                        "Run C2: the Presentation path uses a different platform mechanism and may " +
                            "not be subject to the same override. Then compare with C4.",
                    ),
                    createdSeq, actualId,
                )
            }
        }
    }

    /** The C4-vs-C1 differential, appended to C4's conclusion. This is what a control is for. */
    private fun comparisonNote(c1: Outcome?, controlPassed: Boolean): String {
        if (c1 == null) return ""
        if (c1.evidence["requestedDisplayId"] == Display.DEFAULT_DISPLAY.toString()) {
            return " C1 targeted DEFAULT_DISPLAY too - there was no external display to aim at - so " +
                "this control duplicates C1 rather than contrasting with it. It shows the mechanism " +
                "is repeatable on the built-in panel and establishes nothing about a remote display."
        }
        val c1Landed = c1.status == LabStatus.OBSERVED &&
            c1.evidence["reportedDisplayId"] == c1.evidence["requestedDisplayId"]
        return when {
            !controlPassed -> ""
            c1Landed -> " C1 also landed on its requested display, so the option is honoured " +
                "generally on this build and the external display imposed no extra constraint."
            c1.status.isSilent -> " C1 did not produce a usable measurement, so no comparison is " +
                "possible; the control alone proves only that the instrument works."
            else -> " C1 against display " + (c1.evidence["requestedDisplayId"] ?: "?") +
                " did NOT land as requested while this control did. The mechanism is therefore " +
                "sound and the constraint lies with that display, not with setLaunchDisplayId. " +
                "Which property of the display causes it is not established here."
        }
    }

    // ------------------------------------------------------------------ C3

    /**
     * Timed survival sampling.
     *
     * Three independent signals, kept separate because they fail differently:
     *  1. does DisplayManager still list the display as valid;
     *  2. has a '$HOST_DESTROYED' record appeared after our activity was created;
     *  3. is the Presentation still showing, and is its frame counter still advancing.
     *
     * (3) is the one that catches the nastiest case: a window that is still 'showing' while nothing
     * is being drawn. A frozen surface and a dropped surface look identical to a naive check and are
     * completely different findings.
     */
    private suspend fun survival(
        context: Context,
        targetDisplayId: Int,
        c1: LaunchResult,
        c2: Outcome,
        controller: LabPresentationController,
    ): Outcome {
        val hostPlaced = c1.createdSeq != null
        // Dialog state belongs to the main thread; every read of it hops there rather than
        // racing the window it is measuring.
        val presentationPlaced = c2.status == LabStatus.OBSERVED &&
            withContext(Dispatchers.Main) { controller.isShowing }
        if (!hostPlaced && !presentationPlaced) {
            return Outcome(
                "C3", LabStatus.NOT_TESTED,
                "Neither C1 nor C2 placed anything on display $targetDisplayId, so there was nothing " +
                    "whose survival could be sampled.",
                "No claim about persistence. Absence of content is not evidence that content would " +
                    "not persist.",
                mapOf(
                    "targetDisplayId" to targetDisplayId.toString(),
                    "c1Status" to c1.outcome.status.name,
                    "c2Status" to c2.status.name,
                ),
                "Get C1 or C2 to place something first, then run C3.",
            )
        }

        val dm = context.getSystemService(DisplayManager::class.java)
        val timeline = StringBuilder()
        var displayLostAtMs: Long? = null
        var hostDestroyedAtMs: Long? = null
        var presentationStoppedAtMs: Long? = null
        var framesFrozenFromMs: Long? = null
        var lastFrames = withContext(Dispatchers.Main) { controller.presentation?.frames ?: -1L }
        var framesAtStart = lastFrames
        var framesAtEnd = lastFrames

        val start = SystemClock.elapsedRealtime()
        var elapsed = 0L
        while (elapsed < SURVIVAL_MS) {
            val d = runCatching { dm?.getDisplay(targetDisplayId) }.getOrNull()
            val displayOk = runCatching { d != null && d.isValid }.getOrDefault(false)
            val destroyed = c1.createdSeq?.let { seq ->
                recordsAfter(seq).any { it.event == HOST_DESTROYED }
            } ?: false
            val sample = withContext(Dispatchers.Main) {
                controller.isShowing to (controller.presentation?.frames ?: -1L)
            }
            val showing = sample.first
            val frames = sample.second

            if (!displayOk && displayLostAtMs == null) displayLostAtMs = elapsed
            if (destroyed && hostDestroyedAtMs == null) hostDestroyedAtMs = elapsed
            if (presentationPlaced && !showing && presentationStoppedAtMs == null) {
                presentationStoppedAtMs = elapsed
            }
            if (presentationPlaced && showing && frames == lastFrames && framesFrozenFromMs == null &&
                elapsed > 0L
            ) {
                framesFrozenFromMs = elapsed
            }
            if (frames != lastFrames) framesFrozenFromMs = null
            lastFrames = frames
            framesAtEnd = frames

            timeline.append("t=").append(elapsed / 1000).append("s display=")
                .append(if (displayOk) "yes" else "no")
                .append(" host=").append(if (!hostPlaced) "n/a" else if (destroyed) "destroyed" else "alive")
                .append(" pres=").append(if (!presentationPlaced) "n/a" else if (showing) "showing" else "gone")
                .append(" frames=").append(if (frames < 0) "n/a" else frames.toString())
                .append('\n')

            delay(SURVIVAL_SAMPLE_MS)
            elapsed = SystemClock.elapsedRealtime() - start
        }

        if (framesAtStart < 0) framesAtStart = 0
        val heldDisplay = displayLostAtMs == null
        val heldHost = hostPlaced && hostDestroyedAtMs == null
        val heldPresentation = presentationPlaced && presentationStoppedAtMs == null
        val drawing = presentationPlaced && framesAtEnd > framesAtStart && framesFrozenFromMs == null

        val evidence = linkedMapOf(
            "targetDisplayId" to targetDisplayId.toString(),
            "windowMs" to SURVIVAL_MS.toString(),
            "sampleIntervalMs" to SURVIVAL_SAMPLE_MS.toString(),
            "displayLostAtMs" to (displayLostAtMs?.toString() ?: "-"),
            "hostDestroyedAtMs" to (hostDestroyedAtMs?.toString() ?: "-"),
            "presentationStoppedAtMs" to (presentationStoppedAtMs?.toString() ?: "-"),
            "framesFrozenFromMs" to (framesFrozenFromMs?.toString() ?: "-"),
            "framesDrawn" to (framesAtEnd - framesAtStart).coerceAtLeast(0L).toString(),
            "timeline" to timeline.toString().trim(),
        )

        val observation = buildString {
            append("Sampled every ").append(SURVIVAL_SAMPLE_MS).append(" ms for ")
                .append(SURVIVAL_MS).append(" ms. Display ").append(targetDisplayId)
                .append(if (heldDisplay) " remained valid throughout" else " stopped being valid at t=" + displayLostAtMs + " ms")
            if (hostPlaced) {
                append("; the C1 activity ")
                    .append(if (heldHost) "was not reported destroyed" else "was reported destroyed at t=" + hostDestroyedAtMs + " ms")
            }
            if (presentationPlaced) {
                append("; the C2 presentation ")
                    .append(if (heldPresentation) "was still showing at the end" else "stopped showing at t=" + presentationStoppedAtMs + " ms")
                append(" and drew ").append((framesAtEnd - framesAtStart).coerceAtLeast(0L)).append(" frames")
                framesFrozenFromMs?.let { append(", with the counter static from t=").append(it).append(" ms") }
            }
            append('.')
        }

        val conclusion = when {
            !heldDisplay ->
                "The display went away during the observation window, so whatever was placed did not " +
                    "have 10 s to be observed. Which side ended it - the phone, the wireless link or " +
                    "the head unit - is not visible from inside an app, and is not claimed here."
            presentationPlaced && !drawing && heldPresentation ->
                "The window survived but stopped being drawn. A still surface and a lost surface are " +
                    "different failures, and this is the first: something suppressed rendering while " +
                    "the window remained. Cause not established; candidates to test separately are " +
                    "display doze, the sink pausing the stream, and the window losing focus."
            heldDisplay && (heldHost || heldPresentation) ->
                "What was placed stayed placed for the full 10 s window, by the signals an app can " +
                    "see. This is about the phone's own bookkeeping only: it is not evidence that the " +
                    "head unit kept showing anything, and a longer session may still drop. Whether " +
                    "the picture held in the car is a separate, human observation."
            else ->
                "The placed content did not survive the window. The timeline in the evidence records " +
                    "when each signal changed; no cause is claimed from these signals alone."
        }

        SessionLogger.log(LabCategory.DISPLAY, "route_c_survival_sampled", LabStatus.OBSERVED, evidence)

        return Outcome(
            "C3", LabStatus.OBSERVED, observation, conclusion, evidence,
            if (heldDisplay) "Extend the window to a full drive-length session before claiming stability, " +
                "and mark by hand what the head unit showed during the same 10 s."
            else "Re-establish the session and re-run C1/C2, then C3 again.",
        )
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Resize mode of the launch target.
     *
     * `ActivityInfo.resizeMode` is a non-SDK field, so this is reflective and expected to fail on
     * some builds - which is why the failure is reported as a value rather than swallowed. It
     * matters because an unresizeable activity is one of the documented reasons the platform
     * declines to place an activity on a secondary display, and a C1 redirect with an unknown resize
     * mode is a weaker finding than one where the mode is known.
     *
     * @return value, and how the value was obtained (or why it was not).
     */
    private fun resizeMode(context: Context): Pair<String, String> = try {
        val info: ActivityInfo = context.packageManager.getActivityInfo(
            ComponentName(context, PresentationHostActivity::class.java), 0,
        )
        val field = ActivityInfo::class.java.getDeclaredField("resizeMode")
        field.isAccessible = true
        val mode = field.getInt(info)
        val label = when (mode) {
            0 -> "0 (unresizeable)"
            1 -> "1 (resizeable and pipable, deprecated)"
            2 -> "2 (resizeable)"
            3 -> "3 (resizeable and pipable)"
            4, 5, 6, 7 -> "$mode (force-resizeable variant)"
            else -> mode.toString()
        }
        label to "ActivityInfo.resizeMode, read reflectively (non-SDK field)"
    } catch (t: Throwable) {
        "unreadable (" + t::class.java.simpleName + ")" to
            "Non-SDK reflection was refused or the field is absent on this build. The manifest does " +
                "not set android:resizeableActivity on the target activity, and targetSdk 35 defaults " +
                "it to true - but that is a documentation inference, not a measurement of this device."
    }

    /** Highest SessionLogger sequence number so far. Robust against the in-memory buffer trimming. */
    private fun maxSeq(): Long =
        SessionLogger.snapshot().mapNotNull { it.details["seq"]?.toLongOrNull() }.maxOrNull() ?: 0L

    private fun recordsAfter(seq: Long): List<LogRecord> =
        SessionLogger.snapshot().filter { (it.details["seq"]?.toLongOrNull() ?: 0L) > seq }

    /** Waits for the first record of [event] logged after [afterSeq], or null on timeout. */
    private suspend fun awaitRecord(afterSeq: Long, event: String, timeoutMs: Long): LogRecord? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            recordsAfter(afterSeq).firstOrNull { it.event == event }?.let { return it }
            delay(POLL_MS)
        }
        return recordsAfter(afterSeq).firstOrNull { it.event == event }
    }

    /** Unwraps a Compose/context chain to the hosting Activity, or null if there is none. */
    private fun Context.findActivity(): Activity? {
        var c: Context? = this
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }

    private fun describeDisplays(context: Context): String = runCatching {
        val dm = context.getSystemService(DisplayManager::class.java) ?: return@runCatching "no DisplayManager"
        dm.displays.joinToString("|") { it.displayId.toString() + ":" + it.name }
    }.getOrDefault("unreadable")

    /**
     * What DisplayRole thinks the target is. Recorded for context only: route C's outcomes never
     * depend on it, because "is this the Miracast surface?" is a separate, weaker question that
     * DisplayRole answers at INFERRED at best.
     */
    private fun describeTargetRole(context: Context, targetDisplayId: Int): String = runCatching {
        DisplayRole.verdicts(context).firstOrNull { it.displayId == targetDisplayId }
            ?.let { it.role.name + " (" + it.status.name + ")" } ?: "not listed"
    }.getOrDefault("unreadable")

    private fun describe(t: Throwable): String =
        t::class.java.simpleName + ": " + (t.message ?: "no message")

    /** One outcome, written both as a rich event and as a gradeable observation. */
    private fun record(o: Outcome) {
        SessionLogger.log(
            LabCategory.DISPLAY,
            "route_c_" + o.experimentId.lowercase() + "_outcome",
            o.status,
            buildMap<String, String> {
                put("experiment", o.experimentId)
                put("observation", o.observation)
                put("conclusion", o.conclusion)
                o.nextStep?.let { put("nextStep", it) }
                putAll(o.evidence)
            },
        )
        SessionLogger.log(o.toObservation(LabCategory.DISPLAY))
    }
}
