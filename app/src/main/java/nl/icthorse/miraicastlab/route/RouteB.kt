package nl.icthorse.miraicastlab.route

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import nl.icthorse.miraicastlab.core.Experiment
import nl.icthorse.miraicastlab.core.LabCategory
import nl.icthorse.miraicastlab.core.LabStatus
import nl.icthorse.miraicastlab.core.Observation
import nl.icthorse.miraicastlab.core.Outcome
import nl.icthorse.miraicastlab.core.PrivilegeTier
import nl.icthorse.miraicastlab.core.Route
import nl.icthorse.miraicastlab.core.SessionLogger
import kotlin.math.abs

/**
 * Route B: **Samsung owns the session - what can an app see of it?**
 *
 *     Android -> Samsung DeX / Smart View -> Miracast -> Mirai
 *
 * Route A asks whether the app can be the sender. Route B starts from the opposite premise: the
 * session already exists, established by system UI the tester drove by hand. The research question
 * for this module is therefore not "can we connect" but "what, exactly, is an ordinary app allowed
 * to know about a session it did not create" - because everything routes C and D might do depends
 * on the answer.
 *
 * Three disciplines are load-bearing here.
 *
 * 1. **No transport claim above INFERRED.** Nothing an app can read names a protocol. A
 *    presentation-category display, a live-video route with a presentation display, and a p2p
 *    interface being up are equally consistent with Miracast, with an HDMI dongle, with a
 *    Chromecast, with wired DeX and with another app's VirtualDisplay.
 * 2. **Wireless DeX is not assumed to be ordinary Miracast mirroring.** B2 exists purely to find out
 *    whether an app can tell them apart at all, and to report the reason when it cannot. Treating
 *    the two as one thing is the single assumption this project was told not to make.
 * 3. **Nothing is mutated.** The tester may be in the middle of a session; a probe that tears one
 *    down destroys the measurement it was meant to take.
 */
object RouteB {

    private val CAT = LabCategory.SMART_VIEW

    private const val CLASS_MEDIA_ROUTER = "android.media.MediaRouter"
    private const val PERM_CONFIGURE_WIFI_DISPLAY = "android.permission.CONFIGURE_WIFI_DISPLAY"
    private const val PERM_MEDIA_CONTENT_CONTROL = "android.permission.MEDIA_CONTENT_CONTROL"

    /** Keys in a [RouteBSignals.Sample] that are the B1 session signals proper. */
    private val SIGNAL_KEYS = listOf(
        "display.presentation_ids",
        "route.presentation_display",
        "net.p2p_interfaces",
    )

    /** Samples taken by [run]'s baseline timeline. The long run is [timeline], driven from the UI. */
    private const val BASELINE_SAMPLES = 3
    private const val BASELINE_INTERVAL_MS = 700L

    /**
     * Density difference, in dpi, above which two displays are treated as "differently scaled".
     * 40dpi is roughly one Android density bucket; below that the difference is noise from a
     * rounded logical size, not a deliberately different desktop scale.
     */
    private const val DENSITY_DIFFERENCE_DPI = 40

    // ------------------------------------------------------------------ experiments

    val b1 = Experiment(
        id = "B1",
        route = Route.B,
        title = "Can an app detect that a wireless display session is active at all?",
        hypothesis = "An app can see the SIDE EFFECTS of a session - a presentation-category " +
            "display appears, the selected live-video route gains a presentation display, a p2p " +
            "interface comes up - but no field names the transport, so detection is a weighted " +
            "inference and never a confirmation. " +
            "FALSIFIED BY: any readable field that names the transport or the owning session; that " +
            "would raise detection above INFERRED. Also falsified in the other direction if a " +
            "session the tester can see on the Mirai produces none of the three signals - which " +
            "would be a finding in its own right.",
        method = "DisplayRole.verdicts (display classification), " +
            "DisplayManager.getDisplays(DISPLAY_CATEGORY_PRESENTATION), " +
            "MediaRouter.getSelectedRoute(ROUTE_TYPE_LIVE_VIDEO).getPresentationDisplay(), and " +
            "NetworkInterface enumeration for an up p2p* interface.",
        procedure = "Take one RouteBSignals sample, count how many of the three signals are " +
            "present, and record the display verdicts that produced it. Read-only.",
        preconditions = listOf(
            "To be meaningful this must be run twice: once with no session, once with Smart View " +
                "or Wireless DeX connected by hand. A single reading is a baseline, not a result.",
        ),
        tier = PrivilegeTier.ORDINARY_APP,
        nextOnPass = listOf("B2", "C1"),
        nextOnFail = listOf("B4"),
    )

    val b2 = Experiment(
        id = "B2",
        route = Route.B,
        title = "Can an app tell Wireless DeX apart from ordinary Smart View mirroring?",
        hypothesis = "Only one signal available to an app actually NAMES desktop mode: Samsung's " +
            "non-AOSP Configuration.semDesktopModeEnabled, reachable by reflection on One UI only. " +
            "The other candidates - a distinct density on the secondary display, FLAG_PRESENTATION, " +
            "the display name - are shared by both modes and by wired DeX, so without that field " +
            "an app cannot separate them. " +
            "FALSIFIED BY: a documented public API that reports desktop mode, or a signal " +
            "combination measured to differ reliably between the two modes on this device. " +
            "EXPLICITLY NOT ASSUMED: that Wireless DeX and Miracast screen mirroring are the same " +
            "mechanism, or that either implies the other.",
        method = "Guarded reflection on Configuration.semDesktopModeEnabled and its companion " +
            "constant SEM_DESKTOP_MODE_ENABLED; per-display density from both Display.getRealMetrics " +
            "and a Context created for that display; Display.FLAG_PRESENTATION; the display name; " +
            "Configuration.uiMode UI_MODE_TYPE.",
        procedure = "Read every discriminator on the same sample B1 used, weigh them, and state " +
            "which mode the evidence points at - or, when the naming field is unreadable, report " +
            "the inability to discriminate as the finding, with the reason.",
        preconditions = listOf(
            "A secondary display must exist. With no session there is nothing to classify and the " +
                "result is NOT_TESTED, not 'they are the same'.",
        ),
        tier = PrivilegeTier.SAMSUNG_API,
        nextOnPass = listOf("B3", "C1"),
        nextOnFail = listOf("B3"),
    )

    val b3 = Experiment(
        id = "B3",
        route = Route.B,
        title = "What does Android expose about who OWNS the session, and can an app influence it?",
        hypothesis = "An app may enumerate routes and re-select the one already selected, and that " +
            "is the whole of its reach: MediaRouter's mutating methods take UserRouteInfo, i.e. " +
            "routes the app itself created, so a system-created live-video route can be neither " +
            "created nor removed by an app. " +
            "FALSIFIED BY: a public MediaRouter method that removes or reconfigures a system route, " +
            "or a measured grant of a control permission such as MEDIA_CONTENT_CONTROL or " +
            "CONFIGURE_WIFI_DISPLAY.",
        method = "MediaRouter route inventory; reflective signature reads of addUserRoute, " +
            "createUserRoute, removeUserRoute, createRouteCategory and selectRoute; a no-op " +
            "selectRoute reachability test on the already-selected route; and the permission gates " +
            "for CONFIGURE_WIFI_DISPLAY and MEDIA_CONTENT_CONTROL.",
        procedure = "Read the surface and the signatures - the PARAMETER TYPE of removeUserRoute " +
            "is itself the evidence, and reading it requires calling nothing. Tearing a session " +
            "down is not attempted: it would destroy the tester's session and the measurement " +
            "with it, so that claim stays NOT_TESTED and is reported as such.",
        preconditions = listOf("None, but the result is far more informative during a live session."),
        tier = PrivilegeTier.ORDINARY_APP,
        nextOnPass = listOf("B4"),
        nextOnFail = listOf("B4"),
    )

    val b4 = Experiment(
        id = "B4",
        route = Route.B,
        title = "Which signal flips first when a session starts? (timeline instrument)",
        hypothesis = "The three B1 signals do not appear simultaneously, and their ORDER identifies " +
            "which layer moves first - a p2p interface before a display would point at the Wi-Fi " +
            "Direct link being established before any display is published to apps. " +
            "FALSIFIED BY: all three flipping inside one sampling interval, which would mean this " +
            "instrument's resolution is too coarse to order them and a finer one is needed.",
        method = "RouteBSignals.sample() every interval for N seconds, diffed field by field; every " +
            "transition logged to SessionLogger with its offset from the start of the window.",
        procedure = "RouteB.run() takes only a short baseline (about " + BASELINE_SAMPLES +
            " samples, roughly a second) to prove the instrument works and to record the resting state. The real measurement is " +
            "RouteB.timeline(context, seconds), started from the UI: the tester begins the run, " +
            "then connects Smart View or Wireless DeX by hand inside the window, and the ordering " +
            "of the transitions is the result. Until a tester does that, the ordering question is " +
            "NOT_TESTED - a baseline in which nothing changed answers nothing about ordering.",
        preconditions = listOf(
            "A tester who starts or stops a session DURING the sampling window. Without that, only " +
                "the instrument itself is exercised.",
        ),
        tier = PrivilegeTier.ORDINARY_APP,
        nextOnPass = listOf("C1"),
        nextOnFail = listOf("B1"),
    )

    val experiments: List<Experiment> = listOf(b1, b2, b3, b4)

    // ------------------------------------------------------------------ run

    /**
     * Runs B1-B3 against a single shared sample (so the three experiments describe the same instant)
     * and exercises the B4 instrument with a short baseline. Entirely read-only apart from the
     * deliberately idempotent selectRoute re-selection in B3.
     */
    suspend fun run(context: Context): List<Outcome> {
        SessionLogger.marker("route B run started: who owns the session, and what can we see?", CAT)

        val sample = RouteBSignals.sample(context)
        SessionLogger.log(
            CAT, "route_b_sample", LabStatus.OBSERVED,
            sample.values() + ("summary" to sample.oneLine()),
        )

        val outcomes = mutableListOf<Outcome>()
        outcomes += runB1(context, sample)
        outcomes += runB2(sample)
        outcomes += runB3(context, sample)
        outcomes += runB4(context)

        outcomes.forEach { SessionLogger.log(it.toObservation(CAT)) }
        return outcomes
    }

    // ------------------------------------------------------------------ B1

    private fun runB1(context: Context, sample: RouteBSignals.Sample): Outcome {
        val obs = mutableListOf<Observation>()

        // DisplayRole already weighs each display and refuses to exceed INFERRED for anything
        // remote; its verdicts are reused rather than re-derived.
        obs += runCatching { DisplayRole.observations(context) }.getOrDefault(emptyList())

        sample.displayFailure?.let {
            obs += Observation.error("route.b1.displays", IllegalStateException(it), LabCategory.DISPLAY)
        }
        sample.p2pFailure?.let {
            obs += Observation.error("route.b1.p2p_enumeration", IllegalStateException(it), LabCategory.NETWORK)
        }

        val count = sample.signalCount
        obs += Observation(
            key = "route.b1.session_signals",
            value = count.toString() + "/3 signals present",
            status = if (count > 0) LabStatus.INFERRED else LabStatus.OBSERVED,
            note = if (count > 0) {
                "Present: " + sample.positives.joinToString("; ") + ". This is evidence that " +
                    "SOMETHING is receiving video off-device. It does not identify the transport " +
                    "and therefore cannot be raised above INFERRED."
            } else {
                "None of the three signals is present. Descriptive only: it means no signal was " +
                    "readable at this instant, not that no session can exist. If the tester can see " +
                    "the phone's picture on the Mirai while this reads 0/3, that mismatch is itself " +
                    "the finding and should be marked."
            },
            category = CAT,
        )

        obs += Observation.notTested(
            "route.b1.transport_identity", LabCategory.MIRACAST,
            "No field readable by an app names the transport of a session. Deciding that the link " +
                "to the Mirai is Miracast rather than something else needs evidence from outside " +
                "the phone: the head unit's own display of the connection, or a capture of the link.",
        )

        SessionLogger.logAll(obs)

        val status = if (count > 0) LabStatus.INFERRED else LabStatus.OBSERVED
        val conclusion = if (count > 0) {
            "A wireless display session is probably active: " + count + " of the three independent " +
                "signals an app can read are present. What may NOT be concluded from this: that the " +
                "transport is Miracast, that Samsung Smart View is the owner, that the sink is the " +
                "Mirai, or that the session is mirroring rather than a desktop - B2 addresses the " +
                "last of those and cannot always answer it either."
        } else {
            "No app-visible signal of a wireless display session at this instant. This is the " +
                "baseline the tester should capture BEFORE connecting anything, so that B4's " +
                "timeline has something to diff against. It is not evidence that a session is " +
                "impossible."
        }

        return Outcome(
            experimentId = "B1",
            status = status,
            observation = sample.oneLine(),
            conclusion = conclusion,
            evidence = sample.values(),
            nextStep = if (count > 0) {
                "Run B2 to try to classify the session, then C1 to test whether our own content can " +
                    "be placed on that display."
            } else {
                "Start Smart View or Wireless DeX by hand and re-run, or run " +
                    "RouteB.timeline(context, 60) and connect during the window."
            },
        )
    }

    // ------------------------------------------------------------------ B2

    private fun runB2(sample: RouteBSignals.Sample): Outcome {
        val obs = mutableListOf<Observation>()
        val dexCat = LabCategory.DEX

        val builtIn = sample.internalDisplay
        val secondaries = sample.secondaryDisplays
        val dex = sample.dex

        // 1. the only signal that NAMES desktop mode
        obs += if (dex.fieldValue == null) {
            Observation.notTested(
                "route.b2.desktop_mode_field", dexCat,
                "Configuration.semDesktopModeEnabled could not be read: " +
                    (dex.fieldFailure ?: "no reason recorded") + ". This is the only field " +
                    "available to an app that names desktop mode at all; without it the remaining " +
                    "signals cannot separate Wireless DeX from mirroring. NOT evidence that DeX is " +
                    "inactive.",
            )
        } else {
            Observation(
                key = "route.b2.desktop_mode_field",
                value = dex.line() + " -> active=" + (dex.active?.toString() ?: "undetermined"),
                status = LabStatus.INFERRED,
                note = "Read reflectively from a non-AOSP field on One UI. Capped at INFERRED: the " +
                    "field is undocumented, its meaning may change between One UI releases, and it " +
                    "does not distinguish wireless DeX from DeX over a USB-C dock.",
                category = dexCat,
            )
        }

        // 2. density: DeX drives the desktop at its own scale, mirroring usually does not
        val densitySignals = mutableListOf<String>()
        secondaries.forEach { s ->
            val builtInDensity = builtIn?.densityDpi
            val differs = builtInDensity != null &&
                abs(s.densityDpi - builtInDensity) >= DENSITY_DIFFERENCE_DPI
            if (differs) {
                densitySignals += "display " + s.id + " runs at " + s.densityDpi +
                    "dpi vs built-in " + builtInDensity + "dpi"
            }
            obs += Observation.confirmed(
                "route.b2.secondary_display." + s.id, s.line(), LabCategory.DISPLAY,
                "Raw geometry of a non-default display. Density is a DeX hint, not a proof: an " +
                    "HDMI dongle and a Chromecast also arrive with their own density.",
            )
        }

        // 3. the weak signals, recorded so a reader can disagree with the weighting
        val nameHints = secondaries.filter { s ->
            listOf("dex", "desktop").any { s.name.contains(it, ignoreCase = true) }
        }.map { it.name }
        val presentationFlagged = secondaries.filter { it.presentationFlag }.map { it.id }

        obs += Observation(
            key = "route.b2.weak_signals",
            value = "uiModeType=" + sample.uiModeType +
                "; names hinting desktop=" + (if (nameHints.isEmpty()) "none" else nameHints.joinToString(",")) +
                "; FLAG_PRESENTATION on=" + (if (presentationFlagged.isEmpty()) "none" else presentationFlagged.joinToString(",")) +
                "; density differences=" + (if (densitySignals.isEmpty()) "none" else densitySignals.joinToString("; ")),
            status = LabStatus.INFERRED,
            note = "Every one of these is shared by both candidate modes. FLAG_PRESENTATION in " +
                "particular says 'apps may present here', not 'this is mirroring' and not 'this is " +
                "DeX'. They are recorded to be weighed, not to decide.",
            category = dexCat,
        )

        val discriminable = dex.active != null && secondaries.isNotEmpty()

        val status = when {
            secondaries.isEmpty() -> LabStatus.NOT_TESTED
            dex.active == null -> LabStatus.NOT_TESTED
            else -> LabStatus.INFERRED
        }

        val observation = "secondary displays=" + secondaries.size +
            "; desktopMode=" + (dex.active?.toString() ?: "unreadable") +
            "; " + dex.line() +
            "; uiModeType=" + sample.uiModeType +
            "; density differences=" + (if (densitySignals.isEmpty()) "none" else densitySignals.joinToString("; ")) +
            "; FLAG_PRESENTATION on displays=" + (if (presentationFlagged.isEmpty()) "none" else presentationFlagged.joinToString(","))

        val conclusion = when {
            secondaries.isEmpty() ->
                "Nothing to classify: there is no secondary display at this instant, so the " +
                    "question 'DeX or mirroring' has no subject. This is NOT a finding that the two " +
                    "are indistinguishable, and it is certainly not a finding that they are the " +
                    "same thing - it is an empty measurement. Re-run during a session."
            dex.active == null ->
                "An app CANNOT tell Wireless DeX apart from ordinary Smart View mirroring on this " +
                    "build, and the reason is specific: the only signal that names desktop mode - " +
                    "Configuration.semDesktopModeEnabled - was unreadable (" +
                    (dex.fieldFailure ?: "no reason recorded") + "), and every remaining signal " +
                    "(density, FLAG_PRESENTATION, display name, uiMode) is produced identically by " +
                    "both modes and by wired DeX. This is a limitation of the instrument, not a " +
                    "claim that the two mechanisms are equivalent - the project's standing rule is " +
                    "that they are NOT assumed to be. Distinguishing them needs an out-of-band " +
                    "observation: what the Mirai actually shows (a desktop, or the phone screen)."
            dex.active == true ->
                "The session is consistent with DESKTOP MODE (DeX) rather than plain mirroring: " +
                    "the desktop-mode field reads active" +
                    (if (densitySignals.isEmpty()) "" else ", and the secondary display runs at a " +
                        "different density (" + densitySignals.joinToString("; ") + ")") +
                    ". Two things this does NOT establish: whether that DeX session is WIRELESS " +
                    "(a USB-C dock reads identically) - B1's p2p signal is the only hint there, and " +
                    "it is a hint - and what protocol carries it to the Mirai."
            else ->
                "The desktop-mode field reads inactive, so the session is consistent with plain " +
                    "MIRRORING rather than DeX" +
                    (if (densitySignals.isEmpty()) ", and the secondary display carries no distinct " +
                        "density, which fits mirroring" else ", although the secondary display DOES " +
                        "run at a distinct density (" + densitySignals.joinToString("; ") +
                        "), which does not fit mirroring cleanly and should be re-checked") +
                    ". Still not a transport claim: mirroring to a Chromecast reads the same way."
        }

        SessionLogger.logAll(obs)

        return Outcome(
            experimentId = "B2",
            status = status,
            observation = observation,
            conclusion = conclusion,
            evidence = mapOf(
                "desktopModeRaw" to (dex.fieldValue?.toString() ?: "unreadable"),
                "desktopModeFailure" to (dex.fieldFailure ?: "none"),
                "desktopModeConstant" to (dex.constantValue?.toString() ?: "unreadable"),
                "secondaryDisplays" to secondaries.joinToString(" | ") { it.line() }.ifEmpty { "none" },
                "internalDisplay" to (builtIn?.line() ?: "none"),
                "uiModeType" to sample.uiModeType,
                "densityDifferences" to densitySignals.joinToString("; ").ifEmpty { "none" },
                "discriminable" to discriminable.toString(),
            ),
            nextStep = if (discriminable) {
                "Run B3, then C1 against display " + secondaries.first().id + "."
            } else {
                "Ask the tester to record what the Mirai actually shows (phone screen or a " +
                    "desktop) with a manual marker - that out-of-band observation is what settles " +
                    "the DeX-vs-mirroring question when the phone cannot."
            },
        )
    }

    // ------------------------------------------------------------------ B3

    private suspend fun runB3(context: Context, sample: RouteBSignals.Sample): Outcome {
        val obs = mutableListOf<Observation>()
        val router = sample.router

        obs += if (router.available) {
            Observation.confirmed("route.b3.router", router.summary(), LabCategory.DISPLAY)
        } else {
            Observation.unsupported(
                "route.b3.router", LabCategory.DISPLAY,
                router.failure ?: "MediaRouter unavailable",
            )
        }

        // The signature IS the evidence: removeUserRoute takes a UserRouteInfo, so an app can only
        // remove routes it created itself. Nothing is invoked to establish that.
        val methods = listOf(
            "addUserRoute", "createUserRoute", "removeUserRoute", "createRouteCategory",
            "selectRoute", "getSelectedRoute",
        ).map { RouteAFacts.readMethod(CLASS_MEDIA_ROUTER, it) }
        methods.forEach { m ->
            obs += Observation.confirmed(
                "route.b3.mediarouter_method." + m.methodName.lowercase(), m.line(), LabCategory.DISPLAY,
                "Public SDK signature, read reflectively and never invoked.",
            )
        }
        val remove = methods.first { it.methodName == "removeUserRoute" }
        val removeIsUserOnly = remove.signatures.any { it.contains("UserRouteInfo") }

        obs += Observation.confirmed(
            "route.b3.app_created_routes", "0", LabCategory.DISPLAY,
            "This module calls no route-creating API, so every route listed above was created by " +
                "the system or by another app. An app's own routes are ROUTE_TYPE_USER entries in " +
                "the picker; they carry no display and no transport.",
        )

        val select = RouteAFacts.probeSelectRouteReachability(context)
        obs += if (select.attempted) {
            Observation.observed(
                "route.b3.select_route_reachable", select.line(), LabCategory.DISPLAY,
                "Reachability only. The route re-selected is the one already selected, which the " +
                    "framework treats as a no-op; this deliberately does not test switching.",
            )
        } else {
            Observation.notTested(
                "route.b3.select_route_reachable", LabCategory.DISPLAY,
                select.line(),
            )
        }

        val gates = listOf(PERM_CONFIGURE_WIFI_DISPLAY, PERM_MEDIA_CONTENT_CONTROL)
            .map { RouteAFacts.readPermissionGate(context, it) }
        gates.forEach { g ->
            obs += Observation.confirmed(
                "route.b3.permission." + g.shortName.lowercase(), g.line(), CAT,
                "The control gate around a session an app did not create.",
            )
        }
        val anyControlGranted = gates.any { it.granted }

        obs += Observation.notTested(
            "route.b3.teardown_attempt", CAT,
            "No attempt was made to stop, switch or reconfigure the session. Doing so would " +
                "destroy a live tester session mid-measurement, and this module does not mutate " +
                "device state. So 'an app cannot tear a session down' is NOT established here - " +
                "what is established is that no public API for it was found.",
        )
        obs += Observation.notTested(
            "route.b3.session_owner_identity", CAT,
            "MediaRouter names the ROUTE, not the process that owns the session. Identifying " +
                "Samsung's mirroring service as the owner needs the package-level evidence the " +
                "Smart View probe collects, and even that is correlation rather than ownership.",
        )

        SessionLogger.logAll(obs)

        val status = when {
            !router.available -> LabStatus.NOT_TESTED
            anyControlGranted -> LabStatus.OBSERVED
            removeIsUserOnly || remove.found -> LabStatus.UNSUPPORTED
            else -> LabStatus.INFERRED
        }

        val observation = router.summary() +
            "; routes created by this app=0" +
            "; " + remove.line() +
            "; " + select.line() +
            "; " + gates.joinToString("; ") { it.line() }

        val conclusion = when (status) {
            LabStatus.NOT_TESTED ->
                "MediaRouter was unavailable, so nothing about session ownership was measured."
            LabStatus.OBSERVED ->
                "A control permission reads as GRANTED to this app, which contradicts the " +
                    "hypothesis and is far more likely to be a measurement error than a real " +
                    "grant on a non-rooted device. Verify it by hand before building on it."
            LabStatus.UNSUPPORTED ->
                "The session belongs to the system, and an app's reach over it is limited to " +
                    "reading and to re-selecting what is already selected. The evidence is the " +
                    "SIGNATURE, not an attempt: MediaRouter's mutating methods take a " +
                    "UserRouteInfo - a route the app created - so a system-created live-video " +
                    "route cannot be removed by an app, and there is no public method to create " +
                    "one. The control permissions read as: " +
                    gates.joinToString("; ") { it.line() } + ". " +
                    "NOT concluded: that a switch to another existing route would fail - that was " +
                    "deliberately not attempted (see route.b3.teardown_attempt); and not that " +
                    "Samsung is the owner - MediaRouter names routes, not owners."
            else ->
                "The MediaRouter surface could not be read decisively; see the individual " +
                    "observations before summarising ownership."
        }

        return Outcome(
            experimentId = "B3",
            status = status,
            observation = observation,
            conclusion = conclusion,
            evidence = mapOf(
                "router" to router.summary(),
                "routes" to router.routes.joinToString(" | ") { it.line() }.ifEmpty { "none" },
                "removeUserRoute" to remove.line(),
                "removeIsUserRouteOnly" to removeIsUserOnly.toString(),
                "selectRoute" to select.line(),
                "permissionGates" to gates.joinToString("; ") { it.line() },
            ),
            nextStep = "Run B4 with the tester connecting during the window; then C1, which is the " +
                "first experiment that tries to put anything on the display this session produced.",
        )
    }

    // ------------------------------------------------------------------ B4

    /** What a timeline run saw. [transitions] is in time order across the whole window. */
    data class TimelineResult(
        val samples: Int,
        val durationMs: Long,
        val intervalMs: Long,
        val transitions: List<RouteBSignals.Transition>,
        val first: RouteBSignals.Sample?,
        val last: RouteBSignals.Sample?,
        val failure: String?,
    ) {
        /** Transitions of the three B1 session signals only, in the order they occurred. */
        val signalTransitions: List<RouteBSignals.Transition>
            get() = transitions.filter { SIGNAL_KEYS.contains(it.key) }

        /** The first of the three session signals to change, or null if none did. */
        val firstSignalToFlip: RouteBSignals.Transition? get() = signalTransitions.firstOrNull()

        fun summary(): String = samples.toString() + " samples over " + durationMs + "ms at " +
            intervalMs + "ms; " + transitions.size + " field transitions, " +
            signalTransitions.size + " of them session signals" +
            (failure?.let { "; failed: " + it } ?: "")
    }

    /**
     * Samples the B1 signals every [intervalMs] for [durationSeconds] and returns every transition.
     *
     * This is the instrument the tester drives: start it, then connect Smart View or Wireless DeX by
     * hand inside the window. Every transition is logged as it happens, with its offset from the
     * start, so the ordering survives even if the app is killed mid-run.
     *
     * Cancellation propagates: a cancelled run is not a failed run, and is not recorded as one.
     */
    suspend fun timeline(
        context: Context,
        durationSeconds: Int = 30,
        intervalMs: Long = 1000L,
    ): TimelineResult {
        val seconds = durationSeconds.coerceIn(1, 600)
        val step = intervalMs.coerceIn(200L, 10_000L)
        val start = SystemClock.elapsedRealtime()

        SessionLogger.log(
            CAT, "route_b_timeline_started", LabStatus.OBSERVED,
            mapOf("seconds" to seconds.toString(), "intervalMs" to step.toString()),
        )

        val transitions = mutableListOf<RouteBSignals.Transition>()
        var first: RouteBSignals.Sample? = null
        var previous: RouteBSignals.Sample? = null
        var samples = 0
        var failure: String? = null

        try {
            while (true) {
                val next = RouteBSignals.sample(context)
                samples++
                if (first == null) first = next
                previous?.let { prev ->
                    val diff = RouteBSignals.diff(prev, next, start)
                    diff.forEach { t ->
                        transitions += t
                        SessionLogger.log(
                            CAT, "route_b_signal_transition",
                            if (SIGNAL_KEYS.contains(t.key)) LabStatus.OBSERVED else LabStatus.CONFIRMED,
                            mapOf(
                                "key" to t.key,
                                "before" to t.before,
                                "after" to t.after,
                                "sinceStartMs" to t.sinceStartMs.toString(),
                                "isSessionSignal" to SIGNAL_KEYS.contains(t.key).toString(),
                            ),
                        )
                    }
                }
                previous = next
                if (SystemClock.elapsedRealtime() - start >= seconds * 1000L) break
                delay(step)
            }
        } catch (c: CancellationException) {
            // A cancelled window is not a failed measurement; let the caller's scope handle it.
            throw c
        } catch (t: Throwable) {
            failure = t::class.java.simpleName + ": " + (t.message ?: "no message")
        }

        val result = TimelineResult(
            samples = samples,
            durationMs = SystemClock.elapsedRealtime() - start,
            intervalMs = step,
            transitions = transitions,
            first = first,
            last = previous,
            failure = failure,
        )
        SessionLogger.log(
            CAT, "route_b_timeline_finished",
            if (failure != null) LabStatus.ERROR else LabStatus.OBSERVED,
            mapOf(
                "summary" to result.summary(),
                "firstSignalToFlip" to (result.firstSignalToFlip?.line() ?: "none"),
            ),
        )
        return result
    }

    private suspend fun runB4(context: Context): Outcome {
        // run() takes only a short baseline: a long window belongs to a tester who is about to
        // connect something, not to an automated sweep. The window is sized so that BASELINE_SAMPLES
        // samples fit in it - the loop samples first and checks the deadline afterwards, so a
        // window of (n-1) intervals yields n samples.
        val windowSeconds = (((BASELINE_SAMPLES - 1) * BASELINE_INTERVAL_MS) / 1000L).toInt()
        val result = timeline(
            context,
            durationSeconds = windowSeconds,
            intervalMs = BASELINE_INTERVAL_MS,
        )

        val obs = mutableListOf<Observation>()
        obs += if (result.failure != null) {
            Observation.error(
                "route.b4.instrument", IllegalStateException(result.failure), CAT,
            )
        } else {
            Observation.observed(
                "route.b4.instrument", result.summary(), CAT,
                "The sampler ran. Whether it can ORDER the session signals is a different question, " +
                    "answered only by a window in which a session actually starts.",
            )
        }
        result.transitions.forEach { t ->
            obs += Observation.observed("route.b4.transition." + t.key, t.line(), CAT)
        }
        if (result.signalTransitions.isEmpty()) {
            obs += Observation.notTested(
                "route.b4.signal_ordering", CAT,
                "No session signal changed during this window, so their ordering is unmeasured. " +
                    "This is not evidence that they change together: nothing happened to order. " +
                    "Run RouteB.timeline(context, 60) and connect Smart View by hand inside it.",
            )
        }

        SessionLogger.logAll(obs)

        val status = when {
            result.failure != null -> LabStatus.ERROR
            result.signalTransitions.isNotEmpty() -> LabStatus.OBSERVED
            else -> LabStatus.NOT_TESTED
        }

        val conclusion = when (status) {
            LabStatus.ERROR ->
                "The timeline instrument itself failed; nothing may be concluded about signal " +
                    "ordering, and the failure should be fixed before the vehicle session."
            LabStatus.OBSERVED ->
                "Session signals changed during the window, and their order is recorded: " +
                    result.signalTransitions.joinToString(" then ") { it.key } +
                    ". First to flip: " + (result.firstSignalToFlip?.line() ?: "none") + ". " +
                    "That ordering says which LAYER moved first, not which protocol is in use, and " +
                    "the sampling interval (" + result.intervalMs + "ms) is the resolution limit: " +
                    "two signals inside one interval cannot be ordered by this instrument."
            else ->
                "The instrument works and a resting baseline was captured, but the question B4 " +
                    "asks - which signal flips first - is unanswered, because nothing flipped. " +
                    "The measurement needs a tester to start or stop a session inside the window. " +
                    "Recording this as NOT_TESTED rather than as 'no ordering' is the whole point " +
                    "(docs/PRINCIPLES.md P2)."
        }

        return Outcome(
            experimentId = "B4",
            status = status,
            observation = result.summary() +
                "; transitions: " + (result.transitions.joinToString(" | ") { it.line() }.ifEmpty { "none" }),
            conclusion = conclusion,
            evidence = mapOf(
                "samples" to result.samples.toString(),
                "durationMs" to result.durationMs.toString(),
                "intervalMs" to result.intervalMs.toString(),
                "transitions" to result.transitions.size.toString(),
                "sessionSignalTransitions" to result.signalTransitions.size.toString(),
                "firstSignalToFlip" to (result.firstSignalToFlip?.line() ?: "none"),
                "baselineSignals" to (result.first?.oneLine() ?: "none"),
                "finalSignals" to (result.last?.oneLine() ?: "none"),
                "failure" to (result.failure ?: "none"),
            ),
            nextStep = "Run RouteB.timeline(context, 60) from the UI and connect Smart View or " +
                "Wireless DeX by hand inside the window; the ordering of the logged transitions is " +
                "the result.",
        )
    }
}
