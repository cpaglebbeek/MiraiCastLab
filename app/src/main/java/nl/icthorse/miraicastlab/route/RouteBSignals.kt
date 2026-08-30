package nl.icthorse.miraicastlab.route

import android.content.Context
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Display
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.NetworkInterface
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.Collections
import java.util.Locale

/**
 * One flat, comparable reading of every signal an ordinary app can see that correlates with a
 * wireless display session, plus the two extra fields route B needs to try to tell Wireless DeX
 * apart from ordinary Smart View mirroring.
 *
 * **What this file does not do.** It does not decide that a session exists, it does not name a
 * transport, and it does not treat DeX and Miracast as the same thing. It reads fields. The route B
 * experiments weigh them, and cap every transport claim at INFERRED because none of these signals
 * names a protocol: an HDMI dongle, a Chromecast, wired DeX or another app's VirtualDisplay produce
 * the same readings.
 *
 * The three "is anything mirroring" signals are the ones `samsung/SmartViewProbe` documents. They
 * are re-read here rather than imported: `route` and `samsung` are separate modules and modules do
 * not import each other (docs/PRINCIPLES.md P10). The readers are deliberately shaped differently -
 * this one produces a diffable snapshot for the B4 timeline, which the probe has no use for.
 */
object RouteBSignals {

    /** One display as an app may read it, including both density figures that matter for DeX. */
    data class DisplayLine(
        val id: Int,
        val name: String,
        val isDefault: Boolean,
        val widthPx: Int,
        val heightPx: Int,
        /** Density from the display's own real metrics. */
        val densityDpi: Int,
        /**
         * Density a Context created for this display reports. DeX changes the density it hands
         * content on the desktop; plain mirroring generally hands over the phone's own density.
         * Reading both makes a disagreement visible instead of hiding it behind one number.
         */
        val contextDensityDpi: Int?,
        val refreshRate: Float,
        val presentationFlag: Boolean,
        val presentationCategory: Boolean,
        val flags: List<String>,
        val failure: String?,
    ) {
        fun line(): String = "#" + id + " \"" + name + "\" " + widthPx + "x" + heightPx +
            " @" + densityDpi + "dpi" +
            (contextDensityDpi?.let { if (it != densityDpi) " (context " + it + "dpi)" else "" } ?: "") +
            " " + String.format(Locale.US, "%.1f", refreshRate) + "Hz" +
            (if (presentationFlag) " FLAG_PRESENTATION" else "") +
            (if (presentationCategory) " IN_PRESENTATION_CATEGORY" else "") +
            (failure?.let { " [" + it + "]" } ?: "")
    }

    /**
     * Samsung's non-AOSP desktop-mode field, read reflectively off [Configuration].
     *
     * [active] is deliberately nullable: null means *we could not read the field*, which is
     * NOT_TESTED, and must never be rendered as "DeX is off".
     */
    data class DexRead(
        val fieldValue: Int?,
        val fieldFailure: String?,
        val constantValue: Int?,
        val constantFailure: String?,
    ) {
        val active: Boolean?
            get() = when {
                fieldValue == null -> null
                constantValue != null -> fieldValue == constantValue
                else -> fieldValue != 0
            }

        fun line(): String = when {
            fieldValue == null -> "unreadable [" + (fieldFailure ?: "no reason recorded") + "]"
            else -> "semDesktopModeEnabled=" + fieldValue +
                (constantValue?.let { " (SEM_DESKTOP_MODE_ENABLED=" + it + ")" }
                    ?: " (constant unreadable: " + (constantFailure ?: "-") + ")")
        }
    }

    /**
     * Reads `Configuration.semDesktopModeEnabled` and its companion constant.
     *
     * One UI only. On any other build both lookups fail, and that failure is the finding: without
     * this field an app has no signal that *names* desktop mode, which is exactly what experiment B2
     * has to report rather than guess around.
     */
    fun readDexMode(context: Context): DexRead {
        var value: Int? = null
        var valueFailure: String? = null
        var constant: Int? = null
        var constantFailure: String? = null
        try {
            val f = Configuration::class.java.getDeclaredField("semDesktopModeEnabled")
            f.isAccessible = true
            value = f.getInt(context.resources.configuration)
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
        return DexRead(value, valueFailure, constant, constantFailure)
    }

    fun uiModeTypeName(context: Context): String = try {
        when (context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) {
            Configuration.UI_MODE_TYPE_UNDEFINED -> "UNDEFINED"
            Configuration.UI_MODE_TYPE_NORMAL -> "NORMAL"
            Configuration.UI_MODE_TYPE_DESK -> "DESK"
            Configuration.UI_MODE_TYPE_CAR -> "CAR"
            Configuration.UI_MODE_TYPE_TELEVISION -> "TELEVISION"
            Configuration.UI_MODE_TYPE_APPLIANCE -> "APPLIANCE"
            Configuration.UI_MODE_TYPE_WATCH -> "WATCH"
            Configuration.UI_MODE_TYPE_VR_HEADSET -> "VR_HEADSET"
            else -> "UNKNOWN"
        }
    } catch (t: Throwable) {
        "error:" + t::class.java.simpleName
    }

    /**
     * Names of `p2p*` interfaces that are up, and the failure string if enumeration itself failed.
     *
     * The distinction matters more here than almost anywhere else: a p2p interface coming up is the
     * strongest app-visible hint of a Wi-Fi Direct based session, so "we could not enumerate" must
     * never read as "no p2p interface".
     */
    fun p2pInterfacesUp(): Pair<List<String>, String?> = try {
        val e = NetworkInterface.getNetworkInterfaces()
        if (e == null) {
            Pair(emptyList(), "NetworkInterface.getNetworkInterfaces() returned null")
        } else {
            val up = Collections.list(e)
                .filter { ni ->
                    ni.name.startsWith("p2p", ignoreCase = true) &&
                        runCatching { ni.isUp }.getOrDefault(false)
                }
                .map { it.name }
                .sorted()
            Pair(up, null)
        }
    } catch (t: Throwable) {
        Pair(emptyList(), t::class.java.simpleName + ": " + (t.message ?: "no message"))
    }

    // ------------------------------------------------------------------ the sample

    /** Everything one pass reads, flattened so two passes can be diffed field by field. */
    data class Sample(
        val atElapsedMs: Long,
        val displays: List<DisplayLine>,
        val presentationDisplayIds: List<Int>,
        val router: RouteAFacts.RouterRead,
        val p2pInterfaces: List<String>,
        val p2pFailure: String?,
        val dex: DexRead,
        val uiModeType: String,
        val remoteCandidateIds: List<Int>,
        val displayFailure: String?,
    ) {
        val internalDisplay: DisplayLine? get() = displays.firstOrNull { it.isDefault }
        val secondaryDisplays: List<DisplayLine> get() = displays.filter { !it.isDefault }

        /** True when the selected live-video route carries a presentation display. */
        val routeHasPresentationDisplay: Boolean
            get() = router.selectedLiveVideo?.presentationDisplayId != null

        /**
         * The three independent "something is mirroring" signals, in the order SmartViewProbe
         * documents them. Presence is a signal; the transport stays unidentified.
         */
        val positives: List<String>
            get() = buildList {
                if (presentationDisplayIds.isNotEmpty()) {
                    add("presentation-category display present: " + presentationDisplayIds.joinToString(","))
                }
                if (routeHasPresentationDisplay) {
                    add("selected live-video route has presentation display " +
                        router.selectedLiveVideo?.presentationDisplayId)
                }
                if (p2pInterfaces.isNotEmpty()) {
                    add("Wi-Fi Direct interface up: " + p2pInterfaces.joinToString("/"))
                }
            }

        val signalCount: Int get() = positives.size

        /** Flat key/value view. This is what the B4 timeline diffs. */
        fun values(): Map<String, String> = linkedMapOf(
            "display.count" to displays.size.toString(),
            "display.ids" to displays.joinToString(",") { it.id.toString() }.ifEmpty { "none" },
            "display.presentation_ids" to
                presentationDisplayIds.joinToString(",").ifEmpty { "none" },
            "display.secondary" to
                secondaryDisplays.joinToString(" | ") { it.line() }.ifEmpty { "none" },
            "role.remote_candidates" to
                remoteCandidateIds.joinToString(",").ifEmpty { "none" },
            "route.live_video" to (router.selectedLiveVideo?.name ?: "none"),
            "route.presentation_display" to
                (router.selectedLiveVideo?.presentationDisplayId?.toString() ?: "none"),
            "route.count" to router.routes.size.toString(),
            "net.p2p_interfaces" to
                (if (p2pInterfaces.isEmpty()) (p2pFailure?.let { "error:" + it } ?: "none")
                else p2pInterfaces.joinToString("/")),
            "dex.desktop_mode" to (dex.active?.toString() ?: "unreadable"),
            "dex.raw_field" to (dex.fieldValue?.toString() ?: "unreadable"),
            "ui.mode_type" to uiModeType,
            "signal.count" to signalCount.toString(),
        )

        /** Descriptive only; contains no capability claim, so it is safe to grade OBSERVED. */
        fun oneLine(): String = "signals=" + signalCount + "/3; displays=" + displays.size +
            "; presentation=" + presentationDisplayIds.size +
            "; live-video route=" + (router.selectedLiveVideo?.name ?: "none") +
            "; route presentation display=" +
            (router.selectedLiveVideo?.presentationDisplayId?.toString() ?: "none") +
            "; p2p=" + (if (p2pInterfaces.isEmpty()) "none" else p2pInterfaces.joinToString("/")) +
            "; desktopMode=" + (dex.active?.toString() ?: "unreadable")
    }

    /** Takes one reading. Never throws; a failed sub-read is recorded in the sample. */
    suspend fun sample(context: Context): Sample {
        val router = RouteAFacts.readRouter(context)
        val p2p = withContext(Dispatchers.IO) { p2pInterfacesUp() }

        var displayFailure: String? = null
        var lines: List<DisplayLine> = emptyList()
        var presentationIds: List<Int> = emptyList()
        try {
            val dm = context.getSystemService(DisplayManager::class.java)
            if (dm == null) {
                displayFailure = "getSystemService(DisplayManager) returned null"
            } else {
                presentationIds = runCatching {
                    dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).map { it.displayId }
                }.getOrDefault(emptyList())
                lines = dm.displays.map { toLine(context, it, presentationIds.contains(it.displayId)) }
            }
        } catch (t: Throwable) {
            displayFailure = t::class.java.simpleName + ": " + (t.message ?: "no message")
        }

        val remoteIds = runCatching {
            DisplayRole.verdicts(context)
                .filter { it.role == DisplayRole.Role.REMOTE_CANDIDATE }
                .map { it.displayId }
        }.getOrDefault(emptyList())

        return Sample(
            atElapsedMs = SystemClock.elapsedRealtime(),
            displays = lines,
            presentationDisplayIds = presentationIds,
            router = router,
            p2pInterfaces = p2p.first,
            p2pFailure = p2p.second,
            dex = readDexMode(context),
            uiModeType = uiModeTypeName(context),
            remoteCandidateIds = remoteIds,
            displayFailure = displayFailure,
        )
    }

    @Suppress("DEPRECATION")
    private fun toLine(context: Context, d: Display, inPresentationCategory: Boolean): DisplayLine {
        // getRealMetrics is deprecated but remains the only per-Display metrics source that works
        // for a display this process holds no window on - which is the whole point here.
        val m = DisplayMetrics()
        var failure: String? = null
        runCatching { d.getRealMetrics(m) }
            .onFailure { failure = it::class.java.simpleName }
        val ctxDensity = runCatching {
            context.createDisplayContext(d).resources.configuration.densityDpi
        }.getOrNull()
        return DisplayLine(
            id = d.displayId,
            name = runCatching { d.name }.getOrDefault("unnamed"),
            isDefault = d.displayId == Display.DEFAULT_DISPLAY,
            widthPx = m.widthPixels,
            heightPx = m.heightPixels,
            densityDpi = m.densityDpi,
            contextDensityDpi = ctxDensity,
            refreshRate = runCatching { d.refreshRate }.getOrDefault(0f),
            presentationFlag = runCatching { (d.flags and Display.FLAG_PRESENTATION) != 0 }
                .getOrDefault(false),
            presentationCategory = inPresentationCategory,
            flags = runCatching { DisplayRole.decodeFlags(d) }.getOrDefault(emptyList()),
            failure = failure,
        )
    }

    // ------------------------------------------------------------------ transitions

    /** One field that differed between two consecutive samples. */
    data class Transition(
        val key: String,
        val before: String,
        val after: String,
        val atElapsedMs: Long,
        val sinceStartMs: Long,
        val wallClock: String,
    ) {
        fun line(): String = "+" + sinceStartMs + "ms " + key + ": \"" + before + "\" -> \"" + after + "\""
    }

    /** Every field whose value differs, in sample order. A key present on one side counts. */
    fun diff(before: Sample, after: Sample, startElapsedMs: Long): List<Transition> {
        val stamp = runCatching { LocalTime.now().truncatedTo(ChronoUnit.SECONDS).toString() }
            .getOrDefault("-")
        val b = before.values()
        val a = after.values()
        val keys = LinkedHashSet<String>()
        keys.addAll(b.keys)
        keys.addAll(a.keys)
        return keys.mapNotNull { k ->
            val bv = b[k] ?: "-"
            val av = a[k] ?: "-"
            if (bv == av) {
                null
            } else {
                Transition(k, bv, av, after.atElapsedMs, after.atElapsedMs - startElapsedMs, stamp)
            }
        }
    }
}
