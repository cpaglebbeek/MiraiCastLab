package nl.icthorse.miraicastlab.input

import android.os.Build
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import java.util.Locale

/**
 * Decoding tables and a snapshot model for `android.view.InputDevice`.
 *
 * Why this exists as its own file: the touch-back question ("did the Toyota screen produce input on
 * the phone?") is answered by comparing the input-device inventory before and during a mirroring
 * session. A back channel - UIBC, a Samsung proprietary equivalent, or an HID bridge - has to
 * surface somewhere in this inventory or in the source/device id carried by a MotionEvent. Both the
 * probe and the live monitor therefore need the same decoders, and they must agree.
 */

/** One decoded motion axis of an input device (spec section 2.6 B: "Android InputDevice metadata"). */
data class AxisFacts(
    val axis: Int,
    val axisName: String,
    val source: Int,
    val min: Float,
    val max: Float,
    val range: Float,
    val flat: Float,
    val fuzz: Float,
    val resolution: Float,
) {
    fun oneLine(): String =
        axisName + " [" + fmt(min) + " .. " + fmt(max) + "] range=" + fmt(range) +
            " flat=" + fmt(flat) + " fuzz=" + fmt(fuzz) + " res=" + fmt(resolution) +
            " src=" + InputFacts.decodeSources(source).joinToString("|")

    private fun fmt(v: Float): String = String.format(Locale.US, "%.3f", v)
}

/**
 * Everything a third-party app may legitimately learn about one input device.
 *
 * [external] is null when the hidden `InputDevice.isExternal()` could not be reached; that is a fact
 * about our reflection, not about the device, and is reported separately from [externalError].
 */
data class InputDeviceFacts(
    val id: Int,
    val name: String,
    val descriptor: String?,
    val vendorId: Int,
    val productId: Int,
    val isVirtual: Boolean,
    val external: Boolean?,
    val externalError: String?,
    val sources: Int,
    val sourceNames: List<String>,
    val keyboardType: Int,
    val controllerNumber: Int,
    val hasVibrator: Boolean?,
    val hasMicrophone: Boolean?,
    val supports: Map<String, Boolean>,
    val axes: List<AxisFacts>,
) {
    /** Stable identity across a run: the descriptor is a hash of hardware ids, not personal data. */
    val identity: String get() = descriptor ?: ("id:" + id)

    val keyboardTypeName: String
        get() = when (keyboardType) {
            InputDevice.KEYBOARD_TYPE_NONE -> "NONE"
            InputDevice.KEYBOARD_TYPE_NON_ALPHABETIC -> "NON_ALPHABETIC"
            InputDevice.KEYBOARD_TYPE_ALPHABETIC -> "ALPHABETIC"
            else -> "UNKNOWN(" + keyboardType + ")"
        }

    /** Can this device move a pointer or report a touch? The class of device a back channel needs. */
    val isPointerLike: Boolean
        get() = has(InputDevice.SOURCE_TOUCHSCREEN) || has(InputDevice.SOURCE_MOUSE) ||
            has(InputDevice.SOURCE_TOUCHPAD) || has(InputDevice.SOURCE_STYLUS) ||
            has(InputDevice.SOURCE_MOUSE_RELATIVE) || has(InputDevice.SOURCE_TRACKBALL)

    val isKeyboardLike: Boolean
        get() = has(InputDevice.SOURCE_KEYBOARD) || has(InputDevice.SOURCE_DPAD)

    /**
     * External when the platform says so, otherwise inferred: the built-in surfaces of a phone are
     * virtual or carry no vendor/product id. Callers must not present the inferred variant as
     * CONFIRMED - [external] being null is exactly what marks it as an inference.
     */
    val looksExternal: Boolean
        get() = external ?: (!isVirtual && (vendorId != 0 || productId != 0))

    fun has(source: Int): Boolean = (sources and source) == source

    fun oneLine(): String = buildString {
        append("id=").append(id)
        append(" \"").append(name).append("\"")
        append(" src=").append(sourceNames.joinToString("|"))
        append(" vid=").append(hex(vendorId)).append(" pid=").append(hex(productId))
        append(" virtual=").append(isVirtual)
        append(" external=").append(external?.toString() ?: "unreadable")
        if (keyboardType != InputDevice.KEYBOARD_TYPE_NONE) append(" kbd=").append(keyboardTypeName)
        if (axes.isNotEmpty()) append(" axes=").append(axes.size)
    }

    private fun hex(v: Int): String = "0x" + Integer.toHexString(v)
}

object InputFacts {

    /**
     * Source bits in the order we want them reported. Sources are compound bit patterns that share
     * class bits (SOURCE_MOUSE and SOURCE_TOUCHSCREEN both carry SOURCE_CLASS_POINTER), so the test
     * must be `(sources and c) == c` and never a plain non-zero AND.
     */
    private val SOURCES: List<Pair<Int, String>> = listOf(
        InputDevice.SOURCE_TOUCHSCREEN to "SOURCE_TOUCHSCREEN",
        InputDevice.SOURCE_MOUSE to "SOURCE_MOUSE",
        InputDevice.SOURCE_MOUSE_RELATIVE to "SOURCE_MOUSE_RELATIVE",
        InputDevice.SOURCE_STYLUS to "SOURCE_STYLUS",
        InputDevice.SOURCE_BLUETOOTH_STYLUS to "SOURCE_BLUETOOTH_STYLUS",
        InputDevice.SOURCE_TOUCHPAD to "SOURCE_TOUCHPAD",
        InputDevice.SOURCE_TOUCH_NAVIGATION to "SOURCE_TOUCH_NAVIGATION",
        InputDevice.SOURCE_KEYBOARD to "SOURCE_KEYBOARD",
        InputDevice.SOURCE_DPAD to "SOURCE_DPAD",
        InputDevice.SOURCE_GAMEPAD to "SOURCE_GAMEPAD",
        InputDevice.SOURCE_JOYSTICK to "SOURCE_JOYSTICK",
        InputDevice.SOURCE_TRACKBALL to "SOURCE_TRACKBALL",
        InputDevice.SOURCE_ROTARY_ENCODER to "SOURCE_ROTARY_ENCODER",
        InputDevice.SOURCE_HDMI to "SOURCE_HDMI",
    )

    /** The sources we explicitly answer supportsSource() for, so the report always has the row. */
    private val SUPPORT_CHECKS: List<Pair<Int, String>> = SOURCES

    private val TOOL_TYPES = mapOf(
        0 to "TOOL_TYPE_UNKNOWN",
        1 to "TOOL_TYPE_FINGER",
        2 to "TOOL_TYPE_STYLUS",
        3 to "TOOL_TYPE_MOUSE",
        4 to "TOOL_TYPE_ERASER",
        5 to "TOOL_TYPE_PALM",
    )

    private val BUTTONS: List<Pair<Int, String>> = listOf(
        MotionEvent.BUTTON_PRIMARY to "PRIMARY",
        MotionEvent.BUTTON_SECONDARY to "SECONDARY",
        MotionEvent.BUTTON_TERTIARY to "TERTIARY",
        MotionEvent.BUTTON_BACK to "BACK",
        MotionEvent.BUTTON_FORWARD to "FORWARD",
        MotionEvent.BUTTON_STYLUS_PRIMARY to "STYLUS_PRIMARY",
        MotionEvent.BUTTON_STYLUS_SECONDARY to "STYLUS_SECONDARY",
    )

    private val META: List<Pair<Int, String>> = listOf(
        KeyEvent.META_SHIFT_ON to "SHIFT",
        KeyEvent.META_ALT_ON to "ALT",
        KeyEvent.META_CTRL_ON to "CTRL",
        KeyEvent.META_META_ON to "META",
        KeyEvent.META_SYM_ON to "SYM",
        KeyEvent.META_FUNCTION_ON to "FUNCTION",
        KeyEvent.META_CAPS_LOCK_ON to "CAPS_LOCK",
        KeyEvent.META_NUM_LOCK_ON to "NUM_LOCK",
        KeyEvent.META_SCROLL_LOCK_ON to "SCROLL_LOCK",
    )

    /** Human-readable source set. Never empty: an unknown bit pattern is reported verbatim. */
    fun decodeSources(sources: Int): List<String> {
        if (sources == InputDevice.SOURCE_UNKNOWN) return listOf("SOURCE_UNKNOWN")
        val out = SOURCES.filter { (bit, _) -> (sources and bit) == bit }.map { it.second }
        return if (out.isEmpty()) listOf("SOURCE_RAW(0x" + Integer.toHexString(sources) + ")") else out
    }

    fun decodeToolType(toolType: Int): String =
        TOOL_TYPES[toolType] ?: ("TOOL_TYPE_" + toolType)

    fun decodeButtonState(buttonState: Int): String {
        if (buttonState == 0) return "none"
        val out = BUTTONS.filter { (bit, _) -> (buttonState and bit) == bit }.map { it.second }
        return if (out.isEmpty()) "0x" + Integer.toHexString(buttonState) else out.joinToString("|")
    }

    fun decodeMetaState(metaState: Int): String {
        if (metaState == 0) return "none"
        val out = META.filter { (bit, _) -> (metaState and bit) == bit }.map { it.second }
        return if (out.isEmpty()) "0x" + Integer.toHexString(metaState) else out.joinToString("|")
    }

    /**
     * Reads the hidden `InputDevice.isExternal()`.
     *
     * Deliberately reflective and deliberately isolated: on API 30+ this is a restricted non-SDK
     * interface and the call may simply fail. That failure is recorded against this one field only -
     * every other fact about the device stays valid.
     */
    fun readIsExternal(device: InputDevice): Pair<Boolean?, String?> = try {
        val m = InputDevice::class.java.getMethod("isExternal")
        val v = m.invoke(device)
        if (v is Boolean) Pair(v, null) else Pair(null, "returned " + (v?.javaClass?.simpleName ?: "null"))
    } catch (t: Throwable) {
        Pair(null, t::class.java.simpleName + ": " + (t.message ?: "no message"))
    }

    /** Full facts for one device id, or null when the id has gone away between enumeration and read. */
    fun factsFor(id: Int): InputDeviceFacts? {
        val d: InputDevice = InputDevice.getDevice(id) ?: return null
        val (external, externalError) = readIsExternal(d)

        val axes = try {
            d.motionRanges.map { r ->
                AxisFacts(
                    axis = r.axis,
                    axisName = MotionEvent.axisToString(r.axis),
                    source = r.source,
                    min = r.min,
                    max = r.max,
                    range = r.range,
                    flat = r.flat,
                    fuzz = r.fuzz,
                    resolution = r.resolution,
                )
            }
        } catch (t: Throwable) {
            emptyList()
        }

        val vibrator: Boolean? = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                d.vibratorManager.vibratorIds.isNotEmpty()
            } else {
                @Suppress("DEPRECATION")
                d.vibrator?.hasVibrator()
            }
        } catch (t: Throwable) {
            null
        }

        val mic: Boolean? = try {
            d.hasMicrophone()
        } catch (t: Throwable) {
            null
        }

        val supports = SUPPORT_CHECKS.associate { (bit, name) ->
            name to (try {
                d.supportsSource(bit)
            } catch (t: Throwable) {
                false
            })
        }

        return InputDeviceFacts(
            id = d.id,
            name = d.name ?: "(unnamed)",
            descriptor = try {
                d.descriptor
            } catch (t: Throwable) {
                null
            },
            vendorId = try {
                d.vendorId
            } catch (t: Throwable) {
                0
            },
            productId = try {
                d.productId
            } catch (t: Throwable) {
                0
            },
            isVirtual = try {
                d.isVirtual
            } catch (t: Throwable) {
                false
            },
            external = external,
            externalError = externalError,
            sources = d.sources,
            sourceNames = decodeSources(d.sources),
            keyboardType = try {
                d.keyboardType
            } catch (t: Throwable) {
                InputDevice.KEYBOARD_TYPE_NONE
            },
            controllerNumber = try {
                d.controllerNumber
            } catch (t: Throwable) {
                0
            },
            hasVibrator = vibrator,
            hasMicrophone = mic,
            supports = supports,
            axes = axes,
        )
    }

    /** Current inventory, ordered by device id so two snapshots diff cleanly. */
    fun snapshot(): List<InputDeviceFacts> = try {
        InputDevice.getDeviceIds().sorted().mapNotNull { factsFor(it) }
    } catch (t: Throwable) {
        emptyList()
    }

    /** Identities of the devices present at a given moment; the baseline a back channel must beat. */
    fun identities(devices: List<InputDeviceFacts>): Set<String> = devices.map { it.identity }.toSet()
}
