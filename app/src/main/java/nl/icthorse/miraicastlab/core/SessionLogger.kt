package nl.icthorse.miraicastlab.core

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * The single sink for every observation the lab makes (spec section 13).
 *
 * Design notes:
 * - Process-wide singleton. Every experiment runs inside one Activity and one process, and the
 *   report must be able to see records from all of them, so a singleton is the honest model here.
 * - Append-only JSONL on disk plus an in-memory [StateFlow] for the live viewer. The disk write is
 *   what survives an app kill during a vehicle test, which is exactly when it matters most.
 * - Thread-safe: probes log from coroutines, from the MediaProjection callback thread and from
 *   input dispatch. All mutation goes through [lock].
 */
object SessionLogger {

    private const val TAG = "MiraiCastLab"
    private const val MAX_IN_MEMORY = 5000

    private val lock = Any()
    private val sequence = AtomicLong(0)
    private val iso: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC)

    private var appContext: Context? = null
    private var logFile: File? = null

    private val _records = MutableStateFlow<List<LogRecord>>(emptyList())

    /** Live view of the current test run's records, newest last. */
    val records: StateFlow<List<LogRecord>> = _records.asStateFlow()

    /** Identifier of the current test run. Every record carries it. */
    @Volatile
    var testRunId: String = "uninitialised"
        private set

    /** Wall-clock start of the current run, ISO-8601. */
    @Volatile
    var runStartedAt: String = ""
        private set

    /** True once [init] has run. Probes may log before this; those records stay in memory only. */
    val isInitialised: Boolean get() = appContext != null

    /**
     * Binds the logger to application storage and opens the first test run.
     * Safe to call more than once; only the first call creates a run.
     */
    fun init(context: Context) {
        synchronized(lock) {
            if (appContext != null) return
            appContext = context.applicationContext
            newRun(reason = "app start")
        }
    }

    /** Starts a fresh test run: new id, cleared in-memory buffer, new evidence file. */
    fun newRun(reason: String = "manual") {
        synchronized(lock) {
            testRunId = UUID.randomUUID().toString()
            runStartedAt = nowIso()
            sequence.set(0)
            _records.value = emptyList()
            logFile = evidenceDir()?.let { dir ->
                File(dir, "run-" + runStartedAt.replace(":", "-") + "-" + testRunId.take(8) + ".jsonl")
            }
        }
        log(
            LabCategory.USER,
            "test_run_started",
            LabStatus.CONFIRMED,
            mapOf("reason" to reason, "startedAt" to runStartedAt),
        )
    }

    /** Records one event. This is the primary entry point for every module. */
    fun log(
        category: LabCategory,
        event: String,
        status: LabStatus,
        details: Map<String, String> = emptyMap(),
    ): LogRecord {
        val record = LogRecord(
            timestamp = nowIso(),
            elapsedRealtimeMs = SystemClock.elapsedRealtime(),
            testRunId = testRunId,
            category = category,
            event = event,
            status = status,
            details = details + ("seq" to sequence.incrementAndGet().toString()),
        )
        synchronized(lock) {
            val next = _records.value + record
            _records.value = if (next.size > MAX_IN_MEMORY) next.takeLast(MAX_IN_MEMORY) else next
            appendToDisk(record)
        }
        Log.i(TAG, category.name + " " + event + " [" + status.name + "] " + record.details)
        return record
    }

    /** Records a whole [Observation] as an event. */
    fun log(observation: Observation): LogRecord = log(
        observation.category,
        observation.key,
        observation.status,
        buildMap {
            put("value", observation.value)
            observation.note?.let { put("note", it) }
        },
    )

    /** Records a list of observations in order. */
    fun logAll(observations: List<Observation>) = observations.forEach { log(it) }

    /** A tester-entered marker (spec sections 10 and 12: "add manual observation marker"). */
    fun marker(text: String, category: LabCategory = LabCategory.USER) =
        log(category, "manual_marker", LabStatus.OBSERVED, mapOf("marker" to text))

    /** Snapshot of all records in the current run. */
    fun snapshot(): List<LogRecord> = _records.value

    /** All observations logged so far, reconstructed from the records. */
    fun observations(): List<Observation> = snapshot()
        .filter { it.event != "manual_marker" && it.event != "test_run_started" }
        .map {
            Observation(
                key = it.event,
                value = it.details["value"] ?: "",
                status = it.status,
                note = it.details["note"],
                category = it.category,
            )
        }

    // ---------------------------------------------------------------- storage

    /** App-private evidence directory. Never external storage: nothing here leaves the device. */
    fun evidenceDir(): File? = appContext?.let {
        File(it.filesDir, "evidence").apply { mkdirs() }
    }

    /** App-private report directory. */
    fun reportDir(): File? = appContext?.let {
        File(it.filesDir, "reports").apply { mkdirs() }
    }

    /** Path of the JSONL evidence file for the current run, or null before [init]. */
    fun currentLogFile(): File? = logFile

    private fun appendToDisk(record: LogRecord) {
        val f = logFile ?: return
        runCatching { f.appendText(Json.record(record) + "\n") }
            .onFailure { Log.w(TAG, "evidence write failed", it) }
    }

    private fun nowIso(): String = iso.format(Instant.now())

    // ---------------------------------------------------------------- export

    /** Exports the current run as JSON. Returns null if storage is unavailable. */
    fun exportJson(): File? {
        val dir = reportDir() ?: return null
        val out = File(dir, "miraicastlab-" + testRunId.take(8) + ".json")
        val body = snapshot().joinToString(",\n  ") { Json.record(it) }
        out.writeText("{\n  \"testRunId\": " + Json.str(testRunId) + ",\n" +
            "  \"startedAt\": " + Json.str(runStartedAt) + ",\n" +
            "  \"records\": [\n  " + body + "\n  ]\n}\n")
        return out
    }

    /** Exports the current run as CSV. Returns null if storage is unavailable. */
    fun exportCsv(): File? {
        val dir = reportDir() ?: return null
        val out = File(dir, "miraicastlab-" + testRunId.take(8) + ".csv")
        val sb = StringBuilder("timestamp,elapsedRealtimeMs,category,event,status,details\n")
        snapshot().forEach { r ->
            sb.append(Json.csvCell(r.timestamp)).append(',')
                .append(r.elapsedRealtimeMs).append(',')
                .append(r.category.name).append(',')
                .append(Json.csvCell(r.event)).append(',')
                .append(r.status.name).append(',')
                .append(Json.csvCell(r.details.entries.joinToString("; ") { it.key + "=" + it.value }))
                .append('\n')
        }
        out.writeText(sb.toString())
        return out
    }
}
