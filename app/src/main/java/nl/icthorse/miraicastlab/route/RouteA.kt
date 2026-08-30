package nl.icthorse.miraicastlab.route

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.media.MediaCodecList
import android.media.MediaFormat
import android.provider.Settings
import nl.icthorse.miraicastlab.core.Experiment
import nl.icthorse.miraicastlab.core.LabCategory
import nl.icthorse.miraicastlab.core.LabStatus
import nl.icthorse.miraicastlab.core.Observation
import nl.icthorse.miraicastlab.core.Outcome
import nl.icthorse.miraicastlab.core.PrivilegeTier
import nl.icthorse.miraicastlab.core.Route
import nl.icthorse.miraicastlab.core.SessionLogger

/**
 * Route A: **can the app itself be the Miracast sender?**
 *
 *     App -> Wi-Fi Display protocol -> Mirai
 *
 * The question is not "does this phone support Miracast" - Samsung's own stack answers that, and
 * that is route B. The question is whether *this process*, an ordinary third-party app, can
 * establish and drive a Wi-Fi Display session. Three things would have to be true: an entry point
 * that starts a session, a way to advertise Wi-Fi Display capability on the P2P link, and a
 * capture/encode pipeline to feed it. A1, A2 and A3 measure them one at a time, and A4 combines the
 * three results by a rule that is written down here, before the run, rather than decided afterwards.
 *
 * Nothing in this file connects to anything. The @hide surface is looked up reflectively and never
 * invoked: the point of the measurement is to find out whether the door exists and what stands in
 * front of it, and opening a door with a signature permission behind it is neither possible nor
 * something this project would do (docs/PRINCIPLES.md P6).
 */
object RouteA {

    private val CAT = LabCategory.MIRACAST

    private const val CLASS_DISPLAY_MANAGER = "android.hardware.display.DisplayManager"
    private const val CLASS_WIFI_DISPLAY_STATUS = "android.hardware.display.WifiDisplayStatus"
    private const val CLASS_P2P_MANAGER = "android.net.wifi.p2p.WifiP2pManager"
    private const val CLASS_P2P_DEVICE = "android.net.wifi.p2p.WifiP2pDevice"
    private const val CLASS_P2P_WFD_INFO = "android.net.wifi.p2p.WifiP2pWfdInfo"

    private const val PERM_CONFIGURE_WIFI_DISPLAY = "android.permission.CONFIGURE_WIFI_DISPLAY"
    private const val PERM_CAPTURE_VIDEO_OUTPUT = "android.permission.CAPTURE_VIDEO_OUTPUT"
    private const val PERM_INTERNET = "android.permission.INTERNET"

    /**
     * Positive control. This app declares ACCESS_WIFI_STATE in its manifest and it is granted at
     * install time, so a "not held" answer here would mean the permission reader itself is broken
     * and every other gate reading in this file has to be discarded.
     */
    private const val PERM_CONTROL_HELD = "android.permission.ACCESS_WIFI_STATE"

    private const val ACTION_WIFI_DISPLAY_SETTINGS = "android.settings.WIFI_DISPLAY_SETTINGS"

    /** Method-name fragments that would name a session-initiation entry point if one were public. */
    private val INITIATION_KEYWORDS =
        listOf("wifidisplay", "wfd", "miracast", "connect", "cast", "sink", "session")

    /**
     * The AOSP wireless-display entry points, all of them @hide.
     *
     * They are subtracted from the *public* inventory on purpose. `Class.getMethods()` cannot tell
     * public SDK API from @hide framework API - both are `public` in the class file - so a build
     * that does not filter them would otherwise make the public surface look like it has a
     * connect method. Keeping the two questions apart is the whole point of A1: "is there a public
     * API" and "is the hidden surface visible to reflection" have different answers and different
     * grades.
     */
    private val HIDDEN_WIFI_DISPLAY_METHODS = setOf(
        "connectWifiDisplay", "disconnectWifiDisplay", "startWifiDisplayScan",
        "stopWifiDisplayScan", "getWifiDisplayStatus", "renameWifiDisplay",
        "forgetWifiDisplay", "pauseWifiDisplay", "resumeWifiDisplay",
    )

    // ------------------------------------------------------------------ experiments

    val a1 = Experiment(
        id = "A1",
        route = Route.A,
        title = "Is there any public Android API with which an app can initiate a Wi-Fi Display session?",
        hypothesis = "There is none. DisplayManager exposes no public method that connects to a " +
            "sink; MediaRouter can only select among routes the system created; the cast and " +
            "Wi-Fi-display settings entries are UI panels, not APIs; and the AOSP methods that do " +
            "the work (DisplayManager.connectWifiDisplay, getWifiDisplayStatus) are @hide behind " +
            "the signature permission CONFIGURE_WIFI_DISPLAY. " +
            "FALSIFIED BY: any public method on this build whose name and signature start or join " +
            "a wireless display session, or a CONFIGURE_WIFI_DISPLAY that this app is measured to " +
            "hold. Either finding turns route A from closed into open.",
        method = "PackageManager.getPermissionInfo + Context.checkSelfPermission on " +
            "CONFIGURE_WIFI_DISPLAY (and a control permission this app does hold); public method " +
            "inventory of android.hardware.display.DisplayManager via Class.getMethods(); " +
            "reflective lookup - never invocation - of connectWifiDisplay / disconnectWifiDisplay / " +
            "startWifiDisplayScan / getWifiDisplayStatus and of the WifiDisplayStatus class; " +
            "PackageManager.queryIntentActivities for ACTION_CAST_SETTINGS and " +
            "ACTION_WIFI_DISPLAY_SETTINGS; MediaRouter route inventory plus a no-op " +
            "selectRoute(LIVE_VIDEO, already-selected route) reachability test.",
        procedure = "1) read the permission gates; 2) enumerate every public DisplayManager method " +
            "and keep the ones whose name could plausibly initiate a session; 3) look up the four " +
            "@hide members and a public control member, to separate 'absent' from 'hidden from us'; " +
            "4) resolve the two settings actions; 5) read every MediaRouter route and test whether " +
            "selectRoute is even reachable, by re-selecting the route that is already selected - " +
            "which is a framework no-op and cannot disturb a session in progress.",
        preconditions = listOf("None. This experiment runs on any device, with or without a car."),
        tier = PrivilegeTier.ORDINARY_APP,
        nextOnPass = listOf("A2"),
        nextOnFail = listOf("A2", "B1"),
    )

    val a2 = Experiment(
        id = "A2",
        route = Route.A,
        title = "Wi-Fi P2P: can an app form a group and speak Wi-Fi Display itself?",
        hypothesis = "Group formation is reachable from the public WifiP2pManager surface, but the " +
            "three things that turn a P2P group into a Miracast link are not: the Wi-Fi Display " +
            "capability information element (setWfdInfo is a system API behind " +
            "CONFIGURE_WIFI_DISPLAY), the RTSP M1-M7 capability negotiation, and the " +
            "wpa_supplicant WFD subsystem that carries them. " +
            "FALSIFIED BY: a publicly visible setWfdInfo this app could call, or any public API " +
            "that attaches a WFD information element to a P2P group.",
        method = "PackageManager.hasSystemFeature(FEATURE_WIFI_DIRECT); presence of the " +
            "WIFI_P2P_SERVICE; public method inventory of WifiP2pManager; reflective lookup of " +
            "setWfdInfo, createGroup, connect, discoverPeers; presence of WifiP2pWfdInfo and of " +
            "WifiP2pDevice.getWfdInfo; the CONFIGURE_WIFI_DISPLAY gate; and this app's own " +
            "INTERNET permission state, since an RTSP leg needs a socket.",
        procedure = "Read the feature flag, the service, and the API surface. Nothing is started: " +
            "no discoverPeers, no createGroup, no connect. Forming a group would disturb the very " +
            "Wi-Fi Direct stack a live Smart View session runs on, which would corrupt route B's " +
            "measurement in the same session - so 'an app can actually complete group formation " +
            "here' is deliberately left NOT_TESTED and reported as such.",
        preconditions = listOf("None."),
        tier = PrivilegeTier.ORDINARY_APP,
        nextOnPass = listOf("A3"),
        nextOnFail = listOf("A3", "B1"),
    )

    val a3 = Experiment(
        id = "A3",
        route = Route.A,
        title = "MediaProjection + MediaCodec: is the missing piece transport, or capture?",
        hypothesis = "Capture and encode are available to an ordinary app; the gap in route A is " +
            "transport and session control, not pixels. " +
            "FALSIFIED BY: no AVC encoder on this device, or a projection module result in this " +
            "run showing capture itself is unavailable - either would mean route A fails on the " +
            "capture side too and the transport finding is not the whole story.",
        method = "MediaCodecList(REGULAR_CODECS) encoder inventory for video/avc and video/hevc, " +
            "plus whatever the projection and codec probes have already logged in this test run, " +
            "read back from SessionLogger rather than by calling into another module.",
        procedure = "Count encoders; read prior evidence from the session log; state explicitly " +
            "that a working encoder is a fact about the phone and says nothing whatever about what " +
            "the Mirai accepts (docs/PRINCIPLES.md P3).",
        preconditions = listOf(
            "For the prior-evidence half: the projection and codec probes must have run earlier " +
                "in this same test run. If they have not, that half is NOT_TESTED, not negative.",
        ),
        tier = PrivilegeTier.ORDINARY_APP,
        nextOnPass = listOf("A4"),
        nextOnFail = listOf("A4"),
    )

    val a4 = Experiment(
        id = "A4",
        route = Route.A,
        title = "Verdict for route A, derived mechanically from A1-A3",
        hypothesis = "Route A is closed to an ordinary app. " +
            "FALSIFIED BY: A1 or A2 finding a public, callable initiation path. " +
            "The derivation rule is fixed before the run so the verdict cannot be written first.",
        method = "A pure function over the A1-A3 outcomes. No new measurement.",
        procedure = "RULE: if A1 or A2 is silent (NOT_TESTED/ERROR) the verdict is NOT_TESTED - we " +
            "could not measure the gate, so no claim is made. If either A1 or A2 makes a positive " +
            "claim (CONFIRMED/OBSERVED) the verdict follows that claim. Only when both A1 and A2 " +
            "are UNSUPPORTED is the verdict UNSUPPORTED, and the conclusion then names the specific " +
            "API whose absence carries it.",
        preconditions = listOf("A1, A2 and A3 have run in this session."),
        tier = PrivilegeTier.ORDINARY_APP,
        nextOnPass = emptyList(),
        nextOnFail = listOf("B1", "C1", "D1"),
    )

    val experiments: List<Experiment> = listOf(a1, a2, a3, a4)

    // ------------------------------------------------------------------ run

    /**
     * Runs A1-A3 and derives A4. Read-only throughout: nothing here connects, starts, stops or
     * configures a session, and the only call that touches framework state at all is the
     * intentionally idempotent selectRoute re-selection in A1.
     */
    suspend fun run(context: Context): List<Outcome> {
        SessionLogger.marker("route A run started: can the app itself be the sender?", CAT)

        val gates = listOf(
            PERM_CONFIGURE_WIFI_DISPLAY,
            PERM_CAPTURE_VIDEO_OUTPUT,
            PERM_INTERNET,
            PERM_CONTROL_HELD,
        ).associateWith { RouteAFacts.readPermissionGate(context, it) }

        val outcomes = mutableListOf<Outcome>()
        outcomes += runA1(context, gates)
        outcomes += runA2(context, gates)
        outcomes += runA3(context)
        outcomes += deriveA4(outcomes[0], outcomes[1], outcomes[2])

        outcomes.forEach { SessionLogger.log(it.toObservation(CAT)) }
        return outcomes
    }

    // ------------------------------------------------------------------ A1

    private suspend fun runA1(
        context: Context,
        gates: Map<String, RouteAFacts.PermissionGate>,
    ): Outcome {
        val obs = mutableListOf<Observation>()

        // --- the manager itself
        val managerPresent = runCatching {
            context.getSystemService(DisplayManager::class.java) != null
        }.getOrDefault(false)
        obs += if (managerPresent) {
            Observation.confirmed("route.a1.display_manager_present", true, LabCategory.DISPLAY)
        } else {
            Observation.unsupported(
                "route.a1.display_manager_present", LabCategory.DISPLAY,
                "getSystemService(DisplayManager) returned null; the platform answered.",
            )
        }

        // --- what DisplayManager exposes to us
        val surface = RouteAFacts.readClassSurface(CLASS_DISPLAY_MANAGER)
        val allNameMatches = surface.matching(INITIATION_KEYWORDS)
        val hiddenNamesVisible = allNameMatches.filter { HIDDEN_WIFI_DISPLAY_METHODS.contains(it) }
        val initiationNames = allNameMatches.filterNot { HIDDEN_WIFI_DISPLAY_METHODS.contains(it) }
        obs += if (!surface.loaded) {
            Observation.notTested(
                "route.a1.public_initiation_methods", CAT,
                "Could not enumerate DisplayManager's methods: " + (surface.failure ?: "unknown") +
                    ". Absence of an entry point is therefore not established.",
            )
        } else if (initiationNames.isEmpty()) {
            Observation.unsupported(
                "route.a1.public_initiation_methods", CAT,
                "None of DisplayManager's " + surface.methodNames.size + " methods visible to this " +
                    "app has a name matching " + INITIATION_KEYWORDS.joinToString("/") + ", once " +
                    "the known @hide wireless-display names are set aside and counted separately. " +
                    "The public surface offers displays to read and virtual displays to create - " +
                    "nothing that reaches a sink.",
            )
        } else {
            Observation.observed(
                "route.a1.public_initiation_methods", initiationNames.joinToString(", "), CAT,
                "Candidate entry points by NAME only. A name is not a capability: each still has " +
                    "to be read against its own documentation and permission annotation before any " +
                    "claim is made. This falsifies the A1 hypothesis only if one of them is really " +
                    "callable by an ordinary app.",
            )
        }

        obs += if (hiddenNamesVisible.isEmpty()) {
            Observation(
                key = "route.a1.hidden_names_in_inventory",
                value = "none",
                status = LabStatus.INFERRED,
                note = "No @hide wireless-display method name appears in DisplayManager's method " +
                    "list as this app sees it - consistent with the hidden-API policy filtering " +
                    "them out, and equally consistent with their not existing on this build.",
                category = CAT,
            )
        } else {
            Observation(
                key = "route.a1.hidden_names_in_inventory",
                value = hiddenNamesVisible.joinToString(", "),
                status = LabStatus.INFERRED,
                note = "These @hide members ARE visible to reflection on this build. They are " +
                    "counted as hidden surface, not as public API: they are absent from the SDK " +
                    "and AOSP gates them behind CONFIGURE_WIFI_DISPLAY. Visibility to reflection " +
                    "is not permission to call, and nothing here calls them.",
                category = CAT,
            )
        }

        // --- separate "absent" from "hidden from us"
        val control = RouteAFacts.readMethod(CLASS_DISPLAY_MANAGER, "getDisplays")
        obs += if (control.found) {
            Observation.confirmed(
                "route.a1.reflection_control", control.line(), CAT,
                "Control measurement: reflection over DisplayManager works, so a member we do not " +
                    "find below is either genuinely absent or filtered by the hidden-API policy - " +
                    "not a broken lookup.",
            )
        } else {
            Observation.error(
                "route.a1.reflection_control",
                IllegalStateException(
                    "public method getDisplays not visible via reflection: " +
                        (control.failure ?: "no signature returned"),
                ),
                CAT,
            )
        }

        val hidden = listOf(
            RouteAFacts.readMethod(CLASS_DISPLAY_MANAGER, "connectWifiDisplay"),
            RouteAFacts.readMethod(CLASS_DISPLAY_MANAGER, "disconnectWifiDisplay"),
            RouteAFacts.readMethod(CLASS_DISPLAY_MANAGER, "startWifiDisplayScan"),
            RouteAFacts.readMethod(CLASS_DISPLAY_MANAGER, "getWifiDisplayStatus"),
        )
        hidden.forEach { m ->
            // Reflective findings are capped at INFERRED on purpose: on API 28+ the reflection APIs
            // filter blocked non-SDK members, so "not visible" and "not present" are the same
            // observation from here, and we do not get to choose which one to report.
            obs += Observation(
                key = "route.a1.hidden." + m.methodName,
                value = m.line(),
                status = LabStatus.INFERRED,
                note = if (m.found) {
                    "The member is visible to reflection on this build. It is NOT called: AOSP " +
                        "annotates this surface @RequiresPermission(CONFIGURE_WIFI_DISPLAY), a " +
                        "signature permission this app is measured not to hold, so invoking it " +
                        "would only produce a SecurityException - and calling @hide framework " +
                        "internals is outside what this project does."
                } else {
                    "Not visible to this app. That is consistent with two different worlds - the " +
                        "member does not exist on this build, or the hidden-API policy filtered it " +
                        "out of getMethods() - and an app cannot tell them apart. Hence INFERRED."
                },
                category = CAT,
            )
        }

        val statusClass = RouteAFacts.readClassSurface(CLASS_WIFI_DISPLAY_STATUS)
        obs += Observation(
            key = "route.a1.hidden.wifi_display_status_class",
            value = if (statusClass.loaded) {
                "android.hardware.display.WifiDisplayStatus loads (" +
                    statusClass.methodNames.size + " visible methods)"
            } else {
                "class not loadable: " + (statusClass.failure ?: "unknown")
            },
            status = LabStatus.INFERRED,
            note = "The class that models Wi-Fi Display session state. If it loads, the framework " +
                "on this build has a Wi-Fi Display subsystem - which still says nothing about " +
                "whether an app may reach it. Nothing on it is instantiated or called.",
            category = CAT,
        )

        // --- the gate
        gates.values.forEach { g ->
            obs += Observation.confirmed(
                "route.a1.permission." + g.shortName.lowercase(), g.line(), CAT,
                "Read from PackageManager.getPermissionInfo and Context.checkSelfPermission. Not " +
                    "reflection: the platform answered directly.",
            )
        }
        val configureGate = gates[PERM_CONFIGURE_WIFI_DISPLAY]
        val controlGate = gates[PERM_CONTROL_HELD]
        val gateReadable = controlGate?.granted == true

        // --- settings panels
        val intents = listOf(
            RouteAFacts.resolveAction(context, Settings.ACTION_CAST_SETTINGS),
            RouteAFacts.resolveAction(context, ACTION_WIFI_DISPLAY_SETTINGS),
        )
        intents.forEach { r ->
            val key = "route.a1.intent." + r.action.substringAfterLast('.').lowercase()
            obs += when {
                r.failure != null -> Observation.error(key, IllegalStateException(r.failure), CAT)
                r.resolvable -> Observation.confirmed(
                    key, r.line(), CAT,
                    "A settings panel, not an API. Starting it hands control to the system UI: the " +
                        "app cannot name a sink, cannot read the result, and learns that anything " +
                        "happened only from the side effects experiment B1 measures.",
                )
                else -> Observation.unsupported(
                    key, CAT,
                    "No activity claims this action. This action is named in the manifest <queries> " +
                        "element, so package-visibility filtering is not masking the answer.",
                )
            }
        }

        // --- MediaRouter
        val router = RouteAFacts.readRouter(context)
        obs += if (router.available) {
            Observation.confirmed(
                "route.a1.mediarouter", router.summary(), CAT,
                "MediaRouter enumerates routes the SYSTEM created. This app created none, and " +
                    "there is no public method with which it could create a live-video route.",
            )
        } else {
            Observation.unsupported(
                "route.a1.mediarouter", CAT,
                router.failure ?: "MediaRouter unavailable",
            )
        }
        router.routes.forEach { r ->
            obs += Observation.confirmed("route.a1.mediarouter.route." + r.index, r.line(), CAT)
        }

        val select = RouteAFacts.probeSelectRouteReachability(context)
        obs += when {
            !select.attempted -> Observation.notTested(
                "route.a1.select_route_reachable", CAT,
                select.line() + ". Reachability of selectRoute is therefore unmeasured in this run.",
            )
            select.threw != null -> Observation.observed(
                "route.a1.select_route_reachable", select.line(), CAT,
                "The call is reachable but the framework rejected it. The exception text is the " +
                    "finding.",
            )
            else -> Observation.observed(
                "route.a1.select_route_reachable", select.line(), CAT,
                "selectRoute is reachable to an ordinary app. That is reachability, not control: " +
                    "re-selecting the already-selected route is a framework no-op, and it says " +
                    "nothing about switching to, creating, or tearing down a route. Selecting a " +
                    "DIFFERENT system route was deliberately not attempted - it would change what " +
                    "the tester is looking at in the middle of a measurement.",
            )
        }
        obs += Observation.notTested(
            "route.a1.select_other_route", CAT,
            "Whether an app may select a different system-created live-video route (and what the " +
                "framework does about it) was not attempted, by design. Experiment B3 measures the " +
                "surrounding question - who owns the session - without mutating it.",
        )

        SessionLogger.logAll(obs)

        // --- grading
        val positiveInitiation = surface.loaded && initiationNames.isNotEmpty()
        val gateStandsMeasured = configureGate != null && gateReadable &&
            configureGate.declaredOnDevice && !configureGate.granted
        val status = when {
            positiveInitiation -> LabStatus.OBSERVED
            !surface.loaded || !gateReadable -> LabStatus.NOT_TESTED
            gateStandsMeasured || !managerPresent -> LabStatus.UNSUPPORTED
            else -> LabStatus.INFERRED
        }

        val observation = buildString {
            append("DisplayManager public methods visible: ").append(surface.methodNames.size)
            append("; matching an initiation name (public, @hide names excluded): ")
            append(if (initiationNames.isEmpty()) "none" else initiationNames.joinToString(","))
            append("; @hide wireless-display names visible in the inventory: ")
            append(if (hiddenNamesVisible.isEmpty()) "none" else hiddenNamesVisible.joinToString(","))
            append(". Hidden lookups: ")
            append(hidden.joinToString("; ") { it.methodName + "=" + (if (it.found) "visible" else "not visible") })
            append(". WifiDisplayStatus class loads: ").append(statusClass.loaded)
            append(". ").append(configureGate?.line() ?: "CONFIGURE_WIFI_DISPLAY unreadable")
            append(". Control ").append(controlGate?.line() ?: "unreadable")
            append(". Settings panels: ")
            append(intents.joinToString("; ") { it.action.substringAfterLast('.') + "=" + it.resolvable })
            append(". ").append(router.summary())
            append(". ").append(select.line())
        }

        val conclusion = when (status) {
            LabStatus.OBSERVED ->
                "A DisplayManager method whose NAME could initiate a session, and which is not one " +
                    "of the known @hide wireless-display members, exists on this build. That is a lead, not a capability: A1's hypothesis is not falsified " +
                    "until one of those methods is shown to be callable by an unprivileged app. " +
                    "Read each signature before concluding anything."
            LabStatus.NOT_TESTED ->
                "The measurement did not complete: " +
                    (if (!surface.loaded) "DisplayManager's method list could not be enumerated. "
                    else "") +
                    (if (!gateReadable) "the permission reader failed its own control " +
                        "(ACCESS_WIFI_STATE, which this app declares, did not read back as held), " +
                        "so no gate reading in this experiment may be trusted. " else "") +
                    "No claim is made about route A from this experiment."
            LabStatus.UNSUPPORTED ->
                "No public Android API lets an app initiate a Wi-Fi Display session on this build. " +
                    "The verdict is carried by two specific absences, not by the general shape of " +
                    "the API: (1) DisplayManager exposes no public connect/scan method - the AOSP " +
                    "methods that do (connectWifiDisplay, startWifiDisplayScan, getWifiDisplayStatus) " +
                    "are @hide; and (2) the permission those methods require reads as: " +
                    (configureGate?.line() ?: "CONFIGURE_WIFI_DISPLAY unreadable") + ". " +
                    "MediaRouter does not fill the gap: it selects among routes the system created " +
                    "and offers no method to create a live-video route. The settings panels are UI " +
                    "hand-offs, not APIs. What is NOT concluded: nothing here says the phone cannot " +
                    "do Miracast (route B), and nothing here is about the Mirai."
            else ->
                "The readings are incomplete or mutually inconsistent; see the individual " +
                    "observations. No route A claim is made."
        }

        return Outcome(
            experimentId = "A1",
            status = status,
            observation = observation,
            conclusion = conclusion,
            evidence = mapOf(
                "displayManagerMethods" to surface.methodNames.size.toString(),
                "initiationNameMatches" to initiationNames.joinToString(",").ifEmpty { "none" },
                "hiddenNamesVisible" to hiddenNamesVisible.joinToString(",").ifEmpty { "none" },
                "hiddenLookups" to hidden.joinToString("; ") { it.line() },
                "wifiDisplayStatusClass" to statusClass.loaded.toString(),
                "permissionGates" to gates.values.joinToString("; ") { it.line() },
                "settingsPanels" to intents.joinToString("; ") { it.line() },
                "mediaRouter" to router.summary(),
                "selectRoute" to select.line(),
            ),
            nextStep = if (status == LabStatus.OBSERVED) {
                "Read the signature and permission annotation of each candidate method before " +
                    "treating route A as open. Then run A2."
            } else {
                "Run A2: the P2P layer is the other half of the sender question."
            },
        )
    }


    // ------------------------------------------------------------------ A2

    private fun runA2(
        context: Context,
        gates: Map<String, RouteAFacts.PermissionGate>,
    ): Outcome {
        val obs = mutableListOf<Observation>()
        val net = LabCategory.NETWORK

        // --- is there a P2P layer at all?
        val hasDirect = runCatching {
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)
        }.getOrNull()
        obs += when (hasDirect) {
            null -> Observation.error(
                "route.a2.feature_wifi_direct",
                IllegalStateException("PackageManager.hasSystemFeature threw"),
                net,
            )
            true -> Observation.confirmed(
                "route.a2.feature_wifi_direct", true, net,
                "Feature queries are not visibility-filtered, so this is a real answer. Wi-Fi " +
                    "Direct is the transport a Wi-Fi Display session runs over.",
            )
            false -> Observation.unsupported(
                "route.a2.feature_wifi_direct", net,
                "The platform declares no android.hardware.wifi.direct feature, so there is no P2P " +
                    "layer here for a Wi-Fi Display session to run on.",
            )
        }

        val serviceAvailable = runCatching {
            context.getSystemService(Context.WIFI_P2P_SERVICE) != null
        }.getOrDefault(false)
        obs += if (serviceAvailable) {
            Observation.confirmed(
                "route.a2.p2p_service_present", true, net,
                "WifiP2pManager is obtainable by this app.",
            )
        } else {
            Observation.unsupported(
                "route.a2.p2p_service_present", net,
                "getSystemService(WIFI_P2P_SERVICE) returned null; the platform answered.",
            )
        }

        // --- what the public P2P surface offers, and what it does not
        val managerSurface = RouteAFacts.readClassSurface(CLASS_P2P_MANAGER)
        val groupFormation = listOf("createGroup", "connect", "discoverPeers", "requestGroupInfo")
            .map { RouteAFacts.readMethod(CLASS_P2P_MANAGER, it) }
        val wfdSurface = listOf("setWfdInfo", "setWFDInfo")
            .map { RouteAFacts.readMethod(CLASS_P2P_MANAGER, it) }
        val wfdInfoClass = RouteAFacts.readClassSurface(CLASS_P2P_WFD_INFO)
        val deviceWfd = RouteAFacts.readMethod(CLASS_P2P_DEVICE, "getWfdInfo")

        obs += if (!managerSurface.loaded) {
            Observation.notTested(
                "route.a2.p2p_public_surface", net,
                "Could not enumerate WifiP2pManager: " + (managerSurface.failure ?: "unknown"),
            )
        } else {
            Observation.confirmed(
                "route.a2.p2p_public_surface",
                managerSurface.methodNames.size.toString() + " methods visible; group formation: " +
                    groupFormation.joinToString(", ") {
                        it.methodName + "=" + (if (it.found) "visible" else "not visible")
                    },
                net,
                "Group formation is reachable from the public surface (subject to the runtime " +
                    "location / NEARBY_WIFI_DEVICES permission). That is the part of route A an app " +
                    "CAN do, and on its own it produces a plain Wi-Fi Direct group - not a " +
                    "Miracast link.",
            )
        }

        val wfdVisible = wfdSurface.any { it.found }
        obs += Observation(
            key = "route.a2.wfd_capability_api",
            value = wfdSurface.joinToString(" ; ") { it.line() } + " | " + deviceWfd.line() +
                " | WifiP2pWfdInfo class loads=" + wfdInfoClass.loaded,
            status = LabStatus.INFERRED,
            note = "The Wi-Fi Display information element is what turns a P2P group into something " +
                "a Miracast sink will talk to. In AOSP the setter is a @SystemApi annotated " +
                "@RequiresPermission(CONFIGURE_WIFI_DISPLAY). Reflective visibility is INFERRED at " +
                "most: on API 28+ 'not visible' and 'not present' are the same observation from " +
                "here. Nothing on this surface is invoked.",
            category = net,
        )

        val configureGate = gates[PERM_CONFIGURE_WIFI_DISPLAY]
        val internetGate = gates[PERM_INTERNET]
        val controlGate = gates[PERM_CONTROL_HELD]
        val gateReadable = controlGate?.granted == true
        configureGate?.let {
            obs += Observation.confirmed("route.a2.permission.configure_wifi_display", it.line(), net)
        }
        internetGate?.let {
            obs += Observation.confirmed(
                "route.a2.permission.internet", it.line(), net,
                "Structural, not incidental: this app declares no INTERNET permission and never " +
                    "will (SAFETY.md). Even a hypothetical app that could bring up a WFD-capable " +
                    "group would need a socket for the RTSP M1-M7 leg, and this one cannot open " +
                    "one. Route A is therefore out of scope for THIS build regardless of what the " +
                    "platform permits - a separate fact from the platform verdict, and reported " +
                    "separately.",
            )
        }

        // --- what we deliberately did not do
        obs += Observation.notTested(
            "route.a2.group_formation_attempt", net,
            "No discoverPeers, createGroup or connect call was made. Forming a group would seize " +
                "the same Wi-Fi Direct stack a live Smart View session uses, corrupting route B's " +
                "measurement in the same run - and a probe in this project does not mutate device " +
                "state. Whether group formation actually completes on this device is therefore " +
                "unmeasured, not negative.",
        )
        obs += Observation.notTested(
            "route.a2.rtsp_negotiation", LabCategory.MIRACAST,
            "The RTSP M1-M7 capability negotiation and the wpa_supplicant WFD subsystem sit below " +
                "the app layer; no public API routes them to a third-party process. Measuring them " +
                "needs a sniffer on the link or a bench sink that logs its own session.",
        )

        SessionLogger.logAll(obs)

        // --- grading
        val status = when {
            hasDirect == null || !gateReadable -> LabStatus.NOT_TESTED
            !hasDirect || !serviceAvailable -> LabStatus.UNSUPPORTED
            wfdVisible && configureGate?.granted == true -> LabStatus.OBSERVED
            configureGate != null && configureGate.declaredOnDevice && !configureGate.granted ->
                LabStatus.UNSUPPORTED
            else -> LabStatus.INFERRED
        }

        val observation = buildString {
            append("FEATURE_WIFI_DIRECT=").append(hasDirect?.toString() ?: "unreadable")
            append("; WIFI_P2P_SERVICE present=").append(serviceAvailable)
            append("; WifiP2pManager methods visible=").append(managerSurface.methodNames.size)
            append("; group formation ").append(
                groupFormation.joinToString(",") {
                    it.methodName + "=" + (if (it.found) "visible" else "hidden")
                },
            )
            append("; WFD IE setter ").append(if (wfdVisible) "visible" else "not visible")
            append("; WifiP2pWfdInfo class loads=").append(wfdInfoClass.loaded)
            append("; ").append(configureGate?.line() ?: "CONFIGURE_WIFI_DISPLAY unreadable")
            append("; ").append(internetGate?.line() ?: "INTERNET unreadable")
            append("; no P2P call was made.")
        }

        val conclusion = when {
            status == LabStatus.NOT_TESTED ->
                "The P2P layer could not be measured (feature query failed, or the permission " +
                    "reader failed its own control). No claim is made about route A's P2P half."
            hasDirect == false ->
                "No Wi-Fi Direct on this device: route A has no transport to build on here, and " +
                    "neither would any other app. This says nothing about the Mirai."
            status == LabStatus.OBSERVED ->
                "The WFD capability setter appears reachable AND this app is measured to hold " +
                    "CONFIGURE_WIFI_DISPLAY. That is the falsifying case for A2 and must be " +
                    "verified by hand before anything is built on it - a signature permission held " +
                    "by an ordinary app is far more likely to be a measurement error than a real " +
                    "grant."
            status == LabStatus.UNSUPPORTED ->
                "An app may form a Wi-Fi Direct group, and that is all. What separates a P2P group " +
                    "from a Miracast link is the Wi-Fi Display capability information element, and " +
                    "the API that attaches it is gated behind a permission that reads as: " +
                    (configureGate?.line() ?: "CONFIGURE_WIFI_DISPLAY unreadable") +
                    ". Below that sits the RTSP negotiation, which " +
                    "no public API exposes at all. So the missing piece in route A is not the radio " +
                    "and not group formation - it is the capability advertisement and the session " +
                    "negotiation. NOT concluded: that group formation itself would fail (untested), " +
                    "or anything about what the Mirai would accept."
            else ->
                "The P2P readings are incomplete: the WFD surface was not visible but the " +
                    "permission gate could not be read decisively. See the individual observations."
        }

        return Outcome(
            experimentId = "A2",
            status = status,
            observation = observation,
            conclusion = conclusion,
            evidence = mapOf(
                "featureWifiDirect" to (hasDirect?.toString() ?: "unreadable"),
                "p2pService" to serviceAvailable.toString(),
                "p2pMethodsVisible" to managerSurface.methodNames.size.toString(),
                "groupFormation" to groupFormation.joinToString("; ") { it.line() },
                "wfdSetter" to wfdSurface.joinToString("; ") { it.line() },
                "wfdInfoClass" to (wfdInfoClass.failure ?: "loads"),
                "deviceWfdInfo" to deviceWfd.line(),
                "configureWifiDisplay" to (configureGate?.line() ?: "unreadable"),
                "internet" to (internetGate?.line() ?: "unreadable"),
            ),
            nextStep = "Run A3: with transport measured, establish whether capture and encode are " +
                "the limiting factor or not.",
        )
    }

    // ------------------------------------------------------------------ A3

    private fun runA3(context: Context): Outcome {
        val obs = mutableListOf<Observation>()
        val codec = LabCategory.CODEC

        // Capture/encode half, measured locally so A3 does not depend on another module having run.
        var encoderFailure: String? = null
        var avc = emptyList<String>()
        var hevc = emptyList<String>()
        try {
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            avc = encodersFor(list, MediaFormat.MIMETYPE_VIDEO_AVC)
            hevc = encodersFor(list, MediaFormat.MIMETYPE_VIDEO_HEVC)
        } catch (t: Throwable) {
            encoderFailure = t::class.java.simpleName + ": " + (t.message ?: "no message")
        }

        obs += when {
            encoderFailure != null -> Observation.error(
                "route.a3.video_encoders", IllegalStateException(encoderFailure), codec,
            )
            avc.isEmpty() -> Observation.unsupported(
                "route.a3.video_encoders", codec,
                "MediaCodecList reports no AVC encoder. That would make route A fail on the " +
                    "capture side as well, which is a different failure from the transport one.",
            )
            else -> Observation.confirmed(
                "route.a3.video_encoders",
                "AVC: " + avc.joinToString(",") + (if (hevc.isEmpty()) "" else " | HEVC: " + hevc.joinToString(",")),
                codec,
                "The phone can encode. This is a fact about the phone and nothing else.",
            )
        }

        // Prior evidence half: read back from the session log rather than calling another module.
        val prior = runCatching {
            SessionLogger.observations().filter {
                it.key.startsWith("projection.") || it.key.startsWith("codec.")
            }
        }.getOrDefault(emptyList())
        val priorPositive = prior.count { it.status.isPositiveClaim }

        obs += if (prior.isEmpty()) {
            Observation.notTested(
                "route.a3.prior_capture_evidence", LabCategory.MEDIAPROJECTION,
                "The projection and codec probes have not logged anything in this test run, so " +
                    "their results cannot be cited here. Run the scan or the MediaProjection " +
                    "experiment first if this half matters. Absence of their records is not " +
                    "evidence that capture failed.",
            )
        } else {
            Observation.confirmed(
                "route.a3.prior_capture_evidence",
                prior.size.toString() + " records from the projection/codec modules in this run, " +
                    priorPositive + " of them positive claims",
                LabCategory.MEDIAPROJECTION,
                "Read back from SessionLogger; the route module does not call into other modules " +
                    "(docs/PRINCIPLES.md P10).",
            )
        }

        obs += Observation.notTested(
            "route.a3.sink_acceptance", LabCategory.MIRACAST,
            "What the Toyota Mirai accepts is not derivable from anything in this experiment. An " +
                "encoder list is a phone-side fact; the sink negotiates its own profile, level and " +
                "resolution in RTSP M3/M4, which no app can read (docs/PRINCIPLES.md P3).",
        )

        SessionLogger.logAll(obs)

        val status = when {
            encoderFailure != null -> LabStatus.ERROR
            avc.isEmpty() -> LabStatus.UNSUPPORTED
            else -> LabStatus.INFERRED
        }

        val observation = "AVC encoders=" + (if (avc.isEmpty()) "none" else avc.joinToString(",")) +
            "; HEVC encoders=" + (if (hevc.isEmpty()) "none" else hevc.joinToString(",")) +
            (encoderFailure?.let { "; encoder enumeration failed: " + it } ?: "") +
            "; projection/codec records already in this run=" + prior.size +
            " (" + priorPositive + " positive)"

        val conclusion = when (status) {
            LabStatus.ERROR ->
                "The encoder inventory itself failed, so A3 answers nothing. This says nothing " +
                    "about capture on this device."
            LabStatus.UNSUPPORTED ->
                "No AVC encoder is available here, so on this device route A would fail on capture " +
                    "as well as on transport. Both failures are real and independent."
            else ->
                "Capture and encode are available to an ordinary app on this device; the piece " +
                    "route A lacks is transport and session control, as measured in A1 and A2 - not " +
                    "pixels. State the limit of that plainly: a working encoder proves nothing " +
                    "about a sink. It does not show the Mirai would accept this stream, would " +
                    "negotiate this profile, or would accept a connection at all. It shows only " +
                    "that if a transport existed, this phone could feed it."
        }

        return Outcome(
            experimentId = "A3",
            status = status,
            observation = observation,
            conclusion = conclusion,
            evidence = mapOf(
                "avcEncoders" to avc.joinToString(",").ifEmpty { "none" },
                "hevcEncoders" to hevc.joinToString(",").ifEmpty { "none" },
                "encoderFailure" to (encoderFailure ?: "none"),
                "priorRecords" to prior.size.toString(),
                "priorPositiveClaims" to priorPositive.toString(),
            ),
            nextStep = "Run A4 to combine A1-A3 into the route verdict.",
        )
    }

    private fun encodersFor(
        infos: Array<android.media.MediaCodecInfo>,
        mime: String,
    ): List<String> = infos
        .filter { info ->
            runCatching {
                info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
            }.getOrDefault(false)
        }
        .mapNotNull { runCatching { it.name }.getOrNull() }
        .sorted()

    // ------------------------------------------------------------------ A4

    /**
     * The route A verdict, computed by the rule declared in [a4].procedure.
     *
     * This function contains no measurement and no new judgement: it is deliberately a small,
     * readable state machine over three statuses, so that a reader can check that the conclusion
     * follows from the outcomes rather than from an opinion.
     */
    private fun deriveA4(a1: Outcome, a2: Outcome, a3: Outcome): Outcome {
        val silent = a1.status.isSilent || a2.status.isSilent
        val positive = a1.status.isPositiveClaim || a2.status.isPositiveClaim
        val bothUnsupported =
            a1.status == LabStatus.UNSUPPORTED && a2.status == LabStatus.UNSUPPORTED

        val status = when {
            silent -> LabStatus.NOT_TESTED
            positive -> LabStatus.OBSERVED
            bothUnsupported -> LabStatus.UNSUPPORTED
            else -> LabStatus.INFERRED
        }

        val observation = "A1=" + a1.status.name + "; A2=" + a2.status.name + "; A3=" + a3.status.name

        val conclusion = when (status) {
            LabStatus.NOT_TESTED ->
                "Route A is UNDECIDED in this run: at least one of A1/A2 could not be measured. " +
                    "This is explicitly not a finding that route A is unavailable - re-run the two " +
                    "experiments before drawing any conclusion (docs/PRINCIPLES.md P2)."
            LabStatus.OBSERVED ->
                "At least one of A1/A2 produced a positive claim, which is the falsifying case " +
                    "written down before the run. Route A cannot be called closed on this build " +
                    "until that finding is examined by hand: read the exact signature and " +
                    "permission annotation of whatever was found."
            LabStatus.UNSUPPORTED ->
                "Route A is not available to an ordinary app on this build. Two specific absences " +
                    "carry the verdict, and neither is a generalisation: (1) there is no public " +
                    "DisplayManager method that initiates a Wi-Fi Display session - the AOSP ones " +
                    "are @hide - and (2) android.permission.CONFIGURE_WIFI_DISPLAY, required by " +
                    "both that surface and by the Wi-Fi Display capability setter on WifiP2pManager, " +
                    "is measured as not held by this app - see the A1 and A2 observations for the " +
                    "exact protection level the platform reported. " +
                    "MediaRouter contributes nothing to a fix: it selects among system-created " +
                    "routes and cannot create one. A3 shows capture is NOT the limiting factor. " +
                    "NOT concluded: (a) nothing here says this phone cannot do Miracast - Samsung's " +
                    "own stack is route B and is untouched by this verdict; (b) nothing here " +
                    "describes the Mirai, which was not involved in any of these measurements; " +
                    "(c) nothing here says a privileged tier (adb/Shizuku/system app) would " +
                    "succeed - that is analysis, not a result."
            else ->
                "A1 and A2 disagree or landed on INFERRED. Route A is not established either way; " +
                    "read both outcomes before summarising."
        }

        return Outcome(
            experimentId = "A4",
            status = status,
            observation = observation,
            conclusion = conclusion,
            evidence = mapOf(
                "a1" to a1.status.name,
                "a1Observation" to a1.observation,
                "a2" to a2.status.name,
                "a2Observation" to a2.observation,
                "a3" to a3.status.name,
                "rule" to "silent(A1|A2) -> NOT_TESTED; positive(A1|A2) -> OBSERVED; " +
                    "A1==A2==UNSUPPORTED -> UNSUPPORTED; else INFERRED",
            ),
            nextStep = if (status == LabStatus.UNSUPPORTED) {
                "Route A is closed for an ordinary app. Go to B1: Samsung already owns a session " +
                    "path, so measure what an app can see of it - then C1/D1 for whether an app can " +
                    "put content onto the display that session produced."
            } else {
                "Re-read A1 and A2 before proceeding; the route A verdict is not settled."
            },
        )
    }
}
