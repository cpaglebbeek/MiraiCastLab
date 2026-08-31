package nl.icthorse.miraicastlab.dex

import android.accessibilityservice.AccessibilityService
import android.app.Instrumentation
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.graphics.Path
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import nl.icthorse.miraicastlab.core.LabCategory
import nl.icthorse.miraicastlab.core.LabStatus
import nl.icthorse.miraicastlab.core.Observation
import nl.icthorse.miraicastlab.core.PrivilegeTier
import nl.icthorse.miraicastlab.core.SessionLogger

/**
 * The tiered answer to "can this app be the keyboard and mouse of a DeX desktop?".
 *
 * The premise of the question is not in doubt: a DeX desktop *is* driven by an external keyboard and
 * mouse, and Meta-then-type-then-Enter *is* how you launch an app there. What is in doubt is whether
 * an application process is allowed to produce those events. Android answers that per privilege
 * tier, so there is one route per tier and each one reports what it can and cannot do rather than
 * quietly falling back to a neighbour.
 *
 * Read [InputAttempt.provesVisibleEffect] before adding anything here: every route in this file can
 * establish at most that the platform ACCEPTED an event. Whether a character appeared in the DeX
 * search box is a claim only the tester can make, in a marker, after looking at the screen.
 */

// --------------------------------------------------------------------------------------------
// Shared helpers
// --------------------------------------------------------------------------------------------

/** Short human label for an action, used as [InputAttempt.action] and in the log. */
private fun describe(action: InputAction): String = when (action) {
    is InputAction.Key -> "Key " + action.label + " (code " + action.keyCode +
        (if (action.meta != 0) ", meta 0x" + java.lang.Integer.toHexString(action.meta) else "") + ")"
    is InputAction.Text -> "Text \"" + action.text + "\" (" + action.text.length + " chars)"
    is InputAction.Tap -> "Tap " + action.x.toInt() + "," + action.y.toInt()
    is InputAction.Swipe -> "Swipe " + action.x1.toInt() + "," + action.y1.toInt() + " -> " +
        action.x2.toInt() + "," + action.y2.toInt() + " over " + action.durationMs + " ms"
    is InputAction.Global -> "Global " + action.label + " (" + action.action + ")"
}

/** Writes one attempt into the session log so it reaches the report and the JSONL evidence file. */
private fun InputAttempt.record(): InputAttempt {
    SessionLogger.log(
        LabCategory.DEX,
        "dex.input.attempt",
        status,
        mapOf(
            "value" to observation,
            "note" to conclusion,
            "route" to routeName,
            "tier" to tier.name,
            "action" to action,
            "elapsedMs" to elapsedMs.toString(),
            // Restated on every single record on purpose: a reader of the raw JSONL must not be
            // able to mistake an accepted event for a visible one.
            "provesVisibleEffect" to provesVisibleEffect.toString(),
        ),
    )
    return this
}

private inline fun <T> timed(block: () -> T): Pair<T, Long> {
    val start = SystemClock.elapsedRealtime()
    val value = block()
    return value to (SystemClock.elapsedRealtime() - start)
}

// --------------------------------------------------------------------------------------------
// 1. Ordinary app: android.app.Instrumentation
// --------------------------------------------------------------------------------------------

/**
 * The route every "how do I simulate a tap on Android" answer names, and the one whose limits are
 * most often left out.
 *
 * `Instrumentation.sendKeyDownUpSync` / `sendPointerSync` end up in
 * `InputManager.injectInputEvent`. The system allows that injection **only into windows owned by the
 * calling uid**. Reaching anything else - a DeX launcher, another app's search box - requires
 * `android.permission.INJECT_EVENTS`, which is signature-level and therefore unobtainable by an
 * installed app. See [InputManagerReflectionRoute] for the measured protection level.
 *
 * Consequence, and the reason this route still exists: a send() that throws nothing here proves the
 * mechanism works *inside this process* and proves exactly nothing about DeX. The conclusion string
 * says that every time.
 */
class OwnWindowRoute(context: Context) : InputRoute {

    private val app = context.applicationContext
    override val routeName = "Instrumentation (own windows only)"
    override val tier = PrivilegeTier.ORDINARY_APP

    // One instance is enough; it holds no state we depend on.
    private val instrumentation by lazy { Instrumentation() }

    override fun availability(): Pair<Boolean, String> = true to
        "Runnable, but confined to this app's own windows. Instrumentation injects through " +
            "InputManager.injectInputEvent, which the system permits across uid boundaries only with " +
            "android.permission.INJECT_EVENTS (signature-level). Nothing sent here can reach a DeX " +
            "desktop, a launcher, or another app."

    override suspend fun send(action: InputAction): InputAttempt = withContext(Dispatchers.IO) {
        // Instrumentation.sendKeySync/sendPointerSync call validateNotAppThread() and throw on the
        // main looper, so this must not run in composition or on a UI callback.
        if (action is InputAction.Global) {
            return@withContext attempt(
                action,
                LabStatus.UNSUPPORTED,
                "android.app.Instrumentation exposes no global-action API.",
                "Global actions (HOME/BACK/RECENTS/NOTIFICATIONS) are an AccessibilityService " +
                    "concept. This is a documented absence in the Instrumentation API, not a failure " +
                    "of this device. Use the accessibility route for them.",
                0L,
            )
        }

        val (result, elapsed) = timed {
            runCatching {
                when (action) {
                    is InputAction.Key -> sendKey(action)
                    is InputAction.Text -> instrumentation.sendStringSync(action.text)
                    is InputAction.Tap -> sendTap(action)
                    is InputAction.Swipe -> sendSwipe(action)
                    is InputAction.Global -> Unit // handled above
                }
            }
        }

        result.fold(
            onSuccess = {
                attempt(
                    action,
                    LabStatus.OBSERVED,
                    "Instrumentation returned without throwing.",
                    "The platform accepted the event from this process. That is all: without " +
                        "INJECT_EVENTS the dispatch cannot leave this app's own windows, so this says " +
                        "nothing about DeX and nothing about what any screen showed.",
                    elapsed,
                )
            },
            onFailure = { t ->
                val name = t::class.java.simpleName
                if (t is SecurityException) {
                    attempt(
                        action,
                        LabStatus.UNSUPPORTED,
                        name + ": " + (t.message ?: "no message"),
                        "The platform refused the injection outright. This is the documented " +
                            "INJECT_EVENTS boundary answering: an installed app may not inject into a " +
                            "window it does not own. It is a real negative for cross-app input at this tier.",
                        elapsed,
                    )
                } else {
                    attempt(
                        action,
                        LabStatus.ERROR,
                        name + ": " + (t.message ?: "no message"),
                        "The route itself failed. Nothing may be concluded about the capability.",
                        elapsed,
                    )
                }
            },
        )
    }

    private fun sendKey(action: InputAction.Key) {
        val now = SystemClock.uptimeMillis()
        // The full constructor is used so modifier state travels with the event; sendKeyDownUpSync
        // cannot express meta at all, which matters for a Meta- or Ctrl-based DeX hotkey.
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, action.keyCode, 0, action.meta)
        val up = KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, action.keyCode, 0, action.meta)
        instrumentation.sendKeySync(down)
        instrumentation.sendKeySync(up)
    }

    private fun sendTap(action: InputAction.Tap) {
        val down = SystemClock.uptimeMillis()
        val d = motion(down, down, MotionEvent.ACTION_DOWN, action.x, action.y)
        val u = motion(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, action.x, action.y)
        try {
            instrumentation.sendPointerSync(d)
            instrumentation.sendPointerSync(u)
        } finally {
            d.recycle()
            u.recycle()
        }
    }

    private fun sendSwipe(action: InputAction.Swipe) {
        val steps = 16
        val down = SystemClock.uptimeMillis()
        val events = ArrayList<MotionEvent>(steps + 2)
        events += motion(down, down, MotionEvent.ACTION_DOWN, action.x1, action.y1)
        for (i in 1..steps) {
            val f = i.toFloat() / steps
            events += motion(
                down,
                down + (action.durationMs * f).toLong(),
                MotionEvent.ACTION_MOVE,
                action.x1 + (action.x2 - action.x1) * f,
                action.y1 + (action.y2 - action.y1) * f,
            )
        }
        events += motion(down, down + action.durationMs, MotionEvent.ACTION_UP, action.x2, action.y2)
        try {
            events.forEach { instrumentation.sendPointerSync(it) }
        } finally {
            events.forEach { it.recycle() }
        }
    }

    /**
     * Builds a touchscreen MotionEvent.
     *
     * The long obtain() overload is used because it is the only public one that sets `source`. The
     * short overloads leave source at 0, which the input dispatcher treats as an unclassified device
     * and can silently drop - producing a "success" that was never a touch.
     */
    private fun motion(downTime: Long, eventTime: Long, act: Int, x: Float, y: Float): MotionEvent {
        val props = arrayOf(
            MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_FINGER
            },
        )
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply {
                this.x = x
                this.y = y
                pressure = 1f
                size = 1f
            },
        )
        return MotionEvent.obtain(
            downTime, eventTime, act, 1, props, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
    }

    private fun attempt(
        action: InputAction,
        status: LabStatus,
        observation: String,
        conclusion: String,
        elapsed: Long,
    ) = InputAttempt(routeName, tier, describe(action), status, observation, conclusion, elapsed).record()

    /** Read-only facts about this route, for the report. */
    fun facts(): List<Observation> {
        val cat = LabCategory.DEX
        val present = runCatching { Class.forName("android.app.Instrumentation") }.isSuccess
        return listOf(
            Observation(
                "dex.input.instrumentation.class_present",
                present.toString(),
                if (present) LabStatus.CONFIRMED else LabStatus.UNSUPPORTED,
                "android.app.Instrumentation is public SDK on every Android build; its presence is a " +
                    "platform contract, not a measurement of what it can reach.",
                cat,
            ),
            Observation(
                "dex.input.instrumentation.scope",
                "own uid windows only",
                LabStatus.CONFIRMED,
                "Injection across uid boundaries requires INJECT_EVENTS. This is the documented " +
                    "contract, so it is CONFIRMED as a constraint - not as a successful send. " +
                    app.packageName + " holds no such permission.",
                cat,
            ),
        )
    }
}

// --------------------------------------------------------------------------------------------
// 2. Root tier: InputManager.injectInputEvent - ANALYSED, NEVER INVOKED
// --------------------------------------------------------------------------------------------

/**
 * The row that names which permission actually carries the verdict.
 *
 * `android.hardware.input.InputManager#injectInputEvent(InputEvent, int)` is the system entry point
 * that every "input" path ends at. It is gated by `android.permission.INJECT_EVENTS`. This route
 * *looks the method up and reads that permission's protection level*, and never calls anything.
 *
 * Why analysis rather than an attempt: invoking a non-SDK method to see whether it throws would be
 * an attempt to use a privilege this app is not granted, and the result would be indistinguishable
 * from a hidden-API refusal anyway. Reading the protection level answers the question outright.
 */
class InputManagerReflectionRoute(context: Context) : InputRoute {

    private val app = context.applicationContext
    override val routeName = "InputManager.injectInputEvent (analysis only)"
    override val tier = PrivilegeTier.ROOT

    /** What the reflective lookup found. Computed once, lazily, and never used to call anything. */
    data class Analysis(
        val classFound: Boolean,
        val classDetail: String,
        val methodVisible: Boolean,
        val methodDetail: String,
        val permissionDeclared: Boolean,
        val protection: String,
        val held: Boolean,
        val permissionDetail: String,
    )

    val analysis: Analysis by lazy { analyse() }

    private fun analyse(): Analysis {
        var classFound = false
        var classDetail = ""
        var methodVisible = false
        var methodDetail = ""

        val clazz = try {
            val c = Class.forName("android.hardware.input.InputManager")
            classFound = true
            classDetail = "android.hardware.input.InputManager resolved"
            c
        } catch (t: Throwable) {
            classDetail = t::class.java.simpleName + ": " + (t.message ?: "no message")
            null
        }

        methodDetail = if (clazz == null) {
            "not looked up: the class did not resolve"
        } else {
            try {
                // getMethod on a blocklisted member is itself filtered from API 28 onward, so a
                // NoSuchMethodException here does NOT prove the method is absent from the build.
                val m = clazz.getMethod("injectInputEvent", android.view.InputEvent::class.java, Int::class.javaPrimitiveType)
                methodVisible = true
                "visible via reflection: " + m.toString() + " (NOT invoked)"
            } catch (t: NoSuchMethodException) {
                "NoSuchMethodException - on API " + Build.VERSION.SDK_INT + " this is most likely " +
                    "non-SDK interface filtering rather than the method being absent; the two are " +
                    "indistinguishable from here, so neither is claimed"
            } catch (t: Throwable) {
                t::class.java.simpleName + ": " + (t.message ?: "no message")
            }
        }

        var declared = false
        var protection = "unknown"
        var detail = ""
        try {
            val info = app.packageManager.getPermissionInfo(INJECT_EVENTS, 0)
            declared = true
            protection = protectionOf(info)
            detail = "read from PackageManager.getPermissionInfo"
        } catch (t: PackageManager.NameNotFoundException) {
            detail = "NameNotFoundException: not defined on this build"
        } catch (t: Throwable) {
            detail = t::class.java.simpleName + ": " + (t.message ?: "no message")
        }

        val held = try {
            app.checkSelfPermission(INJECT_EVENTS) == PackageManager.PERMISSION_GRANTED
        } catch (t: Throwable) {
            detail += "; checkSelfPermission " + t::class.java.simpleName
            false
        }

        return Analysis(classFound, classDetail, methodVisible, methodDetail, declared, protection, held, detail)
    }

    @Suppress("DEPRECATION")
    private fun protectionOf(info: PermissionInfo): String {
        val base = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            when (info.protection) {
                PermissionInfo.PROTECTION_NORMAL -> "normal"
                PermissionInfo.PROTECTION_DANGEROUS -> "dangerous"
                PermissionInfo.PROTECTION_SIGNATURE -> "signature"
                PermissionInfo.PROTECTION_SIGNATURE_OR_SYSTEM -> "signatureOrSystem"
                PermissionInfo.PROTECTION_INTERNAL -> "internal"
                else -> "protection(" + info.protection + ")"
            }
        } else {
            "protectionLevel(0x" + java.lang.Integer.toHexString(info.protectionLevel) + ")"
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.protectionFlags else 0
        val extra = buildList {
            if (flags and PermissionInfo.PROTECTION_FLAG_PRIVILEGED != 0) add("privileged")
            if (flags and PermissionInfo.PROTECTION_FLAG_DEVELOPMENT != 0) add("development")
            if (flags and PermissionInfo.PROTECTION_FLAG_APPOP != 0) add("appop")
        }
        return if (extra.isEmpty()) base else base + "|" + extra.joinToString("|")
    }

    override fun availability(): Pair<Boolean, String> = false to
        "Analysis only. This route is never exercised: injectInputEvent is gated by " +
            INJECT_EVENTS + " (" + analysis.protection + " on this build), which an installed app " +
            "cannot be granted. Calling it to watch it fail would add no information and would be an " +
            "attempt to use a privilege this app does not have."

    override suspend fun send(action: InputAction): InputAttempt = InputAttempt(
        routeName = routeName,
        tier = tier,
        action = describe(action),
        status = LabStatus.NOT_TESTED,
        observation = "Not attempted. " + analysis.methodDetail + "; " + INJECT_EVENTS + " = " +
            (if (analysis.permissionDeclared) analysis.protection else "not declared on this build") +
            ", held by this app = " + analysis.held + ".",
        conclusion = "This route is analysed, not exercised. The verdict for cross-app input is " +
            "carried by " + INJECT_EVENTS + ": it is signature-level on stock Android, so only the " +
            "platform signature, a privileged system image or root can hold it. NOT_TESTED is the " +
            "correct grade - the capability was not measured here, and its absence for this app is a " +
            "permission fact rather than something this route demonstrated.",
    ).record()

    fun facts(): List<Observation> {
        val cat = LabCategory.DEX
        val a = analysis
        return listOf(
            Observation(
                "dex.input.inject_events.protection",
                if (a.permissionDeclared) a.protection else "not declared on this build",
                if (a.permissionDeclared) LabStatus.CONFIRMED else LabStatus.NOT_TESTED,
                "PackageManager.getPermissionInfo(" + INJECT_EVENTS + "). " + a.permissionDetail +
                    ". This single value is what decides whether any app-tier route can reach another " +
                    "app's window.",
                cat,
            ),
            Observation(
                "dex.input.inject_events.held_by_this_app",
                a.held.toString(),
                LabStatus.CONFIRMED,
                "Context.checkSelfPermission. Expected false; a true here would mean the build grants " +
                    "a signature permission to an ordinary app.",
                cat,
            ),
            Observation(
                "dex.input.inputmanager.class_resolved",
                a.classFound.toString(),
                if (a.classFound) LabStatus.CONFIRMED else LabStatus.NOT_TESTED,
                a.classDetail,
                cat,
            ),
            Observation(
                "dex.input.inputmanager.inject_method_visible",
                a.methodVisible.toString(),
                // Never UNSUPPORTED: hidden-API filtering and genuine absence look identical here.
                if (a.methodVisible) LabStatus.CONFIRMED else LabStatus.NOT_TESTED,
                a.methodDetail + ". The method is never invoked by this app.",
                cat,
            ),
        )
    }

    companion object {
        const val INJECT_EVENTS = "android.permission.INJECT_EVENTS"
    }
}

// --------------------------------------------------------------------------------------------
// 3. Ordinary app, user-enabled: AccessibilityService
// --------------------------------------------------------------------------------------------

/**
 * The only unprivileged route that can leave this app's own windows.
 *
 * It is also a deliberately partial one, and the partiality is the finding:
 *
 *  - **Gestures work.** `dispatchGesture` is a real, documented, system-wide pointer capability.
 *    Taps and swipes are implemented properly here, with the completion callback awaited.
 *  - **Arbitrary keys do not.** An AccessibilityService has no key-injection API. The nearest thing,
 *    FLAG_REQUEST_FILTER_KEY_EVENTS, *filters* key events on their way past - it does not originate
 *    them - and this service does not request it. So a Meta press, a Ctrl+Esc, a Tab or an Enter
 *    cannot be sent from here at all, and this class says UNSUPPORTED-for-this-route rather than
 *    pretending. Only the four keys that have an exact global-action equivalent are mapped.
 *  - **Text does not.** Typing into another app's field means finding a focused editable node and
 *    calling ACTION_SET_TEXT, which needs window-content retrieval. The service config sets
 *    canRetrieveWindowContent=false on purpose: an instrument that can read every screen is a
 *    different kind of thing from one that can tap. That trade is stated in the NOT_TESTED note
 *    rather than quietly reversed.
 *
 * Which means the DeX "press Meta, type a name, press Enter" flow cannot be completed by this route,
 * and [InputRoutes.runFlow] will report exactly which step it died at instead of a single red verdict.
 */
class AccessibilityRoute(
    context: Context,
    /** Which display gestures should target; API 30+ only. Null means the default display. */
    var targetDisplayId: Int? = null,
) : InputRoute {

    private val app = context.applicationContext
    override val routeName = "AccessibilityService (gestures + global actions)"
    override val tier = PrivilegeTier.ORDINARY_APP

    /**
     * Three distinct states, because "you have not switched it on" and "it is on but you have not
     * armed it" are different instructions to the tester, and neither is a capability finding.
     */
    override fun availability(): Pair<Boolean, String> {
        val service = LabAccessibilityService.instance.value
        if (service == null) {
            val enabled = LabAccessibilityService.isEnabledInSettings(app)
            return false to if (enabled) {
                "Listed as enabled in Settings but the system has not connected it yet. Wait a moment, " +
                    "or toggle it off and on: " + LabAccessibilityService.SETTINGS_PATH
            } else {
                "Not enabled. Turn it on at: " + LabAccessibilityService.SETTINGS_PATH +
                    ". Nothing in this app can send input until you do."
            }
        }
        if (!LabAccessibilityService.armed.value) {
            return false to "Service connected but not armed. Enabling it in Settings is deliberately " +
                "not consent to send input - arm it in this screen first."
        }
        return true to "Connected and armed. Gestures and global actions can be dispatched; keys and " +
            "text still cannot (see the per-action reasons)."
    }

    override suspend fun send(action: InputAction): InputAttempt {
        val (ok, why) = availability()
        if (!ok) {
            return attempt(
                action,
                LabStatus.NOT_TESTED,
                why,
                "The route was not in a state to run. This is a circumstance, not a capability " +
                    "finding: nothing may be concluded about whether it would have worked.",
                0L,
            )
        }
        val service = LabAccessibilityService.instance.value
            ?: return attempt(
                action,
                LabStatus.NOT_TESTED,
                "The service disconnected between the availability check and the send.",
                "Race with the service lifecycle. Nothing may be concluded.",
                0L,
            )

        return when (action) {
            is InputAction.Key -> sendKey(service, action)
            is InputAction.Text -> textNotTested(action)
            is InputAction.Tap -> sendTap(service, action)
            is InputAction.Swipe -> sendSwipe(service, action)
            is InputAction.Global -> sendGlobal(service, action, describe(action))
        }
    }

    // -------- keys: only the four with an exact global-action equivalent ---------------------

    private fun sendKey(service: LabAccessibilityService, action: InputAction.Key): InputAttempt {
        // A modifier can never be expressed: performGlobalAction takes no meta state, so mapping
        // Meta+Tab onto GLOBAL_ACTION_RECENTS would be inventing an equivalence.
        if (action.meta != 0) {
            return attempt(
                action,
                LabStatus.UNSUPPORTED,
                "Modifier state 0x" + java.lang.Integer.toHexString(action.meta) + " cannot be expressed.",
                "An AccessibilityService has no key-injection API at all; the only keyboard-shaped " +
                    "thing it has is performGlobalAction, which takes no modifier. Claiming that " +
                    "GLOBAL_ACTION_RECENTS is 'Meta+Tab' would be a fabricated equivalence, so this " +
                    "route refuses the action. UNSUPPORTED for THIS route only - the shell tier sends it fine.",
                0L,
            )
        }
        val global = when (action.keyCode) {
            KeyEvent.KEYCODE_BACK -> AccessibilityService.GLOBAL_ACTION_BACK to "GLOBAL_ACTION_BACK"
            KeyEvent.KEYCODE_HOME -> AccessibilityService.GLOBAL_ACTION_HOME to "GLOBAL_ACTION_HOME"
            KeyEvent.KEYCODE_APP_SWITCH -> AccessibilityService.GLOBAL_ACTION_RECENTS to "GLOBAL_ACTION_RECENTS"
            KeyEvent.KEYCODE_NOTIFICATION -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS to "GLOBAL_ACTION_NOTIFICATIONS"
            else -> null
        } ?: return attempt(
            action,
            LabStatus.UNSUPPORTED,
            "No global action corresponds to key code " + action.keyCode + " (" + action.label + ").",
            "AccessibilityService cannot inject arbitrary key events. FLAG_REQUEST_FILTER_KEY_EVENTS " +
                "intercepts key events travelling past the service; it does not originate them, and " +
                "this service does not request it. So the DeX app-list hotkey, Tab, Escape and Enter " +
                "are all out of reach here. UNSUPPORTED for THIS route; see the adb-shell row for the " +
                "tier that can do it.",
            0L,
        )

        val (result, elapsed) = timed { service.globalAction(global.first) }
        return globalAttempt(describe(action) + " -> " + global.second, result, elapsed)
    }

    private fun sendGlobal(service: LabAccessibilityService, action: InputAction.Global, label: String): InputAttempt {
        val (result, elapsed) = timed { service.globalAction(action.action) }
        return globalAttempt(label, result, elapsed)
    }

    private fun globalAttempt(label: String, result: Boolean?, elapsed: Long): InputAttempt = when (result) {
        null -> InputAttempt(
            routeName, tier, label, LabStatus.NOT_TESTED,
            "The service refused because it is not armed.",
            "Circumstance, not capability.", elapsed,
        ).record()

        true -> InputAttempt(
            routeName, tier, label, LabStatus.OBSERVED,
            "performGlobalAction returned true.",
            "The platform accepted the action. It does not report where the action landed, and it is " +
                "not evidence that anything changed on the DeX display or on any external screen - " +
                "global actions are normally scoped to the focused display. Confirming an effect needs " +
                "the tester to look and record a marker.",
            elapsed,
        ).record()

        false -> InputAttempt(
            routeName, tier, label, LabStatus.UNSUPPORTED,
            "performGlobalAction returned false.",
            "The platform answered that it did not perform the action on this build. That is a real " +
                "negative for this action here; it may be display-scoped rather than absent everywhere.",
            elapsed,
        ).record()
    }

    // -------- text: deliberately not implemented --------------------------------------------

    private fun textNotTested(action: InputAction.Text): InputAttempt = attempt(
        action,
        LabStatus.NOT_TESTED,
        "Not attempted: typing into another app needs a focused editable node plus ACTION_SET_TEXT, " +
            "which requires window-content retrieval. This service declares canRetrieveWindowContent=false.",
        "A trade-off, not a platform limit: we could have requested content access and probably typed " +
            "the app name into the DeX search box. We did not, because a service that can read every " +
            "screen is a categorically different instrument from one that can tap, and this project " +
            "does not need the first to answer its question. NOT_TESTED is therefore the honest grade - " +
            "the capability was never measured. Enabling it would be a deliberate, documented change.",
        0L,
    )

    // -------- gestures: the part that genuinely works ----------------------------------------

    private suspend fun sendTap(service: LabAccessibilityService, action: InputAction.Tap): InputAttempt {
        val path = Path().apply {
            moveTo(action.x, action.y)
            // A one-pixel line rather than a bare moveTo: some builds reject a zero-length stroke,
            // and 1 px is far below touch slop so it is still a tap, not a drag.
            lineTo(action.x + 1f, action.y)
        }
        val start = SystemClock.elapsedRealtime()
        val result = service.gesture(path, durationMs = TAP_DURATION_MS, displayId = targetDisplayId)
        return gestureAttempt(describe(action), result, SystemClock.elapsedRealtime() - start)
    }

    private suspend fun sendSwipe(service: LabAccessibilityService, action: InputAction.Swipe): InputAttempt {
        val path = Path().apply {
            moveTo(action.x1, action.y1)
            lineTo(action.x2, action.y2)
        }
        val start = SystemClock.elapsedRealtime()
        val result = service.gesture(path, durationMs = action.durationMs, displayId = targetDisplayId)
        return gestureAttempt(describe(action), result, SystemClock.elapsedRealtime() - start)
    }

    private fun gestureAttempt(label: String, result: GestureResult, elapsed: Long): InputAttempt {
        val displaySuffix = targetDisplayId?.let { " [display " + it + "]" } ?: " [default display]"
        val (status, conclusion) = when (result.outcome) {
            GestureOutcome.COMPLETED -> LabStatus.OBSERVED to
                "The platform ran the stroke to completion and called onCompleted. That establishes " +
                    "that dispatchGesture is accepted and executed by this device" + displaySuffix +
                    ". It does NOT establish that anything moved, was pressed, or appeared on the DeX " +
                    "desktop or the head unit - only the tester can say that."

            GestureOutcome.CANCELLED -> LabStatus.OBSERVED to
                "The gesture was accepted and then cancelled (onCancelled). Distinct from a refusal: " +
                    "the dispatch path works, something interrupted this particular stroke - typically " +
                    "another gesture or a window taking over the touch stream."

            GestureOutcome.REFUSED -> LabStatus.UNSUPPORTED to
                "dispatchGesture returned false: the platform would not accept the gesture in this " +
                    "state" + displaySuffix + ". On a non-default display this most often means the " +
                    "display is not gesture-dispatchable for this service."

            GestureOutcome.TIMEOUT -> LabStatus.NOT_TESTED to
                "Neither callback arrived. The outcome is unknown, so nothing is claimed either way."

            GestureOutcome.NOT_ARMED -> LabStatus.NOT_TESTED to
                "The service refused because it is not armed. Circumstance, not capability."

            GestureOutcome.ERROR -> LabStatus.ERROR to
                "The route threw while building or dispatching the gesture. Out-of-bounds coordinates " +
                    "are the usual cause. Says nothing about the capability."
        }
        return InputAttempt(
            routeName, tier, label + displaySuffix, status,
            result.outcome.name + ": " + result.detail, conclusion, elapsed,
        ).record()
    }

    private fun attempt(
        action: InputAction,
        status: LabStatus,
        observation: String,
        conclusion: String,
        elapsed: Long,
    ) = InputAttempt(routeName, tier, describe(action), status, observation, conclusion, elapsed).record()

    fun facts(): List<Observation> {
        val cat = LabCategory.DEX
        val service = LabAccessibilityService.instance.value
        val enabled = LabAccessibilityService.isEnabledInSettings(app)
        val out = mutableListOf<Observation>()

        out += Observation(
            "dex.input.accessibility.enabled_in_settings",
            enabled.toString(),
            if (enabled) LabStatus.CONFIRMED else LabStatus.NOT_TESTED,
            if (enabled) {
                "Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES lists this service."
            } else {
                "Not listed, or the setting could not be read. This is a circumstance, never evidence " +
                    "that the route is unavailable on this device. Enable at: " +
                    LabAccessibilityService.SETTINGS_PATH
            },
            cat,
        )
        out += Observation(
            "dex.input.accessibility.connected",
            (service != null).toString(),
            if (service != null) LabStatus.CONFIRMED else LabStatus.NOT_TESTED,
            "The live service instance is the only signal that the system actually bound it.",
            cat,
        )
        out += Observation(
            "dex.input.accessibility.armed",
            LabAccessibilityService.armed.value.toString(),
            LabStatus.CONFIRMED,
            "In-app consent flag. Defaults to false and resets on every service lifecycle transition; " +
                "no route sends anything while it is false.",
            cat,
        )
        out += if (service != null) {
            Observation(
                "dex.input.accessibility.granted_capabilities",
                service.capabilitySummary().entries.joinToString(", ") { it.key + "=" + it.value },
                LabStatus.CONFIRMED,
                "Read from AccessibilityServiceInfo. canRetrieveWindowContent is false by design - " +
                    "that is why text entry is NOT_TESTED rather than implemented.",
                cat,
            )
        } else {
            Observation.notTested(
                "dex.input.accessibility.granted_capabilities", cat,
                "AccessibilityServiceInfo is only readable while the service is connected.",
            )
        }
        out += Observation(
            "dex.input.accessibility.key_injection",
            "absent",
            LabStatus.UNSUPPORTED,
            "AccessibilityService exposes no key-injection API. FLAG_REQUEST_FILTER_KEY_EVENTS filters " +
                "key events in transit rather than originating them, and this service does not request " +
                "it. Only BACK, HOME, RECENTS and NOTIFICATIONS have global-action equivalents; the DeX " +
                "app-list hotkey, Tab, Escape and Enter have none. This is a documented API absence, so " +
                "UNSUPPORTED is correct for this route.",
            cat,
        )
        out += Observation(
            "dex.input.accessibility.gesture_display_targeting",
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) "available (API " + Build.VERSION.SDK_INT + ")" else "unavailable",
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) LabStatus.CONFIRMED else LabStatus.UNSUPPORTED,
            "GestureDescription.Builder.setDisplayId exists from API 30. Below that a gesture can only " +
                "go to the default display, so a DeX secondary display cannot be targeted at all.",
            cat,
        )
        return out
    }

    companion object {
        private const val TAP_DURATION_MS = 40L
    }
}

// --------------------------------------------------------------------------------------------
// 4. adb shell tier: documented, never executed
// --------------------------------------------------------------------------------------------

/**
 * The tier that actually works, and that this app cannot reach.
 *
 * `adb shell input …` works because the shell runs as uid 2000, which holds INJECT_EVENTS. This app
 * does not, and `Runtime.exec("su")` or `Runtime.exec("input …")` from here would produce a refusal
 * that says nothing - a false negative dressed up as a measurement. So this route executes nothing
 * and instead hands the tester the exact command to run from a workstation, which is a genuinely
 * useful output: it is how the DeX hotkey flow can be verified independently of this app.
 */
class AdbShellRoute(context: Context, var targetDisplayId: Int? = null) : InputRoute {

    private val app = context.applicationContext
    override val routeName = "adb shell input (analysis only)"
    override val tier = PrivilegeTier.ADB_SHELL

    override fun availability(): Pair<Boolean, String> = false to
        "Not runnable from inside the app. `input` works because the adb shell is uid 2000 and holds " +
            "INJECT_EVENTS; this process is not. Nothing is executed here - the command is printed so " +
            "a tester can run it from a workstation over USB or wireless debugging."

    override suspend fun send(action: InputAction): InputAttempt = InputAttempt(
        routeName = routeName,
        tier = tier,
        action = describe(action),
        status = LabStatus.NOT_TESTED,
        observation = "Not executed. Command for a workstation: " + command(action),
        conclusion = "This route is documented, not exercised. Running it needs a second machine, so " +
            "NOT_TESTED is the correct grade - the app deliberately does not shell out, because a " +
            "refusal from an unprivileged process would be a false negative rather than a finding.",
    ).record()

    /** The exact command a developer would run. Shown in the UI for copying. */
    fun command(action: InputAction): String {
        val d = targetDisplayId?.let { " -d " + it } ?: ""
        return when (action) {
            is InputAction.Key ->
                if (action.meta == 0) {
                    "adb shell input" + d + " keyevent " + action.keyCode
                } else {
                    // `input keycombination` (API 30+) is the only way to hold a modifier; plain
                    // `input keyevent` cannot express one, which is exactly why a Meta-based DeX
                    // hotkey needs it.
                    "adb shell input" + d + " keycombination " +
                        metaKeyCodes(action.meta).joinToString(" ") + " " + action.keyCode +
                        "   # requires Android 11+; on older builds use two `sendevent` streams"
                }

            is InputAction.Text ->
                "adb shell input" + d + " text " + shellQuote(action.text.replace(" ", "%s")) +
                    "   # `input text` encodes a space as %s"

            is InputAction.Tap ->
                "adb shell input" + d + " tap " + action.x.toInt() + " " + action.y.toInt()

            is InputAction.Swipe ->
                "adb shell input" + d + " swipe " + action.x1.toInt() + " " + action.y1.toInt() + " " +
                    action.x2.toInt() + " " + action.y2.toInt() + " " + action.durationMs

            is InputAction.Global -> {
                val key = when (action.action) {
                    AccessibilityService.GLOBAL_ACTION_BACK -> KeyEvent.KEYCODE_BACK
                    AccessibilityService.GLOBAL_ACTION_HOME -> KeyEvent.KEYCODE_HOME
                    AccessibilityService.GLOBAL_ACTION_RECENTS -> KeyEvent.KEYCODE_APP_SWITCH
                    AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS -> KeyEvent.KEYCODE_NOTIFICATION
                    else -> null
                }
                if (key != null) {
                    "adb shell input" + d + " keyevent " + key
                } else {
                    "# no `input` equivalent for global action " + action.action + " (" + action.label + ")"
                }
            }
        }
    }

    /** Every command for one flow, in order, as a copyable block. */
    fun script(flow: HotkeyLaunchFlow): String = buildString {
        appendLine("# DeX app-launch flow: " + flow.description)
        appendLine("# Run from a workstation. Nothing in this app executes these.")
        flow.steps().forEachIndexed { i, step ->
            appendLine(command(step))
            if (i < flow.steps().size - 1) appendLine("sleep " + (flow.settleMs / 1000.0))
        }
    }

    private fun metaKeyCodes(meta: Int): List<Int> = buildList {
        if (meta and KeyEvent.META_META_ON != 0) add(KeyEvent.KEYCODE_META_LEFT)
        if (meta and KeyEvent.META_CTRL_ON != 0) add(KeyEvent.KEYCODE_CTRL_LEFT)
        if (meta and KeyEvent.META_ALT_ON != 0) add(KeyEvent.KEYCODE_ALT_LEFT)
        if (meta and KeyEvent.META_SHIFT_ON != 0) add(KeyEvent.KEYCODE_SHIFT_LEFT)
    }

    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    fun facts(): List<Observation> {
        val cat = LabCategory.DEX
        val sample = InputAction.Tap(100f, 200f)
        return listOf(
            Observation(
                "dex.input.adb.commands",
                "keyevent / text / tap / swipe / keycombination",
                LabStatus.NOT_TESTED,
                "Documented syntax, not executed by this app: `" + command(sample) + "`, " +
                    "`adb shell input text 'hello%sworld'`, `adb shell input keyevent 4`, " +
                    "`adb shell input swipe x1 y1 x2 y2 ms`. The shell holds INJECT_EVENTS; this app " +
                    "does not, so running it needs a workstation.",
                cat,
            ),
            Observation(
                "dex.input.adb.launch_on_display",
                "adb shell am start --display <id> -n <package>/<activity>",
                LabStatus.NOT_TESTED,
                "The shell-tier equivalent of route D: start another app's activity on a chosen " +
                    "display. Useful as the independent check on whether the DeX display accepts " +
                    "activities at all, separately from whether this app can put them there. " +
                    "Package " + app.packageName + " can only do this for its own activities, via " +
                    "ActivityOptions.setLaunchDisplayId.",
                cat,
            ),
        )
    }
}

// --------------------------------------------------------------------------------------------
// Registry
// --------------------------------------------------------------------------------------------

object InputRoutes {

    /**
     * All routes, most-capable-cross-app first.
     *
     * @param targetDisplayId the display gestures and shell commands should target. Pass the id of
     *   the DeX / external display when one has been identified; null means the default display.
     */
    fun all(context: Context, targetDisplayId: Int? = null): List<InputRoute> = listOf(
        AccessibilityRoute(context, targetDisplayId),
        OwnWindowRoute(context),
        AdbShellRoute(context, targetDisplayId),
        InputManagerReflectionRoute(context),
    )

    /**
     * The best route that can actually deliver input *outside this app* right now, or null.
     *
     * [OwnWindowRoute] is deliberately excluded even though its availability() is true: it can never
     * leave this process, so returning it as "the best route" for driving a DeX desktop would be the
     * exact misrepresentation this module exists to avoid. Null means "no route can do this now",
     * which is a real answer.
     */
    fun best(context: Context, targetDisplayId: Int? = null): InputRoute? =
        all(context, targetDisplayId)
            .firstOrNull { it is AccessibilityRoute && it.availability().first }

    /**
     * Runs the three steps of a DeX app-launch flow and reports each one separately.
     *
     * Why separately: "the hotkey never arrived" and "the hotkey arrived but the name did not type"
     * are different findings with different next steps, and a single combined verdict would hide
     * which one happened. The list returned always has exactly three entries, one per step.
     *
     * Why it stops early: once a step is not accepted, the desktop is in an unknown state. Typing an
     * app name into whatever happens to be focused, and then pressing Enter on it, could activate
     * something the tester did not ask for. The remaining steps are therefore reported NOT_TESTED
     * with the reason, never as failures they did not get to attempt.
     */
    suspend fun runFlow(
        context: Context,
        flow: HotkeyLaunchFlow,
        route: InputRoute,
    ): List<InputAttempt> {
        val labels = listOf("step 1/3 hotkey", "step 2/3 type name", "step 3/3 Enter")
        val steps = flow.steps()

        SessionLogger.log(
            LabCategory.DEX,
            "dex.input.flow_start",
            LabStatus.OBSERVED,
            mapOf(
                "value" to flow.description,
                "route" to route.routeName,
                "tier" to route.tier.name,
                "accessibilityEnabledInSettings" to
                    LabAccessibilityService.isEnabledInSettings(context).toString(),
                "accessibilityArmed" to LabAccessibilityService.armed.value.toString(),
            ),
        )

        val (available, why) = route.availability()
        if (!available) {
            return steps.mapIndexed { i, step ->
                InputAttempt(
                    route.routeName, route.tier, labels[i] + " - " + describe(step),
                    LabStatus.NOT_TESTED,
                    "Flow not started: " + why,
                    "The route was unavailable before the first step. Nothing may be concluded about " +
                        "any of the three steps.",
                ).record()
            }
        }

        val out = mutableListOf<InputAttempt>()
        var halted: String? = null

        steps.forEachIndexed { i, step ->
            if (halted != null) {
                out += InputAttempt(
                    route.routeName, route.tier, labels[i] + " - " + describe(step),
                    LabStatus.NOT_TESTED,
                    "Not attempted: " + halted,
                    "Deliberately skipped. After an unaccepted step the desktop state is unknown, and " +
                        "typing a name or pressing Enter into an unknown focus could activate something " +
                        "nobody asked for. Skipped is not failed.",
                ).record()
                return@forEachIndexed
            }

            val attempt = route.send(step)
            // The attempt already carries the route's own label; re-label it with the step so the
            // report can line the three up.
            out += attempt.copy(action = labels[i] + " - " + attempt.action)

            if (attempt.status != LabStatus.OBSERVED) {
                halted = labels[i] + " was graded " + attempt.status.name + " (" + attempt.observation + ")"
            } else if (i < steps.size - 1) {
                // The DeX app list needs time to appear and to filter as the name is typed.
                delay(flow.settleMs)
            }
        }

        SessionLogger.log(
            LabCategory.DEX,
            "dex.input.flow_complete",
            if (out.all { it.status == LabStatus.OBSERVED }) LabStatus.OBSERVED else LabStatus.NOT_TESTED,
            mapOf(
                "value" to out.joinToString(" | ") { it.action + "=" + it.status.name },
                "note" to "Every step accepted by the platform is still not evidence that the app " +
                    "launched. Look at the DeX screen and record a marker for what actually happened.",
                "route" to route.routeName,
            ),
        )
        return out
    }

    /**
     * Everything this module knows without sending anything. Safe to call on screen open - it
     * reads state, arms nothing and dispatches nothing.
     */
    fun observations(context: Context, targetDisplayId: Int? = null): List<Observation> {
        val cat = LabCategory.DEX
        val out = mutableListOf<Observation>()
        val routes = all(context, targetDisplayId)

        routes.forEach { route ->
            val (ok, why) = try {
                route.availability()
            } catch (t: Throwable) {
                false to (t::class.java.simpleName + ": " + (t.message ?: "no message"))
            }
            out += Observation(
                "dex.input.route." + route.routeName.substringBefore(' ').lowercase() + ".available",
                ok.toString(),
                // Availability is a fact about right now, not about the capability. An unavailable
                // route is never UNSUPPORTED on that basis alone.
                if (ok) LabStatus.CONFIRMED else LabStatus.NOT_TESTED,
                route.routeName + " [" + route.tier.label + "] - " + why,
                cat,
            )
        }

        routes.forEach { route ->
            out += when (route) {
                is AccessibilityRoute -> route.facts()
                is OwnWindowRoute -> route.facts()
                is AdbShellRoute -> route.facts()
                is InputManagerReflectionRoute -> route.facts()
                else -> emptyList()
            }
        }

        val best = best(context, targetDisplayId)
        out += Observation(
            "dex.input.best_route",
            best?.routeName ?: "none",
            if (best != null) LabStatus.CONFIRMED else LabStatus.NOT_TESTED,
            if (best != null) {
                "The only unprivileged cross-app route, and it can dispatch gestures and four global " +
                    "actions - not arbitrary keys and not text."
            } else {
                "No route can currently send input outside this app. The own-window Instrumentation " +
                    "route is excluded from this answer on purpose: it works, but only into this app's " +
                    "own windows, so it cannot drive a DeX desktop."
            },
            cat,
        )

        out += Observation(
            "dex.input.dex_hotkey_flow_reachable",
            "no at ordinary-app tier",
            LabStatus.UNSUPPORTED,
            "The DeX flow 'press " + DexHotkey.APP_LIST_META.label + ", type a name, press Enter' needs " +
                "two things no unprivileged route has: an arbitrary key event and text into another " +
                "app's field. The accessibility route has neither by design, and Instrumentation cannot " +
                "leave this process. This is an API-level answer about the routes, NOT a measurement " +
                "against a DeX session - whether DeX itself honours those keys from a real keyboard is " +
                "untested here and not in question.",
            cat,
        )
        return out
    }
}
