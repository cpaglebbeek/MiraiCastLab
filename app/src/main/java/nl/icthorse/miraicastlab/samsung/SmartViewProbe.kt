package nl.icthorse.miraicastlab.samsung

import android.content.Context
import android.content.pm.PackageManager
import nl.icthorse.miraicastlab.core.DashboardKeys
import nl.icthorse.miraicastlab.core.LabCategory
import nl.icthorse.miraicastlab.core.LabStatus
import nl.icthorse.miraicastlab.core.Observation
import nl.icthorse.miraicastlab.core.Probe

/**
 * What an ordinary, non-rooted app can learn about Samsung Smart View / Wi-Fi Display.
 *
 * The honest framing (spec sections 2.2 and 20): **Android exposes no API with which a third-party
 * app can start, join, inspect or negotiate a Miracast session.** This probe therefore does three
 * things and claims nothing beyond them:
 *
 *  1. asks the package manager which mirroring-related system packages it is *allowed* to see, and
 *     records the visibility filtering that makes a "no" ambiguous;
 *  2. asks which cast/wireless-display settings panels resolve, so the wizard can hand the tester
 *     off to the right system UI - the only legitimate way to get a session started;
 *  3. reads the three side-effects of an active session that *are* visible to any app (a
 *     presentation-category display, a MediaRouter route with a presentation display, an up p2p
 *     interface) and combines them into an INFERRED verdict.
 *
 * Every Wi-Fi Display protocol fact the research questions ask for - negotiated resolution and
 * framerate, the codec the sink picked, HDCP state, audio transport, UIBC - is unreadable from
 * here. Those are emitted as explicit NOT_TESTED observations with the reason attached. Naming them
 * is the deliverable; guessing them would be the failure.
 */
object SmartViewProbe : Probe {

    override val id = "smartview"
    override val title = "Samsung Smart View surface"

    private const val CAT_KEY_PREFIX = "smartview"

    override suspend fun observe(context: Context): List<Observation> {
        val out = mutableListOf<Observation>()
        val cat = LabCategory.SMART_VIEW

        // ---- 1. package visibility -------------------------------------------------------
        val visible = visiblePackageCount(context)
        val ambiguous = packageAbsenceIsAmbiguous(visible)
        out += Observation.confirmed(
            "smartview.package_query_scope",
            (visible?.toString() ?: "unreadable") + " packages visible to this app",
            cat,
            if (ambiguous) {
                "Android package-visibility filtering appears to be active: this app declares no " +
                    "<queries> element and no QUERY_ALL_PACKAGES, so a package it cannot see may " +
                    "still be installed. Every 'not visible' answer below is graded NOT_TESTED."
            } else {
                "The full package list is readable, so a 'not found' answer below is real evidence " +
                    "of absence."
            },
        )

        WATCHED_PACKAGES.forEach { (pkg, label) ->
            out += packageObservation(readPackage(context, pkg, label), CAT_KEY_PREFIX + ".pkg", cat, ambiguous)
        }

        val features = systemFeaturesMatching(
            context,
            listOf("wfd", "wifi.display", "wifidisplay", "mirror", "cast", "screen_sharing"),
        )
        out += if (features.isEmpty()) {
            Observation.unsupported(
                "smartview.system_features",
                cat,
                "getSystemAvailableFeatures declares no wireless-display or mirroring feature. " +
                    "Feature queries are not visibility-filtered, so this answer is trustworthy.",
            )
        } else {
            Observation.confirmed(
                "smartview.system_features", features.joinToString(", "), cat,
                "Declared platform features naming a display/mirroring capability.",
            )
        }

        out += try {
            Observation.confirmed(
                "smartview.feature_wifi_direct",
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT),
                cat,
                "Wi-Fi Direct is the transport Miracast runs over; its absence would rule Miracast out.",
            )
        } catch (t: Throwable) {
            Observation.error("smartview.feature_wifi_direct", t, cat)
        }

        // ---- 2. settings panels the tester can be sent to --------------------------------
        val intents = castIntents(context)
        val control = intents.firstOrNull { it.key == ACTION_CONTROL_KEY }
        val settingsQueryable = control?.resolvable == true
        out += Observation.confirmed(
            "smartview.intent_query_control",
            if (settingsQueryable) "Settings root resolves (" + control?.matches?.size + " match)" else "Settings root does NOT resolve",
            cat,
            "Control measurement. queryIntentActivities is itself visibility-filtered; if even " +
                "ACTION_SETTINGS does not resolve, no negative intent result below means anything.",
        )

        // A negative is only real evidence when both controls say we were allowed to look.
        val negativesAreEvidence = settingsQueryable && !ambiguous

        intents.filter { it.key != ACTION_CONTROL_KEY }.forEach { f ->
            val key = "smartview.intent." + f.key
            out += when {
                f.failure != null -> Observation.error(
                    key, IllegalStateException(f.failure), cat,
                )

                f.resolvable -> Observation.confirmed(
                    key,
                    f.answer() + (if (f.startable) " [startable]" else " [resolves but not default-startable]"),
                    cat,
                    f.label + " - intent " + f.describe + ". This is a legitimate hand-off point " +
                        "for the tester; the app still cannot start the session itself.",
                )

                negativesAreEvidence -> Observation.unsupported(
                    key, cat,
                    f.label + " - no activity on this device claims " + f.describe + ", and both " +
                        "visibility controls confirm we were allowed to look.",
                )

                else -> Observation.notTested(
                    key, cat,
                    f.label + " - " + f.describe + " did not resolve, but package/intent visibility " +
                        "filtering is active, so absence is not established. Pressing the button in " +
                        "the wizard will settle it: startActivity is not visibility-filtered.",
                )
            }
        }

        // ---- 3. live session signals -----------------------------------------------------
        val signals = readMirroringSignals(context)

        out += Observation.confirmed(
            "smartview.presentation_display_count",
            signals.presentationDisplays.size,
            cat,
            if (signals.presentationDisplays.isEmpty()) {
                "DisplayManager reports no DISPLAY_CATEGORY_PRESENTATION display right now."
            } else {
                signals.presentationDisplays.joinToString(" | ") { it.oneLine() }
            },
        )

        out += Observation.confirmed(
            "smartview.mediaroute_live_video",
            signals.route.describe(),
            cat,
            "The selected LIVE_VIDEO route. A non-null presentation display here is the framework's " +
                "own statement that video is going somewhere off-device.",
        )

        out += if (signals.p2pFailure != null) {
            Observation.error(
                "smartview.p2p_interface",
                IllegalStateException(signals.p2pFailure),
                cat,
            )
        } else if (signals.p2pInterfaces.isEmpty()) {
            Observation.confirmed(
                "smartview.p2p_interface", "none up", cat,
                "No p2p* network interface is up. Miracast normally brings one up; its absence is " +
                    "evidence against an active session, not proof of one way or the other.",
            )
        } else {
            Observation.observed(
                "smartview.p2p_interface", signals.p2pInterfaces.joinToString("/"), cat,
                "A Wi-Fi Direct interface is up. This is consistent with Miracast but also with " +
                    "Wi-Fi Direct file transfer, Quick Share or a printer.",
            )
        }

        // Dashboard key: never CONFIRMED. An app cannot prove the transport is Miracast.
        val evidenceNote = if (signals.positives.isEmpty()) {
            "No contributing signal is present (no presentation display, no route presentation " +
                "display, no p2p interface up)."
        } else {
            "Contributing evidence: " + signals.positives.joinToString("; ") + ". " +
                "This does not identify the transport: an HDMI dongle, DeX, Chromecast or a " +
                "virtual display would produce the same signals."
        }
        out += Observation(
            key = DashboardKeys.SMARTVIEW_ACTIVE,
            value = if (signals.signalCount > 0) {
                "session likely active (" + signals.signalCount + "/3 signals)"
            } else {
                "no session evidence (0/3 signals)"
            },
            status = LabStatus.INFERRED,
            note = evidenceNote,
            category = cat,
        )

        // Purely descriptive: what we saw, with no claim attached, so OBSERVED is honest here.
        out += Observation.observed(
            DashboardKeys.MIRACAST_OBSERVATION,
            signals.evidenceSummary(),
            LabCategory.MIRACAST,
            "Raw signal readout at probe time. None of these fields names the transport protocol.",
        )

        // ---- 4. the protocol facts nobody outside the framework can read ------------------
        out += unreadableProtocolFacts()

        return out
    }

    /**
     * The Wi-Fi Display / Smart View questions from spec 2.1 that are structurally unreachable
     * from an unprivileged app, each with the reason.
     *
     * These are emitted every run on purpose. A report that silently omits them reads as if the
     * questions were answered; a report that lists them as NOT_TESTED with a cause tells the reader
     * precisely which instrument would be needed (a sniffer on the RTSP/M-messages, a bench sink
     * with logging, or a privileged build).
     */
    private fun unreadableProtocolFacts(): List<Observation> {
        val m = LabCategory.MIRACAST
        val why = "No public Android API exposes Wi-Fi Display session state to a third-party app. " +
            "The RTSP capability negotiation happens inside system_server and the Wi-Fi HAL."
        return listOf(
            Observation.notTested(
                "miracast.negotiated_resolution", m,
                why + " Answering this needs an RTSP M3/M4 capture on the sink or a bench receiver " +
                    "that logs the negotiated CEA/VESA table.",
            ),
            Observation.notTested(
                "miracast.negotiated_framerate", m,
                why + " The frame rate lives in the same negotiated resolution table and is not " +
                    "surfaced anywhere an app can read. Display.getRefreshRate() on a mirrored " +
                    "presentation display reports the *logical* display rate, not the link rate, so " +
                    "it must not be reported as the negotiated framerate.",
            ),
            Observation.notTested(
                "miracast.video_codec", m,
                why + " The sink advertises its codec profile/level in M3; the encoder the phone " +
                    "then uses is chosen by the framework, not by this app. CodecProbe reports what " +
                    "the phone *could* encode, which is a different question.",
            ),
            Observation.notTested(
                "miracast.orientation", m,
                why + " Source orientation handling in a Smart View session is decided by the " +
                    "Samsung mirroring service.",
            ),
            Observation.notTested(
                "miracast.hdcp_state", m,
                why + " HDCP negotiation is an HDCP 2.x handshake below the app layer. An app can " +
                    "observe a *consequence* (a SurfaceView going black in a secure display path) " +
                    "but never the state itself.",
            ),
            Observation.notTested(
                "miracast.audio_transport", m,
                why + " Whether audio rides the Miracast MPEG-2 TS mux, or a separate Bluetooth " +
                    "A2DP link, can only be inferred by muting one path at a time on the head unit. " +
                    "AudioRouteProbe records which output device Android believes is active, which " +
                    "is the closest legitimate proxy.",
            ),
            Observation.notTested(
                "miracast.uibc_advertised", m,
                why + " UIBC advertisement is an M3 capability field. Do not infer UIBC from the " +
                    "presence of input events: an ordinary Bluetooth or USB peripheral produces " +
                    "identical InputDevice records. The input module's touch-back test is the only " +
                    "evidence that counts, and only when no other input path is attached.",
            ),
            Observation.notTested(
                "smartview.proprietary_extensions", LabCategory.SMART_VIEW,
                "Samsung publishes no SDK for Smart View extensions. Anything an app could reach " +
                    "would be an undocumented private interface, which this build deliberately does " +
                    "not call. Detecting the packages above is as far as a legitimate probe goes.",
            ),
            Observation.notTested(
                "miracast.session_control", LabCategory.SMART_VIEW,
                "Starting, stopping or configuring a Miracast session from a third-party app is not " +
                    "possible on stock Android. The wizard therefore instruments a tester-driven " +
                    "session instead of claiming to establish one.",
            ),
        )
    }
}
