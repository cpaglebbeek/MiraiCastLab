package nl.icthorse.miraicastlab.auto

import android.app.UiModeManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import nl.icthorse.miraicastlab.core.DashboardKeys
import nl.icthorse.miraicastlab.core.LabCategory
import nl.icthorse.miraicastlab.core.LabStatus
import nl.icthorse.miraicastlab.core.Observation
import nl.icthorse.miraicastlab.core.Probe

/**
 * What an ordinary Android app may legitimately learn about Android Auto (spec section 3).
 *
 * Scope, and the reason this probe is short on verdicts: Android Auto's projection protocol is not
 * a public API. There is no supported call that answers "is a head unit projecting right now". The
 * platform offers exactly one authoritative signal - [UiModeManager.getCurrentModeType] returning
 * UI_MODE_TYPE_CAR - plus a set of circumstantial ones (USB attachment, a 5 GHz link, the presence
 * of the Android Auto packages). This probe reports the authoritative signal as CONFIRMED and the
 * combination of circumstantial ones as INFERRED, and refuses to guess beyond that.
 *
 * Spec section 3 also forbids protocol spoofing and any circumvention of Android Auto's app-category
 * restrictions. Nothing here connects, negotiates, or transmits: every call below is a read.
 */
object AndroidAutoProbe : Probe {

    override val id = "androidauto"
    override val title = "Android Auto connection"

    /** Android Auto (phone-side projection client). */
    private const val PKG_GEARHEAD = "com.google.android.projection.gearhead"

    /** Android Automotive OS template host - present on built-in AAOS, not on a phone. */
    private const val PKG_AAOS_HOST = "com.google.android.apps.automotive.templates.host"

    /** Play services: Android Auto's wireless projection path is bootstrapped from here. */
    private const val PKG_GMS = "com.google.android.gms"

    private val PACKAGES = listOf(PKG_GEARHEAD, PKG_AAOS_HOST, PKG_GMS)

    /** Outcome of one package lookup. [state] separates "absent" from "invisible to us". */
    private data class PkgResult(val state: String, val version: String?) {
        fun render(): String = when {
            state == "present" && version != null -> "present(" + version + ")"
            else -> state
        }
    }

    override suspend fun observe(context: Context): List<Observation> {
        val out = mutableListOf<Observation>()

        // ---------------------------------------------------------- packages
        val lookups = LinkedHashMap<String, PkgResult>()
        PACKAGES.forEach { lookups[it] = lookupPackage(context.packageManager, it) }

        lookups.forEach { (pkg, res) ->
            out += Observation(
                key = "androidauto.package." + shortName(pkg),
                value = res.render(),
                status = when (res.state) {
                    "present" -> LabStatus.CONFIRMED
                    "absent" -> LabStatus.UNSUPPORTED
                    "not_visible" -> LabStatus.NOT_TESTED
                    else -> LabStatus.ERROR
                },
                note = when (res.state) {
                    "not_visible" ->
                        "API 30+ package-visibility filtering: the app has no <queries> entry for " +
                            pkg + ", so an installed package is indistinguishable from an absent one."
                    "absent" -> "PackageManager reported the package is not installed (API 29, no filtering)."
                    else -> pkg
                },
                category = LabCategory.ANDROID_AUTO,
            )
        }

        val anyPresent = lookups.values.any { it.state == "present" }
        val allFiltered = lookups.values.all { it.state == "not_visible" }
        val anyError = lookups.values.any { it.state.startsWith("error") }
        val installedValue = lookups.entries.joinToString("; ") { shortName(it.key) + "=" + it.value.render() }

        out += Observation(
            key = DashboardKeys.AA_INSTALLED,
            value = installedValue,
            status = when {
                anyPresent -> LabStatus.CONFIRMED
                allFiltered -> LabStatus.NOT_TESTED
                anyError -> LabStatus.ERROR
                else -> LabStatus.UNSUPPORTED
            },
            note = when {
                anyPresent -> "At least one Android Auto related package is visible to this app."
                allFiltered ->
                    "Every lookup returned NameNotFound while running on API " + Build.VERSION.SDK_INT +
                        ", where that is also what visibility filtering returns. Absence is NOT claimed. " +
                        "Adding a <queries> manifest entry would make this answerable."
                anyError -> "One or more lookups threw; see the per-package observations."
                else -> "PackageManager reported all three packages absent."
            },
            category = LabCategory.ANDROID_AUTO,
        )

        // ---------------------------------------------------------- car mode (the one hard signal)
        val uiModeType = LabEnv.uiModeType(context)
        val carMode = uiModeType == "CAR"
        out += Observation(
            key = "androidauto.uimode.current_mode_type",
            value = uiModeType,
            status = if (uiModeType.startsWith("error") || uiModeType == "no_uimode_service") {
                LabStatus.ERROR
            } else {
                LabStatus.CONFIRMED
            },
            note = "UiModeManager.getCurrentModeType(). UI_MODE_TYPE_CAR is the documented signal " +
                "that the device is in a car/projection dock session.",
            category = LabCategory.ANDROID_AUTO,
        )
        out += Observation.confirmed(
            "androidauto.uimode.config_car_bit",
            LabEnv.configCarBit(context),
            LabCategory.ANDROID_AUTO,
            "uiMode car bit of this context's own Configuration; it is resolved per context and can " +
                "disagree with the global UiModeManager answer.",
        )

        // Whether the car-mode broadcasts can be observed at all is itself a capability fact: if the
        // receiver cannot be registered, the matrix screen's ENTER/EXIT timeline is worthless.
        out += carModeReceiverProbe(context)

        // ---------------------------------------------------------- AAOS vs phone projection
        val automotive = LabEnv.automotiveFeature(context)
        out += Observation.confirmed(
            "androidauto.feature.automotive",
            automotive,
            LabCategory.ANDROID_AUTO,
            "PackageManager.hasSystemFeature. FEATURE_AUTOMOTIVE true would mean this IS the car " +
                "(Android Automotive OS), not a phone projecting into one.",
        )

        // ---------------------------------------------------------- circumstantial transport signals
        val usb = LabEnv.usbSummary(context)
        out += Observation.confirmed(
            "androidauto.usb.attached",
            usb,
            LabCategory.ANDROID_AUTO,
            "Wired Android Auto appears as a USB accessory/device. Vendor and product ids only; " +
                "serial numbers are deliberately not read.",
        )
        val wifi = LabEnv.wifiSummary(context)
        val freq = LabEnv.wifiFrequencyMhz(context)
        out += Observation.confirmed(
            "androidauto.wifi.link",
            wifi,
            LabCategory.ANDROID_AUTO,
            "Wireless Android Auto requires a 5 GHz capable link, so the band is the relevant fact.",
        )
        out += Observation.confirmed(
            "androidauto.wifi.band",
            LabEnv.band(freq),
            LabCategory.ANDROID_AUTO,
        )
        out += Observation.confirmed(
            "androidauto.p2p_interfaces",
            LabEnv.p2pInterfaces(),
            LabCategory.ANDROID_AUTO,
            "A live Wi-Fi Direct interface is shared ground between Miracast and wireless Android " +
                "Auto; its presence is evidence, not proof, of either.",
        )

        // ---------------------------------------------------------- the combined verdict
        val fiveGhz = LabEnv.band(freq) == "5GHz"
        val usbPresent = LabEnv.usbDeviceCount(context) > 0
        val evidence = "carMode=" + carMode +
            " uiModeType=" + uiModeType +
            " usbDevices=" + usbPresent +
            " link5GHz=" + fiveGhz +
            " automotive=[" + automotive + "]"

        out += if (carMode) {
            Observation(
                key = DashboardKeys.AA_PROJECTION,
                value = "car_mode_active :: " + evidence,
                status = LabStatus.CONFIRMED,
                note = "UiModeManager reports UI_MODE_TYPE_CAR. That is the platform's own " +
                    "authoritative statement that a car/projection session is in effect. It does " +
                    "not identify WHICH head unit or transport; that is recorded separately.",
                category = LabCategory.ANDROID_AUTO,
            )
        } else {
            Observation(
                key = DashboardKeys.AA_PROJECTION,
                value = "no_car_mode_signal :: " + evidence,
                status = LabStatus.INFERRED,
                note = "No UI_MODE_TYPE_CAR right now. The remaining signals are circumstantial: " +
                    "USB attachment and a 5 GHz link are equally consistent with a charger and a " +
                    "home router. Read as 'no projection session detected at this instant', not as " +
                    "'this device cannot project'.",
                category = LabCategory.ANDROID_AUTO,
            )
        }

        // ---------------------------------------------------------- what needs the Toyota
        // Spec section 3 asks four questions no API can answer from a desk. They stay NOT_TESTED
        // until a tester runs the section 9 matrix in the vehicle. No outcome is suggested here.
        out += Observation.notTested(
            "androidauto.coexistence_with_miracast",
            LabCategory.ANDROID_AUTO,
            "Requires the Toyota Mirai head unit: run matrix cases D (USB AA + Miracast) and F " +
                "(wireless AA + Miracast) on the Android Auto screen and record the result there.",
        )
        out += Observation.notTested(
            "androidauto.session_terminator",
            LabCategory.ANDROID_AUTO,
            "Which of the two sessions ends the other, if either does, can only be established by " +
                "starting them in both orders against a real head unit (matrix rows D and F).",
        )
        out += Observation.notTested(
            "androidauto.coexistence_varies_by_transport",
            LabCategory.ANDROID_AUTO,
            "Whether USB and wireless Android Auto behave differently alongside Miracast needs " +
                "matrix rows C/D compared against E/F on the vehicle.",
        )
        out += Observation.notTested(
            "androidauto.time_until_disconnect",
            LabCategory.ANDROID_AUTO,
            "Measured by the tester's timer on the Android Auto matrix screen during a vehicle test.",
        )

        return out
    }

    /**
     * Registers and immediately unregisters a car-mode receiver.
     *
     * The registration attempt is the test. A receiver that cannot be registered means the matrix
     * screen cannot timestamp ENTER/EXIT CAR MODE, which is worth knowing before driving anywhere.
     */
    private fun carModeReceiverProbe(context: Context): Observation {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) = Unit
        }
        return try {
            val filter = IntentFilter().apply {
                addAction(UiModeManager.ACTION_ENTER_CAR_MODE)
                addAction(UiModeManager.ACTION_EXIT_CAR_MODE)
            }
            ContextCompat.registerReceiver(
                context.applicationContext,
                receiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            context.applicationContext.unregisterReceiver(receiver)
            Observation.confirmed(
                "androidauto.carmode.broadcast_registrable",
                "true",
                LabCategory.ANDROID_AUTO,
                "ACTION_ENTER_CAR_MODE / ACTION_EXIT_CAR_MODE receiver registered and released. " +
                    "Registration succeeding does not mean the broadcasts will ever be sent here.",
            )
        } catch (t: Throwable) {
            runCatching { context.applicationContext.unregisterReceiver(receiver) }
            Observation.error("androidauto.carmode.broadcast_registrable", t, LabCategory.ANDROID_AUTO)
        }
    }

    private fun lookupPackage(pm: PackageManager, pkg: String): PkgResult = try {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, 0)
        }
        PkgResult("present", info.versionName ?: ("versionCode " + PackageInfoCompat.getLongVersionCode(info)))
    } catch (e: PackageManager.NameNotFoundException) {
        // On API 30+ this exception is returned both for "not installed" and for "installed but
        // filtered out of your view". Collapsing those two into UNSUPPORTED would be a fabricated
        // negative, which spec section 20 forbids.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            PkgResult("not_visible", null)
        } else {
            PkgResult("absent", null)
        }
    } catch (t: Throwable) {
        PkgResult("error: " + t::class.java.simpleName, null)
    }

    private fun shortName(pkg: String): String = when (pkg) {
        PKG_GEARHEAD -> "gearhead"
        PKG_AAOS_HOST -> "aaos_templates_host"
        PKG_GMS -> "play_services"
        else -> pkg
    }
}
