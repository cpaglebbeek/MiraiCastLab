package nl.icthorse.miraicastlab.route

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import nl.icthorse.miraicastlab.core.LabCategory
import nl.icthorse.miraicastlab.core.LabStatus
import nl.icthorse.miraicastlab.core.Observation

/**
 * Which display is which.
 *
 * The research question needs an answer to "which of these Display objects IS the DeX/Miracast
 * screen?" before any route can be tested. Android does not tell you. There is no
 * `Display.isMiracast()`, no transport field, and a Wi-Fi Display sink is deliberately presented as
 * an ordinary secondary display.
 *
 * So this class does the only honest thing available: it collects the signals an ordinary app CAN
 * see, weighs them, and returns a verdict that is **never stronger than INFERRED** for anything
 * remote. The contributing signals are always reported alongside the verdict, so a reader can
 * disagree with the weighting without having to re-derive the evidence.
 */
object DisplayRole {

    enum class Role(val label: String) {
        INTERNAL("built-in panel"),
        REMOTE_CANDIDATE("remote candidate (DeX / Miracast / cast)"),
        WIRED_CANDIDATE("wired external (HDMI / USB-C DP)"),
        VIRTUAL("app-created virtual display"),
        OVERLAY("system overlay / simulated"),
        UNKNOWN("unknown"),
    }

    /** A weighed verdict about one display, with the evidence that produced it. */
    data class Verdict(
        val displayId: Int,
        val name: String,
        val role: Role,
        val status: LabStatus,
        val signals: List<String>,
        val against: List<String>,
        val flags: List<String>,
        val presentationCategory: Boolean,
    ) {
        val summary: String
            get() = "id=" + displayId + " \"" + name + "\" -> " + role.label

        fun explain(): String = buildString {
            append(role.label)
            if (signals.isNotEmpty()) append(" | for: ").append(signals.joinToString("; "))
            if (against.isNotEmpty()) append(" | against: ").append(against.joinToString("; "))
        }
    }

    /**
     * Names of Display flags that are set, decoded.
     *
     * FLAG_PRESENTATION is the one that matters most here: it is what
     * `DisplayManager.getDisplays(DISPLAY_CATEGORY_PRESENTATION)` selects on, and it is the closest
     * an ordinary app gets to "this display is somewhere else".
     */
    fun decodeFlags(d: Display): List<String> {
        val f = d.flags
        val out = mutableListOf<String>()
        fun has(bit: Int, name: String) { if (f and bit != 0) out += name }
        has(Display.FLAG_PRESENTATION, "FLAG_PRESENTATION")
        has(Display.FLAG_PRIVATE, "FLAG_PRIVATE")
        has(Display.FLAG_SECURE, "FLAG_SECURE")
        has(Display.FLAG_SUPPORTS_PROTECTED_BUFFERS, "FLAG_SUPPORTS_PROTECTED_BUFFERS")
        has(Display.FLAG_ROUND, "FLAG_ROUND")
        return out
    }

    /** Verdicts for every display currently known to the platform. */
    fun verdicts(context: Context): List<Verdict> {
        val dm = context.getSystemService(DisplayManager::class.java) ?: return emptyList()
        val presentation = runCatching {
            dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).map { it.displayId }.toSet()
        }.getOrDefault(emptySet())
        return dm.displays.map { verdict(it, presentation.contains(it.displayId)) }
    }

    /**
     * Weighs one display.
     *
     * Deliberately conservative. A remote verdict is at most INFERRED, because every signal
     * available to an app is also consistent with something that is not Miracast: an HDMI dongle,
     * a Chromecast, DeX over USB-C, or another app's virtual display.
     */
    fun verdict(d: Display, inPresentationCategory: Boolean): Verdict {
        val flags = decodeFlags(d)
        val signals = mutableListOf<String>()
        val against = mutableListOf<String>()
        val name = runCatching { d.name }.getOrDefault("?")

        val isDefault = d.displayId == Display.DEFAULT_DISPLAY
        if (isDefault) signals += "displayId == DEFAULT_DISPLAY"

        if (inPresentationCategory) signals += "in DISPLAY_CATEGORY_PRESENTATION"
        if (flags.contains("FLAG_PRESENTATION")) signals += "FLAG_PRESENTATION set"
        if (flags.contains("FLAG_PRIVATE")) {
            signals += "FLAG_PRIVATE set (app- or system-created virtual display)"
        }

        // DeviceProductInfo is the only place Android hints at how a display is attached.
        // Display.getDeviceProductInfo() is API 31 (S), not 30 - guarding on R would throw on
        // Android 11. Found by lint; the reflection-free path simply reports nothing below 31.
        var connection: String? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                d.deviceProductInfo?.let { info ->
                    connection = when (info.connectionToSinkType) {
                        android.hardware.display.DeviceProductInfo.CONNECTION_TO_SINK_BUILT_IN -> "BUILT_IN"
                        android.hardware.display.DeviceProductInfo.CONNECTION_TO_SINK_DIRECT -> "DIRECT"
                        android.hardware.display.DeviceProductInfo.CONNECTION_TO_SINK_TRANSITIVE -> "TRANSITIVE"
                        else -> "UNKNOWN"
                    }
                    info.manufacturerPnpId?.let { signals += "sink manufacturer PnP id " + it }
                }
            }
        }
        connection?.let { signals += "connectionToSinkType=" + it }

        // Name heuristics. Weak on their own, which is why they only ever contribute to INFERRED.
        val lower = name.lowercase()
        val nameHints = listOf("dex", "cast", "wifi", "wireless", "miracast", "smartview", "overlay", "hdmi")
            .filter { lower.contains(it) }
        nameHints.forEach { signals += "display name contains \"" + it + "\"" }

        val role: Role
        val status: LabStatus

        when {
            isDefault -> {
                role = Role.INTERNAL
                status = LabStatus.CONFIRMED // DEFAULT_DISPLAY is a platform guarantee, not a guess.
            }
            lower.contains("overlay") || lower.contains("simulated") -> {
                role = Role.OVERLAY
                status = LabStatus.INFERRED
                against += "name suggests a developer-options simulated display, not a real sink"
            }
            connection == "BUILT_IN" -> {
                role = Role.INTERNAL
                status = LabStatus.INFERRED
                signals += "reported as built-in although not DEFAULT_DISPLAY (foldable inner/outer panel?)"
            }
            flags.contains("FLAG_PRIVATE") && !inPresentationCategory -> {
                role = Role.VIRTUAL
                status = LabStatus.INFERRED
                against += "private and not offered for presentation: most likely a VirtualDisplay"
            }
            lower.contains("hdmi") || connection == "DIRECT" -> {
                role = Role.WIRED_CANDIDATE
                status = LabStatus.INFERRED
                against += "a wired sink and a wireless sink look identical to an app beyond this hint"
            }
            inPresentationCategory || flags.contains("FLAG_PRESENTATION") -> {
                role = Role.REMOTE_CANDIDATE
                status = LabStatus.INFERRED
                against += "Android never names the transport: this is equally consistent with " +
                    "an HDMI dongle, a Chromecast, wired DeX, or another app's virtual display"
            }
            else -> {
                role = Role.UNKNOWN
                status = LabStatus.NOT_TESTED
                against += "no signal available to classify this display from an ordinary app"
            }
        }

        return Verdict(
            displayId = d.displayId,
            name = name,
            role = role,
            status = status,
            signals = signals,
            against = against,
            flags = flags,
            presentationCategory = inPresentationCategory,
        )
    }

    /**
     * The display the route experiments should target, or null when there is no candidate.
     * Prefers a remote candidate; falls back to any non-default display.
     */
    fun preferredTarget(context: Context): Verdict? {
        val all = verdicts(context)
        return all.firstOrNull { it.role == Role.REMOTE_CANDIDATE }
            ?: all.firstOrNull { it.role == Role.WIRED_CANDIDATE }
            ?: all.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
    }

    /** Verdicts rendered as observations for the scan and the report. */
    fun observations(context: Context): List<Observation> {
        val v = verdicts(context)
        if (v.isEmpty()) {
            return listOf(
                Observation.error(
                    "route.display_role.unavailable",
                    IllegalStateException("DisplayManager returned no displays"),
                    LabCategory.DISPLAY,
                ),
            )
        }
        val out = v.map {
            Observation(
                key = "route.display_role." + it.displayId,
                value = it.summary,
                status = it.status,
                note = it.explain(),
                category = LabCategory.DISPLAY,
            )
        }.toMutableList()
        val remote = v.filter { it.role == Role.REMOTE_CANDIDATE }
        out += if (remote.isEmpty()) {
            Observation(
                "route.remote_display_present", "no remote candidate", LabStatus.OBSERVED,
                "No display currently looks like a DeX/Miracast sink. If Smart View is running, " +
                    "this is a finding; if it is not, this is simply the baseline.",
                LabCategory.DISPLAY,
            )
        } else {
            Observation(
                "route.remote_display_present",
                remote.joinToString("; ") { it.summary },
                LabStatus.INFERRED,
                "Candidate for the route C and D experiments. The transport is not verifiable " +
                    "from an app, so this never rises above INFERRED.",
                LabCategory.DISPLAY,
            )
        }
        return out
    }
}
