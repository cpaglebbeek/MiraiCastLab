#!/usr/bin/env bash
# Streams the logcat lines that matter during a projection session into one timestamped file.
#
# Run this in a terminal while the tester works through the in-app wizard. It captures the app's own
# structured events plus the platform's display/wifi/media chatter, so the app log and the platform
# log can be aligned afterwards on wall-clock time.
set -euo pipefail

SERIAL="${SERIAL:-}"
ADB=(adb); [ -n "$SERIAL" ] && ADB=(adb -s "$SERIAL")

STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
OUT="${OUT_DIR:-evidence}/logcat-${STAMP}.txt"
mkdir -p "$(dirname "$OUT")"

"${ADB[@]}" logcat -c || true
echo "Writing to $OUT   (Ctrl-C to stop)"

# -v threadtime gives wall clock, which is what correlates with the app's ISO-8601 records.
"${ADB[@]}" logcat -v threadtime \
  MiraiCastLab:V \
  DisplayManagerService:V DisplayManager:V \
  WifiP2pService:V wpa_supplicant:V WifiDisplayController:V WifiDisplayAdapter:V \
  MediaRouterService:V MediaProjectionManagerService:V \
  AudioService:V AudioFlinger:W \
  InputReader:V InputDispatcher:W \
  CarModeManager:V UiModeManager:V \
  ActivityTaskManager:W \
  '*:S' | tee "$OUT"
