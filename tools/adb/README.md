# tools/adb

Read-only diagnostics for the developer workstation (spec section 15). None of these require root,
none change device or vehicle state, and each degrades gracefully when a `dumpsys` service does not
exist on the connected Android version.

| Script | Use |
|---|---|
| `collect-dumpsys.sh <label>` | One full snapshot of display / router / audio / wifi / p2p / input / usb / uimode state. Run it **before**, **during** and **after** a projection session; the diff is the finding. |
| `watch-session.sh` | Live filtered logcat for the whole session, wall-clock stamped so it aligns with the app's own ISO-8601 records. |
| `pull-evidence.sh` | Pulls the app's private `evidence/` and `reports/` folders off a debug build. |

Environment variables: `SERIAL` (target a specific device), `OUT_DIR` (default `evidence`),
`PKG` (default `nl.icthorse.miraicastlab.debug`).

Typical vehicle session:

```bash
./tools/adb/collect-dumpsys.sh before
./tools/adb/watch-session.sh &          # leave running
# ... tester works through the in-app Miracast wizard ...
./tools/adb/collect-dumpsys.sh during
# ... disconnect ...
./tools/adb/collect-dumpsys.sh after
kill %1
./tools/adb/pull-evidence.sh
```
