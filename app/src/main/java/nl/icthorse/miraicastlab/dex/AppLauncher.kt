package nl.icthorse.miraicastlab.dex

import android.app.Activity
import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import nl.icthorse.miraicastlab.core.LabCategory
import nl.icthorse.miraicastlab.core.LabStatus
import nl.icthorse.miraicastlab.core.Observation
import nl.icthorse.miraicastlab.core.Outcome
import nl.icthorse.miraicastlab.core.SessionLogger
import nl.icthorse.miraicastlab.route.DisplayRole
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Putting an app on the DeX / external screen **without emulating any input at all**.
 *
 * The rest of this module asks whether this app can be the keyboard and mouse of a DeX desktop -
 * a question whose answer depends entirely on privilege. This file asks a different and much
 * cheaper question: for the specific goal "get YouTube onto that screen and search on it", is any
 * input emulation needed in the first place? An `Intent` plus
 * `ActivityOptions.setLaunchDisplayId(id)` is an ordinary-app API. No accessibility service, no
 * Bluetooth HID, no shell.
 *
 * ### What this file can and cannot establish
 * `startActivity` returning without throwing means one thing only: **the platform accepted the
 * start**. It does not mean an activity was created, does not mean it landed on the display we
 * asked for, and certainly does not mean anything appeared on the head unit. Android gives an
 * ordinary app no way to check: `getRunningTasks` has been restricted to the caller's own tasks
 * since API 21 and `getRunningAppProcesses` to the caller's own process since API 22, so there is
 * no API answer to "where did that activity go?". Three things are therefore recorded separately
 * and never merged into a verdict:
 *
 *  1. what the API did - return value, exception class, the exact `SecurityException` text;
 *  2. what the platform still reports afterwards - the display set, before and after;
 *  3. what a human saw - [recordTesterVerdict], attributed as a human reading, never as measurement.
 *
 * When (1) and (3) disagree, [reconcile] says so and grades the result NOT_TESTED. The
 * disagreement is the finding; resolving it by picking the flattering reading is the exact failure
 * this project exists to avoid. This mirrors route D, whose shape is deliberately reused here.
 */
object AppLauncher {

    /** How long a launch is given to settle before the display set is re-read. */
    const val SETTLE_MS: Long = 1_200L

    /** Sequence behind [nextAttemptId]; attempts must be distinguishable in the evidence file. */
    private val sequence = AtomicInteger(0)

    // ------------------------------------------------------------------ what we may see

    /**
     * One app this process is *allowed to see* behind a MAIN/LAUNCHER query.
     *
     * The name is `LaunchableApp`, not `InstalledApp`, on purpose: this is a statement about our
     * visibility, not about the device.
     */
    data class LaunchableApp(
        val packageName: String,
        val label: String,
        val isYouTube: Boolean,
    )

    /**
     * The apps with a launcher activity that are visible to this process.
     *
     * On API 30+ package visibility is filtered by our own manifest. This app deliberately does not
     * hold `QUERY_ALL_PACKAGES`; it declares a `<queries>` element containing a MAIN/LAUNCHER
     * `<intent>`, which lifts filtering for packages exposing a launcher activity - *documented* to
     * lift it, which is not the same claim as "lifted it on this One UI build".
     *
     * Synchronous, and a PackageManager query over every launcher activity is not free: call it from
     * a background dispatcher or a LaunchedEffect, not from composition.
     *
     * So a short list here is an artefact of what we may SEE. "Which apps exist on this phone" is
     * a different question, it is not answerable at this tier, and it is logged NOT_TESTED every
     * time this runs. It must never be recorded as UNSUPPORTED, and absence from this list must
     * never be reported as "not installed".
     */
    @Suppress("DEPRECATION") // queryIntentActivities gained a ResolveInfoFlags overload only at API 33; minSdk is 29.
    fun installedApps(context: Context): List<LaunchableApp> {
        val pm = context.packageManager
        val self = context.packageName
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

        val resolved = try {
            pm.queryIntentActivities(intent, 0)
        } catch (t: Throwable) {
            SessionLogger.log(
                LabCategory.DEX, "app_launcher_visibility_error", LabStatus.ERROR,
                mapOf("exception" to describe(t)),
            )
            emptyList()
        }

        val apps = resolved
            .mapNotNull { ri -> ri.activityInfo?.packageName?.let { pkg -> pkg to ri } }
            .filter { it.first != self }
            .distinctBy { it.first }
            .map { (pkg, ri) ->
                val label = runCatching { ri.loadLabel(pm).toString() }.getOrDefault(pkg)
                LaunchableApp(pkg, label.ifBlank { pkg }, YouTubeActions.isYouTubePackage(pkg))
            }
            .sortedBy { it.label.lowercase() }

        val holdsQueryAll = holdsPermission(context, "android.permission.QUERY_ALL_PACKAGES")

        SessionLogger.log(
            LabCategory.DEX, "app_launcher_visible_apps", LabStatus.OBSERVED,
            mapOf(
                "resolveInfos" to resolved.size.toString(),
                "distinctPackagesExcludingSelf" to apps.size.toString(),
                "holdsQueryAllPackages" to holdsQueryAll.toString(),
                "sdkInt" to Build.VERSION.SDK_INT.toString(),
                "youTubeVisible" to apps.any { it.isYouTube }.toString(),
                // Capped: the count above is the measurement, the sample is only context.
                "sample" to apps.take(25).joinToString(",") { it.packageName },
                "meaning" to visibilityNote(apps.size, holdsQueryAll),
            ),
        )

        // Graded separately and deliberately: a filtered list cannot answer "what is installed",
        // and an unfiltered-LOOKING list cannot prove it is complete either.
        SessionLogger.log(
            Observation.notTested(
                key = "dex.launcher.installed_apps_enumerable",
                category = LabCategory.DEX,
                note = "An ordinary app without QUERY_ALL_PACKAGES cannot enumerate what is " +
                    "installed on API 30+. The " + apps.size + " entries listed describe OUR " +
                    "VISIBILITY, not the device. No package may be called absent on this evidence.",
            ),
        )
        return apps
    }

    /** Human-readable statement of what the list length does and does not mean. For the UI too. */
    fun visibilityNote(visibleCount: Int, holdsQueryAllPackages: Boolean): String = when {
        holdsQueryAllPackages ->
            "QUERY_ALL_PACKAGES is held, so this list is close to the installed set - unexpected " +
                "for this build, and worth checking, because this app does not declare it."
        visibleCount == 0 ->
            "No foreign launchable package is visible to this app. That is a statement about our " +
                "package visibility, not about an empty phone."
        visibleCount < 12 ->
            "Only " + visibleCount + " foreign packages are visible: consistent with API 30+ " +
                "filtering limiting us to what our own <queries> element names. Apps missing from " +
                "this list are invisible to us, which is not the same as absent."
        else ->
            visibleCount.toString() + " foreign packages are visible: consistent with the declared " +
                "MAIN/LAUNCHER <queries> intent granting broad launcher visibility on this build. " +
                "It still proves nothing about apps without a launcher activity."
    }

    // ------------------------------------------------------------------ tester channel

    /** What a human saw after a launch. The only instrument that can see the head unit. */
    enum class Sighting(val label: String) {
        EXTERNAL_DISPLAY("appeared on the external / DeX screen"),
        PHONE_SCREEN("appeared on the phone screen"),
        BOTH_SCREENS("appeared on both screens"),
        NOTHING_VISIBLE("nothing appeared anywhere"),
        UNSURE("could not tell"),
    }

    /** One tester report, timestamped and attributed. Never merged into an API observation. */
    data class TesterVerdict(
        val attemptId: String,
        val sighting: Sighting,
        val note: String?,
        val atElapsedRealtimeMs: Long,
    )

    // Written by the UI thread, read by launch coroutines: concurrent by construction.
    private val verdicts = ConcurrentHashMap<String, TesterVerdict>()

    /**
     * Records what the tester saw after the attempt with this id.
     *
     * Logged immediately as its own OBSERVED record, attributed to a human, so it survives in the
     * evidence file even if the run is killed before anything else is written.
     */
    fun recordTesterVerdict(attemptId: String, sighting: Sighting, note: String? = null): TesterVerdict {
        val v = TesterVerdict(attemptId, sighting, note, SystemClock.elapsedRealtime())
        verdicts[attemptId] = v
        SessionLogger.log(
            LabCategory.USER, "app_launcher_tester_verdict", LabStatus.OBSERVED,
            buildMap<String, String> {
                put("attemptId", attemptId)
                put("sighting", sighting.name)
                put("sightingLabel", sighting.label)
                put("source", "human tester, entered by hand")
                note?.let { put("note", it) }
            },
        )
        return v
    }

    fun testerVerdict(attemptId: String): TesterVerdict? = verdicts[attemptId]

    /** Drops stale verdicts. A verdict from an earlier attempt must never grade a later one. */
    fun clearTesterVerdicts() = verdicts.clear()

    /**
     * Folds a human sighting into an API-side [Outcome] **without resolving a disagreement**.
     *
     * Rules, in order of how easily they are got wrong:
     *  - a sighting can never raise the status to CONFIRMED. No amount of looking at a screen turns
     *    "the platform accepted a start" into a platform contract.
     *  - when the API refused (UNSUPPORTED/ERROR) but the tester saw the app appear, or the API
     *    accepted while the tester is certain nothing was requested of that screen, the two readings
     *    contradict and the combined status becomes NOT_TESTED: two contradictory readings are not
     *    an answer, and grading them OBSERVED would make a positive claim out of a contradiction.
     *  - "nothing appeared" plus "no exception" stays OBSERVED - of the acceptance, which is all it
     *    ever was. That combination is the silent-drop case and it is the reason this project never
     *    treats a missing exception as success.
     */
    fun reconcile(outcome: Outcome, verdict: TesterVerdict): Outcome {
        val sawSomething = verdict.sighting == Sighting.EXTERNAL_DISPLAY ||
            verdict.sighting == Sighting.PHONE_SCREEN ||
            verdict.sighting == Sighting.BOTH_SCREENS
        val apiRefused = outcome.status == LabStatus.UNSUPPORTED || outcome.status == LabStatus.ERROR
        val contradiction = apiRefused && sawSomething

        val status = when {
            contradiction -> LabStatus.NOT_TESTED
            outcome.status == LabStatus.CONFIRMED -> LabStatus.OBSERVED // never let a sighting confirm
            else -> outcome.status
        }

        val addition = when {
            contradiction ->
                " CONTRADICTION: the API reported a refusal (" + outcome.status.name + ") while the " +
                    "tester reports the app " + verdict.sighting.label + ". Neither reading is " +
                    "discarded and neither is promoted - the refusal may belong to one of several " +
                    "attempted strategies while another succeeded, or the tester may be looking at " +
                    "something an earlier attempt left on screen. No usable answer was produced."
            verdict.sighting == Sighting.NOTHING_VISIBLE ->
                " The tester saw nothing appear on either screen. The start was accepted and had no " +
                    "visible effect - the silent-drop case, which is exactly why 'no exception' is " +
                    "never read as success here. Cause not established; candidates to separate " +
                    "next, not to assume: a background-start drop, the target declining a secondary " +
                    "display, or the activity starting behind what is already shown."
            verdict.sighting == Sighting.EXTERNAL_DISPLAY || verdict.sighting == Sighting.BOTH_SCREENS ->
                " A human reports the app " + verdict.sighting.label + ". That is a human reading, " +
                    "not a measurement: no API available to an ordinary app can see where an " +
                    "activity landed. It does not establish that the screen is a Miracast sink " +
                    "(DisplayRole never answers that above INFERRED) nor what the head unit did " +
                    "with the pixels."
            verdict.sighting == Sighting.PHONE_SCREEN ->
                " A human reports the app appeared on the PHONE screen" +
                    (
                        if ((outcome.evidence["requestedDisplayId"] ?: "none") != "none") {
                            ", although a launch display id was requested. If that holds up on a " +
                                "repeat, this is the silent-override case: the API took the display " +
                                "id and the platform used a different display without raising " +
                                "anything. Which rule caused it is not established here."
                        } else {
                            ". No display id was requested, so this says nothing about " +
                                "setLaunchDisplayId; it is the control reading."
                        }
                        )
            else ->
                " The tester could not tell which screen the app appeared on, so placement remains " +
                    "unestablished. The API-side reading is unchanged."
        }

        val merged = outcome.copy(
            status = status,
            conclusion = outcome.conclusion + addition,
            evidence = outcome.evidence + mapOf(
                "testerSighting" to verdict.sighting.name,
                "testerNote" to (verdict.note ?: "-"),
                "testerAtElapsedMs" to verdict.atElapsedRealtimeMs.toString(),
                "readingsAgree" to (!contradiction).toString(),
            ),
        )
        SessionLogger.log(
            LabCategory.DEX, "app_launcher_reconciled", status,
            mapOf(
                "attemptId" to outcome.experimentId,
                "apiStatus" to outcome.status.name,
                "testerSighting" to verdict.sighting.name,
                "combinedStatus" to status.name,
            ),
        )
        return merged
    }

    // ------------------------------------------------------------------ launching

    /** A distinguishable id per attempt, e.g. "launch-3". Also the [Outcome.experimentId]. */
    fun nextAttemptId(prefix: String): String = prefix + "-" + sequence.incrementAndGet()

    /**
     * Launches a package's own launcher activity, optionally on [displayId].
     *
     * "No exception" is not success, and this function is built around that: it records our
     * foreground state *before* the start (a background start is dropped silently on API 29+ and
     * that confound must be visible rather than mistaken for a platform refusal), it re-reads
     * DisplayManager afterwards, and it keeps the exact `SecurityException` message when the
     * platform refuses - that message names the calling uid and the display and is the single most
     * useful artefact this route can produce.
     */
    suspend fun launch(context: Context, packageName: String, displayId: Int?): Outcome {
        val id = nextAttemptId("launch")
        val pm = context.packageManager
        val intent = runCatching { pm.getLaunchIntentForPackage(packageName) }.getOrNull()
        if (intent == null) {
            val ev = mapOf(
                "targetPackage" to packageName,
                "requestedDisplayId" to (displayId?.toString() ?: "none"),
                "holdsQueryAllPackages" to
                    holdsPermission(context, "android.permission.QUERY_ALL_PACKAGES").toString(),
            )
            SessionLogger.log(LabCategory.DEX, "app_launcher_no_launch_intent", LabStatus.NOT_TESTED, ev)
            return Outcome(
                id, LabStatus.NOT_TESTED,
                "getLaunchIntentForPackage(\"" + packageName + "\") returned null: this process has " +
                    "no launcher intent for that package.",
                "Nothing was attempted, so nothing is established. A null here means either the " +
                    "package is not installed or it is not visible to this app under API 30+ " +
                    "package-visibility filtering, and an ordinary app cannot tell those apart. It " +
                    "must not be recorded as 'not installed' and must not be recorded as a platform " +
                    "refusal.",
                ev,
                "Name the package in the manifest <queries> element and rebuild, or pick a package " +
                    "that appears in installedApps().",
            )
        }
        val label = runCatching {
            @Suppress("DEPRECATION") // getApplicationInfo gained a Flags overload only at API 33.
            pm.getApplicationInfo(packageName, 0).loadLabel(pm).toString()
        }.getOrDefault(packageName)

        return launchIntent(
            context = context,
            attemptId = id,
            intent = intent,
            description = "launcher activity of " + packageName + " (" + label + ")",
            displayId = displayId,
            extraEvidence = mapOf("targetPackage" to packageName, "targetLabel" to label),
        )
    }

    /**
     * Hands a **web URL** to whatever app will take it, optionally targeted at [packageHint] and
     * optionally on [displayId].
     *
     * Only `http` and `https` are accepted. That is a deliberate restriction of this helper, not a
     * platform limit: passing an arbitrary scheme through here would turn a "open a page" button
     * into a general intent launcher, which is a different and much larger capability than anything
     * this module is measuring.
     *
     * This app holds no INTERNET permission and fetches nothing. It hands a URL to another app,
     * which does its own networking under its own permissions.
     */
    suspend fun launchUrl(
        context: Context,
        url: String,
        displayId: Int?,
        packageHint: String? = null,
    ): Outcome {
        val id = nextAttemptId("url")
        val uri = runCatching { Uri.parse(url) }.getOrNull()
        val scheme = uri?.scheme?.lowercase()
        if (uri == null || (scheme != "http" && scheme != "https")) {
            val ev = mapOf("url" to url, "scheme" to (scheme ?: "unparseable"))
            SessionLogger.log(LabCategory.DEX, "app_launcher_url_rejected", LabStatus.NOT_TESTED, ev)
            return Outcome(
                id, LabStatus.NOT_TESTED,
                "The URL \"" + url + "\" was not handed to any app: scheme is " +
                    (scheme ?: "unparseable") + ", and this helper accepts only http and https.",
                "Refused by THIS APP, not by the platform. Nothing is established about what the " +
                    "platform would have done with that intent.",
                ev,
                "Pass an http(s) URL. Non-web schemes are out of scope for this helper by design.",
            )
        }

        val intent = Intent(Intent.ACTION_VIEW, uri)
        if (packageHint != null) intent.setPackage(packageHint)

        // Recorded, never gating: on API 30+ a null resolution can equally mean "no handler" and
        // "no handler we are allowed to see", so we attempt anyway and let the platform answer with
        // an ActivityNotFoundException if there really is none.
        val resolvable = resolves(context, intent)

        return launchIntent(
            context = context,
            attemptId = id,
            intent = intent,
            description = "ACTION_VIEW " + url +
                (if (packageHint != null) " targeted at " + packageHint else " with no package"),
            displayId = displayId,
            extraEvidence = mapOf(
                "url" to url,
                "packageHint" to (packageHint ?: "none"),
                "resolvesForUs" to resolvable,
            ),
        )
    }

    /**
     * The shared body of every launch in this module, so that YouTube's buttons and a plain package
     * launch are measured identically and can be compared.
     *
     * It never claims success. The best status it can produce is OBSERVED, and the conclusion that
     * goes with it says only that the platform accepted the start.
     */
    suspend fun launchIntent(
        context: Context,
        attemptId: String,
        intent: Intent,
        description: String,
        displayId: Int?,
        extraEvidence: Map<String, String> = emptyMap(),
    ): Outcome {
        val dm = context.getSystemService(DisplayManager::class.java)
        val display = displayId?.let { id -> runCatching { dm?.getDisplay(id) }.getOrNull() }
        val flags = display?.let { runCatching { DisplayRole.decodeFlags(it) }.getOrDefault(emptyList()) }
            ?: emptyList()
        val inPresentationCategory = displayId != null && runCatching {
            dm?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
                ?.any { it.displayId == displayId } == true
        }.getOrDefault(false)

        val wasForeground = isForeground(context)
        val displaysBefore = describeDisplays(context)

        val evidence = LinkedHashMap<String, String>()
        evidence.putAll(extraEvidence)
        evidence["attemptId"] = attemptId
        evidence["intent"] = describeIntent(intent)
        evidence["requestedDisplayId"] = displayId?.toString() ?: "none"
        evidence["usesLaunchDisplayId"] = (displayId != null).toString()
        evidence["displayListed"] = (display != null).toString()
        evidence["displayName"] = display?.let { runCatching { it.name }.getOrDefault("?") } ?: "-"
        evidence["displayFlags"] = flags.joinToString("|").ifEmpty { "none" }
        evidence["inPresentationCategory"] = inPresentationCategory.toString()
        evidence["displayRole"] = displayId?.let { describeTargetRole(context, it) } ?: "-"
        evidence["callerForegroundAtLaunch"] = wasForeground.toString()
        evidence["displaysBefore"] = displaysBefore
        evidence["sdkInt"] = Build.VERSION.SDK_INT.toString()

        // FLAG_ACTIVITY_NEW_TASK: required whenever the start does not come from an Activity
        // context, and harmless when it does. Without it a non-Activity context throws.
        val toStart = Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val startedAt = SystemClock.elapsedRealtime()

        try {
            withContext(Dispatchers.Main) {
                if (displayId != null) {
                    val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
                    context.startActivity(toStart, options.toBundle())
                } else {
                    // No options bundle at all: this is the control, and it asks what the system
                    // does on its own while a DeX session is running.
                    context.startActivity(toStart)
                }
            }
            SessionLogger.log(LabCategory.DEX, "app_launcher_start_accepted", LabStatus.OBSERVED, evidence)
        } catch (e: SecurityException) {
            // The documented way the activity manager refuses a launch onto a display the caller may
            // not use. The message names the uid and the display; it is captured verbatim.
            val message = e.message ?: "no message"
            evidence["exception"] = describe(e)
            evidence["securityMessage"] = message
            SessionLogger.log(LabCategory.DEX, "app_launcher_start_refused", LabStatus.UNSUPPORTED, evidence)
            return Outcome(
                attemptId, LabStatus.UNSUPPORTED,
                "startActivity(" + description +
                    (if (displayId != null) ", setLaunchDisplayId(" + displayId + ")" else ", no options") +
                    ") threw SecurityException: " + message,
                "The platform refuses this start to an app with our privileges and says so " +
                    "explicitly - a real negative at the ORDINARY_APP tier, for this target, this " +
                    "display and this build. It says nothing about other targets, other displays, " +
                    "or the adb/Shizuku/system-app tiers, which are analysis only in this build.",
                evidence,
                "Copy the exception message into the privilege-tier matrix, then repeat with no " +
                    "display id to see whether the session routes the app there without us asking.",
            )
        } catch (e: ActivityNotFoundException) {
            evidence["exception"] = describe(e)
            SessionLogger.log(LabCategory.DEX, "app_launcher_start_not_found", LabStatus.NOT_TESTED, evidence)
            return Outcome(
                attemptId, LabStatus.NOT_TESTED,
                "startActivity(" + description + ") threw ActivityNotFoundException: " +
                    (e.message ?: "no message"),
                "No activity that this app is allowed to see would take the intent, so the display " +
                    "question was never reached. On API 30+ that is a statement about visibility and " +
                    "about this device's handlers together, and an ordinary app cannot separate " +
                    "them. Not a display-routing result, and not proof that no handler exists.",
                evidence,
                "Try the fallback strategy (a different package, or no package at all) and compare.",
            )
        } catch (t: Throwable) {
            evidence["exception"] = describe(t)
            SessionLogger.log(LabCategory.DEX, "app_launcher_start_error", LabStatus.ERROR, evidence)
            return Outcome(
                attemptId, LabStatus.ERROR,
                "startActivity(" + description + ") threw " + describe(t) + ".",
                "The mechanism failed to execute. Nothing may be concluded about whether an app can " +
                    "be placed on a chosen display this way.",
                evidence,
                "Re-run. If the same type is thrown again, that type is itself the finding.",
            )
        }

        // What the API can still tell us afterwards. Which is very little, and that is the point.
        delay(SETTLE_MS)
        val settleMs = SystemClock.elapsedRealtime() - startedAt
        val displaysAfter = describeDisplays(context)
        val stillForeground = isForeground(context)
        evidence["displaysAfter"] = displaysAfter
        evidence["displaySetChanged"] = (displaysAfter != displaysBefore).toString()
        evidence["settleMs"] = settleMs.toString()
        evidence["callerForegroundAfter"] = stillForeground.toString()
        evidence["landingObservable"] = "no: getRunningTasks is restricted to our own tasks since " +
            "API 21 and getRunningAppProcesses to our own process since API 22"

        val observation = buildString {
            append("startActivity(").append(description)
            append(if (displayId != null) ", setLaunchDisplayId(" + displayId + ")" else ", no options bundle")
            append(") returned without throwing. After ").append(settleMs).append(" ms: displays before [")
            append(displaysBefore).append("], after [").append(displaysAfter).append("]; ")
            append("requested display ").append(displayId?.toString() ?: "none")
            append(" was ").append(if (displayId == null) "not requested" else if (display != null) "listed" else "NOT listed")
            append("; caller foreground before=").append(wasForeground)
            append(", after=").append(stillForeground).append(".")
        }

        val conclusion = buildString {
            append(
                "The platform ACCEPTED the start. That is the whole of it: no exception is not " +
                    "success. Whether an activity was created, which display it went to, and " +
                    "whether anything appeared on the head unit are all unestablished here, and no " +
                    "API available to an ordinary app can answer them.",
            )
            if (displayId != null && display == null) {
                append(
                    " The requested display id " + displayId + " was not listed by DisplayManager at " +
                        "launch time, so this measured the API's behaviour for an ABSENT display - a " +
                        "different question from placing content on a live external screen.",
                )
            }
            if (!wasForeground) {
                append(
                    " CONFOUND: the start was made while this app was not in the foreground. On API " +
                        "29+ a background activity start can be dropped silently, so a null visible " +
                        "result would be unattributable - it may be the display rule, it may be the " +
                        "background rule.",
                )
            }
            append(
                " Only a human looking at both screens can say where it landed; record that with " +
                    "recordTesterVerdict(\"" + attemptId + "\", ...) and let reconcile() combine the " +
                    "two readings without resolving a disagreement.",
            )
        }

        SessionLogger.log(LabCategory.DEX, "app_launcher_start_settled", LabStatus.OBSERVED, evidence)
        SessionLogger.marker(
            attemptId + ": look at BOTH screens now. Which one is showing " + description + "?",
            LabCategory.USER,
        )

        return Outcome(
            attemptId, LabStatus.OBSERVED, observation, conclusion, evidence,
            "Record what you saw on the screen for attempt " + attemptId + ", then repeat the same " +
                "launch with no display id: if the app lands externally either way, the display id " +
                "explains nothing and the session's own routing does.",
        )
    }

    // ------------------------------------------------------------------ helpers

    /** Whether an intent resolves *for this process*, phrased so it cannot be read as existence. */
    @Suppress("DEPRECATION") // resolveActivity gained a ResolveInfoFlags overload only at API 33.
    fun resolves(context: Context, intent: Intent): String = runCatching {
        val ri = context.packageManager.resolveActivity(intent, 0)
        if (ri == null) {
            "no handler visible to this app (on API 30+ this cannot be told apart from 'no handler exists')"
        } else {
            "resolves to " + (ri.activityInfo?.packageName ?: "?")
        }
    }.getOrDefault("unreadable")

    private fun holdsPermission(context: Context, permission: String): Boolean = runCatching {
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    private fun describeIntent(intent: Intent): String = buildString {
        append(intent.action ?: "no action")
        intent.data?.let { append(" data=").append(it.toString()) }
        intent.`package`?.let { append(" package=").append(it) }
        intent.component?.let { append(" component=").append(it.flattenToShortString()) }
        val extras = runCatching { intent.extras?.keySet()?.joinToString(",") }.getOrNull()
        if (!extras.isNullOrEmpty()) append(" extras=[").append(extras).append("]")
    }

    private fun describeDisplays(context: Context): String = runCatching {
        val dm = context.getSystemService(DisplayManager::class.java)
            ?: return@runCatching "no DisplayManager"
        dm.displays.joinToString("|") { it.displayId.toString() + ":" + it.name }
    }.getOrDefault("unreadable")

    /**
     * What DisplayRole thinks the target is. Context only: no outcome here depends on it, because
     * "is this the Miracast surface?" is a separate and weaker question that DisplayRole answers at
     * INFERRED at best.
     */
    private fun describeTargetRole(context: Context, displayId: Int): String = runCatching {
        DisplayRole.verdicts(context).firstOrNull { it.displayId == displayId }
            ?.let { it.role.name + " (" + it.status.name + ")" } ?: "not listed"
    }.getOrDefault("unreadable")

    /**
     * Are we in the foreground?
     *
     * Read from our own Activity's lifecycle, the only thing an ordinary app can trust:
     * getRunningAppProcesses() returns only our own process since API 22 and its importance field
     * has been unreliable across OEM builds. With no Activity context this returns false, which is
     * the conservative direction - it turns results into caveats rather than into claims.
     */
    private suspend fun isForeground(context: Context): Boolean = withContext(Dispatchers.Main) {
        val activity = context.findActivity() ?: return@withContext false
        val owner = activity as? LifecycleOwner
            ?: return@withContext runCatching { activity.hasWindowFocus() }.getOrDefault(false)
        runCatching { owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
            .getOrDefault(false)
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

    private fun describe(t: Throwable): String =
        t::class.java.simpleName + ": " + (t.message ?: "no message")
}
