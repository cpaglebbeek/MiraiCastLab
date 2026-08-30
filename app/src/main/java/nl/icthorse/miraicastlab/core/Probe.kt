package nl.icthorse.miraicastlab.core

import android.content.Context
import android.util.Log

/**
 * A read-only capability probe.
 *
 * A probe must be safe to run at any time, must never mutate device state, and must never throw:
 * a failure is data ([Observation.error]), not an exception. Use [safeObserve] to get that
 * guarantee for free.
 */
interface Probe {
    /** Stable identifier, e.g. "display". Used as a log key prefix. */
    val id: String

    /** Human-readable title shown in the UI. */
    val title: String

    /** Runs the probe and returns its findings. Implementations should not log; the caller does. */
    suspend fun observe(context: Context): List<Observation>
}

/**
 * Runs [Probe.observe] and converts any throwable into an ERROR observation.
 *
 * A probe that blows up must degrade the report, not the app: a Samsung-only API missing on an
 * emulator is an expected outcome of this project, not a crash.
 */
suspend fun Probe.safeObserve(context: Context): List<Observation> = try {
    observe(context)
} catch (t: Throwable) {
    Log.w("MiraiCastLab", "probe " + id + " failed", t)
    listOf(Observation.error(id + ".probe_failed", t, LabCategory.DEVICE))
}

/**
 * Guards a single reflective or optional platform call.
 *
 * Returns [Observation.error] when the call throws and [Observation.unsupported] when it returns
 * null, which is the honest distinction between "we could not ask" and "the platform said no".
 */
inline fun probeValue(
    key: String,
    category: LabCategory,
    note: String? = null,
    block: () -> Any?,
): Observation = try {
    val v = block()
    if (v == null) {
        Observation.unsupported(key, category, note ?: "Platform returned null.")
    } else {
        Observation.confirmed(key, v, category, note)
    }
} catch (t: Throwable) {
    Observation.error(key, t, category)
}
