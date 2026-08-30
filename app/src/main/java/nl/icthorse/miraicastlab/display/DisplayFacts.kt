package nl.icthorse.miraicastlab.display

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import android.view.Surface
import androidx.annotation.RequiresApi

/**
 * A single snapshot of everything an ordinary app may legitimately read about one [Display].
 *
 * Kept as an immutable value type on purpose: the display test screen compares consecutive
 * snapshots to detect "did the topology just change?", and a data class gives that comparison
 * for free. The probe and the live screen therefore observe exactly the same facts - there is
 * no second, divergent reading path that could disagree with the report.
 */
data class DisplayModeFact(
    val modeId: Int,
    val physicalWidth: Int,
    val physicalHeight: Int,
    val refreshRate: Float,
) {
    override fun toString(): String =
        "#" + modeId + " " + physicalWidth + "x" + physicalHeight + "@" + fmt(refreshRate) + "Hz"
}

data class DisplayFact(
    val displayId: Int,
    val name: String,
    val state: Int,
    val stateName: String,
    val flags: Int,
    val flagNames: List<String>,
    val isValid: Boolean,
    val rotation: Int,
    val rotationDegrees: Int,
    val refreshRate: Float,
    val currentModeId: Int,
    val modes: List<DisplayModeFact>,
    val realWidth: Int,
    val realHeight: Int,
    val densityDpi: Int,
    val xdpi: Float,
    val ydpi: Float,
    val hdrTypes: List<String>,
    val isHdr: Boolean?,
    val isWideColorGamut: Boolean?,
    val inPresentationCategory: Boolean,
    val productInfo: String?,
    val readErrors: List<String>,
) {
    /**
     * Anything that is not the built-in panel. DisplayManager gives no reliable way to tell a
     * Miracast sink from a DeX surface or a virtual display, so "external" is deliberately the
     * weakest possible claim: not display 0.
     */
    val isExternal: Boolean get() = displayId != Display.DEFAULT_DISPLAY

    val resolution: String get() = realWidth.toString() + "x" + realHeight

    /** Everything worth putting in a log record for this display. */
    fun toLogDetails(): Map<String, String> = buildMap {
        put("displayId", displayId.toString())
        put("name", name)
        put("state", stateName)
        put("flags", flagNames.joinToString("|").ifEmpty { "none" })
        put("valid", isValid.toString())
        put("resolution", resolution)
        put("densityDpi", densityDpi.toString())
        put("rotation", rotationDegrees.toString())
        put("refreshRate", fmt(refreshRate))
        put("modes", modes.size.toString())
        put("presentationCategory", inPresentationCategory.toString())
        if (hdrTypes.isNotEmpty()) put("hdr", hdrTypes.joinToString("|"))
        productInfo?.let { put("productInfo", it) }
        if (readErrors.isNotEmpty()) put("readErrors", readErrors.joinToString("; "))
    }

    /** Human-readable key/value lines for the UI cards, in a stable order. */
    fun uiLines(): List<Pair<String, String>> = buildList {
        add("displayId" to displayId.toString())
        add("name" to name)
        add("state" to (stateName + " (" + state + ")"))
        add("valid" to isValid.toString())
        add("flags" to (flagNames.joinToString(", ").ifEmpty { "none set (0x" + Integer.toHexString(flags) + ")" }))
        add("real size" to resolution)
        add("density" to (densityDpi.toString() + " dpi  (x=" + fmt(xdpi) + ", y=" + fmt(ydpi) + ")"))
        add("rotation" to (rotationDegrees.toString() + " deg (Surface.ROTATION_" + rotationDegrees + ")"))
        add("refreshRate" to (fmt(refreshRate) + " Hz"))
        add("current mode" to ("#" + currentModeId))
        add("supported modes" to (if (modes.isEmpty()) "unreported" else modes.joinToString("  ")))
        add("HDR types" to (if (hdrTypes.isEmpty()) "none reported" else hdrTypes.joinToString(", ")))
        add("isHdr" to (isHdr?.toString() ?: "unreadable"))
        add("wideColorGamut" to (isWideColorGamut?.toString() ?: "unreadable"))
        add("PRESENTATION category" to inPresentationCategory.toString())
        productInfo?.let { add("deviceProductInfo" to it) }
        if (readErrors.isNotEmpty()) add("read errors" to readErrors.joinToString("; "))
    }
}

/**
 * Reads display topology. Every optional call is individually guarded: on this project a display
 * that answers half of the API is still evidence, and must not be lost to one throwing getter.
 */
object DisplayFacts {

    fun manager(context: Context): DisplayManager? =
        context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager

    /** All displays DisplayManager reports, in id order. Empty only when the service is missing. */
    fun collect(context: Context): List<DisplayFact> {
        val dm = manager(context) ?: return emptyList()
        val presentationIds: Set<Int> = try {
            dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).map { it.displayId }.toSet()
        } catch (t: Throwable) {
            emptySet()
        }
        return try {
            dm.displays.sortedBy { it.displayId }.map { read(it, it.displayId in presentationIds) }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    /** Display ids that DisplayManager itself classes as presentation-capable. */
    fun presentationDisplayIds(context: Context): List<Int> = try {
        manager(context)
            ?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            ?.map { it.displayId }
            ?: emptyList()
    } catch (t: Throwable) {
        emptyList()
    }

    /**
     * Compact fingerprint of the whole topology. Two snapshots with the same signature describe
     * the same set of displays at the same geometry, which is what "nothing changed" means here.
     */
    fun signature(facts: List<DisplayFact>): String = facts.joinToString(";") {
        it.displayId.toString() + ":" + it.name + ":" + it.resolution + ":" + it.stateName +
            ":" + it.densityDpi + ":" + fmt(it.refreshRate) + ":" + it.flags
    }

    @Suppress("DEPRECATION") // getRealMetrics is the only route to the real size of a *secondary*
    // display; WindowMetrics only describes the display the window is on.
    private fun read(display: Display, inPresentationCategory: Boolean): DisplayFact {
        val errors = mutableListOf<String>()

        val flags = safeInt(errors, "flags") { display.flags } ?: 0
        val state = safeInt(errors, "state") { display.state } ?: Display.STATE_UNKNOWN
        val rotation = safeInt(errors, "rotation") { display.rotation } ?: Surface.ROTATION_0

        val metrics = DisplayMetrics()
        try {
            display.getRealMetrics(metrics)
        } catch (t: Throwable) {
            errors += "getRealMetrics: " + describe(t)
        }

        val modes: List<DisplayModeFact> = try {
            display.supportedModes.map {
                DisplayModeFact(it.modeId, it.physicalWidth, it.physicalHeight, it.refreshRate)
            }
        } catch (t: Throwable) {
            errors += "supportedModes: " + describe(t)
            emptyList()
        }

        val currentModeId = try {
            display.mode?.modeId ?: -1
        } catch (t: Throwable) {
            errors += "mode: " + describe(t)
            -1
        }

        val hdr: List<String> = try {
            display.hdrCapabilities?.supportedHdrTypes?.map { hdrTypeName(it) } ?: emptyList()
        } catch (t: Throwable) {
            errors += "hdrCapabilities: " + describe(t)
            emptyList()
        }

        val product: String? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            readProductInfo(display, errors)
        } else {
            null
        }

        return DisplayFact(
            displayId = display.displayId,
            name = try { display.name ?: "unnamed" } catch (t: Throwable) { errors += "name: " + describe(t); "unreadable" },
            state = state,
            stateName = stateName(state),
            flags = flags,
            flagNames = flagNames(flags),
            isValid = try { display.isValid } catch (t: Throwable) { errors += "isValid: " + describe(t); false },
            rotation = rotation,
            rotationDegrees = rotationDegrees(rotation),
            refreshRate = try { display.refreshRate } catch (t: Throwable) { errors += "refreshRate: " + describe(t); 0f },
            currentModeId = currentModeId,
            modes = modes,
            realWidth = metrics.widthPixels,
            realHeight = metrics.heightPixels,
            densityDpi = metrics.densityDpi,
            xdpi = metrics.xdpi,
            ydpi = metrics.ydpi,
            hdrTypes = hdr,
            isHdr = try { display.isHdr } catch (t: Throwable) { null },
            isWideColorGamut = try { display.isWideColorGamut } catch (t: Throwable) { null },
            inPresentationCategory = inPresentationCategory,
            productInfo = product,
            readErrors = errors,
        )
    }

    /**
     * API 31+ EDID-derived sink description. On a wireless sink this is usually absent, and its
     * absence is itself informative - so a null result is recorded, not hidden.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    private fun readProductInfo(display: Display, errors: MutableList<String>): String? = try {
        val info = display.deviceProductInfo
        if (info == null) {
            null
        } else {
            buildList {
                info.name?.let { add("name=" + it) }
                info.manufacturerPnpId?.let { add("pnpId=" + it) }
                info.productId?.let { add("productId=" + it) }
                add("sinkConnection=" + connectionName(info.connectionToSinkType))
            }.joinToString(", ")
        }
    } catch (t: Throwable) {
        errors += "deviceProductInfo: " + describe(t)
        null
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun connectionName(type: Int): String = when (type) {
        android.hardware.display.DeviceProductInfo.CONNECTION_TO_SINK_BUILT_IN -> "BUILT_IN"
        android.hardware.display.DeviceProductInfo.CONNECTION_TO_SINK_DIRECT -> "DIRECT"
        android.hardware.display.DeviceProductInfo.CONNECTION_TO_SINK_TRANSITIVE -> "TRANSITIVE"
        else -> "UNKNOWN(" + type + ")"
    }

    fun flagNames(flags: Int): List<String> = buildList {
        if (flags and Display.FLAG_PRESENTATION != 0) add("FLAG_PRESENTATION")
        if (flags and Display.FLAG_SECURE != 0) add("FLAG_SECURE")
        if (flags and Display.FLAG_PRIVATE != 0) add("FLAG_PRIVATE")
        if (flags and Display.FLAG_ROUND != 0) add("FLAG_ROUND")
        if (flags and Display.FLAG_SUPPORTS_PROTECTED_BUFFERS != 0) add("FLAG_SUPPORTS_PROTECTED_BUFFERS")
    }

    fun stateName(state: Int): String = when (state) {
        Display.STATE_OFF -> "OFF"
        Display.STATE_ON -> "ON"
        Display.STATE_DOZE -> "DOZE"
        Display.STATE_DOZE_SUSPEND -> "DOZE_SUSPEND"
        Display.STATE_ON_SUSPEND -> "ON_SUSPEND"
        Display.STATE_UNKNOWN -> "UNKNOWN"
        else -> "STATE_" + state
    }

    private fun rotationDegrees(rotation: Int): Int = when (rotation) {
        Surface.ROTATION_0 -> 0
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> -1
    }

    private fun hdrTypeName(type: Int): String = when (type) {
        Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "DOLBY_VISION"
        Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
        Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
        Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> "HDR10_PLUS"
        else -> "HDR_TYPE_" + type
    }

    private inline fun safeInt(errors: MutableList<String>, what: String, block: () -> Int): Int? = try {
        block()
    } catch (t: Throwable) {
        errors += what + ": " + describe(t)
        null
    }

    private fun describe(t: Throwable): String =
        t::class.java.simpleName + "(" + (t.message ?: "no message") + ")"
}

/** One decimal place, locale-independent: report text must be diffable across devices. */
internal fun fmt(v: Float): String = String.format(java.util.Locale.US, "%.1f", v)
