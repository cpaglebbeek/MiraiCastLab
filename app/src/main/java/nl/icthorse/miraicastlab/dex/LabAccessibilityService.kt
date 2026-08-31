package nl.icthorse.miraicastlab.dex

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.provider.Settings
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import nl.icthorse.miraicastlab.core.LabCategory
import nl.icthorse.miraicastlab.core.LabStatus
import nl.icthorse.miraicastlab.core.SessionLogger
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * The one cross-application input route an unprivileged Android app actually has.
 *
 * Why this exists at all, stated plainly because SAFETY.md's privacy section says "no accessibility
 * service is used as a workaround": this service is not a workaround for anything. It is the
 * *instrument* of a single measurement - can an ordinary app deliver keyboard-and-mouse-shaped input
 * to a Samsung DeX desktop? - and it is deliberately built to be the weakest possible version of
 * itself:
 *
 *  - `canRetrieveWindowContent=false` in the service config, so it cannot read a single character of
 *    any screen. That is the difference between an instrument and a keylogger, and it costs us
 *    ACTION_SET_TEXT, which is why [InputAction.Text] is reported NOT_TESTED rather than implemented.
 *  - `canRequestFilterKeyEvents=false`, so it cannot observe key input either.
 *  - `accessibilityEventTypes=typeWindowStateChanged` only, and [onAccessibilityEvent] ignores even
 *    that. The subscription exists because the platform requires a non-empty event mask; nothing is
 *    read from the events.
 *  - it does nothing at all until the tester enables it in Settings, AND separately arms it in the
 *    app. Enabling is not consent to send input; [armed] is.
 *
 * Nothing here touches the vehicle, a bus, or a protocol. It dispatches taps and system-level global
 * actions on this phone, which is what a mouse and a few keyboard keys do.
 */
class LabAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Bind the logger here too: the service can be connected by the system before the Activity
        // ever runs, and evidence written only to memory does not survive that.
        runCatching { SessionLogger.init(applicationContext) }

        _instance.value = this

        // A fresh connection always starts disarmed. The service can be reconnected by the system
        // (config change, service restart, user toggling it) without the tester intending to send
        // anything, so consent does not survive a reconnect.
        setArmed(false, "service connected")

        val info = runCatching { serviceInfo }.getOrNull()
        SessionLogger.log(
            LabCategory.DEX,
            "dex.accessibility.connected",
            LabStatus.CONFIRMED,
            mapOf(
                "value" to "connected",
                "canPerformGestures" to capabilityFlag(info, AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES).toString(),
                "canRetrieveWindowContent" to capabilityFlag(info, AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT).toString(),
                "canRequestFilterKeyEvents" to capabilityFlag(info, AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_FILTER_KEY_EVENTS).toString(),
                "armed" to "false",
                "note" to "The service is connected but disarmed; it cannot send anything yet.",
            ),
        )
    }

    /**
     * Deliberately empty.
     *
     * The manifest config subscribes to typeWindowStateChanged only because an accessibility service
     * must declare at least one event type to be installable. Reading those events would tell us
     * which app the user has in front of them, which is precisely the kind of information this
     * project has no business collecting. So the event is dropped without inspection - not logged,
     * not counted, not correlated.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    /** Required override. Nothing is in flight that could need interrupting. */
    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        setArmed(false, "service unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        _instance.value = null
        setArmed(false, "service destroyed")
        SessionLogger.log(
            LabCategory.DEX,
            "dex.accessibility.disconnected",
            LabStatus.OBSERVED,
            mapOf("value" to "destroyed", "note" to "No route can send input until it is enabled and armed again."),
        )
        super.onDestroy()
    }

    // ------------------------------------------------------------------ actions

    /**
     * Dispatches a gesture and waits for the platform's own verdict on it.
     *
     * The distinction between the four outcomes is the whole value of this method:
     *  - [GestureOutcome.REFUSED]   dispatchGesture returned false - the service was not in a state
     *                               where the platform would even accept the gesture.
     *  - [GestureOutcome.CANCELLED] accepted, then cancelled - typically another gesture, or a
     *                               window that took over the touch stream.
     *  - [GestureOutcome.COMPLETED] the platform ran the stroke to the end. This still says nothing
     *                               about what appeared on any screen; see InputAttempt.
     *  - [GestureOutcome.TIMEOUT]   neither callback arrived, which is a finding of its own.
     */
    suspend fun gesture(
        path: Path,
        durationMs: Long,
        displayId: Int? = null,
        timeoutMs: Long = DEFAULT_GESTURE_TIMEOUT_MS,
    ): GestureResult {
        if (!armed.value) {
            return GestureResult(GestureOutcome.NOT_ARMED, "The service is connected but not armed in the app.")
        }

        val description = try {
            val builder = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs.coerceAtLeast(1L)))
            // Targeting a specific display is the point when a DeX desktop is a second display, but
            // the API only exists from API 30. Below that the gesture goes to the default display
            // and the report must not pretend otherwise.
            if (displayId != null && displayId != Display.DEFAULT_DISPLAY) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    builder.setDisplayId(displayId)
                } else {
                    return GestureResult(
                        GestureOutcome.REFUSED,
                        "GestureDescription.Builder.setDisplayId requires API 30; this build is API " +
                            Build.VERSION.SDK_INT + ", so a non-default display cannot be targeted.",
                    )
                }
            }
            builder.build()
        } catch (t: Throwable) {
            // Out-of-bounds coordinates, an empty path and a multi-contour path all land here.
            return GestureResult(
                GestureOutcome.ERROR,
                t::class.java.simpleName + ": " + (t.message ?: "no message"),
            )
        }

        val result = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val settled = AtomicBoolean(false)
                val callback = object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (settled.compareAndSet(false, true)) {
                            cont.resume(GestureResult(GestureOutcome.COMPLETED, "onCompleted"))
                        }
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (settled.compareAndSet(false, true)) {
                            cont.resume(GestureResult(GestureOutcome.CANCELLED, "onCancelled"))
                        }
                    }
                }
                val accepted = try {
                    // null handler = callbacks on the service's main thread, which is fine: we only
                    // resume a continuation from it.
                    dispatchGesture(description, callback, null)
                } catch (t: Throwable) {
                    if (settled.compareAndSet(false, true)) {
                        cont.resume(
                            GestureResult(
                                GestureOutcome.ERROR,
                                t::class.java.simpleName + ": " + (t.message ?: "no message"),
                            ),
                        )
                    }
                    return@suspendCancellableCoroutine
                }
                if (!accepted && settled.compareAndSet(false, true)) {
                    cont.resume(GestureResult(GestureOutcome.REFUSED, "dispatchGesture returned false"))
                }
            }
        }

        return result ?: GestureResult(
            GestureOutcome.TIMEOUT,
            "Neither onCompleted nor onCancelled arrived within " + timeoutMs + " ms.",
        )
    }

    /**
     * Performs one of the AccessibilityService global actions.
     *
     * Returns null when the service is not armed, so "we did not try" stays distinguishable from
     * "the platform said no".
     */
    fun globalAction(action: Int): Boolean? {
        if (!armed.value) return null
        return try {
            performGlobalAction(action)
        } catch (t: Throwable) {
            SessionLogger.log(
                LabCategory.DEX,
                "dex.accessibility.global_action_threw",
                LabStatus.ERROR,
                mapOf("value" to action.toString(), "note" to t::class.java.simpleName + ": " + (t.message ?: "")),
            )
            false
        }
    }

    /** Capabilities the platform actually granted this service, for the report. */
    fun capabilitySummary(): Map<String, String> {
        val info = runCatching { serviceInfo }.getOrNull()
            ?: return mapOf("serviceInfo" to "null (not connected yet)")
        return mapOf(
            "canPerformGestures" to capabilityFlag(info, AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES).toString(),
            "canRetrieveWindowContent" to capabilityFlag(info, AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT).toString(),
            "canRequestFilterKeyEvents" to capabilityFlag(info, AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_FILTER_KEY_EVENTS).toString(),
            "eventTypes" to "0x" + java.lang.Integer.toHexString(info.eventTypes),
            "flags" to "0x" + java.lang.Integer.toHexString(info.flags),
        )
    }

    private fun capabilityFlag(info: AccessibilityServiceInfo?, capability: Int): Boolean =
        info != null && (info.capabilities and capability) != 0

    // ------------------------------------------------------------------ companion

    companion object {

        private const val DEFAULT_GESTURE_TIMEOUT_MS = 5_000L

        private val _instance = MutableStateFlow<LabAccessibilityService?>(null)

        /**
         * The live service instance, or null when the system has not connected it.
         *
         * This is the only honest availability signal: the Settings string can list the service
         * while the system has not (yet) bound it, and a route that assumed otherwise would report
         * "ready" for something that cannot run.
         */
        val instance: StateFlow<LabAccessibilityService?> = _instance.asStateFlow()

        private val _armed = MutableStateFlow(false)

        /**
         * Explicit in-app consent to send input. Defaults to false and returns to false on every
         * service lifecycle transition: having the service enabled in Settings is deliberately not
         * enough to make this app send a single tap.
         */
        val armed: StateFlow<Boolean> = _armed.asStateFlow()

        /** The exact place a tester has to go, in both AOSP and One UI wording. */
        const val SETTINGS_PATH: String =
            "Settings > Accessibility > Installed apps (One UI) / Downloaded apps (AOSP) > " +
                "MiraiCast Lab → DeX control > On"

        fun component(context: Context): ComponentName =
            ComponentName(context.applicationContext, LabAccessibilityService::class.java)

        /**
         * Arms or disarms the service. Every transition is logged, because "when was it armed" is
         * part of the evidence for every attempt that follows.
         */
        fun setArmed(value: Boolean, reason: String) {
            val changed = _armed.value != value
            _armed.value = value
            if (changed || value) {
                SessionLogger.log(
                    LabCategory.DEX,
                    if (value) "dex.accessibility.armed" else "dex.accessibility.disarmed",
                    LabStatus.OBSERVED,
                    mapOf("value" to value.toString(), "reason" to reason),
                )
            }
        }

        /**
         * Whether the tester has enabled this service in Settings.
         *
         * Read from Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, which is a colon-separated list
         * of flattened component names. Both the long and short flattenings are accepted because
         * different One UI versions have written both.
         */
        fun isEnabledInSettings(context: Context): Boolean = try {
            val enabled = Settings.Secure.getString(
                context.applicationContext.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ).orEmpty()
            val me = component(context)
            val long = me.flattenToString()
            val short = me.flattenToShortString()
            enabled.split(':').any { it.trim().equals(long, ignoreCase = true) || it.trim().equals(short, ignoreCase = true) }
        } catch (t: Throwable) {
            // A read failure is not a "no"; callers treat false as "not established", never as
            // evidence that the service is absent.
            false
        }

        /** Whether the global accessibility master switch reports as on. Context only. */
        fun masterSwitchOn(context: Context): Boolean = try {
            Settings.Secure.getInt(
                context.applicationContext.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                0,
            ) == 1
        } catch (t: Throwable) {
            false
        }

        /** The Settings screen where the tester turns the service on. Never opened automatically. */
        fun settingsIntent(): Intent =
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

/** How a dispatched gesture ended, as reported by the platform itself. */
enum class GestureOutcome {
    /** The stroke ran to the end. Still not proof that anything visible happened. */
    COMPLETED,

    /** Accepted, then cancelled by the system or by another gesture. */
    CANCELLED,

    /** dispatchGesture returned false: the platform would not take it. */
    REFUSED,

    /** Neither callback arrived in time. */
    TIMEOUT,

    /** The service is connected but the tester has not armed it in the app. */
    NOT_ARMED,

    /** Building or dispatching threw. Says nothing about the capability. */
    ERROR,
}

/** One gesture dispatch result plus the literal reason string it came with. */
data class GestureResult(val outcome: GestureOutcome, val detail: String)
