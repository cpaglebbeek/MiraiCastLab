# Routes A–D — can an app put content on the display Samsung already established?

**Research question v0.2, sharpened from the original build spec.**

The original question was *"can the phone mirror to the Mirai?"*. This one is more specific, and it
matters because the answer changes what has to be built:

> The Toyota Mirai 2025 accepts Miracast. The Galaxy Z Fold has DeX and Wireless DeX. **If Samsung
> has already established the wireless display session, can an ordinary Android app direct content
> onto the display DeX/Miracast is using — without implementing any Miracast protocol itself?**

If yes, the app never speaks Wi-Fi Display. It only tells Android: *render this Activity on that
display.* That is a dramatically smaller problem than being a Miracast sender.

The desired end state is **not** this:

```
[phone] full screen  ──mirror──▶  [Mirai] same screen
```

but this:

```
[phone] normal Android UI        [Mirai] one specific chosen app / Activity
```

---

## The four routes

They succeed and fail **independently**. Collapsing them is how you end up believing "Miracast
works" because something else did.

| | Chain | What it would require |
|---|---|---|
| **A** | App → Wi-Fi Display protocol → Mirai | the app implements RTSP/WFD and drives the radio |
| **B** | Android → Samsung DeX / Smart View → Miracast → Mirai | nothing from us; Samsung owns the session |
| **C** | App → Android secondary-display API → DeX display → Mirai | the app puts **its own** content on that display |
| **D** | App → launch another app on that display → Mirai | the app puts **someone else's** content there |

The owner's hypothesis is **C/D**. This document does not confirm it. Every row below that says
`NOT TESTED` is a measurement waiting to happen, and the app is the instrument.

---

## What has to be true before C or D can even be asked

**Which `Display` object *is* the DeX/Miracast screen?**

Android does not tell you. There is no `Display.isMiracast()`, no transport field, and a Wi-Fi
Display sink is deliberately presented as an ordinary secondary display. `route/DisplayRole.kt`
therefore weighs the signals an app *can* see and returns a verdict that is **never stronger than
`INFERRED`** for anything remote:

| Signal | What it contributes |
|---|---|
| `displayId == DEFAULT_DISPLAY` | the built-in panel — a platform guarantee, so `CONFIRMED` |
| membership of `DISPLAY_CATEGORY_PRESENTATION` | the strongest app-visible "this is somewhere else" |
| `FLAG_PRESENTATION` | same, from the flags side |
| `FLAG_PRIVATE` | an app- or system-created virtual display, *not* a physical sink |
| `DeviceProductInfo.connectionToSinkType` (API 30+) | `BUILT_IN` / `DIRECT` / `TRANSITIVE` |
| display name containing `dex`, `cast`, `wifi`, `overlay`, `hdmi` | weak, contributes only to `INFERRED` |

**Why a remote verdict can never be `CONFIRMED`:** every one of those signals is equally consistent
with an HDMI dongle, a Chromecast, DeX over USB-C, a developer-options simulated display, or another
app's `VirtualDisplay`. The verdict always ships with the signals that produced it, so a reader can
disagree with the weighting without re-deriving the evidence.

---

## The forbidden assumptions

Written down because assuming any of them would silently invalidate the whole investigation. Each is
a runtime measurement, not a premise:

1. Wireless DeX is the same thing as ordinary Miracast screen mirroring.
2. The Mirai is a full Miracast sink in every mode.
3. A DeX display appears as a normal Android secondary display.
4. `setLaunchDisplayId()` works without special privileges.
5. An app may launch another app on an external display.
6. Touch input travels back from the Mirai over Miracast.

---

## Experiment format

Every experiment is recorded as `hypothesis → API/method → test → observation → log → conclusion →
next test`, enforced by `core/Experiment.kt`.

Two fields do the real work, and they are separate on purpose:

- **`observation`** — literally what happened: return values, exception types, which display id was
  reported back.
- **`conclusion`** — what may be concluded from that. Sometimes the honest answer is *nothing*.

The gap between those two is where every wrong finding in this problem domain lives. A common one:
`startActivity` with a launch display id throws no exception, so the activity "went to the external
display" — except the platform silently redirected it to the default display. **No exception is not
success.** Route C and D both verify where the activity *actually landed* rather than trusting the
absence of a throw.

---

## Privilege tiers

Each tier strictly contains the one before it. The primary build stays at **ordinary app**;
everything above is analysis plus a path that is disabled by default and degrades to `NOT_TESTED`.

| Tier | What it is |
|---|---|
| `ORDINARY_APP` | public Android APIs, no special grant |
| `SAMSUNG_API` | proprietary surface reached reflectively; guarded, isolated, degrades to `UNSUPPORTED` |
| `ADB_SHELL` | documented shell commands from a workstation, uid 2000, **no root** |
| `SHIZUKU` | the adb-shell tier without a cable, via a user-started service. Not integrated in this build |
| `ROOT` | out of scope for the build. Analysed, never required |
| `CUSTOM_ROM` | signature or privileged permissions. Analysed only |

`route/PrivilegeTiers.kt` holds the capability × tier matrix and marks every row as **DOCUMENTED**
or **MEASURED**. A documented row is not evidence about this device.

---

## Status — 2026-08-30

**Nothing below has run on a Galaxy Z Fold, and nothing has run against a Toyota Mirai.**
An emulator baseline HAS run (Android 16, no external display, run `0.2.0`), and it is reported
separately in §Emulator baseline. An emulator says nothing about the Mirai; it says a great deal
about whether the instrument works.

| Route | Experiment | Status |
|---|---|---|
| A | A1 public API to initiate a Wi-Fi Display session | NOT TESTED |
| A | A2 Wi-Fi P2P: can an app speak RTSP/WFD itself | NOT TESTED |
| A | A3 capture and encode vs transport | NOT TESTED |
| B | B1 is a session active at all | NOT TESTED |
| B | B2 Wireless DeX vs ordinary Smart View mirroring | NOT TESTED |
| B | B3 what Android exposes about session ownership | NOT TESTED |
| B | B4 signal-transition timeline while connecting by hand | NOT TESTED |
| **C** | **C1 `setLaunchDisplayId()` with our own Activity** | **NOT TESTED** |
| **C** | **C2 `android.app.Presentation`** | **NOT TESTED** |
| C | C3 does the content survive over time | NOT TESTED |
| C | C4 control: same mechanism against `DEFAULT_DISPLAY` | NOT TESTED |
| **D** | **D1 package visibility — what can this app even see** | **NOT TESTED** |
| **D** | **D2 launch another app with a launch display id** | **NOT TESTED** |
| D | D3 the platform's documented refusal, and its exact message | NOT TESTED |
| D | D4 launch *without* a display id while DeX is active | NOT TESTED |

### Emulator baseline — Android 16, no external display, 2026-08-30

All fifteen experiments ran without a crash. Because there is no external display, C and D could
only be exercised against `DEFAULT_DISPLAY` — which is exactly what makes two of these results
useful.

| Exp | Status | What was actually observed |
|---|---|---|
| A1 | UNSUPPORTED | no public `DisplayManager` method initiates a Wi-Fi Display session; the AOSP ones are `@hide` |
| A2 | UNSUPPORTED | `CONFIGURE_WIFI_DISPLAY`, needed for the WFD capability setter, measured as not held |
| A3 | INFERRED | capture and encode are available; the gap in A is transport, not pixels |
| A4 | UNSUPPORTED | derived mechanically from A1–A3, naming the two absences that carry it |
| B1 | OBSERVED | 0 of 3 session signals present — the baseline, not a negative |
| B2 | NOT_TESTED | no secondary display, so DeX and mirroring **could not be told apart** — recorded as an empty measurement, never as "they are the same" |
| B3 | UNSUPPORTED | `MediaRouter`'s mutating methods take `UserRouteInfo`; a system route can be neither created nor removed by an app |
| B4 | NOT_TESTED | timeline not run in this session |
| **C1** | **OBSERVED** | requested display 0, the launched activity reported `displayId=0` after 273 ms. `setLaunchDisplayId` **was honoured** for our own activity |
| **C2** | **UNSUPPORTED** | `Presentation.show()` threw `InvalidDisplayException`, and `DisplayManager` still listed display 0 as valid immediately afterwards — so a genuine refusal, not a stale display |
| C3 | OBSERVED | placed content kept drawing |
| C4 | NOT_TESTED | the control needs a second display to be meaningful |
| D1 | OBSERVED | package visibility measured |
| D2 | NOT_TESTED | no launch attempted — a visibility limit, **not** a platform refusal |
| D3 | OBSERVED | the documented constraint encoded |

**The finding worth keeping.** C1 succeeded and C2 failed on the same display. The two mechanisms
behave differently, which is precisely why they are measured separately — had they been run as one
"route C" test, this would have been invisible. It also matches the documented contract:
`Presentation` is for secondary displays, and `DEFAULT_DISPLAY` is not one.

What this baseline does **not** establish: anything about DeX, about Miracast, or about the Mirai.
C1 honouring a display id on the built-in panel says nothing about whether it would be honoured on a
wireless display, and the outcome note says so in those words.

---

C4 and D4 are the controls, and they are the reason the matrix is trustworthy. If C1 works on
`DEFAULT_DISPLAY` but not on the remote display, the mechanism is fine and the **display** is the
constraint. Without that control, a failure is unattributable.

---

## Safety

Unchanged from [`SAFETY.md`](../SAFETY.md), and the owner's own instruction matches it: tests in the
vehicle happen **stationary only**, and restrictions that appear while driving are *analysed*, never
circumvented. No interlock bypass, no CAN injection, no vehicle-state spoofing. The app has no
`INTERNET` permission and no vehicle-bus code, so both are impossible rather than merely forbidden.

## How to update this document

After a session, replace each `NOT TESTED` with what the exported report says, plus the `testRunId`
and the date. Never from memory, and never upgrade a `NOT TESTED` because a test was skipped.
