# SAFETY.md — the boundary this project does not cross

MiraiCast Lab exists to find out **what is technically available** between a Samsung Galaxy Z Fold
and a Toyota Mirai 2025 head unit. It does not exist to make the vehicle do something it was
designed not to do.

## The rule

> **Do not implement functionality whose purpose is to defeat, patch, bypass, spoof or disable a
> vehicle manufacturer's safety interlocks that suppress video while a vehicle is moving.**

This is taken verbatim from section 1 of the build specification and it is binding on every module,
every future change, and every contributor — human or AI.

## What that means concretely

**Not in this repository, now or later:**

| Forbidden | Why |
|---|---|
| CAN bus message injection | Directly manipulates vehicle state |
| Vehicle-state spoofing (park/drive/speed) | Defeats the interlock by lying to it |
| Patching or replacing head-unit firmware | Defeats the interlock by removing it |
| Android Auto protocol spoofing | Section 3 of the spec forbids it explicitly |
| Circumventing Android Auto app-category restrictions | Same |
| Any code path that transmits to a vehicle bus | No legitimate use in a capability explorer |
| Detection evasion of any kind | Nothing here needs to hide |

**In this repository, and the whole point of it:**

- Observing what the phone's own APIs report.
- Guiding a tester through a manual connection and recording what changed.
- Recording, with timestamps, what the tester saw the head unit do.
- Reporting "the head unit blanked video" as a **result**.

## The motion-state experiment (spec section 12)

The research question — *does the system technically allow moving-image playback when the receiver
believes the vehicle is moving?* — is answered here **only** by:

- documented Android APIs,
- protocol observation from the phone side,
- an emulator or simulator,
- replayed or synthetic state on a **bench** head unit,
- tester-entered markers describing what was observed.

`MotionStateScreen` therefore contains exactly two things: a passive liveness recorder, and large
buttons with which the tester records what they saw (`PARK`, `DRIVE STATE SIMULATED`,
`VIDEO VISIBLE`, `VIDEO BLOCKED`, …). It sends nothing anywhere. It cannot: the application has no
`INTERNET` permission and no vehicle-bus code.

**If the Toyota head unit disables video based on vehicle state, that is recorded as a finding and
the project stops there.** No bypass is built for use on public roads, or anywhere else.

## Where testing belongs

- a bench / test-lab setup,
- a **stationary** vehicle,
- an emulator,
- a controlled experiment with simulated receiver state.

Not on a public road, and not while driving.

## The accessibility service (added in v0.3.0)

The app declares an `AccessibilityService`. That is a powerful permission and it deserves more than
a line, so here is the whole reasoning.

**Why it exists.** The research question asks whether an app can drive a DeX desktop with emulated
keyboard and mouse input. On a non-rooted Android device there is exactly one unprivileged route
that can send input to *another* app: an accessibility service. `INJECT_EVENTS` is signature-level
and unobtainable; `Instrumentation` reaches only our own windows. So this is not a workaround for a
missing API — it *is* the API, and section 18 of the build spec allows it precisely on that
condition: a specific experiment genuinely needs it.

**What it is allowed to do.** The config asks for the narrowest capability set that still permits
the measurement:

| Setting | Value | Why |
|---|---|---|
| `canPerformGestures` | **true** | the actual capability under test |
| `canRetrieveWindowContent` | **false** | it must not be able to read what is on your screens |
| `canRequestFilterKeyEvents` | **false** | it must not see your keystrokes |
| `accessibilityEventTypes` | `typeWindowStateChanged` only | the minimum Android accepts |

`onAccessibilityEvent` does not read event content.

**The trade-off, stated rather than hidden.** Because the service cannot retrieve window content, it
also cannot type text into a focused field — `ACTION_SET_TEXT` needs that access. So the "type an app
name" step of the hotkey flow comes back `NOT_TESTED` rather than working. That is a deliberate
choice: an instrument that can read every screen you open is a different kind of thing from one that
can tap, and this project does not need the first.

**Two locks, not one.** Enabling the service in Android Settings is not sufficient. The app keeps a
separate in-app *armed* flag that defaults to off, so a service left enabled by accident cannot send
anything. Nothing is dispatched on screen open; every action needs a press.

**When you are done testing, turn it off** in Settings → Accessibility. The app says so on screen.

**What it will never do.** Read screen content, log keystrokes, run automatically, persist input
across sessions, or send anything anywhere — the app has no `INTERNET` permission, so the last is
structural rather than a promise.

## Bluetooth HID (added in v0.3.0)

The app can register as a Bluetooth HID keyboard/mouse toward an **already paired** host, using the
public `BluetoothHidDevice` API. It never initiates pairing, never connects without an explicit
button press, and holds no `BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE` or location permission.

A phone cannot be a HID host to itself, so this route cannot drive the DeX desktop running on the
same phone. That is recorded as the finding it is, not worked around.

Device addresses are reported as presence, never as values: the MAC address of a household device
identifies a household.

## Privacy and data

- The app declares **no `INTERNET` permission**. It cannot upload anything, by construction.
- All evidence is written to app-private storage (`filesDir/evidence`, `filesDir/reports`).
- No personal data is collected. Wi-Fi addresses are reported as *presence*, not as values, where
  the value would identify a person or a household.
- Every permission is optional and explained before it is requested; denial degrades a probe to
  `NOT_TESTED`, it never blocks the app.
- No accessibility service is used as a workaround.
- No root is required, requested or assumed.

## Escalation

If a future change appears to need something on the forbidden list, the correct outcome is to
**record the limitation as a finding** and stop — not to route around it.
