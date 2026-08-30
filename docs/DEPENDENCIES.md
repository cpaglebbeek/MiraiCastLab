# Dependencies

Every dependency has to earn its place. The app must build and run completely offline (spec §4), and
each library is build surface that can break a vehicle session.

## Runtime

| Dependency | Why it is here | What would break without it |
|---|---|---|
| `androidx.core:core-ktx` | `ContextCompat.checkSelfPermission`, `ContextCompat.registerReceiver` (mandatory export flag on API 33+), `FileProvider` | permission checks and the report share sheet |
| `androidx.lifecycle:lifecycle-runtime-ktx` / `-compose` / `-viewmodel-compose` | lifecycle-aware collection of `SessionLogger.records` | live log viewer and dashboard would leak or go stale |
| `androidx.activity:activity-compose` | `setContent`, `rememberLauncherForActivityResult` | the MediaProjection consent flow and every permission request |
| `androidx.compose:compose-bom` | pins all Compose artefacts to one consistent set | version skew between Compose modules |
| `compose.ui`, `ui-graphics`, `material3` | the whole UI, and `Canvas` for the diagnostic scene | — |
| `compose.material:material-icons-extended` | the back arrow and a handful of test icons | navigation affordance |
| `androidx.navigation:navigation-compose` | the flat 14-destination graph | one-tap navigation from the dashboard |
| `kotlinx-coroutines-android` | probes are `suspend`; capture and polling run off the main thread | the app would block during a scan |

**Nothing else.** In particular: no JSON library (`core/Json.kt` is 50 lines and dependency-free), no
`androidx.mediarouter` (the framework `android.media.MediaRouter` is what a plain app sees, and that
is exactly what we want to measure), no image loader, no analytics, no crash reporter, no network
client. The app has no `INTERNET` permission, so a network client could not work even if added.

## Build

| | Version | Note |
|---|---|---|
| JDK | 21 | `jvmTarget` is 17; 21 is the toolchain |
| Gradle | 8.11.1 (wrapper) | cached locally |
| Android Gradle Plugin | 8.7.3 | |
| Kotlin | 2.1.0 | Compose compiler is the `kotlin.plugin.compose` plugin, not a separate artefact |
| Compose BOM | 2024.12.01 | |
| compileSdk / targetSdk / minSdk | 35 / 35 / 29 | minSdk 29 is the floor for `AudioPlaybackCaptureConfiguration` and the mediaProjection FGS type |

## Test

| | Why |
|---|---|
| `junit:junit` | plain JVM assertions |
| `org.robolectric:robolectric` | lets the evidence-model and report-generator tests run on the JVM without a device |
| `androidx.test.ext:junit` | Robolectric's runner glue |

## Platform surfaces this project depends on (not libraries, but real dependencies)

| Surface | Risk if it changes |
|---|---|
| `DisplayManager` + `DISPLAY_CATEGORY_PRESENTATION` | the primary Miracast/DeX signal an app can see |
| `android.media.MediaRouter` | framework router; the androidx one would show a different picture |
| `WifiP2pManager` + `NEARBY_WIFI_DEVICES` | permission split at API 33 |
| `MediaProjection` + FGS type `mediaProjection` | Android 14 made `registerCallback` before `createVirtualDisplay` mandatory |
| `ActivityOptions.setLaunchDisplayId` | how the scene reaches the car screen; OEM behaviour varies |
| `UiModeManager.UI_MODE_TYPE_CAR` | the only authoritative Android Auto signal |
| `SystemProperties.ro.build.version.oneui` (reflection) | Samsung-proprietary; guarded, degrades to UNSUPPORTED |
| `Configuration.semDesktopModeEnabled` (reflection) | Samsung-proprietary; guarded, degrades to UNSUPPORTED |
