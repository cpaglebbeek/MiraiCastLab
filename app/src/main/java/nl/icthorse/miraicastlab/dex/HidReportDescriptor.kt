package nl.icthorse.miraicastlab.dex

/**
 * The HID report descriptor this app publishes in its SDP record when it registers as a Bluetooth
 * HID **device** (see [HidKeyboard]).
 *
 * A HID descriptor is the contract with the host: it tells the host what reports to expect, how many
 * bytes each one has, and what every bit means. Get it wrong and the host either rejects the
 * connection or silently misreads every report, so the bytes are written out here one item at a time
 * with the USB HID 1.11 item they encode. A reader with the spec (HID 1.11 section 6.2.2 for the
 * item encodings, HID Usage Tables 1.12 for the pages) can check every line.
 *
 * The descriptor is a *combo*: one application collection for a keyboard (report id 1, 8 bytes) and
 * one for a mouse (report id 2, 4 bytes). Report ids are mandatory here because two collections
 * share one interrupt channel and the host must be able to tell the reports apart.
 *
 * Two properties of this descriptor decide what the route can and cannot express, and both are
 * findings rather than defects:
 *
 * 1. The keyboard collection uses the **Keyboard/Keypad usage page (0x07) only**. That page has no
 *    usage for Android's BACK or HOME, and no "search" key - AC Search lives on the Consumer page
 *    (0x0C), which this descriptor does not declare. Those actions therefore cannot be sent at all
 *    over this route; they are not merely unreliable.
 * 2. The mouse collection reports X/Y as **relative** deltas (Input(Data,Var,Rel)). A relative
 *    pointer has no way to express "tap at (x, y)": absolute positioning needs an absolute pointer
 *    or digitizer collection *and* knowledge of the host's screen geometry, neither of which this
 *    app has.
 *
 * [BYTES] and [annotated] are both derived from the same [ITEMS] list, so the documentation cannot
 * drift away from the bytes that are actually sent.
 */
object HidReportDescriptor {

    /** Report id of the keyboard input report. Byte layout: modifiers, reserved, 6 key usages. */
    const val REPORT_ID_KEYBOARD = 1

    /** Report id of the mouse input report. Byte layout: buttons, dx, dy, wheel. */
    const val REPORT_ID_MOUSE = 2

    /** Payload length of a keyboard report, excluding the report id (Android prepends that). */
    const val KEYBOARD_REPORT_SIZE = 8

    /** Payload length of a mouse report, excluding the report id. */
    const val MOUSE_REPORT_SIZE = 4

    /** How many simultaneously-held keys one keyboard report can carry (the classic 6KRO array). */
    const val KEYBOARD_KEY_SLOTS = 6

    /** Logical minimum/maximum of the relative pointer axes, from the descriptor itself. */
    const val POINTER_MIN = -127
    const val POINTER_MAX = 127

    const val MOUSE_BUTTON_LEFT = 0x01
    const val MOUSE_BUTTON_RIGHT = 0x02
    const val MOUSE_BUTTON_MIDDLE = 0x04

    /** One HID short item plus the human reading of it. */
    private class Item(val data: IntArray, val comment: String)

    private fun item(vararg data: Int, comment: String) = Item(data, comment)

    /**
     * The descriptor, item by item. Indentation in the comments mirrors collection nesting exactly
     * as a `hidrd`/`usbhid-dump` decode would print it.
     */
    private val ITEMS: List<Item> = listOf(
        // ---------------------------------------------------------------- keyboard, report id 1
        item(0x05, 0x01, comment = "Usage Page (Generic Desktop, 0x01)"),
        item(0x09, 0x06, comment = "Usage (Keyboard, 0x06)"),
        item(0xA1, 0x01, comment = "Collection (Application)"),
        item(0x85, REPORT_ID_KEYBOARD, comment = "  Report ID (1) - keyboard"),
        item(0x05, 0x07, comment = "  Usage Page (Keyboard/Keypad, 0x07)"),
        item(0x19, 0xE0, comment = "  Usage Minimum (0xE0, Keyboard LeftControl)"),
        item(0x29, 0xE7, comment = "  Usage Maximum (0xE7, Keyboard Right GUI)"),
        item(0x15, 0x00, comment = "  Logical Minimum (0)"),
        item(0x25, 0x01, comment = "  Logical Maximum (1)"),
        item(0x75, 0x01, comment = "  Report Size (1 bit)"),
        item(0x95, 0x08, comment = "  Report Count (8)"),
        item(0x81, 0x02, comment = "  Input (Data,Var,Abs) -> byte 0: the 8 modifier bits"),
        item(0x95, 0x01, comment = "  Report Count (1)"),
        item(0x75, 0x08, comment = "  Report Size (8 bits)"),
        item(0x81, 0x03, comment = "  Input (Cnst,Var,Abs) -> byte 1: reserved, always 0"),
        item(0x95, 0x06, comment = "  Report Count (6)"),
        item(0x75, 0x08, comment = "  Report Size (8 bits)"),
        item(0x15, 0x00, comment = "  Logical Minimum (0)"),
        item(0x25, 0x65, comment = "  Logical Maximum (101 = 0x65, Keyboard Application)"),
        item(0x05, 0x07, comment = "  Usage Page (Keyboard/Keypad, 0x07)"),
        item(0x19, 0x00, comment = "  Usage Minimum (0, Reserved/no event)"),
        item(0x29, 0x65, comment = "  Usage Maximum (0x65) - every usage this app sends is <= 0x65"),
        item(0x81, 0x00, comment = "  Input (Data,Ary,Abs) -> bytes 2..7: up to six held key usages"),
        item(0xC0, comment = "End Collection"),

        // ---------------------------------------------------------------- mouse, report id 2
        item(0x05, 0x01, comment = "Usage Page (Generic Desktop, 0x01)"),
        item(0x09, 0x02, comment = "Usage (Mouse, 0x02)"),
        item(0xA1, 0x01, comment = "Collection (Application)"),
        item(0x85, REPORT_ID_MOUSE, comment = "  Report ID (2) - mouse"),
        item(0x09, 0x01, comment = "  Usage (Pointer, 0x01)"),
        item(0xA1, 0x00, comment = "  Collection (Physical)"),
        item(0x05, 0x09, comment = "    Usage Page (Button, 0x09)"),
        item(0x19, 0x01, comment = "    Usage Minimum (Button 1, primary/left)"),
        item(0x29, 0x03, comment = "    Usage Maximum (Button 3, tertiary/middle)"),
        item(0x15, 0x00, comment = "    Logical Minimum (0)"),
        item(0x25, 0x01, comment = "    Logical Maximum (1)"),
        item(0x75, 0x01, comment = "    Report Size (1 bit)"),
        item(0x95, 0x03, comment = "    Report Count (3)"),
        item(0x81, 0x02, comment = "    Input (Data,Var,Abs) -> byte 0 bits 0..2: buttons"),
        item(0x75, 0x05, comment = "    Report Size (5 bits)"),
        item(0x95, 0x01, comment = "    Report Count (1)"),
        item(0x81, 0x03, comment = "    Input (Cnst,Var,Abs) -> byte 0 bits 3..7: padding to a byte"),
        item(0x05, 0x01, comment = "    Usage Page (Generic Desktop, 0x01)"),
        item(0x09, 0x30, comment = "    Usage (X, 0x30)"),
        item(0x09, 0x31, comment = "    Usage (Y, 0x31)"),
        item(0x09, 0x38, comment = "    Usage (Wheel, 0x38)"),
        item(0x15, 0x81, comment = "    Logical Minimum (-127, two's complement 0x81)"),
        item(0x25, 0x7F, comment = "    Logical Maximum (127)"),
        item(0x75, 0x08, comment = "    Report Size (8 bits)"),
        item(0x95, 0x03, comment = "    Report Count (3)"),
        item(0x81, 0x06, comment = "    Input (Data,Var,Rel) -> bytes 1..3: dx, dy, wheel (RELATIVE)"),
        item(0xC0, comment = "  End Collection"),
        item(0xC0, comment = "End Collection"),
    )

    /** The descriptor as it goes into [android.bluetooth.BluetoothHidDeviceAppSdpSettings]. */
    val BYTES: ByteArray by lazy {
        val flat = ITEMS.flatMap { it.data.asList() }
        ByteArray(flat.size) { (flat[it] and 0xFF).toByte() }
    }

    /** Hex dump with the decoded item on each line, for the report and for the on-screen evidence. */
    val annotated: String by lazy {
        buildString {
            var offset = 0
            ITEMS.forEach { i ->
                val hex = i.data.joinToString(" ") { b -> "0x%02X".format(b and 0xFF) }
                append("%04d".format(offset)).append("  ")
                append(hex.padEnd(15)).append("  ").append(i.comment).append('\n')
                offset += i.data.size
            }
            append("total ").append(offset).append(" bytes")
        }
    }

    /**
     * Builds a keyboard input report.
     *
     * @param modifiers bitmap of [HidKeyMap] MOD_* bits, byte 0 of the report.
     * @param usages    up to [KEYBOARD_KEY_SLOTS] HID keyboard usages held down right now. Extra
     *                  entries are dropped rather than overflowing into the next report: a real
     *                  keyboard would send the rollover code, and silently corrupting byte 8 would
     *                  be worse than dropping.
     */
    fun keyboardReport(modifiers: Int, usages: IntArray): ByteArray {
        val report = ByteArray(KEYBOARD_REPORT_SIZE)
        report[0] = (modifiers and 0xFF).toByte()
        report[1] = 0 // reserved; the descriptor declares it constant
        for (slot in 0 until KEYBOARD_KEY_SLOTS) {
            report[2 + slot] = if (slot < usages.size) (usages[slot] and 0xFF).toByte() else 0
        }
        return report
    }

    /** The all-zero report that means "no modifier held, no key held". */
    fun keyboardRelease(): ByteArray = ByteArray(KEYBOARD_REPORT_SIZE)

    /**
     * Builds a mouse input report. Deltas are clamped to the logical range declared above; wrapping
     * a delta would move the host pointer the wrong way, which is a silent lie about what was sent.
     */
    fun mouseReport(buttons: Int, dx: Int, dy: Int, wheel: Int): ByteArray = byteArrayOf(
        (buttons and 0x07).toByte(),
        clampAxis(dx).toByte(),
        clampAxis(dy).toByte(),
        clampAxis(wheel).toByte(),
    )

    /** The all-zero mouse report: no buttons, no movement. */
    fun mouseRelease(): ByteArray = ByteArray(MOUSE_REPORT_SIZE)

    /** True when [value] fits the declared logical range without clamping. */
    fun fitsAxis(value: Int): Boolean = value in POINTER_MIN..POINTER_MAX

    fun clampAxis(value: Int): Int = value.coerceIn(POINTER_MIN, POINTER_MAX)

    /** Renders a report for the log: bytes only, no personal data can appear here. */
    fun hex(report: ByteArray): String =
        report.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
}
