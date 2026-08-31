package nl.icthorse.miraicastlab.dex

import android.view.KeyEvent

/**
 * Translation between Android's [KeyEvent] world and the HID Keyboard/Keypad usage page (0x07).
 *
 * These are two different alphabets and the mapping is deliberately partial:
 *
 * - Android key codes name *semantic* keys, including ones no keyboard has (BACK, HOME, SEARCH,
 *   APP_SWITCH). The HID keyboard page names *physical key positions* on a 101-key layout. Anything
 *   Android-only has no usage and is reported as unmapped rather than approximated. Sending Escape
 *   for BACK would be inventing a result.
 * - A HID usage is a key *position*, not a character. The host applies its own keyboard layout to
 *   it, so [textPlan] necessarily assumes the host is on a US-QWERTY-compatible layout. On a host
 *   set to AZERTY or QWERTZ the same bytes produce different characters. That assumption is stated
 *   in every conclusion this module writes; it is not something the phone can detect or correct.
 *
 * Usage numbers below are from HID Usage Tables 1.12, section 10 (Keyboard/Keypad page).
 */
object HidKeyMap {

    // Modifier bits of byte 0 of the keyboard report (usages 0xE0..0xE7, one bit each).
    const val MOD_LEFT_CTRL = 0x01
    const val MOD_LEFT_SHIFT = 0x02
    const val MOD_LEFT_ALT = 0x04
    const val MOD_LEFT_GUI = 0x08 // "Meta" / Windows key - the DeX app-list key
    const val MOD_RIGHT_CTRL = 0x10
    const val MOD_RIGHT_SHIFT = 0x20
    const val MOD_RIGHT_ALT = 0x40
    const val MOD_RIGHT_GUI = 0x80

    // A few usages worth naming because the DeX flow depends on them.
    const val USAGE_ENTER = 0x28
    const val USAGE_ESCAPE = 0x29
    const val USAGE_BACKSPACE = 0x2A
    const val USAGE_TAB = 0x2B
    const val USAGE_SPACE = 0x2C

    /**
     * Android key codes that ARE a modifier. Pressing one alone (Meta, for the DeX app list) is a
     * report with the bit set and an empty key array - exactly what a physical keyboard sends.
     */
    private val MODIFIER_KEYCODES: Map<Int, Int> = mapOf(
        KeyEvent.KEYCODE_CTRL_LEFT to MOD_LEFT_CTRL,
        KeyEvent.KEYCODE_CTRL_RIGHT to MOD_RIGHT_CTRL,
        KeyEvent.KEYCODE_SHIFT_LEFT to MOD_LEFT_SHIFT,
        KeyEvent.KEYCODE_SHIFT_RIGHT to MOD_RIGHT_SHIFT,
        KeyEvent.KEYCODE_ALT_LEFT to MOD_LEFT_ALT,
        KeyEvent.KEYCODE_ALT_RIGHT to MOD_RIGHT_ALT,
        KeyEvent.KEYCODE_META_LEFT to MOD_LEFT_GUI,
        KeyEvent.KEYCODE_META_RIGHT to MOD_RIGHT_GUI,
    )

    /** Android key code -> HID keyboard usage, for keys a 101-key keyboard actually has. */
    private val KEYCODE_TO_USAGE: Map<Int, Int> = buildMap {
        // Letters: KEYCODE_A..KEYCODE_Z are contiguous, usages 0x04..0x1D likewise.
        for (i in 0..25) put(KeyEvent.KEYCODE_A + i, 0x04 + i)
        // Digits: HID puts 1..9 at 0x1E..0x26 and 0 at 0x27, so 0 is a special case.
        for (i in 1..9) put(KeyEvent.KEYCODE_0 + i, 0x1E + (i - 1))
        put(KeyEvent.KEYCODE_0, 0x27)
        // Function row.
        for (i in 0..11) put(KeyEvent.KEYCODE_F1 + i, 0x3A + i)

        put(KeyEvent.KEYCODE_ENTER, USAGE_ENTER)
        put(KeyEvent.KEYCODE_NUMPAD_ENTER, 0x58)
        put(KeyEvent.KEYCODE_ESCAPE, USAGE_ESCAPE)
        put(KeyEvent.KEYCODE_DEL, USAGE_BACKSPACE)      // Android DEL is backspace
        put(KeyEvent.KEYCODE_FORWARD_DEL, 0x4C)         // ... and FORWARD_DEL is Delete
        put(KeyEvent.KEYCODE_TAB, USAGE_TAB)
        put(KeyEvent.KEYCODE_SPACE, USAGE_SPACE)
        put(KeyEvent.KEYCODE_MINUS, 0x2D)
        put(KeyEvent.KEYCODE_EQUALS, 0x2E)
        put(KeyEvent.KEYCODE_LEFT_BRACKET, 0x2F)
        put(KeyEvent.KEYCODE_RIGHT_BRACKET, 0x30)
        put(KeyEvent.KEYCODE_BACKSLASH, 0x31)
        put(KeyEvent.KEYCODE_SEMICOLON, 0x33)
        put(KeyEvent.KEYCODE_APOSTROPHE, 0x34)
        put(KeyEvent.KEYCODE_GRAVE, 0x35)
        put(KeyEvent.KEYCODE_COMMA, 0x36)
        put(KeyEvent.KEYCODE_PERIOD, 0x37)
        put(KeyEvent.KEYCODE_SLASH, 0x38)
        put(KeyEvent.KEYCODE_CAPS_LOCK, 0x39)
        put(KeyEvent.KEYCODE_INSERT, 0x49)
        put(KeyEvent.KEYCODE_MOVE_HOME, 0x4A)
        put(KeyEvent.KEYCODE_PAGE_UP, 0x4B)
        put(KeyEvent.KEYCODE_MOVE_END, 0x4D)
        put(KeyEvent.KEYCODE_PAGE_DOWN, 0x4E)
        // Arrows: HID orders them right, left, down, up.
        put(KeyEvent.KEYCODE_DPAD_RIGHT, 0x4F)
        put(KeyEvent.KEYCODE_DPAD_LEFT, 0x50)
        put(KeyEvent.KEYCODE_DPAD_DOWN, 0x51)
        put(KeyEvent.KEYCODE_DPAD_UP, 0x52)
    }

    /**
     * Android key codes that exist as *concepts* but have no usage on the Keyboard/Keypad page, with
     * the reason. Reported verbatim so the finding is "this page cannot express it", not "it failed".
     */
    private val NO_HID_EQUIVALENT: Map<Int, String> = mapOf(
        KeyEvent.KEYCODE_BACK to
            "Android BACK has no Keyboard/Keypad usage. It is a navigation concept, not a key on a " +
            "101-key keyboard; Escape is a different key and would be a substitution, not a mapping.",
        KeyEvent.KEYCODE_HOME to
            "Android HOME has no Keyboard/Keypad usage. On a desktop host the analogue is Meta/GUI, " +
            "which is a different keystroke and is available separately as a modifier.",
        KeyEvent.KEYCODE_APP_SWITCH to
            "Android APP_SWITCH has no Keyboard/Keypad usage. Alt+Tab or Meta+Tab is a host-side " +
            "convention, not the same key.",
        KeyEvent.KEYCODE_SEARCH to
            "Search is AC Search (0x0221) on the Consumer page 0x0C, which this descriptor does not " +
            "declare. It cannot be sent over the keyboard collection at all.",
        KeyEvent.KEYCODE_MENU to
            "MENU maps to Keyboard Application (0x65) on some keyboards but not identically; not " +
            "mapped, to avoid claiming an equivalence that depends on the host.",
    )

    /** One keystroke: modifiers held plus at most one key usage. */
    data class Stroke(val modifiers: Int, val usage: Int, val label: String) {
        /** True when this is a modifier-only stroke, e.g. a bare Meta tap. */
        val isModifierOnly: Boolean get() = usage == 0
    }

    /** The result of turning literal text into keystrokes, including what could not be typed. */
    data class TextPlan(val strokes: List<Stroke>, val unmapped: String) {
        val complete: Boolean get() = unmapped.isEmpty()
    }

    /** Translates [KeyEvent] meta state bits into HID modifier bits. */
    fun modifiersFor(meta: Int): Int {
        var bits = 0
        // Side-specific bits first; the generic *_ON bits are set whenever either side is down, so
        // testing them alone would turn a right-shift into a left-shift.
        if (meta and KeyEvent.META_CTRL_LEFT_ON != 0) bits = bits or MOD_LEFT_CTRL
        if (meta and KeyEvent.META_CTRL_RIGHT_ON != 0) bits = bits or MOD_RIGHT_CTRL
        if (meta and KeyEvent.META_SHIFT_LEFT_ON != 0) bits = bits or MOD_LEFT_SHIFT
        if (meta and KeyEvent.META_SHIFT_RIGHT_ON != 0) bits = bits or MOD_RIGHT_SHIFT
        if (meta and KeyEvent.META_ALT_LEFT_ON != 0) bits = bits or MOD_LEFT_ALT
        if (meta and KeyEvent.META_ALT_RIGHT_ON != 0) bits = bits or MOD_RIGHT_ALT
        if (meta and KeyEvent.META_META_LEFT_ON != 0) bits = bits or MOD_LEFT_GUI
        if (meta and KeyEvent.META_META_RIGHT_ON != 0) bits = bits or MOD_RIGHT_GUI
        // Generic bits with no side information default to the left-hand key, as physical keyboards
        // do when the meta state came from a synthetic event such as DexHotkey's META_CTRL_ON.
        if (bits and (MOD_LEFT_CTRL or MOD_RIGHT_CTRL) == 0 &&
            meta and KeyEvent.META_CTRL_ON != 0
        ) bits = bits or MOD_LEFT_CTRL
        if (bits and (MOD_LEFT_SHIFT or MOD_RIGHT_SHIFT) == 0 &&
            meta and KeyEvent.META_SHIFT_ON != 0
        ) bits = bits or MOD_LEFT_SHIFT
        if (bits and (MOD_LEFT_ALT or MOD_RIGHT_ALT) == 0 &&
            meta and KeyEvent.META_ALT_ON != 0
        ) bits = bits or MOD_LEFT_ALT
        if (bits and (MOD_LEFT_GUI or MOD_RIGHT_GUI) == 0 &&
            meta and KeyEvent.META_META_ON != 0
        ) bits = bits or MOD_LEFT_GUI
        return bits
    }

    /** HID usage for an Android key code, or null when the keyboard page has no such key. */
    fun usageFor(keyCode: Int): Int? = KEYCODE_TO_USAGE[keyCode]

    /** HID modifier bit when [keyCode] IS a modifier key, else null. */
    fun modifierBitFor(keyCode: Int): Int? = MODIFIER_KEYCODES[keyCode]

    /** Why a key code cannot be sent, when the reason is known and documented. */
    fun refusalFor(keyCode: Int): String? = NO_HID_EQUIVALENT[keyCode]

    /**
     * Turns an [InputAction.Key] into a single stroke, or null when it cannot be expressed.
     * A bare modifier (Meta for the DeX app list) yields a modifier-only stroke.
     */
    fun strokeFor(keyCode: Int, meta: Int, label: String): Stroke? {
        val modifierBit = modifierBitFor(keyCode)
        if (modifierBit != null) return Stroke(modifiersFor(meta) or modifierBit, 0, label)
        val usage = usageFor(keyCode) ?: return null
        return Stroke(modifiersFor(meta), usage, label)
    }

    /**
     * Character -> (usage, needs shift) on a US-QWERTY host layout. See the class comment: the host,
     * not the phone, decides what character a position produces.
     */
    private fun charToUsage(c: Char): Pair<Int, Boolean>? = when (c) {
        in 'a'..'z' -> (0x04 + (c - 'a')) to false
        in 'A'..'Z' -> (0x04 + (c - 'A')) to true
        in '1'..'9' -> (0x1E + (c - '1')) to false
        '0' -> 0x27 to false
        ' ' -> USAGE_SPACE to false
        '\n' -> USAGE_ENTER to false
        '\t' -> USAGE_TAB to false
        '!' -> 0x1E to true
        '@' -> 0x1F to true
        '#' -> 0x20 to true
        '$' -> 0x21 to true
        '%' -> 0x22 to true
        '^' -> 0x23 to true
        '&' -> 0x24 to true
        '*' -> 0x25 to true
        '(' -> 0x26 to true
        ')' -> 0x27 to true
        '-' -> 0x2D to false
        '_' -> 0x2D to true
        '=' -> 0x2E to false
        '+' -> 0x2E to true
        '[' -> 0x2F to false
        '{' -> 0x2F to true
        ']' -> 0x30 to false
        '}' -> 0x30 to true
        '\\' -> 0x31 to false
        '|' -> 0x31 to true
        ';' -> 0x33 to false
        ':' -> 0x33 to true
        '\'' -> 0x34 to false
        '"' -> 0x34 to true
        '`' -> 0x35 to false
        '~' -> 0x35 to true
        ',' -> 0x36 to false
        '<' -> 0x36 to true
        '.' -> 0x37 to false
        '>' -> 0x37 to true
        '/' -> 0x38 to false
        '?' -> 0x38 to true
        else -> null // accented and non-Latin characters need a host-side compose/IME, not a usage
    }

    /**
     * Plans the keystrokes for literal text. Characters with no usage are collected in
     * [TextPlan.unmapped] rather than dropped silently: a partially typed app name is a different
     * finding from a fully typed one, and the report must be able to tell them apart.
     */
    fun textPlan(text: String): TextPlan {
        val strokes = mutableListOf<Stroke>()
        val unmapped = StringBuilder()
        text.forEach { c ->
            val mapped = charToUsage(c)
            if (mapped == null) {
                unmapped.append(c)
            } else {
                val (usage, shift) = mapped
                strokes += Stroke(
                    modifiers = if (shift) MOD_LEFT_SHIFT else 0,
                    usage = usage,
                    label = if (c == '\n') "\\n" else if (c == '\t') "\\t" else c.toString(),
                )
            }
        }
        return TextPlan(strokes, unmapped.toString())
    }

    /** Human-readable modifier list for logs and conclusions. */
    fun describeModifiers(bits: Int): String {
        if (bits == 0) return "none"
        val parts = mutableListOf<String>()
        if (bits and MOD_LEFT_CTRL != 0) parts += "LCtrl"
        if (bits and MOD_LEFT_SHIFT != 0) parts += "LShift"
        if (bits and MOD_LEFT_ALT != 0) parts += "LAlt"
        if (bits and MOD_LEFT_GUI != 0) parts += "LGui(Meta)"
        if (bits and MOD_RIGHT_CTRL != 0) parts += "RCtrl"
        if (bits and MOD_RIGHT_SHIFT != 0) parts += "RShift"
        if (bits and MOD_RIGHT_ALT != 0) parts += "RAlt"
        if (bits and MOD_RIGHT_GUI != 0) parts += "RGui(Meta)"
        return parts.joinToString("+")
    }

    /** How many Android key codes this table covers - reported as a fact, not as a capability. */
    val mappedKeyCodeCount: Int get() = KEYCODE_TO_USAGE.size + MODIFIER_KEYCODES.size
}
