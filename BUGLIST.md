# BUGLIST — MiraiCast Lab

Per project convention: every bug gets an id, a status, the version it was found in and the version
it was fixed in. Bugs come from what the tester reports, plus what verification finds.

| ID | Description | Status | Found in | Fixed in |
|---|---|---|---|---|
| MC-002 | `CodecProbe` read the encoder bitrate range from `MediaCodecInfo.EncoderCapabilities.bitrateRange`, which does not exist — `EncoderCapabilities` carries quality, complexity and bitrate *modes*; the supported bitrate range lives on `VideoCapabilities`/`AudioCapabilities`. | FIXED | 0.1.0 (pre-release) | 0.1.0 |
| MC-001 | `import androidx.compose.foundation.lazy.item` does not exist; `item` is a `LazyListScope` member and needs no import. Broke the first skeleton build. | FIXED | 0.1.0 (pre-commit) | 0.1.0 |

## Open verification gaps (not bugs — untested surface)

| ID | Description | Blocked on |
|---|---|---|
| MC-V1 | No probe has run on a real Samsung Galaxy Z Fold. | Physical Z Fold |
| MC-V2 | No experiment has run against a Toyota Mirai 2025 head unit. | Physical vehicle |
| MC-V3 | Touch-back (UIBC or equivalent) has never been exercised. | Physical vehicle |
| MC-V4 | The Android Auto + Miracast coexistence matrix (rows A–F) is entirely unrun. | Physical vehicle |
