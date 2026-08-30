package nl.icthorse.miraicastlab.audio

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import nl.icthorse.miraicastlab.core.LabCategory
import nl.icthorse.miraicastlab.core.LabPermissions
import nl.icthorse.miraicastlab.core.LabStatus
import nl.icthorse.miraicastlab.core.Observation
import nl.icthorse.miraicastlab.core.Probe

// Type ids that are numerically stable but whose Kotlin constants are not guaranteed to be present
// in every android.jar this project may be compiled against. Compared as ints, never dereferenced.
private const val TYPE_BUILTIN_SPEAKER_SAFE = 24
private const val TYPE_ECHO_REFERENCE = 28
private const val TYPE_DOCK_ANALOG = 31

/** Decodes AudioDeviceInfo.TYPE_* to a readable name. Unknown ids degrade to their number, never to a guess. */
internal fun audioDeviceTypeName(type: Int): String = when (type) {
    AudioDeviceInfo.TYPE_UNKNOWN -> "TYPE_UNKNOWN"
    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "TYPE_BUILTIN_EARPIECE"
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "TYPE_BUILTIN_SPEAKER"
    AudioDeviceInfo.TYPE_WIRED_HEADSET -> "TYPE_WIRED_HEADSET"
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "TYPE_WIRED_HEADPHONES"
    AudioDeviceInfo.TYPE_LINE_ANALOG -> "TYPE_LINE_ANALOG"
    AudioDeviceInfo.TYPE_LINE_DIGITAL -> "TYPE_LINE_DIGITAL"
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "TYPE_BLUETOOTH_SCO"
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "TYPE_BLUETOOTH_A2DP"
    AudioDeviceInfo.TYPE_HDMI -> "TYPE_HDMI"
    AudioDeviceInfo.TYPE_HDMI_ARC -> "TYPE_HDMI_ARC"
    AudioDeviceInfo.TYPE_USB_DEVICE -> "TYPE_USB_DEVICE"
    AudioDeviceInfo.TYPE_USB_ACCESSORY -> "TYPE_USB_ACCESSORY"
    AudioDeviceInfo.TYPE_DOCK -> "TYPE_DOCK"
    AudioDeviceInfo.TYPE_FM -> "TYPE_FM"
    AudioDeviceInfo.TYPE_BUILTIN_MIC -> "TYPE_BUILTIN_MIC"
    AudioDeviceInfo.TYPE_FM_TUNER -> "TYPE_FM_TUNER"
    AudioDeviceInfo.TYPE_TV_TUNER -> "TYPE_TV_TUNER"
    AudioDeviceInfo.TYPE_TELEPHONY -> "TYPE_TELEPHONY"
    AudioDeviceInfo.TYPE_AUX_LINE -> "TYPE_AUX_LINE"
    AudioDeviceInfo.TYPE_IP -> "TYPE_IP"
    AudioDeviceInfo.TYPE_BUS -> "TYPE_BUS"
    AudioDeviceInfo.TYPE_USB_HEADSET -> "TYPE_USB_HEADSET"
    AudioDeviceInfo.TYPE_HEARING_AID -> "TYPE_HEARING_AID"
    TYPE_BUILTIN_SPEAKER_SAFE -> "TYPE_BUILTIN_SPEAKER_SAFE"
    AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "TYPE_REMOTE_SUBMIX"
    AudioDeviceInfo.TYPE_BLE_HEADSET -> "TYPE_BLE_HEADSET"
    AudioDeviceInfo.TYPE_BLE_SPEAKER -> "TYPE_BLE_SPEAKER"
    TYPE_ECHO_REFERENCE -> "TYPE_ECHO_REFERENCE"
    AudioDeviceInfo.TYPE_HDMI_EARC -> "TYPE_HDMI_EARC"
    AudioDeviceInfo.TYPE_BLE_BROADCAST -> "TYPE_BLE_BROADCAST"
    TYPE_DOCK_ANALOG -> "TYPE_DOCK_ANALOG"
    else -> "TYPE_$type"
}

/** Decodes AudioFormat.ENCODING_* for the encodings a device advertises. */
private fun encodingName(enc: Int): String = when (enc) {
    AudioFormat.ENCODING_INVALID -> "INVALID"
    AudioFormat.ENCODING_DEFAULT -> "DEFAULT"
    AudioFormat.ENCODING_PCM_16BIT -> "PCM_16BIT"
    AudioFormat.ENCODING_PCM_8BIT -> "PCM_8BIT"
    AudioFormat.ENCODING_PCM_FLOAT -> "PCM_FLOAT"
    AudioFormat.ENCODING_AC3 -> "AC3"
    AudioFormat.ENCODING_E_AC3 -> "E_AC3"
    AudioFormat.ENCODING_DTS -> "DTS"
    AudioFormat.ENCODING_DTS_HD -> "DTS_HD"
    AudioFormat.ENCODING_MP3 -> "MP3"
    AudioFormat.ENCODING_AAC_LC -> "AAC_LC"
    AudioFormat.ENCODING_AAC_HE_V1 -> "AAC_HE_V1"
    AudioFormat.ENCODING_AAC_HE_V2 -> "AAC_HE_V2"
    AudioFormat.ENCODING_IEC61937 -> "IEC61937"
    AudioFormat.ENCODING_OPUS -> "OPUS"
    else -> "enc_$enc"
}

private fun maskName(mask: Int): String = "0x" + Integer.toHexString(mask).uppercase()

/** One-line identity of an audio endpoint, used by the probe, the engine and the screen. */
internal fun describeAudioDevice(d: AudioDeviceInfo): String {
    val name = runCatching { d.productName?.toString() }.getOrNull().orEmpty()
    // getAddress() has moved between @SystemApi and public across SDK levels; reach it
    // reflectively so a missing method is data rather than a compile break.
    val addr = runCatching {
        AudioDeviceInfo::class.java.getMethod("getAddress").invoke(d) as? String
    }.getOrNull().orEmpty()
    return buildString {
        append(audioDeviceTypeName(d.type))
        if (name.isNotBlank()) append(" \"").append(name).append('"')
        append(" id=").append(d.id)
        if (addr.isNotBlank()) append(" addr=").append(addr)
    }
}

/** Everything the platform will tell us about one endpoint, for the note field. */
private fun detailAudioDevice(d: AudioDeviceInfo): String = buildString {
    append("sink=").append(runCatching { d.isSink }.getOrDefault(false))
    append(" source=").append(runCatching { d.isSource }.getOrDefault(false))
    val rates = runCatching { d.sampleRates }.getOrNull()
    append(" rates=").append(
        if (rates == null || rates.isEmpty()) "[unconstrained]" else rates.joinToString(",")
    )
    val ch = runCatching { d.channelCounts }.getOrNull()
    append(" channels=").append(
        if (ch == null || ch.isEmpty()) "[unconstrained]" else ch.joinToString(",")
    )
    val masks = runCatching { d.channelMasks }.getOrNull()
    if (masks != null && masks.isNotEmpty()) {
        append(" masks=").append(masks.joinToString(",") { maskName(it) })
    }
    val enc = runCatching { d.encodings }.getOrNull()
    if (enc != null && enc.isNotEmpty()) {
        append(" encodings=").append(enc.joinToString(",") { encodingName(it) })
    }
}

/**
 * Audio endpoints and the current media route (spec sections 5.1 and 6).
 *
 * Read-only by contract: the probe never opens an AudioTrack, because opening one would itself move
 * the route and wake a connected head unit's amplifier - it would change the thing it is measuring.
 * The one piece of hard routing evidence that requires playback (AudioTrack.getRoutedDevice) is
 * produced by [AudioTestScreen] while a tone is deliberately running, and is graded OBSERVED there.
 */
object AudioRouteProbe : Probe {
    override val id = "audio"
    override val title = "Audio devices & routes"

    override suspend fun observe(context: Context): List<Observation> {
        val out = mutableListOf<Observation>()
        val am = runCatching { context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }
            .getOrNull()

        if (am == null) {
            // The platform answered: there is no AudioManager here. That is UNSUPPORTED, not untested.
            out += Observation.unsupported(
                "audio.output_devices",
                LabCategory.AUDIO,
                "getSystemService(AUDIO_SERVICE) returned null or threw.",
            )
            out += Observation.unsupported(
                "audio.active_output",
                LabCategory.AUDIO,
                "No AudioManager, so no route can be read.",
            )
            return out
        }

        out += endpoints(am)
        out += activeOutput(am)
        out += managerState(am)
        out += capturePolicy(context, am)
        out += platformAudioFeatures(context)
        return out
    }

    // -------------------------------------------------------------------------------- endpoints

    private fun endpoints(am: AudioManager): List<Observation> {
        val out = mutableListOf<Observation>()

        val outputs = runCatching { am.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }
            .getOrElse { t ->
                out += Observation.error("audio.output_devices", t, LabCategory.AUDIO)
                return out
            }

        out += Observation.confirmed(
            "audio.output_devices",
            outputs.size,
            LabCategory.AUDIO,
            if (outputs.isEmpty()) {
                "AudioManager enumerated zero output endpoints."
            } else {
                outputs.joinToString("; ") { describeAudioDevice(it) }
            },
        )
        out += Observation.confirmed(
            "audio.output_types",
            outputs.map { audioDeviceTypeName(it.type) }.distinct().sorted().joinToString(","),
            LabCategory.AUDIO,
            "Distinct output endpoint types currently enumerated.",
        )
        outputs.forEachIndexed { i, d ->
            out += Observation.confirmed(
                "audio.output.$i",
                describeAudioDevice(d),
                LabCategory.AUDIO,
                detailAudioDevice(d),
            )
        }

        val inputs = runCatching { am.getDevices(AudioManager.GET_DEVICES_INPUTS) }.getOrNull()
        if (inputs == null) {
            out += Observation.error(
                "audio.input_devices",
                IllegalStateException("getDevices(GET_DEVICES_INPUTS) failed"),
                LabCategory.AUDIO,
            )
        } else {
            out += Observation.confirmed(
                "audio.input_devices",
                inputs.size,
                LabCategory.AUDIO,
                inputs.joinToString("; ") { describeAudioDevice(it) },
            )
            inputs.forEachIndexed { i, d ->
                out += Observation.confirmed(
                    "audio.input.$i",
                    describeAudioDevice(d),
                    LabCategory.AUDIO,
                    detailAudioDevice(d),
                )
            }
        }

        // Presence of specific endpoint classes. "false" here is a direct reading of the current
        // enumeration, not a claim about what the phone could ever expose.
        fun presence(key: String, type: Int, why: String) {
            val hit = outputs.filter { it.type == type }
            out += Observation.confirmed(
                key,
                hit.isNotEmpty(),
                LabCategory.AUDIO,
                if (hit.isEmpty()) {
                    "Not enumerated at probe time. $why"
                } else {
                    hit.joinToString("; ") { describeAudioDevice(it) }
                },
            )
        }
        presence(
            "audio.hdmi_output_present",
            AudioDeviceInfo.TYPE_HDMI,
            "An HDMI sink would appear here for a wired DeX dock.",
        )
        presence(
            "audio.bluetooth_a2dp_present",
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            "A2DP is the likely audio path when the Mirai is paired as a Bluetooth audio sink.",
        )
        presence(
            "audio.usb_output_present",
            AudioDeviceInfo.TYPE_USB_DEVICE,
            "A USB audio class device would appear here on a wired connection to the head unit.",
        )
        presence(
            "audio.aux_line_present",
            AudioDeviceInfo.TYPE_AUX_LINE,
            "Analogue AUX into the vehicle would appear here.",
        )

        // TYPE_REMOTE_SUBMIX is how the audio half of Wi-Fi Display is fed on many devices: the
        // framework mixes media into a submix that the Miracast encoder reads.
        val submix = outputs.filter { it.type == AudioDeviceInfo.TYPE_REMOTE_SUBMIX }
        out += if (submix.isNotEmpty()) {
            Observation(
                key = "audio.remote_submix_present",
                value = submix.joinToString("; ") { describeAudioDevice(it) },
                status = LabStatus.INFERRED,
                note = "A remote-submix output endpoint is exposed. On many Android builds this is " +
                    "the audio path of a Wi-Fi Display / Miracast session, so its presence is " +
                    "evidence of an active or available wireless-display audio route - but the " +
                    "endpoint alone does not prove a session exists. Confirm against " +
                    "miracast.session_evidence.",
                category = LabCategory.AUDIO,
            )
        } else {
            Observation.notTested(
                "audio.remote_submix_present",
                LabCategory.AUDIO,
                "No remote-submix output is enumerated right now. This endpoint typically only " +
                    "materialises while a wireless-display session is running, so its absence " +
                    "says nothing about whether this device supports one.",
            )
        }

        return out
    }

    // ---------------------------------------------------------------------------- active output

    /**
     * "Which endpoint would media go to right now?"
     *
     * Preferred answer comes from AudioManager's attribute-based routing query. That method is not
     * present or not callable on every level (it has been public, @SystemApi and permission-guarded
     * at different times), so it is reached reflectively and isolated here: a missing method must be
     * data, not a compile break or a crash. When it is unavailable we fall back to the deprecated
     * boolean route flags, which are a guess and are graded INFERRED accordingly.
     */
    private fun activeOutput(am: AudioManager): List<Observation> {
        val out = mutableListOf<Observation>()
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        var method: String? = null
        var devices: String? = null

        for (name in listOf("getAudioDevicesForAttributes", "getDevicesForAttributes")) {
            if (devices != null) break
            try {
                val m = AudioManager::class.java.getMethod(name, AudioAttributes::class.java)
                val result = m.invoke(am, attrs) as? List<*>
                if (result != null && result.isNotEmpty()) {
                    devices = result.joinToString("; ") { element ->
                        when (element) {
                            is AudioDeviceInfo -> describeAudioDevice(element)
                            null -> "null"
                            else -> element.toString()
                        }
                    }
                    method = "AudioManager.$name (reflective)"
                }
            } catch (t: Throwable) {
                // NoSuchMethodException, hidden-API blocklist or SecurityException: all mean
                // "cannot ask this way here", none mean "there is no route".
                out += Observation.notTested(
                    "audio.active_output.$name",
                    LabCategory.AUDIO,
                    t::class.java.simpleName + ": " + (t.message ?: "no message"),
                )
            }
        }

        if (devices != null) {
            out += Observation.confirmed(
                "audio.active_output",
                devices,
                LabCategory.AUDIO,
                "Reported by the platform for USAGE_MEDIA via $method.",
            )
            out += Observation.confirmed(
                "audio.active_output_method",
                method,
                LabCategory.AUDIO,
            )
            return out
        }

        @Suppress("DEPRECATION")
        val flags = runCatching {
            listOfNotNull(
                if (am.isBluetoothA2dpOn) "isBluetoothA2dpOn" else null,
                if (am.isBluetoothScoOn) "isBluetoothScoOn" else null,
                if (am.isSpeakerphoneOn) "isSpeakerphoneOn" else null,
                if (am.isWiredHeadsetOn) "isWiredHeadsetOn" else null,
            )
        }.getOrDefault(emptyList())

        val guess = when {
            "isBluetoothA2dpOn" in flags -> "TYPE_BLUETOOTH_A2DP (inferred)"
            "isBluetoothScoOn" in flags -> "TYPE_BLUETOOTH_SCO (inferred)"
            "isWiredHeadsetOn" in flags -> "wired headset/headphones (inferred)"
            "isSpeakerphoneOn" in flags -> "TYPE_BUILTIN_SPEAKER via speakerphone (inferred)"
            else -> "TYPE_BUILTIN_SPEAKER (default assumption)"
        }

        out += Observation(
            key = "audio.active_output",
            value = guess,
            status = LabStatus.INFERRED,
            note = "The attribute-based routing query was unavailable, so this is derived from the " +
                "deprecated AudioManager route flags [" +
                (if (flags.isEmpty()) "none set" else flags.joinToString(",")) +
                "]. Those flags describe telephony-era routing, not the media route, so treat this " +
                "as a hint. AudioTestScreen reports AudioTrack.getRoutedDevice() during playback, " +
                "which is direct evidence.",
            category = LabCategory.AUDIO,
        )
        out += Observation(
            key = "audio.active_output_method",
            value = "deprecated AudioManager route flags",
            status = LabStatus.INFERRED,
            note = "Fallback path.",
            category = LabCategory.AUDIO,
        )
        return out
    }

    // --------------------------------------------------------------------------- manager state

    private fun managerState(am: AudioManager): List<Observation> {
        val out = mutableListOf<Observation>()

        out += runCatching {
            Observation.confirmed(
                "audio.mode",
                describeAudioMode(am.mode),
                LabCategory.AUDIO,
                "AudioManager.getMode(). MODE_IN_COMMUNICATION would divert media routing.",
            )
        }.getOrElse { Observation.error("audio.mode", it, LabCategory.AUDIO) }

        out += runCatching {
            val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val min = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                am.getStreamMinVolume(AudioManager.STREAM_MUSIC)
            } else {
                0
            }
            Observation.confirmed(
                "audio.stream_music_volume",
                "$cur/$max (min $min)",
                LabCategory.AUDIO,
                "A zero here explains an inaudible test tone without any routing fault.",
            )
        }.getOrElse { Observation.error("audio.stream_music_volume", it, LabCategory.AUDIO) }

        out += runCatching {
            Observation.confirmed("audio.is_music_active", am.isMusicActive, LabCategory.AUDIO)
        }.getOrElse { Observation.error("audio.is_music_active", it, LabCategory.AUDIO) }

        out += runCatching {
            Observation.confirmed("audio.is_volume_fixed", am.isVolumeFixed, LabCategory.AUDIO,
                "True when the phone hands volume control to the sink, which is typical for a dock.")
        }.getOrElse { Observation.error("audio.is_volume_fixed", it, LabCategory.AUDIO) }

        out += runCatching {
            Observation.confirmed(
                "audio.bluetooth_sco_off_call",
                am.isBluetoothScoAvailableOffCall,
                LabCategory.AUDIO,
            )
        }.getOrElse { Observation.error("audio.bluetooth_sco_off_call", it, LabCategory.AUDIO) }

        out += runCatching {
            val id = am.generateAudioSessionId()
            if (id <= 0) {
                Observation.unsupported(
                    "audio.session_id_generation",
                    LabCategory.AUDIO,
                    "generateAudioSessionId() returned " + id + " (ERROR).",
                )
            } else {
                Observation.confirmed(
                    "audio.session_id_generation",
                    id,
                    LabCategory.AUDIO,
                    "A session id can be allocated, so effect/capture wiring by session is possible.",
                )
            }
        }.getOrElse { Observation.error("audio.session_id_generation", it, LabCategory.AUDIO) }

        out += runCatching {
            Observation.confirmed(
                "audio.output_sample_rate",
                am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE) ?: "null",
                LabCategory.AUDIO,
                "Native output rate. The lab tone generator runs at 48000 Hz regardless.",
            )
        }.getOrElse { Observation.error("audio.output_sample_rate", it, LabCategory.AUDIO) }

        out += runCatching {
            Observation.confirmed(
                "audio.output_frames_per_buffer",
                am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER) ?: "null",
                LabCategory.AUDIO,
                "Native burst size; a lower bound on the phone's own output latency.",
            )
        }.getOrElse { Observation.error("audio.output_frames_per_buffer", it, LabCategory.AUDIO) }

        out += runCatching {
            val min = AudioTrack.getMinBufferSize(
                SR,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (min <= 0) {
                Observation.unsupported(
                    "audio.min_buffer_48k_stereo",
                    LabCategory.AUDIO,
                    "getMinBufferSize returned $min for 48 kHz stereo PCM16.",
                )
            } else {
                Observation.confirmed(
                    "audio.min_buffer_48k_stereo",
                    "$min bytes (" + (min / 4) + " frames, " +
                        String.format(java.util.Locale.US, "%.1f", (min / 4.0) / SR * 1000.0) + " ms)",
                    LabCategory.AUDIO,
                    "Floor for the tone generator's own buffering.",
                )
            }
        }.getOrElse { Observation.error("audio.min_buffer_48k_stereo", it, LabCategory.AUDIO) }

        out += runCatching {
            Observation.confirmed(
                "audio.active_playback_configs",
                am.activePlaybackConfigurations.size,
                LabCategory.AUDIO,
                am.activePlaybackConfigurations.joinToString(";") { c ->
                    "usage=" + c.audioAttributes.usage + " content=" + c.audioAttributes.contentType
                }.ifBlank { "No app is playing audio right now." },
            )
        }.getOrElse { Observation.error("audio.active_playback_configs", it, LabCategory.AUDIO) }

        // getCommunicationDevice is the supported successor of the deprecated route flags (API 31+).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            out += runCatching {
                val dev = am.communicationDevice
                if (dev == null) {
                    Observation.unsupported(
                        "audio.communication_device",
                        LabCategory.AUDIO,
                        "getCommunicationDevice() returned null: no explicit communication route is set.",
                    )
                } else {
                    Observation.confirmed(
                        "audio.communication_device",
                        describeAudioDevice(dev),
                        LabCategory.AUDIO,
                        "Communication (voice) route, not necessarily the media route.",
                    )
                }
            }.getOrElse { Observation.error("audio.communication_device", it, LabCategory.AUDIO) }
        } else {
            out += Observation.notTested(
                "audio.communication_device",
                LabCategory.AUDIO,
                "AudioManager.getCommunicationDevice() needs API 31; this device is API " +
                    Build.VERSION.SDK_INT + ".",
            )
        }

        return out
    }

    // -------------------------------------------------------------------------- capture policy

    /**
     * Whether this app's own playback can be captured, and whether we may capture at all.
     *
     * ALLOW_CAPTURE_BY_ALL on our own AudioAttributes is a platform contract from API 29 and the
     * tone engine sets it, so it is CONFIRMED. Capturing *other* apps' playback is a separate
     * question that this probe does not exercise, and a denied RECORD_AUDIO makes every capture
     * finding NOT_TESTED - never UNSUPPORTED.
     */
    private fun capturePolicy(context: Context, am: AudioManager): List<Observation> {
        val out = mutableListOf<Observation>()

        out += runCatching {
            val p = am.allowedCapturePolicy
            val name = when (p) {
                AudioAttributes.ALLOW_CAPTURE_BY_ALL -> "ALLOW_CAPTURE_BY_ALL"
                AudioAttributes.ALLOW_CAPTURE_BY_SYSTEM -> "ALLOW_CAPTURE_BY_SYSTEM"
                AudioAttributes.ALLOW_CAPTURE_BY_NONE -> "ALLOW_CAPTURE_BY_NONE"
                else -> "policy_$p"
            }
            Observation.confirmed("audio.capture_policy_current", name, LabCategory.AUDIO,
                "Process-wide default capture policy reported by AudioManager.")
        }.getOrElse { Observation.error("audio.capture_policy_current", it, LabCategory.AUDIO) }

        out += Observation.confirmed(
            "audio.capture_policy_allow_all_achievable",
            true,
            LabCategory.AUDIO,
            "AudioAttributes.Builder.setAllowedCapturePolicy(ALLOW_CAPTURE_BY_ALL) exists from " +
                "API 29 and the lab tone generator sets it on every AudioTrack it opens, so this " +
                "app's own output is capturable by an AudioPlaybackCapture session.",
        )

        val recGranted = runCatching {
            LabPermissions.isGranted(context, LabPermissions.RECORD_AUDIO)
        }.getOrDefault(false)
        out += Observation.confirmed(
            "audio.record_permission",
            if (recGranted) "granted" else "denied",
            LabCategory.AUDIO,
            "RECORD_AUDIO gates every playback-capture experiment.",
        )

        out += Observation.notTested(
            "audio.playback_capture",
            LabCategory.AUDIO,
            if (recGranted) {
                "RECORD_AUDIO is granted, but this probe deliberately does not open an " +
                    "AudioPlaybackCapture session: doing so would change the routing it is measuring."
            } else {
                "RECORD_AUDIO is not granted, so no capture was attempted. This says nothing about " +
                    "whether playback capture works on this device."
            },
        )

        return out
    }

    // ------------------------------------------------------------------------ platform features

    private fun platformAudioFeatures(context: Context): List<Observation> {
        val pm = context.packageManager
        fun feature(key: String, feature: String, note: String) = runCatching {
            Observation.confirmed(key, pm.hasSystemFeature(feature), LabCategory.AUDIO, note)
        }.getOrElse { Observation.error(key, it, LabCategory.AUDIO) }

        return listOf(
            feature(
                "audio.feature_output",
                PackageManager.FEATURE_AUDIO_OUTPUT,
                "FEATURE_AUDIO_OUTPUT.",
            ),
            feature(
                "audio.feature_low_latency",
                PackageManager.FEATURE_AUDIO_LOW_LATENCY,
                "FEATURE_AUDIO_LOW_LATENCY: round-trip under 45 ms on the built-in path. Says " +
                    "nothing about a wireless route to the vehicle.",
            ),
            feature(
                "audio.feature_pro",
                PackageManager.FEATURE_AUDIO_PRO,
                "FEATURE_AUDIO_PRO: stricter low-latency and USB audio guarantees.",
            ),
            feature(
                "audio.feature_microphone",
                PackageManager.FEATURE_MICROPHONE,
                "Needed for any capture-based A/V measurement done on the phone itself.",
            ),
        )
    }
}
