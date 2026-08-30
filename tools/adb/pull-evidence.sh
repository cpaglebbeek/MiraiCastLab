#!/usr/bin/env bash
# Pulls the app's own evidence and report files off the phone.
#
# Works without root because the app is debuggable: run-as reads its private storage.
# For a release build, use the in-app share sheet instead.
set -euo pipefail

SERIAL="${SERIAL:-}"
ADB=(adb); [ -n "$SERIAL" ] && ADB=(adb -s "$SERIAL")
PKG="${PKG:-nl.icthorse.miraicastlab.debug}"
OUT="${OUT_DIR:-evidence}/device-$(date -u +%Y%m%dT%H%M%SZ)"

mkdir -p "$OUT"
echo "Pulling $PKG private storage into $OUT"

if ! "${ADB[@]}" shell run-as "$PKG" ls files >/dev/null 2>&1; then
  echo "run-as failed for $PKG." >&2
  echo "That means the installed build is not debuggable. Use the in-app EXPORT/SHARE buttons." >&2
  exit 1
fi

"${ADB[@]}" exec-out run-as "$PKG" tar c files/evidence files/reports 2>/dev/null > "$OUT/private.tar"
tar xf "$OUT/private.tar" -C "$OUT"
rm -f "$OUT/private.tar"

echo "Done:"
find "$OUT" -type f -printf '  %p  (%s bytes)\n'
