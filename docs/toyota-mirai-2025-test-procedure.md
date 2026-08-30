# Physical test procedure — Samsung Galaxy Z Fold × Toyota Mirai 2025

Everything in this project up to this point runs without a car. This document is the part that
does not. It is written so the whole session can be executed from the phone, with the laptop
optional (spec section 21: *"the tester should not need to remember ADB commands while sitting in
the vehicle"*).

---

## 0. Before you leave

**Vehicle state:** the car must be **stationary**, in park, ignition on. See `SAFETY.md`. Nothing in
this procedure is to be executed while driving, and nothing in this procedure attempts to change
what the head unit does about vehicle state.

**Prepare:**

1. Install a debug build and confirm it starts.
   ```bash
   ./gradlew :app:assembleDebug
   adb install -r -d app/build/outputs/apk/debug/app-debug.apk
   ```
2. Open the app once at home and press **RUN DEVICE SCAN**. This gives you a *baseline with no car
   present* — the single most useful reference you will have.
3. In **EXPORT REPORT**, fill in the vehicle form (make, model, year, head-unit software version)
   and export a Markdown report. Label it `baseline-no-vehicle`.
4. Optional but recommended: take a laptop and run
   `./tools/adb/collect-dumpsys.sh baseline` and keep `./tools/adb/watch-session.sh` running.
5. Charge the phone. The diagnostic scene plus screen capture is a heavy load.

**Grant, when the app asks and explains why:** nearby-devices (or location on Android ≤ 12) for
Wi-Fi Direct discovery, notifications for the capture service. Audio and Bluetooth are optional —
if you deny them, the corresponding findings come back `NOT_TESTED`, which is correct and harmless.

---

## 1. Baseline in the vehicle, nothing connected  (matrix row A)

| | |
|---|---|
| Screen | Dashboard → **RUN DEVICE SCAN** |
| Then | **ANDROID AUTO COEXISTENCE TEST** → row **A (OFF / OFF)** → record |

Record before anything is connected. This is what "normal" looks like inside this car, and every
later observation is a diff against it.

Note especially: number of displays, whether any `p2p*` interface exists, the active audio output,
and whether Android already reports car mode.

---

## 2. Miracast / Smart View  (matrix row B)

Screen: **MIRACAST TEST** — the seven-step wizard drives this whole section.

| Step | What you do | What the app records |
|---|---|---|
| 1 | Tap **Scan baseline** | display / route / Wi-Fi / P2P / audio snapshot |
| 2 | Start **Smart View** from the phone's quick panel and pick the Mirai | the app cannot connect for you; it opens the right settings panel if the intent resolves |
| 3 | Wait, watching the live change list | every display, route, interface and audio-route change, timestamped |
| 4 | Tap **Launch scene on external display** | the diagnostic pattern appears on the car screen |
| 5 | For each sub-test, tap what you actually see | VISIBLE / DISTORTED / BLACK / AUDIO ONLY / NOT SHOWN |
| 6 | Touch the numbered targets on the **car** screen | any input event that arrives on the phone |
| 7 | Export | a wizard summary with a status per line |

### What to look at on the car screen

The diagnostic scene is built to be read, not admired:

- **Rotating 60-position sweep** — photograph the car screen with another phone. The number of
  distinct sweep positions visible in one photo tells you the delivered frame rate, independent of
  what either device claims.
- **Frame counter and timestamp** — photograph the car screen and the phone screen in one frame.
  The difference between the two counters is the end-to-end latency, in frames.
- **1-pixel stripe bars** — if they moiré or vanish, the link is scaling.
- **RGB / greyscale blocks** — if the darkest or brightest blocks merge, levels are being clipped.
- **Bouncing object trail** — judder and frame drops are visible here before any counter shows them.

### Questions this step answers (and the honest limits)

The app will report the **negotiated resolution, framerate, codec, HDCP state and UIBC advertisement
as `NOT_TESTED`**, permanently. Those live in the Wi-Fi Display / wpa_supplicant layer, which is not
exposed to third-party apps on a non-rooted device. That is a finding of this project, not a gap in
it. What you *can* establish: whether a display appeared, at what size Android drew to it, whether
audio moved, whether motion survived the link, and whether anything came back the other way.

---

## 3. Touch-back — does the Mirai screen drive the phone?

Screen: **INPUT TEST**.

1. Sanity-check the instrument first: pair a Bluetooth mouse or plug in a USB-C mouse and confirm the
   event monitor logs its movement. If it does not, the monitor is broken and any negative result
   below is meaningless.
2. Run the **3×5 target grid**. Touch each numbered target **on the car's screen**.
3. Read the verdict.

A result of **NO INPUT OBSERVED** is recorded as such — not as "unsupported". It means this session,
with this sink, over this transport, produced no back channel. That is what we can say.

If input *does* arrive, the app records its source, device id, coordinates, pressure and the derived
coordinate transform between the grid and what was received — which is the evidence that a real
input back channel exists.

---

## 4. Audio

Screen: **AUDIO TEST**. Run each mode and note what came out of the car speakers:

| Mode | What it proves |
|---|---|
| Silence | that the route is live but idle |
| Mono tone | that audio traverses the link at all |
| Stereo L/R identify | channel mapping — the app names the channel that should be audible |
| Continuous tone | dropouts and resampling artefacts |
| Latency click | with an external recorder: end-to-end audio latency |
| A/V sync pulse | the screen flashes at the same instant the click is written |

For the last two: record the car's screen and speakers together on a second phone. The app logs its
own internal timestamps; the *end-to-end* number can only come from that external recording, and the
app says so rather than pretending otherwise.

---

## 5. Samsung DeX  (spec 2.3)

Screen: **SAMSUNG DEX**.

Start Wireless DeX from the quick panel and see whether the Mirai is even offered as a target. Then
compare the external display's density and flags against what plain Smart View produced. A different
density is the usual discriminator between DeX and mirroring.

Record the exact limitation if DeX refuses the Mirai — the wording of the refusal is data.

---

## 6. Android Auto coexistence  (matrix rows C–F)

Screen: **ANDROID AUTO COEXISTENCE TEST**.

Work the matrix in order. For each row the app snapshots the environment automatically; you supply
the observations only you can make.

| Row | Android Auto | Miracast | The question |
|---|---|---|---|
| C | USB on | off | does wired AA work at all here |
| D | USB on | on | can wired AA coexist with Miracast |
| E | Wireless on | off | does wireless AA work at all here |
| F | Wireless on | on | can wireless AA coexist with Miracast |

For D and F, try **both connection orders** — AA first then Miracast, and the reverse. They can
behave differently, and which one survives is the finding.

Watch the Wi-Fi frequency readout. Wireless Android Auto and Miracast both want the 5 GHz radio; if
one kills the other, radio contention is the most likely reason and the frequency change is your
evidence for it.

Record time-until-disconnect with the in-app timer whenever a session drops.

---

## 7. Motion state — observation only  (spec 12)

Screen: **LAB MOTION-STATE TEST**. Read the safety card at the top of that screen before you start.

The car stays **stationary**. What you are recording is what the head unit does with projected video
under whatever state it is in, using the marker buttons: `PARK`, `VIDEO VISIBLE`, `VIDEO BLOCKED`,
`AUDIO ONLY`, `CONNECTION LOST`.

If a bench head unit or a legitimate stationary service/test environment is available that can change
the receiver's vehicle-state input, record what the receiver does — video stays, video hides, an
overlay appears, audio continues, projection ends. **Nothing in this project changes that input.**

---

## 8. Close the session

1. **EXPORT REPORT** → Markdown, JSON and CSV. Note the paths shown on screen.
2. With a laptop: `./tools/adb/collect-dumpsys.sh after` and `./tools/adb/pull-evidence.sh`.
3. Without a laptop: use the in-app share sheet.
4. Disconnect Smart View and Android Auto.

---

## What a complete session should have produced

- one report per matrix row, or an explicit `NOT_TESTED` for rows you could not run;
- a photograph of the car screen showing the sweep and the frame counter;
- one external A/V recording for the latency estimate;
- a touch-back verdict with its percentage and source list;
- the JSONL evidence file for the whole run.

Anything you did not do stays `NOT_TESTED` in the report. Do not fill it in from memory afterwards.
