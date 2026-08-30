package nl.icthorse.miraicastlab.display

import android.content.Context
import android.view.Display
import nl.icthorse.miraicastlab.core.DashboardKeys
import nl.icthorse.miraicastlab.core.LabCategory
import nl.icthorse.miraicastlab.core.LabStatus
import nl.icthorse.miraicastlab.core.Observation
import nl.icthorse.miraicastlab.core.Probe

/**
 * Display topology (spec sections 2.2, 2.3, 6).
 *
 * What this probe can and cannot prove:
 * a Wi-Fi Display sink (Miracast), a wireless DeX surface and an app-created VirtualDisplay all
 * arrive at DisplayManager as an ordinary secondary [Display]. There is no public API that names
 * the transport. So this probe reports *that* a secondary display exists, with its full geometry,
 * and refuses to claim it is the Toyota head unit: that link is graded INFERRED at best, and only
 * the tester's own marker (see DisplayTestScreen) ties a topology change to a Smart View action.
 *
 * DisplayManager.DISPLAY_CATEGORY_PRESENTATION is the strongest ordinary-app signal available:
 * the framework only lists a display there when it is a real, non-private presentation surface.
 */
object DisplayProbe : Probe {

    override val id = "display"
    override val title = "Displays & topology"

    override suspend fun observe(context: Context): List<Observation> {
        val out = mutableListOf<Observation>()

        val dm = DisplayFacts.manager(context)
        if (dm == null) {
            // The platform answered: there is no display service on this context. That is a real
            // negative, not a missing test.
            out += Observation.unsupported(
                DashboardKeys.DISPLAY_COUNT,
                LabCategory.DISPLAY,
                "getSystemService(DISPLAY_SERVICE) returned null.",
            )
            out += Observation.unsupported(DashboardKeys.EXTERNAL_PRESENT, LabCategory.DISPLAY,
                "No DisplayManager, so topology cannot be enumerated.")
            out += Observation.notTested(DashboardKeys.DEFAULT_RESOLUTION, LabCategory.DISPLAY,
                "No DisplayManager available to measure with.")
            out += Observation.notTested(DashboardKeys.EXTERNAL_RESOLUTION, LabCategory.DISPLAY,
                "No DisplayManager available to measure with.")
            out += Observation.notTested(DashboardKeys.EXTERNAL_NAMES, LabCategory.DISPLAY,
                "No DisplayManager available to enumerate names.")
            return out
        }

        val facts = DisplayFacts.collect(context)
        val presentationIds = DisplayFacts.presentationDisplayIds(context)
        val external = facts.filter { it.isExternal }
        val default = facts.firstOrNull { it.displayId == Display.DEFAULT_DISPLAY }

        // ---------------------------------------------------------------- dashboard contract

        out += Observation.confirmed(
            DashboardKeys.DISPLAY_COUNT,
            facts.size,
            LabCategory.DISPLAY,
            "DisplayManager.getDisplays() size, including the built-in panel.",
        )

        out += Observation.confirmed(
            DashboardKeys.EXTERNAL_PRESENT,
            external.isNotEmpty(),
            LabCategory.DISPLAY,
            if (external.isEmpty()) {
                "No display other than DEFAULT_DISPLAY at probe time."
            } else {
                "Secondary display(s) present: " + external.joinToString(", ") { "id=" + it.displayId } +
                    ". Transport (Miracast / DeX / virtual) is NOT identifiable from DisplayManager."
            },
        )

        if (default != null) {
            out += Observation.confirmed(
                DashboardKeys.DEFAULT_RESOLUTION,
                default.resolution + " @" + fmt(default.refreshRate) + "Hz, " + default.densityDpi + "dpi",
                LabCategory.DISPLAY,
                "Display.getRealMetrics() of DEFAULT_DISPLAY. On a foldable this changes with the fold state.",
            )
        } else {
            out += Observation.error(
                DashboardKeys.DEFAULT_RESOLUTION,
                IllegalStateException("DEFAULT_DISPLAY absent from getDisplays()"),
                LabCategory.DISPLAY,
            )
        }

        if (external.isEmpty()) {
            // Nothing is attached. That says nothing about what a Toyota sink would do, so this
            // is NOT_TESTED and must never be promoted to UNSUPPORTED.
            out += Observation.notTested(
                DashboardKeys.EXTERNAL_RESOLUTION,
                LabCategory.DISPLAY,
                "No secondary display attached at probe time; nothing was measured.",
            )
            out += Observation.notTested(
                DashboardKeys.EXTERNAL_NAMES,
                LabCategory.DISPLAY,
                "No secondary display attached at probe time.",
            )
        } else {
            out += Observation.confirmed(
                DashboardKeys.EXTERNAL_RESOLUTION,
                external.joinToString("; ") {
                    it.resolution + " @" + fmt(it.refreshRate) + "Hz, " + it.densityDpi + "dpi (id=" + it.displayId + ")"
                },
                LabCategory.DISPLAY,
                "Real metrics of every non-default display.",
            )
            out += Observation.confirmed(
                DashboardKeys.EXTERNAL_NAMES,
                external.joinToString("; ") { "id=" + it.displayId + " \"" + it.name + "\"" },
                LabCategory.DISPLAY,
                "Display.getName() is vendor text; it is a hint about the sink, not proof of it.",
            )
        }

        // ---------------------------------------------------------------- presentation category

        out += Observation.confirmed(
            "display.presentation_category_count",
            presentationIds.size,
            LabCategory.DISPLAY,
            "DisplayManager.getDisplays(DISPLAY_CATEGORY_PRESENTATION): the strongest ordinary-app " +
                "signal that a Miracast/DeX presentation surface exists.",
        )
        out += Observation.confirmed(
            "display.presentation_category_ids",
            if (presentationIds.isEmpty()) "none" else presentationIds.joinToString(","),
            LabCategory.DISPLAY,
        )

        // ---------------------------------------------------------------- honesty about identity

        out += when {
            external.isEmpty() -> Observation.notTested(
                "display.external_is_wireless_sink",
                LabCategory.MIRACAST,
                "No secondary display present, so no sink could be characterised.",
            )

            presentationIds.any { it != Display.DEFAULT_DISPLAY } -> Observation(
                key = "display.external_is_wireless_sink",
                value = "possible",
                status = LabStatus.INFERRED,
                note = "A non-default display is listed in DISPLAY_CATEGORY_PRESENTATION. That is " +
                    "consistent with Miracast/Smart View or wireless DeX, but DisplayManager exposes " +
                    "no transport field: an HDMI dongle or a VirtualDisplay looks identical. Correlate " +
                    "with the tester's Smart View marker and with WifiP2pProbe before claiming more.",
                category = LabCategory.MIRACAST,
            )

            else -> Observation(
                key = "display.external_is_wireless_sink",
                value = "unknown",
                status = LabStatus.INFERRED,
                note = "A secondary display exists but is not in the PRESENTATION category " +
                    "(typically FLAG_PRIVATE, e.g. an app-owned VirtualDisplay such as this lab's own " +
                    "MediaProjection surface). Not evidence of a wireless sink.",
                category = LabCategory.MIRACAST,
            )
        }

        // ---------------------------------------------------------------- per-display detail

        if (facts.isEmpty()) {
            out += Observation.error(
                "display.enumeration",
                IllegalStateException("getDisplays() returned no displays at all"),
                LabCategory.DISPLAY,
            )
        }

        facts.forEach { f ->
            val p = "display." + f.displayId + "."
            out += Observation.confirmed(p + "name", f.name, LabCategory.DISPLAY)
            out += Observation.confirmed(p + "state", f.stateName + " (" + f.state + ")", LabCategory.DISPLAY)
            out += Observation.confirmed(
                p + "flags",
                if (f.flagNames.isEmpty()) "0x" + Integer.toHexString(f.flags) else f.flagNames.joinToString("|"),
                LabCategory.DISPLAY,
                "FLAG_PRESENTATION marks a display an app may put a Presentation on; FLAG_SECURE " +
                    "governs whether protected content may be shown there.",
            )
            out += Observation.confirmed(p + "valid", f.isValid, LabCategory.DISPLAY)
            out += Observation.confirmed(p + "rotation", f.rotationDegrees, LabCategory.DISPLAY)
            out += Observation.confirmed(p + "refresh_rate", fmt(f.refreshRate) + " Hz", LabCategory.DISPLAY)
            out += Observation.confirmed(p + "real_size", f.resolution, LabCategory.DISPLAY)
            out += Observation.confirmed(
                p + "density",
                f.densityDpi.toString() + " dpi (xdpi=" + fmt(f.xdpi) + ", ydpi=" + fmt(f.ydpi) + ")",
                LabCategory.DISPLAY,
            )
            out += Observation.confirmed(p + "current_mode", f.currentModeId, LabCategory.DISPLAY)

            if (f.modes.isEmpty()) {
                out += Observation.unsupported(
                    p + "modes",
                    LabCategory.DISPLAY,
                    "Display.getSupportedModes() reported nothing for this display.",
                )
            } else {
                out += Observation.confirmed(
                    p + "modes",
                    f.modes.joinToString("; "),
                    LabCategory.DISPLAY,
                    "modeId, physical width x height, refresh rate. A sink's mode list is the " +
                        "clearest evidence of what resolutions it will actually accept.",
                )
            }

            if (f.hdrTypes.isEmpty()) {
                out += Observation.unsupported(
                    p + "hdr_types",
                    LabCategory.DISPLAY,
                    "Display.getHdrCapabilities() listed no HDR type for this display.",
                )
            } else {
                out += Observation.confirmed(p + "hdr_types", f.hdrTypes.joinToString(","), LabCategory.DISPLAY)
            }

            f.isHdr?.let { out += Observation.confirmed(p + "is_hdr", it, LabCategory.DISPLAY) }
            f.isWideColorGamut?.let {
                out += Observation.confirmed(p + "wide_color_gamut", it, LabCategory.DISPLAY)
            }

            out += Observation.confirmed(
                p + "presentation_category",
                f.inPresentationCategory,
                LabCategory.DISPLAY,
                "Present in DisplayManager's PRESENTATION category listing.",
            )

            if (f.productInfo != null) {
                out += Observation.confirmed(
                    p + "device_product_info",
                    f.productInfo,
                    LabCategory.DISPLAY,
                    "Display.getDeviceProductInfo() (API 31+). sinkConnection=DIRECT/TRANSITIVE " +
                        "distinguishes a directly attached sink from one behind a repeater.",
                )
            } else {
                out += Observation.notTested(
                    p + "device_product_info",
                    LabCategory.DISPLAY,
                    "No DeviceProductInfo: either below API 31 or the sink supplied no EDID-style " +
                        "identity, which is normal for wireless sinks.",
                )
            }

            f.readErrors.forEach { err ->
                out += Observation(
                    key = p + "read_error",
                    value = err,
                    status = LabStatus.ERROR,
                    note = "One getter threw; the remaining fields for this display are still valid.",
                    category = LabCategory.DISPLAY,
                )
            }
        }

        return out
    }
}
