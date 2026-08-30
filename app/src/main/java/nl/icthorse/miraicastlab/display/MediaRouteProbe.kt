package nl.icthorse.miraicastlab.display

import android.content.Context
import android.media.MediaRouter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.icthorse.miraicastlab.core.LabCategory
import nl.icthorse.miraicastlab.core.LabStatus
import nl.icthorse.miraicastlab.core.Observation
import nl.icthorse.miraicastlab.core.Probe

/**
 * Framework MediaRouter enumeration (spec sections 2.2, 6).
 *
 * android.media.MediaRouter - not androidx.mediarouter, which is not a dependency of this project.
 * This is the one place where an unprivileged app can legitimately see that the system has a live
 * video route to something that is not the phone: when Smart View / Miracast is up, the selected
 * LIVE_VIDEO route stops being the default route and usually gains a presentation display.
 *
 * MediaRouter is main-thread affine, so the whole read is marshalled onto Dispatchers.Main.
 */
object MediaRouteProbe : Probe {

    override val id = "mediaroute"
    override val title = "MediaRouter routes"

    private val TYPE_LIVE_AUDIO = MediaRouter.ROUTE_TYPE_LIVE_AUDIO
    private val TYPE_LIVE_VIDEO = MediaRouter.ROUTE_TYPE_LIVE_VIDEO
    private val TYPE_USER = MediaRouter.ROUTE_TYPE_USER

    /**
     * The remote-display bit is not part of the public MediaRouter surface on every platform
     * version, so the value is written out rather than referenced. It is only used to *decode* a
     * bitmask that the platform handed us; nothing is called with it.
     */
    private val TYPE_REMOTE_DISPLAY = 1 shl 2

    override suspend fun observe(context: Context): List<Observation> = withContext(Dispatchers.Main) {
        val out = mutableListOf<Observation>()

        val router = context.getSystemService(Context.MEDIA_ROUTER_SERVICE) as? MediaRouter
        if (router == null) {
            out += Observation.unsupported(
                "mediaroute.available",
                LabCategory.DISPLAY,
                "getSystemService(MEDIA_ROUTER_SERVICE) returned null; the platform has no MediaRouter here.",
            )
            return@withContext out
        }
        out += Observation.confirmed("mediaroute.available", true, LabCategory.DISPLAY)

        val count = try {
            router.routeCount
        } catch (t: Throwable) {
            out += Observation.error("mediaroute.count", t, LabCategory.DISPLAY)
            return@withContext out
        }
        out += Observation.confirmed(
            "mediaroute.count",
            count,
            LabCategory.DISPLAY,
            "MediaRouter.getRouteCount(): every route the system currently knows about, of any type.",
        )

        var withPresentationDisplay = 0
        var remoteDisplayRoutes = 0

        for (i in 0 until count) {
            val route = try {
                router.getRouteAt(i)
            } catch (t: Throwable) {
                out += Observation.error("mediaroute.route." + i, t, LabCategory.DISPLAY)
                continue
            }
            if (route == null) continue

            val p = "mediaroute.route." + i + "."
            val name = safeText { route.getName(context) } ?: safeText { route.name } ?: "unnamed"
            val types = try { route.supportedTypes } catch (t: Throwable) { 0 }
            val presentationId = try { route.presentationDisplay?.displayId } catch (t: Throwable) { null }
            if (presentationId != null) withPresentationDisplay++
            if (types and TYPE_REMOTE_DISPLAY != 0) remoteDisplayRoutes++

            out += Observation.confirmed(p + "name", name, LabCategory.DISPLAY)
            out += Observation.confirmed(
                p + "types",
                typeNames(types),
                LabCategory.DISPLAY,
                "Supported route types as a bitmask decode (0x" + Integer.toHexString(types) + ").",
            )
            out += Observation.confirmed(
                p + "description",
                safeText { route.description } ?: "none",
                LabCategory.DISPLAY,
            )
            out += Observation.confirmed(
                p + "status",
                safeText { route.status } ?: "none",
                LabCategory.DISPLAY,
                "RouteInfo.getStatus(): the user-visible status text the system attaches to the route.",
            )

            // getStatusCode() is not public API. It is read reflectively, isolated and guarded, and
            // is reported as INFERRED because it is a private surface that may vanish or be blocked
            // by the hidden-API policy - which is itself recorded rather than hidden.
            val code = reflectiveStatusCode(route)
            out += if (code == null) {
                Observation.notTested(
                    p + "status_code",
                    LabCategory.DISPLAY,
                    "RouteInfo.getStatusCode() is not accessible to an ordinary app on this platform " +
                        "(hidden-API policy). The public status text above is unaffected.",
                )
            } else {
                Observation(
                    key = p + "status_code",
                    value = statusCodeName(code) + " (" + code + ")",
                    status = LabStatus.INFERRED,
                    note = "Read via reflection from a non-public getter; the decode table is the AOSP one " +
                        "and could differ on this vendor build.",
                    category = LabCategory.DISPLAY,
                )
            }

            out += Observation.confirmed(
                p + "enabled",
                try { route.isEnabled } catch (t: Throwable) { "unreadable" },
                LabCategory.DISPLAY,
            )
            out += Observation.confirmed(
                p + "playback_type",
                playbackTypeName(try { route.playbackType } catch (t: Throwable) { -1 }),
                LabCategory.DISPLAY,
            )
            out += Observation.confirmed(
                p + "playback_stream",
                streamName(try { route.playbackStream } catch (t: Throwable) { -1 }),
                LabCategory.DISPLAY,
            )
            out += Observation.confirmed(
                p + "volume",
                try { route.volume.toString() + "/" + route.volumeMax } catch (t: Throwable) { "unreadable" },
                LabCategory.DISPLAY,
            )
            out += Observation.confirmed(
                p + "volume_handling",
                volumeHandlingName(try { route.volumeHandling } catch (t: Throwable) { -1 }),
                LabCategory.DISPLAY,
                "VARIABLE means the remote sink accepts volume commands from the phone.",
            )
            out += Observation.confirmed(
                p + "device_type",
                deviceTypeName(try { route.deviceType } catch (t: Throwable) { -1 }),
                LabCategory.DISPLAY,
            )
            out += Observation.confirmed(
                p + "category",
                safeText { route.category?.getName(context) } ?: "none",
                LabCategory.DISPLAY,
            )
            out += if (presentationId == null) {
                Observation(
                    key = p + "presentation_display",
                    value = "none",
                    status = LabStatus.CONFIRMED,
                    note = "This route carries no presentation display; it cannot be a live remote screen.",
                    category = LabCategory.DISPLAY,
                )
            } else {
                Observation.confirmed(
                    p + "presentation_display",
                    "displayId=" + presentationId,
                    LabCategory.DISPLAY,
                    "A route with a presentation display is the legitimate, public signal that a " +
                        "remote screen (Miracast / Smart View / Cast) is attached.",
                )
            }
        }

        // ---------------------------------------------------------------- selected routes

        listOf(
            "live_video" to TYPE_LIVE_VIDEO,
            "live_audio" to TYPE_LIVE_AUDIO,
            "live_av" to (TYPE_LIVE_VIDEO or TYPE_LIVE_AUDIO),
            "user" to TYPE_USER,
        ).forEach { (label, type) ->
            val key = "mediaroute.selected." + label
            try {
                val sel = router.getSelectedRoute(type)
                if (sel == null) {
                    out += Observation.unsupported(
                        key,
                        LabCategory.DISPLAY,
                        "MediaRouter reported no selected route for this type.",
                    )
                } else {
                    val presId = try { sel.presentationDisplay?.displayId } catch (t: Throwable) { null }
                    out += Observation.confirmed(
                        key,
                        (safeText { sel.getName(context) } ?: "unnamed") +
                            " [" + typeNames(try { sel.supportedTypes } catch (t: Throwable) { 0 }) + "]" +
                            (if (presId != null) " presentationDisplay=" + presId else ""),
                        LabCategory.DISPLAY,
                        "While nothing is cast, this is normally the built-in default route.",
                    )
                }
            } catch (t: Throwable) {
                out += Observation.error(key, t, LabCategory.DISPLAY)
            }
        }

        // ---------------------------------------------------------------- roll-up

        out += Observation.confirmed(
            "mediaroute.presentation_display_count",
            withPresentationDisplay,
            LabCategory.DISPLAY,
            "Routes reporting a presentation display.",
        )
        out += Observation.confirmed(
            "mediaroute.remote_display_route_count",
            remoteDisplayRoutes,
            LabCategory.DISPLAY,
            "Routes advertising ROUTE_TYPE_REMOTE_DISPLAY.",
        )
        out += if (withPresentationDisplay > 0) {
            Observation(
                key = "mediaroute.remote_screen_evidence",
                value = "present",
                status = LabStatus.OBSERVED,
                note = "At least one MediaRouter route currently exposes a presentation display. " +
                    "That proves a remote screen is attached; it does not prove which technology " +
                    "(Miracast, Smart View, DeX, Cast) or which sink.",
                category = LabCategory.MIRACAST,
            )
        } else {
            Observation.notTested(
                "mediaroute.remote_screen_evidence",
                LabCategory.MIRACAST,
                "No route exposes a presentation display right now. Nothing is attached at probe " +
                    "time; this is not evidence that the sink would be rejected.",
            )
        }

        out
    }

    // ---------------------------------------------------------------- decoding helpers

    private fun typeNames(types: Int): String {
        val names = buildList {
            if (types and TYPE_LIVE_AUDIO != 0) add("LIVE_AUDIO")
            if (types and TYPE_LIVE_VIDEO != 0) add("LIVE_VIDEO")
            if (types and TYPE_REMOTE_DISPLAY != 0) add("REMOTE_DISPLAY")
            if (types and TYPE_USER != 0) add("USER")
        }
        return if (names.isEmpty()) "none" else names.joinToString("|")
    }

    private fun playbackTypeName(v: Int): String = when (v) {
        MediaRouter.RouteInfo.PLAYBACK_TYPE_LOCAL -> "LOCAL"
        MediaRouter.RouteInfo.PLAYBACK_TYPE_REMOTE -> "REMOTE"
        else -> "unreadable(" + v + ")"
    }

    private fun volumeHandlingName(v: Int): String = when (v) {
        MediaRouter.RouteInfo.PLAYBACK_VOLUME_FIXED -> "FIXED"
        MediaRouter.RouteInfo.PLAYBACK_VOLUME_VARIABLE -> "VARIABLE"
        else -> "unreadable(" + v + ")"
    }

    private fun deviceTypeName(v: Int): String = when (v) {
        MediaRouter.RouteInfo.DEVICE_TYPE_UNKNOWN -> "UNKNOWN"
        MediaRouter.RouteInfo.DEVICE_TYPE_TV -> "TV"
        MediaRouter.RouteInfo.DEVICE_TYPE_SPEAKER -> "SPEAKER"
        MediaRouter.RouteInfo.DEVICE_TYPE_BLUETOOTH -> "BLUETOOTH"
        else -> "other(" + v + ")"
    }

    private fun streamName(v: Int): String = when (v) {
        android.media.AudioManager.STREAM_MUSIC -> "STREAM_MUSIC"
        android.media.AudioManager.STREAM_VOICE_CALL -> "STREAM_VOICE_CALL"
        android.media.AudioManager.STREAM_SYSTEM -> "STREAM_SYSTEM"
        android.media.AudioManager.STREAM_RING -> "STREAM_RING"
        android.media.AudioManager.STREAM_ALARM -> "STREAM_ALARM"
        android.media.AudioManager.STREAM_NOTIFICATION -> "STREAM_NOTIFICATION"
        android.media.AudioManager.STREAM_DTMF -> "STREAM_DTMF"
        android.media.AudioManager.STREAM_ACCESSIBILITY -> "STREAM_ACCESSIBILITY"
        else -> "stream(" + v + ")"
    }

    /** AOSP decode table for the non-public RouteInfo status codes. */
    private fun statusCodeName(code: Int): String = when (code) {
        0 -> "STATUS_NONE"
        1 -> "STATUS_SCANNING"
        2 -> "STATUS_CONNECTING"
        3 -> "STATUS_AVAILABLE"
        4 -> "STATUS_NOT_AVAILABLE"
        5 -> "STATUS_IN_USE"
        6 -> "STATUS_CONNECTED"
        else -> "STATUS_" + code
    }

    /**
     * Isolated, fully guarded reflective read of a non-public getter. Returns null on any failure,
     * including the API 28+ hidden-API blocklist, which is the expected outcome and is reported as
     * NOT_TESTED by the caller.
     */
    private fun reflectiveStatusCode(route: MediaRouter.RouteInfo): Int? = try {
        val m = route.javaClass.getMethod("getStatusCode")
        m.isAccessible = true
        (m.invoke(route) as? Int)
    } catch (t: Throwable) {
        null
    }

    private inline fun safeText(block: () -> CharSequence?): String? = try {
        block()?.toString()?.takeIf { it.isNotBlank() }
    } catch (t: Throwable) {
        null
    }
}
