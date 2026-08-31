package nl.icthorse.miraicastlab.dex

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import nl.icthorse.miraicastlab.core.LabCategory
import nl.icthorse.miraicastlab.core.LabStatus
import nl.icthorse.miraicastlab.core.Observation
import nl.icthorse.miraicastlab.core.PrivilegeTier
import nl.icthorse.miraicastlab.core.SessionLogger
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The one public Android API with which an ordinary app really does send keyboard and mouse reports:
 * [BluetoothHidDevice] (API 28; this app's minSdk is 29, so the class is always present and no
 * version guard is needed for it).
 *
 * ## What this route is, and what it is not
 *
 * [BluetoothHidDevice] registers this phone in the HID **device** role - it makes the phone *be* a
 * keyboard and mouse. The reports it sends leave the phone over Bluetooth and arrive at a paired
 * **host**: a PC, a tablet, possibly a head unit that accepts a Bluetooth keyboard.
 *
 * A phone cannot be a Bluetooth HID host to itself. There is no loopback: Android exposes the device
 * role and the host role as separate profiles on separate ends of a radio link, and nothing this
 * object sends can arrive at the DeX desktop running on this same phone. So this route can drive a
 * *remote* desktop, and it can never drive the *local* DeX desktop that the research question is
 * about. That is not a disappointment or a bug; it is the finding, and it is exactly why the Intent
 * and AccessibilityService routes exist alongside it.
 *
 * Whether the Toyota Mirai head unit accepts a Bluetooth HID keyboard at all is **NOT_TESTED**. No
 * Mirai was paired while this code was written and the question is not answered by guessing.
 *
 * ## Delivery is not effect
 *
 * Every method here can, at best, establish that the local Bluetooth stack accepted a report for
 * transmission. `sendReport()` returning true says nothing about whether the host processed it or
 * whether a character appeared anywhere. Hence [InputAttempt.provesVisibleEffect] is false and every
 * conclusion below stops at "accepted".
 *
 * ## Safety
 *
 * - Pairing is never initiated. [connect] refuses any address that is not already bonded.
 * - Nothing is connected or sent without an explicit call - registration and [connect] are the arm;
 *   [send] refuses while either is missing. Nothing here runs on screen open: [observations] and
 *   [bondedHosts] are read-only and touch no radio state.
 * - Bluetooth addresses and device names never reach [SessionLogger]. Hosts appear in the log as an
 *   opaque per-process token (see [hostToken]); addresses are returned to the UI only, so the tester
 *   can pick a host on their own screen.
 */
object HidKeyboard {

    /** Route name used in every [InputAttempt] and log key. */
    const val ROUTE_NAME = "Bluetooth HID (BluetoothHidDevice)"

    /** The finding that decides what this route is worth for the DeX question. */
    const val NOT_A_LOCAL_ROUTE =
        "A phone cannot be a Bluetooth HID host to itself: BluetoothHidDevice puts this phone in the " +
            "HID device role and its reports leave over the air to a paired host, so nothing it sends " +
            "can reach the DeX desktop running on this same phone."

    /** Appended to every send conclusion. The gap between delivery and effect lives here. */
    private const val DELIVERY_CAVEAT =
        "This records only that the local Bluetooth stack accepted the report for transmission. " +
            "Whether the host processed it, and whether anything changed on its screen, is not " +
            "observable from this app and has to be recorded by the tester."

    /** HID usages are key positions; the host applies its own layout to them. */
    private const val LAYOUT_CAVEAT =
        "Typed text assumes the host uses a US-QWERTY-compatible layout: a HID usage is a key " +
            "position, not a character, and the host decides what it produces."

    private const val SDP_NAME = "MiraiCast Lab"
    private const val SDP_DESCRIPTION = "Capability lab keyboard and mouse"
    private const val SDP_PROVIDER = "MiraiCast Lab"

    private const val PROXY_TIMEOUT_MS = 6_000L
    private const val REGISTER_TIMEOUT_MS = 6_000L
    private const val CONNECT_TIMEOUT_MS = 20_000L

    /** How long a key is held, and the gap before the next one. Short, but not zero: a host that
     *  debounces needs a press and a release it can distinguish. */
    private const val KEY_HOLD_MS = 12L
    private const val KEY_GAP_MS = 18L

    /** Upper bound on the pointer steps one swipe may generate, so a huge delta cannot spin. */
    private const val MAX_POINTER_STEPS = 64

    private val _state = MutableStateFlow("idle - not registered")

    /** Serialises register / connect / send so two callers cannot interleave reports. */
    private val mutex = Mutex()

    @Volatile private var appContext: Context? = null
    @Volatile private var proxy: BluetoothHidDevice? = null
    @Volatile private var serviceListener: BluetoothProfile.ServiceListener? = null
    @Volatile private var appRegistered = false
    @Volatile private var connectedHost: BluetoothDevice? = null

    private val appStatusSignal = AtomicReference<CompletableDeferred<Boolean>?>(null)
    private val connectSignal = AtomicReference<CompletableDeferred<Int>?>(null)

    /** Callbacks arrive on this executor, never on the main thread. */
    private val callbackExecutor: Executor by lazy {
        Executors.newSingleThreadExecutor { r -> Thread(r, "miraicastlab-hid").apply { isDaemon = true } }
    }

    /**
     * Per-process random salt for [hostToken]. A salted digest is not reversible by anyone outside
     * this process - a bare MAC digest would be, the address space is small enough to enumerate -
     * and it is deliberately not stable across runs, so nothing in the evidence files can be joined
     * up into a device history.
     */
    private val tokenSalt: String = UUID.randomUUID().toString()

    // ------------------------------------------------------------------ public surface

    /**
     * Whether the platform could offer this route at all: a Bluetooth adapter has to exist.
     *
     * Whether the *HID device role* is supported by this build's Bluetooth stack cannot be asked
     * directly; the only honest test is [register], where `getProfileProxy(HID_DEVICE)` answers.
     */
    fun isSupported(context: Context): Boolean {
        appContext = context.applicationContext
        val hasFeature = try {
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)
        } catch (t: Throwable) {
            false
        }
        return hasFeature && adapter(context) != null
    }

    /**
     * Acquires the HID_DEVICE profile proxy and registers this app's SDP record (a combined
     * keyboard+mouse descriptor, see [HidReportDescriptor]).
     *
     * Registration publishes an SDP record and nothing else: no pairing, no connection, no input.
     */
    suspend fun register(context: Context): Observation = mutex.withLock {
        appContext = context.applicationContext
        val key = "dex.hid.register"

        val adapter = adapter(context)
            ?: return@withLock unsupported(
                key,
                "getSystemService(BluetoothManager).adapter returned null: this device has no " +
                    "Bluetooth adapter, so the HID device role cannot exist here.",
            )

        val missing = missingPermission(context)
        if (missing != null) {
            return@withLock notTested(
                key,
                "The route needs " + missing + ", which is not granted" +
                    (if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                        " (below API 31 the legacy android.permission.BLUETOOTH is required and is " +
                            "not declared in this build's manifest)"
                    } else "") +
                    ". Nothing was attempted; the capability itself is untested.",
            )
        }

        if (!adapterEnabled(adapter)) {
            return@withLock notTested(
                key,
                "Bluetooth is turned off. The app does not turn it on by itself; the tester does " +
                    "that. Untested, not unsupported.",
            )
        }

        if (appRegistered && proxy != null) {
            return@withLock observed(
                key,
                "already registered",
                "The SDP record from an earlier call in this run is still registered. " + NOT_A_LOCAL_ROUTE,
            )
        }

        // --- profile proxy
        val hid = proxy ?: run {
            val acquired = acquireProxy(context, adapter)
            when {
                acquired.refusedImmediately -> return@withLock unsupported(
                    key,
                    "BluetoothAdapter.getProfileProxy(HID_DEVICE) returned false: this build's " +
                        "Bluetooth stack does not offer the HID device role to apps. That is the " +
                        "platform answering, not a failure to test.",
                )
                acquired.proxy == null -> return@withLock Observation(
                    key,
                    "proxy timeout after " + PROXY_TIMEOUT_MS + "ms",
                    LabStatus.ERROR,
                    "getProfileProxy() accepted the request but onServiceConnected never arrived. " +
                        "The route failed to start; this says nothing about the capability.",
                    LabCategory.DEX,
                ).also { SessionLogger.log(it) }
                // The null case is handled above, so this branch is the acquired proxy.
                else -> acquired.proxy
            }
        }

        proxy = hid

        // --- SDP record: name, description, provider, subclass, descriptor bytes
        val sdp = try {
            BluetoothHidDeviceAppSdpSettings(
                SDP_NAME,
                SDP_DESCRIPTION,
                SDP_PROVIDER,
                // COMBO: the descriptor declares a keyboard collection and a mouse collection.
                BluetoothHidDevice.SUBCLASS1_COMBO,
                HidReportDescriptor.BYTES,
            )
        } catch (t: Throwable) {
            return@withLock error(key, t)
        }

        val signal = CompletableDeferred<Boolean>()
        appStatusSignal.set(signal)

        // QoS is left to the stack (null in, null out). The defaults are what every other HID
        // implementation on the device uses; inventing token-rate numbers here would be noise.
        val accepted = try {
            @SuppressLint("MissingPermission") // permission checked above
            val ok = hid.registerApp(sdp, null, null, callbackExecutor, hidCallback)
            ok
        } catch (t: Throwable) {
            appStatusSignal.set(null)
            return@withLock error(key, t)
        }

        if (!accepted) {
            appStatusSignal.set(null)
            return@withLock unsupported(
                key,
                "BluetoothHidDevice.registerApp() returned false: the stack refused this app's HID " +
                    "SDP record. A refusal is the platform's answer for this device and build.",
            )
        }

        // registerApp() returning true only means the call was accepted. Registration itself is
        // confirmed by the onAppStatusChanged callback, so wait for it rather than assume it.
        val confirmed = withTimeoutOrNull(REGISTER_TIMEOUT_MS) { signal.await() }
        appStatusSignal.set(null)

        return@withLock when (confirmed) {
            true -> observed(
                key,
                "registered",
                "registerApp() returned true and onAppStatusChanged reported registered=true: the " +
                    "platform accepted this app as a Bluetooth HID keyboard+mouse device. No host " +
                    "is connected by this and nothing has been sent. " + NOT_A_LOCAL_ROUTE,
            )
            false -> unsupported(
                key,
                "onAppStatusChanged reported registered=false: the stack answered that this app is " +
                    "not registered as a HID device. That is an answer about this app on this " +
                    "build - it is not a statement that the HID device role is absent from Android.",
            )
            else -> Observation(
                key,
                "registerApp()=true, no onAppStatusChanged within " + REGISTER_TIMEOUT_MS + "ms",
                LabStatus.INFERRED,
                "The call was accepted, so registration probably happened, but the confirming " +
                    "callback did not arrive in time and it was not observed. " + NOT_A_LOCAL_ROUTE,
                LabCategory.DEX,
            ).also { SessionLogger.log(it) }
        }
    }

    /** Unregisters the SDP record and releases the profile proxy. Safe to call when not registered. */
    fun unregister() {
        val hid = proxy
        val ctx = appContext
        var unregistered: Boolean? = null
        if (hid != null) {
            unregistered = try {
                @SuppressLint("MissingPermission")
                val ok = hid.unregisterApp()
                ok
            } catch (t: Throwable) {
                SessionLogger.log(Observation.error("dex.hid.unregister", t, LabCategory.DEX))
                null
            }
            if (ctx != null) {
                runCatching {
                    adapter(ctx)?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid)
                }
            }
        }
        proxy = null
        serviceListener = null
        appRegistered = false
        connectedHost = null
        appStatusSignal.getAndSet(null)?.complete(false)
        connectSignal.getAndSet(null)?.complete(BluetoothProfile.STATE_DISCONNECTED)
        _state.value = "idle - not registered"
        SessionLogger.log(
            LabCategory.DEX,
            "dex.hid.unregister",
            LabStatus.OBSERVED,
            mapOf(
                "value" to (unregistered?.toString() ?: "nothing to unregister"),
                "note" to "SDP record released; the app is no longer offered as a HID device.",
            ),
        )
    }

    /**
     * Already-bonded devices, as `address to displayName`, so the tester can choose a host.
     *
     * This lists; it never pairs and never connects. The returned addresses and names go to the
     * on-screen picker only - neither is ever written to the log (see [hostToken]).
     */
    @SuppressLint("MissingPermission") // guarded by missingPermission() below
    fun bondedHosts(context: Context): List<Pair<String, String>> {
        appContext = context.applicationContext
        val adapter = adapter(context) ?: return emptyList()
        if (missingPermission(context) != null) return emptyList()
        return try {
            adapter.bondedDevices.orEmpty().map { device ->
                val name = try {
                    device.name ?: "(unnamed device)"
                } catch (t: Throwable) {
                    "(name unavailable)"
                }
                device.address to name
            }
        } catch (t: Throwable) {
            SessionLogger.log(Observation.error("dex.hid.bonded_hosts", t, LabCategory.DEX))
            emptyList()
        }
    }

    /**
     * Connects to an **already bonded** host and waits for the connection state to settle.
     *
     * Refuses anything that is not bonded: this module never triggers a pairing dialog, and never
     * connects without this explicit call behind a tester's button press.
     */
    @SuppressLint("MissingPermission") // guarded by missingPermission() below
    suspend fun connect(address: String): Observation = mutex.withLock {
        val key = "dex.hid.connect"
        val ctx = appContext
            ?: return@withLock notTested(key, "register(context) has not been called in this run.")
        val adapter = adapter(ctx)
            ?: return@withLock unsupported(key, "no Bluetooth adapter on this device.")
        val missing = missingPermission(ctx)
        if (missing != null) return@withLock notTested(key, "missing permission " + missing + ".")
        val hid = proxy
        if (hid == null || !appRegistered) {
            return@withLock notTested(
                key,
                "This app is not registered as a HID device yet; register() has to succeed first.",
            )
        }

        val device = try {
            adapter.bondedDevices.orEmpty().firstOrNull {
                it.address.equals(address, ignoreCase = true)
            }
        } catch (t: Throwable) {
            return@withLock error(key, t)
        }

        if (device == null) {
            return@withLock notTested(
                key,
                "No bonded device with that address. This route never initiates pairing: the tester " +
                    "pairs the host in Android's own Bluetooth settings first.",
            )
        }
        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            return@withLock notTested(
                key,
                "The chosen device is not in BOND_BONDED state (" + device.bondState + "). Refused " +
                    "rather than triggering a pairing request.",
            )
        }

        val token = hostToken(device)
        val signal = CompletableDeferred<Int>()
        connectSignal.set(signal)
        _state.value = "connecting to " + token

        val accepted = try {
            hid.connect(device)
        } catch (t: Throwable) {
            connectSignal.set(null)
            return@withLock error(key, t)
        }

        if (!accepted) {
            connectSignal.set(null)
            return@withLock Observation(
                key,
                "connect() returned false for " + token,
                LabStatus.ERROR,
                "The stack refused the connect call outright. Nothing was connected and nothing " +
                    "was sent; this says nothing about whether the host would accept a HID keyboard.",
                LabCategory.DEX,
            ).also { SessionLogger.log(it) }
        }

        val settled = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { signal.await() }
        connectSignal.set(null)

        return@withLock when (settled) {
            BluetoothProfile.STATE_CONNECTED -> observed(
                key,
                "connected to " + token,
                "onConnectionStateChanged reported STATE_CONNECTED: the host accepted a HID " +
                    "connection from this phone. Nothing has been typed or moved yet. " +
                    NOT_A_LOCAL_ROUTE,
            )
            BluetoothProfile.STATE_DISCONNECTED -> Observation(
                key,
                "disconnected " + token,
                LabStatus.OBSERVED,
                "The connection attempt ended in STATE_DISCONNECTED: this host did not hold a HID " +
                    "connection now. It is a result for this host and this moment, not a verdict on " +
                    "the route.",
                LabCategory.DEX,
            ).also { SessionLogger.log(it) }
            null -> Observation(
                key,
                "no terminal state within " + CONNECT_TIMEOUT_MS + "ms for " + token,
                LabStatus.NOT_TESTED,
                "connect() was accepted but the connection never settled in time - typically a host " +
                    "that is out of range or asleep. Nothing may be concluded.",
                LabCategory.DEX,
            ).also { SessionLogger.log(it) }
            else -> Observation(
                key,
                "connection state " + settled + " for " + token,
                LabStatus.OBSERVED,
                "Reported by onConnectionStateChanged.",
                LabCategory.DEX,
            ).also { SessionLogger.log(it) }
        }
    }

    /** Drops the HID connection to the currently connected host. The SDP record stays registered. */
    @SuppressLint("MissingPermission")
    suspend fun disconnect(): Observation = mutex.withLock {
        val key = "dex.hid.disconnect"
        val hid = proxy
        val host = connectedHost
        if (hid == null || host == null) {
            return@withLock notTested(key, "no host is connected.")
        }
        return@withLock try {
            val ok = hid.disconnect(host)
            observed(
                key,
                if (ok) "disconnect accepted" else "disconnect refused",
                "disconnect() returned " + ok + " for " + hostToken(host) + ".",
            )
        } catch (t: Throwable) {
            error(key, t)
        }
    }

    /** Live, human-readable route state for the UI. */
    fun state(): StateFlow<String> = _state.asStateFlow()

    /**
     * Read-only facts about this route. Runs no radio operation, registers nothing and sends
     * nothing, so it is safe to call when a screen opens.
     *
     * These are returned, not logged: the caller logs them, exactly as [nl.icthorse.miraicastlab.core.Probe]
     * implementations do, so the report cannot end up with two copies of every row.
     */
    @SuppressLint("MissingPermission") // every guarded call is behind missingPermission()
    fun observations(context: Context): List<Observation> {
        appContext = context.applicationContext
        val out = mutableListOf<Observation>()
        val cat = LabCategory.DEX

        out += Observation.confirmed(
            "dex.hid.api",
            "BluetoothHidDevice (API 28+)",
            cat,
            "The only public API with which an ordinary app can send real keyboard and mouse " +
                "reports. minSdk here is 29, so the class always exists; whether the stack offers " +
                "the role is a separate question that register() answers.",
        )

        val hasFeature = try {
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)
        } catch (t: Throwable) {
            out += Observation.error("dex.hid.feature_bluetooth", t, cat)
            false
        }
        out += if (hasFeature) {
            Observation.confirmed("dex.hid.feature_bluetooth", "present", cat)
        } else {
            Observation.unsupported(
                "dex.hid.feature_bluetooth",
                cat,
                "PackageManager reports no FEATURE_BLUETOOTH: no Bluetooth radio here.",
            )
        }

        val adapter = adapter(context)
        out += if (adapter == null) {
            Observation.unsupported(
                "dex.hid.adapter",
                cat,
                "BluetoothManager.getAdapter() returned null.",
            )
        } else {
            Observation.confirmed("dex.hid.adapter", "present", cat)
        }

        val missing = missingPermission(context)
        out += if (missing == null) {
            Observation.confirmed(
                "dex.hid.permission",
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "BLUETOOTH_CONNECT granted" else "legacy BLUETOOTH granted",
                cat,
            )
        } else {
            Observation.notTested(
                "dex.hid.permission",
                cat,
                missing + " is not granted, so registering and connecting cannot be attempted. " +
                    (if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                        "Below API 31 this is the install-time android.permission.BLUETOOTH, which " +
                            "this build's manifest does not declare - on such a device the route is " +
                            "untestable until it is added."
                    } else "The tester grants it from the permission prompt."),
            )
        }

        if (adapter != null && missing == null) {
            out += if (adapterEnabled(adapter)) {
                Observation.confirmed("dex.hid.adapter_enabled", "true", cat)
            } else {
                Observation.notTested(
                    "dex.hid.adapter_enabled",
                    cat,
                    "Bluetooth is off. The app never turns it on; the route is untested until the " +
                        "tester does.",
                )
            }
            val bonded = try {
                adapter.bondedDevices.orEmpty().size
            } catch (t: Throwable) {
                -1
            }
            out += if (bonded >= 0) {
                Observation.confirmed(
                    "dex.hid.bonded_host_count",
                    bonded,
                    cat,
                    "Count only. Addresses and names of household devices are personal data and are " +
                        "never written to the log; they are shown in the picker on the tester's own " +
                        "screen and nowhere else.",
                )
            } else {
                Observation.notTested("dex.hid.bonded_host_count", cat, "getBondedDevices() threw.")
            }
        }

        out += if (appRegistered) {
            Observation.observed(
                "dex.hid.app_registered",
                "true",
                cat,
                "onAppStatusChanged reported this app registered as a HID device in this run.",
            )
        } else {
            Observation.notTested(
                "dex.hid.app_registered",
                cat,
                "register() has not succeeded in this run. Registration is an explicit tester action.",
            )
        }

        val host = connectedHost
        out += if (host != null) {
            Observation.observed(
                "dex.hid.host_connected",
                hostToken(host),
                cat,
                "One paired host holds a HID connection from this phone.",
            )
        } else {
            Observation.notTested(
                "dex.hid.host_connected",
                cat,
                "No host is connected, so no report can be delivered anywhere.",
            )
        }

        out += Observation.confirmed(
            "dex.hid.descriptor",
            HidReportDescriptor.BYTES.size.toString() + " bytes, report id " +
                HidReportDescriptor.REPORT_ID_KEYBOARD + " = keyboard (" +
                HidReportDescriptor.KEYBOARD_REPORT_SIZE + "B), report id " +
                HidReportDescriptor.REPORT_ID_MOUSE + " = mouse (" +
                HidReportDescriptor.MOUSE_REPORT_SIZE + "B)",
            cat,
            "The SDP descriptor this app publishes. See HidReportDescriptor.annotated for the " +
                "item-by-item decode.",
        )

        out += Observation.confirmed(
            "dex.hid.keycodes_mapped",
            HidKeyMap.mappedKeyCodeCount,
            cat,
            "Android key codes with a HID Keyboard/Keypad usage in this build's table. " + LAYOUT_CAVEAT,
        )

        // The structural finding. This is a platform contract, not a measurement, so CONFIRMED.
        out += Observation.confirmed(
            "dex.hid.self_host",
            "impossible",
            cat,
            NOT_A_LOCAL_ROUTE,
        )
        out += Observation.unsupported(
            "dex.hid.drives_local_dex",
            cat,
            "This route cannot deliver a keystroke to the DeX desktop of the phone that is sending " +
                "it. The architecture answers that, and it is why the Intent route matters for the " +
                "local-DeX question.",
        )

        // The honest open question. Never upgrade this without a Mirai in front of you.
        out += Observation.notTested(
            "dex.hid.mirai_accepts_hid_keyboard",
            cat,
            "Whether the Toyota Mirai head unit accepts a Bluetooth HID keyboard at all was not " +
                "tested. It has not been paired as a HID host in any run of this code, and it is " +
                "not guessed here.",
        )

        return out
    }

    /** Adapts this object to the shared measurement harness. */
    fun asInputRoute(context: Context): InputRoute = HidInputRoute(context.applicationContext)

    // ------------------------------------------------------------------ sending

    /**
     * Whether a report can be delivered right now, and why not when it cannot.
     * Even when true, the caveat that this is a *remote* host and not the local DeX desktop stands.
     */
    fun availability(context: Context): Pair<Boolean, String> {
        val adapter = adapter(context)
            ?: return false to "no Bluetooth adapter on this device."
        missingPermission(context)?.let {
            return false to ("missing permission " + it + ".")
        }
        if (!adapterEnabled(adapter)) return false to "Bluetooth is turned off."
        if (proxy == null || !appRegistered) {
            return false to ("not registered as a HID device yet - press Register first. " + NOT_A_LOCAL_ROUTE)
        }
        val host = connectedHost
            ?: return false to ("registered, but no paired host is connected - pick a bonded host " +
                "and press Connect. " + NOT_A_LOCAL_ROUTE)
        return true to ("connected to " + hostToken(host) + "; reports go to that host only. " + NOT_A_LOCAL_ROUTE)
    }

    /**
     * Translates a key or text action into HID keyboard reports and sends them.
     * Mouse actions belong to [sendMouseReport].
     */
    suspend fun sendKeyboardReport(context: Context, action: InputAction): InputAttempt {
        val started = System.nanoTime()
        val label = describe(action)
        preflight(context, label, started)?.let { return it }
        val hid = proxy ?: return finish(label, LabStatus.NOT_TESTED, "no HID proxy", "Nothing was sent.", started)
        val host = connectedHost ?: return finish(label, LabStatus.NOT_TESTED, "no connected host", "Nothing was sent.", started)

        return when (action) {
            is InputAction.Key -> {
                val stroke = HidKeyMap.strokeFor(action.keyCode, action.meta, action.label)
                if (stroke == null) {
                    val why = HidKeyMap.refusalFor(action.keyCode)
                        ?: ("Android key code " + action.keyCode + " has no usage on the HID " +
                            "Keyboard/Keypad page declared by this descriptor.")
                    finish(
                        label,
                        LabStatus.UNSUPPORTED,
                        "no HID usage for keyCode " + action.keyCode,
                        why + " Nothing was sent; no substitute key was invented.",
                        started,
                    )
                } else {
                    mutex.withLock { typeStrokes(hid, host, listOf(stroke)) }.let { r ->
                        reportResult(
                            label,
                            r,
                            "press and release of " + stroke.label + " (usage 0x" +
                                "%02X".format(stroke.usage) + ", modifiers " +
                                HidKeyMap.describeModifiers(stroke.modifiers) + ")",
                            started,
                        )
                    }
                }
            }

            is InputAction.Text -> {
                val plan = HidKeyMap.textPlan(action.text)
                if (plan.strokes.isEmpty()) {
                    finish(
                        label,
                        LabStatus.UNSUPPORTED,
                        "no character of " + action.text.length + " could be mapped",
                        "None of these characters has a usage in the Keyboard/Keypad page as mapped " +
                            "here (unmapped: \"" + plan.unmapped + "\"). Nothing was sent. " + LAYOUT_CAVEAT,
                        started,
                    )
                } else {
                    val r = mutex.withLock { typeStrokes(hid, host, plan.strokes) }
                    val detail = "typed " + plan.strokes.size + " of " + action.text.length +
                        " characters" +
                        (if (plan.complete) "" else " (unmapped: \"" + plan.unmapped + "\")")
                    reportResult(label, r, detail + ". " + LAYOUT_CAVEAT, started)
                }
            }

            else -> finish(
                label,
                LabStatus.ERROR,
                "sendKeyboardReport called with " + action::class.java.simpleName,
                "Wrong entry point for this action; nothing was sent.",
                started,
            )
        }
    }

    /**
     * Translates a pointer action into HID mouse reports and sends them.
     *
     * [InputAction.Tap] is refused: the descriptor declares a **relative** pointer, which has no way
     * to express an absolute coordinate. Delivering a tap at (x, y) would need an absolute-pointer or
     * digitizer collection plus the host's screen geometry, and the phone has neither.
     */
    suspend fun sendMouseReport(context: Context, action: InputAction): InputAttempt {
        val started = System.nanoTime()
        val label = describe(action)
        preflight(context, label, started)?.let { return it }
        val hid = proxy ?: return finish(label, LabStatus.NOT_TESTED, "no HID proxy", "Nothing was sent.", started)
        val host = connectedHost ?: return finish(label, LabStatus.NOT_TESTED, "no connected host", "Nothing was sent.", started)

        return when (action) {
            is InputAction.Tap -> finish(
                label,
                LabStatus.UNSUPPORTED,
                "relative pointer cannot address (" + action.x + ", " + action.y + ")",
                "The mouse collection in this descriptor reports X and Y as relative deltas " +
                    "(Input(Data,Var,Rel)). A relative pointer has no absolute coordinate, and this " +
                    "app does not know the host's screen geometry or pointer position, so a tap at a " +
                    "given point cannot be expressed at all. Nothing was sent.",
                started,
            )

            is InputAction.Swipe -> {
                val dx = (action.x2 - action.x1).roundToInt()
                val dy = (action.y2 - action.y1).roundToInt()
                val r = mutex.withLock {
                    moveRelative(hid, host, dx, dy, HidReportDescriptor.MOUSE_BUTTON_LEFT, action.durationMs)
                }
                reportResult(
                    label,
                    r,
                    "delivered as a left-button drag of (" + dx + ", " + dy + ") relative units in " +
                        "steps of at most " + HidReportDescriptor.POINTER_MAX + ". The start point " +
                        "(" + action.x1 + ", " + action.y1 + ") was ignored: a relative pointer " +
                        "cannot be positioned, only moved",
                    started,
                )
            }

            else -> finish(
                label,
                LabStatus.ERROR,
                "sendMouseReport called with " + action::class.java.simpleName,
                "Wrong entry point for this action; nothing was sent.",
                started,
            )
        }
    }

    // ------------------------------------------------------------------ internals

    private class HidInputRoute(private val context: Context) : InputRoute {
        override val routeName = ROUTE_NAME
        override val tier = PrivilegeTier.ORDINARY_APP
        override fun availability(): Pair<Boolean, String> = HidKeyboard.availability(context)
        override suspend fun send(action: InputAction): InputAttempt = when (action) {
            is InputAction.Key, is InputAction.Text -> sendKeyboardReport(context, action)
            is InputAction.Tap, is InputAction.Swipe -> sendMouseReport(context, action)
            is InputAction.Global -> {
                val started = System.nanoTime()
                finish(
                    describe(action),
                    LabStatus.UNSUPPORTED,
                    "no HID representation for global action " + action.action,
                    "AccessibilityService global actions are an Android-internal concept with no " +
                        "counterpart in the HID protocol. A keyboard cannot express them and no " +
                        "keystroke was substituted for one. Nothing was sent.",
                    started,
                )
            }
        }
    }

    /** Result of a report burst: how many reports the stack accepted, and the first failure if any. */
    private class Burst(val accepted: Int, val attempted: Int, val failure: String?)

    private suspend fun typeStrokes(
        hid: BluetoothHidDevice,
        host: BluetoothDevice,
        strokes: List<HidKeyMap.Stroke>,
    ): Burst {
        var accepted = 0
        var attempted = 0
        strokes.forEach { stroke ->
            val usages = if (stroke.isModifierOnly) IntArray(0) else intArrayOf(stroke.usage)
            val press = HidReportDescriptor.keyboardReport(stroke.modifiers, usages)
            attempted++
            val pressOk = sendRaw(hid, host, HidReportDescriptor.REPORT_ID_KEYBOARD, press)
            if (pressOk != null) return Burst(accepted, attempted, pressOk)
            accepted++
            delay(KEY_HOLD_MS)

            attempted++
            val releaseOk = sendRaw(
                hid, host, HidReportDescriptor.REPORT_ID_KEYBOARD, HidReportDescriptor.keyboardRelease(),
            )
            if (releaseOk != null) return Burst(accepted, attempted, releaseOk)
            accepted++
            delay(KEY_GAP_MS)
        }
        return Burst(accepted, attempted, null)
    }

    private suspend fun moveRelative(
        hid: BluetoothHidDevice,
        host: BluetoothDevice,
        dx: Int,
        dy: Int,
        buttons: Int,
        durationMs: Long,
    ): Burst {
        // Each report can carry at most +/-127 per axis, so a long move becomes several reports.
        val span = max(abs(dx), abs(dy))
        val steps = ((span + HidReportDescriptor.POINTER_MAX - 1) / HidReportDescriptor.POINTER_MAX)
            .coerceIn(1, MAX_POINTER_STEPS)
        val perStepDelay = (durationMs / (steps + 2)).coerceIn(0L, 60L)

        var accepted = 0
        var attempted = 0

        // Button down, no movement: the host sees a press before the drag, as a real mouse sends it.
        attempted++
        sendRaw(hid, host, HidReportDescriptor.REPORT_ID_MOUSE, HidReportDescriptor.mouseReport(buttons, 0, 0, 0))
            ?.let { return Burst(accepted, attempted, it) }
        accepted++
        delay(perStepDelay)

        var doneX = 0
        var doneY = 0
        for (step in 1..steps) {
            // Integer-accumulate so rounding cannot lose or invent movement over the whole gesture.
            val targetX = dx * step / steps
            val targetY = dy * step / steps
            val stepX = targetX - doneX
            val stepY = targetY - doneY
            doneX = targetX
            doneY = targetY
            attempted++
            sendRaw(
                hid, host, HidReportDescriptor.REPORT_ID_MOUSE,
                HidReportDescriptor.mouseReport(buttons, stepX, stepY, 0),
            )?.let { return Burst(accepted, attempted, it) }
            accepted++
            delay(perStepDelay)
        }

        attempted++
        sendRaw(hid, host, HidReportDescriptor.REPORT_ID_MOUSE, HidReportDescriptor.mouseRelease())
            ?.let { return Burst(accepted, attempted, it) }
        accepted++
        return Burst(accepted, attempted, null)
    }

    /** Sends one report. Returns null on acceptance, or the failure text. Never throws. */
    @SuppressLint("MissingPermission") // preflight() checked the permission
    private fun sendRaw(
        hid: BluetoothHidDevice,
        host: BluetoothDevice,
        reportId: Int,
        payload: ByteArray,
    ): String? = try {
        if (hid.sendReport(host, reportId, payload)) {
            null
        } else {
            "sendReport(id=" + reportId + ", " + HidReportDescriptor.hex(payload) + ") returned false"
        }
    } catch (t: Throwable) {
        t::class.java.simpleName + ": " + (t.message ?: "no message")
    }

    /** Turns a [Burst] into an attempt, keeping delivery and effect apart. */
    private fun reportResult(label: String, burst: Burst, detail: String, started: Long): InputAttempt {
        return if (burst.failure == null) {
            finish(
                label,
                LabStatus.OBSERVED,
                "the Bluetooth stack accepted " + burst.accepted + " of " + burst.attempted +
                    " HID reports (" + detail + ")",
                "The platform accepted these reports for transmission to the paired host. " +
                    DELIVERY_CAVEAT + " " + NOT_A_LOCAL_ROUTE,
                started,
            )
        } else {
            finish(
                label,
                LabStatus.ERROR,
                burst.accepted.toString() + " of " + burst.attempted + " reports accepted, then: " +
                    burst.failure,
                "The burst stopped part-way, so the host may have received an incomplete sequence " +
                    "(a key may still be reported as held). This is a failure of the route, not " +
                    "evidence about the capability.",
                started,
            )
        }
    }

    /**
     * Shared refusal path for both send entry points. Returns a finished attempt when the route
     * cannot be used, or null when it can.
     */
    private fun preflight(context: Context, label: String, started: Long): InputAttempt? {
        if (adapter(context) == null) {
            return finish(
                label,
                LabStatus.UNSUPPORTED,
                "no Bluetooth adapter",
                "This device has no Bluetooth adapter, so the HID device role cannot exist here.",
                started,
            )
        }
        val (usable, why) = availability(context)
        if (!usable) {
            return finish(
                label,
                LabStatus.NOT_TESTED,
                "route not usable: " + why,
                "Nothing was sent. A route that could not run says nothing about whether it works.",
                started,
            )
        }
        return null
    }

    /**
     * A short label for logs. Typed text is reported as a character count, never as content: the
     * tester may type anything into the box and the evidence file is not the place to find out what.
     */
    private fun describe(action: InputAction): String = when (action) {
        is InputAction.Key -> "Key(" + action.label + ", keyCode=" + action.keyCode + ", meta=" + action.meta + ")"
        is InputAction.Text -> "Text(" + action.text.length + " chars)"
        is InputAction.Tap -> "Tap(" + action.x + ", " + action.y + ")"
        is InputAction.Swipe -> "Swipe(" + action.x1 + "," + action.y1 + " -> " + action.x2 + "," + action.y2 + ")"
        is InputAction.Global -> "Global(" + action.label + ")"
    }

    private fun finish(
        action: String,
        status: LabStatus,
        observation: String,
        conclusion: String,
        started: Long,
    ): InputAttempt {
        val attempt = InputAttempt(
            routeName = ROUTE_NAME,
            tier = PrivilegeTier.ORDINARY_APP,
            action = action,
            status = status,
            observation = observation,
            conclusion = conclusion,
            elapsedMs = (System.nanoTime() - started) / 1_000_000,
        )
        SessionLogger.log(
            LabCategory.DEX,
            "dex.hid.send",
            status,
            mapOf(
                "route" to ROUTE_NAME,
                "action" to action,
                "value" to observation,
                "note" to conclusion,
                "elapsedMs" to attempt.elapsedMs.toString(),
                "provesVisibleEffect" to attempt.provesVisibleEffect.toString(),
            ),
        )
        return attempt
    }

    // ------------------------------------------------------------------ platform plumbing

    private class ProxyResult(val proxy: BluetoothHidDevice?, val refusedImmediately: Boolean)

    private suspend fun acquireProxy(context: Context, adapter: BluetoothAdapter): ProxyResult {
        val deferred = CompletableDeferred<BluetoothHidDevice?>()
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, service: BluetoothProfile?) {
                if (profile == BluetoothProfile.HID_DEVICE) {
                    deferred.complete(service as? BluetoothHidDevice)
                }
            }

            override fun onServiceDisconnected(profile: Int) {
                if (profile != BluetoothProfile.HID_DEVICE) return
                proxy = null
                appRegistered = false
                connectedHost = null
                _state.value = "profile proxy disconnected"
                SessionLogger.log(
                    LabCategory.DEX,
                    "dex.hid.proxy_disconnected",
                    LabStatus.OBSERVED,
                    mapOf("note" to "The HID_DEVICE profile proxy went away; registration is gone with it."),
                )
            }
        }
        val accepted = try {
            adapter.getProfileProxy(context.applicationContext, listener, BluetoothProfile.HID_DEVICE)
        } catch (t: Throwable) {
            SessionLogger.log(Observation.error("dex.hid.get_profile_proxy", t, LabCategory.DEX))
            false
        }
        if (!accepted) return ProxyResult(null, refusedImmediately = true)
        serviceListener = listener
        val p = withTimeoutOrNull(PROXY_TIMEOUT_MS) { deferred.await() }
        return ProxyResult(p, refusedImmediately = false)
    }

    private val hidCallback = object : BluetoothHidDevice.Callback() {

        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            appRegistered = registered
            _state.value = if (registered) {
                "registered as HID keyboard+mouse" +
                    (connectedHost?.let { " - connected to " + hostToken(it) } ?: " - no host connected")
            } else {
                "idle - not registered"
            }
            SessionLogger.log(
                LabCategory.DEX,
                "dex.hid.app_status_changed",
                LabStatus.OBSERVED,
                mapOf(
                    "value" to registered.toString(),
                    "pluggedDevice" to hostToken(pluggedDevice),
                    "note" to "Registration status of this app's HID SDP record. " + NOT_A_LOCAL_ROUTE,
                ),
            )
            appStatusSignal.getAndSet(null)?.complete(registered)
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            val token = hostToken(device)
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectedHost = device
                    _state.value = "connected to " + token
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (device == null || device.address == connectedHost?.address) connectedHost = null
                    _state.value = if (appRegistered) {
                        "registered as HID keyboard+mouse - no host connected"
                    } else {
                        "idle - not registered"
                    }
                }
            }
            SessionLogger.log(
                LabCategory.DEX,
                "dex.hid.connection_state_changed",
                LabStatus.OBSERVED,
                mapOf(
                    "value" to stateName(state),
                    "host" to token,
                    "note" to "Reported by BluetoothHidDevice.Callback for a host the tester chose.",
                ),
            )
            if (state == BluetoothProfile.STATE_CONNECTED || state == BluetoothProfile.STATE_DISCONNECTED) {
                connectSignal.getAndSet(null)?.complete(state)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onGetReport(device: BluetoothDevice?, type: Byte, id: Byte, bufferSize: Int) {
            // The host is asking for the current state of a report, usually right after connecting.
            // Answering with the idle report is protocol hygiene, not input: no key is pressed and
            // the pointer does not move. Leaving it unanswered makes some hosts drop the link.
            val hid = proxy
            if (hid == null || device == null) return
            val payload = when (id.toInt()) {
                HidReportDescriptor.REPORT_ID_KEYBOARD -> HidReportDescriptor.keyboardRelease()
                HidReportDescriptor.REPORT_ID_MOUSE -> HidReportDescriptor.mouseRelease()
                else -> null
            }
            runCatching {
                if (payload == null) {
                    hid.reportError(device, BluetoothHidDevice.ERROR_RSP_INVALID_RPT_ID)
                } else {
                    hid.replyReport(device, type, id, payload)
                }
            }
            SessionLogger.log(
                LabCategory.DEX,
                "dex.hid.get_report",
                LabStatus.OBSERVED,
                mapOf(
                    "value" to ("host requested report id " + id.toInt() + ", type " + type.toInt()),
                    "host" to hostToken(device),
                    "note" to "Answered with the idle (all-zero) report; no input was sent.",
                ),
            )
        }

        override fun onSetProtocol(device: BluetoothDevice?, protocol: Byte) {
            SessionLogger.log(
                LabCategory.DEX,
                "dex.hid.set_protocol",
                LabStatus.OBSERVED,
                mapOf(
                    "value" to if (protocol == BluetoothHidDevice.PROTOCOL_BOOT_MODE) "boot" else "report",
                    "host" to hostToken(device),
                    "note" to "Boot mode means the host reads the fixed boot keyboard/mouse layout " +
                        "rather than this app's report descriptor.",
                ),
            )
        }

        override fun onVirtualCableUnplug(device: BluetoothDevice?) {
            if (device == null || device.address == connectedHost?.address) connectedHost = null
            _state.value = if (appRegistered) {
                "registered as HID keyboard+mouse - no host connected"
            } else {
                "idle - not registered"
            }
            SessionLogger.log(
                LabCategory.DEX,
                "dex.hid.virtual_cable_unplug",
                LabStatus.OBSERVED,
                mapOf(
                    "host" to hostToken(device),
                    "note" to "The host dropped the virtual cable; it must be reconnected explicitly.",
                ),
            )
        }
    }

    private fun adapter(context: Context): BluetoothAdapter? = try {
        // BluetoothManager rather than the deprecated BluetoothAdapter.getDefaultAdapter().
        context.getSystemService(BluetoothManager::class.java)?.adapter
    } catch (t: Throwable) {
        null
    }

    @SuppressLint("MissingPermission") // isEnabled needs BLUETOOTH_CONNECT from API 31; guarded by try
    private fun adapterEnabled(adapter: BluetoothAdapter): Boolean = try {
        adapter.isEnabled
    } catch (t: Throwable) {
        false
    }

    /**
     * The permission this route needs, or null when it is granted.
     * BLUETOOTH_CONNECT from API 31; the install-time legacy BLUETOOTH permission below that.
     */
    private fun missingPermission(context: Context): String? {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_CONNECT
        } else {
            "android.permission.BLUETOOTH"
        }
        val granted = try {
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        } catch (t: Throwable) {
            false
        }
        return if (granted) null else permission
    }

    /**
     * A stable, non-reversible stand-in for a host, for logs and conclusions.
     *
     * A Bluetooth MAC of a household device is personal data and this project collects none, so no
     * address and no device name ever reaches [SessionLogger]. The digest is salted with a value
     * generated fresh in this process, so the token cannot be brute-forced back to an address by a
     * reader of the evidence file, and cannot be joined across runs.
     */
    private fun hostToken(device: BluetoothDevice?): String =
        hostToken(try { device?.address } catch (t: Throwable) { null })

    private fun hostToken(address: String?): String {
        if (address.isNullOrBlank()) return "no-host"
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest((tokenSalt + address.uppercase(Locale.ROOT)).toByteArray())
            "host-" + (0 until 3).joinToString("") { "%02x".format(digest[it].toInt() and 0xFF) }
        } catch (t: Throwable) {
            "host-?"
        }
    }

    private fun stateName(state: Int): String = when (state) {
        BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
        BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
        BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
        BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
        else -> "state " + state
    }

    // ------------------------------------------------------------------ observation helpers

    private fun observed(key: String, value: String, note: String): Observation =
        Observation(key, value, LabStatus.OBSERVED, note, LabCategory.DEX).also { SessionLogger.log(it) }

    private fun unsupported(key: String, note: String): Observation =
        Observation(key, "absent", LabStatus.UNSUPPORTED, note, LabCategory.DEX).also { SessionLogger.log(it) }

    private fun notTested(key: String, note: String): Observation =
        Observation(key, "-", LabStatus.NOT_TESTED, note, LabCategory.DEX).also { SessionLogger.log(it) }

    private fun error(key: String, t: Throwable): Observation =
        Observation.error(key, t, LabCategory.DEX).also { SessionLogger.log(it) }
}
