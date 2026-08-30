package nl.icthorse.miraicastlab.scan

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.FeatureInfo
import android.content.pm.PackageManager
import android.os.Build
import nl.icthorse.miraicastlab.core.DashboardKeys
import nl.icthorse.miraicastlab.core.LabCategory
import nl.icthorse.miraicastlab.core.LabPermissions
import nl.icthorse.miraicastlab.core.LabStatus
import nl.icthorse.miraicastlab.core.Observation
import nl.icthorse.miraicastlab.core.Probe
import java.io.File

/**
 * Device & platform inventory (spec sections 5.1 and 6).
 *
 * Everything here is read-only and comes straight off public platform APIs, with two deliberate
 * exceptions that are isolated and fully guarded:
 *  - the One UI version, which Samsung exposes only as a system property, and
 *  - Build.VERSION.SEM_PLATFORM_INT, a Samsung-only field.
 * Both are reflective, both degrade to UNSUPPORTED (property answered "not here") or ERROR
 * (we could not ask), never to a guess.
 */
object DeviceProbe : Probe {

    override val id = "device"
    override val title = "Device & platform"

    /**
     * Locations a superuser binary is customarily installed in. Absence proves little: SELinux
     * hides most of these from an unprivileged app even on a rooted device, which is exactly why a
     * negative root verdict is graded INFERRED below.
     */
    private val SU_PATHS = listOf(
        "/sbin/su",
        "/system/bin/su",
        "/system/xbin/su",
        "/system/sbin/su",
        "/system/sd/xbin/su",
        "/system/bin/failsafe/su",
        "/system_ext/bin/su",
        "/vendor/bin/su",
        "/odm/bin/su",
        "/product/bin/su",
        "/data/local/su",
        "/data/local/bin/su",
        "/data/local/xbin/su",
        "/su/bin/su",
        "/debug_ramdisk/su",
        "/apex/com.android.runtime/bin/su",
        "/magisk/.core/bin/su",
    )

    /** Manager apps that ship with the common rooting solutions. */
    private val ROOT_PACKAGES = listOf(
        "com.topjohnwu.magisk",
        "io.github.huskydg.magisk",
        "me.weishu.kernelsu",
        "eu.chainfire.supersu",
        "com.noshufou.android.su",
        "com.noshufou.android.su.elite",
        "com.koushikdutta.superuser",
        "com.thirdparty.superuser",
        "com.yellowes.su",
        "com.kingroot.kinguser",
        "com.kingo.root",
        "com.zhiqupk.root.global",
        "com.alephzain.framaroot",
        "com.ramdroid.appquarantine",
    )

    /**
     * Features worth an explicit yes/no, because a missing one directly kills a projection path
     * (spec section 6). PackageManager answering "false" is real evidence of absence, so those
     * become UNSUPPORTED rather than NOT_TESTED.
     */
    private val NAMED_FEATURES = listOf(
        PackageManager.FEATURE_WIFI to "wifi",
        PackageManager.FEATURE_WIFI_DIRECT to "wifi_direct",
        PackageManager.FEATURE_MIDI to "midi",
        PackageManager.FEATURE_USB_HOST to "usb_host",
        PackageManager.FEATURE_BLUETOOTH to "bluetooth",
        PackageManager.FEATURE_LEANBACK to "leanback",
        PackageManager.FEATURE_AUTOMOTIVE to "automotive",
        // Not requested by the spec but decisive for a Z Fold: a hinge sensor means the device
        // really is the foldable we think we are testing on.
        "android.hardware.sensor.hinge_angle" to "hinge_angle_sensor",
        PackageManager.FEATURE_USB_ACCESSORY to "usb_accessory",
        PackageManager.FEATURE_TOUCHSCREEN to "touchscreen",
    )

    override suspend fun observe(context: Context): List<Observation> {
        val out = mutableListOf<Observation>()
        out += buildFacts()
        out += oneUiVersion()
        out += rootEvidence(context)
        out += ownAppFacts(context)
        out += features(context)
        out += LabPermissions.observe(context)
        return out
    }

    // ---------------------------------------------------------------- android.os.Build

    private fun buildFacts(): List<Observation> {
        val c = LabCategory.DEVICE
        val out = mutableListOf<Observation>()

        // Dashboard contract keys first (DashboardKeys.missingFrom reports on exactly these).
        out += Observation.confirmed(DashboardKeys.MANUFACTURER, Build.MANUFACTURER, c)
        out += Observation.confirmed(DashboardKeys.MODEL, Build.MODEL, c)
        out += Observation.confirmed(DashboardKeys.ANDROID_RELEASE, Build.VERSION.RELEASE, c)
        out += Observation.confirmed(DashboardKeys.SDK_INT, Build.VERSION.SDK_INT, c)
        out += Observation.confirmed(DashboardKeys.FINGERPRINT, Build.FINGERPRINT, c)

        val samsung = Build.MANUFACTURER.equals("samsung", ignoreCase = true) ||
            Build.BRAND.equals("samsung", ignoreCase = true)
        out += Observation.confirmed(
            DashboardKeys.IS_SAMSUNG,
            samsung,
            c,
            "Derived from Build.MANUFACTURER=" + Build.MANUFACTURER + " / Build.BRAND=" + Build.BRAND + ".",
        )

        out += Observation.confirmed("device.brand", Build.BRAND, c)
        out += Observation.confirmed("device.device", Build.DEVICE, c)
        out += Observation.confirmed("device.product", Build.PRODUCT, c)
        out += Observation.confirmed("device.hardware", Build.HARDWARE, c)
        out += Observation.confirmed("device.board", Build.BOARD, c)
        out += Observation.confirmed("device.bootloader", Build.BOOTLOADER, c)
        out += Observation.confirmed("device.build_display", Build.DISPLAY, c)
        out += Observation.confirmed("device.build_id", Build.ID, c)
        out += Observation.confirmed("device.build_tags", Build.TAGS, c)
        out += Observation.confirmed("device.build_type", Build.TYPE, c)
        out += Observation.confirmed("device.build_time_epoch_ms", Build.TIME, c)
        out += Observation.confirmed(
            "device.supported_abis",
            Build.SUPPORTED_ABIS.joinToString(", "),
            c,
        )
        out += Observation.confirmed("device.version_incremental", Build.VERSION.INCREMENTAL, c)
        out += Observation.confirmed("device.version_codename", Build.VERSION.CODENAME, c)
        out += Observation.confirmed("device.version_security_patch", Build.VERSION.SECURITY_PATCH, c)
        out += Observation.confirmed("device.version_base_os", Build.VERSION.BASE_OS, c)
        out += Observation.confirmed("device.version_preview_sdk_int", Build.VERSION.PREVIEW_SDK_INT, c)

        // SOC_MANUFACTURER / SOC_MODEL are API 31 fields; reading them below that is a
        // NoSuchFieldError, so the version gate is load-bearing, not decoration.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            out += Observation.confirmed("device.soc_manufacturer", Build.SOC_MANUFACTURER, c)
            out += Observation.confirmed("device.soc_model", Build.SOC_MODEL, c)
        } else {
            out += Observation.notTested(
                "device.soc_manufacturer",
                c,
                "Build.SOC_MANUFACTURER exists only from API 31; this device runs API " +
                    Build.VERSION.SDK_INT + ".",
            )
        }

        // Build.SKU / Build.ODM_SKU are API 31 fields.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            out += guarded("device.sku", c) { Build.SKU }
            out += guarded("device.odm_sku", c) { Build.ODM_SKU }
        }

        return out
    }

    // ---------------------------------------------------------------- One UI

    /** Outcome of one reflective lookup: a value, an explicit empty answer, or a failure to ask. */
    private sealed class Lookup {
        class Value(val v: String) : Lookup()
        object Empty : Lookup()
        class Failed(val t: Throwable) : Lookup()
    }

    private fun systemProperty(key: String): Lookup = try {
        val cls = Class.forName("android.os.SystemProperties")
        val get = cls.getMethod("get", String::class.java, String::class.java)
        val v = get.invoke(null, key, "") as? String
        if (v.isNullOrBlank()) Lookup.Empty else Lookup.Value(v)
    } catch (t: Throwable) {
        Lookup.Failed(t)
    }

    private fun semPlatformInt(): Lookup = try {
        val f = Build.VERSION::class.java.getField("SEM_PLATFORM_INT")
        Lookup.Value(f.getInt(null).toString())
    } catch (t: Throwable) {
        // NoSuchFieldException on any non-Samsung ROM: an answer, not a malfunction.
        if (t is NoSuchFieldException) Lookup.Empty else Lookup.Failed(t)
    }

    /**
     * Renders Samsung's packed One UI integer: major*10000 + minor*100 + patch, so 60101 is 6.1.1.
     * The two sources use different bases, hence [SEM_BASE] rather than one guessing function -
     * a One UI 9 property (90000) and a SEM value for One UI 0 would otherwise be indistinguishable.
     */
    private fun formatPackedOneUi(packed: Int): String? {
        if (packed <= 0) return null
        val major = packed / 10000
        if (major <= 0) return null
        val minor = (packed % 10000) / 100
        val patch = packed % 100
        return if (patch == 0) major.toString() + "." + minor
        else major.toString() + "." + minor + "." + patch
    }

    /** SEM_PLATFORM_INT is the packed One UI number offset by this constant. */
    private const val SEM_BASE = 90000

    /** Accepts both the packed integer form and the rare dotted-string form of the property. */
    private fun decodeOneUiProperty(raw: String): String? {
        val t = raw.trim()
        if (t.contains('.')) {
            return if (t.matches(Regex("\\d+(\\.\\d+)+"))) t else null
        }
        val n = t.toIntOrNull() ?: return null
        return formatPackedOneUi(n)
    }

    private fun oneUiVersion(): List<Observation> {
        val c = LabCategory.DEVICE
        val out = mutableListOf<Observation>()
        val tried = mutableListOf<String>()

        val prop = systemProperty("ro.build.version.oneui")
        when (prop) {
            is Lookup.Value -> {
                out += Observation.confirmed("device.one_ui_raw_property", prop.v, c,
                    "ro.build.version.oneui, read via android.os.SystemProperties.")
                val pretty = decodeOneUiProperty(prop.v)
                if (pretty != null) {
                    return out + Observation.confirmed(
                        DashboardKeys.ONE_UI, pretty, c,
                        "Decoded from ro.build.version.oneui=" + prop.v + ".",
                    )
                }
                tried += "ro.build.version.oneui returned '" + prop.v + "' which did not decode"
            }
            is Lookup.Empty -> tried += "ro.build.version.oneui was empty"
            is Lookup.Failed -> tried += "ro.build.version.oneui lookup threw " +
                prop.t::class.java.simpleName
        }

        val sem = semPlatformInt()
        when (sem) {
            is Lookup.Value -> {
                out += Observation.confirmed("device.sem_platform_int", sem.v, c,
                    "Samsung-only field android.os.Build.VERSION.SEM_PLATFORM_INT.")
                val pretty = sem.v.toIntOrNull()
                    ?.let { if (it > SEM_BASE) formatPackedOneUi(it - SEM_BASE) else null }
                if (pretty != null) {
                    return out + Observation(
                        DashboardKeys.ONE_UI,
                        pretty,
                        LabStatus.INFERRED,
                        "Derived from SEM_PLATFORM_INT=" + sem.v +
                            " using the observed 90000 + version*10000 packing; Samsung publishes " +
                            "no contract for this field, so the decode is inferred, not confirmed.",
                        c,
                    )
                }
                tried += "SEM_PLATFORM_INT=" + sem.v + " did not decode"
            }
            is Lookup.Empty -> tried += "Build.VERSION.SEM_PLATFORM_INT is absent"
            is Lookup.Failed -> tried += "SEM_PLATFORM_INT lookup threw " +
                sem.t::class.java.simpleName
        }

        val sem2 = systemProperty("ro.build.version.sem")
        when (sem2) {
            is Lookup.Value -> {
                out += Observation.confirmed("device.ro_build_version_sem", sem2.v, c)
                tried += "ro.build.version.sem=" + sem2.v + " (platform int, not a One UI number)"
            }
            is Lookup.Empty -> tried += "ro.build.version.sem was empty"
            is Lookup.Failed -> tried += "ro.build.version.sem lookup threw " +
                sem2.t::class.java.simpleName
        }

        // Last resort: Samsung stamps the build increment, e.g. "F926BXXU1AUH4". This never
        // yields a One UI number, so it is recorded as context only and the verdict stays negative.
        tried += "Build.VERSION.INCREMENTAL='" + Build.VERSION.INCREMENTAL +
            "' carries no One UI number"

        val samsung = Build.MANUFACTURER.equals("samsung", ignoreCase = true)
        out += Observation(
            DashboardKeys.ONE_UI,
            "absent",
            LabStatus.UNSUPPORTED,
            "No One UI version could be resolved. Tried: " + tried.joinToString("; ") + ". " +
                (
                    if (samsung) {
                        "This is a Samsung device, so an absent value more likely means the " +
                            "reflective route is blocked than that One UI is missing."
                    } else {
                        "This is not a Samsung device, so an absent One UI version is the " +
                            "expected result."
                    }
                    ),
            c,
        )
        return out
    }

    // ---------------------------------------------------------------- root

    private fun rootEvidence(context: Context): List<Observation> {
        val c = LabCategory.DEVICE
        val out = mutableListOf<Observation>()
        val positives = mutableListOf<String>()
        val inconclusive = mutableListOf<String>()

        // 1. su binaries.
        val found = mutableListOf<String>()
        val pathFailure: Throwable? = try {
            SU_PATHS.forEach { p -> if (File(p).exists()) found += p }
            null
        } catch (t: Throwable) {
            t
        }
        when {
            pathFailure != null ->
                out += Observation.error("device.root.su_paths", pathFailure, c)
            found.isNotEmpty() -> {
                out += Observation.confirmed("device.root.su_paths", found.joinToString(", "), c,
                    "A file named 'su' exists at these paths.")
                positives += "su binary at " + found.joinToString(", ")
            }
            else -> {
                out += Observation(
                    "device.root.su_paths", "none of " + SU_PATHS.size + " paths",
                    LabStatus.INFERRED,
                    "SELinux hides most system paths from an unprivileged app, so 'no su found' is " +
                        "weak evidence.",
                    c,
                )
                inconclusive += "no su binary visible in " + SU_PATHS.size + " known paths"
            }
        }

        // 2. Build tags. "test-keys" means the ROM was not signed with the vendor release key.
        val testKeys = Build.TAGS?.contains("test-keys") == true
        out += Observation.confirmed("device.root.test_keys", testKeys, c,
            "Build.TAGS=" + Build.TAGS + ". 'test-keys' indicates a non-release-signed build.")
        if (testKeys) positives += "Build.TAGS contains test-keys"

        // 3. Root manager packages. Without a <queries> element this app cannot see other
        //    packages at all on API 30+, so we first measure whether we can see anything.
        val pm = context.packageManager
        val visibilityProbe = try {
            pm.getPackageInfo("com.android.settings", 0)
            true
        } catch (t: Throwable) {
            false
        }
        out += Observation(
            "device.package_visibility",
            if (visibilityProbe) "unfiltered" else "filtered",
            LabStatus.INFERRED,
            if (visibilityProbe) {
                "com.android.settings is visible, so package queries return meaningful answers."
            } else {
                "com.android.settings is not visible to this app. Android package-visibility " +
                    "filtering (API 30+, no <queries> element declared) is in effect, so 'package " +
                    "not found' cannot be read as 'package not installed'."
            },
            c,
        )
        val rootPkgs = mutableListOf<String>()
        ROOT_PACKAGES.forEach { p ->
            try {
                pm.getPackageInfo(p, 0)
                rootPkgs += p
            } catch (t: Throwable) {
                // Not installed, or filtered out. Indistinguishable from here; see above.
            }
        }
        if (rootPkgs.isNotEmpty()) {
            out += Observation.confirmed("device.root.packages", rootPkgs.joinToString(", "), c,
                "Known root manager packages that PackageManager could resolve.")
            positives += "root manager package " + rootPkgs.joinToString(", ")
        } else {
            out += Observation(
                "device.root.packages", "none visible",
                if (visibilityProbe) LabStatus.INFERRED else LabStatus.NOT_TESTED,
                if (visibilityProbe) {
                    "None of " + ROOT_PACKAGES.size + " known root managers resolved."
                } else {
                    "Package visibility is filtered, so this check could not run at all. " +
                        "This is NOT evidence that no root manager is installed."
                },
                c,
            )
            if (!visibilityProbe) inconclusive += "root package check blocked by package visibility"
        }

        // 4. Debuggable/secure system properties: context, not proof.
        val roDebuggable = systemProperty("ro.debuggable")
        if (roDebuggable is Lookup.Value) {
            out += Observation.confirmed("device.root.ro_debuggable", roDebuggable.v, c,
                "1 means the ROM allows debugging of any app; typical of engineering builds.")
            if (roDebuggable.v.trim() == "1") positives += "ro.debuggable=1"
        }
        val roSecure = systemProperty("ro.secure")
        if (roSecure is Lookup.Value) {
            out += Observation.confirmed("device.root.ro_secure", roSecure.v, c,
                "0 means adbd runs as root on this build.")
            if (roSecure.v.trim() == "0") positives += "ro.secure=0"
        }

        // Verdict. Positive evidence is still INFERRED: we never execute su, which would change
        // device state and prompt the user, so root was never actually exercised.
        val verdict = when {
            positives.isNotEmpty() -> Observation(
                DashboardKeys.ROOT, "YES", LabStatus.INFERRED,
                "Root artefacts present (" + positives.joinToString("; ") + "). The lab never " +
                    "executes su, so elevated access was not actually exercised.",
                c,
            )
            inconclusive.size >= 2 -> Observation(
                DashboardKeys.ROOT, "UNKNOWN", LabStatus.NOT_TESTED,
                "Every root check was blocked or inconclusive: " + inconclusive.joinToString("; ") + ".",
                c,
            )
            else -> Observation(
                DashboardKeys.ROOT, "NO", LabStatus.INFERRED,
                "No root artefact was visible. Absence of evidence is not evidence of absence: an " +
                    "unprivileged app cannot see most of the filesystem, and modern root solutions " +
                    "hide themselves from exactly these checks.",
                c,
            )
        }
        return out + verdict
    }

    // ---------------------------------------------------------------- own app

    private fun ownAppFacts(context: Context): List<Observation> {
        val c = LabCategory.DEVICE
        val out = mutableListOf<Observation>()
        val ai: ApplicationInfo = context.applicationInfo

        out += Observation.confirmed("app.application_id", context.packageName, c,
            "The debug build carries a .debug suffix; useful when two builds are side by side.")
        out += Observation.confirmed(
            "app.debuggable",
            (ai.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0,
            c,
            "A debuggable build is reachable over adb without a developer-options toggle.",
        )
        out += Observation.confirmed("app.target_sdk", ai.targetSdkVersion, c)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            out += guarded("app.min_sdk", c) { ai.minSdkVersion }
        }
        out += guarded("app.version_name", c) {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }
        out += guarded("app.version_code", c) {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        }
        out += Observation.confirmed(
            "app.has_internet_permission",
            LabPermissions.isGranted(context, android.Manifest.permission.INTERNET),
            c,
            "Must stay false: MiraiCast Lab declares no INTERNET permission and uploads nothing.",
        )
        return out
    }

    // ---------------------------------------------------------------- PackageManager features

    private fun features(context: Context): List<Observation> {
        val c = LabCategory.DEVICE
        val out = mutableListOf<Observation>()
        val pm = context.packageManager

        // The explicit type keeps the empty-array fallback inferable.
        val all: Array<FeatureInfo> = try {
            pm.systemAvailableFeatures
        } catch (t: Throwable) {
            out += Observation.error("device.features", t, c)
            emptyArray()
        }
        val names = all.mapNotNull { it.name }.sorted()
        val glEsVersions = all.filter { it.name == null }.map { it.glEsVersion }

        out += Observation.confirmed("device.features.count", all.size, c)
        out += Observation.confirmed("device.features.all", names.joinToString(", "), c,
            "Full PackageManager.getSystemAvailableFeatures() list, alphabetical.")
        if (glEsVersions.isNotEmpty()) {
            out += Observation.confirmed("device.features.opengl_es_version",
                glEsVersions.joinToString(", "), c,
                "The unnamed FeatureInfo entry carries the OpenGL ES version.")
        }

        NAMED_FEATURES.forEach { (feature, short) ->
            val key = "device.feature." + short
            try {
                if (pm.hasSystemFeature(feature)) {
                    val version = all.firstOrNull { it.name == feature }?.version ?: 0
                    out += Observation.confirmed(
                        key,
                        if (version != 0) "present (version " + version + ")" else "present",
                        c,
                        feature,
                    )
                } else {
                    // PackageManager gave a definite negative: that is UNSUPPORTED, not NOT_TESTED.
                    out += Observation.unsupported(key, c,
                        "PackageManager.hasSystemFeature(" + feature + ") returned false.")
                }
            } catch (t: Throwable) {
                out += Observation.error(key, t, c)
            }
        }
        return out
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Reads one value that may not exist on this platform build. A throw is ERROR ("we could not
     * ask"), a null is UNSUPPORTED ("the platform answered: nothing here").
     */
    private inline fun guarded(
        key: String,
        category: LabCategory,
        note: String? = null,
        block: () -> Any?,
    ): Observation = try {
        val v = block()
        if (v == null) Observation.unsupported(key, category, note ?: "Platform returned null.")
        else Observation.confirmed(key, v, category, note)
    } catch (t: Throwable) {
        Observation.error(key, t, category)
    }
}
