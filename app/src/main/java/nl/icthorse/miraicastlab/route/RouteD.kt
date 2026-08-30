package nl.icthorse.miraicastlab.route

import android.app.Activity
import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import nl.icthorse.miraicastlab.core.Experiment
import nl.icthorse.miraicastlab.core.LabCategory
import nl.icthorse.miraicastlab.core.LabStatus
import nl.icthorse.miraicastlab.core.Observation
import nl.icthorse.miraicastlab.core.Outcome
import nl.icthorse.miraicastlab.core.PrivilegeTier
import nl.icthorse.miraicastlab.core.Route
import nl.icthorse.miraicastlab.core.SessionLogger
import java.util.concurrent.ConcurrentHashMap

/**
 * Route D: **can this app put SOMEONE ELSE'S content on a display that something else already
 * established?**
 *
 * This is the route the owner explicitly refuses to assume works, in either direction. Nothing in
 * this file decides in advance whether an ordinary app may launch a foreign activity on a chosen
 * display; it encodes the checks and lets the platform answer.
 *
 * Four experiments, deliberately not collapsible into one another:
 *
 *  - **D1** package visibility. Before anything can be launched we have to know what this app is
 *    even allowed to *see*. On API 30+ the answer is a property of our own manifest, not of the
 *    device, and confusing the two would poison every later step.
 *  - **D2** launch a visible package's launcher activity with
 *    `ActivityOptions.setLaunchDisplayId(id)`.
 *  - **D3** the documented constraints, read off this device: which gating permissions we hold,
 *    what the target display's flags are, what the target activity's resize mode is, and - when the
 *    platform refuses - the exact text of the `SecurityException` it raised.
 *  - **D4** the control that matters most here: launch the same app with **no** display id at all
 *    while a DeX session is running. If DeX routes it to the external screen by itself, then any
 *    D2 success would have been DeX's doing rather than ours. That is a completely different
 *    mechanism and it is the one most likely to be misattributed.
 *
 * ### Why this file leans on a human
 * For our own activity (route C) the launched component reports its own `displayId` back through
 * [SessionLogger], which is a hard measurement. For a foreign app that channel does not exist, and
 * every API that would answer "which display did that activity land on" is restricted:
 * `ActivityManager.getRunningTasks` has returned only the caller's own tasks since API 21,
 * `getRunningAppProcesses` only the caller's own process since API 22, and `dumpsys` needs the
 * shell uid. So route D combines two independent readings - what the API did, and what the tester
 * saw - and when they disagree it says so instead of choosing the flattering one.
 *
 * ### What is never assumed here
 * That the target display is the Miracast/DeX surface (that is [DisplayRole]'s question and it never
 * answers above INFERRED); that a launch which threw nothing succeeded; that a refusal at the
 * ordinary-app tier says anything about the adb, Shizuku or system-app tiers; and that anything at
 * all appeared inside the vehicle. The last of those can only be established by a person looking at
 * the head unit, which is exactly why [TesterVerdict] exists and is attributed.
 */
object RouteD {

    // ------------------------------------------------------------------ tuning

    /**
     * Below this many *distinct* packages behind ACTION_MAIN/CATEGORY_LAUNCHER we treat the result
     * as filtered. A phone in daily use carries dozens of launchable apps; a handful means we are
     * looking at our own manifest's <queries> element, not at the device.
     */
    private const val LAUNCHER_FILTER_THRESHOLD = 12

    /** How long a launch is given to settle before the display set is re-read. */
    private const val LAUNCH_SETTLE_MS = 2_500L

    /** How long the tester is given to answer "which screen did it appear on?". */
    private const val TESTER_WINDOW_MS = 15_000L

    /** How long we wait for the lab to be back in the foreground before attempting D4. */
    private const val FOREGROUND_WINDOW_MS = 20_000L

    private const val POLL_MS = 250L

    // ------------------------------------------------------------------ tester channel

    /** What a human saw after a launch. The only instrument that can see the head unit. */
    enum class Sighting(val label: String) {
        EXTERNAL_DISPLAY("appeared on the external screen"),
        PHONE_SCREEN("appeared on the phone screen"),
        BOTH_SCREENS("appeared on both screens"),
        NOTHING_VISIBLE("nothing appeared anywhere"),
        UNSURE("could not tell"),
    }

    /** One tester report, timestamped and attributed. Never merged into an API observation. */
    data class TesterVerdict(
        val experimentId: String,
        val sighting: Sighting,
        val note: String?,
        val atElapsedRealtimeMs: Long,
    )

    // Written by the UI thread, read by the experiment coroutine: concurrent by construction.
    private val verdicts = ConcurrentHashMap<String, TesterVerdict>()

    /**
     * Records what the tester saw. Call this from the route D screen while an experiment is waiting.
     *
     * The verdict is logged immediately as its own OBSERVED record, attributed to the tester, so it
     * survives in the evidence file even if the run is killed before the outcome is written.
     */
    fun recordTesterVerdict(experimentId: String, sighting: Sighting, note: String? = null): TesterVerdict {
        val v = TesterVerdict(experimentId, sighting, note, SystemClock.elapsedRealtime())
        verdicts[experimentId] = v
        SessionLogger.log(
            LabCategory.USER,
            "route_d_tester_verdict",
            LabStatus.OBSERVED,
            buildMap<String, String> {
                put("experiment", experimentId)
                put("sighting", sighting.name)
                put("sightingLabel", sighting.label)
                put("source", "human tester, entered by hand")
                note?.let { put("note", it) }
            },
        )
        return v
    }

    fun testerVerdict(experimentId: String): TesterVerdict? = verdicts[experimentId]

    /** Drops stale verdicts. A verdict from a previous run must never grade this one. */
    fun clearTesterVerdicts() = verdicts.clear()

    // ------------------------------------------------------------------ experiments

    /**
     * The four experiments. Hypotheses are written here, before any code runs, and each one names
     * what would falsify it - a hypothesis that cannot fail is a slogan.
     */
    val experiments: List<Experiment> = listOf(
        Experiment(
            id = "D1",
            route = Route.D,
            title = "What can this app see? Package visibility before anything is launched",
            hypothesis = "The set of packages this app can enumerate is decided by our own manifest " +
                "and by API 30+ package-visibility filtering, not by what is installed. Falsified " +
                "if the launcher query returns a number consistent with a whole handset (dozens of " +
                "distinct packages), which would mean the declared <queries> intent grants broad " +
                "visibility on this build. Either way this experiment makes NO claim about which " +
                "apps are installed: that question is not answerable at this tier.",
            method = "PackageManager.queryIntentActivities(Intent(ACTION_MAIN).addCategory(" +
                "CATEGORY_LAUNCHER), 0) and PackageManager.getInstalledPackages(0)",
            procedure = "Run both queries, count resolve infos and distinct packages, and compare " +
                "them against a threshold that separates 'a handset' from 'our own <queries> " +
                "element'. Record the numbers as a measurement of VISIBILITY. Separately record " +
                "'which apps are installed' as NOT_TESTED, because a filtered list cannot answer it " +
                "and an unfiltered-looking list cannot prove it is complete either. Pick a launch " +
                "candidate for D2/D4 from what is visible, never our own package - launching " +
                "ourselves is route C's question.",
            preconditions = listOf(
                "None. This runs in any configuration and is the precondition for D2 and D4.",
            ),
            tier = PrivilegeTier.ORDINARY_APP,
            nextOnPass = listOf("D2", "D4"),
            nextOnFail = listOf("D3"),
        ),
        Experiment(
            id = "D2",
            route = Route.D,
            title = "Launch ANOTHER app's activity on a chosen display",
            hypothesis = "An ordinary, unprivileged app can place a DIFFERENT app's launcher " +
                "activity on a specified non-default display by passing " +
                "ActivityOptions.setLaunchDisplayId(id) to startActivity. Falsified by: a thrown " +
                "SecurityException naming a permission we do not hold; ActivityNotFoundException; " +
                "nothing appearing anywhere; or - the case that matters - the activity appearing on " +
                "the PHONE screen, i.e. the platform accepted the option and silently overrode it. " +
                "Explicitly NOT part of this hypothesis: that pixels reached the vehicle.",
            method = "PackageManager.getLaunchIntentForPackage(pkg) + " +
                "ActivityOptions.makeBasic().setLaunchDisplayId(id).toBundle() -> " +
                "Context.startActivity(intent, options)",
            procedure = "Record our own foreground state first: a launch made while we are NOT " +
                "resumed can be dropped by the background-activity-launch restrictions of API 29+ " +
                "with no exception at all, and that confound must be visible in the evidence rather " +
                "than mistaken for a platform refusal. Then launch, catching SecurityException, " +
                "ActivityNotFoundException and everything else separately. After the launch settles, " +
                "re-read DisplayManager and record the display set, and ask the tester which screen " +
                "the app appeared on. Combine both readings into one outcome; when they disagree, " +
                "report the disagreement instead of resolving it.",
            preconditions = listOf(
                "D1 found at least one visible package other than ours with a launcher intent.",
                "The lab is in the foreground, so this is not a background activity start.",
                "A display with the requested id is listed by DisplayManager. If it is not, this " +
                    "measures the API's behaviour for an absent display, which is a different question.",
            ),
            tier = PrivilegeTier.ORDINARY_APP,
            nextOnPass = listOf("D3", "D4"),
            nextOnFail = listOf("D3", "D4"),
        ),
        Experiment(
            id = "D3",
            route = Route.D,
            title = "The documented constraint, read off this device",
            hypothesis = "Whether a cross-uid launch onto a specific display is permitted is decided " +
                "by properties that can be read here: the gating permissions we hold or do not hold, " +
                "whether the target display is private (FLAG_PRIVATE) or offered for presentation " +
                "(FLAG_PRESENTATION / DISPLAY_CATEGORY_PRESENTATION), and whether the target " +
                "activity is resizeable. Falsified if D2 is refused while none of these read as a " +
                "constraint, or if D2 succeeds while all of them read as constraints - in either " +
                "case the model behind this experiment is wrong and the real gate is elsewhere.",
            method = "Context.checkSelfPermission(ACTIVITY_EMBEDDING / INTERNAL_SYSTEM_WINDOW / " +
                "MANAGE_ACTIVITY_TASKS / SYSTEM_ALERT_WINDOW), Settings.canDrawOverlays(), " +
                "Display.getFlags(), DisplayManager.DISPLAY_CATEGORY_PRESENTATION, " +
                "PackageManager.getActivityInfo(...).resizeMode (non-SDK, reflective)",
            procedure = "Read each gate and record its value. Then record what D2 actually did: the " +
                "platform's documented way of refusing a launch onto a display the caller may not " +
                "use is to throw SecurityException from startActivity, so if D2 threw one, its EXACT " +
                "message is captured verbatim here - the message names the uid and display and is " +
                "the single most useful artefact route D can produce. If D2 threw nothing, that is " +
                "recorded as 'no refusal was raised', which is NOT the same as 'the launch was " +
                "permitted': a silent redirect to the default display raises nothing either.",
            preconditions = listOf("D2 has run, in any outcome, including a refused one."),
            tier = PrivilegeTier.ORDINARY_APP,
            nextOnPass = listOf("D4"),
            nextOnFail = listOf("D4"),
        ),
        Experiment(
            id = "D4",
            route = Route.D,
            title = "Control: launch the same app with NO display id while DeX is active",
            hypothesis = "A plain startActivity, with no ActivityOptions at all, puts the app on the " +
                "external display anyway, because the active DeX/desktop session - not our app - " +
                "decides where new activities go. Falsified if the app appears on the phone screen, " +
                "which would mean DeX does not route foreign launches and any D2 result is about " +
                "setLaunchDisplayId alone. This is a genuinely different mechanism from D2 and is " +
                "the one most likely to be mistaken for it.",
            method = "Context.startActivity(launchIntent) with no options bundle",
            procedure = "Wait for the lab to be foreground again after D2 (otherwise this is a " +
                "background activity start and measures the wrong thing), then launch the same " +
                "package with no options, and ask the tester the same question. Compare with D2: " +
                "D4 external + D2 not = DeX routes, we do not. Both external = D2's result cannot " +
                "be attributed to setLaunchDisplayId, because the same thing happens without it. " +
                "Both on the phone = neither mechanism placed anything externally in this session.",
            preconditions = listOf(
                "D1 found a launch candidate.",
                "The lab is foreground again, so background-activity-launch restrictions do not apply.",
                "For the hypothesis to be under test at all, a DeX/desktop session must actually be " +
                    "running. If it is not, D4 still runs but measures ordinary launch behaviour, and " +
                    "the outcome says so.",
            ),
            tier = PrivilegeTier.ORDINARY_APP,
            nextOnPass = listOf("D2", "B2"),
            nextOnFail = listOf("D2"),
        ),
    )

    // ------------------------------------------------------------------ runner

    /**
     * Runs D1 -> D2 -> D3 -> D4 and returns one [Outcome] per experiment, always four, always in
     * that order, whatever happened.
     *
     * Order matters. D1 chooses what D2 and D4 aim at. D3 reads D2's refusal, so it runs after it.
     * D4 runs last because it needs the lab back in the foreground, which is only possible once the
     * tester has answered D2 and returned to the app.
     *
     * @param targetDisplayId the display route D should aim at - typically
     *        `DisplayRole.preferredTarget(context)?.displayId`.
     * @param targetPackage   the package the tester chose, or null to let D1 pick a visible one.
     */
    suspend fun run(
        context: Context,
        targetDisplayId: Int,
        targetPackage: String? = null,
    ): List<Outcome> {
        // A verdict left over from an earlier run would silently grade this one.
        clearTesterVerdicts()

        SessionLogger.log(
            LabCategory.DISPLAY,
            "route_d_run_started",
            LabStatus.OBSERVED,
            mapOf(
                "targetDisplayId" to targetDisplayId.toString(),
                "requestedPackage" to (targetPackage ?: "-"),
                "displays" to describeDisplays(context),
                "targetRole" to describeTargetRole(context, targetDisplayId),
                "foreground" to foregroundState(context),
            ),
        )

        // --- D1 -----------------------------------------------------------------
        val d1 = visibility(context, targetPackage)
        record(d1.outcome, LabCategory.DEVICE)

        // --- D2 -----------------------------------------------------------------
        val d2 = attemptLaunch(context, "D2", d1.target, targetDisplayId)
        record(d2.outcome, LabCategory.DISPLAY)

        // --- D3 -----------------------------------------------------------------
        val d3 = constraints(context, targetDisplayId, d1.target, d2)
        record(d3, LabCategory.DISPLAY)

        // --- D4 -----------------------------------------------------------------
        // D2 pushed a foreign app to the front, so we are almost certainly backgrounded now. A
        // startActivity from the background is dropped silently on API 29+, which would look exactly
        // like "DeX did not route it" - the wrong finding for the right observation.
        val backForeground = awaitForeground(context, FOREGROUND_WINDOW_MS)
        val d4 = attemptLaunch(context, "D4", d1.target, null, compareWith = d2, foreground = backForeground)
        record(d4.outcome, LabCategory.DISPLAY)

        // Fold what this run genuinely measured into the capability x tier matrix, so the report
        // shows MEASURED beside the rows route D touched and DOCUMENTED beside every row it did not.
        foldIntoMatrix(d1.outcome, d2.outcome)

        SessionLogger.log(
            LabCategory.DISPLAY,
            "route_d_run_finished",
            LabStatus.OBSERVED,
            mapOf(
                "D1" to d1.outcome.status.name,
                "D2" to d2.outcome.status.name,
                "D3" to d3.status.name,
                "D4" to d4.outcome.status.name,
                "cleanup" to "The apps launched by D2 and D4 are still running: an app cannot finish " +
                    "an activity belonging to another package. Dismiss them by hand before the next " +
                    "run, or the next run will be looking at this one's leftovers.",
            ),
        )
        return listOf(d1.outcome, d2.outcome, d3, d4.outcome)
    }

    // ------------------------------------------------------------------ D1

    /** What D1 established, plus the launch candidate it picked for D2 and D4. */
    private data class Visibility(val outcome: Outcome, val target: LaunchTarget?)

    /** A package we may attempt to launch, with the intent the platform gave us for it. */
    private data class LaunchTarget(val packageName: String, val label: String, val intent: Intent)

    @Suppress("DEPRECATION") // The Flags overloads only exist from API 33; minSdk here is 29.
    private fun visibility(context: Context, requested: String?): Visibility {
        val pm = context.packageManager
        val self = context.packageName

        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved: List<ResolveInfo>
        try {
            resolved = pm.queryIntentActivities(launcherIntent, 0)
        } catch (t: Throwable) {
            val ev = mapOf("exception" to describe(t))
            SessionLogger.log(LabCategory.DEVICE, "route_d_visibility_error", LabStatus.ERROR, ev)
            return Visibility(
                Outcome(
                    "D1", LabStatus.ERROR,
                    "queryIntentActivities(ACTION_MAIN/CATEGORY_LAUNCHER) threw " + describe(t) + ".",
                    "Nothing is established about package visibility, and therefore nothing about " +
                        "what D2 and D4 could have aimed at. This says nothing about the platform's " +
                        "visibility rules.",
                    ev,
                    "Re-run D1. If the same type is thrown again, that type is itself the finding.",
                ),
                null,
            )
        }

        val distinct = resolved.mapNotNull { it.activityInfo?.packageName }.distinct().sorted()
        val others = distinct.filter { it != self }
        val installedCount = runCatching { pm.getInstalledPackages(0).size }.getOrNull()

        // The manifest declares a <queries> element containing a MAIN/LAUNCHER <intent>. Per the
        // package-visibility documentation that lifts filtering for packages exposing a matching
        // launcher activity - but "documented to lift it" and "lifted it on this Samsung build" are
        // different claims, and the count below is what separates them.
        val filtered = others.size < LAUNCHER_FILTER_THRESHOLD
        val visibilityVerdict = when {
            others.isEmpty() -> "no package other than our own is visible"
            filtered -> "only " + others.size + " other packages are visible: consistent with " +
                "API 30+ filtering limiting us to what our own <queries> element names"
            else -> others.size.toString() + " other packages are visible: consistent with the " +
                "declared MAIN/LAUNCHER <queries> intent granting broad launcher visibility on this build"
        }

        val chosen: LaunchTarget? = pickTarget(context, requested, others)

        val evidence = linkedMapOf(
            "launcherResolveInfos" to resolved.size.toString(),
            "launcherDistinctPackages" to distinct.size.toString(),
            "launcherPackagesExcludingSelf" to others.size.toString(),
            "installedPackagesVisible" to (installedCount?.toString() ?: "unreadable"),
            "filterThreshold" to LAUNCHER_FILTER_THRESHOLD.toString(),
            "looksFiltered" to filtered.toString(),
            "sdkInt" to Build.VERSION.SDK_INT.toString(),
            "holdsQueryAllPackages" to holdsPermission(context, "android.permission.QUERY_ALL_PACKAGES").toString(),
            // Capped so the evidence file stays readable; the count above is the measurement.
            "visiblePackagesSample" to others.take(25).joinToString(","),
            "requestedPackage" to (requested ?: "-"),
            "chosenPackage" to (chosen?.packageName ?: "-"),
            "chosenReason" to targetReason(requested, chosen, others),
        )

        SessionLogger.log(LabCategory.DEVICE, "route_d_visibility_measured", LabStatus.OBSERVED, evidence)

        // The two questions are graded separately on purpose. "How much can we see" was measured.
        // "What is installed on this phone" was not, and a short list is a visibility artefact.
        SessionLogger.log(
            Observation(
                key = "route.d1.installed_apps_enumerable",
                value = "-",
                status = LabStatus.NOT_TESTED,
                note = "An ordinary app without QUERY_ALL_PACKAGES cannot enumerate what is " +
                    "installed on API 30+. The counts in D1 describe OUR VISIBILITY, not the device. " +
                    "No package may be called absent on this evidence.",
                category = LabCategory.DEVICE,
            ),
        )

        val observation = "queryIntentActivities(ACTION_MAIN/CATEGORY_LAUNCHER) returned " +
            resolved.size + " resolve infos across " + distinct.size + " distinct packages (" +
            others.size + " excluding ourselves). getInstalledPackages(0) returned " +
            (installedCount?.toString() ?: "unreadable") + " entries. QUERY_ALL_PACKAGES held: " +
            holdsPermission(context, "android.permission.QUERY_ALL_PACKAGES") + "."

        val conclusion = "This measures what this app is ALLOWED TO SEE - " + visibilityVerdict +
            ". It is not a statement about which apps are installed: that question is separately " +
            "graded NOT_TESTED, because a filtered list cannot answer it and an unfiltered-looking " +
            "list cannot prove completeness either. " +
            (
                if (chosen != null) {
                    "D2 and D4 will aim at " + chosen.packageName + " (" + chosen.label + ")."
                } else {
                    "No launchable foreign package was visible, so D2 and D4 have nothing to aim at " +
                        "and will be NOT_TESTED - which is a fact about our visibility, not about the phone."
                }
                )

        return Visibility(
            Outcome(
                "D1", LabStatus.OBSERVED, observation, conclusion, evidence,
                if (chosen != null) {
                    "Run D2 against " + chosen.packageName + ", with a human watching both screens."
                } else {
                    "Pick a package by hand on the route D screen, or add it to the manifest " +
                        "<queries> element and rebuild; do not conclude anything about installed apps."
                },
            ),
            chosen,
        )
    }

    /**
     * Chooses the package to launch.
     *
     * Our own package is never a candidate: launching ourselves somewhere is route C's question and
     * answering it here would quietly turn route D into a duplicate of route C.
     */
    @Suppress("DEPRECATION") // getApplicationInfo(String, Int) only gained a Flags overload at API 33.
    private fun pickTarget(context: Context, requested: String?, visible: List<String>): LaunchTarget? {
        val pm = context.packageManager
        val self = context.packageName
        val order = buildList {
            requested?.let { add(it) }
            addAll(visible)
        }.distinct().filter { it != self }

        for (pkg in order) {
            val intent = runCatching { pm.getLaunchIntentForPackage(pkg) }.getOrNull() ?: continue
            val label = runCatching {
                pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString()
            }.getOrDefault(pkg)
            return LaunchTarget(pkg, label, intent)
        }
        return null
    }

    private fun targetReason(requested: String?, chosen: LaunchTarget?, visible: List<String>): String = when {
        chosen == null && requested != null ->
            "the requested package produced no launch intent and no visible fallback did either"
        chosen == null -> "no visible package other than our own produced a launch intent"
        requested != null && chosen.packageName == requested -> "requested by the tester"
        requested != null -> "the requested package had no launch intent; fell back to the first visible one"
        else -> "first visible package with a launcher intent, out of " + visible.size
    }

    // ------------------------------------------------------------------ D2 / D4

    /** Everything one launch attempt produced, kept raw so D3 and D4 can read it. */
    private data class Attempt(
        val outcome: Outcome,
        /** null for D4, which deliberately requests no display. */
        val requestedDisplayId: Int?,
        val threw: Throwable?,
        val securityMessage: String?,
        val sighting: Sighting?,
        val wasForeground: Boolean,
    )

    /**
     * The shared body of D2 and D4.
     *
     * The difference between them is one line - whether an options bundle carrying a launch display
     * id is passed - and that is the entire point: everything else is held constant so the
     * comparison means something.
     *
     * Two things this function refuses to do:
     *  1. treat "startActivity threw nothing" as success. The platform may redirect a launch to the
     *     default display, or drop it entirely under background-activity-launch rules, without any
     *     error surface at all.
     *  2. resolve a disagreement between the API-side signals and the tester. When they conflict the
     *     outcome is graded NOT_TESTED - not because the test could not be run, but because the run
     *     produced two contradictory readings and therefore no usable answer. Grading it OBSERVED
     *     would make a positive claim (see LabStatus.isPositiveClaim) out of a contradiction.
     */
    private suspend fun attemptLaunch(
        context: Context,
        experimentId: String,
        target: LaunchTarget?,
        requestedDisplayId: Int?,
        compareWith: Attempt? = null,
        foreground: Boolean? = null,
    ): Attempt {
        val usesDisplayId = requestedDisplayId != null
        if (target == null) {
            val ev = mapOf(
                "requestedDisplayId" to (requestedDisplayId?.toString() ?: "none"),
                "reason" to "D1 found no launchable foreign package visible to this app",
            )
            return Attempt(
                Outcome(
                    experimentId, LabStatus.NOT_TESTED,
                    "No launch was attempted: D1 found no package other than ours with a launcher " +
                        "intent visible to this app.",
                    "Nothing is established about launching a foreign app" +
                        (if (usesDisplayId) " on a chosen display" else " while DeX is active") +
                        ". This is a limit of our package visibility, not a platform refusal, and it " +
                        "must never be recorded as one.",
                    ev,
                    "Name a package on the route D screen, or extend the manifest <queries> element, " +
                        "then re-run.",
                ),
                requestedDisplayId, null, null, null, false,
            )
        }

        val dm = context.getSystemService(DisplayManager::class.java)
        val display = requestedDisplayId?.let { id -> runCatching { dm?.getDisplay(id) }.getOrNull() }
        val flags = display?.let { runCatching { DisplayRole.decodeFlags(it) }.getOrDefault(emptyList()) }
            ?: emptyList()
        val inPresentationCategory = requestedDisplayId != null && runCatching {
            dm?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
                ?.any { it.displayId == requestedDisplayId } == true
        }.getOrDefault(false)

        val wasForeground = foreground ?: isForeground(context)
        val displaysBefore = describeDisplays(context)

        val evidence = linkedMapOf(
            "targetPackage" to target.packageName,
            "targetLabel" to target.label,
            "targetComponent" to (target.intent.component?.flattenToShortString() ?: "-"),
            "requestedDisplayId" to (requestedDisplayId?.toString() ?: "none (control)"),
            "usesLaunchDisplayId" to usesDisplayId.toString(),
            "displayListed" to (display != null).toString(),
            "displayName" to (display?.let { runCatching { it.name }.getOrDefault("?") } ?: "-"),
            "displayFlags" to (flags.joinToString("|").ifEmpty { "none" }),
            "inPresentationCategory" to inPresentationCategory.toString(),
            "callerForegroundAtLaunch" to wasForeground.toString(),
            "displaysBefore" to displaysBefore,
        )

        val intent = Intent(target.intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val launchedAt = SystemClock.elapsedRealtime()
        var threw: Throwable? = null
        var securityMessage: String? = null

        try {
            if (requestedDisplayId != null) {
                val options = ActivityOptions.makeBasic().setLaunchDisplayId(requestedDisplayId)
                context.startActivity(intent, options.toBundle())
            } else {
                // Deliberately NO options bundle: D4 asks what the system does on its own.
                context.startActivity(intent)
            }
            SessionLogger.log(
                LabCategory.DISPLAY, "route_d_launch_requested", LabStatus.OBSERVED,
                evidence + ("experiment" to experimentId),
            )
        } catch (e: SecurityException) {
            // The documented way the activity manager refuses a launch onto a display the caller may
            // not use. The message names the uid and the display and is captured verbatim: it is the
            // single most useful artefact route D can produce, and D3 reads it back.
            threw = e
            securityMessage = e.message ?: "no message"
            val ev = evidence + mapOf("exception" to describe(e), "securityMessage" to securityMessage)
            SessionLogger.log(LabCategory.DISPLAY, "route_d_launch_refused", LabStatus.UNSUPPORTED, ev)
            return Attempt(
                Outcome(
                    experimentId, LabStatus.UNSUPPORTED,
                    "startActivity(" + target.packageName +
                        (if (usesDisplayId) ", setLaunchDisplayId(" + requestedDisplayId + ")" else ", no options") +
                        ") threw SecurityException: " + securityMessage,
                    "The platform refuses this launch to an app with our privileges and says so " +
                        "explicitly. A real negative at the ORDINARY_APP tier for this package and " +
                        "this display. It says nothing about other packages, about other displays, " +
                        "or about the adb/Shizuku/system-app tiers, which are analysis only in this " +
                        "build - and nothing about route C, where the activity is our own.",
                    ev,
                    "Copy the exception message into the privilege-tier matrix as MEASURED evidence " +
                        "for 'launch ANOTHER app on a display', then run D4 to find out whether DeX " +
                        "routes the app there without us asking.",
                ),
                requestedDisplayId, threw, securityMessage, null, wasForeground,
            )
        } catch (e: ActivityNotFoundException) {
            threw = e
            val ev = evidence + ("exception" to describe(e))
            SessionLogger.log(LabCategory.DISPLAY, "route_d_launch_not_found", LabStatus.NOT_TESTED, ev)
            return Attempt(
                Outcome(
                    experimentId, LabStatus.NOT_TESTED,
                    "startActivity threw ActivityNotFoundException: " + (e.message ?: "no message"),
                    "The chosen component could not be started at all, so the display question was " +
                        "never reached. This is about the target app or our visibility of it, not " +
                        "about launching on displays.",
                    ev,
                    "Choose a different package in D1 and re-run.",
                ),
                requestedDisplayId, threw, null, null, wasForeground,
            )
        } catch (t: Throwable) {
            threw = t
            val ev = evidence + ("exception" to describe(t))
            SessionLogger.log(LabCategory.DISPLAY, "route_d_launch_error", LabStatus.ERROR, ev)
            return Attempt(
                Outcome(
                    experimentId, LabStatus.ERROR,
                    "startActivity threw " + describe(t) + ".",
                    "The mechanism failed to execute. Nothing may be concluded about whether an app " +
                        "can launch another app" + (if (usesDisplayId) " on a chosen display." else "."),
                    ev,
                    "Re-run. If the same type is thrown again, that type is itself the finding.",
                ),
                requestedDisplayId, threw, null, null, wasForeground,
            )
        }

        // --- what the API can still tell us afterwards --------------------------
        delay(LAUNCH_SETTLE_MS)
        val settleMs = SystemClock.elapsedRealtime() - launchedAt
        evidence["displaysAfter"] = describeDisplays(context)
        evidence["displaySetChanged"] = (evidence["displaysAfter"] != displaysBefore).toString()
        evidence["settleMs"] = settleMs.toString()
        evidence["callerForegroundAfter"] = isForeground(context).toString()
        evidence["runningTasksReadable"] = runningTasksReadable(context)

        // --- and what only a human can ------------------------------------------
        SessionLogger.marker(
            experimentId + ": look at BOTH screens now. Which one is showing " + target.label +
                "? Record it on the route D screen within " + (TESTER_WINDOW_MS / 1000) + " s.",
            LabCategory.USER,
        )
        val verdict = awaitTesterVerdict(experimentId, TESTER_WINDOW_MS)
        evidence["testerSighting"] = verdict?.sighting?.name ?: "not reported"
        verdict?.note?.let { evidence["testerNote"] = it }

        // The API-side signal: if the foreign app had taken the phone screen we would normally have
        // lost the foreground. Suggestive, never conclusive - DeX, split-screen, pop-up view and an
        // overlay all break the correspondence.
        val stillForeground = evidence["callerForegroundAfter"] == "true"
        val apiHint = when {
            !wasForeground ->
                "we were not in the foreground when we launched, so this attempt may have been " +
                    "dropped by the background-activity-launch restrictions with no exception raised"
            stillForeground ->
                "we kept the foreground on the phone panel, which is consistent with the activity " +
                    "having landed somewhere else - and equally consistent with it not having " +
                    "launched at all"
            else ->
                "we lost the foreground, which is consistent with the activity having landed on the " +
                    "phone panel - and equally consistent with DeX moving focus for another reason"
        }

        val observation = buildString {
            append("startActivity(").append(target.packageName)
            append(if (usesDisplayId) ", setLaunchDisplayId(" + requestedDisplayId + ")" else ", no options bundle")
            append(") returned without throwing. After ").append(settleMs).append(" ms: displays before [")
            append(displaysBefore).append("], after [").append(evidence["displaysAfter"]).append("]; ")
            append("caller foreground before=").append(wasForeground)
            append(", after=").append(stillForeground).append(". ")
            append("Tester verdict: ")
            append(verdict?.let { it.sighting.label + (it.note?.let { n -> " (\"" + n + "\")" } ?: "") }
                ?: "none recorded within " + (TESTER_WINDOW_MS / 1000) + " s")
            append(".")
        }

        val status: LabStatus
        val conclusion: String
        val nextStep: String

        val landedExternal = verdict?.sighting == Sighting.EXTERNAL_DISPLAY ||
            verdict?.sighting == Sighting.BOTH_SCREENS
        val landedPhone = verdict?.sighting == Sighting.PHONE_SCREEN
        val nothingSeen = verdict?.sighting == Sighting.NOTHING_VISIBLE
        val contradiction = (landedPhone && stillForeground && wasForeground) ||
            (landedExternal && !stillForeground)

        when {
            !wasForeground -> {
                status = LabStatus.NOT_TESTED
                conclusion = "The launch was made while this app was NOT in the foreground. On API " +
                    "29+ a background activity start is dropped silently, so a null result here is " +
                    "unattributable: it may be the display rule, or it may be the background rule. " +
                    "This is a confounded run, not a negative result. " + apiHint + "."
                nextStep = "Bring the lab to the foreground, dismiss the app the previous experiment " +
                    "left running, and re-run " + experimentId + "."
            }

            verdict == null -> {
                status = LabStatus.NOT_TESTED
                conclusion = "No exception was raised, and that is not evidence of success: the " +
                    "platform can redirect a launch to the default display, or drop it, without any " +
                    "error surface. Where the activity actually landed was not established, because " +
                    "no human reported it and no API available to an ordinary app can answer it " +
                    "(getRunningTasks and getRunningAppProcesses return only our own since API 21/22). " +
                    "The API-side hint, which is a hint and nothing more: " + apiHint + "."
                nextStep = "Re-run with someone watching the head unit, and record the sighting " +
                    "within the " + (TESTER_WINDOW_MS / 1000) + " s window."
            }

            contradiction -> {
                // Two independent readings that cannot both be right. Reporting either one alone
                // would be the exact failure mode this project exists to avoid.
                status = LabStatus.NOT_TESTED
                conclusion = "The two independent readings contradict each other: the tester reports " +
                    "the app " + (verdict?.sighting?.label ?: "?") + ", while the API-side signal says " +
                    apiHint + ". No usable answer was produced. Neither reading is discarded here, " +
                    "and neither is promoted: the foreground signal is known to be unreliable under " +
                    "DeX, multi-window and pop-up view, and the tester may have been looking at a " +
                    "screen the previous experiment left behind."
                nextStep = "Dismiss everything on both screens, re-run " + experimentId + " from a " +
                    "clean state, and note in the marker what was already on each screen beforehand."
            }

            nothingSeen -> {
                status = LabStatus.OBSERVED
                conclusion = "startActivity threw nothing and the tester saw nothing appear on either " +
                    "screen. The launch was therefore accepted by the API and had no visible effect - " +
                    "the silent-drop case, which is precisely why 'no exception' is never treated as " +
                    "success in this project. Cause not established; candidates to separate next, not " +
                    "to assume: a background-start drop, the target app declining to show on a " +
                    "secondary display, or the activity starting behind what is already displayed."
                nextStep = "Run D3 for the gating permissions and display flags, then D4 to see " +
                    "whether the same app appears when no display id is requested at all."
            }

            landedExternal -> {
                status = LabStatus.OBSERVED
                conclusion = (if (usesDisplayId) {
                    "A foreign app's activity was seen on the external screen after we requested " +
                        "display " + requestedDisplayId + ". Attribution is NOT yet established: D4 " +
                        "asks whether the same thing happens with no display id, and until D4 says " +
                        "otherwise this may be the DeX session routing the launch rather than " +
                        "setLaunchDisplayId honouring it. It also does not establish that the screen " +
                        "in question is a Miracast sink, nor what the head unit did with the pixels."
                } else {
                    "A foreign app's activity was seen on the external screen although NO display id " +
                        "was requested. The routing decision therefore came from the system - a DeX/" +
                        "desktop session or the display's own policy - and not from this app. If D2 " +
                        "also landed externally, D2's result cannot be attributed to " +
                        "setLaunchDisplayId, because the same thing happens without it."
                }) + testerAttribution(verdict)
                nextStep = if (usesDisplayId) {
                    "Run D4 - without it this result is unattributable."
                } else {
                    "Compare with D2 and, in route B, establish whether the session is Wireless DeX " +
                        "or ordinary mirroring: they are not the same mechanism."
                }
            }

            landedPhone -> {
                status = LabStatus.OBSERVED
                conclusion = (if (usesDisplayId) {
                    "The launch was accepted and the activity appeared on the PHONE screen even " +
                        "though display " + requestedDisplayId + " was requested. The API took the " +
                        "display id and the platform used a different display without raising " +
                        "anything - the same silent-override class of finding as a redirected route " +
                        "C launch. Which rule caused it is not established here; D3 records the " +
                        "candidate gates (permissions, FLAG_PRIVATE, presentation category, resize " +
                        "mode) without deciding between them."
                } else {
                    "With no display id requested, the activity appeared on the phone screen. The " +
                        "active session did not route this foreign launch to the external display, " +
                        "so if D2 did land externally that result belongs to setLaunchDisplayId and " +
                        "not to DeX. Whether a DeX session was actually running at the time is a " +
                        "route B question and is not assumed here."
                }) + testerAttribution(verdict)
                nextStep = "Run D3 and read the gates; then compare D2 and D4 side by side before " +
                    "concluding anything about either mechanism."
            }

            else -> { // UNSURE
                status = LabStatus.NOT_TESTED
                conclusion = "The tester could not tell which screen the app appeared on, so the " +
                    "question this experiment asks was not answered. The API-side hint is only a " +
                    "hint: " + apiHint + "."
                nextStep = "Re-run with both screens visible from one position, and with both screens " +
                    "cleared beforehand."
            }
        }

        SessionLogger.log(
            LabCategory.DISPLAY,
            "route_d_launch_settled",
            status,
            evidence + ("experiment" to experimentId),
        )

        val comparison = compareWith?.let { comparisonNote(it, verdict?.sighting) } ?: ""

        return Attempt(
            Outcome(experimentId, status, observation, conclusion + comparison, evidence, nextStep),
            requestedDisplayId, threw, securityMessage, verdict?.sighting, wasForeground,
        )
    }

    /** Every tester-sourced statement is labelled as one. It is evidence, not instrumentation. */
    private fun testerAttribution(v: TesterVerdict?): String {
        if (v == null) return ""
        return " The screen this claim rests on was reported by the human tester (" + v.sighting.name +
            (v.note?.let { ", \"" + it + "\"" } ?: "") + "), not measured by an API; no API available " +
            "to an ordinary app can see where a foreign activity landed."
    }

    /** The D4-vs-D2 differential, appended to D4's conclusion. This is what the control is for. */
    private fun comparisonNote(d2: Attempt, d4Sighting: Sighting?): String {
        val d2Sighting = d2.sighting
        if (d2Sighting == null || d4Sighting == null) {
            return " No D2/D4 comparison is possible: " +
                (if (d2Sighting == null) "D2 produced no tester verdict" else "D4 produced no tester verdict") +
                ", and the comparison is the only thing that separates 'we placed it' from 'DeX placed it'."
        }
        val d2External = d2Sighting == Sighting.EXTERNAL_DISPLAY || d2Sighting == Sighting.BOTH_SCREENS
        val d4External = d4Sighting == Sighting.EXTERNAL_DISPLAY || d4Sighting == Sighting.BOTH_SCREENS
        return when {
            d4External && d2External ->
                " D2 and D4 both landed externally. The launch display id therefore explains nothing " +
                    "on its own: the system routes foreign launches to that display with or without " +
                    "it. Route D's mechanism question is unresolved in favour of the system."
            d4External && !d2External ->
                " D4 landed externally while D2 did not. That is the inverted result: not asking for " +
                    "a display worked where asking for one did not, which points at the session's own " +
                    "routing policy and makes setLaunchDisplayId look actively counterproductive here. " +
                    "Worth repeating before it is believed."
            !d4External && d2External ->
                " D4 stayed on the phone while D2 landed externally. This is the only combination in " +
                    "which the placement can be attributed to setLaunchDisplayId rather than to the " +
                    "session - on this device, this build, this display, and these two runs."
            else ->
                " Neither D2 nor D4 placed the app externally in this session. Nothing is concluded " +
                    "about either mechanism in general: two attempts on one build is not a platform " +
                    "verdict, and route B has not established what kind of session was even running."
        }
    }

    // ------------------------------------------------------------------ D3

    /**
     * Reads the documented gates off this device.
     *
     * This experiment states no outcome in advance. It records which permissions we hold, what the
     * display's flags are, whether the target activity is resizeable, and - if D2 was refused - the
     * exact `SecurityException` message, which is the platform naming its own rule.
     *
     * The permissions checked are the ones AOSP's launch-on-display path consults. They are
     * signature or privileged permissions, so an ordinary app is expected not to hold them, but
     * "expected" is not "measured" and the values below are read, not assumed.
     */
    private fun constraints(
        context: Context,
        targetDisplayId: Int,
        target: LaunchTarget?,
        d2: Attempt,
    ): Outcome {
        val dm = context.getSystemService(DisplayManager::class.java)
        val display = runCatching { dm?.getDisplay(targetDisplayId) }.getOrNull()
        val flags = display?.let { runCatching { DisplayRole.decodeFlags(it) }.getOrDefault(emptyList()) }
            ?: emptyList()
        val inPresentationCategory = runCatching {
            dm?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
                ?.any { it.displayId == targetDisplayId } == true
        }.getOrDefault(false)

        val gates = GATE_PERMISSIONS.associateWith { holdsPermission(context, it) }
        val canDrawOverlays = runCatching { Settings.canDrawOverlays(context) }.getOrNull()
        val resize = targetResizeMode(context, target)

        val evidence = linkedMapOf(
            "targetDisplayId" to targetDisplayId.toString(),
            "displayListed" to (display != null).toString(),
            "displayFlags" to (flags.joinToString("|").ifEmpty { "none" }),
            "displayIsPrivate" to flags.contains("FLAG_PRIVATE").toString(),
            "inPresentationCategory" to inPresentationCategory.toString(),
            "targetPackage" to (target?.packageName ?: "-"),
            "targetActivityResizeMode" to resize.first,
            "targetActivityResizeModeSource" to resize.second,
            "canDrawOverlays" to (canDrawOverlays?.toString() ?: "unreadable"),
            "d2Status" to d2.outcome.status.name,
            "d2Threw" to (d2.threw?.let { it::class.java.simpleName } ?: "nothing"),
            "d2SecurityMessage" to (d2.securityMessage ?: "-"),
        )
        gates.forEach { (perm, held) -> evidence["perm." + perm.substringAfterLast('.')] = held.toString() }

        SessionLogger.log(LabCategory.DISPLAY, "route_d_constraints_read", LabStatus.OBSERVED, evidence)

        val heldGates = gates.filterValues { it }.keys
        val gateSummary = if (heldGates.isEmpty()) {
            "none of " + gates.keys.joinToString(", ") { it.substringAfterLast('.') }
        } else {
            heldGates.joinToString(", ") { it.substringAfterLast('.') }
        }
        val observation = buildString {
            append("Gating permissions held by this app: ")
            append(gateSummary)
            append(". canDrawOverlays=").append(canDrawOverlays?.toString() ?: "unreadable")
            append(". Display ").append(targetDisplayId).append(": listed=").append(display != null)
            append(", flags=").append(evidence["displayFlags"])
            append(", inPresentationCategory=").append(inPresentationCategory)
            append(". Target activity resize mode: ").append(resize.first).append(". ")
            append(
                d2.securityMessage?.let { "D2 was refused with SecurityException: \"" + it + "\"" }
                    ?: ("D2 raised no SecurityException (it " +
                        (d2.threw?.let { "threw " + describe(it) } ?: "returned normally") + ")"),
            )
            append(".")
        }

        val conclusion = buildString {
            append(
                "These are the platform's own inputs to the decision, read on this device rather " +
                    "than quoted from documentation. The documented refusal path for a cross-uid " +
                    "launch onto a display the caller may not use is a SecurityException thrown out " +
                    "of startActivity, naming the calling uid and the display; ",
            )
            if (d2.securityMessage != null) {
                append(
                    "that is what happened, and the message above is the platform stating its own " +
                        "rule. That message - not this file's model of it - is the evidence.",
                )
            } else {
                append(
                    "no such exception was raised in D2. That does NOT mean the launch was " +
                        "permitted: a silent redirect to the default display and a silent " +
                        "background-start drop both raise nothing either, so absence of a refusal " +
                        "is not permission. What actually happened is D2's and D4's question.",
                )
            }
            append(
                " No causal claim is made between any single gate above and D2's result: several of " +
                    "them can independently produce the same visible outcome, and separating them " +
                    "needs one experiment per gate, which this build does not run.",
            )
            if (display == null) {
                append(
                    " Note that display " + targetDisplayId + " is not currently listed, so the " +
                        "display-side gates were read as absent rather than as permissive.",
                )
            }
        }

        return Outcome(
            "D3", LabStatus.OBSERVED, observation, conclusion, evidence,
            if (d2.securityMessage != null) {
                "Record the exact message in PrivilegeTiers as MEASURED evidence for 'launch ANOTHER " +
                    "app on a display', and re-run D2 against a display that is in " +
                    "DISPLAY_CATEGORY_PRESENTATION to see whether the message changes."
            } else {
                "Run D4, then repeat D2 with a resizeable target app, changing exactly one gate at a " +
                    "time so a difference can be attributed."
            },
        )
    }

    /**
     * The permissions AOSP's launch-on-display and background-start checks consult. All of them are
     * signature or privileged, or a special app-op; they are read, never requested. This app
     * declares none of them and must not start doing so.
     */
    private val GATE_PERMISSIONS = listOf(
        "android.permission.ACTIVITY_EMBEDDING",
        "android.permission.INTERNAL_SYSTEM_WINDOW",
        "android.permission.MANAGE_ACTIVITY_TASKS",
        "android.permission.SYSTEM_ALERT_WINDOW",
        "android.permission.START_ANY_ACTIVITY",
    )

    // ------------------------------------------------------------------ helpers

    private fun holdsPermission(context: Context, permission: String): Boolean = runCatching {
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /**
     * Resize mode of the FOREIGN target activity.
     *
     * `ActivityInfo.resizeMode` is a non-SDK field, so this is reflective and may be refused - which
     * is reported as a value rather than swallowed. It matters because an unresizeable activity is
     * one of the documented reasons the platform declines to place an activity on a secondary
     * display, and a D2 result with an unknown resize mode is a weaker finding than one with a known
     * mode.
     */
    @Suppress("DEPRECATION") // getActivityInfo(ComponentName, Int) only gained a Flags overload at API 33.
    private fun targetResizeMode(context: Context, target: LaunchTarget?): Pair<String, String> {
        val component: ComponentName = target?.intent?.component
            ?: return "n/a" to "no launch target was resolved, so there was no activity to read"
        return try {
            val info: ActivityInfo = context.packageManager.getActivityInfo(component, 0)
            val field = ActivityInfo::class.java.getDeclaredField("resizeMode")
            field.isAccessible = true
            val mode = field.getInt(info)
            val label = when (mode) {
                0 -> "0 (unresizeable)"
                1 -> "1 (resizeable and pipable, deprecated)"
                2 -> "2 (resizeable)"
                3 -> "3 (resizeable and pipable)"
                else -> mode.toString()
            }
            label to "ActivityInfo.resizeMode of " + component.flattenToShortString() +
                ", read reflectively (non-SDK field)"
        } catch (t: Throwable) {
            "unreadable (" + t::class.java.simpleName + ")" to
                "Non-SDK reflection was refused or the field is absent on this build. Nothing is " +
                    "assumed about the target's resizeability in its place."
        }
    }

    /** Waits for the tester to answer, or gives up. Never invents an answer. */
    private suspend fun awaitTesterVerdict(experimentId: String, timeoutMs: Long): TesterVerdict? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            verdicts[experimentId]?.let { return it }
            delay(POLL_MS)
        }
        return verdicts[experimentId]
    }

    /**
     * Waits until the lab is resumed again, so D4 is not a background activity start.
     *
     * Returns whether the foreground was regained. A false here is not a failure of D4; it is the
     * reason D4's outcome will be NOT_TESTED rather than negative.
     */
    private suspend fun awaitForeground(context: Context, timeoutMs: Long): Boolean {
        if (isForeground(context)) return true
        SessionLogger.marker(
            "Return to MiraiCast Lab now (Back or Recents). D4 must be launched while the lab is in " +
                "the foreground, or Android drops the start silently and the result means nothing.",
            LabCategory.USER,
        )
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (isForeground(context)) {
                SessionLogger.log(
                    LabCategory.DISPLAY, "route_d_foreground_regained", LabStatus.OBSERVED,
                    mapOf("waitedMs" to (timeoutMs - (deadline - SystemClock.elapsedRealtime())).toString()),
                )
                return true
            }
            delay(POLL_MS)
        }
        SessionLogger.log(
            LabCategory.DISPLAY, "route_d_foreground_not_regained", LabStatus.NOT_TESTED,
            mapOf(
                "waitedMs" to timeoutMs.toString(),
                "consequence" to "D4 runs as a background activity start and is graded NOT_TESTED",
            ),
        )
        return false
    }

    /**
     * Are we in the foreground?
     *
     * Read from our own Activity's lifecycle, which is the only thing an ordinary app can trust:
     * ActivityManager.getRunningAppProcesses() returns only our own process since API 22 and its
     * importance field has been unreliable across OEM builds. When there is no Activity context at
     * all this returns false, which is the conservative direction: it turns results into
     * NOT_TESTED rather than into claims.
     */
    private suspend fun isForeground(context: Context): Boolean = withContext(Dispatchers.Main) {
        val activity = context.findActivity() ?: return@withContext false
        val owner = activity as? LifecycleOwner
            ?: return@withContext runCatching { activity.hasWindowFocus() }.getOrDefault(false)
        runCatching {
            owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }.getOrDefault(false)
    }

    private suspend fun foregroundState(context: Context): String = withContext(Dispatchers.Main) {
        val activity = context.findActivity() ?: return@withContext "no Activity context"
        val state = runCatching {
            (activity as? LifecycleOwner)?.lifecycle?.currentState?.name ?: "unknown"
        }.getOrDefault("unreadable")
        val focus = runCatching { activity.hasWindowFocus().toString() }.getOrDefault("unreadable")
        "lifecycle=" + state + " hasWindowFocus=" + focus
    }

    /**
     * Records why we do not use the task list to answer "where did it land".
     *
     * `getRunningTasks` has been restricted to the caller's own tasks since API 21 and
     * `getRunningAppProcesses` to the caller's own process since API 22. We call the latter only to
     * record the size of what we get back, as evidence for the restriction - never as a signal about
     * another app.
     */
    private fun runningTasksReadable(context: Context): String = runCatching {
        val am = context.getSystemService(ActivityManager::class.java)
            ?: return@runCatching "no ActivityManager"
        val procs = am.runningAppProcesses?.size ?: 0
        "getRunningAppProcesses returned " + procs + " entries (own process only since API 22); " +
            "getRunningTasks is restricted to our own tasks since API 21 and is not called"
    }.getOrDefault("unreadable")

    /**
     * Writes the privilege-tier matrix with route D's measurable rows folded in.
     *
     * Two deliberate restraints:
     *  - D1's OBSERVED status is NOT copied onto "enumerate all installed packages". D1 measured how
     *    much this app can SEE; it never attempted full enumeration, because the app does not declare
     *    QUERY_ALL_PACKAGES. That row therefore stays NOT_TESTED and only gains the counts as a note.
     *  - the documented tier of a row is never recomputed from a measurement. A refusal here is a
     *    refusal on this device, this display and this build, and does not establish where the
     *    lowest enabling tier lies.
     */
    private fun foldIntoMatrix(d1: Outcome, d2: Outcome) {
        val measurements = mapOf(
            PrivilegeTiers.CAP_ENUMERATE_PACKAGES to (
                LabStatus.NOT_TESTED to (
                    "D1 measured visibility, not enumeration (QUERY_ALL_PACKAGES is not declared): " +
                        d1.observation
                    )
                ),
            PrivilegeTiers.CAP_LAUNCH_OTHER_APP_ON_DISPLAY to (
                d2.status to ("D2 [" + d2.status.name + "]: " + d2.observation)
                ),
        )
        runCatching { PrivilegeTiers.logMatrix(PrivilegeTiers.rowsWith(measurements)) }
            .onFailure {
                SessionLogger.log(
                    LabCategory.DEVICE, "route_d_matrix_fold_failed", LabStatus.ERROR,
                    mapOf("exception" to describe(it)),
                )
            }
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
        val dm = context.getSystemService(DisplayManager::class.java)
            ?: return@runCatching "no DisplayManager"
        dm.displays.joinToString("|") { it.displayId.toString() + ":" + it.name }
    }.getOrDefault("unreadable")

    /**
     * What DisplayRole thinks the target is. Context only: route D's outcomes never depend on it,
     * because "is this the Miracast surface?" is a separate and weaker question that DisplayRole
     * answers at INFERRED at best.
     */
    private fun describeTargetRole(context: Context, targetDisplayId: Int): String = runCatching {
        DisplayRole.verdicts(context).firstOrNull { it.displayId == targetDisplayId }
            ?.let { it.role.name + " (" + it.status.name + ")" } ?: "not listed"
    }.getOrDefault("unreadable")

    private fun describe(t: Throwable): String =
        t::class.java.simpleName + ": " + (t.message ?: "no message")

    /** One outcome, written both as a rich event and as a gradeable observation. */
    private fun record(o: Outcome, category: LabCategory) {
        SessionLogger.log(
            category,
            "route_d_" + o.experimentId.lowercase() + "_outcome",
            o.status,
            buildMap<String, String> {
                put("experiment", o.experimentId)
                put("observation", o.observation)
                put("conclusion", o.conclusion)
                o.nextStep?.let { put("nextStep", it) }
                putAll(o.evidence)
            },
        )
        SessionLogger.log(o.toObservation(category))
    }
}
