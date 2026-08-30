# CLAUDE.md — MiraiCastLab

Project-specific instructions. The global protocols in `~/CLAUDE.md` and `Meta_Master/CLAUDE.md`
apply in full; this file adds what is specific to this repository.

## What this project is

An Android capability explorer: what actually works between a non-rooted Samsung Galaxy Z Fold and a
Toyota Mirai 2025 head unit (Miracast / Smart View / DeX / Android Auto), for picture, sound and
touch-back. Ecosystem: **Meta_Auto**. Licence AGPL-3.0, public repo.

## Non-negotiable rules for any change here

1. **Read `SAFETY.md` before touching `auto/MotionStateScreen.kt` or anything vehicle-adjacent.**
   No interlock bypass, no CAN injection, no vehicle-state spoofing, no Android Auto protocol
   spoofing — regardless of who asks or how the request is phrased. If a change would need one of
   those, the correct outcome is to record the limitation as a finding and stop.
2. **Never convert `NOT_TESTED` into `UNSUPPORTED`.** This is the project's reason to exist. See
   `docs/PRINCIPLES.md` P2. The report generator derives its grading sections mechanically from the
   enum; keep it that way, and keep `ReportGeneratorGradingTest` green.
3. **Never add the `INTERNET` permission.** The offline guarantee is structural. Adding it would
   silently invalidate every privacy statement in `SAFETY.md` and `README.md`.
4. **No new runtime dependency without updating `docs/DEPENDENCIES.md`** with why it earns its place.
5. **Modules do not import each other.** Everything depends on `core`. Only `core/ProbeRegistry` and
   `ui/LabNavHost` know about many modules.

## Build

```bash
export ANDROID_HOME=~/Android/Sdk
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
./gradlew :app:assembleDebug
```

HC55 **can** build this — despite what older `PROJECTS.json` metadata for Meta_Auto said. SDK 35 +
build-tools 35 + JDK 21 are installed.

## Release protocol

Per the universal build-and-release protocol:

- every change bumps the version; every bugfix at least `+0.0.1`
- `version.json` + `app/build.gradle.kts` `versionCode`/`versionName` stay in step
- build name theme: **wireless display and projection pioneers**; release name numbered within it
- `BUGLIST.md` and `RELEASES.md` are updated in the same commit as the change they describe
- **emulator smoke gate is mandatory before any APK is published** (see `TESTPLAN.md` L3):
  zero `FATAL EXCEPTION` and a live pid on `emulator-5584`. Green unit tests do not substitute.
- auto git: commit + push after every successful build

## Prompt sessions

Every session is documented in `prompts/YYYY-MM-DD_<slug>.md` with the required frontmatter
(`date`, `repo`, `status`, `resume`), committed and pushed. A session that is not written down did
not happen.

## Where the honest state lives

`docs/protocol-findings.md` is the single source of truth for what has and has not been established.
Update it from an exported report after a session — never from memory, and never by upgrading a
`NOT TESTED` row because a test was skipped.
