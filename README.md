# MiraiCast Lab

**An Android capability explorer that finds out — empirically — what actually works between a
non-rooted Samsung Galaxy Z Fold and a Toyota Mirai 2025 head unit.**

Picture · sound · touch back. Miracast, Smart View, DeX, Android Auto. The app does not assume any
of it works. It runs experiments, grades every finding by how strongly it is evidenced, and writes
an evidence file you can argue with.

> **Version 0.1.0 "Mirai-1" · not yet run on a Z Fold · not yet run in a vehicle.**
> Every vehicle-side result in this repository is `NOT TESTED`. That is the honest state, and
> [`docs/protocol-findings.md`](docs/protocol-findings.md) is where those results get filled in.

---

## The one rule

Every fact the app produces carries its own epistemic status:

| | |
|---|---|
| **CONFIRMED** | direct evidence, or a platform contract plus a matching implementation |
| **OBSERVED** | actually seen during a test |
| **INFERRED** | evidence suggests it, not directly confirmed |
| **UNSUPPORTED** | a test or API produced evidence it is absent here |
| **NOT TESTED** | hardware or circumstances were unavailable — **no claim is made** |

`NOT TESTED` is never converted into `UNSUPPORTED`. The report generator derives its
confirmed/inferred/unsupported/still-needs-hardware sections **mechanically from the enum**, so
nobody — human or model — can quietly upgrade "we didn't test it" into "it doesn't work".

The app will not tell you "the Toyota Mirai supports UIBC". It will tell you whether touching the
Mirai screen produced an input event on the phone during a specific run, at a specific time, with a
specific device id.

---

## Safety

Read [`SAFETY.md`](SAFETY.md). Short version:

- **No** interlock bypass, CAN injection, vehicle-state spoofing or lockout defeat — not now, not later.
- The motion-state experiment **observes and records**. If the head unit blanks video, that is the result.
- Bench, simulator or **stationary** vehicle only.
- No `INTERNET` permission. The app cannot upload anything, by construction.
- No root. No accessibility-service workaround. No personal data.

---

## What it does

**Twelve read-only probes** inventory the device: `DeviceProbe`, `CodecProbe`, `DisplayProbe`,
`MediaRouteProbe`, `WifiP2pProbe`, `ConnectivityProbe`, `AudioRouteProbe`, `InputDeviceProbe`,
`ProjectionCapabilityProbe`, `SmartViewProbe`, `DexProbe`, `AndroidAutoProbe`.

**Thirteen screens** run the experiments:

| Screen | What it establishes |
|---|---|
| Dashboard | the whole device state on one page, with a status chip per fact |
| Device scan | full capability inventory, grouped and filterable |
| Miracast wizard | the seven-step guided session (spec §8): baseline → connect by hand → watch the diff → project the scene → record what you saw → input → export |
| Display test | live display topology with a `DisplayListener`; launches the scene onto the car screen |
| Network / Wi-Fi Direct | P2P peers, transports, and `p2p0` appearing the moment Smart View connects |
| Audio test | silence, mono, stereo L/R, continuous, latency click, A/V sync pulse — all synthesised in code |
| Input test | full event monitor + the 3×5 touch-back target grid with a derived coordinate transform |
| MediaProjection test | capture FPS, VirtualDisplay metrics, a real H.264 encode with encoder statistics |
| Test pattern | the diagnostic scene: bouncing object, frame counter, 60-position sweep, 1-pixel bars, RGB/greyscale blocks, live FPS and p99 |
| Android Auto | the A–F coexistence matrix, persisted, with automatic environment snapshots |
| Motion state | liveness recorder + tester markers. Observation only. |
| Report | the 20-section Markdown report, plus JSON and CSV export and a share sheet |
| Live log | the structured log with category/status filters and search |

**Structured evidence.** Every record is
`{timestamp, elapsedRealtimeMs, testRunId, category, event, status, details}`, appended as JSONL to
app-private storage as it happens — so a session survives the app being killed, which is exactly
what happens during heavy capture in a car.

---

## Build

```bash
export ANDROID_HOME=~/Android/Sdk
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
./gradlew :app:assembleDebug
adb install -r -d app/build/outputs/apk/debug/app-debug.apk
```

Kotlin 2.1 · Compose (Material 3) · AGP 8.7.3 · minSdk 29 · targetSdk 35 · no cloud dependencies.
Full detail, including the mandatory emulator smoke gate: [`BUILD.md`](BUILD.md).

---

## Running a real session

[`docs/toyota-mirai-2025-test-procedure.md`](docs/toyota-mirai-2025-test-procedure.md) is a single
checklist you can work through from the driver's seat, with the laptop optional. It tells you what to
photograph and why — the 60-position sweep gives you the true delivered frame rate from one picture,
and the frame counter on both screens in one frame gives you the latency.

---

## Documentation

| | |
|---|---|
| [`SAFETY.md`](SAFETY.md) | the boundary, and why it is structural rather than a policy |
| [`BUILD.md`](BUILD.md) | toolchain, build, install, emulator smoke gate, evidence pulling |
| [`TESTPLAN.md`](TESTPLAN.md) | what is verified how, and what is explicitly not |
| [`docs/architecture.md`](docs/architecture.md) | components, relations, cause-and-effect, non-goals |
| [`docs/protocol-findings.md`](docs/protocol-findings.md) | the capability matrix — including what a third-party app *structurally cannot* observe |
| [`docs/PRINCIPLES.md`](docs/PRINCIPLES.md) | the rules that decide arguments about this codebase |
| [`docs/DEPENDENCIES.md`](docs/DEPENDENCIES.md) | every dependency and why it earns its place |
| [`tools/adb/README.md`](tools/adb/README.md) | read-only workstation diagnostics |
| [`BUGLIST.md`](BUGLIST.md) · [`RELEASES.md`](RELEASES.md) | bugs and releases |

---

## Licence

AGPL-3.0. See [`LICENSE`](LICENSE).
