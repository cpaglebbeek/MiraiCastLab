package nl.icthorse.miraicastlab.report

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import nl.icthorse.miraicastlab.core.LabCategory
import nl.icthorse.miraicastlab.core.LabStatus
import nl.icthorse.miraicastlab.core.SessionLogger
import nl.icthorse.miraicastlab.ui.BigActionButton
import nl.icthorse.miraicastlab.ui.Gap
import nl.icthorse.miraicastlab.ui.HGap
import nl.icthorse.miraicastlab.ui.LabButton
import nl.icthorse.miraicastlab.ui.LabCard
import nl.icthorse.miraicastlab.ui.LabColors
import nl.icthorse.miraicastlab.ui.LabMono
import nl.icthorse.miraicastlab.ui.LabScaffold
import nl.icthorse.miraicastlab.ui.MonoBlock
import nl.icthorse.miraicastlab.ui.StatTile
import nl.icthorse.miraicastlab.ui.statusColor
import java.io.File

/** How much of the report the live preview renders. The exported file is always complete. */
private const val PREVIEW_CHARS = 6000

/**
 * Report and export screen (spec sections 13 and 14).
 *
 * Everything the tester types here lands in section 3 of the report and nowhere else: tester input
 * is never mixed into the evidence-graded sections, because a typed claim is not a measurement.
 */
@Composable
fun ReportScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val records by SessionLogger.records.collectAsState()

    var vehicle by remember { mutableStateOf(VehicleContext.load(ctx)) }
    var lastExport by remember { mutableStateOf<File?>(null) }
    var message by remember { mutableStateOf("") }
    var confirmNewRun by remember { mutableStateOf(false) }
    var fullPreview by remember { mutableStateOf(false) }

    // Rebuilt whenever a record arrives or a form field changes: the preview is the report.
    val markdown = remember(records, vehicle) { ReportGenerator.build(ctx, vehicle, records) }
    val counts = remember(records) {
        val obs = ReportGenerator.distinctFindings(records)
        LabStatus.values().associateWith { s -> obs.count { it.status == s } }
    }

    fun persist(next: VehicleContext) {
        vehicle = next
        VehicleContext.save(ctx, next)
    }

    fun report(kind: String, file: File?) {
        if (file == null) {
            message = "Export failed: no writable storage for " + kind + "."
            SessionLogger.log(
                LabCategory.REPORT, "export_failed", LabStatus.ERROR,
                mapOf("kind" to kind, "reason" to "no writable storage"),
            )
            return
        }
        lastExport = file
        message = file.absolutePath
        SessionLogger.log(
            LabCategory.REPORT, "export_written", LabStatus.CONFIRMED,
            mapOf("kind" to kind, "path" to file.absolutePath, "bytes" to file.length().toString()),
        )
    }

    LabScaffold(
        title = "Report & export",
        subtitle = "run " + SessionLogger.testRunId.take(8) + " - " + records.size + " records",
        onBack = onBack,
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            LabCard("Evidence in this run") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                ) {
                    LabStatus.values().forEach { s ->
                        StatTile(
                            value = (counts[s] ?: 0).toString(),
                            label = s.label,
                            color = statusColor(s),
                        )
                    }
                }
                Gap()
                Text(
                    "NOT_TESTED and ERROR make no claim. They are never reported as unsupported.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LabColors.TextDim,
                )
            }

            LabCard("3. Vehicle / head-unit details (tester-entered)") {
                Text(
                    "Typed here, reported as tester input only - the app cannot verify any of it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LabColors.TextDim,
                )
                Gap()
                LabField("Vehicle make", vehicle.make) { persist(vehicle.copy(make = it)) }
                LabField("Vehicle model", vehicle.model) { persist(vehicle.copy(model = it)) }
                LabField("Model year", vehicle.year) { persist(vehicle.copy(year = it)) }
                LabField("Head-unit software version", vehicle.headUnitSoftware) {
                    persist(vehicle.copy(headUnitSoftware = it))
                }
                LabField("Connection method used", vehicle.connectionMethod) {
                    persist(vehicle.copy(connectionMethod = it))
                }
                LabField("Tester name / initials", vehicle.tester) { persist(vehicle.copy(tester = it)) }
                LabField("Notes", vehicle.notes, singleLine = false) { persist(vehicle.copy(notes = it)) }
                Gap()
                Text(
                    "Saved automatically; survives an app restart in the vehicle.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LabColors.TextDim,
                )
            }

            LabCard("Export") {
                BigActionButton(
                    text = "EXPORT MARKDOWN",
                    subtitle = "the full 20-section report",
                    onClick = { report("markdown", ReportGenerator.write(ctx, markdown)) },
                )
                Row(Modifier.fillMaxWidth()) {
                    LabButton("EXPORT JSON", { report("json", runCatching { SessionLogger.exportJson() }.getOrNull()) }, Modifier.weight(1f))
                    HGap()
                    LabButton("EXPORT CSV", { report("csv", runCatching { SessionLogger.exportCsv() }.getOrNull()) }, Modifier.weight(1f))
                }
                Gap()
                BigActionButton(
                    text = "SHARE LAST EXPORT",
                    subtitle = lastExport?.name ?: "export something first",
                    enabled = lastExport != null,
                    onClick = {
                        val f = lastExport
                        if (f == null) {
                            message = "Nothing exported yet."
                        } else {
                            val result = shareFile(ctx, f)
                            message = result.exceptionOrNull()?.let {
                                "Share failed: " + it::class.java.simpleName + " - file is still at " + f.absolutePath
                            } ?: ("Shared " + f.absolutePath)
                            SessionLogger.log(
                                LabCategory.REPORT, "export_shared",
                                if (result.isSuccess) LabStatus.OBSERVED else LabStatus.ERROR,
                                mapOf("path" to f.absolutePath, "ok" to result.isSuccess.toString()),
                            )
                        }
                    },
                )
                Gap()
                Text("Last written file:", style = MaterialTheme.typography.bodyMedium, color = LabColors.TextDim)
                MonoBlock(message.ifBlank { "nothing exported in this run yet" })
                Gap()
                Text(
                    "Evidence directory (adb pull target):",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LabColors.TextDim,
                )
                MonoBlock(SessionLogger.currentLogFile()?.absolutePath ?: "logger not initialised")
            }

            LabCard("Report preview") {
                Text(
                    "Rendered from the live log; sections 15-18 are derived from the evidence grades, " +
                        "never written by hand.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LabColors.TextDim,
                )
                Gap()
                val shown = if (fullPreview || markdown.length <= PREVIEW_CHARS) {
                    markdown
                } else {
                    markdown.take(PREVIEW_CHARS) + "\n\n... preview truncated at " + PREVIEW_CHARS +
                        " of " + markdown.length + " characters. The exported file is complete."
                }
                MonoBlock(shown)
                Gap()
                LabButton(
                    if (fullPreview) "SHOW LESS" else "SHOW FULL REPORT (" + markdown.length + " chars)",
                    { fullPreview = !fullPreview },
                )
            }

            LabCard("Test session") {
                Text(
                    "A new run gets a new testRunId and a new evidence file. The in-memory buffer of " +
                        records.size + " records is cleared; already-written evidence files stay on disk.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LabColors.TextDim,
                )
                Gap()
                LabButton("NEW TEST RUN", { confirmNewRun = true })
            }
            Gap(24)
        }
    }

    if (confirmNewRun) {
        AlertDialog(
            onDismissRequest = { confirmNewRun = false },
            title = { Text("Start a new test run?") },
            text = {
                Text(
                    "This clears the live buffer of " + records.size + " records and starts a new " +
                        "evidence file. Export first if this run is not saved yet.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmNewRun = false
                    lastExport = null
                    message = ""
                    SessionLogger.newRun(reason = "tester started a new run from the report screen")
                }) { Text("NEW RUN") }
            },
            dismissButton = {
                TextButton(onClick = { confirmNewRun = false }) { Text("CANCEL") }
            },
        )
    }
}

/** One persisted form field. Kept tiny so the form reads as the report section it feeds. */
@Composable
private fun LabField(
    label: String,
    value: String,
    singleLine: Boolean = true,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, style = LabMono) },
        singleLine = singleLine,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    )
}

/**
 * Hands one evidence file to a chooser through the FileProvider.
 *
 * The app has no INTERNET permission; sharing is the only way anything leaves the device, and it is
 * always an explicit tester action on a file the tester can also see the path of.
 */
private fun shareFile(context: Context, file: File): Result<Unit> = runCatching {
    val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mimeFor(file)
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "MiraiCast Lab evidence " + SessionLogger.testRunId.take(8))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(send, "Share evidence file").apply {
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(chooser)
}

private fun mimeFor(file: File): String = when (file.extension.lowercase()) {
    "json" -> "application/json"
    "csv" -> "text/csv"
    "md" -> "text/markdown"
    "jsonl" -> "application/x-ndjson"
    else -> "text/plain"
}
