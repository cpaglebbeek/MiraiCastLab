package nl.icthorse.miraicastlab.scan

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import nl.icthorse.miraicastlab.core.Observation

/**
 * The most recent full capability scan, kept process-wide.
 *
 * A scan takes seconds and touches every probe, so losing it because the tester navigated to a
 * test screen and back would cost real time in a vehicle. This holds the result only; the
 * authoritative record is already on disk in SessionLogger's JSONL evidence file.
 */
object ScanState {

    private val _last = MutableStateFlow<List<Observation>>(emptyList())

    /** Observations from the last completed scan, empty until one has run. */
    val last: StateFlow<List<Observation>> = _last.asStateFlow()

    /** Wall duration of that scan in milliseconds, 0 when none has run. */
    @Volatile
    var lastDurationMs: Long = 0L
        private set

    fun publish(observations: List<Observation>, durationMs: Long) {
        lastDurationMs = durationMs
        _last.value = observations
    }
}
