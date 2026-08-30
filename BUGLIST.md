# BUGLIST — MiraiCast Lab

Per project convention: every bug gets an id, a status, the version it was found in and the version
it was fixed in. Bugs come from what the tester reports, plus what verification finds.

| ID | Description | Status | Found in | Fixed in |
|---|---|---|---|---|
| MC-004 | Manifest declared `ACCESS_FINE_LOCATION` without `ACCESS_COARSE_LOCATION`; Android 12 requires both to be declared and requested together, so P2P discovery would have failed to obtain the permission on API 31–32. | FIXED | 0.1.0 (pre-release) | 0.1.0 |
| MC-003 | `SamsungSurface.p2pInterfacesUp()` passed the result of `NetworkInterface.getNetworkInterfaces()` straight to `Collections.list()`. That call returns **null**, not an empty enumeration, when the platform can list nothing — so the p2p check died with a bare NPE. It degraded to `ERROR` rather than crashing, but it mattered: a p2p* interface coming up is the strongest app-visible Miracast signal, and "we could not enumerate" must never read as "no p2p interface". Found on the emulator smoke run. | FIXED | 0.1.0 (pre-release) | 0.1.0 |
| MC-002 | `CodecProbe` read the encoder bitrate range from `MediaCodecInfo.EncoderCapabilities.bitrateRange`, which does not exist — `EncoderCapabilities` carries quality, complexity and bitrate *modes*; the supported bitrate range lives on `VideoCapabilities`/`AudioCapabilities`. | FIXED | 0.1.0 (pre-release) | 0.1.0 |
| MC-005 | `PresentationHostActivity` read `display?.displayId`; `ContextWrapper.getDisplay()` is API 30 while `minSdk` is 29, so this would have thrown on Android 10. Now version-guarded with `WindowManager.getDefaultDisplay()` below API 30. | FIXED | 0.1.0 (pre-release) | 0.1.0 |
| MC-001 | `import androidx.compose.foundation.lazy.item` does not exist; `item` is a `LazyListScope` member and needs no import. Broke the first skeleton build. | FIXED | 0.1.0 (pre-commit) | 0.1.0 |

## Open verification gaps (not bugs — untested surface)

| ID | Description | Blocked on |
|---|---|---|
| MC-V1 | No probe has run on a real Samsung Galaxy Z Fold. | Physical Z Fold |
| MC-V2 | No experiment has run against a Toyota Mirai 2025 head unit. | Physical vehicle |
| MC-V3 | Touch-back (UIBC or equivalent) has never been exercised. | Physical vehicle |
| MC-V4 | The Android Auto + Miracast coexistence matrix (rows A–F) is entirely unrun. | Physical vehicle |
