# Protocol and capability matrix

**Status of this document at v0.1.0: no row below has been verified on a Samsung Galaxy Z Fold, and
no row has been verified against a Toyota Mirai 2025.** Every vehicle-side answer is `NOT TESTED`.
This file is the place those answers get filled in, one session at a time — not a summary of results
we already have.

Grading, per spec section 20:

| | meaning |
|---|---|
| **CONFIRMED** | direct evidence, or an authoritative platform contract *plus* a matching implementation |
| **OBSERVED** | actually seen during a test |
| **INFERRED** | evidence suggests it; not directly confirmed |
| **UNSUPPORTED** | a test or API produced evidence it is absent here |
| **NOT TESTED** | hardware or circumstances were unavailable — **no claim is made** |

---

## 1. What a non-rooted third-party Android app can and cannot observe

These rows are about the **Android platform**, not about the Mirai. They are graded from the
documented API surface plus the implementation in this repository. They are what makes the rest of
the document honest: several questions in the specification are *structurally* unanswerable from an
app, and saying so precisely is a result.

| # | Capability | Status | Basis |
|---|---|---|---|
| 1.1 | Enumerate all logical displays, their modes, refresh rates and flags | CONFIRMED | `DisplayManager.getDisplays`, `Display.getSupportedModes` — public API, implemented in `DisplayProbe` |
| 1.2 | Detect that a *secondary* display appeared or disappeared, with a timestamp | CONFIRMED | `DisplayManager.DisplayListener` — implemented in `DisplayTestScreen` |
| 1.3 | Detect that the secondary display is specifically a **Miracast** sink | UNSUPPORTED | Android surfaces a Wi-Fi Display sink as an ordinary secondary display. No public API names the transport. The app therefore grades "this is the Toyota" as INFERRED at best. |
| 1.4 | Enumerate `MediaRouter` live-video/live-audio routes and their presentation displays | CONFIRMED | `android.media.MediaRouter` — public API, implemented in `MediaRouteProbe` |
| 1.5 | Establish a Miracast / Wi-Fi Display session from app code | UNSUPPORTED | No public API. `WIFI_DISPLAY_SETTINGS` and the Samsung picker are *settings panels*, not a connect API. The app guides the tester instead (spec 2.2). |
| 1.6 | Read the **negotiated resolution** of a Wi-Fi Display session | NOT TESTED → structurally unavailable | Lives in the RTSP/WFD negotiation inside wpa_supplicant and the display HAL. Not exposed. The app can only report what Android drew to. |
| 1.7 | Read the **negotiated framerate** | NOT TESTED → structurally unavailable | same |
| 1.8 | Read the **video codec** the sink accepted | NOT TESTED → structurally unavailable | same. `MediaCodecList` describes the *phone's* codecs and says nothing about the sink. |
| 1.9 | Read **HDCP / content-protection** state of the link | NOT TESTED → structurally unavailable | same. `Display.FLAG_SECURE` / `FLAG_SUPPORTS_PROTECTED_BUFFERS` describe the display's *policy*, not the link's HDCP handshake. |
| 1.10 | Read whether the sink advertised **UIBC** | NOT TESTED → structurally unavailable | UIBC is advertised in the WFD capability IE during RTSP setup. Not exposed to apps. **The only app-level test is empirical: did any input arrive?** That is what `InputTestScreen` does. |
| 1.11 | Enumerate Wi-Fi Direct peers with device type | CONFIRMED (permission-gated) | `WifiP2pManager.requestPeers` + `NEARBY_WIFI_DEVICES` (API 33+) or `ACCESS_FINE_LOCATION` (≤32) |
| 1.12 | Read the phone's own P2P MAC address | UNSUPPORTED | Android returns `02:00:00:00:00:00` to third-party apps |
| 1.13 | See a `p2p*` network interface appear when a P2P group forms | CONFIRMED | `NetworkInterface.getNetworkInterfaces()` — the strongest app-visible signal that Wi-Fi Direct is live. Still only **INFERRED** evidence that the transport is Miracast. |
| 1.14 | Observe audio route changes, including `TYPE_REMOTE_SUBMIX` | CONFIRMED | `AudioManager.getDevices` + `AudioDeviceCallback`. `REMOTE_SUBMIX` appearing is INFERRED Miracast-audio evidence. |
| 1.15 | Capture the phone's own screen and encode H.264 without root | CONFIRMED | `MediaProjection` + `VirtualDisplay` + `MediaCodec`. Says nothing about what the sink receives. |
| 1.16 | Place an activity on a secondary display | CONFIRMED | `ActivityOptions.setLaunchDisplayId` — this is how the diagnostic scene reaches the car screen |
| 1.17 | Log every arriving input event with source, device and coordinates | CONFIRMED | `MotionEvent` / `KeyEvent` / `InputDevice` — public API |
| 1.18 | Detect Android Auto projection state | PARTIAL — CONFIRMED for car mode | `UiModeManager.getCurrentModeType() == UI_MODE_TYPE_CAR` is authoritative. Everything else (USB accessory presence, 5 GHz link) is INFERRED. |
| 1.19 | Read the One UI version | INFERRED | `ro.build.version.oneui` via guarded reflection on `SystemProperties`; absent on non-Samsung, which is a legitimate UNSUPPORTED |
| 1.20 | Detect Samsung DeX mode | INFERRED | `Configuration.semDesktopModeEnabled` via reflection — proprietary, guarded, degrades to UNSUPPORTED |

**The single most important row is 1.10.** The specification asks whether the Mirai advertises UIBC.
No app can read that advertisement. The project therefore answers a different, answerable question:
*did touching the Mirai screen produce an input event on the phone?* A negative answer is recorded as
**NO INPUT OBSERVED for this session**, never as "the Mirai does not support UIBC".

---

## 2. Samsung Galaxy Z Fold — device-side

| # | Question | Status | Notes |
|---|---|---|---|
| 2.1 | Model, One UI version, build fingerprint | NOT TESTED | fills in on first run of `DeviceProbe` |
| 2.2 | Root present | NOT TESTED | `DeviceProbe` reports YES/NO/UNKNOWN; a negative is graded INFERRED |
| 2.3 | Wi-Fi Direct feature present | NOT TESTED | `FEATURE_WIFI_DIRECT` |
| 2.4 | H.264 encoder count and maximum size | NOT TESTED | `CodecProbe` |
| 2.5 | MediaProjection capture FPS at scaled resolution | NOT TESTED | `MediaProjectionScreen` |
| 2.6 | Diagnostic scene holds 60 fps | NOT TESTED | `TestPatternScreen` reports mean and p99 frame time |
| 2.7 | Smart View packages present and settings intents resolvable | NOT TESTED | `SmartViewProbe` |
| 2.8 | DeX reflection resolves | NOT TESTED | `DexProbe` |

---

## 3. Toyota Mirai 2025 — vehicle-side

**Every row here is `NOT TESTED`.** They are the reason the project exists.

### 3.1 Miracast / Wi-Fi Display (spec 2.1)

| # | Question | Status |
|---|---|---|
| 3.1.1 | Does the Z Fold discover the Mirai as a Wi-Fi Display sink? | NOT TESTED |
| 3.1.2 | Which discovery mechanism is visible to the app? | NOT TESTED |
| 3.1.3 | Is Wi-Fi Direct / P2P involved (does `p2p0` appear)? | NOT TESTED |
| 3.1.4 | Can the app detect the active session (presentation display + route + p2p)? | NOT TESTED |
| 3.1.5 | Which resolution does Android draw to on that display? | NOT TESTED |
| 3.1.6 | Which orientation? | NOT TESTED |
| 3.1.7 | Which framerate is actually delivered? (photograph the 60-position sweep) | NOT TESTED |
| 3.1.8 | Is the video H.264? | NOT TESTED — structurally unavailable from the phone (see 1.8) |
| 3.1.9 | Is HDCP requested or active? | NOT TESTED — structurally unavailable (see 1.9) |
| 3.1.10 | Is audio transported? | NOT TESTED |
| 3.1.11 | Audio characteristics (channels, dropouts, sync) | NOT TESTED |
| 3.1.12 | End-to-end A/V latency | NOT TESTED — needs an external recording; the app logs only its own timestamps |
| 3.1.13 | Does the receiver advertise UIBC? | NOT TESTED — structurally unavailable (see 1.10) |
| 3.1.14 | **Does touching the Mirai screen produce input on the Z Fold?** | NOT TESTED — this is the answerable form of 3.1.13 |
| 3.1.15 | Which event types arrive, if any? | NOT TESTED |
| 3.1.16 | Any Samsung proprietary Smart View extension in play? | NOT TESTED |

### 3.2 Samsung DeX (spec 2.3)

| # | Question | Status |
|---|---|---|
| 3.2.1 | Is Wireless DeX offered to the Mirai at all? | NOT TESTED |
| 3.2.2 | Is the Mirai visible only as Miracast, or also as a DeX target? | NOT TESTED |
| 3.2.3 | Does a distinct external logical display appear? | NOT TESTED |
| 3.2.4 | Do display metrics differ between Smart View and DeX? | NOT TESTED |
| 3.2.5 | Can the phone act as a touchpad? | NOT TESTED |
| 3.2.6 | Can mouse/keyboard events reach DeX? | NOT TESTED |
| 3.2.7 | Can the Mirai touchscreen become a pointer source? | NOT TESTED |
| 3.2.8 | Can the external display host ordinary activities? | NOT TESTED |

### 3.3 Android Auto coexistence (spec 3 and 9)

| Row | Android Auto | Miracast | Result |
|---|---|---|---|
| A | off | off | NOT TESTED |
| B | off | on | NOT TESTED |
| C | USB on | off | NOT TESTED |
| D | USB on | on | NOT TESTED |
| E | wireless on | off | NOT TESTED |
| F | wireless on | on | NOT TESTED |

Candidate outcomes, listed so the recorded result can be matched against them — **not** predictions:
both coexist · Miracast terminates AA · AA terminates Miracast · radio contention prevents
coexistence · USB AA + Wi-Fi Miracast coexist · wireless AA + Miracast cannot coexist · the result
depends on connection order.

### 3.4 Motion state (spec 12)

| # | Question | Status |
|---|---|---|
| 3.4.1 | Does the head unit keep showing projected moving imagery under a drive/motion state? | NOT TESTED |
| 3.4.2 | If not: hidden, overlaid with a warning, audio-only, or projection terminated? | NOT TESTED |
| 3.4.3 | How long between the state change and the video change? | NOT TESTED |

Method constraint, from `SAFETY.md`: bench, simulator, or stationary vehicle only, and **no
interlock bypass is built regardless of the answer.**

---

## 4. How to update this document

After a session: open the exported Markdown report, and for each row above replace `NOT TESTED` with
the status the report gives it, plus the `testRunId` and the date. Do not summarise from memory, and
never upgrade a `NOT TESTED` to `UNSUPPORTED` because a test did not happen.
