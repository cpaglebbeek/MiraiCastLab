package nl.icthorse.miraicastlab.samsung

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.icthorse.miraicastlab.core.LabCategory
import nl.icthorse.miraicastlab.core.LabStatus
import nl.icthorse.miraicastlab.core.Observation
import nl.icthorse.miraicastlab.core.SessionLogger
import nl.icthorse.miraicastlab.core.safeObserve
import nl.icthorse.miraicastlab.ui.BigActionButton
import nl.icthorse.miraicastlab.ui.Gap
import nl.icthorse.miraicastlab.ui.LabButton
import nl.icthorse.miraicastlab.ui.LabCard
import nl.icthorse.miraicastlab.ui.LabColors
import nl.icthorse.miraicastlab.ui.LabMono
import nl.icthorse.miraicastlab.ui.LabScaffold
import nl.icthorse.miraicastlab.ui.MonoBlock
import nl.icthorse.miraicastlab.ui.ObservationRow
import nl.icthorse.miraicastlab.ui.VerdictBanner
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/**
 * Samsung DeX findings (spec section 2.3).
 *
 * DeX is the question that decides whether the head unit can host real Android activities or only
 * receive a mirror. The phone-side half of that is measurable right now; the vehicle-side half is
 * not, and this screen keeps the two visibly separate rather than letting the reader assume the
 * absence of DeX signals means DeX was refused by the car.
 *
 * The live watcher exists because the DeX transition is the evidence: entering DeX changes the
 * display topology and the external display's density within a second or two, and those two
 * timestamps are what distinguish DeX from Smart View mirroring.
 */
@Composable
fun DexScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val findings = remember { mutableStateListOf<Observation>() }
    var probing by remember { mutableStateOf(false) }

    // Live state, refreshed once a second so the DeX transition is caught while the tester watches.
    var desktopMode by remember { mutableStateOf<Boolean?>(null) }
    var uiMode by remember { mutableStateOf("-") }
    var displays by remember { mutableStateOf<List<SurfaceDisplayFact>>(emptyList()) }
    val transitions = remember { mutableStateListOf<String>() }

    val intents = remember(context) { castIntents(context) }
    var launchMessage by remember { mutableStateOf<String?>(null) }

    fun note(text: String) {
        val stamped = LocalTime.now().truncatedTo(ChronoUnit.SECONDS).toString() + "  " + text
        transitions.add(stamped)
        SessionLogger.log(
            LabCategory.DEX, "dex_live_transition", LabStatus.OBSERVED, mapOf("change" to text),
        )
    }

    fun runProbe() {
        if (probing) return
        probing = true
        scope.launch {
            val found = withContext(Dispatchers.Default) { DexProbe.safeObserve(context) }
            findings.clear()
            findings.addAll(found)
            SessionLogger.logAll(found)
            probing = false
        }
    }

    LaunchedEffect(Unit) { runProbe() }

    // Display topology events give the exact instant of a transition; the poll below gives the
    // density, which is the value that actually discriminates DeX from a mirror.
    DisposableEffect(context) {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val handler = Handler(Looper.getMainLooper())
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = note("display ADDED #" + displayId)
            override fun onDisplayRemoved(displayId: Int) = note("display REMOVED #" + displayId)
            override fun onDisplayChanged(displayId: Int) = note("display CHANGED #" + displayId)
        }
        runCatching { dm?.registerDisplayListener(listener, handler) }
        onDispose { runCatching { dm?.unregisterDisplayListener(listener) } }
    }

    LaunchedEffect(Unit) {
        var previous = ""
        while (true) {
            val reflection = readDexReflection(context)
            val facts = withContext(Dispatchers.Default) { readDisplayFacts(context) }
            desktopMode = reflection.desktopModeActive
            uiMode = uiModeTypeName(uiModeType(context))
            displays = facts
            val signature = "desktop=" + (reflection.desktopModeActive?.toString() ?: "unreadable") +
                " ui=" + uiMode +
                " displays=" + facts.joinToString(",") {
                    it.id.toString() + ":" + it.width + "x" + it.height + "@" + it.densityDpi
                }
            if (previous.isNotEmpty() && signature != previous) {
                note(signature)
            }
            previous = signature
            delay(1000)
        }
    }

    val pending = findings.filter { it.status == LabStatus.NOT_TESTED }
    val measured = findings.filter { it.status != LabStatus.NOT_TESTED }

    LabScaffold(
        title = "Samsung DeX",
        subtitle = "phone-side facts now, vehicle-side facts still open",
        onBack = onBack,
    ) {
        LazyColumn(Modifier.fillMaxSize()) {

            item {
                val status = when (desktopMode) {
                    true -> LabStatus.OBSERVED
                    false -> LabStatus.CONFIRMED
                    null -> LabStatus.NOT_TESTED
                }
                VerdictBanner(
                    status = status,
                    headline = when (desktopMode) {
                        true -> "DeX is running"
                        false -> "DeX is not running"
                        null -> "DeX state unreadable"
                    },
                    detail = when (desktopMode) {
                        true -> "Samsung's desktop-mode flag is set. Watch the density line below: " +
                            "an external display at a different density than the phone is DeX; the " +
                            "same density is a mirror."
                        false -> "Samsung's desktop-mode flag reads disabled on this device right now."
                        null -> "Configuration.semDesktopModeEnabled could not be read. That means " +
                            "no claim can be made either way - not that DeX is off."
                    },
                )
            }

            item {
                LabCard("Live state") {
                    MonoBlock(
                        "desktop mode : " + (desktopMode?.toString() ?: "unreadable") + "\n" +
                            "ui mode type : " + uiMode + "\n" +
                            "displays     : " + displays.size + "\n" +
                            displays.joinToString("\n") { "  " + it.oneLine() },
                    )
                    Gap()
                    Text(
                        "Refreshed every second while this screen is open. Every change is " +
                            "timestamped into the session log.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = LabColors.TextDim,
                    )
                }
            }

            item {
                LabCard("Enter DeX now") {
                    Text(
                        "ENTER DEX ON THE HEAD UNIT",
                        style = MaterialTheme.typography.titleLarge,
                        color = LabColors.Accent,
                    )
                    Gap()
                    Text(
                        "1. Connect the phone to the Mirai with Smart View first.\n" +
                            "2. Open the quick panel and choose DeX, or use the Smart View tile's " +
                            "DeX option if the sink advertises it.\n" +
                            "3. Leave this screen open while you do it - the transition is what is " +
                            "being measured.\n\n" +
                            "MiraiCast Lab cannot start DeX. No third-party API exists for it, and " +
                            "this build does not call private ones.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Gap()
                    intents.filter {
                        it.key == "cast_settings" || it.key == "display_settings" ||
                            it.key == "smart_mirroring_launcher"
                    }.forEach { fact ->
                        LabButton(
                            text = "OPEN: " + fact.label.uppercase(),
                            onClick = { launchMessage = launchIntentFact(context, fact) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = fact.intent != null,
                        )
                        Gap(6)
                    }
                    if (launchMessage != null) {
                        Text(
                            launchMessage ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = LabColors.TextDim,
                        )
                    }
                }
            }

            if (transitions.isNotEmpty()) {
                item { LabCard("Transitions seen on this screen (" + transitions.size + ")") { Gap(0) } }
                items(transitions) { line ->
                    Text(
                        line,
                        style = LabMono,
                        color = LabColors.Observed,
                        modifier = Modifier.padding(horizontal = 26.dp, vertical = 2.dp),
                    )
                }
            }

            item {
                LabCard("Phone-side findings (" + measured.size + ")") {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        LabButton(
                            text = if (probing) "PROBING..." else "RE-RUN DEX PROBE",
                            onClick = { runProbe() },
                            modifier = Modifier.weight(1f),
                            enabled = !probing,
                        )
                    }
                }
            }
            items(measured) { o ->
                Box(Modifier.padding(horizontal = 26.dp)) { ObservationRow(o) }
            }

            item {
                LabCard("Still needs the vehicle (" + pending.size + ")") {
                    Text(
                        "These are the spec 2.3 questions that no amount of phone-side probing can " +
                            "answer. They are NOT_TESTED, which is a statement about the test " +
                            "conditions and never about the capability. Do not let them be read as " +
                            "'DeX does not work with the Mirai'.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
            items(pending) { o ->
                Box(Modifier.padding(horizontal = 26.dp)) { ObservationRow(o) }
            }

            item {
                LabCard("Record what you saw") {
                    Text(
                        "The picker's own wording is evidence this app cannot capture. If the DeX " +
                            "option was offered for the Mirai, or greyed out, or absent, record it " +
                            "as a marker so the report carries it.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Gap()
                    Column {
                        listOf(
                            "DeX WAS offered for the Mirai",
                            "DeX was NOT offered for the Mirai",
                            "DeX offered but connection failed",
                            "Mirai listed as Miracast sink only",
                        ).forEach { marker ->
                            BigActionButton(
                                text = marker.uppercase(),
                                onClick = {
                                    SessionLogger.marker(marker, LabCategory.DEX)
                                },
                                subtitle = "recorded as a tester observation",
                            )
                        }
                    }
                }
            }

            item { Gap(24) }
        }
    }
}
