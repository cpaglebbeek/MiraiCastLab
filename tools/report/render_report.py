#!/usr/bin/env python3
"""
Render a MiraiCast Lab evidence file into the 20-section Markdown report, on a workstation.

The app generates the same report on the phone. This exists for two reasons the phone cannot serve:
you can re-render an old run after the app has moved on, and you can render the raw JSONL that the
app appends during a session even if the run never reached the export screen - which is exactly the
case when a vehicle session ends with the process being killed.

The grading rule is the same one the app enforces, and it is enforced here mechanically for the same
reason: sections 15-18 are derived from the status field, never written by hand.

    NOT_TESTED is never reported as UNSUPPORTED.

Usage:
    render_report.py evidence/run-2026-08-30T12-00-00Z-a1b2c3d4.jsonl  -o reports/run.md
    render_report.py reports/miraicastlab-a1b2c3d4.json                -o reports/run.md
"""

from __future__ import annotations

import argparse
import json
import sys
from collections import OrderedDict, defaultdict
from pathlib import Path

# Section number -> (title, the log categories that feed it)
SECTIONS = OrderedDict([
    (1,  ("Test environment", None)),
    (2,  ("Phone details", ["DEVICE"])),
    (3,  ("Vehicle/head-unit details entered by tester", ["USER"])),
    (4,  ("Android capabilities", ["DEVICE", "CODEC"])),
    (5,  ("Miracast observations", ["MIRACAST", "SMART_VIEW"])),
    (6,  ("Wi-Fi Direct observations", ["NETWORK"])),
    (7,  ("Display observations", ["DISPLAY"])),
    (8,  ("Audio observations", ["AUDIO"])),
    (9,  ("Touch/input findings", ["INPUT"])),
    (10, ("DeX findings", ["DEX"])),
    (11, ("Android Auto findings", ["ANDROID_AUTO"])),
    (12, ("Android Auto + Miracast coexistence matrix", ["ANDROID_AUTO"])),
    (13, ("MediaProjection findings", ["MEDIAPROJECTION"])),
    (14, ("Motion-state lab findings", ["MOTION_STATE"])),
    (15, ("Confirmed facts", None)),
    (16, ("Inferences", None)),
    (17, ("Unsupported features", None)),
    (18, ("Tests still requiring hardware", None)),
    (19, ("Raw evidence/log references", None)),
    (20, ("Conclusion", None)),
])

EMPTY = "_No observations were recorded for this section in this test run._"


def load(path: Path) -> tuple[list[dict], dict]:
    """Accepts either the app's JSON export or the raw append-only JSONL evidence file."""
    text = path.read_text(encoding="utf-8")
    if path.suffix == ".jsonl" or text.lstrip().startswith("{\"timestamp\""):
        records = []
        for n, line in enumerate(text.splitlines(), 1):
            line = line.strip()
            if not line:
                continue
            try:
                records.append(json.loads(line))
            except json.JSONDecodeError as e:
                # A killed process can leave a torn final line. Report it; never drop it silently.
                print(f"warning: line {n} is not valid JSON and was skipped ({e})", file=sys.stderr)
        meta = {"testRunId": records[0]["testRunId"] if records else "unknown",
                "startedAt": records[0]["timestamp"] if records else "unknown"}
        return records, meta

    doc = json.loads(text)
    return doc.get("records", []), {"testRunId": doc.get("testRunId", "unknown"),
                                    "startedAt": doc.get("startedAt", "unknown")}


def esc(s: str) -> str:
    """Escape a cell for a Markdown table. Pipes and newlines break the table silently."""
    return str(s).replace("|", "\\|").replace("\n", " ")


def table(records: list[dict]) -> str:
    if not records:
        return EMPTY
    out = ["| Event | Value | Status | Note | Time |", "|---|---|---|---|---|"]
    for r in records:
        d = r.get("details", {})
        value = d.get("value", "")
        if not value:
            value = "; ".join(f"{k}={v}" for k, v in d.items() if k != "seq")
        out.append("| {} | {} | `{}` | {} | {} |".format(
            esc(r.get("event", "")), esc(value), r.get("status", ""),
            esc(d.get("note", "")), esc(r.get("timestamp", ""))))
    return "\n".join(out)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("evidence", type=Path, help="a .jsonl evidence file or a .json export")
    ap.add_argument("-o", "--output", type=Path, help="write here instead of stdout")
    args = ap.parse_args()

    if not args.evidence.exists():
        print(f"error: {args.evidence} does not exist", file=sys.stderr)
        return 1

    records, meta = load(args.evidence)
    by_cat: dict[str, list[dict]] = defaultdict(list)
    by_status: dict[str, list[dict]] = defaultdict(list)
    for r in records:
        by_cat[r.get("category", "USER")].append(r)
        by_status[r.get("status", "ERROR")].append(r)

    L = []
    L.append("# MiraiCast Lab — test report")
    L.append("")
    L.append(f"Rendered by `tools/report/render_report.py` from `{args.evidence}`.")
    L.append("")
    L.append("> Grading is derived mechanically from the recorded status of each observation. "
             "`NOT_TESTED` means hardware or circumstances were unavailable and **no claim is "
             "made** — it is never reported as `UNSUPPORTED`.")
    L.append("")

    for num, (title, cats) in SECTIONS.items():
        L.append(f"## {num}. {title}")
        L.append("")

        if num == 1:
            L.append(f"- Test run id: `{meta['testRunId']}`")
            L.append(f"- Run started: `{meta['startedAt']}`")
            L.append(f"- Records: {len(records)}")
            L.append(f"- Evidence file: `{args.evidence}`")
            L.append("- Rendered off-device; the app renders the identical sections on the phone.")
        elif num == 12:
            rows = [r for r in records if "matrix" in r.get("event", "").lower()]
            L.append(table(rows) if rows else
                     "_No coexistence-matrix rows were recorded. Rows A–F remain `NOT_TESTED`._")
        elif num == 15:
            L.append(table(by_status.get("CONFIRMED", []) + by_status.get("OBSERVED", [])))
        elif num == 16:
            L.append(table(by_status.get("INFERRED", [])))
        elif num == 17:
            L.append(table(by_status.get("UNSUPPORTED", [])))
        elif num == 18:
            L.append(table(by_status.get("NOT_TESTED", [])))
            errs = by_status.get("ERROR", [])
            if errs:
                L.append("")
                L.append("### Probe failures — still untested")
                L.append("")
                L.append("A probe that failed says nothing about the capability it was measuring.")
                L.append("")
                L.append(table(errs))
        elif num == 19:
            L.append(f"- `{args.evidence}` — {len(records)} records")
            L.append("- Workstation dumpsys snapshots, if collected: `evidence/adb-*`")
            L.append("- Session logcat, if collected: `evidence/logcat-*.txt`")
        elif num == 20:
            c = len(by_status.get("CONFIRMED", [])) + len(by_status.get("OBSERVED", []))
            i = len(by_status.get("INFERRED", []))
            u = len(by_status.get("UNSUPPORTED", []))
            n = len(by_status.get("NOT_TESTED", []))
            e = len(by_status.get("ERROR", []))
            L.append(f"{c} facts confirmed or observed, {i} inferred, {u} capabilities evidenced as "
                     f"unsupported in this environment, {n} tests still requiring hardware"
                     + (f", {e} probe failures." if e else "."))
            L.append("")
            L.append("The `NOT_TESTED` items make **no claim**: they were not exercised, which is "
                     "not evidence that the capability is absent.")
        else:
            rows = []
            for c in (cats or []):
                rows.extend(by_cat.get(c, []))
            rows.sort(key=lambda r: r.get("timestamp", ""))
            L.append(table(rows))
        L.append("")

    out = "\n".join(L)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(out, encoding="utf-8")
        print(f"wrote {args.output} ({len(out)} bytes)")
    else:
        print(out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
