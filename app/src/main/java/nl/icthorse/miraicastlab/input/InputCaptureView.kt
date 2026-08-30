package nl.icthorse.miraicastlab.input

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * One captured input event, already decoded so nothing has to be re-derived in the UI or the report.
 *
 * [uptimeDeltaMs] is the interesting one for this project: Android stamps a locally generated event
 * with the uptime clock at the moment the driver produced it, so the delta to "now" is normally a
 * couple of milliseconds. An event that was injected on behalf of a remote sink (a UIBC-style back
 * channel) travelled over the air first, so a consistently large delta is itself evidence about
 * where the event came from - it is recorded, never used to claim a capability on its own.
 */
data class CapturedEvent(
    val seq: Long,
    val hook: String,
    val kind: String,
    val typeName: String,
    val deviceId: Int,
    val deviceName: String,
    val deviceDescriptor: String?,
    val deviceIdentity: String,
    val deviceVirtual: Boolean?,
    val deviceExternal: Boolean?,
    val source: Int,
    val sourceNames: String,
    val x: Float,
    val y: Float,
    val rawX: Float,
    val rawY: Float,
    val pressure: Float,
    val size: Float,
    val toolType: String,
    val buttonState: String,
    val metaState: String,
    val keyCode: String?,
    val eventTimeMs: Long,
    val downTimeMs: Long,
    val uptimeDeltaMs: Long,
    val isDownLike: Boolean,
    val isMove: Boolean,
    val wallClockMs: Long,
) {
    /** Compact single line for the on-screen monitor and the MonoBlock evidence dump. */
    fun oneLine(): String = buildString {
        append(String.format(Locale.US, "%04d", seq)).append(' ')
        append(hook).append(' ')
        append(typeName).append(' ')
        append("dev=").append(deviceId).append(':').append(deviceName).append(' ')
        append("src=").append(sourceNames).append(' ')
        if (kind == "motion") {
            append(String.format(Locale.US, "xy=(%.1f,%.1f) raw=(%.1f,%.1f) ", x, y, rawX, rawY))
            append(String.format(Locale.US, "p=%.2f sz=%.2f ", pressure, size))
            append("tool=").append(toolType).append(' ')
            append("btn=").append(buttonState).append(' ')
        } else {
            append("key=").append(keyCode ?: "-").append(' ')
        }
        append("meta=").append(metaState).append(' ')
        append("dt=").append(uptimeDeltaMs).append("ms")
    }

    /** Flattened for the structured log (spec section 13 "details"). */
    fun details(): Map<String, String> = mapOf(
        "seq" to seq.toString(),
        "hook" to hook,
        "kind" to kind,
        "type" to typeName,
        "device_id" to deviceId.toString(),
        "device_name" to deviceName,
        "device_identity" to deviceIdentity,
        "device_virtual" to (deviceVirtual?.toString() ?: "unknown"),
        "device_external" to (deviceExternal?.toString() ?: "unreadable"),
        "source" to ("0x" + Integer.toHexString(source)),
        "source_names" to sourceNames,
        "x" to String.format(Locale.US, "%.2f", x),
        "y" to String.format(Locale.US, "%.2f", y),
        "raw_x" to String.format(Locale.US, "%.2f", rawX),
        "raw_y" to String.format(Locale.US, "%.2f", rawY),
        "pressure" to String.format(Locale.US, "%.3f", pressure),
        "size" to String.format(Locale.US, "%.3f", size),
        "tool_type" to toolType,
        "button_state" to buttonState,
        "meta_state" to metaState,
        "key_code" to (keyCode ?: "-"),
        "event_time_ms" to eventTimeMs.toString(),
        "down_time_ms" to downTimeMs.toString(),
        "uptime_delta_ms" to uptimeDeltaMs.toString(),
    )
}

/**
 * A View that swallows nothing and reports everything (spec section 2.6 B).
 *
 * Compose's pointer input pipeline normalises events long before a composable sees them, which
 * loses exactly the fields this experiment is about: the originating device id, the decoded source,
 * the tool type and the button state. So the capture surface is a plain View hosted with
 * AndroidView, overriding every dispatch hook a remote back channel could plausibly arrive on:
 * touch, generic motion (mouse wheel/joystick), hover (a mouse pointer moving without a button),
 * captured pointer (relative mouse), and the key path including DPAD focus navigation.
 *
 * The view claims focus so key events reach it; BACK is explicitly *not* consumed so the tester can
 * always leave the screen.
 */
@SuppressLint("ViewConstructor")
class InputCaptureView(context: Context) : View(context) {

    /** Callback on the main thread for every event this view sees. */
    var onEvent: ((CapturedEvent) -> Unit)? = null

    /** When false the view still consumes events but reports none - used while a run is idle. */
    var capturing: Boolean = true

    private val seq = AtomicLong(0)
    private val deviceCache = HashMap<Int, InputDeviceFacts?>()

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        isLongClickable = true
        // Haptics on every probe touch would be noise during a vehicle test.
        isHapticFeedbackEnabled = false
        contentDescription = "Input capture surface"
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestFocus()
    }

    /** Devices come and go during a mirroring session; the cache must not outlive that. */
    fun clearDeviceCache() {
        deviceCache.clear()
    }

    fun grabFocus(): Boolean {
        isFocusableInTouchMode = true
        return requestFocus()
    }

    /** Relative-mouse capture. Only meaningful with a real pointer device attached. */
    fun setPointerCapture(enabled: Boolean) {
        try {
            if (enabled) requestPointerCapture() else releasePointerCapture()
        } catch (t: Throwable) {
            // A device without a pointer simply refuses; that is data for the caller's log, not a crash.
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        report("onTouchEvent", event)
        if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        report("onGenericMotionEvent", event)
        return true
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        report("onHoverEvent", event)
        return true
    }

    /** API 26+; only delivered while pointer capture is held. */
    override fun onCapturedPointerEvent(event: MotionEvent): Boolean {
        report("onCapturedPointerEvent", event)
        return true
    }

    override fun onPointerCaptureChange(hasCapture: Boolean) {
        super.onPointerCaptureChange(hasCapture)
        onEvent?.let { cb ->
            if (capturing) cb(syntheticKeyLike("onPointerCaptureChange", "capture=" + hasCapture))
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        report("dispatchKeyEvent", event)
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        report("onKeyDown", event)
        // BACK must stay usable: the tester may be sitting in a car with the phone mirrored.
        if (keyCode == KeyEvent.KEYCODE_BACK) return super.onKeyDown(keyCode, event)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        report("onKeyUp", event)
        if (keyCode == KeyEvent.KEYCODE_BACK) return super.onKeyUp(keyCode, event)
        return true
    }

    private fun facts(deviceId: Int): InputDeviceFacts? =
        deviceCache.getOrPut(deviceId) { InputFacts.factsFor(deviceId) }

    private fun report(hook: String, e: MotionEvent) {
        val cb = onEvent ?: return
        if (!capturing) return
        val f = facts(e.deviceId)
        val now = SystemClock.uptimeMillis()
        val action = e.actionMasked
        val captured = CapturedEvent(
            seq = seq.incrementAndGet(),
            hook = hook,
            kind = "motion",
            typeName = MotionEvent.actionToString(action),
            deviceId = e.deviceId,
            deviceName = f?.name ?: nameOf(e.deviceId),
            deviceDescriptor = f?.descriptor,
            deviceIdentity = f?.identity ?: ("id:" + e.deviceId),
            deviceVirtual = f?.isVirtual,
            deviceExternal = f?.external,
            source = e.source,
            sourceNames = InputFacts.decodeSources(e.source).joinToString("|"),
            x = e.x,
            y = e.y,
            rawX = e.rawX,
            rawY = e.rawY,
            pressure = e.pressure,
            size = e.size,
            toolType = if (e.pointerCount > 0) InputFacts.decodeToolType(e.getToolType(0)) else "none",
            buttonState = InputFacts.decodeButtonState(e.buttonState),
            metaState = InputFacts.decodeMetaState(e.metaState),
            keyCode = null,
            eventTimeMs = e.eventTime,
            downTimeMs = e.downTime,
            uptimeDeltaMs = now - e.eventTime,
            isDownLike = action == MotionEvent.ACTION_DOWN ||
                action == MotionEvent.ACTION_POINTER_DOWN ||
                action == MotionEvent.ACTION_BUTTON_PRESS,
            isMove = action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_HOVER_MOVE,
            wallClockMs = System.currentTimeMillis(),
        )
        cb(captured)
    }

    private fun report(hook: String, e: KeyEvent) {
        val cb = onEvent ?: return
        if (!capturing) return
        val f = facts(e.deviceId)
        val now = SystemClock.uptimeMillis()
        val captured = CapturedEvent(
            seq = seq.incrementAndGet(),
            hook = hook,
            kind = "key",
            typeName = if (e.action == KeyEvent.ACTION_DOWN) "KEY_DOWN"
            else if (e.action == KeyEvent.ACTION_UP) "KEY_UP" else "KEY_MULTIPLE",
            deviceId = e.deviceId,
            deviceName = f?.name ?: nameOf(e.deviceId),
            deviceDescriptor = f?.descriptor,
            deviceIdentity = f?.identity ?: ("id:" + e.deviceId),
            deviceVirtual = f?.isVirtual,
            deviceExternal = f?.external,
            source = e.source,
            sourceNames = InputFacts.decodeSources(e.source).joinToString("|"),
            x = Float.NaN,
            y = Float.NaN,
            rawX = Float.NaN,
            rawY = Float.NaN,
            pressure = Float.NaN,
            size = Float.NaN,
            toolType = "-",
            buttonState = "-",
            metaState = InputFacts.decodeMetaState(e.metaState),
            keyCode = KeyEvent.keyCodeToString(e.keyCode),
            eventTimeMs = e.eventTime,
            downTimeMs = e.downTime,
            uptimeDeltaMs = now - e.eventTime,
            isDownLike = e.action == KeyEvent.ACTION_DOWN,
            isMove = false,
            wallClockMs = System.currentTimeMillis(),
        )
        cb(captured)
    }

    /** For state changes that are not themselves an input event but belong in the same timeline. */
    private fun syntheticKeyLike(hook: String, note: String): CapturedEvent = CapturedEvent(
        seq = seq.incrementAndGet(),
        hook = hook,
        kind = "state",
        typeName = note,
        deviceId = -1,
        deviceName = "(view)",
        deviceDescriptor = null,
        deviceIdentity = "view",
        deviceVirtual = null,
        deviceExternal = null,
        source = InputDevice.SOURCE_UNKNOWN,
        sourceNames = "-",
        x = Float.NaN,
        y = Float.NaN,
        rawX = Float.NaN,
        rawY = Float.NaN,
        pressure = Float.NaN,
        size = Float.NaN,
        toolType = "-",
        buttonState = "-",
        metaState = "-",
        keyCode = null,
        eventTimeMs = SystemClock.uptimeMillis(),
        downTimeMs = 0L,
        uptimeDeltaMs = 0L,
        isDownLike = false,
        isMove = false,
        wallClockMs = System.currentTimeMillis(),
    )

    private fun nameOf(deviceId: Int): String = try {
        InputDevice.getDevice(deviceId)?.name ?: ("unknown(" + deviceId + ")")
    } catch (t: Throwable) {
        "unknown(" + deviceId + ")"
    }
}
