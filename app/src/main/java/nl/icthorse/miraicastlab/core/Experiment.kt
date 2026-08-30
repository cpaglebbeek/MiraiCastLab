package nl.icthorse.miraicastlab.core

/**
 * A single named experiment, recorded in the shape the research question asks for:
 *
 *     hypothesis -> API/method -> test -> observation -> log -> conclusion -> next test
 *
 * The point of the structure is that the *hypothesis is written down before the test runs*. An
 * experiment whose conclusion was decided first is not an experiment, and this project has exactly
 * one job: not doing that.
 *
 * [conclusion] is deliberately absent until [Outcome] exists. There is no "expected result" field.
 */
data class Experiment(
    /** Stable id, e.g. "C1" for route C mechanism 1. Used as the log key prefix. */
    val id: String,
    /** Which route this belongs to. */
    val route: Route,
    /** One line: what is being asked. */
    val title: String,
    /** What we expect and, more importantly, what would falsify it. Written before running. */
    val hypothesis: String,
    /** The exact API or method under test, named so a reader can look it up. */
    val method: String,
    /** What the test does, concretely. */
    val procedure: String,
    /** What has to be true before this can run at all. */
    val preconditions: List<String> = emptyList(),
    /** Which privilege tier this needs. */
    val tier: PrivilegeTier = PrivilegeTier.ORDINARY_APP,
    /** Ids of the experiments this one should be followed by, whichever way it goes. */
    val nextOnPass: List<String> = emptyList(),
    val nextOnFail: List<String> = emptyList(),
)

/**
 * The four candidate chains from phone to head unit. Keeping them apart matters: they can succeed
 * and fail independently, and collapsing them is how you end up believing Miracast "works" because
 * something else did.
 */
enum class Route(val code: String, val label: String, val chain: String) {
    A(
        "A",
        "App is itself the Miracast sender",
        "App -> Wi-Fi Display protocol -> Mirai",
    ),
    B(
        "B",
        "Samsung manages the session",
        "Android -> Samsung DeX / Smart View -> Miracast -> Mirai",
    ),
    C(
        "C",
        "App puts its OWN content on the display Samsung already established",
        "App -> Android secondary-display API -> DeX wireless display -> Mirai",
    ),
    D(
        "D",
        "App puts ANOTHER app's content on that display",
        "App -> launch other app on chosen display -> DeX wireless display -> Mirai",
    ),
}

/**
 * How much privilege a capability needs.
 *
 * The ordering is meaningful: each tier strictly contains the one before it. The project's primary
 * build stays at [ORDINARY_APP]; everything above it is analysis plus an experimental path that is
 * disabled by default and degrades to NOT_TESTED when the tier is unavailable.
 */
enum class PrivilegeTier(val label: String, val note: String) {
    ORDINARY_APP(
        "ordinary app",
        "Public Android APIs, no special grant. This is what the primary build uses.",
    ),
    SAMSUNG_API(
        "Samsung API / reflection",
        "Proprietary surface reached reflectively. Guarded, isolated, degrades to UNSUPPORTED.",
    ),
    ADB_SHELL(
        "adb shell (uid 2000)",
        "Documented shell-level commands from a workstation. No root. Analysis only in this build.",
    ),
    SHIZUKU(
        "Shizuku",
        "The adb-shell tier without a cable, via a user-started service. Not integrated in this build.",
    ),
    ROOT(
        "root",
        "Out of scope for the primary build. Analysed, never required.",
    ),
    CUSTOM_ROM(
        "custom ROM / system app",
        "Signature or privileged permissions. Analysed only.",
    ),
}

/**
 * The result of running one [Experiment].
 *
 * [observation] is what was literally seen. [conclusion] is what may be concluded from it, and the
 * two are separate fields on purpose: the gap between them is where every wrong finding in this
 * problem domain lives.
 */
data class Outcome(
    val experimentId: String,
    val status: LabStatus,
    /** Literally what happened: return values, exceptions, which display id was reported. */
    val observation: String,
    /** What may be concluded. May be "nothing" - that is a valid conclusion. */
    val conclusion: String,
    /** Raw evidence: stack trace, dumpsys excerpt, display id, timings. */
    val evidence: Map<String, String> = emptyMap(),
    /** Which experiment to run next, given this outcome. */
    val nextStep: String? = null,
) {
    fun toObservation(category: LabCategory) = Observation(
        key = "route." + experimentId.lowercase() + ".outcome",
        value = observation,
        status = status,
        note = conclusion,
        category = category,
    )
}
