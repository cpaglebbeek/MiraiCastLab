---
date: 2026-08-30
repo: MiraiCastLab
status: pending
resume: "MiraiCast Lab v0.1.0 staat; alles vehicle-side is NOT TESTED. Hervatten = APK op de Z Fold zetten en de Mirai-sessie draaien volgens docs/toyota-mirai-2025-test-procedure.md"
---

# Sessie — one-shot build MiraiCast Lab

## Vraag

Christian uploadde `MiraiCast_Lab_Claude_OneShot_v1.0.md`: een volledige build-spec voor een Android
capability explorer die empirisch vaststelt wat er tussen een non-rooted Samsung Galaxy Z Fold en een
Toyota Mirai 2025 mogelijk is — beeld, geluid, en touch-terug (Miracast / Smart View / DeX /
Android Auto). Daarna: **"bouw alles. ulttacode"** (typefout voor *ultracode* = multi-agent
orchestratie).

## Conflict dat eerst opgelost moest worden

De spec zegt in §23 letterlijk *"Do not ask for permission between these steps"*. Het WhatIf-protocol
in `~/CLAUDE.md` zegt: nooit bouwen zonder akkoord vooraf. Opgelost door **één** gate vooraf
(WhatIf + drie beslispunten) en daarna ononderbroken doorwerken door alle 14 stappen. Kandidaat voor
een `feedback_`-memory; niet vastgelegd zonder akkoord van Christian.

## Beslispunten en antwoorden

| Vraag | Keuze |
|---|---|
| Scope vastlegging | Volledig newp — repo + GitHub + PROJECTS.json/ECOSYSTEMS.md/STATUS.md + memory + `/verifyrules` fase 0 + `/sanitycheck` als laatste fase |
| Package-id | `nl.icthorse.miraicastlab` (huisconventie, niet het `nl.meta.*` uit de spec) |
| Repo | Public + AGPL-3.0, zoals Android2AndroidMirror in hetzelfde ecosysteem |

## Fase 0 — /verifyrules

Naleefrapport gedraaid vóór er iets aangemaakt werd. Bevindingen:

- ❌ statusblok ontbrak in de eerste twee antwoorden → hersteld vanaf antwoord 3
- ❌ prompt-sessie ontbrak → dit bestand
- ⚠ 5 parallelle Claude-sessies gemeten (`ps`) → mitigatie: `git pull --rebase` vóór elke
  Meta_Master-push, nooit force-push
- ✅ Meta_Master gepulld (SessionStart-hook), WhatIf gevolgd, zsh-path-veiligheid, shared-infra n.v.t.
  (pure Android-app, geen poort/nginx/systemd)

## Omgeving — gemeten, niet aangenomen

| | |
|---|---|
| JDK | OpenJDK 21.0.12 |
| Android SDK | `~/Android/Sdk`, platform android-35, build-tools 34/35 |
| Gradle wrapper-dists in cache | 8.11.1 / 8.9 / 8.7 / 8.4 |
| AGP / Kotlin in cache | 8.7.3 / 2.1.0 |
| Aangesloten toestel | **geen** (`adb devices` leeg) |
| Emulators | HorseBoat-box: `emulator-5584` (A16), `emulator-5554` (A14) |

**Correctie op de projectadministratie:** `PROJECTS.json` had bij ecosysteem Meta_Auto staan
`"build_host": "Mac (Android SDK; HC55 heeft geen SDK)"`. Dat is aantoonbaar onjuist — HC55 bouwt dit
project end-to-end. Aangepast.

## Aanpak

1. **Skelet + bevroren contracten eerst, met de hand.** `core/` (LabStatus, LabCategory, Observation,
   LogRecord, SessionLogger, Probe, LabPermissions, DashboardKeys, ProbeRegistry, dependency-vrije
   Json), `ui/` (LabTheme, LabComponents, LabNavHost, DashboardScreen), plus 12 probe-stubs en 13
   screen-stubs die `NOT_TESTED` teruggeven. Baseline geverifieerd met `assembleDebug` vóór de
   fan-out — een kapotte baseline zou tien agents laten falen.
2. **Fan-out (ultracode).** Eén workflow, tien agents, één per module, met disjuncte bestandssets en
   het volledige contract in de prompt. Agents draaien géén gradle: ze delen dezelfde working tree,
   dus een build van agent A zou de half-geschreven bestanden van agent B compileren. Integratiebuild
   doet de orchestrator.
3. **Docs parallel**, in paden die geen agent aanraakt.

## Kernontwerpbeslissing

De hele app hangt aan één invariant uit §20 van de spec: **`NOT_TESTED` mag nooit `UNSUPPORTED`
worden.** Dat is structureel afgedwongen in plaats van als afspraak:

- aparte enum-constanten, geen enkele functie die de een op de ander afbeeldt;
- de rapportsecties 15–18 worden **mechanisch** uit de enum afgeleid, dus geen proza-stap kan het
  vervagen;
- `Probe.safeObserve` maakt van elke throwable een `ERROR`-observatie met de notitie *"the capability
  itself is untested"* — een crashende probe wordt nooit een negatief resultaat;
- `DashboardKeys.missingFrom()` maakt een ontbrekend veld zichtbaar als gat in plaats van als leegte.

## Veiligheidsgrens

`SAFETY.md` is structureel gemaakt, niet als beleid: **geen `INTERNET`-permissie** (uploaden is
onmogelijk, niet slechts verboden) en **geen voertuigbus-code** (interlock-interferentie is onmogelijk,
niet slechts ongewenst). De motion-state-test is louter waarnemen + tester-markers.

## Wat dit oplevert, en wat niet

Software compleet aan de telefoonkant. **Elk vehicle-side resultaat is `NOT TESTED`** — er is hier
geen Z Fold en geen Mirai. Zie `docs/protocol-findings.md` §3.

Bovendien vastgelegd als *bevinding*, niet als omissie: een non-rooted third-party app kan de
onderhandelde resolutie, framerate, codec, HDCP-status en UIBC-advertentie van een Wi-Fi Display-sessie
**structureel niet** lezen (§1 van protocol-findings). De beantwoordbare vorm van de UIBC-vraag is
empirisch: *kwam er input binnen toen ik het Mirai-scherm aanraakte?*

## Hervatten

APK op de Z Fold, dan `docs/toyota-mirai-2025-test-procedure.md` afwerken in een stilstaande auto.
Resultaten terugschrijven in `docs/protocol-findings.md` vanuit het geëxporteerde rapport — nooit uit
het hoofd, en nooit door een `NOT TESTED`-rij te promoveren omdat een test is overgeslagen.
