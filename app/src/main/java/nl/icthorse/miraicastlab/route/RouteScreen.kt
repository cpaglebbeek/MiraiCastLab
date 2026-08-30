package nl.icthorse.miraicastlab.route

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import nl.icthorse.miraicastlab.core.Experiment
import nl.icthorse.miraicastlab.core.LabCategory
import nl.icthorse.miraicastlab.core.LabStatus
import nl.icthorse.miraicastlab.core.Outcome
import nl.icthorse.miraicastlab.core.Route
import nl.icthorse.miraicastlab.core.SessionLogger
import nl.icthorse.miraicastlab.ui.BigActionButton
import nl.icthorse.miraicastlab.ui.Gap
import nl.icthorse.miraicastlab.ui.LabButton
import nl.icthorse.miraicastlab.ui.LabCard
import nl.icthorse.miraicastlab.ui.LabColors
import nl.icthorse.miraicastlab.ui.LabMono
import nl.icthorse.miraicastlab.ui.LabScaffold
import nl.icthorse.miraicastlab.ui.MonoBlock
import nl.icthorse.miraicastlab.ui.StatusChip
import nl.icthorse.miraicastlab.ui.VerdictBanner

/**
 * The route experiments (research question v0.2).
 *
 * Four chains from phone to head unit that succeed and fail independently:
 *   A  app is itself the Miracast sender
 *   B  Samsung owns the session
 *   C  our own content onto the display Samsung established
 *   D  another app's content onto that display
 *
 * The screen's job is to make each one runnable in isolation and to show the hypothesis BEFORE the
 * outcome. An experiment whose conclusion is read first is not an experiment.
 */
@Composable
fun RouteScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()

    var target by remember { mutableStateOf(DisplayRole.preferredTarget(context)) }
    var roles by remember { mutableStateOf(DisplayRole.verdicts(context)) }
    var running by remember { mutableStateOf<String?>(null) }
    var outcomes by remember { mutableStateOf<List<Outcome>>(emptyList()) }
    var pkg by remember { mutableStateOf("") }
    var timeline by remember { mutableStateOf<String?>(null) }

    val presentation = remember { LabPresentationController() }

    // A Presentation that outlives this screen would sit on the display and silently grade the next
    // experiment. Tear it down with the composition, not only on the stop button.
    DisposableEffect(Unit) {
        onDispose { runCatching { presentation.stop() } }
    }

    // Keep the display picture current: the whole point is that a session may start while we watch.
    LaunchedEffect(Unit) {
        while (true) {
            roles = DisplayRole.verdicts(context)
            target = DisplayRole.preferredTarget(context)
            kotlinx.coroutines.delay(2000)
        }
    }

    fun record(list: List<Outcome>) {
        outcomes = (outcomes.filterNot { o -> list.any { it.experimentId == o.experimentId } } + list)
            .sortedBy { it.experimentId }
        list.forEach { SessionLogger.log(it.toObservation(LabCategory.DISPLAY)) }
    }

    LabScaffold(
        title = "Routes A-D",
        subtitle = "can an app reach the display Samsung established?",
        onBack = onBack,
    ) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 40.dp),
        ) {
            item {
                val t = target
                if (t == null) {
                    VerdictBanner(
                        LabStatus.NOT_TESTED,
                        "No external display",
                        "Routes C and D need a display other than the built-in panel. Start Smart " +
                            "View or Wireless DeX by hand; this screen re-checks every 2 seconds.",
                    )
                } else {
                    VerdictBanner(
                        t.status,
                        "Target: " + t.summary,
                        t.explain(),
                    )
                }
            }

            item {
                LabCard("Displays and their role") {
                    Text(
                        "Android never names the transport. A remote verdict is INFERRED at best - " +
                            "an HDMI dongle, a Chromecast, wired DeX or another app's VirtualDisplay " +
                            "produce the same signals.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = LabColors.TextDim,
                    )
                    Gap()
                    roles.forEach { v ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(v.summary, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    (if (v.flags.isEmpty()) "no flags" else v.flags.joinToString(" ")) +
                                        (if (v.presentationCategory) "  [presentation category]" else ""),
                                    style = LabMono,
                                    color = LabColors.TextDim,
                                )
                            }
                            StatusChip(v.status)
                        }
                    }
                }
            }

            if (running != null) {
                item {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("running " + running, style = LabMono, color = LabColors.TextDim)
                    }
                }
            }

            // ---- one card per route, hypothesis visible before any result -------------------
            items(Route.entries.toList()) { route ->
                RouteCard(
                    route = route,
                    experiments = experimentsFor(route),
                    outcomes = outcomes,
                    enabled = running == null,
                    needsDisplay = route == Route.C || route == Route.D,
                    hasDisplay = target != null,
                    onRun = {
                        scope.launch {
                            running = route.code
                            val id = target?.displayId ?: 0
                            val res = when (route) {
                                Route.A -> RouteA.run(context)
                                Route.B -> RouteB.run(context)
                                Route.C -> RouteC.run(context, id)
                                Route.D -> RouteD.run(context, id, pkg.ifBlank { null })
                            }
                            record(res)
                            running = null
                        }
                    },
                )
            }

            // ---- route B timeline instrument ------------------------------------------------
            item {
                LabCard("B4 - which signal flips first") {
                    Text(
                        "Start this, then connect Smart View or Wireless DeX by hand inside the " +
                            "window. Every transition is logged with its offset, so the ORDER " +
                            "survives even if the app is killed.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = LabColors.TextDim,
                    )
                    Gap()
                    LabButton("RUN 60 s TIMELINE", {
                        scope.launch {
                            running = "B4 timeline"
                            val r = RouteB.timeline(context, 60, 1000)
                            timeline = r.summary() + "\n\n" +
                                (r.firstSignalToFlip?.let { "first signal to flip: " + it } ?: "no session signal changed") +
                                "\n\n" + r.transitions.joinToString("\n") { it.toString() }
                            running = null
                        }
                    }, enabled = running == null)
                    timeline?.let { Gap(); MonoBlock(it) }
                }
            }

            // ---- route D target package ------------------------------------------------------
            item {
                LabCard("D - which app to launch") {
                    Text(
                        "Leave empty to let D1 pick the first launchable package it can see. Package " +
                            "visibility is filtered on Android 11+, so a short list is an artefact of " +
                            "what this app may see, not a claim about what is installed.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = LabColors.TextDim,
                    )
                    Gap()
                    OutlinedTextField(
                        value = pkg,
                        onValueChange = { pkg = it },
                        label = { Text("package name (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Gap()
                    Text("What did you actually SEE?", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "The API result and your eyes can disagree. If they do, that disagreement is " +
                            "the finding - it is recorded, not resolved by picking one.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = LabColors.TextDim,
                    )
                    Gap()
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        RouteD.Sighting.entries.take(3).forEach { s ->
                            LabButton(s.name.replace('_', ' '), {
                                RouteD.recordTesterVerdict("D2", s)
                            }, Modifier.weight(1f))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        RouteD.Sighting.entries.drop(3).forEach { s ->
                            LabButton(s.name.replace('_', ' '), {
                                RouteD.recordTesterVerdict("D2", s)
                            }, Modifier.weight(1f))
                        }
                    }
                }
            }

            // ---- Presentation controller (C2 by hand) -----------------------------------------
            item {
                LabCard("C2 - Presentation, by hand") {
                    Text(
                        "android.app.Presentation is a different mechanism from setLaunchDisplayId " +
                            "and can succeed or fail independently. Run it on its own so the two " +
                            "results stay separable.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = LabColors.TextDim,
                    )
                    Gap()
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LabButton("SHOW PRESENTATION", {
                            val a = activity
                            val id = target?.displayId
                            if (a == null || id == null) {
                                SessionLogger.log(
                                    LabCategory.DISPLAY, "route.c2.manual_blocked", LabStatus.NOT_TESTED,
                                    mapOf("reason" to if (a == null) "no Activity context" else "no external display"),
                                )
                            } else {
                                record(listOf(presentation.start(a, id)))
                            }
                        }, Modifier.weight(1f), enabled = target != null)
                        LabButton("DISMISS", { presentation.stop() }, Modifier.weight(1f))
                    }
                }
            }

            // ---- privilege tiers ---------------------------------------------------------------
            item {
                LabCard("Privilege tiers - what each level would add") {
                    Text(
                        "DOCUMENTED rows are not evidence about this device. Only MEASURED rows are.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = LabColors.Inferred,
                    )
                    Gap()
                    PrivilegeTiers.byTier().forEach { (tier, rows) ->
                        Text(tier.label + "  (" + rows.size + ")", style = MaterialTheme.typography.titleLarge)
                        Text(tier.note, style = MaterialTheme.typography.bodyMedium, color = LabColors.TextDim)
                        rows.forEach { r ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(r.capability, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        (if (r.measured) "MEASURED  " else "DOCUMENTED  ") + r.mechanism,
                                        style = LabMono,
                                        color = if (r.measured) LabColors.Observed else LabColors.TextDim,
                                    )
                                }
                                StatusChip(r.status)
                            }
                        }
                        Gap(12)
                    }
                }
            }

            item {
                BigActionButton(
                    "RUN ALL FOUR ROUTES",
                    {
                        scope.launch {
                            val id = target?.displayId ?: 0
                            running = "A"; record(RouteA.run(context))
                            running = "B"; record(RouteB.run(context))
                            running = "C"; record(RouteC.run(context, id))
                            running = "D"; record(RouteD.run(context, id, pkg.ifBlank { null }))
                            running = null
                        }
                    },
                    Modifier.padding(horizontal = 12.dp),
                    enabled = running == null,
                    subtitle = "A and B need no display; C and D do",
                )
            }
        }
    }
}

private fun experimentsFor(route: Route): List<Experiment> = when (route) {
    Route.A -> RouteA.experiments
    Route.B -> RouteB.experiments
    Route.C -> RouteC.experiments
    Route.D -> RouteD.experiments
}

/**
 * One route: its chain, its experiments with their hypotheses, and any outcomes so far.
 *
 * The hypothesis is rendered whether or not an outcome exists, and above it. That ordering is the
 * whole discipline: it is what stops the conclusion from being written after the fact.
 */
@Composable
private fun RouteCard(
    route: Route,
    experiments: List<Experiment>,
    outcomes: List<Outcome>,
    enabled: Boolean,
    needsDisplay: Boolean,
    hasDisplay: Boolean,
    onRun: () -> Unit,
) {
    LabCard(route.code + " - " + route.label) {
        Text(route.chain, style = LabMono, color = LabColors.Accent)
        Gap()
        experiments.forEach { e ->
            val o = outcomes.firstOrNull { it.experimentId == e.id }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(e.id + "  " + e.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                StatusChip(o?.status ?: LabStatus.NOT_TESTED)
            }
            Text("hypothesis: " + e.hypothesis, style = MaterialTheme.typography.bodyMedium, color = LabColors.TextDim)
            Text("method: " + e.method, style = LabMono, color = LabColors.TextDim)
            if (o != null) {
                Gap(4)
                Text("observed: " + o.observation, style = MaterialTheme.typography.bodyMedium)
                Text("conclusion: " + o.conclusion, style = MaterialTheme.typography.bodyMedium, color = LabColors.Observed)
                o.nextStep?.let { Text("next: " + it, style = MaterialTheme.typography.bodyMedium, color = LabColors.Inferred) }
            }
            Gap(10)
        }
        if (needsDisplay && !hasDisplay) {
            Text(
                "Needs an external display. Without one this route stays NOT_TESTED - which is not " +
                    "the same as saying it does not work.",
                style = MaterialTheme.typography.bodyMedium,
                color = LabColors.NotTested,
            )
            Gap()
        }
        LabButton("RUN ROUTE " + route.code, onRun, enabled = enabled && (!needsDisplay || hasDisplay))
    }
}
