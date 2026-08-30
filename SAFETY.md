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
