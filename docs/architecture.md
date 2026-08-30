# Architecture — MiraiCast Lab

## 1. What the system is for

One sentence: **turn "can the Z Fold show video, play sound and receive touch on a Toyota Mirai
2025?" into a set of experiments whose results are graded by how strongly they are evidenced.**

Every architectural decision below follows from that. The app is an *instrument*, not a media
player. Its output is not a picture on a car screen — the picture is a stimulus. Its output is an
evidence file.

## 2. The layer that everything else depends on

```
                       ┌──────────────────────────────┐
                       │        core (contract)       │
                       │  LabStatus · LabCategory ·   │
                       │  Observation · LogRecord ·   │
                       │  SessionLogger · Probe ·     │
                       │  LabPermissions · Json ·     │
                       │  DashboardKeys · Registry    │
                       └───────────────┬──────────────┘
                                       │  every module depends on core
   ┌───────────┬───────────┬───────────┼───────────┬───────────┬────────────┐
   │           │           │           │           │           │            │
 scan       display       net        audio       input     projection     scene
 (device,   (topology,   (P2P,      (routes,    (devices,   (capture,   (test pattern,
  codecs)    routes)    transports)  tones)     touch-back)  H.264)      controls)
   │           │           │           │           │           │            │
   └───────────┴───────────┴─────┬─────┴───────────┴───────────┴────────────┘
                                 │
              ┌──────────────────┼──────────────────┐
              │                  │                  │
          samsung              auto               report
     (Smart View, DeX,   (Android Auto,      (20-section generator,
      Miracast wizard)    motion state)       exports, log viewer)
```

Arrows point *into* `core` only. No module imports another module's implementation. The two places
that legitimately know about many modules are `core/ProbeRegistry` (which lists the probes) and
`ui/LabNavHost` (which lists the screens) — and both hold only references, never logic.

**Why this shape.** The Samsung-specific and vehicle-specific research is the part most likely to be
wrong, to be device-dependent, or to stop working on the next One UI release. Isolating it (spec
section 17: *"do not tightly couple Samsung-specific research to the generic test components"*) means
a failure there degrades one card on one screen instead of the app.

## 3. The evidence model — the actual core idea

```kotlin
enum class LabStatus { CONFIRMED, OBSERVED, INFERRED, UNSUPPORTED, NOT_TESTED, ERROR }
data class Observation(key, value, status, note, category)
```

Every fact the app produces is an `Observation` carrying its own epistemic status. This is not
decoration; it is enforced structurally:

| Rule | How it is enforced |
|---|---|
| `NOT_TESTED` never becomes `UNSUPPORTED` | They are distinct enum constants and no code maps one to the other. The report generator derives its "confirmed / inferred / unsupported / still requires hardware" sections **mechanically** from the enum, so no human can quietly promote one. |
| A probe crash never becomes a negative result | `Probe.safeObserve` converts any throwable into `Observation.error`, whose note says *"the capability itself is untested"*. |
| A missing dashboard field is visible | `DashboardKeys.missingFrom()` reports keys no probe produced. A blank field is an alarm, not an absence of news. |
| Nothing can be claimed about the sink from phone-side data | Modules that could tempt you (display, codec, projection) emit an explicit `NOT_TESTED` observation stating that phone capability says nothing about what the Mirai accepts. |

`LabStatus.isSilent` (`NOT_TESTED` or `ERROR`) exists so UI and report code can ask "may I conclude
anything from this?" instead of re-deriving the rule each time.

## 4. Components and their relations

### 4.1 `core`

| Component | Responsibility | Depends on | Depended on by |
|---|---|---|---|
| `Evidence.kt` | `LabStatus`, `LabCategory`, `Observation`, `LogRecord` | — | everything |
| `SessionLogger` | the single sink; in-memory `StateFlow` + append-only JSONL on disk | `Json`, `Evidence` | every module, the report |
| `Probe` / `safeObserve` / `probeValue` | the read-only probe contract and its failure discipline | `Evidence` | all 12 probes |
| `ProbeRegistry` | the ordered list of probes; `runAll` with progress | all probes | dashboard, scan screen, wizard |
| `LabPermissions` | optional-permission model with a tester-facing reason per permission | `Evidence` | net, audio, projection, input |
| `DashboardKeys` | the probe → dashboard contract, plus gap detection | `Evidence` | dashboard, all probes |
| `Json` | dependency-free serialisation | — | `SessionLogger`, report |

**Why `SessionLogger` is a singleton.** Records must survive navigation, arrive from coroutines, from
`ImageReader` callbacks on a `HandlerThread`, from input dispatch, and from a second activity running
on a different display. One process, one log. The alternative — passing a logger through every
composable — buys testability the project does not need and costs correctness it does.

**Why append-only JSONL.** The moment evidence matters most is a vehicle session, which is exactly
when the app is most likely to be killed (foreground service, heavy capture, thermal). Each record is
flushed as its own line, so a kill loses at most the current record.

### 4.2 The twelve probes

`DeviceProbe`, `CodecProbe`, `DisplayProbe`, `MediaRouteProbe`, `WifiP2pProbe`,
`ConnectivityProbe`, `AudioRouteProbe`, `InputDeviceProbe`, `ProjectionCapabilityProbe`,
`SmartViewProbe`, `DexProbe`, `AndroidAutoProbe`.

A probe is **read-only, idempotent, and non-throwing**. It never mutates device state and never asks
for permission — permission requests belong to screens, where a human is present to be told why.

### 4.3 The screens

Thirteen, one per experiment, all `@Composable fun X(onBack: () -> Unit)` and all wrapped in
`LabScaffold` so back navigation is uniform. The nav graph (`ui/LabNavHost.kt`) is a flat list of
string routes — deliberately flat, because a tester in a car needs one tap from the dashboard to any
experiment and one tap back.

### 4.4 The two activities

| Activity | Why it exists |
|---|---|
| `MainActivity` | the whole app |
| `scene.PresentationHostActivity` | to place the diagnostic scene on a **secondary display** via `ActivityOptions.setLaunchDisplayId`. This is how an ordinary app puts content on a Miracast/DeX screen without any privileged API — and it is itself one of the project's findings. |

### 4.5 `projection.ProjectionService`

A foreground service with `foregroundServiceType="mediaProjection"`. It holds **no capture logic**.
It exists because Android 14+ refuses `createVirtualDisplay` unless such a service is already
running. Separating "the permission ritual" from "the capture pipeline" keeps the ritual correct in
one place.

## 5. Data flow of a test run

```
tester taps a test
        │
        ▼
screen ── requests permission (with a reason) ──► LabPermissions
        │                                              │ denied
        │                                              ▼
        │                                    Observation(NOT_TESTED)
        ▼
   probe / experiment
        │  observations
        ▼
   SessionLogger.log ──┬──► StateFlow ──► live log viewer, dashboard, screens
                       └──► evidence/run-<ts>-<id>.jsonl   (append-only, survives a kill)
                                        │
                                        ▼
                              ReportGenerator (mechanical grading)
                                        │
                        ┌───────────────┼───────────────┐
                        ▼               ▼               ▼
                   report.md        report.json     report.csv
```

## 6. Cause and effect — what breaks what

| If this changes | Then this must change with it |
|---|---|
| a `DashboardKeys` constant | the probe that emits it, or the dashboard shows `-` and reports a gap |
| a probe's `object` name or `id` | `ProbeRegistry.all`, and any wizard step that looks it up by id |
| a screen's composable signature | `ui/LabNavHost.kt` |
| a `LabStatus` constant | `ui/LabComponents.statusColor` **and** the report generator's sections 15–18 |
| the manifest's FileProvider authority | `report`'s share intent |
| `minSdk` | every `Build.VERSION.SDK_INT` guard, and `LabPermissions.NEARBY`'s split at API 33 |
| adding a dependency | `gradle/libs.versions.toml` **and** the offline guarantee — check it pulls nothing at runtime |

## 7. Deliberate non-goals

- **No re-implementation of Miracast.** Establishing a Wi-Fi Display session is not available to a
  third-party app on a non-rooted Android device. The app guides and instruments; it does not
  connect. Pretending otherwise would be the single easiest way to produce a false result.
- **No hidden-API dependence in the primary path.** Where a proprietary surface is probed (One UI
  version, DeX mode, `InputDevice.isExternal`), it is reflective, guarded, isolated, and degrades to
  `UNSUPPORTED`/`NOT_TESTED` — never to a crash and never to a guess.
- **No network.** No `INTERNET` permission. The offline guarantee is structural, not a policy.
- **No accessibility service** as a workaround.
- **No vehicle interaction.** See `SAFETY.md`.

## 8. Known architectural risks

| Risk | Consequence | Mitigation |
|---|---|---|
| One UI / DeX reflection breaks on a future One UI | DeX discrimination degrades | guarded, isolated in `samsung`, reports `UNSUPPORTED` with the exception name |
| Package-visibility (Android 11+) hides Samsung packages that exist | false `UNSUPPORTED` | those checks are graded `INFERRED`/`NOT_TESTED`, never `UNSUPPORTED`, and the ambiguity is stated in the note |
| `SessionLogger` in-memory buffer caps at 5000 records | long sessions truncate the live view | the JSONL file is uncapped; the viewer says how many records it is showing |
| Secondary-display activity launch is OEM-dependent | scene may open on the phone instead | the launch result is logged with the actual `display.displayId` it landed on |
| Heavy capture + 60 fps scene on a folding phone | thermal throttling skews FPS numbers | the scene reports 99th-percentile frame time alongside the average, which makes throttling visible rather than hidden |
