package nl.icthorse.miraicastlab.input

import android.content.Context
import android.hardware.input.InputManager
import android.view.InputDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.icthorse.miraicastlab.core.LabCategory
import nl.icthorse.miraicastlab.core.LabStatus
import nl.icthorse.miraicastlab.core.Observation
import nl.icthorse.miraicastlab.core.Probe
import java.util.Locale

/**
 * Inventory of every input device Android will admit to (spec section 6, "InputDevice list").
 *
 * This probe is the static half of the touch-back question. A working back channel from the head
 * unit - UIBC, a Samsung equivalent, or an HID bridge - has to become *something* in this list, or
 * else has to inject events onto a device that is already in it. Running the probe before and after
 * a mirroring session and diffing the result is therefore a real experiment, which is why every
 * device is reported with its stable descriptor and not just its volatile id.
 *
 * The probe never claims a back channel exists. It reports what is enumerated; the live grid in
 * [InputTestScreen] is what produces OBSERVED evidence.
 */
object InputDeviceProbe : Probe {

    override val id = "input"
    override val title = "Input devices"

    /** Source classes we want a count for in every report, in reporting order. */
    private val COUNTED = listOf(
        InputDevice.SOURCE_TOUCHSCREEN to "touchscreen",
        InputDevice.SOURCE_MOUSE to "mouse",
        InputDevice.SOURCE_MOUSE_RELATIVE to "mouse_relative",
        InputDevice.SOURCE_STYLUS to "stylus",
        InputDevice.SOURCE_TOUCHPAD to "touchpad",
        InputDevice.SOURCE_TOUCH_NAVIGATION to "touch_navigation",
        InputDevice.SOURCE_KEYBOARD to "keyboard",
        InputDevice.SOURCE_DPAD to "dpad",
        InputDevice.SOURCE_JOYSTICK to "joystick",
        InputDevice.SOURCE_GAMEPAD to "gamepad",
        InputDevice.SOURCE_TRACKBALL to "trackball",
        InputDevice.SOURCE_ROTARY_ENCODER to "rotary_encoder",
        InputDevice.SOURCE_HDMI to "hdmi",
    )

    override suspend fun observe(context: Context): List<Observation> {
        val out = mutableListOf<Observation>()

        // InputManager itself is the platform answering yes/no about the whole subsystem.
        val im = try {
            context.getSystemService(Context.INPUT_SERVICE) as? InputManager
        } catch (t: Throwable) {
            out += Observation.error("input.input_manager", t, LabCategory.INPUT)
            null
        }
        if (im == null) {
            out += Observation.unsupported(
                "input.input_manager",
                LabCategory.INPUT,
                "getSystemService(INPUT_SERVICE) returned null; device hot-plug cannot be observed.",
            )
        } else {
            out += Observation.confirmed(
                "input.input_manager",
                "available",
                LabCategory.INPUT,
                "InputDeviceListener can be registered, so devices appearing during a session are observable.",
            )
        }

        val devices = withContext(Dispatchers.Default) { InputFacts.snapshot() }

        out += Observation.confirmed("input.device_count", devices.size, LabCategory.INPUT)
        out += Observation.confirmed(
            "input.device_ids",
            devices.joinToString(",") { it.id.toString() }.ifEmpty { "none" },
            LabCategory.INPUT,
        )

        // isExternal() is hidden API. Whether we can read it is itself a reportable fact, because it
        // decides whether the external-device count below is CONFIRMED or merely INFERRED.
        val externalReadable = devices.any { it.external != null }
        val externalError = devices.firstOrNull { it.externalError != null }?.externalError
        out += if (devices.isEmpty()) {
            Observation.notTested(
                "input.is_external_readable",
                LabCategory.INPUT,
                "No input devices were enumerated, so the hidden method was never exercised.",
            )
        } else if (externalReadable) {
            Observation.confirmed(
                "input.is_external_readable",
                "true",
                LabCategory.INPUT,
                "Hidden InputDevice.isExternal() is reachable by reflection on this build.",
            )
        } else {
            Observation.unsupported(
                "input.is_external_readable",
                LabCategory.INPUT,
                "InputDevice.isExternal() could not be invoked (" + (externalError ?: "unknown") +
                    "). Only this one field is affected; external counts below are INFERRED.",
            )
        }

        val externalDevices = devices.filter { it.looksExternal }
        out += Observation(
            "input.external_count",
            externalDevices.size.toString(),
            if (externalReadable) LabStatus.CONFIRMED else LabStatus.INFERRED,
            if (externalReadable) {
                "From InputDevice.isExternal()."
            } else {
                "Inferred from non-virtual devices carrying a vendor or product id; isExternal() was unreadable."
            },
            LabCategory.INPUT,
        )
        out += Observation.confirmed(
            "input.external_names",
            externalDevices.joinToString(" ; ") { it.name }.ifEmpty { "none" },
            LabCategory.INPUT,
            "An external touchscreen or mouse appearing here during mirroring is what a back channel looks like.",
        )
        out += Observation.confirmed(
            "input.virtual_count",
            devices.count { it.isVirtual },
            LabCategory.INPUT,
            "Virtual devices are the platform's own synthetic sources (e.g. the software key injector).",
        )

        COUNTED.forEach { (bit, name) ->
            val matching = devices.filter { it.has(bit) }
            out += Observation.confirmed(
                "input.count." + name,
                matching.size,
                LabCategory.INPUT,
                if (matching.isEmpty()) null else matching.joinToString(" ; ") { it.name },
            )
        }

        // The single most load-bearing derived fact on this screen.
        val externalPointers = devices.filter { it.isPointerLike && it.looksExternal }
        out += Observation(
            "input.external_pointer_present",
            externalPointers.isNotEmpty().toString(),
            LabStatus.OBSERVED,
            if (externalPointers.isEmpty()) {
                "No external pointer device is enumerated at scan time. This describes the moment of " +
                    "the scan only: it is not a claim that the sink has no input back channel."
            } else {
                "Enumerated now: " + externalPointers.joinToString(" ; ") { it.name }
            },
            LabCategory.INPUT,
        )
        out += Observation.confirmed(
            "input.keyboards",
            devices.filter { it.isKeyboardLike }.joinToString(" ; ") {
                it.name + " (" + it.keyboardTypeName + ")"
            }.ifEmpty { "none" },
            LabCategory.INPUT,
        )

        // The verdict itself belongs to the live grid; the static probe must not pre-empt it.
        out += Observation.notTested(
            "input.touch_back",
            LabCategory.INPUT,
            "Whether the head unit delivers input to the phone requires the Toyota Mirai and a run " +
                "of the touch-back target grid on the INPUT TEST screen.",
        )
        out += Observation.notTested(
            "input.uibc_advertised",
            LabCategory.INPUT,
            "UIBC negotiation happens inside the RTSP/Miracast session, which no public Android API " +
                "exposes to a third-party app. It can only be inferred from input actually arriving.",
        )

        devices.forEach { d ->
            val prefix = "input.dev." + d.id
            out += Observation.confirmed(prefix + ".summary", d.oneLine(), LabCategory.INPUT)
            out += Observation.confirmed(
                prefix + ".sources",
                d.sourceNames.joinToString("|") + " (0x" + Integer.toHexString(d.sources) + ")",
                LabCategory.INPUT,
                "descriptor=" + (d.descriptor ?: "unavailable"),
            )
            out += if (d.external != null) {
                Observation.confirmed(prefix + ".external", d.external, LabCategory.INPUT)
            } else {
                Observation.unsupported(
                    prefix + ".external",
                    LabCategory.INPUT,
                    "isExternal() unreadable: " + (d.externalError ?: "unknown"),
                )
            }
            out += Observation.confirmed(
                prefix + ".capabilities",
                "vibrator=" + (d.hasVibrator?.toString() ?: "unknown") +
                    " microphone=" + (d.hasMicrophone?.toString() ?: "unknown") +
                    " controller=" + d.controllerNumber +
                    " keyboard=" + d.keyboardTypeName,
                LabCategory.INPUT,
            )
            val supported = d.supports.filterValues { it }.keys
            out += Observation.confirmed(
                prefix + ".supports_source",
                supported.joinToString("|").ifEmpty { "none" },
                LabCategory.INPUT,
                "From InputDevice.supportsSource() per source, not from the raw source bitmask.",
            )
            if (d.axes.isNotEmpty()) {
                out += Observation.confirmed(
                    prefix + ".motion_ranges",
                    d.axes.joinToString(" | ") { it.oneLine() },
                    LabCategory.INPUT,
                    d.axes.size.toString() + " axes",
                )
            }
        }

        out += Observation.confirmed(
            "input.inventory_hash",
            String.format(
                Locale.US,
                "%08x",
                devices.joinToString(",") { it.identity + ":" + it.sources }.hashCode(),
            ),
            LabCategory.INPUT,
            "Stable over an unchanged device set; a different value between two scans means the " +
                "input topology changed - the diff a back channel would produce.",
        )

        return out
    }
}
