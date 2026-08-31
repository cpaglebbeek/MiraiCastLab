package nl.icthorse.miraicastlab.dex

import android.content.Intent
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import nl.icthorse.miraicastlab.core.LabCategory
import nl.icthorse.miraicastlab.core.LabStatus
import nl.icthorse.miraicastlab.core.Outcome
import nl.icthorse.miraicastlab.core.SessionLogger
import nl.icthorse.miraicastlab.route.DisplayRole
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
 * Driving a DeX desktop from the app.
 *
 * The screen is ordered by what actually works, which is the opposite of the order the question was
 * asked in. The Intent buttons come first because they reach the goal today at ordinary-app tier;
 * the emulated keyboard and mouse come second because they are a measurement whose headline result
 * is a negative.
 *
 * That ordering is deliberate. A tester who opens this in a car should hit the working thing first.
 */
@Composable
fun DexControlScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var target by remember { mutableStateOf(DisplayRole.preferredTarget(context)) }
    var outcomes by remember { mutableStateOf<List<Outcome>>(emptyList()) }
    var attempts by remember { mutableStateOf<List<InputAttempt>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var appName by remember { mutableStateOf("YouTube") }
    var busy by remember { mutableStateOf<String?>(null) }

    val service by LabAccessibilityService.instance.collectAsState()
    val hidState by HidKeyboard.state().collectAsState()
    // Armed state lives in the service, not here: a screen rotation must not silently re-arm it.
    val armed by LabAccessibilityService.armed.collectAsState()

    // The display picture can change while the tester is on this screen - that is the whole point.
    LaunchedEffect(Unit) {
        while (true) {
            target = DisplayRole.preferredTarget(context)
            kotlinx.coroutines.delay(2000)
        }
    }

    val displayId: Int? = target?.displayId

    fun add(o: Outcome) { outcomes = (outcomes + o).takeLast(40) }
    fun addAll(l: List<Outcome>) { outcomes = (outcomes + l).takeLast(40) }
    fun addAttempts(l: List<InputAttempt>) { attempts = (attempts + l).takeLast(60) }

    /** Routes need to know where to aim, or every gesture silently lands on the phone screen. */
    fun routes() = InputRoutes.all(context, displayId)

    LabScaffold(
        title = "DeX control",
        subtitle = "keyboard, mouse, hotkeys and the Intent route",
        onBack = onBack,
    ) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {

            item {
                val t = target
                if (t == null) {
                    VerdictBanner(
                        LabStatus.NOT_TESTED,
                        "No external display",
                        "Everything below still runs, but it targets the phone screen. Start Smart " +
                            "View or Wireless DeX by hand to aim at the car instead.",
                    )
                } else {
                    VerdictBanner(t.status, "Target: " + t.summary, t.explain())
                }
            }

            // ---------------------------------------------------------------- what works today
            item {
                LabCard("YouTube on the chosen display") {
                    Text(
                        "No input emulation involved. An Intent plus setLaunchDisplayId reaches the " +
                            "goal at ordinary-app tier - which is why this is the first card and not " +
                            "the last.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = LabColors.TextDim,
                    )
                    Gap()
                    BigActionButton("OPEN YOUTUBE", {
                        scope.launch {
                            busy = "opening YouTube"
                            add(YouTubeActions.openYouTube(context, displayId))
                            busy = null
                        }
                    }, enabled = busy == null, subtitle = displayLabel(displayId))
                    Gap(6)
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("search on YouTube") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Gap(6)
                    BigActionButton("SEARCH ON YOUTUBE", {
                        scope.launch {
                            busy = "searching"
                            add(YouTubeActions.searchYouTube(context, query, displayId))
                            busy = null
                        }
                    }, enabled = busy == null && query.isNotBlank(), subtitle = "in-app search, URL fallback")
                    Gap(6)
                    BigActionButton("OPEN, THEN SEARCH", {
                        scope.launch {
                            busy = "open then search"
                            addAll(YouTubeActions.openAndSearch(context, query, displayId))
                            busy = null
                        }
                    }, enabled = busy == null && query.isNotBlank(),
                        subtitle = "two steps, reported separately")
                    Gap(8)
                    Text("one-tap queries", style = MaterialTheme.typography.bodyMedium, color = LabColors.TextDim)
                    Gap(4)
                    YouTubeActions.suggestedQueries.chunked(2).forEach { pair ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            pair.forEach { q ->
                                LabButton(q, { query = q }, Modifier.weight(1f))
                            }
                            if (pair.size == 1) Box(Modifier.weight(1f))
                        }
                    }
                }
            }

            item {
                LabCard("Launch any visible app on that display") {
                    val apps = remember { AppLauncher.installedApps(context) }
                    Text(
                        apps.size.toString() + " launchable packages are visible to this app. " +
                            "Package visibility is filtered on Android 11+ and we hold no " +
                            "QUERY_ALL_PACKAGES, so a short list says what we may SEE, not what is " +
                            "installed.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = LabColors.TextDim,
                    )
                    Gap()
                    apps.take(12).forEach { app ->
                        LabButton(app.label, {
                            scope.launch {
                                busy = "launching " + app.label
                                add(AppLauncher.launch(context, app.packageName, displayId))
                                busy = null
                            }
                        }, Modifier.fillMaxWidth())
                    }
                }
            }

            // ------------------------------------------------- the measurement, and its headline
            item {
                LabCard("Emulated keyboard and mouse - the measurement") {
                    Text(
                        "A DeX desktop IS driven by an external keyboard and mouse, and it does have " +
                            "the hotkey flow: press Meta, type a name, press Enter. That is not in " +
                            "question. What is in question is whether THIS app can be that keyboard.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Gap()
                    Text(
                        "Expect a negative at ordinary-app tier, and read it as the finding rather " +
                            "than as a defect: no accessibility API originates key events, so step 1 " +
                            "of the flow has no unprivileged route at all. Step 2 needs to type into " +
                            "another app's field, which needs window-content access this app " +
                            "deliberately does not request.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = LabColors.Inferred,
                    )
                }
            }

            item {
                LabCard("Routes and what each can do") {
                    routes().forEach { r ->
                        val (ok, why) = r.availability()
                        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(r.routeName, style = MaterialTheme.typography.bodyLarge)
                                Text(r.tier.label + " - " + why, style = LabMono, color = LabColors.TextDim)
                            }
                            StatusChip(if (ok) LabStatus.OBSERVED else LabStatus.NOT_TESTED)
                        }
                    }
                }
            }

            item {
                LabCard("Accessibility - the only unprivileged cross-app route") {
                    val enabled = LabAccessibilityService.isEnabledInSettings(context)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (enabled) "enabled in Settings" else "not enabled in Settings",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                if (service != null) "service is bound" else "service not bound",
                                style = LabMono, color = LabColors.TextDim,
                            )
                        }
                        StatusChip(if (service != null) LabStatus.OBSERVED else LabStatus.NOT_TESTED)
                    }
                    Gap()
                    Text(
                        "It cannot read your screen and cannot see your keystrokes - the config " +
                            "declares neither. The price is that it can tap but cannot type. Turn it " +
                            "off in Settings when you are done.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = LabColors.TextDim,
                    )
                    Gap()
                    Text(LabAccessibilityService.SETTINGS_PATH, style = LabMono, color = LabColors.TextDim)
                    Gap()
                    LabButton("OPEN ACCESSIBILITY SETTINGS", {
                        runCatching {
                            context.startActivity(
                                LabAccessibilityService.settingsIntent()
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }.onFailure {
                            SessionLogger.log(
                                LabCategory.DEX, "dex.accessibility.settings_failed",
                                LabStatus.ERROR, mapOf("error" to it.toString()),
                            )
                        }
                    }, Modifier.fillMaxWidth())
                    Gap()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = armed,
                            onCheckedChange = {
                                // setArmed logs the transition itself, including the reason - "when
                                // was it armed" is evidence for every attempt that follows.
                                LabAccessibilityService.setArmed(it, "tester toggled it on the DeX control screen")
                            },
                            enabled = service != null,
                        )
                        Text(
                            "  arm the service (second lock, off by default)",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }

            // ---------------------------------------------------------------- the hotkey flow
            item {
                LabCard("Hotkey app-launch flow") {
                    OutlinedTextField(
                        value = appName,
                        onValueChange = { appName = it },
                        label = { Text("app name to type") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Gap(6)
                    val flow = HotkeyLaunchFlow(appName)
                    Text(flow.description, style = LabMono, color = LabColors.Accent)
                    Gap()
                    routes().forEach { r ->
                        LabButton("RUN FLOW VIA " + r.routeName, {
                            scope.launch {
                                busy = "flow via " + r.routeName
                                addAttempts(InputRoutes.runFlow(context, flow, r))
                                busy = null
                            }
                        }, Modifier.fillMaxWidth(), enabled = busy == null)
                    }
                }
            }

            item {
                LabCard("Hotkeys, individually") {
                    Text(
                        "Each key is sent on its own so a refusal is attributable to that key rather " +
                            "than to the flow.",
                        style = MaterialTheme.typography.bodyMedium, color = LabColors.TextDim,
                    )
                    Gap()
                    DexHotkey.entries.forEach { hk ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            LabButton(hk.label, {
                                scope.launch {
                                    val r = InputRoutes.best(context, displayId) ?: routes().first()
                                    addAttempts(listOf(r.send(hk.asAction())))
                                }
                            }, Modifier.fillMaxWidth(), enabled = busy == null)
                            Text(hk.effect, style = MaterialTheme.typography.bodyMedium, color = LabColors.TextDim)
                        }
                    }
                }
            }

            // ---------------------------------------------------------------- the trackpad
            item {
                LabCard("Trackpad - tap and drag") {
                    Text(
                        "Gestures are the one thing an accessibility service really can dispatch. " +
                            "Coordinates are taken on the TARGET display, so a tap here is aimed at " +
                            displayLabel(displayId) + ".",
                        style = MaterialTheme.typography.bodyMedium, color = LabColors.TextDim,
                    )
                    Gap()
                    TrackpadSurface { action ->
                        scope.launch {
                            val r = InputRoutes.best(context, displayId) ?: routes().first()
                            addAttempts(listOf(r.send(action)))
                        }
                    }
                }
            }

            // ---------------------------------------------------------------- Bluetooth HID
            item {
                LabCard("Bluetooth HID keyboard / mouse") {
                    Text(
                        "The only public API with which this app really emits HID reports. It drives " +
                            "an already paired HOST - a PC, a tablet, possibly the Mirai if it accepts " +
                            "a Bluetooth keyboard. It cannot drive the DeX desktop on this same phone: " +
                            "a phone is not a HID host to itself. That is the finding, not a shortfall.",
                        style = MaterialTheme.typography.bodyMedium, color = LabColors.TextDim,
                    )
                    Gap()
                    Text("state: " + hidState, style = LabMono)
                    Gap()
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LabButton("REGISTER", {
                            scope.launch { SessionLogger.log(HidKeyboard.register(context)) }
                        }, Modifier.weight(1f))
                        LabButton("UNREGISTER", { HidKeyboard.unregister() }, Modifier.weight(1f))
                    }
                    Gap()
                    val hosts = remember(hidState) { runCatching { HidKeyboard.bondedHosts(context) }.getOrDefault(emptyList()) }
                    if (hosts.isEmpty()) {
                        Text(
                            "No bonded Bluetooth devices visible. Pair a host in Android Settings " +
                                "first - this app never initiates pairing.",
                            style = MaterialTheme.typography.bodyMedium, color = LabColors.NotTested,
                        )
                    } else {
                        hosts.forEach { (name, addr) ->
                            LabButton("CONNECT  " + name, {
                                scope.launch { SessionLogger.log(HidKeyboard.connect(addr)) }
                            }, Modifier.fillMaxWidth())
                        }
                    }
                }
            }

            // ---------------------------------------------------------------- results
            if (attempts.isNotEmpty()) {
                item {
                    LabCard("Input attempts") {
                        Text(
                            "Delivery is not effect. Every row below says at most that the platform " +
                                "accepted an event. Whether anything changed on a screen is a claim " +
                                "only you can make.",
                            style = MaterialTheme.typography.bodyMedium, color = LabColors.Inferred,
                        )
                        Gap()
                        attempts.reversed().forEach { a ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(a.action, style = MaterialTheme.typography.bodyLarge)
                                    Text(a.routeName + "  " + a.elapsedMs + "ms", style = LabMono, color = LabColors.TextDim)
                                    Text(a.observation, style = MaterialTheme.typography.bodyMedium)
                                    Text(a.conclusion, style = MaterialTheme.typography.bodyMedium, color = LabColors.Observed)
                                }
                                StatusChip(a.status)
                            }
                        }
                    }
                }
            }

            if (outcomes.isNotEmpty()) {
                item {
                    LabCard("Launch outcomes") {
                        outcomes.reversed().forEach { o ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(o.experimentId, style = LabMono, color = LabColors.TextDim)
                                    Text(o.observation, style = MaterialTheme.typography.bodyMedium)
                                    Text(o.conclusion, style = MaterialTheme.typography.bodyMedium, color = LabColors.Observed)
                                }
                                StatusChip(o.status)
                            }
                        }
                        Gap()
                        Text("What did you actually SEE on the target screen?",
                            style = MaterialTheme.typography.titleLarge)
                        Gap(4)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            AppLauncher.Sighting.entries.take(3).forEach { s ->
                                LabButton(s.name.replace('_', ' '), {
                                    outcomes.lastOrNull()?.let {
                                        AppLauncher.recordTesterVerdict(it.experimentId, s)
                                    }
                                }, Modifier.weight(1f))
                            }
                        }
                    }
                }
            }

            busy?.let { b -> item { Text("  " + b + "...", style = LabMono, color = LabColors.Inferred) } }
        }
    }
}

private fun displayLabel(displayId: Int?): String =
    if (displayId == null) "the phone screen (no external display)" else "display " + displayId

/**
 * A drag surface that turns touches into [InputAction.Tap] and [InputAction.Swipe].
 *
 * Coordinates are passed through unscaled on purpose. Mapping phone coordinates onto a DeX
 * resolution would be a guess dressed as a measurement; the route reports what it was given, and
 * the tester sees where it landed.
 */
@Composable
private fun TrackpadSurface(onAction: (InputAction) -> Unit) {
    var last by remember { mutableStateOf("no gesture yet") }
    Box(
        Modifier
            .fillMaxWidth()
            .height(180.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(LabColors.Ink)
            .border(1.dp, LabColors.Line, RoundedCornerShape(12.dp))
            .pointerInput(Unit) {
                detectTapGestures { p ->
                    last = "tap " + p.x.toInt() + "," + p.y.toInt()
                    onAction(InputAction.Tap(p.x, p.y))
                }
            }
            .pointerInput(Unit) {
                var startX = 0f
                var startY = 0f
                detectDragGestures(
                    onDragStart = { startX = it.x; startY = it.y },
                    onDragEnd = { },
                ) { change, _ ->
                    val p = change.position
                    last = "drag " + startX.toInt() + "," + startY.toInt() + " -> " + p.x.toInt() + "," + p.y.toInt()
                    onAction(InputAction.Swipe(startX, startY, p.x, p.y))
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(last, style = LabMono, color = LabColors.TextDim)
    }
}
