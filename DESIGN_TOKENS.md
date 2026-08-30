# DESIGN_TOKENS

Every visual decision in MiraiCast Lab, in one place. The app is read by a tester **in a car, in
daylight, at arm's length** — that constraint, not taste, is what set most of these values.

Source of truth: `app/src/main/java/nl/icthorse/miraicastlab/ui/LabTheme.kt` (tokens) and
`ui/LabComponents.kt` (components). Change a value there and it moves everywhere.

## 1. Colour — chrome

| Token | Value | Where it is used |
|---|---|---|
| `LabColors.Ink` | `#0B0F14` | app background, monospace evidence blocks, status/navigation bars |
| `LabColors.Surface` | `#141A22` | cards, title bar, the sweep-dial face in the diagnostic scene |
| `LabColors.SurfaceHigh` | `#1E2731` | raised surface / Material `surfaceVariant` |
| `LabColors.Line` | `#2C3846` | card borders, hairlines |
| `LabColors.Text` | `#E8EEF4` | primary text |
| `LabColors.TextDim` | `#93A3B4` | labels, notes, secondary text |
| `LabColors.Accent` | `#00E5A0` | primary buttons, active state, scene chrome |
| `LabColors.AccentDim` | `#0A8F66` | primary in the light scheme |

Dark by default and dark-first: the light scheme exists for bench work, but every layout is
designed against the dark palette.

## 2. Colour — evidence grades

**One colour per `LabStatus`.** This is not decoration: the whole point of the app is that a reader
can tell at a glance how strongly something is claimed. `statusColor()` in `LabComponents.kt` is the
only mapping; nothing else may colour a status.

| Status | Token | Value | Reads as |
|---|---|---|---|
| `CONFIRMED` | `LabColors.Confirmed` | `#00E5A0` | green — direct evidence |
| `OBSERVED` | `LabColors.Observed` | `#4FC3F7` | blue — seen this run |
| `INFERRED` | `LabColors.Inferred` | `#FFC845` | amber — suggested, not confirmed |
| `UNSUPPORTED` | `LabColors.Unsupported` | `#FF6B6B` | red — the platform answered "no" |
| `NOT_TESTED` | `LabColors.NotTested` | `#7A8899` | grey — **no claim made** |
| `ERROR` | `LabColors.Error` | `#FF3D71` | magenta-red — the probe failed |

`NOT_TESTED` is deliberately the most *muted* colour in the set. A grey chip should not read as a
result, because it is not one.

## 3. Colour — the diagnostic scene

`scene/SceneRenderer.kt` holds two kinds of colour and they must not be confused.

**Chrome** — references the shared tokens, safe to restyle: `Accent` → `LabColors.Accent`,
`Amber` → `LabColors.Inferred`, `Alarm` → `LabColors.Error`, `DialBg` → `LabColors.Surface`.

**Calibration** — fixed by measurement intent, **never themed**:

| Constant | Value | Why it must not follow a theme |
|---|---|---|
| `BandBg` | `#0D1116` | band background; constant so bar contrast is comparable between runs |
| `GridLine` | `#3355708A` | 20% steel; a fixed hairline reference for scaling artefacts |
| `ParityOff` | `#12181F` | the "off" parity square; near-black and constant so level clipping is readable |
| `FaintOutline` | `#14FFFFFF` | flat 8% white box around the audio-pulse area |
| `PulseFrame` | `#FF6B6B` | full-bleed pulse frame; fixed so photographs compare across runs and vehicles |
| `Color.White` | `#FFFFFF` | the "on" parity square — pure white is the top-of-range reference |

A reference block that follows a theme is no longer a reference. The tester photographs the car
screen to see whether **the link** clipped a level, not whether the app picked a different shade.

## 4. Typography

| Style | Size | Weight | Used for |
|---|---|---|---|
| `displaySmall` | 34sp | Bold | screen titles, the big status word in `VerdictBanner` |
| `headlineMedium` | 26sp | Bold | stat tile values |
| `titleLarge` | 20sp | SemiBold | card titles, title bar |
| `bodyLarge` | 16sp | Normal | observation values, primary content |
| `bodyMedium` | 14sp | Normal | labels, notes |
| `labelLarge` | 15sp | SemiBold | button text |
| `LabMono` | 13sp | Monospace | keys, timestamps, coordinates, codec names, raw dumps |

Everything is at least 14sp. `LabMono` is monospace because coordinates and timestamps are read as
*columns* — a proportional font makes a coordinate drift comparison unreadable.

## 5. Shape, spacing and touch targets

| Token | Value | Note |
|---|---|---|
| Card radius | 14dp | `LabCard`, `VerdictBanner` |
| Button radius | 12dp | `BigActionButton`, `LabButton` |
| Chip radius | 6dp | `StatusChip` |
| Mono block radius | 8dp | `MonoBlock` |
| Card padding | 14dp | inner |
| Card margin | 12dp horizontal / 6dp vertical | between cards |
| **`BigActionButton` min height** | **64dp** | primary experiment actions |
| **`LabButton` min height** | **52dp** | secondary actions |
| Card border | 1dp `LabColors.Line` | |
| Verdict border | 2dp status colour | |

64dp is well above the 48dp Material minimum. That is the car: the tester is reaching sideways, the
phone may be in a holder, and a mis-tap during a timed measurement costs the measurement.

## 6. Shared components (`ui/LabComponents.kt`)

| Component | Role |
|---|---|
| `LabScaffold` | every screen's frame — title, subtitle, back arrow. Uniform escape route. |
| `LabCard` | grouped block of information |
| `BigActionButton` / `LabButton` | primary / secondary action |
| `StatusChip` | evidence grade badge |
| `VerdictBanner` | the one-glance answer at the top of a test screen |
| `ObservationRow` / `ObservationList` | key, value, grade, note |
| `MonoBlock` | raw evidence, horizontally scrollable |
| `StatTile` / `StatRow` | live measurements (fps, counts) |
| `Dot` | live/idle indicator |
| `Gap` / `HGap` | fixed spacing |

Using these is what makes ten independently written modules look like one app.

## 7. Not yet configurable at runtime

The app has **no in-app appearance settings**. Light/dark follows the system; text size and contrast
are not adjustable in the app.

For a lab instrument read in a vehicle this is a real limitation, not a neutral choice, and it is
recorded here as a known gap rather than left implicit. Tracked as P3 in the sanitycheck.
The one persisted preference today is the vehicle/head-unit form on the report screen
(`SharedPreferences`), which is data, not appearance.
