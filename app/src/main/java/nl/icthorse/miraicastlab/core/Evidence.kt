package nl.icthorse.miraicastlab.core

/**
 * The evidence grading used across the whole application (spec section 20, "NO-HALLUCINATION RULE").
 *
 * The single most important invariant in this codebase:
 * **[NOT_TESTED] must never be silently promoted to [UNSUPPORTED].**
 * "We could not run the test" and "the test proved the capability is absent" are different claims.
 */
enum class LabStatus(val label: String, val explanation: String) {
    /** Direct evidence, or authoritative API/spec documentation *plus* a matching implementation. */
    CONFIRMED("CONFIRMED", "Directly evidenced on this device, or guaranteed by the platform contract."),

    /** The behaviour was actually seen during a test run. */
    OBSERVED("OBSERVED", "Seen during this test run."),

    /** Evidence points this way but it was not directly confirmed. */
    INFERRED("INFERRED", "Suggested by evidence, not directly confirmed."),

    /** A test or API produced evidence that the capability is absent here. */
    UNSUPPORTED("UNSUPPORTED", "Evidence shows it is not available in this environment."),

    /** Hardware or circumstances were unavailable. Never downgrade this to UNSUPPORTED. */
    NOT_TESTED("NOT_TESTED", "Hardware or circumstances were unavailable. No claim is made."),

    /** The probe itself failed (exception, permission denial, timeout). */
    ERROR("ERROR", "The probe failed to run; this says nothing about the capability.");

    /** True when this status makes a positive claim about a capability existing. */
    val isPositiveClaim: Boolean get() = this == CONFIRMED || this == OBSERVED

    /** True when nothing at all may be concluded from this status. */
    val isSilent: Boolean get() = this == NOT_TESTED || this == ERROR
}

/** Log/observation categories, matching the `category` field of the structured log (spec section 13). */
enum class LabCategory {
    DEVICE,
    DISPLAY,
    NETWORK,
    AUDIO,
    INPUT,
    MIRACAST,
    ANDROID_AUTO,
    MEDIAPROJECTION,
    CODEC,
    DEX,
    SMART_VIEW,
    MOTION_STATE,
    REPORT,
    USER,
}

/**
 * One capability finding: a key/value pair with an explicit evidence grade.
 *
 * @param key      short stable identifier, e.g. "display.external.count"
 * @param value    the observed value rendered as text
 * @param status   how strongly this is claimed - see [LabStatus]
 * @param note     optional human-readable qualification ("why is this only INFERRED?")
 * @param category which subsystem this belongs to
 */
data class Observation(
    val key: String,
    val value: String,
    val status: LabStatus,
    val note: String? = null,
    val category: LabCategory = LabCategory.DEVICE,
) {
    companion object {
        /** Convenience for a value that was read straight off a platform API. */
        fun confirmed(key: String, value: Any?, category: LabCategory, note: String? = null) =
            Observation(key, value?.toString() ?: "null", LabStatus.CONFIRMED, note, category)

        /** Convenience for something seen happening during a run. */
        fun observed(key: String, value: Any?, category: LabCategory, note: String? = null) =
            Observation(key, value?.toString() ?: "null", LabStatus.OBSERVED, note, category)

        /** Convenience for a capability the platform reports as absent. */
        fun unsupported(key: String, category: LabCategory, note: String? = null) =
            Observation(key, "absent", LabStatus.UNSUPPORTED, note, category)

        /** Convenience for something that requires hardware we do not have. */
        fun notTested(key: String, category: LabCategory, note: String? = null) =
            Observation(key, "-", LabStatus.NOT_TESTED, note, category)

        /** Convenience for a probe failure. Records the throwable message, never swallows it. */
        fun error(key: String, t: Throwable, category: LabCategory) =
            Observation(
                key,
                t::class.java.simpleName + ": " + (t.message ?: "no message"),
                LabStatus.ERROR,
                "The probe threw; the capability itself is untested.",
                category,
            )
    }
}

/**
 * One structured log record. Serialised shape is exactly the JSON in spec section 13.
 */
data class LogRecord(
    val timestamp: String,
    val elapsedRealtimeMs: Long,
    val testRunId: String,
    val category: LabCategory,
    val event: String,
    val status: LabStatus,
    val details: Map<String, String> = emptyMap(),
)
