package nl.icthorse.miraicastlab.dex

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import kotlinx.coroutines.delay
import nl.icthorse.miraicastlab.core.LabCategory
import nl.icthorse.miraicastlab.core.LabStatus
import nl.icthorse.miraicastlab.core.Observation
import nl.icthorse.miraicastlab.core.Outcome
import nl.icthorse.miraicastlab.core.SessionLogger

/**
 * The two standard buttons the research question actually asks for: **open YouTube on the DeX /
 * Mirai screen, and search on it.**
 *
 * This is the short way round. The rest of this module asks whether the app can emulate a keyboard
 * and mouse so that a human's "press Meta, type youtube, press Enter" can be replayed; that
 * question is real and privilege-bound. But for this particular goal none of it is needed: an
 * `Intent` carrying the query, plus `ActivityOptions.setLaunchDisplayId(id)` for the target screen,
 * is an ordinary-app API and needs no accessibility service, no HID, no shell. Both paths are kept
 * because they answer different questions - this one delivers the feature, the input routes measure
 * the capability.
 *
 * ### No network permission, and that is not an oversight
 * This app declares **no `INTERNET` permission** and fetches nothing. `searchYouTube` does not query
 * YouTube; it builds a URL or an intent extra and hands it to another app, which does its own
 * networking under its own permissions. Adding `INTERNET` here would buy nothing and would silently
 * invalidate every privacy statement in SAFETY.md and README.md. Do not "fix" this file by adding
 * it.
 *
 * ### What an accepted intent proves
 * Nothing about pixels. Every outcome below tops out at OBSERVED, meaning the platform accepted the
 * start. Whether YouTube appeared, on which screen, and whether the head unit displayed it, are
 * claims only the tester can make - see [AppLauncher.recordTesterVerdict] and
 * [AppLauncher.reconcile].
 */
object YouTubeActions {

    /** The YouTube app on a phone. */
    const val PKG_YOUTUBE = "com.google.android.youtube"

    /** The Android TV / large-screen build. Recognised so a DeX device that carries it is not missed. */
    const val PKG_YOUTUBE_TV = "com.google.android.youtube.tv"

    /** Home page, used by the web fallbacks. */
    const val URL_HOME = "https://www.youtube.com/"

    /** Results page. `search_query` is YouTube's own parameter name for the web search. */
    const val URL_RESULTS = "https://www.youtube.com/results"

    /**
     * One-tap queries, so the tester is not typing in a car.
     *
     * They are chosen to be quick to judge on a head unit: each one obviously either played or did
     * not, and none of them depends on an account, a subscription or a region.
     */
    val suggestedQueries: List<String> = listOf(
        "4k test pattern",
        "colour bars 1080p",
        "audio sync test",
        "60fps motion test",
        "lofi radio",
        "toyota mirai",
    )

    fun isYouTubePackage(packageName: String): Boolean =
        packageName == PKG_YOUTUBE || packageName == PKG_YOUTUBE_TV

    // ------------------------------------------------------------------ visibility

    /**
     * Which YouTube package, if any, this process can see - and the honest caveat that goes with it.
     *
     * Returns the package name and a description. A null package does **not** mean YouTube is
     * absent: on API 30+ an app without `QUERY_ALL_PACKAGES` sees only what its `<queries>` element
     * names, and this manifest names a MAIN/LAUNCHER intent rather than YouTube itself. "Is YouTube
     * installed" is therefore graded NOT_TESTED, every time, and never UNSUPPORTED.
     */
    fun visibleYouTubePackage(context: Context): Pair<String?, String> {
        val pm = context.packageManager
        for (pkg in listOf(PKG_YOUTUBE, PKG_YOUTUBE_TV)) {
            val hasLaunchIntent = runCatching { pm.getLaunchIntentForPackage(pkg) }.getOrNull() != null
            if (hasLaunchIntent) {
                return pkg to ("getLaunchIntentForPackage(\"" + pkg + "\") returned an intent, so " +
                    "this package is installed AND visible to us")
            }
        }
        val holdsQueryAll = runCatching {
            context.checkSelfPermission("android.permission.QUERY_ALL_PACKAGES") ==
                PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        return null to ("no YouTube package produced a launch intent for this process " +
            "(QUERY_ALL_PACKAGES held: " + holdsQueryAll + "). Under API 30+ package-visibility " +
            "filtering this cannot be told apart from YouTube not being installed.")
    }

    /** Logs the visibility reading, including the NOT_TESTED grade for "is YouTube installed". */
    private fun logVisibility(context: Context, flow: String): String? {
        val (pkg, why) = visibleYouTubePackage(context)
        SessionLogger.log(
            LabCategory.DEX, "youtube_visibility", LabStatus.OBSERVED,
            mapOf("flow" to flow, "visiblePackage" to (pkg ?: "none"), "reading" to why),
        )
        if (pkg == null) {
            SessionLogger.log(
                Observation.notTested(
                    key = "dex.youtube.installed",
                    category = LabCategory.DEX,
                    note = why + " The web fallback is still attempted, and its result is about " +
                        "browsers, not about YouTube.",
                ),
            )
        } else {
            SessionLogger.log(
                Observation.confirmed(
                    key = "dex.youtube.installed",
                    value = pkg,
                    category = LabCategory.DEX,
                    note = "A launch intent was returned for this package, which an app only gets " +
                        "for a package that is installed and visible to it.",
                ),
            )
        }
        return pkg
    }

    // ------------------------------------------------------------------ open

    /**
     * Opens YouTube, preferring the app and falling back to the web, on [displayId] when one is given.
     *
     * Three strategies, tried in order, with the one that was used reported explicitly:
     *  1. the YouTube app's own launcher intent - the cleanest, and the only one that cannot end up
     *     in a browser;
     *  2. `ACTION_VIEW` on the YouTube home URL **targeted at the YouTube package** - reaches the app
     *     even when its launcher intent is not visible to us;
     *  3. `ACTION_VIEW` on the same URL with **no package** - the browser fallback. This one is
     *     attempted for real even when YouTube is invisible to us, because a browser on the DeX
     *     screen still answers the owner's question.
     *
     * A strategy "succeeds" here only in the sense that the platform accepted the start.
     */
    suspend fun openYouTube(context: Context, displayId: Int?): Outcome {
        val pkg = logVisibility(context, "open")
        SessionLogger.log(
            LabCategory.DEX, "youtube_open_started", LabStatus.OBSERVED,
            mapOf("requestedDisplayId" to (displayId?.toString() ?: "none"), "visiblePackage" to (pkg ?: "none")),
        )

        val target = pkg ?: PKG_YOUTUBE
        return firstAccepted(
            flow = "open",
            what = "open YouTube",
            strategies = listOf<Pair<String, suspend () -> Outcome>>(
                "app-launch-intent" to suspend { AppLauncher.launch(context, target, displayId) },
                "view-url-targeted" to suspend { AppLauncher.launchUrl(context, URL_HOME, displayId, target) },
                "view-url-browser" to suspend { AppLauncher.launchUrl(context, URL_HOME, displayId, null) },
            ),
        )
    }

    // ------------------------------------------------------------------ search

    /**
     * Searches on YouTube, preferring the in-app search, on [displayId] when one is given.
     *
     * Strategy 1 is `Intent(ACTION_SEARCH)` with `setPackage(PKG_YOUTUBE)` and the query in the
     * `"query"` extra. That is the intent the YouTube app itself declares a filter for; it is a
     * long-standing convention of that app rather than a platform contract, which is why a refusal
     * or a missing handler here is a fact about the installed YouTube build and not about Android.
     *
     * Strategies 2 and 3 hand `https://www.youtube.com/results?search_query=...` to YouTube and then
     * to any browser. The app fetches nothing: it passes a URL to another app (see the class note on
     * the absent INTERNET permission).
     */
    suspend fun searchYouTube(context: Context, query: String, displayId: Int?): Outcome {
        val q = query.trim()
        if (q.isEmpty()) {
            val id = AppLauncher.nextAttemptId("yt-search")
            val ev = mapOf("query" to "(empty)", "requestedDisplayId" to (displayId?.toString() ?: "none"))
            SessionLogger.log(LabCategory.DEX, "youtube_search_empty_query", LabStatus.NOT_TESTED, ev)
            return Outcome(
                id, LabStatus.NOT_TESTED,
                "No search was attempted: the query was empty after trimming.",
                "Refused by this app, not by the platform. Nothing is established.",
                ev,
                "Enter a query, or use one of YouTubeActions.suggestedQueries.",
            )
        }

        val pkg = logVisibility(context, "search")
        val target = pkg ?: PKG_YOUTUBE
        // URLEncoder is the form encoder: it renders a space as "+", which is exactly right inside a
        // query string and would be wrong in a path segment. The results URL only ever takes it as a
        // query parameter, so this is correct here and must not be "corrected" to %20 blindly.
        val encoded = runCatching { java.net.URLEncoder.encode(q, "UTF-8") }.getOrDefault(q)
        val resultsUrl = URL_RESULTS + "?search_query=" + encoded

        SessionLogger.log(
            LabCategory.DEX, "youtube_search_started", LabStatus.OBSERVED,
            mapOf(
                "query" to q,
                "encodedQuery" to encoded,
                "resultsUrl" to resultsUrl,
                "requestedDisplayId" to (displayId?.toString() ?: "none"),
                "visiblePackage" to (pkg ?: "none"),
            ),
        )

        val searchIntent = Intent(Intent.ACTION_SEARCH)
            .setPackage(target)
            .putExtra("query", q)

        return firstAccepted(
            flow = "search",
            what = "search YouTube for \"" + q + "\"",
            strategies = listOf<Pair<String, suspend () -> Outcome>>(
                "in-app-action-search" to suspend {
                    AppLauncher.launchIntent(
                        context = context,
                        attemptId = AppLauncher.nextAttemptId("yt-search"),
                        intent = searchIntent,
                        description = "ACTION_SEARCH query=\"" + q + "\" targeted at " + target,
                        displayId = displayId,
                        extraEvidence = mapOf(
                            "query" to q,
                            "targetPackage" to target,
                            "resolvesForUs" to AppLauncher.resolves(context, searchIntent),
                            "intentContract" to "ACTION_SEARCH with extra \"query\" is declared by " +
                                "the YouTube app itself; it is convention, not a platform guarantee",
                        ),
                    )
                },
                "results-url-targeted" to suspend {
                    AppLauncher.launchUrl(context, resultsUrl, displayId, target)
                },
                "results-url-browser" to suspend {
                    AppLauncher.launchUrl(context, resultsUrl, displayId, null)
                },
            ),
        )
    }

    // ------------------------------------------------------------------ the two-step flow

    /**
     * Open YouTube, wait, then search - the flow the owner described, kept as **two** outcomes.
     *
     * They are not merged, because a failure has to stay attributable to a step: "the app opened but
     * the search intent was refused" and "nothing opened at all" are different findings with
     * different next actions. The list is always of size two, whatever happened.
     *
     * [settleMs] exists because the second intent arrives while the first activity is still starting.
     * It is a delay, not a synchronisation: an ordinary app cannot observe that YouTube finished
     * launching, so a too-short wait shows up as a search that landed on a home screen, and the
     * tester's verdict is what catches it.
     */
    suspend fun openAndSearch(
        context: Context,
        query: String,
        displayId: Int?,
        settleMs: Long = 1_500L,
    ): List<Outcome> {
        SessionLogger.log(
            LabCategory.DEX, "youtube_open_and_search_started", LabStatus.OBSERVED,
            mapOf(
                "query" to query,
                "requestedDisplayId" to (displayId?.toString() ?: "none"),
                "settleMs" to settleMs.toString(),
                "note" to "two steps, reported separately; step 2 runs even if step 1 was not accepted",
            ),
        )

        val open = openYouTube(context, displayId)
        delay(settleMs)
        // Step 2 runs regardless: ACTION_SEARCH starts the app by itself, so a step-1 refusal does
        // not make step 2 meaningless - and skipping it would hide that.
        val search = searchYouTube(context, query, displayId)

        SessionLogger.log(
            LabCategory.DEX, "youtube_open_and_search_finished",
            worstOf(open.status, search.status),
            mapOf(
                "openStatus" to open.status.name,
                "openAttemptId" to open.experimentId,
                "searchStatus" to search.status.name,
                "searchAttemptId" to search.experimentId,
                "proves" to "at most that the platform accepted both starts; what appeared on which " +
                    "screen is a tester claim, recorded through AppLauncher.recordTesterVerdict",
            ),
        )
        return listOf(open, search)
    }

    // ------------------------------------------------------------------ internals

    /**
     * Runs strategies in order until the platform accepts one, and reports which one that was.
     *
     * Selection rules, in order:
     *  1. the first strategy the platform ACCEPTED (status OBSERVED) is the result;
     *  2. otherwise the first explicit platform REFUSAL (UNSUPPORTED) is the result, because a
     *     refusal is a real negative and losing it behind a later "nothing was visible to us" would
     *     be the more flattering and less true report;
     *  3. otherwise the last attempt, which is the browser fallback.
     *
     * Every strategy's status and observation is kept in the evidence either way, so a reader can
     * see the whole ladder rather than only the rung that was returned. The returned outcome keeps
     * the attempt id of the strategy it came from, so a tester verdict attaches to the right one.
     */
    private suspend fun firstAccepted(
        flow: String,
        what: String,
        strategies: List<Pair<String, suspend () -> Outcome>>,
    ): Outcome {
        val ladder = LinkedHashMap<String, String>()
        val attempts = mutableListOf<Pair<String, Outcome>>()
        var accepted: Pair<String, Outcome>? = null

        for ((name, block) in strategies) {
            val outcome = try {
                block()
            } catch (t: Throwable) {
                // A strategy body must not be able to abort the ladder. A throw here is about this
                // code, not about the platform, and is graded ERROR accordingly.
                Outcome(
                    AppLauncher.nextAttemptId("yt-" + flow), LabStatus.ERROR,
                    "Strategy \"" + name + "\" threw before or around the start: " +
                        t::class.java.simpleName + ": " + (t.message ?: "no message"),
                    "This is a fault in the lab code path, not a platform answer. Nothing is " +
                        "established about the capability.",
                    mapOf("strategy" to name),
                )
            }
            attempts += name to outcome
            ladder["strategy." + name] = outcome.status.name + " [" + outcome.experimentId + "] " +
                outcome.observation.take(220)
            if (outcome.status == LabStatus.OBSERVED) {
                accepted = name to outcome
                break
            }
        }

        val chosen = accepted
            ?: attempts.firstOrNull { it.second.status == LabStatus.UNSUPPORTED }
            ?: attempts.last()
        val (chosenName, chosenOutcome) = chosen

        val evidence = LinkedHashMap<String, String>()
        evidence.putAll(chosenOutcome.evidence)
        evidence.putAll(ladder)
        evidence["flow"] = flow
        evidence["strategyUsed"] = chosenName
        evidence["strategiesAttempted"] = attempts.joinToString(",") { it.first }
        evidence["strategiesAvailable"] = strategies.joinToString(",") { it.first }
        evidence["selectionRule"] = if (accepted != null) {
            "first strategy the platform accepted"
        } else if (chosenOutcome.status == LabStatus.UNSUPPORTED) {
            "no strategy was accepted; the explicit platform refusal is reported in preference to a later inconclusive attempt"
        } else {
            "no strategy was accepted and none was explicitly refused; the last attempt is reported"
        }

        val conclusion = buildString {
            append("Flow \"").append(what).append("\" used strategy \"").append(chosenName).append("\" (")
            append(if (accepted != null) "accepted by the platform" else "no strategy was accepted")
            append("). ")
            append(chosenOutcome.conclusion)
            if (accepted != null && attempts.size > 1) {
                append(" The earlier strategies were tried first and did not get that far; their " +
                    "results are in the evidence, and the fact that a LATER strategy was needed is " +
                    "itself a finding about this device.")
            }
            if (accepted == null) {
                append(" None of the ").append(attempts.size).append(" strategies was accepted, " +
                    "which is a stronger negative than any single one of them - but it is still " +
                    "about this device, this build and these targets, at the ORDINARY_APP tier only.")
            }
        }

        val result = chosenOutcome.copy(conclusion = conclusion, evidence = evidence)
        SessionLogger.log(
            LabCategory.DEX, "youtube_" + flow + "_finished", result.status,
            mapOf(
                "what" to what,
                "strategyUsed" to chosenName,
                "attemptId" to result.experimentId,
                "status" to result.status.name,
                "proves" to "at most that the platform accepted the start; visible effect is a " +
                    "tester claim and is never asserted here",
            ) + ladder,
        )
        SessionLogger.log(result.toObservation(LabCategory.DEX))
        return result
    }

    /** The least flattering of two statuses, for a flow-level summary that cannot over-claim. */
    private fun worstOf(a: LabStatus, b: LabStatus): LabStatus {
        val rank = { s: LabStatus ->
            when (s) {
                LabStatus.CONFIRMED -> 5
                LabStatus.OBSERVED -> 4
                LabStatus.INFERRED -> 3
                LabStatus.UNSUPPORTED -> 2
                LabStatus.NOT_TESTED -> 1
                LabStatus.ERROR -> 0
            }
        }
        return if (rank(a) <= rank(b)) a else b
    }
}
