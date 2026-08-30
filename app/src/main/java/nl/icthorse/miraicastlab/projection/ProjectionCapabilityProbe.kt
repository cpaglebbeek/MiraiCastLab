package nl.icthorse.miraicastlab.projection

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjectionManager
import android.os.Build
import nl.icthorse.miraicastlab.core.LabCategory
import nl.icthorse.miraicastlab.core.LabPermissions
import nl.icthorse.miraicastlab.core.LabStatus
import nl.icthorse.miraicastlab.core.Observation
import nl.icthorse.miraicastlab.core.Probe

/**
 * What MediaProjection could do here, established without touching the tester (spec section 2.4).
 *
 * Everything in this probe is answerable from the platform alone: whether the service resolves,
 * whether the permissions and the typed foreground service are in place, what geometry a
 * VirtualDisplay would target, and what the AVC encoder says about itself. The moment a finding
 * would need the consent dialog, it is NOT_TESTED and stays that way until
 * [MediaProjectionScreen] actually runs the experiment - "the user has not pressed the button yet"
 * is not evidence of absence.
 *
 * The AVC encoder is instantiated and released here rather than only read from MediaCodecList:
 * createEncoderByType is the call the experiment will make, so it is the call worth testing.
 */
object ProjectionCapabilityProbe : Probe {

    override val id = "projection"
    override val title = "MediaProjection capability"

    private val C = LabCategory.MEDIAPROJECTION
    private val MIME_AVC: String = MediaFormat.MIMETYPE_VIDEO_AVC

    override suspend fun observe(context: Context): List<Observation> {
        val out = mutableListOf<Observation>()
        out += manager(context)
        out += permissions(context)
        out += foregroundService(context)
        out += target(context)
        out += encoder(context)
        out += boundaries()
        return out
    }

    // ------------------------------------------------------------- the service

    private fun manager(context: Context): List<Observation> {
        val out = mutableListOf<Observation>()
        val mgr = try {
            context.getSystemService(MediaProjectionManager::class.java)
        } catch (t: Throwable) {
            out += Observation.error("projection.manager", t, C)
            null
        }
        if (mgr == null) {
            // The platform answered: there is no media projection service here. That is a real
            // UNSUPPORTED, unlike anything that merely needs the user.
            out += Observation.unsupported(
                "projection.manager_available",
                C,
                "getSystemService(MediaProjectionManager) returned null.",
            )
            out += Observation.notTested(
                "projection.consent_intent_resolvable",
                C,
                "No MediaProjectionManager to build a consent Intent with.",
            )
            return out
        }

        out += Observation.confirmed(
            "projection.manager_available",
            mgr::class.java.name,
            C,
            "MediaProjectionManager resolves, so screen capture consent can be requested.",
        )

        // createScreenCaptureIntent only builds an Intent; it shows nothing and needs no consent.
        try {
            val intent = mgr.createScreenCaptureIntent()
            val info = intent.resolveActivityInfo(context.packageManager, 0)
            if (info != null) {
                out += Observation.confirmed(
                    "projection.consent_intent_resolvable",
                    info.packageName + "/" + info.name,
                    C,
                    "The system consent dialog exists and can be launched.",
                )
                out += Observation.confirmed(
                    "projection.consent_intent_exported",
                    info.exported,
                    C,
                )
            } else {
                out += Observation.unsupported(
                    "projection.consent_intent_resolvable",
                    C,
                    "PackageManager resolved no activity for the screen capture Intent.",
                )
            }
        } catch (t: Throwable) {
            out += Observation.error("projection.consent_intent_resolvable", t, C)
        }
        return out
    }

    // ---------------------------------------------------------- permissions

    private fun permissions(context: Context): List<Observation> {
        val out = mutableListOf<Observation>()

        out += grant(
            context,
            Manifest.permission.FOREGROUND_SERVICE,
            "projection.permission.foreground_service",
            "Required to run the capture service at all.",
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            out += grant(
                context,
                Manifest.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION,
                "projection.permission.foreground_service_media_projection",
                "Android 14+ refuses createVirtualDisplay without this typed foreground service.",
            )
        } else {
            out += Observation.confirmed(
                "projection.permission.foreground_service_media_projection",
                "not required below API 34",
                C,
                "Typed foreground service permissions were introduced in Android 14.",
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = LabPermissions.isGranted(context, Manifest.permission.POST_NOTIFICATIONS)
            out += Observation(
                "projection.permission.post_notifications",
                if (granted) "granted" else "denied",
                LabStatus.CONFIRMED,
                if (granted) {
                    "The mandatory capture notification can be shown."
                } else {
                    "Capture may still start, but the mandatory foreground-service notification " +
                        "will not be visible to the tester."
                },
                C,
            )
        } else {
            out += Observation.confirmed(
                "projection.permission.post_notifications",
                "not required below API 33",
                C,
            )
        }

        // Whether the channel is muted or blocked decides whether the tester sees the capture chip.
        try {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm == null) {
                out += Observation.unsupported(
                    "projection.notifications_enabled",
                    C,
                    "getSystemService(NotificationManager) returned null.",
                )
            } else {
                out += Observation.confirmed(
                    "projection.notifications_enabled",
                    nm.areNotificationsEnabled(),
                    C,
                    "App-level notification switch, which the capture notification depends on.",
                )
                val channel = nm.getNotificationChannel(ProjectionService.CHANNEL_ID)
                out += if (channel == null) {
                    Observation.notTested(
                        "projection.notification_channel",
                        C,
                        "The capture channel is created on first use; it does not exist yet.",
                    )
                } else {
                    Observation.confirmed(
                        "projection.notification_channel",
                        "importance=" + channel.importance,
                        C,
                    )
                }
            }
        } catch (t: Throwable) {
            out += Observation.error("projection.notifications_enabled", t, C)
        }

        out += Observation(
            "projection.permission.record_audio",
            if (LabPermissions.isGranted(context, Manifest.permission.RECORD_AUDIO)) "granted" else "denied",
            LabStatus.CONFIRMED,
            "Decides whether AudioPlaybackCaptureConfiguration can be exercised at all.",
            LabCategory.AUDIO,
        )
        return out
    }

    private fun grant(context: Context, permission: String, key: String, note: String): Observation =
        Observation(
            key,
            if (LabPermissions.isGranted(context, permission)) "granted" else "denied",
            LabStatus.CONFIRMED,
            note,
            C,
        )

    private fun foregroundService(context: Context): List<Observation> {
        val out = mutableListOf<Observation>()
        try {
            val component = ComponentName(context, ProjectionService::class.java)
            val info = context.packageManager.getServiceInfo(component, 0)
            out += Observation.confirmed("projection.service_declared", info.name, C)
            val type = info.foregroundServiceType
            val hasMediaProjection =
                type and ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION != 0
            out += Observation(
                "projection.service_fgs_type",
                if (hasMediaProjection) "mediaProjection (0x" + Integer.toHexString(type) + ")"
                else "0x" + Integer.toHexString(type),
                if (hasMediaProjection) LabStatus.CONFIRMED else LabStatus.UNSUPPORTED,
                "Read back from the installed manifest, not from source.",
                C,
            )
        } catch (t: PackageManager.NameNotFoundException) {
            out += Observation.unsupported(
                "projection.service_declared",
                C,
                "ProjectionService is not present in the installed package.",
            )
        } catch (t: Throwable) {
            out += Observation.error("projection.service_declared", t, C)
        }
        return out
    }

    // -------------------------------------------------------------- geometry

    private fun target(context: Context): List<Observation> {
        val t = CaptureTarget.measure(context)
        val out = mutableListOf<Observation>()
        val status = if (t.isUsable) LabStatus.CONFIRMED else LabStatus.ERROR
        out += Observation(
            "projection.source_resolution",
            t.sourceResolution,
            status,
            "Default display, read via " + t.source + ".",
            LabCategory.DISPLAY,
        )
        out += Observation(
            "projection.target_resolution",
            t.resolution,
            status,
            "What a VirtualDisplay would be created with: long edge capped at " +
                CaptureTarget.MAX_LONG_EDGE + " px, both edges floored to a multiple of 16 " +
                "(aspect drift " + String.format("%.2f", t.aspectErrorPercent) + "%).",
            C,
        )
        out += Observation(
            "projection.target_density_dpi",
            t.densityDpi.toString(),
            status,
            null,
            C,
        )
        out += Observation(
            "projection.target_scale",
            String.format("%.3f", t.scale),
            status,
            null,
            C,
        )
        t.error?.let {
            out += Observation("projection.target_read_errors", it, LabStatus.ERROR, null, C)
        }
        return out
    }

    // --------------------------------------------------------------- encoder

    private fun encoder(context: Context): List<Observation> {
        val out = mutableListOf<Observation>()
        var codec: MediaCodec? = null
        try {
            val instance = MediaCodec.createEncoderByType(MIME_AVC)
            codec = instance
            val info = instance.codecInfo
            out += Observation.confirmed(
                "projection.avc_encoder",
                info.name,
                LabCategory.CODEC,
                "The encoder MediaCodec.createEncoderByType(\"" + MIME_AVC + "\") actually returns.",
            )
            out += Observation.confirmed(
                "projection.avc_encoder_hardware_accelerated",
                info.isHardwareAccelerated,
                LabCategory.CODEC,
            )

            val caps = info.getCapabilitiesForType(MIME_AVC)
            out += Observation.confirmed(
                "projection.avc_encoder_max_instances",
                caps.maxSupportedInstances,
                LabCategory.CODEC,
                "Concurrent instances of this encoder the device admits to supporting.",
            )
            val surfaceInput = caps.colorFormats.any {
                it == MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            }
            out += Observation(
                "projection.avc_encoder_surface_input",
                if (surfaceInput) "COLOR_FormatSurface" else "absent",
                if (surfaceInput) LabStatus.CONFIRMED else LabStatus.UNSUPPORTED,
                "Surface input is what lets a VirtualDisplay feed the encoder without a copy.",
                LabCategory.CODEC,
            )

            val video = caps.videoCapabilities
            if (video == null) {
                out += Observation.unsupported(
                    "projection.avc_encoder_target_size_supported",
                    LabCategory.CODEC,
                    "The encoder reported no VideoCapabilities.",
                )
            } else {
                val target = CaptureTarget.measure(context)
                val sizeOk = try {
                    video.isSizeSupported(target.width, target.height)
                } catch (t: Throwable) {
                    false
                }
                val rateOk = try {
                    video.areSizeAndRateSupported(target.width, target.height, 30.0)
                } catch (t: Throwable) {
                    false
                }
                out += Observation(
                    "projection.avc_encoder_target_size_supported",
                    target.resolution + " size=" + sizeOk + " at30fps=" + rateOk,
                    if (sizeOk) LabStatus.CONFIRMED else LabStatus.UNSUPPORTED,
                    "Whether the geometry this module would capture at is encodable here.",
                    LabCategory.CODEC,
                )
                out += Observation.confirmed(
                    "projection.avc_encoder_bitrate_range",
                    video.bitrateRange.toString(),
                    LabCategory.CODEC,
                )
                out += Observation.confirmed(
                    "projection.avc_encoder_frame_rate_range",
                    video.supportedFrameRates.toString(),
                    LabCategory.CODEC,
                )
                out += Observation.confirmed(
                    "projection.avc_encoder_alignment",
                    video.widthAlignment.toString() + "x" + video.heightAlignment,
                    LabCategory.CODEC,
                )
            }
        } catch (t: Throwable) {
            // createEncoderByType throws IOException when no codec exists; that is the platform
            // answering, but the throwable could equally be a transient resource shortage, so the
            // honest grade is ERROR with the exception text attached.
            out += Observation.error("projection.avc_encoder", t, LabCategory.CODEC)
        } finally {
            try {
                codec?.release()
            } catch (t: Throwable) {
                // A leaked codec instance would poison the encode test later; nothing else to do.
            }
        }
        return out
    }

    // ------------------------------------------------------------ boundaries

    private fun boundaries(): List<Observation> = listOf(
        Observation.notTested(
            "projection.consent_dialog",
            C,
            "Screen capture consent needs a user gesture. Run it from the MediaProjection screen.",
        ),
        Observation.notTested(
            "projection.capture_session_pending",
            C,
            "Frame rate, captured geometry and first-frame latency require a granted capture " +
                "session; none has run in this test run yet.",
        ),
        Observation(
            "projection.flag_secure_windows",
            "excluded by the platform",
            LabStatus.CONFIRMED,
            "Windows marked FLAG_SECURE are blanked in any MediaProjection capture. A black " +
                "region in a mirrored session is therefore expected behaviour, not a fault.",
            C,
        ),
        Observation.notTested(
            "projection.sink_receives",
            LabCategory.MIRACAST,
            "MediaProjection measures only what this phone can capture and encode locally. What " +
                "the Toyota Mirai head unit receives over Miracast is negotiated by the sink and " +
                "was not measured here.",
        ),
    )
}
