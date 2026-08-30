package nl.icthorse.miraicastlab.auto

import nl.icthorse.miraicastlab.core.Json
import nl.icthorse.miraicastlab.core.LabStatus
import nl.icthorse.miraicastlab.core.SessionLogger
import java.io.File

/**
 * The spec section 9 experiment matrix: six states of (Android Auto x Miracast) and what the tester
 * observed in each.
 *
 * Half of every row is captured by the app (environment at the moment the case starts) and half is
 * entered by the tester (did it connect, in which order, what did the head unit do). Neither half is
 * inferred from the other: a row with an automatic capture but no tester verdict is still
 * NOT_TESTED, because the capture proves what the phone saw, not what the vehicle did.
 */
internal enum class MatrixRow(
    val rowId: String,
    val androidAuto: String,
    val miracast: String,
    val question: String,
) {
    A("A", "OFF", "OFF", "Baseline: what does the phone look like with neither session up?"),
    B("B", "OFF", "ON", "Miracast alone: does the video path work at all?"),
    C("C", "USB ON", "OFF", "Wired Android Auto alone."),
    D("D", "USB ON", "ON", "Can wired Android Auto coexist with Miracast?"),
    E("E", "Wireless ON", "OFF", "Wireless Android Auto alone."),
    F("F", "Wireless ON", "ON", "Can wireless Android Auto coexist with Miracast?"),
    ;

    val label: String get() = "Case " + rowId + " - AA " + androidAuto + " / Miracast " + miracast
}

/** Free-choice answers the tester fills in. Empty string always means "not answered". */
internal object MatrixAnswers {
    val SUCCESS = listOf("YES", "NO", "PARTIAL")
    val ORDER = listOf("AA first", "Miracast first", "Simultaneous", "N/A")
    val ANIMATION = listOf("VISIBLE", "BLOCKED", "FROZEN", "N/A")
}

/**
 * One matrix case. Everything is a String so the on-disk shape is a flat JSON object, which keeps
 * both the writer and [MiniJson] trivial and keeps a half-written file readable.
 */
internal data class MatrixCase(
    val rowId: String,
    val startedAt: String = "",
    val startedElapsedMs: Long = 0L,
    val capture: Map<String, String> = emptyMap(),
    val connectionSuccess: String = "",
    val connectionOrder: String = "",
    val failureReason: String = "",
    val animationVisible: String = "",
    val touchBehaviour: String = "",
    val notes: String = "",
    val timerStartedElapsedMs: Long = 0L,
    val disconnectAt: String = "",
    val timeUntilDisconnectMs: Long = -1L,
) {
    val started: Boolean get() = startedAt.isNotEmpty()
    val timerRunning: Boolean get() = timerStartedElapsedMs > 0L && timeUntilDisconnectMs < 0L

    /**
     * Evidence grade for the row.
     *
     * OBSERVED requires a tester verdict. A started-but-unanswered row stays NOT_TESTED: the
     * environment capture says nothing about whether the two sessions coexisted.
     */
    val status: LabStatus
        get() = when {
            !started -> LabStatus.NOT_TESTED
            connectionSuccess.isEmpty() -> LabStatus.NOT_TESTED
            else -> LabStatus.OBSERVED
        }

    val statusNote: String
        get() = when {
            !started -> "Not run. No claim is made about this combination."
            connectionSuccess.isEmpty() ->
                "Environment captured at " + startedAt + ", but the tester has not recorded whether " +
                    "the connection succeeded. The coexistence question is still unanswered."
            else -> "Tester verdict recorded during this run."
        }

    fun toFlatMap(): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        m["rowId"] = rowId
        m["startedAt"] = startedAt
        m["startedElapsedMs"] = startedElapsedMs.toString()
        m["connectionSuccess"] = connectionSuccess
        m["connectionOrder"] = connectionOrder
        m["failureReason"] = failureReason
        m["animationVisible"] = animationVisible
        m["touchBehaviour"] = touchBehaviour
        m["notes"] = notes
        m["timerStartedElapsedMs"] = timerStartedElapsedMs.toString()
        m["disconnectAt"] = disconnectAt
        m["timeUntilDisconnectMs"] = timeUntilDisconnectMs.toString()
        // The capture is flattened with a prefix so the file stays one level deep.
        capture.forEach { (k, v) -> m["capture." + k] = v }
        return m
    }

    companion object {
        fun fromFlatMap(m: Map<String, String>): MatrixCase? {
            val id = m["rowId"]?.takeIf { it.isNotEmpty() } ?: return null
            val capture = LinkedHashMap<String, String>()
            m.forEach { (k, v) -> if (k.startsWith("capture.")) capture[k.removePrefix("capture.")] = v }
            return MatrixCase(
                rowId = id,
                startedAt = m["startedAt"].orEmpty(),
                startedElapsedMs = m["startedElapsedMs"]?.toLongOrNull() ?: 0L,
                capture = capture,
                connectionSuccess = m["connectionSuccess"].orEmpty(),
                connectionOrder = m["connectionOrder"].orEmpty(),
                failureReason = m["failureReason"].orEmpty(),
                animationVisible = m["animationVisible"].orEmpty(),
                touchBehaviour = m["touchBehaviour"].orEmpty(),
                notes = m["notes"].orEmpty(),
                timerStartedElapsedMs = m["timerStartedElapsedMs"]?.toLongOrNull() ?: 0L,
                disconnectAt = m["disconnectAt"].orEmpty(),
                timeUntilDisconnectMs = m["timeUntilDisconnectMs"]?.toLongOrNull() ?: -1L,
            )
        }
    }
}

/**
 * Disk persistence for the matrix.
 *
 * Written on every single edit rather than on leaving the screen: a vehicle test is expensive to
 * repeat, and the app being killed while backgrounded behind Android Auto is a realistic outcome of
 * the very experiment being run.
 */
internal object MatrixStore {

    private const val PREFIX = "aa-matrix-"
    private const val SUFFIX = ".json"

    data class Loaded(
        val cases: Map<String, MatrixCase>,
        val sourceFile: String?,
        val fromEarlierRun: Boolean,
    )

    fun fileForCurrentRun(): File? {
        val dir = SessionLogger.evidenceDir() ?: return null
        return File(dir, PREFIX + SessionLogger.testRunId + SUFFIX)
    }

    /** Serialises the whole matrix. Returns the file written, or null when storage is unavailable. */
    fun save(cases: Collection<MatrixCase>): File? {
        val file = fileForCurrentRun() ?: return null
        return try {
            val body = cases
                .sortedBy { it.rowId }
                .joinToString(",\n    ") { Json.obj(it.toFlatMap()) }
            val text = "{\n" +
                "  \"testRunId\": " + Json.str(SessionLogger.testRunId) + ",\n" +
                "  \"savedAt\": " + Json.str(LabEnv.nowIso()) + ",\n" +
                "  \"schema\": \"miraicastlab.aa-matrix.v1\",\n" +
                "  \"cases\": [\n    " + body + "\n  ]\n}\n"
            file.writeText(text)
            file
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Loads the matrix for the current run, falling back to the newest matrix file from any earlier
     * run. The fallback is flagged, never silently merged: rows carried over from another run were
     * observed against a different environment snapshot and the tester must be told.
     */
    fun load(): Loaded {
        val dir = SessionLogger.evidenceDir() ?: return Loaded(emptyMap(), null, false)
        return try {
            val current = fileForCurrentRun()
            val file = when {
                current != null && current.isFile && current.length() > 0 -> current
                else -> dir.listFiles()
                    ?.filter { it.isFile && it.name.startsWith(PREFIX) && it.name.endsWith(SUFFIX) }
                    ?.maxByOrNull { it.lastModified() }
            } ?: return Loaded(emptyMap(), null, false)

            val root = MiniJson.parse(file.readText()) ?: return Loaded(emptyMap(), file.name, false)
            val cases = MiniJson.objectArray(root, "cases")
                .mapNotNull { MatrixCase.fromFlatMap(it) }
                .associateBy { it.rowId }
            val fileRunId = MiniJson.str(root, "testRunId").orEmpty()
            Loaded(cases, file.name, fileRunId.isNotEmpty() && fileRunId != SessionLogger.testRunId)
        } catch (t: Throwable) {
            Loaded(emptyMap(), null, false)
        }
    }
}
