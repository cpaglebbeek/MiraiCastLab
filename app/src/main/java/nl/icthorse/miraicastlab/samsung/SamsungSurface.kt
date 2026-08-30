package nl.icthorse.miraicastlab.samsung

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.media.MediaRouter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Display
import nl.icthorse.miraicastlab.core.LabCategory
import nl.icthorse.miraicastlab.core.LabStatus
import nl.icthorse.miraicastlab.core.Observation
import nl.icthorse.miraicastlab.core.SessionLogger
import java.net.NetworkInterface
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.Collections
import java.util.Locale

/**
 * The one place that knows anything Samsung-proprietary or mirroring-specific.
 *
 * Isolated deliberately (spec section 17): the generic display, audio, network and input modules
 * must stay free of Samsung assumptions, and every hidden-surface experiment must be findable in a
 * single file so its risk is auditable.
 *
 * Nothing here establishes a Miracast session. Android exposes no third-party API that can. Every
 * function below either *observes* platform state or *hands the tester off* to a system UI, and the
 * grading of each finding says which of the two produced it.
 */

// ---------------------------------------------------------------- package visibility

/**
 * Below this many visible packages we assume Android 11 package-visibility filtering is active,
 * because a real handset carries hundreds. The app declares no <queries> element and no
 * QUERY_ALL_PACKAGES, so on API 30+ an absent package is normally indistinguishable from a
 * filtered one - and that ambiguity must be reported, never resolved into UNSUPPORTED.
 */
private const val VISIBILITY_FILTER_THRESHOLD = 40

/** System packages worth asking about, each with the reason it matters to this research. */
val WATCHED_PACKAGES: List<Pair<String, String>> = listOf(
    "com.samsung.android.smartmirroring" to "Smart View / smart mirroring UI",
    "com.samsung.android.app.mirrorlink" to "MirrorLink, the legacy automotive projection stack",
    "com.samsung.android.mdx" to "Multi Device Experience / Link to Windows",
    "com.samsung.android.dressroom" to "Samsung DeX-related component",
    "com.sec.android.app.desktoplauncher" to "Samsung DeX desktop launcher",
    "com.samsung.desktopsystemui" to "Samsung DeX system UI",
    "com.google.android.gms" to "Google Play services, which hosts Cast",
)

/** Packages that, if present, indicate the DeX stack exists on this build. */
val DEX_PACKAGES: List<Pair<String, String>> = listOf(
    "com.sec.android.app.desktoplauncher" to "Samsung DeX desktop launcher",
    "com.samsung.desktopsystemui" to "Samsung DeX system UI",
    "com.samsung.android.dressroom" to "Samsung DeX-related component",
)

/** What we could learn about one package, including *why* we could not learn it. */
data class PackageFact(
    val pkg: String,
    val label: String,
    val present: Boolean,
    val versionName: String? = null,
    val versionCode: Long? = null,
    val enabled: Boolean? = null,
    val failure: String? = null,
) {
    /** Stable short key segment, e.g. "smartmirroring". */
    val shortKey: String get() = pkg.substringAfterLast('.')

    fun describe(): String = when {
        present -> "present" +
            (versionName?.let { " v" + it } ?: "") +
            (versionCode?.let { " (" + it + ")" } ?: "") +
            (enabled?.let { if (it) " enabled" else " DISABLED" } ?: "")
        else -> "not visible to this app" + (failure?.let { " [" + it + "]" } ?: "")
    }
}

/**
 * How many packages this app is allowed to see at all.
 *
 * This is the control measurement for every package question below: a tiny number proves that any
 * "package absent" answer is really "package hidden".
 */
@Suppress("DEPRECATION")
fun visiblePackageCount(context: Context): Int? = try {
    context.packageManager.getInstalledPackages(0).size
} catch (t: Throwable) {
    null
}

/** True when a "not found" answer cannot be distinguished from Android 11 visibility filtering. */
fun packageAbsenceIsAmbiguous(visibleCount: Int?): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
        (visibleCount == null || visibleCount < VISIBILITY_FILTER_THRESHOLD)

@Suppress("DEPRECATION")
fun readPackage(context: Context, pkg: String, label: String): PackageFact = try {
    val info = context.packageManager.getPackageInfo(pkg, 0)
    val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        info.longVersionCode
    } else {
        info.versionCode.toLong()
    }
    PackageFact(
        pkg = pkg,
        label = label,
        present = true,
        versionName = info.versionName,
        versionCode = code,
        enabled = info.applicationInfo?.enabled,
    )
} catch (e: PackageManager.NameNotFoundException) {
    PackageFact(pkg, label, present = false, failure = "NameNotFoundException")
} catch (t: Throwable) {
    PackageFact(
        pkg, label, present = false,
        failure = t::class.java.simpleName + ": " + (t.message ?: "no message"),
    )
}

/**
 * Grades a package lookup honestly.
 *
 * present                       -> CONFIRMED, the platform handed us a version.
 * absent while filtering is on  -> NOT_TESTED, we were never allowed to ask.
 * absent while filtering is off -> UNSUPPORTED, the platform genuinely answered "no".
 */
fun packageObservation(
    fact: PackageFact,
    prefix: String,
    category: LabCategory,
    ambiguous: Boolean,
): Observation = when {
    fact.present -> Observation.confirmed(
        prefix + "." + fact.shortKey, fact.describe(), category, fact.label,
    )

    ambiguous -> Observation.notTested(
        prefix + "." + fact.shortKey, category,
        fact.label + " - " + fact.describe() + ". Android package-visibility filtering is active " +
            "for this app, so absence here is NOT evidence that the package is missing.",
    )

    else -> Observation.unsupported(
        prefix + "." + fact.shortKey, category,
        fact.label + " - the package manager reports no such package and visibility filtering " +
            "does not appear to be masking it.",
    )
}

/** System feature names containing any of [keywords]. Feature queries are not visibility-filtered. */
fun systemFeaturesMatching(context: Context, keywords: List<String>): List<String> = try {
    context.packageManager.systemAvailableFeatures
        .mapNotNull { it.name }
        .filter { name -> keywords.any { name.contains(it, ignoreCase = true) } }
        .sorted()
} catch (t: Throwable) {
    emptyList()
}

// ---------------------------------------------------------------- settings intents

const val ACTION_WIFI_DISPLAY_SETTINGS = "android.settings.WIFI_DISPLAY_SETTINGS"
const val ACTION_SAMSUNG_WFD_PICKER = "com.samsung.wfd.LAUNCH_WFD_PICKER"
const val PKG_SMART_MIRRORING = "com.samsung.android.smartmirroring"

/** One intent we asked the package manager about, and exactly what it answered. */
data class IntentFact(
    val key: String,
    val label: String,
    val describe: String,
    val intent: Intent?,
    val matches: List<String>,
    val defaultMatches: Int,
    val failure: String? = null,
) {
    /** True when at least one activity claims this intent. */
    val resolvable: Boolean get() = matches.isNotEmpty()

    /** True when the intent can also be started implicitly (it carries CATEGORY_DEFAULT). */
    val startable: Boolean get() = defaultMatches > 0 || (intent?.component != null && resolvable)

    fun answer(): String = when {
        intent == null -> "no intent could be built"
        matches.isEmpty() -> "no activity resolves this intent"
        else -> matches.joinToString(", ")
    }
}

@Suppress("DEPRECATION")
private fun resolveIntent(context: Context, key: String, label: String, intent: Intent?): IntentFact {
    if (intent == null) {
        return IntentFact(key, label, "-", null, emptyList(), 0, "no intent available")
    }
    val describe = (intent.action ?: intent.component?.flattenToShortString() ?: "?") +
        (intent.`package`?.let { " @" + it } ?: "")
    return try {
        val pm = context.packageManager
        val any = pm.queryIntentActivities(intent, 0)
        val def = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        IntentFact(
            key = key,
            label = label,
            describe = describe,
            intent = intent,
            matches = any.mapNotNull { ri ->
                ri.activityInfo?.let { it.packageName + "/" + it.name }
            },
            defaultMatches = def.size,
        )
    } catch (t: Throwable) {
        IntentFact(
            key, label, describe, intent, emptyList(), 0,
            t::class.java.simpleName + ": " + (t.message ?: "no message"),
        )
    }
}

/**
 * Every legitimate route to a wireless-display picker, plus a control.
 *
 * The control matters: queryIntentActivities is itself subject to package-visibility filtering, so
 * a non-resolving cast intent means nothing until we know whether the Settings app is visible to
 * us at all. [ACTION_CONTROL_KEY] is that yardstick.
 */
const val ACTION_CONTROL_KEY = "settings_control"

fun castIntents(context: Context): List<IntentFact> {
    val launchSmartMirroring = try {
        context.packageManager.getLaunchIntentForPackage(PKG_SMART_MIRRORING)
    } catch (t: Throwable) {
        null
    }
    return listOf(
        resolveIntent(
            context, ACTION_CONTROL_KEY,
            "Android Settings root (control measurement)",
            Intent(Settings.ACTION_SETTINGS),
        ),
        resolveIntent(
            context, "cast_settings",
            "Android cast / wireless display picker",
            Intent(Settings.ACTION_CAST_SETTINGS),
        ),
        resolveIntent(
            context, "wifi_display_settings",
            "AOSP Wi-Fi Display settings panel",
            Intent(ACTION_WIFI_DISPLAY_SETTINGS),
        ),
        resolveIntent(
            context, "samsung_wfd_picker",
            "Samsung Wi-Fi Display picker",
            Intent(ACTION_SAMSUNG_WFD_PICKER),
        ),
        resolveIntent(
            context, "samsung_wfd_picker_scoped",
            "Samsung Wi-Fi Display picker, scoped to the Smart View package",
            Intent(ACTION_SAMSUNG_WFD_PICKER).setPackage(PKG_SMART_MIRRORING),
        ),
        resolveIntent(
            context, "smart_mirroring_launcher",
            "Smart View app launcher entry",
            launchSmartMirroring,
        ),
        resolveIntent(
            context, "display_settings",
            "Android display settings (fallback route for the tester)",
            Intent(Settings.ACTION_DISPLAY_SETTINGS),
        ),
    )
}

/**
 * Starts a settings intent on the tester's behalf.
 *
 * Note the asymmetry with [resolveIntent]: *starting* an implicit intent is not subject to package
 * visibility, so this can succeed for an intent that did not resolve. An ActivityNotFoundException
 * here is therefore stronger evidence of absence than an empty query result.
 */
fun launchIntentFact(context: Context, fact: IntentFact): String {
    val base = fact.intent ?: return "No intent available for " + fact.label + "."
    return try {
        context.startActivity(Intent(base).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        SessionLogger.log(
            LabCategory.SMART_VIEW, "settings_intent_launched", LabStatus.OBSERVED,
            mapOf("intent" to fact.describe, "label" to fact.label),
        )
        "Opened: " + fact.label + ". Return to MiraiCast Lab when Smart View is connected."
    } catch (t: Throwable) {
        SessionLogger.log(
            LabCategory.SMART_VIEW, "settings_intent_failed", LabStatus.UNSUPPORTED,
            mapOf(
                "intent" to fact.describe,
                "error" to t::class.java.simpleName,
                "message" to (t.message ?: "no message"),
            ),
        )
        "Could not open " + fact.label + ": " + t::class.java.simpleName +
            ". That failure is itself evidence this device has no such panel."
    }
}

// ---------------------------------------------------------------- displays

/** One display as this app can see it. Named to avoid confusion with the generic display module. */
data class SurfaceDisplayFact(
    val id: Int,
    val name: String,
    val width: Int,
    val height: Int,
    val densityDpi: Int,
    val refreshRate: Float,
    val presentation: Boolean,
    val secure: Boolean,
    val stateName: String,
) {
    fun oneLine(): String = "#" + id + " " + name + " " + width + "x" + height +
        " @" + densityDpi + "dpi " +
        String.format(Locale.US, "%.1f", refreshRate) + "Hz" +
        (if (presentation) " PRESENTATION" else "") +
        (if (secure) " SECURE" else "") +
        " " + stateName
}

private fun displayStateName(state: Int): String = when (state) {
    Display.STATE_OFF -> "OFF"
    Display.STATE_ON -> "ON"
    Display.STATE_DOZE -> "DOZE"
    Display.STATE_DOZE_SUSPEND -> "DOZE_SUSPEND"
    Display.STATE_VR -> "VR"
    Display.STATE_ON_SUSPEND -> "ON_SUSPEND"
    else -> "UNKNOWN(" + state + ")"
}

@Suppress("DEPRECATION")
private fun toFact(d: Display): SurfaceDisplayFact {
    // getRealMetrics is deprecated but is still the only per-Display metrics source that works for
    // a display this process is not currently hosting a window on.
    val m = DisplayMetrics()
    runCatching { d.getRealMetrics(m) }
    return SurfaceDisplayFact(
        id = d.displayId,
        name = runCatching { d.name }.getOrDefault("unnamed"),
        width = m.widthPixels,
        height = m.heightPixels,
        densityDpi = m.densityDpi,
        refreshRate = runCatching { d.refreshRate }.getOrDefault(0f),
        presentation = (d.flags and Display.FLAG_PRESENTATION) != 0,
        secure = (d.flags and Display.FLAG_SECURE) != 0,
        stateName = runCatching { displayStateName(d.state) }.getOrDefault("UNKNOWN"),
    )
}

fun readDisplayFacts(context: Context): List<SurfaceDisplayFact> = try {
    val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
    dm?.displays?.map { toFact(it) } ?: emptyList()
} catch (t: Throwable) {
    emptyList()
}

/** Displays Android itself considers suitable to present onto - the Miracast/DeX signal. */
fun readPresentationDisplays(context: Context): List<SurfaceDisplayFact> = try {
    val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
    dm?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)?.map { toFact(it) } ?: emptyList()
} catch (t: Throwable) {
    emptyList()
}

// ---------------------------------------------------------------- media router

data class RouteFact(
    val name: String?,
    val status: String?,
    val presentationDisplayId: Int?,
    val failure: String? = null,
) {
    fun describe(): String = when {
        failure != null -> "unavailable [" + failure + "]"
        name == null -> "no selected live-video route"
        else -> name + (status?.let { " (" + it + ")" } ?: "") +
            (presentationDisplayId?.let { " -> presentation display #" + it } ?: " -> no presentation display")
    }
}

/**
 * The currently selected LIVE_VIDEO route.
 *
 * When Smart View is mirroring, the framework normally selects a remote-display route whose
 * presentationDisplay is non-null. That is the strongest signal an unprivileged app gets, and it
 * still does not name the transport.
 */
fun readLiveVideoRoute(context: Context): RouteFact = try {
    val mr = context.getSystemService(Context.MEDIA_ROUTER_SERVICE) as? MediaRouter
    if (mr == null) {
        RouteFact(null, null, null, "MEDIA_ROUTER_SERVICE returned null")
    } else {
        val r = mr.getSelectedRoute(MediaRouter.ROUTE_TYPE_LIVE_VIDEO)
        RouteFact(
            name = r?.name?.toString(),
            status = r?.status?.toString(),
            presentationDisplayId = runCatching { r?.presentationDisplay?.displayId }.getOrNull(),
        )
    }
} catch (t: Throwable) {
    RouteFact(null, null, null, t::class.java.simpleName + ": " + (t.message ?: "no message"))
}

// ---------------------------------------------------------------- network interfaces

/** Names of up p2p* interfaces, plus the failure string if enumeration itself failed. */
fun p2pInterfacesUp(): Pair<List<String>, String?> = try {
    // getNetworkInterfaces() returns null - not an empty enumeration - when the platform can list
    // nothing, which happens on emulators and can happen transiently on a device. Passing that null
    // to Collections.list() throws an NPE whose message says nothing useful. Naming the condition
    // matters here more than elsewhere: a p2p* interface coming up is the strongest app-visible
    // Miracast signal, so "we could not enumerate" must never read as "no p2p interface".
    val e = NetworkInterface.getNetworkInterfaces()
        ?: return Pair(emptyList(), "NetworkInterface.getNetworkInterfaces() returned null")
    val all = Collections.list(e)
    val up = all.filter { ni ->
        ni.name.startsWith("p2p", ignoreCase = true) &&
            runCatching { ni.isUp }.getOrDefault(false)
    }.map { it.name }
    Pair(up, null)
} catch (t: Throwable) {
    Pair(emptyList(), t::class.java.simpleName + ": " + (t.message ?: "no message"))
}

private fun interfacesUp(): List<String> = try {
    Collections.list(NetworkInterface.getNetworkInterfaces() ?: Collections.emptyEnumeration())
        .filter { runCatching { it.isUp }.getOrDefault(false) }
        .map { it.name }
        .sorted()
} catch (t: Throwable) {
    emptyList()
}

// ---------------------------------------------------------------- combined mirroring signals

/**
 * The three independent signals an ordinary app can read that correlate with an active wireless
 * display session. None of them identifies the transport as Miracast; together they are the best
 * INFERRED verdict available without privileged access.
 */
data class MirroringSignals(
    val allDisplays: List<SurfaceDisplayFact>,
    val presentationDisplays: List<SurfaceDisplayFact>,
    val route: RouteFact,
    val p2pInterfaces: List<String>,
    val p2pFailure: String?,
) {
    val routeHasPresentationDisplay: Boolean get() = route.presentationDisplayId != null

    /** Signals that are actually present, phrased as evidence lines for the note field. */
    val positives: List<String>
        get() = buildList {
            if (presentationDisplays.isNotEmpty()) {
                add(
                    "presentation-category display present: " +
                        presentationDisplays.joinToString(" | ") { it.oneLine() },
                )
            }
            if (routeHasPresentationDisplay) {
                add("MediaRouter live-video route has a presentation display: " + route.describe())
            }
            if (p2pInterfaces.isNotEmpty()) {
                add("Wi-Fi Direct interface up: " + p2pInterfaces.joinToString("/"))
            }
        }

    val signalCount: Int get() = positives.size

    /** Purely descriptive; contains no capability claim. Safe to grade OBSERVED. */
    fun evidenceSummary(): String = "displays=" + allDisplays.size +
        "; presentation=" + presentationDisplays.size +
        "; live-video route=" + (route.name ?: "none") +
        "; route presentation display=" + (route.presentationDisplayId?.toString() ?: "none") +
        "; p2p interfaces=" + (if (p2pInterfaces.isEmpty()) "none" else p2pInterfaces.joinToString("/"))
}

fun readMirroringSignals(context: Context): MirroringSignals {
    val (p2p, p2pFail) = p2pInterfacesUp()
    return MirroringSignals(
        allDisplays = readDisplayFacts(context),
        presentationDisplays = readPresentationDisplays(context),
        route = readLiveVideoRoute(context),
        p2pInterfaces = p2p,
        p2pFailure = p2pFail,
    )
}

// ---------------------------------------------------------------- DeX reflection

/**
 * Result of reading Samsung's non-AOSP desktop-mode field off [Configuration].
 *
 * This is the one proprietary surface this module touches. It is read-only, guarded per call, and
 * lives here rather than in the generic display module precisely so its blast radius is one file.
 */
data class DexReflection(
    val fieldValue: Int? = null,
    val fieldFailure: String? = null,
    val constantValue: Int? = null,
    val constantFailure: String? = null,
) {
    /** null means we could not read the field at all, which is NOT_TESTED, not "DeX is off". */
    val desktopModeActive: Boolean?
        get() = when {
            fieldValue == null -> null
            constantValue != null -> fieldValue == constantValue
            else -> fieldValue != 0
        }
}

fun readDexReflection(context: Context): DexReflection {
    val cfg = context.resources.configuration
    var value: Int? = null
    var valueFailure: String? = null
    var constant: Int? = null
    var constantFailure: String? = null

    try {
        val f = Configuration::class.java.getDeclaredField("semDesktopModeEnabled")
        f.isAccessible = true
        value = f.getInt(cfg)
    } catch (t: Throwable) {
        valueFailure = t::class.java.simpleName + ": " + (t.message ?: "no message")
    }

    try {
        val f = Configuration::class.java.getDeclaredField("SEM_DESKTOP_MODE_ENABLED")
        f.isAccessible = true
        constant = f.getInt(null)
    } catch (t: Throwable) {
        constantFailure = t::class.java.simpleName + ": " + (t.message ?: "no message")
    }

    return DexReflection(value, valueFailure, constant, constantFailure)
}

/** Samsung's platform revision, exposed as Build.VERSION.SEM_PLATFORM_INT on One UI builds. */
fun readSemPlatformInt(): Pair<Int?, String?> = try {
    val f = Build.VERSION::class.java.getDeclaredField("SEM_PLATFORM_INT")
    f.isAccessible = true
    Pair(f.getInt(null), null)
} catch (t: Throwable) {
    Pair(null, t::class.java.simpleName + ": " + (t.message ?: "no message"))
}

/** DeX broadcast actions worth listening for. Neither is documented; both are cheap to watch. */
val DEX_BROADCAST_ACTIONS: List<Pair<String, String>> = listOf(
    "android.app.action.ENTER_KNOX_DESKTOP_MODE" to "Knox desktop-mode entry",
    "com.samsung.intent.action.DESKTOP_MODE_CHANGED" to "Samsung DeX mode change",
    "com.samsung.intent.action.EXIT_DESKTOP_MODE" to "Samsung DeX exit",
)

fun uiModeType(context: Context): Int =
    context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK

fun uiModeTypeName(value: Int): String = when (value) {
    Configuration.UI_MODE_TYPE_UNDEFINED -> "UNDEFINED"
    Configuration.UI_MODE_TYPE_NORMAL -> "NORMAL"
    Configuration.UI_MODE_TYPE_DESK -> "DESK"
    Configuration.UI_MODE_TYPE_CAR -> "CAR"
    Configuration.UI_MODE_TYPE_TELEVISION -> "TELEVISION"
    Configuration.UI_MODE_TYPE_APPLIANCE -> "APPLIANCE"
    Configuration.UI_MODE_TYPE_WATCH -> "WATCH"
    Configuration.UI_MODE_TYPE_VR_HEADSET -> "VR_HEADSET"
    else -> "UNKNOWN(" + value + ")"
}

// ---------------------------------------------------------------- audio route (snapshot only)

/**
 * Output device type names, written as literals rather than AudioDeviceInfo constants so that
 * compiling against a newer SDK never introduces an API-level trap on this hot path.
 */
private fun audioTypeName(type: Int): String = when (type) {
    1 -> "EARPIECE"
    2 -> "SPEAKER"
    3 -> "WIRED_HEADSET"
    4 -> "WIRED_HEADPHONES"
    5 -> "LINE_ANALOG"
    6 -> "LINE_DIGITAL"
    7 -> "BT_SCO"
    8 -> "BT_A2DP"
    9 -> "HDMI"
    10 -> "HDMI_ARC"
    11 -> "USB_DEVICE"
    12 -> "USB_ACCESSORY"
    13 -> "DOCK"
    14 -> "FM"
    18 -> "TELEPHONY"
    19 -> "AUX_LINE"
    20 -> "IP"
    21 -> "BUS"
    22 -> "USB_HEADSET"
    23 -> "HEARING_AID"
    24 -> "SPEAKER_SAFE"
    25 -> "REMOTE_SUBMIX"
    26 -> "BLE_HEADSET"
    27 -> "BLE_SPEAKER"
    29 -> "HDMI_EARC"
    30 -> "BLE_BROADCAST"
    else -> "TYPE_" + type
}

private fun audioOutputSummary(context: Context): String = try {
    val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    val devices = am?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
    if (devices == null) {
        "unavailable"
    } else {
        devices.map { audioTypeName(it.type) }.sorted().joinToString(",").ifEmpty { "none" }
    }
} catch (t: Throwable) {
    "error:" + t::class.java.simpleName
}

// ---------------------------------------------------------------- wizard snapshot / diff

/** A flat, comparable picture of everything the wizard watches (spec section 8 step 3). */
data class WizardSnapshot(
    val atElapsedMs: Long,
    val values: Map<String, String>,
)

/** One observed transition between two consecutive snapshots. */
data class SnapshotDiff(
    val key: String,
    val before: String,
    val after: String,
    val atElapsedMs: Long,
    val wallClock: String,
)

@Suppress("DEPRECATION", "MissingPermission")
fun takeWizardSnapshot(context: Context): WizardSnapshot {
    val v = LinkedHashMap<String, String>()

    val displays = readDisplayFacts(context)
    val presentation = readPresentationDisplays(context)
    v["display.count"] = displays.size.toString()
    v["display.ids"] = displays.joinToString(",") { it.id.toString() }.ifEmpty { "none" }
    v["display.presentation_count"] = presentation.size.toString()
    v["display.default_resolution"] = displays.firstOrNull { it.id == Display.DEFAULT_DISPLAY }
        ?.let { it.width.toString() + "x" + it.height + "@" + it.densityDpi } ?: "unknown"
    v["display.external"] = displays.filter { it.id != Display.DEFAULT_DISPLAY }
        .joinToString(" | ") { it.oneLine() }.ifEmpty { "none" }

    val route = readLiveVideoRoute(context)
    v["route.live_video"] = route.name ?: "none"
    v["route.presentation_display"] = route.presentationDisplayId?.toString() ?: "none"
    v["route.status"] = route.status ?: "none"

    try {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        v["wifi.enabled"] = (wm?.isWifiEnabled?.toString()) ?: "unknown"
        val info = wm?.connectionInfo
        // SSID is normally redacted without location permission; whatever comes back is recorded
        // verbatim so the report never implies we learned more than we did.
        v["wifi.ssid"] = info?.ssid ?: "unknown"
        v["wifi.frequency_mhz"] = info?.frequency?.toString() ?: "unknown"
        v["wifi.link_speed_mbps"] = info?.linkSpeed?.toString() ?: "unknown"
    } catch (t: Throwable) {
        v["wifi.enabled"] = "error:" + t::class.java.simpleName
    }

    val (p2p, p2pFail) = p2pInterfacesUp()
    v["net.p2p_interfaces"] = if (p2p.isEmpty()) (p2pFail?.let { "error:" + it } ?: "none") else p2p.joinToString("/")
    v["net.interfaces_up"] = interfacesUp().joinToString(",").ifEmpty { "none" }

    try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        var caps: NetworkCapabilities? = null
        if (cm != null) {
            val active = cm.activeNetwork
            if (active != null) caps = cm.getNetworkCapabilities(active)
        }
        v["net.transport"] = when {
            caps == null -> "none"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "BLUETOOTH"
            else -> "OTHER"
        }
    } catch (t: Throwable) {
        v["net.transport"] = "error:" + t::class.java.simpleName
    }

    v["audio.outputs"] = audioOutputSummary(context)
    v["ui.mode_type"] = uiModeTypeName(uiModeType(context))
    v["dex.desktop_mode"] = readDexReflection(context).desktopModeActive?.toString() ?: "unreadable"

    return WizardSnapshot(SystemClock.elapsedRealtime(), v)
}

/** Every key whose value differs, in snapshot order. Keys only in one side count as changes. */
fun diffSnapshots(before: WizardSnapshot, after: WizardSnapshot): List<SnapshotDiff> {
    val stamp = LocalTime.now().truncatedTo(ChronoUnit.SECONDS).toString()
    val keys = LinkedHashSet<String>()
    keys.addAll(before.values.keys)
    keys.addAll(after.values.keys)
    return keys.mapNotNull { k ->
        val b = before.values[k] ?: "-"
        val a = after.values[k] ?: "-"
        if (b == a) null else SnapshotDiff(k, b, a, after.atElapsedMs, stamp)
    }
}
