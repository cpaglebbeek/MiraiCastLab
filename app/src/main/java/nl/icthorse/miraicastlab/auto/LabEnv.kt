package nl.icthorse.miraicastlab.auto

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.hardware.usb.UsbManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.view.Display
import java.net.NetworkInterface
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import android.media.MediaRouter as FwMediaRouter

/**
 * Read-only snapshot of everything an ordinary (non-privileged) app may legitimately see about the
 * phone's projection environment.
 *
 * Every accessor here is passive. Nothing in this file changes device state, opens a socket, or
 * transmits anything anywhere: spec sections 1, 3 and 12 forbid touching the vehicle side, and the
 * app holds no INTERNET permission to transmit with in the first place.
 *
 * Every call is individually guarded. A vendor that removed a service, an emulator without USB and
 * a permission the tester declined must all degrade into a readable string, never into a crash in
 * the middle of a test run that cannot be repeated.
 */
internal object LabEnv {

    private val ISO: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC)

    fun nowIso(): String = runCatching { ISO.format(Instant.now()) }.getOrDefault("unknown")

    // ------------------------------------------------------------------ car mode / UI mode

    /** Name of the current UI mode type. UI_MODE_TYPE_CAR is the documented car-projection signal. */
    fun uiModeType(context: Context): String = try {
        when (val t = (context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)?.currentModeType) {
            null -> "no_uimode_service"
            Configuration.UI_MODE_TYPE_CAR -> "CAR"
            Configuration.UI_MODE_TYPE_NORMAL -> "NORMAL"
            Configuration.UI_MODE_TYPE_DESK -> "DESK"
            Configuration.UI_MODE_TYPE_TELEVISION -> "TELEVISION"
            Configuration.UI_MODE_TYPE_APPLIANCE -> "APPLIANCE"
            Configuration.UI_MODE_TYPE_WATCH -> "WATCH"
            Configuration.UI_MODE_TYPE_VR_HEADSET -> "VR_HEADSET"
            Configuration.UI_MODE_TYPE_UNDEFINED -> "UNDEFINED"
            else -> "unknown(" + t + ")"
        }
    } catch (t: Throwable) {
        "error: " + describe(t)
    }

    /** True only when UiModeManager itself reports car mode. This is the authoritative signal. */
    fun carModeActive(context: Context): Boolean = uiModeType(context) == "CAR"

    /**
     * The car bit of the *current Configuration*, which is resolved per-context and can differ from
     * the global UiModeManager answer (for example on a secondary display with its own config).
     */
    fun configCarBit(context: Context): String = try {
        val masked = context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
        if (masked == Configuration.UI_MODE_TYPE_CAR) "car" else "not_car(0x" + masked.toString(16) + ")"
    } catch (t: Throwable) {
        "error: " + describe(t)
    }

    /** Distinguishes Android Automotive OS (built into a vehicle) from phone projection. */
    fun automotiveFeature(context: Context): String = try {
        val auto = context.packageManager.hasSystemFeature("android.hardware.type.automotive")
        val embedded = context.packageManager.hasSystemFeature("android.hardware.type.embedded")
        "automotive=" + auto + " embedded=" + embedded
    } catch (t: Throwable) {
        "error: " + describe(t)
    }

    // ------------------------------------------------------------------ USB

    /**
     * USB summary. Wired Android Auto presents the head unit as a USB accessory (phone in accessory
     * mode) so accessory presence is the more interesting half.
     *
     * Only vendor and product ids are recorded. Serial numbers are deliberately never read: they
     * identify hardware and the lab collects no identifying data (spec section 1).
     */
    fun usbSummary(context: Context): String = try {
        val usb = context.getSystemService(Context.USB_SERVICE) as? UsbManager
        if (usb == null) {
            "no_usb_service"
        } else {
            val devices = runCatching { usb.deviceList }.getOrNull().orEmpty()
            val accessories = runCatching { usb.accessoryList }.getOrNull()?.toList().orEmpty()
            val ids = devices.values.joinToString(" ") { d ->
                runCatching { "vid=" + d.vendorId + "/pid=" + d.productId + "/cls=" + d.deviceClass }
                    .getOrDefault("unreadable")
            }
            val acc = accessories.joinToString(" ") { a ->
                runCatching { (a.manufacturer ?: "?") + "/" + (a.model ?: "?") }.getOrDefault("unreadable")
            }
            "devices=" + devices.size +
                (if (ids.isEmpty()) "" else " [" + ids + "]") +
                " accessories=" + accessories.size +
                (if (acc.isEmpty()) "" else " [" + acc + "]")
        }
    } catch (t: Throwable) {
        "error: " + describe(t)
    }

    fun usbDeviceCount(context: Context): Int = try {
        (context.getSystemService(Context.USB_SERVICE) as? UsbManager)?.deviceList?.size ?: 0
    } catch (t: Throwable) {
        0
    }

    // ------------------------------------------------------------------ Wi-Fi

    /**
     * Wi-Fi link summary. Wireless Android Auto and Miracast both need a 5 GHz-capable link, so the
     * frequency band is the fact worth recording, not the SSID.
     *
     * The SSID is frequently redacted by the platform ("<unknown ssid>") when the caller lacks
     * location permission; that redaction is recorded verbatim rather than worked around.
     */
    @Suppress("DEPRECATION")
    fun wifiSummary(context: Context): String = try {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wm == null) {
            "no_wifi_service"
        } else if (!wm.isWifiEnabled) {
            "wifi_disabled"
        } else {
            val info: android.net.wifi.WifiInfo? = wm.connectionInfo
            val freq = info?.frequency ?: -1
            val netId = info?.networkId ?: -1
            if (info == null || (netId == -1 && freq <= 0)) {
                "wifi_enabled_not_associated"
            } else {
                "ssid=" + (info.ssid ?: "?") +
                    " freq=" + freq + "MHz band=" + band(freq) +
                    " linkSpeed=" + info.linkSpeed + "Mbps rssi=" + info.rssi
            }
        }
    } catch (t: Throwable) {
        "error: " + describe(t)
    }

    @Suppress("DEPRECATION")
    fun wifiFrequencyMhz(context: Context): Int = try {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        wm?.connectionInfo?.frequency ?: -1
    } catch (t: Throwable) {
        -1
    }

    fun band(freqMhz: Int): String = when {
        freqMhz <= 0 -> "none"
        freqMhz < 2500 -> "2.4GHz"
        freqMhz < 5900 -> "5GHz"
        freqMhz < 7200 -> "6GHz"
        else -> "unknown"
    }

    /**
     * Presence of a Wi-Fi Direct interface. A live p2p interface is the fingerprint shared by
     * Miracast and wireless Android Auto, which is exactly why the two may contend for it.
     */
    fun p2pInterfaces(): String = try {
        val all = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        val names = all
            .filter { it.name != null && it.name.contains("p2p") }
            .map { it.name + (if (runCatching { it.isUp }.getOrDefault(false)) ":up" else ":down") }
        when {
            // An empty interface list means the enumeration itself told us nothing, which is not the
            // same finding as "no Wi-Fi Direct interface exists". Keep the two apart.
            all.isEmpty() -> "interface_list_unreadable"
            names.isEmpty() -> "none (of " + all.size + " interfaces)"
            else -> names.joinToString(",")
        }
    } catch (t: Throwable) {
        "error: " + describe(t)
    }

    // ------------------------------------------------------------------ transport

    fun activeTransport(context: Context): String = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm == null) {
            "no_connectivity_service"
        } else {
            val net = cm.activeNetwork
            val caps = net?.let { cm.getNetworkCapabilities(it) }
            if (caps == null) {
                "none"
            } else {
                val parts = mutableListOf<String>()
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) parts += "WIFI"
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) parts += "CELLULAR"
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) parts += "ETHERNET"
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) parts += "BLUETOOTH"
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) parts += "VPN"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_USB)
                ) {
                    parts += "USB"
                }
                if (parts.isEmpty()) "other" else parts.joinToString("+")
            }
        }
    } catch (t: Throwable) {
        "error: " + describe(t)
    }

    // ------------------------------------------------------------------ displays

    fun displaySummary(context: Context): String = try {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val displays = dm?.displays
        if (displays == null) {
            "no_display_service"
        } else {
            displays.joinToString("; ") { d ->
                runCatching {
                    "#" + d.displayId + " '" + d.name + "' state=" + d.state +
                        (if (d.displayId == Display.DEFAULT_DISPLAY) " (default)" else " (external)")
                }.getOrDefault("#? unreadable")
            }
        }
    } catch (t: Throwable) {
        "error: " + describe(t)
    }

    fun displayCount(context: Context): Int = try {
        (context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)?.displays?.size ?: -1
    } catch (t: Throwable) {
        -1
    }

    fun externalDisplayPresent(context: Context): Boolean = try {
        (context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
            ?.displays
            ?.any { it.displayId != Display.DEFAULT_DISPLAY } ?: false
    } catch (t: Throwable) {
        false
    }

    /**
     * The framework MediaRouter's selected live-video route and its presentation display. A wireless
     * display session (Miracast) surfaces here as a route with a non-null presentation display.
     */
    fun presentationRoute(context: Context): String = try {
        val mr = context.getSystemService(Context.MEDIA_ROUTER_SERVICE) as? FwMediaRouter
        if (mr == null) {
            "no_mediarouter_service"
        } else {
            val route = mr.getSelectedRoute(FwMediaRouter.ROUTE_TYPE_LIVE_VIDEO)
            val pd = route?.presentationDisplay
            "route=" + (route?.name ?: "none") +
                " presentationDisplay=" + (pd?.let { "#" + it.displayId + " '" + it.name + "'" } ?: "none")
        }
    } catch (t: Throwable) {
        "error: " + describe(t)
    }

    fun presentationDisplayPresent(context: Context): Boolean = try {
        val mr = context.getSystemService(Context.MEDIA_ROUTER_SERVICE) as? FwMediaRouter
        mr?.getSelectedRoute(FwMediaRouter.ROUTE_TYPE_LIVE_VIDEO)?.presentationDisplay != null
    } catch (t: Throwable) {
        false
    }

    // ------------------------------------------------------------------ audio

    /**
     * Best-effort active output route.
     *
     * A non-privileged app cannot ask the platform "which device is audio going to right now" on
     * every API level, so this combines the routing flags with the attached-device list and is
     * graded INFERRED wherever it is emitted as an observation.
     */
    @Suppress("DEPRECATION")
    fun audioRoute(context: Context): String = try {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (am == null) {
            "no_audio_service"
        } else {
            val flags = buildList {
                if (runCatching { am.isBluetoothA2dpOn }.getOrDefault(false)) add("bt_a2dp")
                if (runCatching { am.isBluetoothScoOn }.getOrDefault(false)) add("bt_sco")
                if (runCatching { am.isSpeakerphoneOn }.getOrDefault(false)) add("speakerphone")
                if (runCatching { am.isWiredHeadsetOn }.getOrDefault(false)) add("wired_headset")
                if (runCatching { am.isMusicActive }.getOrDefault(false)) add("music_active")
            }
            val devices = runCatching {
                am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { audioTypeName(it.type) }.distinct()
            }.getOrDefault(listOf("unreadable"))
            "flags=[" + flags.joinToString(",") + "] outputs=[" + devices.joinToString(",") + "]"
        }
    } catch (t: Throwable) {
        "error: " + describe(t)
    }

    private fun audioTypeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "BUILTIN_SPEAKER"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "BUILTIN_EARPIECE"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "WIRED_HEADPHONES"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BLUETOOTH_A2DP"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BLUETOOTH_SCO"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB_ACCESSORY"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        AudioDeviceInfo.TYPE_HDMI_ARC -> "HDMI_ARC"
        AudioDeviceInfo.TYPE_AUX_LINE -> "AUX_LINE"
        AudioDeviceInfo.TYPE_LINE_ANALOG -> "LINE_ANALOG"
        AudioDeviceInfo.TYPE_LINE_DIGITAL -> "LINE_DIGITAL"
        AudioDeviceInfo.TYPE_TELEPHONY -> "TELEPHONY"
        AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "REMOTE_SUBMIX"
        AudioDeviceInfo.TYPE_DOCK -> "DOCK"
        AudioDeviceInfo.TYPE_IP -> "IP"
        AudioDeviceInfo.TYPE_BUS -> "BUS"
        else -> "TYPE_" + type
    }

    // ------------------------------------------------------------------ snapshot

    /**
     * Ordered snapshot used by the matrix auto-capture, the live environment strip and the
     * liveness recorder. Keys are stable so a saved matrix stays comparable across runs.
     */
    fun snapshot(context: Context): LinkedHashMap<String, String> {
        val m = LinkedHashMap<String, String>()
        m["capturedAt"] = nowIso()
        m["uiModeType"] = uiModeType(context)
        m["carMode"] = carModeActive(context).toString()
        m["configCarBit"] = configCarBit(context)
        m["automotive"] = automotiveFeature(context)
        m["usb"] = usbSummary(context)
        m["wifi"] = wifiSummary(context)
        m["p2pInterfaces"] = p2pInterfaces()
        m["transport"] = activeTransport(context)
        m["displays"] = displaySummary(context)
        m["externalDisplay"] = externalDisplayPresent(context).toString()
        m["presentationRoute"] = presentationRoute(context)
        m["audio"] = audioRoute(context)
        return m
    }

    /** Cheap subset sampled once per second by the motion-state liveness recorder. */
    fun liveSnapshot(context: Context): LinkedHashMap<String, String> {
        val m = LinkedHashMap<String, String>()
        m["displayCount"] = displayCount(context).toString()
        m["externalDisplay"] = externalDisplayPresent(context).toString()
        m["presentationDisplay"] = presentationDisplayPresent(context).toString()
        m["transport"] = activeTransport(context)
        m["audio"] = audioRoute(context)
        m["carMode"] = carModeActive(context).toString()
        return m
    }

    private fun describe(t: Throwable): String =
        t::class.java.simpleName + ": " + (t.message ?: "no message")
}
