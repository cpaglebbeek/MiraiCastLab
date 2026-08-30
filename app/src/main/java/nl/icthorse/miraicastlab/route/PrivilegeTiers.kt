package nl.icthorse.miraicastlab.route

import nl.icthorse.miraicastlab.core.LabCategory
import nl.icthorse.miraicastlab.core.LabStatus
import nl.icthorse.miraicastlab.core.Observation
import nl.icthorse.miraicastlab.core.PrivilegeTier
import nl.icthorse.miraicastlab.core.SessionLogger

/**
 * The capability x privilege-tier matrix.
 *
 * **This file is documentation, not measurement.** Every row below states the lowest tier at which
 * the *documented* Android or Samsung contract says a capability is reachable, and the concrete
 * mechanism that reaches it. That is a claim about the platform's published behaviour, and it is
 * emphatically not a claim about a Galaxy Z Fold connected to a Toyota Mirai. Documented rows
 * therefore carry `measured = false` and [LabStatus.NOT_TESTED]: nothing here has been run on this
 * device by this object.
 *
 * Measurements arrive from somewhere else. [rowsWith] folds outcomes from the route experiments
 * into the matrix and flips the affected rows to `measured = true` with whatever status the run
 * actually produced. It is a pure function - it returns a new list and mutates nothing - so it can
 * be unit-tested without an Android device, and so a report can never accidentally accumulate
 * "measurements" across runs.
 *
 * ### Why a matrix at all
 * Because the interesting answers in this project are not "yes" or "no" but "not at this tier". A
 * capability that is impossible for an ordinary app and trivial from `adb shell` is a completely
 * different situation from one that is impossible everywhere, and collapsing the two would hide the
 * only actionable finding this research can produce.
 *
 * ### Scope, per SAFETY.md
 * The elevated rows are **analysis only**. `ADB_SHELL` rows describe documented commands a developer
 * runs against their own device from a workstation with developer options enabled. `ROOT` and
 * `CUSTOM_ROM` rows exist to state where a boundary lies, and this repository contains no exploit,
 * no rooting procedure and no ROM modification steps - nor may it ever. Nothing in this file
 * touches a vehicle bus, and nothing in it circumvents a safety interlock.
 */
object PrivilegeTiers {

    // ------------------------------------------------------------------ capability keys
    // Exposed as constants so callers and tests address rows by identity rather than by retyping a
    // string that might drift.

    const val CAP_ENUMERATE_DISPLAYS = "enumerate displays"
    const val CAP_DETECT_REMOTE_DISPLAY = "detect that a display is remote (name its transport)"
    const val CAP_OWN_ACTIVITY_ON_DISPLAY = "put OWN activity on a display"
    const val CAP_PRESENTATION_ON_DISPLAY = "show a Presentation on a display"
    const val CAP_LAUNCH_OTHER_APP_ON_DISPLAY = "launch ANOTHER app on a display"
    const val CAP_ENUMERATE_PACKAGES = "enumerate all installed packages"
    const val CAP_INITIATE_MIRACAST = "initiate a Miracast session"
    const val CAP_READ_NEGOTIATED_VIDEO = "read negotiated resolution / codec / HDCP"
    const val CAP_READ_UIBC = "read the UIBC advertisement"
    const val CAP_INJECT_INPUT = "inject input events"
    const val CAP_TEARDOWN_WIFI_DISPLAY = "tear down a system Wi-Fi Display route"
    const val CAP_READ_DEX_MODE = "read DeX mode"
    const val CAP_MIRROR_OWN_SCREEN = "capture and encode the screen for our own transport"
    const val CAP_KNOW_DISPLAY_OWNER = "know which app or subsystem owns a display"
    const val CAP_OPEN_CAST_PICKER = "open the system cast / Smart View picker"

    /**
     * One cell of the matrix.
     *
     * @param capability what is being asked for, one of the `CAP_*` constants.
     * @param tier       the LOWEST tier at which the documented mechanism becomes available.
     * @param mechanism  the concrete API, permission or shell command. Named so a reader can look it
     *                   up and disagree.
     * @param status     evidence grade. Documented rows are NOT_TESTED by construction.
     * @param note       why this row reads the way it does, and the source it came from.
     * @param measured   false = platform documentation; true = a value this project actually observed
     *                   on a device during a run.
     */
    data class TierRow(
        val capability: String,
        val tier: PrivilegeTier,
        val mechanism: String,
        val status: LabStatus,
        val note: String,
        val measured: Boolean,
    ) {
        /** Short stable key segment for logs and reports, e.g. "launch_another_app_on_a_display". */
        val key: String
            get() = capability.lowercase()
                .replace(Regex("[^a-z0-9]+"), "_")
                .trim('_')

        val provenance: String get() = if (measured) "MEASURED" else "DOCUMENTED"

        fun oneLine(): String = provenance + " | " + capability + " -> " + tier.label +
            " | " + mechanism + " | " + status.name
    }

    // ------------------------------------------------------------------ the matrix

    /**
     * The documented baseline. Order is deliberate: it runs from what any app can do, through what
     * the research question actually needs, to what only a system image can do.
     */
    val rows: List<TierRow> = listOf(
        TierRow(
            capability = CAP_ENUMERATE_DISPLAYS,
            tier = PrivilegeTier.ORDINARY_APP,
            mechanism = "DisplayManager.getDisplays() and " +
                "getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION); Display.getFlags()",
            status = LabStatus.NOT_TESTED,
            note = "Source: android.hardware.display.DisplayManager, public since API 17, no " +
                "permission. Documented as available to any app. Whether any non-default display is " +
                "listed on this phone while DeX runs is measured by the display probe and by route C, " +
                "not here.",
            measured = false,
        ),
        TierRow(
            capability = CAP_DETECT_REMOTE_DISPLAY,
            tier = PrivilegeTier.ADB_SHELL,
            mechanism = "adb shell dumpsys display (DisplayDeviceInfo carries the device type, e.g. " +
                "WIFI, and the underlying flags)",
            status = LabStatus.NOT_TESTED,
            note = "Source: the public Display API exposes no transport field. An ordinary app has " +
                "only heuristics - FLAG_PRESENTATION, DISPLAY_CATEGORY_PRESENTATION, " +
                "DeviceProductInfo.connectionToSinkType (API 30+) and the display name - which is why " +
                "route/DisplayRole never grades a remote verdict above INFERRED. The shell dump is " +
                "the lowest tier that NAMES the transport rather than guessing at it.",
            measured = false,
        ),
        TierRow(
            capability = CAP_OWN_ACTIVITY_ON_DISPLAY,
            tier = PrivilegeTier.ORDINARY_APP,
            mechanism = "ActivityOptions.makeBasic().setLaunchDisplayId(id).toBundle() passed to " +
                "Context.startActivity(intent, options)",
            status = LabStatus.NOT_TESTED,
            note = "Source: android.app.ActivityOptions#setLaunchDisplayId, public since API 26. The " +
                "documentation describes it as a REQUEST: the platform may place the activity " +
                "elsewhere, and it raises nothing when it does. Experiment C1 measures whether the " +
                "request is honoured on this build, and C4 is its control.",
            measured = false,
        ),
        TierRow(
            capability = CAP_PRESENTATION_ON_DISPLAY,
            tier = PrivilegeTier.ORDINARY_APP,
            mechanism = "android.app.Presentation(activityContext, display).show()",
            status = LabStatus.NOT_TESTED,
            note = "Source: android.app.Presentation, public since API 17. Documented to require an " +
                "Activity (window-token-bearing) context, and to throw " +
                "WindowManager.InvalidDisplayException when the display is removed or refused. " +
                "Experiment C2 measures it; a private display owned by another uid is the documented " +
                "case in which it is refused.",
            measured = false,
        ),
        TierRow(
            capability = CAP_LAUNCH_OTHER_APP_ON_DISPLAY,
            tier = PrivilegeTier.ADB_SHELL,
            mechanism = "adb shell am start --display <id> -n <package>/<activity>",
            status = LabStatus.NOT_TESTED,
            note = "Source: the `am start --display` option, documented in the activity manager shell " +
                "help. This row states the lowest tier at which the platform DOCUMENTS the operation " +
                "as available; it does not state that an ordinary app cannot do it. AOSP's " +
                "launch-on-display path consults, among others, ACTIVITY_EMBEDDING and " +
                "INTERNAL_SYSTEM_WINDOW (both signature/privileged) and refuses with a " +
                "SecurityException naming the calling uid and the display. Whether an ordinary app is " +
                "refused, silently redirected, or honoured on THIS build is exactly what experiments " +
                "D2 and D3 measure, and this file does not answer it in advance.",
            measured = false,
        ),
        TierRow(
            capability = CAP_ENUMERATE_PACKAGES,
            tier = PrivilegeTier.ORDINARY_APP,
            mechanism = "<uses-permission android:name=\"android.permission.QUERY_ALL_PACKAGES\"/>, " +
                "then PackageManager.getInstalledPackages(); adb shell pm list packages is the " +
                "equivalent at the shell tier",
            status = LabStatus.NOT_TESTED,
            note = "Source: Android 11 package-visibility documentation. The permission is normal, " +
                "not signature - so the lowest enabling tier really is ORDINARY_APP - but it is " +
                "policy-restricted on Google Play and THIS APP DELIBERATELY DOES NOT DECLARE IT. What " +
                "we can see instead comes from the manifest <queries> element, which names specific " +
                "packages plus a MAIN/LAUNCHER intent. Experiment D1 measures the resulting visible " +
                "count, and grades 'which apps are installed' NOT_TESTED because a filtered list " +
                "cannot answer it.",
            measured = false,
        ),
        TierRow(
            capability = CAP_OPEN_CAST_PICKER,
            tier = PrivilegeTier.ORDINARY_APP,
            mechanism = "startActivity(Intent(\"android.settings.CAST_SETTINGS\")), " +
                "\"android.settings.WIFI_DISPLAY_SETTINGS\", or Samsung's " +
                "\"com.samsung.wfd.LAUNCH_WFD_PICKER\"",
            status = LabStatus.NOT_TESTED,
            note = "Source: android.provider.Settings action constants; the Samsung action is " +
                "proprietary and undocumented, hence guarded and expected to be absent on non-Samsung " +
                "builds. Opening a picker is NOT initiating a session: the user still taps the sink, " +
                "and the app is never told what they chose. Route A and the Miracast wizard measure " +
                "which of these actions resolve on this device.",
            measured = false,
        ),
        TierRow(
            capability = CAP_INITIATE_MIRACAST,
            tier = PrivilegeTier.CUSTOM_ROM,
            mechanism = "DisplayManager.connectWifiDisplay(String address) with " +
                "android.permission.CONFIGURE_WIFI_DISPLAY - hidden/system API, signature|privileged " +
                "permission, so it requires a platform-signed or preinstalled app",
            status = LabStatus.NOT_TESTED,
            note = "Source: AOSP DisplayManager (@hide/@SystemApi) and its permission declaration. " +
                "There is no public API by which an app starts a Wi-Fi Display session on its own " +
                "authority; MediaRouter selects among routes the system already offers and cannot " +
                "create one. An app CAN speak the Wi-Fi Display protocol itself over Wi-Fi P2P " +
                "(route A2) - that is a different capability, listed separately, and does not use " +
                "this API.",
            measured = false,
        ),
        TierRow(
            capability = CAP_TEARDOWN_WIFI_DISPLAY,
            tier = PrivilegeTier.CUSTOM_ROM,
            mechanism = "DisplayManager.disconnectWifiDisplay() with CONFIGURE_WIFI_DISPLAY " +
                "(signature|privileged)",
            status = LabStatus.NOT_TESTED,
            note = "Source: as above - the teardown counterpart of connectWifiDisplay carries the same " +
                "permission. At the ORDINARY_APP tier an app can drop only routing it established " +
                "itself; it cannot end a session the system or Samsung owns. At the ADB_SHELL tier " +
                "`am force-stop <casting package>` is a documented command whose effect on a live " +
                "session is unknown and is not claimed here. This project has no reason to end a " +
                "session it did not start, and does not.",
            measured = false,
        ),
        TierRow(
            capability = CAP_READ_NEGOTIATED_VIDEO,
            tier = PrivilegeTier.ADB_SHELL,
            mechanism = "adb shell dumpsys display; adb logcat -s WifiDisplayController " +
                "(the system's RTSP M1-M7 negotiation is logged there on AOSP-derived builds)",
            status = LabStatus.NOT_TESTED,
            note = "Source: no public API reports the negotiated Wi-Fi Display parameters. An app can " +
                "read Display.getMode() / getSupportedModes() IF the sink surfaces as a Display, but " +
                "that is the display's mode, not the codec profile, bitrate or HDCP state that the " +
                "RTSP capability exchange settled on. READ_LOGS is signature-level, so an app cannot " +
                "read the system's log itself. HDCP state in particular may not be exposed at ANY " +
                "tier below the platform image; that possibility is stated, not resolved.",
            measured = false,
        ),
        TierRow(
            capability = CAP_READ_UIBC,
            tier = PrivilegeTier.ADB_SHELL,
            mechanism = "adb logcat -s WifiDisplayController (the wfd_uibc_capability line of the " +
                "RTSP M3/M4 exchange), or route A2 where the app negotiates the session itself",
            status = LabStatus.NOT_TESTED,
            note = "Source: Wi-Fi Alliance Display specification, section on the User Input Back " +
                "Channel; Android exposes no API for it. When the app is itself the sender (route A2) " +
                "it reads the advertisement out of its own RTSP session at ORDINARY_APP tier - but " +
                "that is a session it created, and says nothing about a session Samsung created. " +
                "Whether the Mirai advertises UIBC at all is unknown and is one of the questions this " +
                "project exists to answer, not one it presumes.",
            measured = false,
        ),
        TierRow(
            capability = CAP_INJECT_INPUT,
            tier = PrivilegeTier.ADB_SHELL,
            mechanism = "adb shell input tap <x> <y> / adb shell input keyevent <code>; the same " +
                "uid-2000 capability is what Shizuku exposes without a cable",
            status = LabStatus.NOT_TESTED,
            note = "Source: InputManager.injectInputEvent requires android.permission.INJECT_EVENTS, " +
                "which is signature-level; the shell uid holds it, which is why the `input` command " +
                "works. An app can dispatch events only into its own windows. An AccessibilityService " +
                "can dispatch gestures system-wide, and is named here for completeness only: " +
                "SAFETY.md rules out using accessibility as a workaround, so this project does not " +
                "take that path. Nothing in this row is about sending input INTO the vehicle.",
            measured = false,
        ),
        TierRow(
            capability = CAP_READ_DEX_MODE,
            tier = PrivilegeTier.SAMSUNG_API,
            mechanism = "android.content.res.Configuration.semDesktopModeEnabled, read reflectively " +
                "and compared with Configuration.SEM_DESKTOP_MODE_ENABLED",
            status = LabStatus.NOT_TESTED,
            note = "Source: Samsung DeX developer documentation; the field is proprietary and absent " +
                "on non-Samsung builds, so the read is guarded per field and degrades to UNSUPPORTED " +
                "rather than throwing. At ORDINARY_APP tier only indirect proxies exist - " +
                "Configuration.uiMode, screen metrics, the appearance of a second display - and none " +
                "of them distinguishes Wireless DeX from ordinary Smart View mirroring, which is " +
                "forbidden assumption #1 and a route B measurement.",
            measured = false,
        ),
        TierRow(
            capability = CAP_MIRROR_OWN_SCREEN,
            tier = PrivilegeTier.ORDINARY_APP,
            mechanism = "MediaProjectionManager.createScreenCaptureIntent() + user consent, then " +
                "VirtualDisplay -> MediaCodec (a foreground service of type mediaProjection is " +
                "required from API 29)",
            status = LabStatus.NOT_TESTED,
            note = "Source: android.media.projection, public since API 21; the foreground-service-type " +
                "requirement is documented from API 29. This capability is about producing an encoded " +
                "stream, NOT about delivering it: there is no public transport to a Miracast sink, " +
                "which is precisely the gap route A exists to characterise. Consent is per-session " +
                "and cannot be pre-granted by an app.",
            measured = false,
        ),
        TierRow(
            capability = CAP_KNOW_DISPLAY_OWNER,
            tier = PrivilegeTier.ADB_SHELL,
            mechanism = "adb shell dumpsys display / dumpsys activity displays (which lists each " +
                "display's stacks and their owning packages)",
            status = LabStatus.NOT_TESTED,
            note = "Source: ActivityManager.getRunningTasks has returned only the caller's own tasks " +
                "since API 21 and getRunningAppProcesses only the caller's own process since API 22, " +
                "so an ordinary app cannot see what occupies a display it did not create. That " +
                "restriction is the direct reason route D combines its API reading with a human " +
                "tester's sighting instead of querying the system.",
            measured = false,
        ),
    )

    // ------------------------------------------------------------------ queries

    /**
     * The lowest documented tier at which [capability] becomes available, or null when the matrix
     * has no row for it. Matching is exact first, then case-insensitive substring, so both a
     * `CAP_*` constant and a hand-typed fragment resolve.
     */
    fun lowestTierFor(capability: String): PrivilegeTier? {
        rows.firstOrNull { it.capability == capability }?.let { return it.tier }
        val needle = capability.trim().lowercase()
        if (needle.isEmpty()) return null
        return rows.firstOrNull {
            it.capability.lowercase().contains(needle) || needle.contains(it.capability.lowercase())
        }?.tier
    }

    /** All rows reachable without leaving the tier the primary build runs at. */
    fun ordinaryAppRows(): List<TierRow> = rows.filter { it.tier == PrivilegeTier.ORDINARY_APP }

    /** All rows the primary build cannot reach. These are the findings that need a different tier. */
    fun elevatedRows(): List<TierRow> = rows.filter { it.tier != PrivilegeTier.ORDINARY_APP }

    /** Rows grouped by tier, in tier order, for a report table. */
    fun byTier(): Map<PrivilegeTier, List<TierRow>> =
        PrivilegeTier.entries.associateWith { tier -> rows.filter { it.tier == tier } }
            .filterValues { it.isNotEmpty() }

    /**
     * Folds real measurements into the documented matrix.
     *
     * Pure: it returns a new list and changes nothing. A capability present in [measurements] gets
     * that status, the supplied note appended to the documented one, and `measured = true`; every
     * other row is returned untouched and still NOT_TESTED.
     *
     * The tier is deliberately NOT recomputed from a measurement. Observing that an ordinary app
     * could not do something does not establish that the lowest enabling tier is higher - it may be
     * this device, this display, this build or this moment - so the documented tier stands and the
     * status carries the news.
     *
     * @param measurements capability key -> (status, what was actually observed)
     */
    fun rowsWith(measurements: Map<String, Pair<LabStatus, String>>): List<TierRow> = rows.map { row ->
        val m = measurements[row.capability] ?: return@map row
        row.copy(
            status = m.first,
            note = row.note + " | MEASURED ON THIS DEVICE: " + m.second,
            measured = true,
        )
    }

    // ------------------------------------------------------------------ evidence

    /**
     * The matrix as observations.
     *
     * Every row is emitted with its own status, and the note always begins with DOCUMENTED or
     * MEASURED so that no reader of the exported report can mistake a documentation row for a
     * device measurement. A leading header observation says the same thing about the matrix as a
     * whole, because a table is easy to quote out of context.
     */
    fun observations(rows: List<TierRow> = this.rows): List<Observation> {
        val header = Observation(
            key = "tier.matrix",
            value = rows.size.toString() + " capabilities, " + rows.count { it.measured } + " measured",
            status = LabStatus.NOT_TESTED,
            note = "The capability x privilege-tier matrix is PLATFORM DOCUMENTATION unless a row " +
                "says MEASURED. A documented row states what the published API contract allows at a " +
                "tier; it is not evidence about this phone, this head unit or this session.",
            category = LabCategory.DEVICE,
        )
        return listOf(header) + rows.map { row ->
            Observation(
                key = "tier." + row.key,
                value = row.tier.label + " via " + row.mechanism,
                status = row.status,
                note = row.provenance + " - " + row.note,
                category = categoryFor(row.capability),
            )
        }
    }

    /** Writes the matrix into the session log so it reaches the JSONL evidence file and the report. */
    fun logMatrix(rows: List<TierRow> = this.rows) {
        SessionLogger.log(
            LabCategory.DEVICE,
            "privilege_tier_matrix",
            LabStatus.NOT_TESTED,
            mapOf(
                "capabilities" to rows.size.toString(),
                "measuredRows" to rows.count { it.measured }.toString(),
                "documentedRows" to rows.count { !it.measured }.toString(),
                "provenance" to "documented unless a row is marked MEASURED",
            ),
        )
        SessionLogger.logAll(observations(rows))
    }

    /** Routes each capability to the category its evidence belongs under in the report. */
    private fun categoryFor(capability: String): LabCategory = when {
        // Miracast first: "Wi-Fi Display route" also contains "display", and the transport is the
        // more informative bucket for it.
        capability.contains("Miracast", ignoreCase = true) ||
            capability.contains("UIBC", ignoreCase = true) ||
            capability.contains("Wi-Fi Display", ignoreCase = true) -> LabCategory.MIRACAST
        capability.contains("display", ignoreCase = true) ||
            capability.contains("Presentation", ignoreCase = true) -> LabCategory.DISPLAY
        capability.contains("codec", ignoreCase = true) ||
            capability.contains("encode", ignoreCase = true) -> LabCategory.CODEC
        capability.contains("input", ignoreCase = true) -> LabCategory.INPUT
        capability.contains("DeX", ignoreCase = true) -> LabCategory.DEX
        else -> LabCategory.DEVICE
    }
}
