# architectuur/

| File | What it is |
|---|---|
| `MiraiCastLab_viewer.html` | standalone interactive architecture viewer — open it directly over `file://`, no server, no network |
| `MiraiCastLab_archdsl.dsl` | the same model as ArchDSL source, Dragon1-compatible |

## What is in the model

154 elements, 203 relations, 5 views, 5 animated scenarios (82 steps).

| View | Elements | Relations | Built from |
|---|---|---|---|
| Conceptueel | 48 | 37 | `README.md`, `docs/architecture.md` §1/§3/§7, `docs/PRINCIPLES.md` (P1–P12), `SAFETY.md` |
| Logisch | 22 | 45 | `docs/architecture.md` §2/§4.1, `ARCHITECTURE.md`, `core/ProbeRegistry.kt`, `ui/LabNavHost.kt` |
| Fysiek | 24 | 34 | `AndroidManifest.xml`, `ARCHITECTURE.md`, `BUILD.md`, `docs/DEPENDENCIES.md`, `tools/` |
| Transacties | 41 | 61 | `docs/architecture.md` §5/§4.5, `core/Probe.kt`, `projection/CaptureEngine.kt`, `report/` |
| Journeys | 32 | 35 | `docs/toyota-mirai-2025-test-procedure.md` §0–§8, `SAFETY.md` |

Scenarios: `scan_chain` (24 steps, including the `safeObserve` failure path — a throwable becomes an
ERROR observation, not a negative result), `projection_chain` (12, with the Android 14 ordering
constraint taken verbatim from the `CaptureEngine` KDoc), `report_chain` (10),
`miracast_wizard` (13), `in_car_session` (23).

## Verified on 2026-08-30

Independently checked, not taken on trust:

| Check | Result |
|---|---|
| External `src=` / `href=` of any kind | **0** — the only `http://` strings are XML namespace URIs |
| `Date.now` / `Math.random` anywhere in the file | **0** |
| Model JSON parses | yes |
| Five views render distinctly | yes — 33 to 78 boxes each, all different |
| JS errors on load and on every view switch | none |
| Network requests when opened over `file://` | none |
| Animation controls | ▶ ❚❚ ⏭ 🔁 present and working; step narrative renders |
| Notation switch ArchiMate ↔ Dragon1 | works, model unchanged |
| DSL: relations carrying `:type` | 0 |
| DSL: lowercase types | 0 |
| DSL attribute keys | only `-attr` and `-description` |
| Elements without a repo source reference | **0** |

## Known limitation — edge routing on the dense views

Relations are drawn as straight lines with the label at the midpoint. On **Logisch** (45 relations
over 22 elements) and **Transacties** (61 over 41) this produces crossing lines, labels that sit on
top of boxes, and a few labels clipped at box edges. The content is correct and every element is
readable; the *wiring* is harder to follow than it should be.

Hover over any element or relation for its full text and its repo source — that is the reliable way
to read the dense views today. Improving this needs orthogonal edge routing with lane assignment and
label collision avoidance in the shared viewer template, which is a change to
`Meta_Master/templates/viewer_template.html` rather than to this repo.

## Two «TBD» markers — deliberate

1. `Samsung Galaxy Z Fold «TBD: exact model»` — no generation is recorded anywhere in the repo, and
   `docs/protocol-findings.md` 2.1 has the model as `NOT TESTED`.
2. `Toyota Mirai 2025 head unit «TBD: software version»` — the tester enters this in the report form;
   no value exists yet.

Both are visible gaps on purpose. Filling them in with a plausible guess is exactly the failure this
project is built to avoid.

## Not modelled

The manifest's `<queries>` block (Samsung / Android Auto / root package names, four settings-intent
actions) and the permission list are not separate elements — roughly 30 leaf nodes that would bury
the Fysiek view. Their *consequence* is modelled, as the Journeys decision point
"Does the settings intent resolve?" (resolvable = CONFIRMED, unresolvable = UNSUPPORTED).
