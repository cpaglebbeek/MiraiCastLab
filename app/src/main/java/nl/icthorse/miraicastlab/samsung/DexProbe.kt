package nl.icthorse.miraicastlab.samsung

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.view.Display
import androidx.core.content.ContextCompat
import nl.icthorse.miraicastlab.core.LabCategory
import nl.icthorse.miraicastlab.core.LabStatus
import nl.icthorse.miraicastlab.core.Observation
import nl.icthorse.miraicastlab.core.Probe

/**
 * Samsung DeX surface.
 *
 * DeX is the interesting case for this research because it is the one Samsung path that turns an
 * external display into a *real* second Android display with its own density and window stack,
 * rather than a mirror of the phone. If the Mirai head unit accepts DeX, activities can be hosted
 * on it; if it only accepts Smart View, they cannot.
 *
 * This probe answers the phone-side half of spec 2.3 and marks the vehicle-side half NOT_TESTED,
 * because every one of those questions needs the car in front of the tester.
 *
 * The one proprietary call in this file is a read-only reflective look at Samsung's
 * `Configuration.semDesktopModeEnabled`. It is guarded per field, never written, and its absence is
 * recorded rather than worked around (spec sections 2.2 and 17).
 */
object DexProbe : Probe {

    override val id = "dex"
    override val title = "Samsung DeX surface"

    override suspend fun observe(context: Context): List<Observation> {
        val out = mutableListOf<Observation>()
        val cat = LabCategory.DEX

        // ---- 1. Samsung's own desktop-mode flag ------------------------------------------
        val dex = readDexReflection(context)

        out += if (dex.fieldValue != null) {
            Observation.confirmed(
                "dex.config_field_sem_desktop_mode_enabled", dex.fieldValue, cat,
                "Configuration.semDesktopModeEnabled read reflectively. Present means this is a " +
                    "Samsung framework build that tracks a desktop mode.",
            )
        } else {
            Observation.unsupported(
                "dex.config_field_sem_desktop_mode_enabled", cat,
                "Configuration has no readable semDesktopModeEnabled field on this build: " +
                    (dex.fieldFailure ?: "unknown failure") +
                    ". On a non-Samsung device that is the expected answer; on a Samsung device it " +
                    "would more likely mean non-SDK interface restrictions blocked the read.",
            )
        }

        out += if (dex.constantValue != null) {
            Observation.confirmed(
                "dex.config_constant_sem_desktop_mode_enabled", dex.constantValue, cat,
                "Configuration.SEM_DESKTOP_MODE_ENABLED, the value semDesktopModeEnabled takes " +
                    "while DeX is running.",
            )
        } else {
            Observation.unsupported(
                "dex.config_constant_sem_desktop_mode_enabled", cat,
                "Constant not present: " + (dex.constantFailure ?: "unknown failure") +
                    ". Without it, a non-zero field value is treated as 'enabled', which is a " +
                    "weaker reading and is graded accordingly.",
            )
        }

        val active = dex.desktopModeActive
        out += when (active) {
            null -> Observation.notTested(
                "dex.desktop_mode_active", cat,
                "The desktop-mode field could not be read at all, so nothing may be concluded " +
                    "about whether DeX is running. This is NOT the same as 'DeX is off'.",
            )

            true -> Observation.observed(
                "dex.desktop_mode_active", "true", cat,
                "Samsung reports desktop mode is currently enabled.",
            )

            false -> Observation.confirmed(
                "dex.desktop_mode_active", "false", cat,
                "Samsung reports desktop mode is currently disabled. Re-run this probe while DeX " +
                    "is running to capture the contrast.",
            )
        }

        val (sem, semFailure) = readSemPlatformInt()
        out += if (sem != null) {
            Observation.confirmed(
                "dex.sem_platform_int", sem, cat,
                "Build.VERSION.SEM_PLATFORM_INT - Samsung's own platform revision. Its presence is " +
                    "independent evidence that this is a One UI framework build.",
            )
        } else {
            Observation.unsupported(
                "dex.sem_platform_int", cat,
                "Build.VERSION exposes no SEM_PLATFORM_INT: " + (semFailure ?: "unknown failure"),
            )
        }

        // ---- 2. UI mode ------------------------------------------------------------------
        val mode = uiModeType(context)
        out += Observation.confirmed(
            "dex.ui_mode_type", uiModeTypeName(mode), cat,
            "UI_MODE_TYPE_DESK would indicate a desktop-style UI; UI_MODE_TYPE_CAR would indicate " +
                "the system believes it is in an automotive UI context. Samsung DeX historically " +
                "does NOT switch this to DESK, so NORMAL here does not rule DeX out.",
        )

        out += try {
            Observation.confirmed(
                "dex.feature_freeform_windows",
                context.packageManager.hasSystemFeature(
                    PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT,
                ),
                cat,
                "Freeform window management is what lets DeX host resizable app windows on the " +
                    "external display.",
            )
        } catch (t: Throwable) {
            Observation.error("dex.feature_freeform_windows", t, cat)
        }

        val features = systemFeaturesMatching(context, listOf("dex", "desktop", "multiwindow", "freeform"))
        out += if (features.isEmpty()) {
            Observation.unsupported(
                "dex.system_features", cat,
                "No declared platform feature names a DeX/desktop capability. Feature queries are " +
                    "not visibility-filtered, so this answer is trustworthy.",
            )
        } else {
            Observation.confirmed("dex.system_features", features.joinToString(", "), cat)
        }

        // ---- 3. DeX packages -------------------------------------------------------------
        val visible = visiblePackageCount(context)
        val ambiguous = packageAbsenceIsAmbiguous(visible)
        DEX_PACKAGES.forEach { (pkg, label) ->
            out += packageObservation(readPackage(context, pkg, label), "dex.pkg", cat, ambiguous)
        }

        // ---- 4. DeX broadcasts -----------------------------------------------------------
        out += broadcastRegistrationFacts(context, cat)

        // ---- 5. external display shape ---------------------------------------------------
        out += externalDisplayDiscriminator(context, cat)

        // ---- 6. what needs the vehicle ---------------------------------------------------
        out += vehicleDependentQuestions()

        return out
    }

    /**
     * Registers, then immediately unregisters, a receiver for each DeX action.
     *
     * What this proves is narrow and the note says so: Android accepts a runtime receiver for any
     * action string, existing or invented. Success means only that the listener the DeX screen uses
     * can be installed. Whether the broadcast is ever *sent* stays NOT_TESTED until one is seen.
     */
    private fun broadcastRegistrationFacts(context: Context, cat: LabCategory): List<Observation> {
        val noop = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) = Unit
        }
        return DEX_BROADCAST_ACTIONS.flatMap { (action, label) ->
            val key = "dex.broadcast." + action.substringAfterLast('.').lowercase()
            val registration = try {
                ContextCompat.registerReceiver(
                    context.applicationContext,
                    noop,
                    IntentFilter(action),
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
                runCatching { context.applicationContext.unregisterReceiver(noop) }
                Observation.confirmed(
                    key + ".registrable", "yes", cat,
                    label + " (" + action + ") - a receiver can be installed for this action. " +
                        "Android accepts any action string, so this says nothing about whether the " +
                        "broadcast exists.",
                )
            } catch (t: Throwable) {
                Observation.error(key + ".registrable", t, cat)
            }
            listOf(
                registration,
                Observation.notTested(
                    key + ".received", cat,
                    label + " has not been seen during this run. Open the DeX screen and enter DeX " +
                        "to test it. Note that the receiver is registered RECEIVER_NOT_EXPORTED, so " +
                        "a broadcast sent by a Samsung app running as a non-system uid would not " +
                        "reach it; a null result is therefore not evidence of absence.",
                ),
            )
        }
    }

    /**
     * The density discriminator.
     *
     * Plain Smart View mirroring hands the sink a copy of the phone's own display, so the external
     * display's density matches the phone's. DeX composes an independent desktop, typically at a
     * much lower density. A distinct density on a presentation display is therefore the best
     * unprivileged discriminator between the two - and it is INFERRED, never CONFIRMED, because a
     * DisplayManager virtual display or an HDMI dongle can look the same.
     */
    private fun externalDisplayDiscriminator(context: Context, cat: LabCategory): List<Observation> {
        val out = mutableListOf<Observation>()
        val all = readDisplayFacts(context)
        val default = all.firstOrNull { it.id == Display.DEFAULT_DISPLAY }
        val external = all.filter { it.id != Display.DEFAULT_DISPLAY }

        out += Observation.confirmed(
            "dex.external_display_count", external.size, cat,
            if (external.isEmpty()) {
                "No secondary display exists right now, so neither DeX nor Smart View is presenting."
            } else {
                external.joinToString(" | ") { it.oneLine() }
            },
        )

        if (external.isEmpty()) {
            out += Observation.notTested(
                "dex.density_discriminator", cat,
                "Needs a secondary display to exist. Start Smart View or DeX and re-run.",
            )
            return out
        }

        val presentation = external.filter { it.presentation }
        out += Observation.confirmed(
            "dex.external_presentation_flag",
            presentation.size.toString() + "/" + external.size + " carry FLAG_PRESENTATION",
            cat,
            "FLAG_PRESENTATION means Android considers the display suitable for app content rather " +
                "than a private mirror.",
        )

        val phoneDpi = default?.densityDpi
        val distinct = external.filter { phoneDpi != null && it.densityDpi != phoneDpi }
        out += when {
            phoneDpi == null -> Observation.notTested(
                "dex.density_discriminator", cat,
                "The default display's density could not be read, so no comparison is possible.",
            )

            distinct.isNotEmpty() -> Observation(
                key = "dex.density_discriminator",
                value = "external density differs from phone (" +
                    distinct.joinToString(", ") { it.densityDpi.toString() + "dpi" } +
                    " vs " + phoneDpi + "dpi)",
                status = LabStatus.INFERRED,
                note = "A distinct density means the external display is composed independently, " +
                    "which is what DeX does and what plain mirroring does not. An HDMI adapter or a " +
                    "virtual display would produce the same reading, so this is inference, not proof.",
                category = cat,
            )

            else -> Observation(
                key = "dex.density_discriminator",
                value = "external density matches phone (" + phoneDpi + "dpi)",
                status = LabStatus.INFERRED,
                note = "Same density as the phone is what a straight mirror looks like, so this run " +
                    "points at Smart View mirroring rather than DeX. Confirm on the head unit: a " +
                    "mirror shows the phone's own UI, DeX shows a desktop.",
                category = cat,
            )
        }
        return out
    }

    /** Spec 2.3 questions that cannot be answered without the Toyota Mirai physically present. */
    private fun vehicleDependentQuestions(): List<Observation> {
        val cat = LabCategory.DEX
        val needsCar = "Requires the Toyota Mirai 2025 head unit connected to this phone. "
        return listOf(
            Observation.notTested(
                "dex.offered_to_mirai", cat,
                needsCar + "Whether Wireless DeX appears as an option for this sink is decided by " +
                    "the Samsung mirroring UI after it has discovered the sink's capabilities.",
            ),
            Observation.notTested(
                "dex.sink_advertises_dex_capability", cat,
                needsCar + "Whether the Mirai is seen only as a Miracast sink or also as a " +
                    "DeX-capable wireless display is not exposed to third-party apps; the tester " +
                    "must read it off the Smart View / DeX picker and record it as a marker.",
            ),
            Observation.notTested(
                "dex.metrics_differ_smartview_vs_dex", cat,
                needsCar + "This needs two runs against the same sink - one in Smart View, one in " +
                    "DeX - so dex.density_discriminator can be compared across them. The wizard's " +
                    "baseline/poll diff is the instrument for that.",
            ),
            Observation.notTested(
                "dex.phone_as_touchpad", cat,
                needsCar + "Whether the phone screen becomes a DeX touchpad is a system UI " +
                    "behaviour; an app can only observe that its own window lost focus.",
            ),
            Observation.notTested(
                "dex.mirai_touch_as_pointer_source", cat,
                needsCar + "Whether the head unit's touchscreen becomes a pointer source is exactly " +
                    "the UIBC question. The input module's touch-back test is the instrument; do " +
                    "not infer it from any DeX signal on this screen.",
            ),
            Observation.notTested(
                "dex.external_hosts_activities", cat,
                needsCar + "Testable the moment any external display exists: the Miracast wizard's " +
                    "step 4 launches the test scene with ActivityOptions.setLaunchDisplayId. If the " +
                    "scene appears on the head unit, this becomes OBSERVED.",
            ),
            Observation.notTested(
                "dex.keyboard_mouse_to_dex", cat,
                needsCar + "Sending synthetic key or pointer events into another app's window is " +
                    "not permitted without an accessibility service or instrumentation, neither of " +
                    "which this build uses. Only real peripherals can be characterised.",
            ),
        )
    }
}
