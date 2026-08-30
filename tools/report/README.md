# tools/report

`render_report.py` renders a MiraiCast Lab evidence file into the same 20-section Markdown report
the app produces, on a workstation.

It exists for two things the phone cannot do:

1. **re-render an old run** after the app has started a new one, and
2. **render a session that never reached the export screen** — which is exactly what happens when a
   vehicle session ends with the process being killed. The app appends each record to JSONL as it
   happens, so that file survives even when the export does not.

```bash
./render_report.py evidence/run-2026-08-30T12-00-00Z-a1b2c3d4.jsonl -o reports/session.md
./render_report.py reports/miraicastlab-a1b2c3d4.json               -o reports/session.md
```

Accepts both the app's JSON export and the raw JSONL. A torn final line — the signature of a killed
process — is reported on stderr and skipped, never dropped silently.

Grading is mechanical here for the same reason it is mechanical in the app: sections 15–18 are
derived from the status field, so `NOT_TESTED` cannot be rendered as `UNSUPPORTED` by a prose step.
