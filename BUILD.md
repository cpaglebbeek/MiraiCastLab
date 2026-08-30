# BUILD.md

## Toolchain that this project is known to build with

Measured on HorseCloud55 (Hetzner, Ubuntu 24.04) on 2026-08-30:

| Component | Version | Where |
|---|---|---|
| JDK | OpenJDK 21.0.12 | `/usr/lib/jvm/java-21-openjdk-amd64` |
| Android SDK platform | android-35 | `~/Android/Sdk/platforms` |
| Build tools | 35.0.0 (34.0.0 also present) | `~/Android/Sdk/build-tools` |
| Gradle | 8.11.1 (wrapper) | `gradle/wrapper` |
| Android Gradle Plugin | 8.7.3 | `gradle/libs.versions.toml` |
| Kotlin | 2.1.0 | `gradle/libs.versions.toml` |
| Compose BOM | 2024.12.01 | `gradle/libs.versions.toml` |

`compileSdk 35`, `targetSdk 35`, `minSdk 29`.

> **Correction to earlier project metadata:** `Meta_Master/PROJECTS.json` recorded for the
> `Meta_Auto` ecosystem that *"HC55 heeft geen SDK"* and that Android builds must happen on the Mac.
> That is no longer true and was verified false on 2026-08-30: HC55 builds this project end to end.

## Build

```bash
cd ~/projects/MiraiCastLab
export ANDROID_HOME=~/Android/Sdk
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64

./gradlew :app:assembleDebug          # debug APK
./gradlew :app:testDebugUnitTest      # JVM unit tests
./gradlew :app:lintDebug              # lint
./gradlew :app:assembleRelease        # unsigned release APK
```

Outputs land in `app/build/outputs/apk/<variant>/`.

The debug build gets `applicationIdSuffix ".debug"`, so a debug and a release build can be installed
side by side on the same phone. That matters during a vehicle session: you can keep a known-good
build installed while trying a new one.

## Install on the Galaxy Z Fold

```bash
adb install -r -d app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n nl.icthorse.miraicastlab.debug/nl.icthorse.miraicastlab.MainActivity
```

## Emulator smoke gate (mandatory before publishing an APK)

Green unit tests do not prove an app starts — manifest semantics are only checked on a device.

```bash
APK=app/build/outputs/apk/debug/app-debug.apk
scp -i ~/.ssh/horseboat_hetzner "$APK" root@95.216.46.120:/tmp/miraicastlab.apk
ssh -i ~/.ssh/horseboat_hetzner root@95.216.46.120 '
  D=emulator-5584
  adb -s $D install -r -d /tmp/miraicastlab.apk
  adb -s $D logcat -c
  adb -s $D shell am start -n nl.icthorse.miraicastlab.debug/nl.icthorse.miraicastlab.MainActivity
  sleep 8
  echo "FATAL count: $(adb -s $D logcat -d -b crash | grep -c "FATAL EXCEPTION")"
  echo "pid: $(adb -s $D shell pidof nl.icthorse.miraicastlab.debug)"
'
```

Green = `FATAL count: 0` **and** a live pid.

## Pulling evidence off the phone

Evidence and reports are written to app-private storage. With a debug build:

```bash
adb exec-out run-as nl.icthorse.miraicastlab.debug tar c files/evidence files/reports > lab-evidence.tar
tar xf lab-evidence.tar
```

Or use the in-app share sheet, which exposes the same files through the FileProvider.

## Offline behaviour

The app has **no `INTERNET` permission**. Gradle needs the network for dependency resolution on a
cold cache; the app itself never does.
