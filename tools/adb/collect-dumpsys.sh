#!/usr/bin/env bash
# Collects the Android-side evidence that the app itself cannot read, into one evidence folder.
#
# Everything here is a read-only diagnostic. No root, no state change, no vehicle interaction.
# Run it three times during a vehicle session: before connecting, while connected, after
# disconnecting. The diff between the three is usually where the answer is.
set -euo pipefail

SERIAL="${SERIAL:-}"
ADB=(adb)
[ -n "$SERIAL" ] && ADB=(adb -s "$SERIAL")

LABEL="${1:-snapshot}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
OUT="${OUT_DIR:-evidence}/adb-${STAMP}-${LABEL}"
mkdir -p "$OUT"

if ! "${ADB[@]}" get-state >/dev/null 2>&1; then
  echo "No device. Check 'adb devices' and that USB debugging is authorised." >&2
  exit 1
fi

echo "Collecting into $OUT"

# Identity first, so a folder is never ambiguous later.
{
  echo "collected_at_utc=$STAMP"
  echo "label=$LABEL"
  for p in ro.product.manufacturer ro.product.model ro.build.version.release \
           ro.build.version.sdk ro.build.version.oneui ro.build.fingerprint; do
    printf '%s=%s\n' "$p" "$("${ADB[@]}" shell getprop "$p" | tr -d '\r')"
  done
} > "$OUT/device.properties"

# Each dumpsys is optional: an Android version that lacks one must not abort the run.
dump() {
  local svc="$1" file="$2"
  if "${ADB[@]}" shell dumpsys "$svc" > "$OUT/$file" 2>"$OUT/$file.err"; then
    [ -s "$OUT/$file.err" ] || rm -f "$OUT/$file.err"
    echo "  ok   $svc"
  else
    echo "  MISS $svc (not available on this Android version)"
  fi
}

dump display        display.txt
dump media_router   media_router.txt
dump audio          audio.txt
dump connectivity   connectivity.txt
dump wifi           wifi.txt
dump wifip2p        wifip2p.txt
dump input          input.txt
dump media_projection media_projection.txt
dump window         window.txt
dump activity       activity.txt
dump usb            usb.txt
dump uimode         uimode.txt

# Package state for the projection-relevant apps only. No full package dump: it is enormous and
# contains far more about the user than this project needs.
for pkg in com.samsung.android.smartmirroring com.sec.android.app.desktoplauncher \
           com.samsung.desktopsystemui com.samsung.android.mdx \
           com.google.android.projection.gearhead nl.icthorse.miraicastlab \
           nl.icthorse.miraicastlab.debug; do
  "${ADB[@]}" shell dumpsys package "$pkg" > "$OUT/package-$pkg.txt" 2>/dev/null || true
  [ -s "$OUT/package-$pkg.txt" ] || rm -f "$OUT/package-$pkg.txt"
done

# Network interfaces: p2p0 appearing is the strongest app-visible Wi-Fi Direct signal.
"${ADB[@]}" shell ip -o addr    > "$OUT/ip-addr.txt"    2>/dev/null || true
"${ADB[@]}" shell ip -o link    > "$OUT/ip-link.txt"    2>/dev/null || true

echo "Done. $(find "$OUT" -type f | wc -l) files in $OUT"
