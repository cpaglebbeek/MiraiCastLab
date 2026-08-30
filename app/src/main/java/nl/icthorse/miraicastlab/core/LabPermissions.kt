package nl.icthorse.miraicastlab.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Runtime permission helper.
 *
 * Every permission the lab asks for is optional (spec section 18): the app must start and remain
 * useful when the tester denies one. Denials are logged as UNSUPPORTED-for-this-run rather than
 * treated as errors, and the affected probes report NOT_TESTED.
 */
object LabPermissions {

    /** A permission the app may request, with the tester-facing reason it exists. */
    data class Request(
        val permission: String,
        val why: String,
        val neededFor: String,
        val minSdk: Int = 1,
        val maxSdk: Int = Int.MAX_VALUE,
    ) {
        val appliesHere: Boolean
            get() = Build.VERSION.SDK_INT in minSdk..maxSdk
    }

    /**
     * Wi-Fi P2P peer discovery needs NEARBY_WIFI_DEVICES from API 33 and fine location before it.
     * We ask for exactly one of the two, never both.
     */
    val NEARBY: Request
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Request(
                Manifest.permission.NEARBY_WIFI_DEVICES,
                "Android requires this before an app may list Wi-Fi Direct peers.",
                "Wi-Fi Direct / Miracast peer discovery",
                minSdk = Build.VERSION_CODES.TIRAMISU,
            )
        } else {
            Request(
                Manifest.permission.ACCESS_FINE_LOCATION,
                "Before Android 13, Wi-Fi Direct peer discovery was gated behind location access. " +
                    "MiraiCast Lab never reads your location.",
                "Wi-Fi Direct / Miracast peer discovery",
                maxSdk = 32, // Build.VERSION_CODES.S_V2
            )
        }

    val RECORD_AUDIO = Request(
        Manifest.permission.RECORD_AUDIO,
        "Used only to characterise which audio inputs exist and whether playback capture is " +
            "permitted. No audio is stored or transmitted.",
        "Audio route and capture characterisation",
    )

    val NOTIFICATIONS = Request(
        Manifest.permission.POST_NOTIFICATIONS,
        "Android requires a visible notification while a screen-capture service runs.",
        "MediaProjection foreground service",
        minSdk = Build.VERSION_CODES.TIRAMISU,
    )

    val BLUETOOTH = Request(
        Manifest.permission.BLUETOOTH_CONNECT,
        "Used only to list already-paired input devices for the HID baseline test. " +
            "The app never initiates pairing.",
        "Bluetooth HID input baseline",
        minSdk = Build.VERSION_CODES.S,
    )

    /** All requests that apply on this Android version. */
    fun all(): List<Request> = listOf(NEARBY, RECORD_AUDIO, NOTIFICATIONS, BLUETOOTH)
        .filter { it.appliesHere }

    fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun isGranted(context: Context, request: Request): Boolean =
        !request.appliesHere || isGranted(context, request.permission)

    /** Reports the grant state of every applicable permission as observations. */
    fun observe(context: Context): List<Observation> = all().map { r ->
        Observation(
            key = "permission." + r.permission.substringAfterLast('.').lowercase(),
            value = if (isGranted(context, r)) "granted" else "denied",
            status = LabStatus.CONFIRMED,
            note = r.neededFor,
            category = LabCategory.DEVICE,
        )
    }
}
