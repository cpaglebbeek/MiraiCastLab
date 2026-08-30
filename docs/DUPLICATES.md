# DUPLICATES — accepted duplication register

No duplicate content — code, config or docs — unless it is recorded here with an explicit reason and
an approval date. Unintended duplication is a drift magnet: two places that quietly grow apart.

## Scan of 2026-08-30

Detector `jscpd`, thresholds ≥ 6 lines and ≥ 80% similarity, run over the whole tree excluding
`build/`, `dist/`, `gradle/` and `.git/`.

| | |
|---|---|
| Raw hits | 46 |
| Pure `import` / `package` blocks | 39 |
| Closing braces and comment separators | 6 |
| **Genuine duplication** | **1** |

Kotlin import blocks are structurally identical across files that use the same `core` API. That is
what a shared contract looks like, not duplication, and it is not registrable.

## Resolved

### DUP-001 — device identity fields for MotionEvent and KeyEvent

| Field | Value |
|---|---|
| id | DUP-001 |
| file-A | `input/InputCaptureView.kt:225-234` (MotionEvent path) |
| file-B | `input/InputCaptureView.kt:266-275` (KeyEvent path) |
| lines | 8 per copy |
| similarity | ~95% |
| decision | **refactored, not registered** |
| decided by | cpaglebbeek |
| date | 2026-08-30 |

Both paths filled the same eight device-identity fields — `deviceId`, `deviceName`,
`deviceDescriptor`, `deviceIdentity`, `deviceVirtual`, `deviceExternal`, `source`, `sourceNames`.

This block answers *"did this event come from the Toyota?"*, which is the finding the whole
touch-back experiment turns on. Two copies drifting apart would quietly change what the experiment
reports, so registering the duplication was the wrong call and it was extracted instead.

Now: `InputCaptureView.baseEvent(...)` builds everything common to any input event, defaulting the
pointer-shaped fields to the "no pointer" values a `KeyEvent` already carries. The motion path
overrides them with `copy()`; the key path needs no overrides at all.

## Currently accepted duplication

**None.** No entry in this file is awaiting approval.
