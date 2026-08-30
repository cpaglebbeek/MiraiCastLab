package nl.icthorse.miraicastlab.report

import android.content.Context
import android.os.Build
import nl.icthorse.miraicastlab.core.DashboardKeys
import nl.icthorse.miraicastlab.core.LabCategory
import nl.icthorse.miraicastlab.core.LabStatus
import nl.icthorse.miraicastlab.core.LogRecord
import nl.icthorse.miraicastlab.core.Observation
import nl.icthorse.miraicastlab.core.SessionLogger
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Tester-entered context for spec section 14.3.
 *
 * This is the only part of the report that is *not* machine evidence, so it is rendered in its own
 * section and every row is explicitly labelled "tester-entered". It never feeds the confirmed/
 * inferred/unsupported derivation in sections 15-18.
 */
data class VehicleContext(
    val make: String = "",
    val model: String = "",
    val year: String = "",
    val headUnitSoftware: String = "",
    val connectionMethod: String = "",
    val tester: String = "",
    val notes: String = "",
) {
    /** Ordered field list, used identically by the form and by the report table. */
    fun fields(): List<Pair<String, String>> = listOf(
        "Vehicle make" to make,
        "Vehicle model" to model,
        "Model year" to year,
        "Head-unit software version" to headUnitSoftware,
        "Connection method used" to connectionMethod,
        "Tester name / initials" to tester,
        "Notes" to notes,
    )

    val isEmpty: Boolean get() = fields().all { it.second.isBlank() }

    companion object {
        private const val PREFS = "miraicastlab.vehicle"

        /** Reads the persisted context. Missing keys are blank, never invented. */
        fun load(context: Context): VehicleContext {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return VehicleContext(
                make = p.getString("make", "").orEmpty(),
                model = p.getString("model", "").orEmpty(),
                year = p.getString("year", "").orEmpty(),
                headUnitSoftware = p.getString("sw", "").orEmpty(),
                connectionMethod = p.getString("conn", "").orEmpty(),
                tester = p.getString("tester", "").orEmpty(),
                notes = p.getString("notes", "").orEmpty(),
            )
        }

        /** Persists the context so a vehicle session survives an app kill in the car. */
        fun save(context: Context, v: VehicleContext) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("make", v.make)
                .putString("model", v.model)
                .putString("year", v.year)
                .putString("sw", v.headUnitSoftware)
                .putString("conn", v.connectionMethod)
                .putString("tester", v.tester)
                .putString("notes", v.notes)
                .apply()
        }
    }
}

/**
 * Builds the Markdown result report of spec section 14.
 *
 * Two properties matter more than anything else here:
 *
 * 1. **Sections 15-18 are derived mechanically from [LabStatus]** - CONFIRMED/OBSERVED become
 *    facts, INFERRED becomes inferences, UNSUPPORTED becomes unsupported, NOT_TESTED becomes
 *    "still needs hardware". Nothing in this file may hand-write a capability claim, and no status
 *    is ever promoted (spec section 20).
 * 2. **All twenty sections are always emitted.** An absent section would read as a silent negative;
 *    an empty one says so in words instead.
 */
object ReportGenerator {

    /** The literal sentence every empty section uses. Kept in one place so it is auditable. */
    private const val EMPTY_SECTION =
        "No observations were recorded for this section in this test run. " +
            "That is an absence of evidence, not evidence of absence: nothing here is claimed either way."

    private val ISO: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC)

    /** The A-F experiment matrix of spec section 9. Rows always render, even without evidence. */
    private val MATRIX_ROWS = listOf(
        Triple("A", "OFF", "OFF") to "baseline",
        Triple("B", "OFF", "ON") to "Miracast only",
        Triple("C", "USB ON", "OFF") to "wired Android Auto only",
        Triple("D", "USB ON", "ON") to "can wired AA coexist with Miracast?",
        Triple("E", "Wireless ON", "OFF") to "wireless Android Auto only",
        Triple("F", "Wireless ON", "ON") to "can wireless AA coexist with Miracast?",
    )

    /**
     * Events that are app bookkeeping, not capability findings.
     *
     * They stay in the raw log and are listed in section 19, but they never enter sections 15-18:
     * "the tester exported a file" is not a fact about the phone or the head unit, and letting it
     * into the confirmed-facts table would inflate the very count the conclusion is built from.
     */
    private val BOOKKEEPING_EVENTS = setOf(
        "test_run_started", "capability_scan_complete", "export_written", "export_failed",
        "export_shared",
    )

    /** Per-scenario fields spec section 9 asks for. Missing ones are reported as gaps, not as "no". */
    private val MATRIX_FIELDS = listOf(
        "connection_success", "connection_order", "failure_reason", "wifi_changes",
        "display_changes", "audio_route", "touch_behavior", "disconnect_event",
        "time_to_disconnect_ms", "animation_visible",
    )

    // ------------------------------------------------------------------ public API

    /**
     * Renders the complete report.
     *
     * @param records the log records to report on; defaults to the current run.
     */
    fun build(
        context: Context,
        vehicle: VehicleContext,
        records: List<LogRecord> = SessionLogger.snapshot(),
    ): String {
        val all = observationsFrom(records)
        val distinct = collapse(all)
        val used = HashSet<String>()

        fun take(predicate: (Observation) -> Boolean): List<Observation> =
            distinct.filter(predicate).onEach { used += identity(it) }

        val sb = StringBuilder(16 * 1024)
        sb.append("# MiraiCast Lab - evidence report\n\n")
        sb.append("Generated ").append(ISO.format(Instant.now()))
            .append(" from test run `").append(SessionLogger.testRunId).append("`.\n\n")
        sb.append("Every row below carries the evidence grade it was recorded with. ")
            .append("`NOT_TESTED` and `ERROR` make no claim about the capability.\n\n")

        section1Environment(sb, context, records, all, distinct)
        section2Phone(sb, take { it.category == LabCategory.DEVICE && it.key.startsWith("device.") })

        // 3 is tester input, not evidence, so it never touches `used`.
        header(sb, 3, "Vehicle / head-unit details entered by tester")
        vehicleTable(sb, vehicle)

        val matrix = distinct.filter { isMatrix(it) }.onEach { used += identity(it) }

        val capabilities = take {
            !isMatrix(it) && (
                it.category == LabCategory.CODEC ||
                    (it.category == LabCategory.DEVICE && !it.key.startsWith("device.")) ||
                    (it.category == LabCategory.NETWORK && !isP2p(it))
                )
        }
        header(sb, 4, "Android capabilities")
        table(sb, capabilities)
        coverageGaps(sb, distinct)

        header(sb, 5, "Miracast observations")
        table(sb, take { !isMatrix(it) && (it.category == LabCategory.MIRACAST || it.category == LabCategory.SMART_VIEW) })
        sb.append("\nSamsung Smart View findings are reported here because Smart View is the vendor ")
            .append("front-end for the same Wi-Fi Display path; their categories are preserved in the raw log.\n\n")

        header(sb, 6, "Wi-Fi Direct observations")
        table(sb, take { !isMatrix(it) && isP2p(it) })

        header(sb, 7, "Display observations")
        table(sb, take { !isMatrix(it) && it.category == LabCategory.DISPLAY })

        header(sb, 8, "Audio observations")
        table(sb, take { !isMatrix(it) && it.category == LabCategory.AUDIO })

        header(sb, 9, "Touch / input findings")
        table(sb, take { !isMatrix(it) && it.category == LabCategory.INPUT })

        header(sb, 10, "DeX findings")
        table(sb, take { !isMatrix(it) && it.category == LabCategory.DEX })

        header(sb, 11, "Android Auto findings")
        table(sb, take { !isMatrix(it) && it.category == LabCategory.ANDROID_AUTO })

        header(sb, 12, "Android Auto + Miracast coexistence matrix")
        matrixSection(sb, matrix)

        header(sb, 13, "MediaProjection findings")
        table(sb, take { !isMatrix(it) && it.category == LabCategory.MEDIAPROJECTION })

        header(sb, 14, "Motion-state lab findings")
        motionSection(sb, take { !isMatrix(it) && it.category == LabCategory.MOTION_STATE })

        // Anything no section claimed still has to be visible somewhere.
        val orphans = distinct.filterNot { identity(it) in used }
        if (orphans.isNotEmpty()) {
            sb.append("### 14b. Observations not claimed by a section above\n\n")
            sb.append("Recorded by a module whose category has no dedicated report section.\n\n")
            table(sb, orphans)
        }

        derivedSections(sb, distinct)
        section19Evidence(sb, context, records)
        section20Conclusion(sb, distinct)

        sb.append("\n---\n\nEnd of report. ")
            .append("Produced by MiraiCast Lab on-device; no data left the phone.\n")
        return sb.toString()
    }

    /** Writes the report next to the other exports and returns the file, or null without storage. */
    fun write(context: Context, markdown: String): File? {
        val dir = SessionLogger.reportDir() ?: File(context.filesDir, "reports").apply { mkdirs() }
        if (!dir.exists() && !dir.mkdirs()) return null
        val out = File(dir, "miraicastlab-report-" + SessionLogger.testRunId.take(8) + ".md")
        return runCatching { out.writeText(markdown); out }.getOrNull()
    }

    // ------------------------------------------------------------------ record -> observation

    /**
     * Reconstructs observations from the log.
     *
     * Records that are not key/value observations (scan bookkeeping, tester markers) are kept as
     * well: dropping them would make the report claim less than the run actually recorded. A marker
     * is graded OBSERVED because the tester saw it happen; it is never upgraded to CONFIRMED.
     */
    fun observationsFrom(records: List<LogRecord>): List<Observation> = records
        .filterNot { isBookkeeping(it) }
        .map { r ->
        val detail = r.details.filterKeys { it != "seq" && it != "value" && it != "note" }
        when (r.event) {
            "manual_marker" -> Observation(
                key = "tester.marker." + (r.details["seq"] ?: "0"),
                value = r.details["marker"] ?: "",
                status = LabStatus.OBSERVED,
                note = "Tester-entered marker at " + r.timestamp,
                category = r.category,
            )
            else -> Observation(
                key = r.event,
                value = r.details["value"]
                    ?: detail.entries.joinToString("; ") { it.key + "=" + it.value }.ifBlank { "-" },
                status = r.status,
                note = r.details["note"],
                category = r.category,
            )
        }
    }

    /** True for a record about the app's own housekeeping rather than about a capability. */
    private fun isBookkeeping(r: LogRecord): Boolean =
        r.event in BOOKKEEPING_EVENTS || (r.category == LabCategory.REPORT && r.event.startsWith("export"))

    /** Findings exactly as the report counts them: repeats collapsed to their latest value. */
    fun distinctFindings(records: List<LogRecord>): List<Observation> =
        collapse(observationsFrom(records))

    /**
     * Collapses repeats to the latest value per (category, key).
     *
     * A run typically re-scans several times; without this, one re-scan would triple every count in
     * sections 15-18 and the conclusion would silently overstate how much was measured. Markers are
     * exempt because each one is a distinct event.
     */
    private fun collapse(observations: List<Observation>): List<Observation> {
        val latest = LinkedHashMap<String, Observation>()
        observations.forEach { latest[identity(it)] = it }
        return latest.values.toList()
    }

    private fun identity(o: Observation): String = o.category.name + "|" + o.key

    private fun isP2p(o: Observation): Boolean =
        o.key.startsWith("wifip2p.") || o.key.startsWith("p2p.") || o.key.startsWith("wifidirect.")

    private fun isMatrix(o: Observation): Boolean =
        o.key.startsWith("matrix.") || o.key.contains("coexist")

    // ------------------------------------------------------------------ sections

    private fun header(sb: StringBuilder, number: Int, title: String) {
        sb.append("\n---\n\n## ").append(number).append(". ").append(title).append("\n\n")
    }

    private fun section1Environment(
        sb: StringBuilder,
        context: Context,
        records: List<LogRecord>,
        all: List<Observation>,
        distinct: List<Observation>,
    ) {
        header(sb, 1, "Test environment")
        val evidence = SessionLogger.currentLogFile()?.absolutePath ?: "unavailable (logger not initialised)"
        val rows = listOf(
            "Test run id" to SessionLogger.testRunId,
            "Run started at (UTC)" to SessionLogger.runStartedAt.ifBlank { "unknown" },
            "Report generated at (UTC)" to ISO.format(Instant.now()),
            "Log records in this run" to records.size.toString(),
            "Findings after collapsing repeats" to (distinct.size.toString() + " of " + all.size),
            "Evidence file (JSONL)" to evidence,
            "Report directory" to (SessionLogger.reportDir()?.absolutePath ?: "unavailable"),
            "Logger initialised" to SessionLogger.isInitialised.toString(),
            "App package" to context.packageName,
            "App version" to appVersion(context),
            "Android release / SDK" to (Build.VERSION.RELEASE + " / API " + Build.VERSION.SDK_INT),
            "Network access" to "none - the app holds no INTERNET permission",
        )
        kvTable(sb, "Property", "Value", rows)
    }

    private fun section2Phone(sb: StringBuilder, observations: List<Observation>) {
        header(sb, 2, "Phone details")
        sb.append("Read live from `android.os.Build` while this report was generated:\n\n")
        kvTable(
            sb, "Build field", "Value",
            listOf(
                "MANUFACTURER" to Build.MANUFACTURER,
                "BRAND" to Build.BRAND,
                "MODEL" to Build.MODEL,
                "DEVICE" to Build.DEVICE,
                "PRODUCT" to Build.PRODUCT,
                "HARDWARE" to Build.HARDWARE,
                "VERSION.RELEASE" to Build.VERSION.RELEASE,
                "VERSION.SDK_INT" to Build.VERSION.SDK_INT.toString(),
                "VERSION.SECURITY_PATCH" to Build.VERSION.SECURITY_PATCH,
                "FINGERPRINT" to Build.FINGERPRINT,
            ),
        )
        sb.append("\nRecorded by the device probe during this run:\n\n")
        table(sb, observations)
    }

    private fun vehicleTable(sb: StringBuilder, v: VehicleContext) {
        if (v.isEmpty) {
            sb.append(
                "The tester entered no vehicle or head-unit details for this run, so nothing is " +
                    "known about the receiver side. This section is tester input, never measured " +
                    "evidence.\n\n",
            )
            return
        }
        sb.append("Tester-entered, unverified by the app:\n\n")
        sb.append("| Field | Value entered by tester |\n|---|---|\n")
        v.fields().forEach { (k, value) ->
            sb.append("| ").append(cell(k)).append(" | ")
                .append(if (value.isBlank()) "_not entered_" else cell(value)).append(" |\n")
        }
        sb.append('\n')
    }

    private fun coverageGaps(sb: StringBuilder, distinct: List<Observation>) {
        val missing = DashboardKeys.missingFrom(distinct)
        sb.append("\n**Dashboard key coverage:** ")
        if (missing.isEmpty()) {
            sb.append("all ").append(DashboardKeys.ALL.size)
                .append(" dashboard keys were produced by a probe in this run.\n\n")
        } else {
            sb.append(DashboardKeys.ALL.size - missing.size).append(" of ")
                .append(DashboardKeys.ALL.size)
                .append(" dashboard keys were produced. The following produced nothing, which means ")
                .append("the probe did not run or did not report - it is NOT a negative result:\n\n")
            missing.forEach { sb.append("- `").append(it).append("` - NOT_TESTED\n") }
            sb.append('\n')
        }
    }

    private fun matrixSection(sb: StringBuilder, matrix: List<Observation>) {
        sb.append("Scenario grid from spec section 9. A scenario with no records was not run in ")
            .append("this session; that is NOT_TESTED and says nothing about coexistence.\n\n")
        sb.append("| Test | Android Auto | Miracast | Question | Records | Status |\n|---|---|---|---|---|---|\n")

        val grouped = matrix.groupBy {
            it.key.removePrefix("matrix.").substringBefore('.').uppercase()
        }
        MATRIX_ROWS.forEach { (row, question) ->
            val (letter, aa, cast) = row
            val found = grouped[letter].orEmpty()
            sb.append("| ").append(letter).append(" | ").append(aa).append(" | ").append(cast)
                .append(" | ").append(cell(question)).append(" | ").append(found.size).append(" | ")
                .append(if (found.isEmpty()) "NOT_TESTED" else summariseStatuses(found)).append(" |\n")
        }
        sb.append('\n')

        MATRIX_ROWS.forEach { (row, _) ->
            val letter = row.first
            val found = grouped[letter].orEmpty()
            if (found.isEmpty()) return@forEach
            sb.append("#### Scenario ").append(letter).append("\n\n")
            table(sb, found)
            val present = found.map { it.key.substringAfterLast('.') }.toSet()
            val absent = MATRIX_FIELDS.filterNot { it in present }
            if (absent.isNotEmpty()) {
                sb.append("Fields spec section 9 asks for that this scenario did not record ")
                    .append("(NOT_TESTED, not negative): ")
                    .append(absent.joinToString(", ") { "`" + it + "`" }).append("\n\n")
            }
        }

        val loose = matrix.filterNot {
            it.key.removePrefix("matrix.").substringBefore('.').uppercase() in
                MATRIX_ROWS.map { r -> r.first.first }
        }
        if (loose.isNotEmpty()) {
            sb.append("#### Other coexistence observations\n\n")
            table(sb, loose)
        }
        if (matrix.isEmpty()) sb.append(EMPTY_SECTION).append("\n\n")
    }

    private fun motionSection(sb: StringBuilder, observations: List<Observation>) {
        sb.append("Observation only. The lab never touches a vehicle safety interlock: if the head ")
            .append("unit blanks video while moving, that blanking is the recorded result.\n\n")
        table(sb, observations)
    }

    private fun derivedSections(sb: StringBuilder, distinct: List<Observation>) {
        // Sections 15-18 are pure functions of LabStatus. Nothing here is authored by hand.
        header(sb, 15, "Confirmed facts")
        sb.append("Derived mechanically from status CONFIRMED and OBSERVED.\n\n")
        table(sb, distinct.filter { it.status.isPositiveClaim })

        header(sb, 16, "Inferences")
        sb.append("Derived mechanically from status INFERRED. These are not facts and must not be ")
            .append("quoted as capabilities.\n\n")
        table(sb, distinct.filter { it.status == LabStatus.INFERRED })

        header(sb, 17, "Unsupported features")
        sb.append("Derived mechanically from status UNSUPPORTED: the platform or an API answered ")
            .append("that the capability is absent in this environment.\n\n")
        table(sb, distinct.filter { it.status == LabStatus.UNSUPPORTED })

        header(sb, 18, "Tests still requiring hardware")
        sb.append("Derived mechanically from status NOT_TESTED. Nothing below is a negative result; ")
            .append("each item needs the physical Samsung Galaxy Z Fold and/or the Toyota Mirai ")
            .append("head unit before anything may be said about it.\n\n")
        table(sb, distinct.filter { it.status == LabStatus.NOT_TESTED })

        val errors = distinct.filter { it.status == LabStatus.ERROR }
        sb.append("\n### 18a. Probe failed, still untested\n\n")
        sb.append("The probe itself failed. A failed probe says nothing at all about the capability, ")
            .append("so these remain untested rather than unsupported.\n\n")
        table(sb, errors)
    }

    private fun section19Evidence(sb: StringBuilder, context: Context, records: List<LogRecord>) {
        header(sb, 19, "Raw evidence / log references")
        kvTable(
            sb, "Artifact", "Location",
            listOf(
                "Test run id" to SessionLogger.testRunId,
                "JSONL evidence (append-only, survives an app kill)" to
                    (SessionLogger.currentLogFile()?.absolutePath ?: "unavailable"),
                "Evidence directory" to (SessionLogger.evidenceDir()?.absolutePath ?: "unavailable"),
                "Report / export directory" to (SessionLogger.reportDir()?.absolutePath ?: "unavailable"),
                "Records in this run" to records.size.toString(),
            ),
        )
        sb.append("\nPull with: `adb exec-out run-as ").append(context.packageName)
            .append(" tar c files/evidence | tar x`\n\n")

        sb.append("Records per category:\n\n")
        val perCategory = records.groupingBy { it.category }.eachCount()
        sb.append("| Category | Records |\n|---|---|\n")
        LabCategory.values().forEach { c ->
            sb.append("| ").append(c.name).append(" | ").append(perCategory[c] ?: 0).append(" |\n")
        }
        sb.append('\n')

        val bookkeeping = records.filter { isBookkeeping(it) }
        sb.append("Run bookkeeping events, kept out of sections 15-18 because they describe the app, ")
            .append("not a capability: ").append(bookkeeping.size).append("\n\n")
        if (bookkeeping.isNotEmpty()) {
            sb.append("| Timestamp | Event | Status | Details |\n|---|---|---|---|\n")
            bookkeeping.forEach { b ->
                sb.append("| ").append(cell(b.timestamp)).append(" | ").append(cell(b.event))
                    .append(" | ").append(b.status.label).append(" | ")
                    .append(cell(b.details.filterKeys { k -> k != "seq" }.entries.joinToString("; ") { e -> e.key + "=" + e.value }))
                    .append(" |\n")
            }
            sb.append('\n')
        }

        val markers = records.filter { it.event == "manual_marker" }
        sb.append("\nTester markers in this run: ").append(markers.size).append("\n\n")
        if (markers.isNotEmpty()) {
            sb.append("| Timestamp | Elapsed ms | Category | Marker |\n|---|---|---|---|\n")
            markers.forEach { m ->
                sb.append("| ").append(cell(m.timestamp)).append(" | ").append(m.elapsedRealtimeMs)
                    .append(" | ").append(m.category.name).append(" | ")
                    .append(cell(m.details["marker"] ?: "")).append(" |\n")
            }
            sb.append('\n')
        }
    }

    private fun section20Conclusion(sb: StringBuilder, distinct: List<Observation>) {
        header(sb, 20, "Conclusion")
        // Counts only. This section must never name a capability, or it would become a claim the
        // evidence does not carry.
        val confirmed = distinct.count { it.status.isPositiveClaim }
        val inferred = distinct.count { it.status == LabStatus.INFERRED }
        val unsupported = distinct.count { it.status == LabStatus.UNSUPPORTED }
        val notTested = distinct.count { it.status == LabStatus.NOT_TESTED }
        val errored = distinct.count { it.status == LabStatus.ERROR }

        sb.append("This run recorded **").append(distinct.size).append(" distinct findings**: ")
            .append(confirmed).append(" confirmed or observed, ")
            .append(inferred).append(" inferred, ")
            .append(unsupported).append(" unsupported, ")
            .append(notTested).append(" still requiring the physical Samsung Galaxy Z Fold and/or ")
            .append("Toyota Mirai head unit, and ")
            .append(errored).append(" where the probe itself failed.\n\n")
        sb.append("The ").append(notTested + errored)
            .append(" items in sections 18 and 18a make no claim in either direction. ")
            .append("They were not measured in this run and must not be read as unsupported.\n\n")
        sb.append("| Status | Count | Meaning |\n|---|---|---|\n")
        LabStatus.values().forEach { s ->
            sb.append("| ").append(s.label).append(" | ")
                .append(distinct.count { it.status == s }).append(" | ")
                .append(cell(s.explanation)).append(" |\n")
        }
        sb.append('\n')
    }

    // ------------------------------------------------------------------ markdown helpers

    /** Version name of the installed package, or a stated unknown. Never guessed. */
    private fun appVersion(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    }.getOrElse { "unknown (" + it::class.java.simpleName + ")" }

    /** Escapes a value for a Markdown table cell: pipes break the row, newlines break the table. */
    private fun cell(raw: String?): String {
        val s = (raw ?: "").replace("|", "\\|").replace("\r", " ").replace("\n", " ").trim()
        return if (s.isEmpty()) "-" else s
    }

    private fun table(sb: StringBuilder, observations: List<Observation>) {
        if (observations.isEmpty()) {
            sb.append(EMPTY_SECTION).append("\n\n")
            return
        }
        sb.append("| Key | Value | Status | Note |\n|---|---|---|---|\n")
        observations.forEach { o ->
            sb.append("| `").append(cell(o.key)).append("` | ").append(cell(o.value))
                .append(" | ").append(o.status.label).append(" | ").append(cell(o.note)).append(" |\n")
        }
        sb.append('\n')
    }

    private fun kvTable(sb: StringBuilder, keyHeader: String, valueHeader: String, rows: List<Pair<String, String>>) {
        sb.append("| ").append(keyHeader).append(" | ").append(valueHeader).append(" |\n|---|---|\n")
        rows.forEach { (k, v) -> sb.append("| ").append(cell(k)).append(" | ").append(cell(v)).append(" |\n") }
        sb.append('\n')
    }

    /** Compact status summary for a matrix row, worst-news-first so a reader cannot miss it. */
    private fun summariseStatuses(observations: List<Observation>): String {
        val order = listOf(
            LabStatus.ERROR, LabStatus.UNSUPPORTED, LabStatus.NOT_TESTED,
            LabStatus.INFERRED, LabStatus.OBSERVED, LabStatus.CONFIRMED,
        )
        return order.mapNotNull { s ->
            val n = observations.count { it.status == s }
            if (n == 0) null else s.label + " x" + n
        }.joinToString(", ")
    }
}
