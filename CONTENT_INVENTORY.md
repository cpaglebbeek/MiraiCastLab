# CONTENT_INVENTORY

Per screen: what it is for, who reads it, what it must say, and what the tester is supposed to *do*
next. Fourteen destinations, all declared in `ui/LabNavHost.kt`.

The audience is one person: a technically capable tester, seated in a stationary Toyota Mirai, who
should not have to remember an ADB command. Every "key message" below is written to survive being
read at arm's length in daylight.

---

## 1. Dashboard — `dashboard` · `ui/DashboardScreen.kt`

**Purpose** the whole device state on one page, then one tap to any experiment.
**Key messages** what phone this is · what Android and One UI · is it rooted · what the network and
Wi-Fi Direct are doing · how many displays and is one external · what audio outputs exist · is there
Miracast or Android Auto evidence · how many H.264 encoders.
**Every fact carries a `StatusChip`.** A dashboard without grades would be exactly the false
confidence this app exists to prevent.
**CTA** eleven `BigActionButton`s, one per experiment, plus Samsung DeX and Live log.
**Failure state** if a probe produced nothing, `DashboardKeys.missingFrom()` reports the gap in red.
A blank field is an alarm, not an absence of news.

## 2. Device scan — `device_scan` · `scan/DeviceScanScreen.kt`

**Purpose** the full capability inventory, grouped and filterable.
**Key messages** counts per evidence grade; every observation grouped by category; total scan
duration; the dashboard coverage gap.
**CTA** RUN FULL SCAN. Then tap a status chip to filter — usually `UNSUPPORTED` or `NOT_TESTED`.
**Note** on a Samsung flagship this produces several hundred codec rows. That is deliberate: the
evidence file is the product.

## 3. Miracast wizard — `miracast` · `samsung/MiracastWizardScreen.kt`

**Purpose** the seven-step guided session. The single most important screen in the app.
**Key messages** step 1 baseline · step 2 *"Start Smart View and connect to the Toyota Mirai"* ·
step 3 the live diff · step 4 scene onto the car screen · step 5 record what you saw · step 6 input ·
step 7 export.
**CTA** one action per step, in order.
**What it must never say** that the app connected. It cannot. It opens the right settings panel and
measures what changed.

## 4. Display test — `display` · `display/DisplayTestScreen.kt`

**Purpose** live display topology — the instrument that timestamps "Smart View just started".
**Key messages** every display with modes, refresh rate, flags, HDR and presentation-category
membership; a rolling timestamped event list; a verdict banner on external-display presence.
**CTA** SEND TEST SCENE TO EXTERNAL DISPLAY · MARK: SMART VIEW STARTED / STOPPED.
**Honesty note** a Wi-Fi Display sink looks like an ordinary secondary display. "This is the Toyota"
is `INFERRED` at best, and the screen says so.

## 5. Network / Wi-Fi Direct — `network` · `net/NetworkTestScreen.kt`

**Purpose** watch `p2p0` appear the moment Smart View connects.
**Key messages** peers with device type · transports · a 2-second-refresh interface table · and an
explicit card listing what an app *cannot* see, each with its reason.
**CTA** grant nearby-devices (with the reason shown first) · START / STOP DISCOVERY.
**Permission-denied state** every peer finding becomes `NOT_TESTED` with the missing permission
named. Never "no peers found".

## 6. Audio test — `audio` · `audio/AudioTestScreen.kt`

**Purpose** does sound cross the link, in which channel, and how far behind the picture.
**Key messages** the six modes; a huge label naming the channel that should be audible *right now*;
the current active output; a timestamped route-change list.
**CTA** SILENCE · MONO TONE · STEREO L/R · CONTINUOUS · LATENCY CLICK · A/V SYNC PULSE.
**Honesty note** the app logs its own render and write timestamps. True end-to-end latency needs an
external recording, and the screen says so rather than presenting the internal number as the answer.

## 7. Input / touch-back — `input` · `input/InputTestScreen.kt`

**Purpose** the project's sharpest question: does touching the Mirai screen reach the phone?
**Key messages** (A) every arriving event with source, device, coordinates, pressure, buttons and
timing; (B) a 3×5 numbered grid with hit percentage, distinct sources, and a derived coordinate
transform; (C) an HID baseline so the tester can prove the monitor works before blaming the car.
**CTA** calibrate with a mouse first · then touch the numbered targets **on the car screen**.
**The result that matters** `NO INPUT OBSERVED` for this session. Never "the Mirai has no UIBC".

## 8. MediaProjection — `projection` · `projection/MediaProjectionScreen.kt`

**Purpose** what the phone itself can capture and encode, without root.
**Key messages** capture FPS, captured resolution and strides, dropped frames, time to first frame;
encoder name, hardware acceleration, configured vs actual bitrate, encoded frames, encode FPS.
**CTA** REQUEST SCREEN CAPTURE PERMISSION (explained first: what is captured, and that nothing
leaves the device) · ENCODE 5 s H.264 TEST CLIP · STOP.
**Honesty note** an explicit observation states that this proves what the *phone* can capture and
says nothing about what the sink receives.

## 9. Test pattern — `scene` · `scene/TestPatternScreen.kt`

**Purpose** the stimulus. What the tester actually looks at on the car screen.
**Key messages** bouncing object with trail · frame counter · monotonic timestamp · 60-position
sweep · 1-pixel scrolling bars · RGB and greyscale reference blocks · audio-pulse indicator · real
pixel resolution · live FPS and p99 frame time · orientation · touch crosshair · TEST LAB watermark
and run id.
**CTA** play/pause · speed · next pattern · grid · audio pulse · latency sequence · reset stats ·
input capture · manual marker. **Every action is timestamped in the log.**
**Why it looks like that** photograph the sweep and you have the delivered frame rate from one
picture; photograph both screens in one frame and you have the latency.

## 10. Android Auto coexistence — `android_auto` · `auto/AndroidAutoScreen.kt`

**Purpose** the A–F matrix: can Android Auto and Miracast coexist, and which one wins.
**Key messages** a live environment strip; per row the auto-captured state plus the tester's own
observations; time-until-disconnect.
**CTA** start a row, then record success, order, failure reason, whether the animation stayed
visible, touch behaviour, and the disconnect.
**Unrun rows render as `NOT_TESTED`.** Never blank, never a guess. The matrix persists to disk so it
survives an app restart mid-session.

## 11. Motion state — `motion` · `auto/MotionStateScreen.kt`

**Purpose** record what the head unit does with projected video. **Observation only.**
**Key messages** a non-dismissible safety card at the top, in plain language: this screen records,
it does not change vehicle state, and the test belongs on a bench or a stationary vehicle. Then a
per-second liveness timeline and the elapsed time between marker pairs.
**CTA** PARK · DRIVE STATE SIMULATED · MOTION STATE SIMULATED · VIDEO VISIBLE · VIDEO BLOCKED ·
AUDIO ONLY · CONNECTION LOST · free text.
**What it will never have** anything that transmits to a vehicle. See `SAFETY.md`.

## 12. Samsung DeX — `dex` · `samsung/DexScreen.kt`

**Purpose** is the Mirai offered to DeX at all, and can DeX be told apart from plain mirroring.
**Key messages** DeX detection via guarded reflection; live display-density watching to catch the
transition; the spec 2.3 questions each with their current status.
**CTA** ENTER DEX NOW, then watch the density.
**Expected state today** mostly `NOT_TESTED` — that list *is* the deliverable until there is a car.

## 13. Report — `report` · `report/ReportScreen.kt`

**Purpose** turn a session into something you can hand to someone else.
**Key messages** the vehicle form (make, model, year, head-unit software, connection method,
tester); a live Markdown preview; counts per grade; the absolute file path in monospace so it can be
found with `adb pull`.
**CTA** EXPORT MARKDOWN · JSON · CSV · SHARE · NEW TEST RUN (confirmed — it clears the buffer).
**The rule the generator enforces** sections 15–18 are derived mechanically from the status field.
No prose step can turn a `NOT_TESTED` into an `UNSUPPORTED`.

## 14. Live log — `log` · `report/LogViewerScreen.kt`

**Purpose** watch the evidence arrive, and find one record among thousands.
**Key messages** newest first with timestamp, elapsed ms, category, event, grade and details; the
run id and evidence path at the top; "showing X of Y records".
**CTA** filter by category and grade · search · auto-scroll · pause · jump to newest.

---

## Cross-screen invariants

1. Every screen is wrapped in `LabScaffold`. The back route is always the same.
2. Every claim carries a `StatusChip`. Nothing is stated without its grade.
3. Every permission is explained before it is requested, and denial degrades to `NOT_TESTED`.
4. Every tester action is timestamped into the same log.
5. No screen ever converts "we did not test it" into "it does not work".
