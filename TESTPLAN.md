# TESTPLAN

What is verified, how, and — just as importantly — what is not. This file is written to the same
standard the app applies to its own findings: a test that did not run is `NOT TESTED`, not a pass.

## Levels

| Level | Runs where | Proves |
|---|---|---|
| **L1 Unit** | JVM (`testDebugUnitTest`), Robolectric where a `Context` is needed | the evidence model and the report generator behave correctly — in particular that grading is mechanical |
| **L2 Build** | `assembleDebug`, `lintDebug` | it compiles and has no blocking lint |
| **L3 Emulator smoke** | HorseBoat box, `emulator-5584` (A16) / `emulator-5554` (A14) | the app **starts** — manifest semantics are only checked on a device |
| **L4 Device** | physical Galaxy Z Fold | every probe against real hardware |
| **L5 Vehicle** | Toyota Mirai 2025, stationary | the questions the project exists to answer |

L1–L3 can run on HC55. **L4 and L5 cannot** and are therefore `NOT TESTED` at v0.1.0.

## L1 — unit tests

The tests worth writing here are the ones that protect the project's central invariant. Volume is
not the goal; these specific properties are.

| Test | Asserts |
|---|---|
| `LabStatusTest` | `NOT_TESTED.isSilent`, `ERROR.isSilent`, only `CONFIRMED`/`OBSERVED` are `isPositiveClaim` |
| `ObservationTest` | the companion helpers produce the status they claim; `error()` preserves the throwable type and message |
| `JsonTest` | escaping of quotes, backslashes, newlines, tabs and control characters; `record()` emits exactly the seven spec §13 fields; CSV quoting doubles embedded quotes |
| `DashboardKeysTest` | `missingFrom` reports every key no probe produced, and nothing else |
| **`ReportGeneratorGradingTest`** | **the critical one:** given a mixed set of observations, section 15 contains exactly the `CONFIRMED`+`OBSERVED` ones, 16 the `INFERRED`, 17 the `UNSUPPORTED`, 18 the `NOT_TESTED` (with `ERROR` listed separately inside it) — and **no `NOT_TESTED` observation appears in section 17 under any input** |
| `ReportSectionsTest` | all 20 sections are present and in order even when the run is empty, and an empty section states that explicitly rather than being omitted |
| `SessionLoggerTest` (Robolectric) | records carry the run id; `newRun` clears the buffer and starts a new file; `observations()` round-trips key/value/status/note |

Run: `./gradlew :app:testDebugUnitTest`

## L2 — build and lint

```bash
./gradlew :app:assembleDebug :app:lintDebug
```

Lint is configured `abortOnError = false`: a lint warning must not block a vehicle session at 22:00
in a car park. The HTML report is still produced and is expected to be read.

## L3 — emulator smoke gate (mandatory before any APK is published)

Green unit tests proved nothing about starting up on a real Android runtime — this project's own
house rule, learned the hard way on another app where 571 green tests missed a manifest attribute
that crashed every launch.

```bash
D=emulator-5584
adb -s $D install -r -d app-debug.apk
adb -s $D logcat -c
adb -s $D shell am start -n nl.icthorse.miraicastlab.debug/nl.icthorse.miraicastlab.MainActivity
sleep 8
adb -s $D logcat -d -b crash | grep -c "FATAL EXCEPTION"   # must be 0
adb -s $D shell pidof nl.icthorse.miraicastlab.debug        # must be non-empty
```

Pass = zero `FATAL EXCEPTION` **and** a live pid. Anything else is a fail, regardless of what the
unit tests say.

An emulator also gives real, if unexciting, probe output: exactly one display, no Wi-Fi Direct, no
Samsung packages, a software H.264 encoder. That is useful — it verifies that the probes report
`UNSUPPORTED` where the platform genuinely answers "no", and `NOT_TESTED` where it cannot answer.

## L4 — physical Galaxy Z Fold  · **NOT TESTED**

| # | Check |
|---|---|
| 4.1 | App starts; dashboard populates with no `-` fields and zero reported dashboard gaps |
| 4.2 | `DeviceProbe` resolves the One UI version (the reflection path actually works on One UI) |
| 4.3 | `CodecProbe` finds hardware H.264 encoders and their real maximum size |
| 4.4 | Folding: the diagnostic scene survives fold/unfold without losing its frame counter |
| 4.5 | Permission denial path: deny everything, confirm the app still runs and reports `NOT_TESTED` |
| 4.6 | MediaProjection: consent → FGS → VirtualDisplay → measured FPS, then a clean teardown with no leak on the second run |
| 4.7 | H.264 test clip written to `evidence/` and playable after `adb pull` |
| 4.8 | Input monitor logs a Bluetooth or USB-C mouse — the instrument calibration for L5 |
| 4.9 | Scene holds 60 fps; p99 frame time is reported and plausible |
| 4.10 | Report exports as Markdown, JSON and CSV; the share sheet opens |

## L5 — Toyota Mirai 2025, stationary  · **NOT TESTED**

Driven entirely by [`docs/toyota-mirai-2025-test-procedure.md`](docs/toyota-mirai-2025-test-procedure.md).
Every question and its current status lives in
[`docs/protocol-findings.md`](docs/protocol-findings.md) §3.

Safety precondition, non-negotiable: stationary vehicle or bench. See [`SAFETY.md`](SAFETY.md).

## What this plan deliberately does not test

- **Instrumented UI tests.** No connected device on the build host, and a Compose UI test that runs
  only on an emulator would verify a layout, not a capability. The budget goes to the L1 grading
  tests and the L3 smoke gate instead.
- **The Mirai's internal protocol behaviour.** Structurally unavailable to a third-party app; see
  `docs/protocol-findings.md` §1. Recorded as a finding, not chased.
- **Any moving-vehicle scenario.** Out of scope by `SAFETY.md`, permanently.
