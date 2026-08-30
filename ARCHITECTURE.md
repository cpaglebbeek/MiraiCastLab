# ARCHITECTURE

Root overview. The full treatment — evidence model, cause-and-effect table, non-goals, known
architectural risks — is in [`docs/architecture.md`](docs/architecture.md). This file is the map you
read first.

## What the system is

An Android **instrument**. It determines empirically which display, audio and input paths exist
between a non-rooted Samsung Galaxy Z Fold and a Toyota Mirai 2025 head unit. The picture it draws
on the car screen is a stimulus; the product is an evidence file.

## Component map

```
                         core  (the contract everything depends on)
                LabStatus · LabCategory · Observation · LogRecord
                SessionLogger · Probe · LabPermissions · Json
                DashboardKeys · ProbeRegistry
                                  ▲
        ┌───────────┬─────────────┼─────────────┬───────────┬───────────┐
      scan       display         net          audio       input     projection
        │           │             │             │           │           │
        └───────────┴──────┬──────┴─────────────┴───────────┴───────────┘
                           │
            ┌──────────────┼──────────────┬───────────────┐
          scene         samsung          auto           report
```

Arrows point **into** `core` only. No module imports another module. The two places that know about
many modules are `core/ProbeRegistry` (lists the 12 probes) and `ui/LabNavHost` (lists the 14
destinations) — and both hold references, not logic.

## Units

| Unit | Files | Role |
|---|---|---|
| `core/` | 8 | evidence model, logging, probe contract, permissions, dashboard contract |
| `ui/` | 4 | theme, shared components, nav graph, dashboard |
| `scan/` | 4 | device and codec inventory |
| `display/` | 4 | display topology, MediaRouter, live listener |
| `net/` | 4 | Wi-Fi Direct, connectivity, interfaces |
| `audio/` | 3 | audio routes, synthesised test tones, A/V pulse |
| `input/` | 4 | event monitor, touch-back grid |
| `projection/` | 4 | capture, VirtualDisplay, H.264, foreground service |
| `scene/` | 5 | diagnostic test pattern, phone-side controls, presentation host |
| `samsung/` | 5 | Smart View and DeX surfaces, Miracast wizard |
| `auto/` | 4 | Android Auto matrix, motion-state observation |
| `report/` | 3 | 20-section generator, exports, live log viewer |

## Runtime

| Component | Note |
|---|---|
| `MainActivity` | single-activity host for all fourteen destinations |
| `scene.PresentationHostActivity` | exists only to place the scene on a **secondary** display via `ActivityOptions.setLaunchDisplayId` — how an ordinary app reaches a Miracast/DeX screen with no privileged API |
| `projection.ProjectionService` | foreground service, type `mediaProjection`. Holds no capture logic; it exists because Android 14+ refuses `createVirtualDisplay` without it |
| `FileProvider` | shares exported reports; paths in `res/xml/file_paths.xml` |

## Storage

App-private only. `filesDir/evidence/run-<ts>-<id>.jsonl` (append-only, flushed per record, survives
a process kill) and `filesDir/reports/`. **No `INTERNET` permission** — nothing can leave the device.

## Deploy

`./gradlew :app:assembleDebug` on HC55 → `dist/MiraiCastLab-v<version>-<Codename>-debug.apk` →
`/APKDeploy` → `horsecloud55.ddns.net:4443/MiraiCastLab/`. Emulator smoke gate is mandatory first;
see [`TESTPLAN.md`](TESTPLAN.md) L3.

## Interactive architecture viewer

`architectuur/MiraiCastLab_viewer.html` — standalone, deterministic, five views (Conceptueel,
Logisch, Fysiek, Transacties, Journeys) with animated transaction and journey scenarios, an
ArchiMate ↔ Dragon1 notation switch, and DSL import/export. Source model:
`architectuur/MiraiCastLab_archdsl.dsl`.

## Related documentation

| | |
|---|---|
| [`docs/architecture.md`](docs/architecture.md) | full architecture: evidence model, cause-and-effect, non-goals, risks |
| [`docs/PRINCIPLES.md`](docs/PRINCIPLES.md) | P1–P12, the rules that settle arguments |
| [`docs/DEPENDENCIES.md`](docs/DEPENDENCIES.md) | every dependency and platform surface, with justification |
| [`docs/protocol-findings.md`](docs/protocol-findings.md) | the capability matrix and the honest state |
| [`CONTENT_INVENTORY.md`](CONTENT_INVENTORY.md) | purpose, key messages and CTA per screen |
| [`DESIGN_TOKENS.md`](DESIGN_TOKENS.md) | colour, typography, spacing, components |
| [`SAFETY.md`](SAFETY.md) | the boundary and why it is structural |
| [`docs/DUPLICATES.md`](docs/DUPLICATES.md) | accepted duplication register |
