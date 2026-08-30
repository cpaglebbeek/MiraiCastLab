# Principles

The rules that settle arguments about this codebase. When a change conflicts with one of these, the
principle wins or the principle changes — not silently both.

## P1 — A finding without a grade is not a finding

Every fact carries a `LabStatus`. There is no code path that produces an ungraded value into the
report. If you cannot say how strongly something is evidenced, you have not finished measuring it.

## P2 — `NOT_TESTED` is not a weaker `UNSUPPORTED`

"We could not run the test" and "the test showed it is absent" are different claims about the world.
No function maps one to the other. The report's confirmed/inferred/unsupported/still-needs-hardware
sections are derived mechanically from the enum, precisely so that no prose step can blur them.

*Consequence:* a denied permission, a device that is not present, a missing car — all `NOT_TESTED`.
A `getSystemService` that returns null, a `hasSystemFeature` that returns false — those are
`UNSUPPORTED`, because the platform answered.

## P3 — Phone-side capability says nothing about the sink

The Z Fold having eight H.264 encoders tells you nothing about what the Mirai accepts. Every module
that could tempt a reader into that inference emits an explicit `NOT_TESTED` observation saying so.

## P4 — A probe crash is data, not an outage

`safeObserve` converts any throwable into an `ERROR` observation whose note reads *"the capability
itself is untested"*. A probe may never take the app down, and may never turn its own failure into a
negative result about the device.

## P5 — Absence of evidence is stated as such

Root not detected is `INFERRED`, not `CONFIRMED`. No input arriving is "NO INPUT OBSERVED in this
session", not "the sink has no back channel". Package-visibility failures on Android 11+ are
`INFERRED`/`NOT_TESTED`, never `UNSUPPORTED` — the package may exist and simply be invisible to us.

## P6 — The app instruments; it does not pretend to connect

Establishing a Wi-Fi Display session is not available to a third-party app on a non-rooted device.
The app opens the right settings panel, then measures what changed. Anything that looked like the
app connecting would be a lie in the evidence file.

## P7 — The safety boundary is structural, not a policy

No `INTERNET` permission, so uploading is impossible rather than merely forbidden. No vehicle-bus
code, so interlock interference is impossible rather than merely disallowed. See `SAFETY.md`.

## P8 — Evidence is written as it happens

Append-only JSONL, flushed per record. The moment evidence matters most is a vehicle session, which
is exactly when the process is most likely to be killed.

## P9 — The tester is in a car

Large targets, one tap from the dashboard to any experiment, one tap back, a visible current-action
label, and PASS/FAIL/OBSERVED/WAITING states. No ADB required to run a session, and no step that
depends on remembering a command.

## P10 — Modules do not import each other

Everything depends on `core`. `ProbeRegistry` and `LabNavHost` are the only places that know about
many modules, and they hold references, not logic. Samsung-proprietary research is quarantined in
`samsung/` so that its inevitable breakage on a future One UI costs one card, not the app.

## P11 — Offline, dependency-light, no assets

The diagnostic scene is generated in code. The tones are synthesised in code. Nothing here needs the
network, a media file, or a licence from anyone.

## P12 — Comment the why

The what is in the code. Notes on an `Observation` explain *why* a status is what it is, because in
six months that note is the only thing standing between a reader and a wrong conclusion.
