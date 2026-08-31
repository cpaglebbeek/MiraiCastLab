package nl.icthorse.miraicastlab.dex

import android.view.KeyEvent
import nl.icthorse.miraicastlab.core.LabStatus
import nl.icthorse.miraicastlab.core.PrivilegeTier

/**
 * The contract for "can this app act as the keyboard and mouse of a DeX desktop?"
 *
 * The premise is sound and worth stating plainly: a Samsung DeX desktop **is** driven by an external
 * keyboard and mouse, and it does have hotkeys - Meta opens the app list, you type a name, Enter
 * launches it. None of that is in question.
 *
 * The open question is narrower: can *this app* be that keyboard and mouse? Android's answer depends
 * entirely on privilege, so every route below reports which tier it needed and what actually
 * happened, rather than succeeding or failing silently.
 */

/** One emulated input action, independent of how it is delivered. */
sealed interface InputAction {
    /** A key press and release, optionally with modifiers held. */
    data class Key(val keyCode: Int, val meta: Int = 0, val label: String) : InputAction

    /** Literal text, typed character by character. */
    data class Text(val text: String) : InputAction

    /** A tap at absolute screen coordinates. */
    data class Tap(val x: Float, val y: Float) : InputAction

    /** A pointer move / drag from one point to another over [durationMs]. */
    data class Swipe(
        val x1: Float, val y1: Float,
        val x2: Float, val y2: Float,
        val durationMs: Long = 120,
    ) : InputAction

    /** A named global action such as HOME or BACK. */
    data class Global(val action: Int, val label: String) : InputAction
}

/** What one delivery route did with one action. */
data class InputAttempt(
    val routeName: String,
    val tier: PrivilegeTier,
    val action: String,
    val status: LabStatus,
    /** Literally what happened - return value, exception type, or the refusal message. */
    val observation: String,
    /** What may be concluded. Often less than the observation suggests. */
    val conclusion: String,
    val elapsedMs: Long = 0,
) {
    /**
     * Delivery is not effect.
     *
     * Every route in this module can at most report that the platform ACCEPTED an event. Whether a
     * character appeared in a DeX search box, or the car screen changed, is a separate claim that
     * only the tester can make. No route may set this true.
     */
    val provesVisibleEffect: Boolean get() = false
}

/**
 * A way of getting an [InputAction] into the system.
 *
 * Implementations must never throw: a refusal is data. They must also never claim success from the
 * absence of an exception - see [InputAttempt.provesVisibleEffect].
 */
interface InputRoute {
    val routeName: String
    val tier: PrivilegeTier

    /** Whether this route can be used right now, and why not when it cannot. */
    fun availability(): Pair<Boolean, String>

    suspend fun send(action: InputAction): InputAttempt
}

/**
 * The Samsung DeX keyboard shortcuts this module can emulate.
 *
 * Sources are Samsung's own DeX documentation and the standard Android key semantics behind them.
 * They are listed as *documented*, not measured: whether a given One UI build honours a given
 * shortcut is exactly what the experiment finds out.
 */
enum class DexHotkey(
    val label: String,
    val keyCode: Int,
    val meta: Int,
    val effect: String,
) {
    APP_LIST_META(
        "Meta  (⊞)",
        KeyEvent.KEYCODE_META_LEFT,
        0,
        "Opens the DeX app list. Type a name and press Enter to launch it - the flow this module emulates.",
    ),
    APP_LIST_CTRL_ESC(
        "Ctrl + Esc",
        KeyEvent.KEYCODE_ESCAPE,
        KeyEvent.META_CTRL_ON,
        "Alternative app-list shortcut on DeX and on Windows-style desktops.",
    ),
    SEARCH(
        "Search",
        KeyEvent.KEYCODE_SEARCH,
        0,
        "System search. On some builds this is what the app list listens for.",
    ),
    HOME(
        "Meta + H  /  Home",
        KeyEvent.KEYCODE_HOME,
        0,
        "Returns to the DeX home screen.",
    ),
    BACK(
        "Back",
        KeyEvent.KEYCODE_BACK,
        0,
        "Back. The most reliable probe: if nothing else lands, this usually does.",
    ),
    RECENTS(
        "Meta + Tab",
        KeyEvent.KEYCODE_TAB,
        KeyEvent.META_META_ON,
        "Task switcher.",
    ),
    ENTER(
        "Enter",
        KeyEvent.KEYCODE_ENTER,
        0,
        "Confirms a typed app name in the app list, or a query in a search box.",
    ),
    ESCAPE(
        "Esc",
        KeyEvent.KEYCODE_ESCAPE,
        0,
        "Dismisses the app list without launching anything. The safe way to end a failed attempt.",
    ),
    ;

    fun asAction(): InputAction.Key = InputAction.Key(keyCode, meta, label)
}

/**
 * The app-launch flow the research question describes: hotkey, type a name, Enter.
 *
 * Modelled as data rather than as a script so the report can show exactly which of the three steps
 * was delivered and which was not. A flow that fails at step 2 is a different finding from one that
 * fails at step 1.
 */
data class HotkeyLaunchFlow(
    val appName: String,
    val hotkey: DexHotkey = DexHotkey.APP_LIST_META,
    val settleMs: Long = 700,
) {
    fun steps(): List<InputAction> = listOf(
        hotkey.asAction(),
        InputAction.Text(appName),
        DexHotkey.ENTER.asAction(),
    )

    val description: String
        get() = "press " + hotkey.label + ", type \"" + appName + "\", press Enter"
}
