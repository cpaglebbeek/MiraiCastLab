# MiraiCast Lab - Architecture — ArchDSL (DSL-B), Dragon1-compatible.
# Types are strict PascalCase. Relations carry no :type — the engine infers it from
# the endpoint types. Only the shared attribute keys are used:
#   -fill  -stroke  -color  -icon  -description  -attr key = value
# Top-level keywords: set / pos / Viewpoint / Slide only.
# Sources: docs/architecture.md, docs/DEPENDENCIES.md, docs/PRINCIPLES.md, SAFETY.md, docs/toyota-mirai-2025-test-procedure.md, README.md, ARCHITECTURE.md, BUILD.md, AndroidManifest.xml, core/ProbeRegistry.kt, ui/LabNavHost.kt

set language archimate
set title "MiraiCast Lab - Architecture"
set subtitle "Z Fold x Toyota Mirai 2025 capability explorer - v0.1.0 Mirai-1"

# ---------------------------------------------------------------- elements
Driver dr_unknown "Unknown: what actually works Z Fold to Mirai 2025" { -description "An Android capability explorer that finds out - empirically - what actually works between a non-rooted Samsung Galaxy Z Fold and a Toyota Mirai 2025 head unit." -attr src = "README.md (opening claim)" }
Driver dr_false "A false result is the thing to avoid" { -description "Pretending to connect would be the single easiest way to produce a false result." -attr src = "docs/architecture.md §7 (non-goals)" }
Assessment as_state "v0.1.0 Mirai-1: not yet run on a Z Fold, not yet in a vehicle" { -description "Every vehicle-side result in this repository is NOT TESTED. That is the honest state." -attr src = "README.md + version.json + docs/protocol-findings.md" }
Goal go_empirical "Empirical answer: picture, sound, touch back" { -description "Picture - sound - touch back. Miracast, Smart View, DeX, Android Auto." -attr src = "README.md" }
Goal go_experiments "Turn the question into graded experiments" { -description "Turn 'can the Z Fold show video, play sound and receive touch on a Toyota Mirai 2025?' into a set of experiments whose results are graded by how strongly they are evidenced." -attr src = "docs/architecture.md §1" }
Goal go_evidence "The output is an evidence file, not a picture" { -description "The app is an instrument, not a media player. The picture on the car screen is a stimulus; the output is an evidence file." -attr src = "docs/architecture.md §1" }
Requirement rq_grade "Every fact carries a LabStatus" { -description "There is no code path that produces an ungraded value into the report." -attr src = "docs/PRINCIPLES.md P1 · docs/architecture.md §3" }
Requirement rq_mechanical "Report sections 15-18 derived mechanically from the enum" { -description "NOT_TESTED and UNSUPPORTED are distinct enum constants and no code maps one to the other, so no human can quietly promote one." -attr src = "docs/architecture.md §3 (rule 1) · report/ReportGenerator.kt" }
Requirement rq_safeobserve "A probe crash becomes Observation.error via safeObserve" { -description "safeObserve converts any throwable into Observation.error whose note says 'the capability itself is untested'." -attr src = "docs/architecture.md §3 (rule 2) · core/Probe.kt" }
Requirement rq_missing "A missing dashboard field is visible (DashboardKeys.missingFrom)" { -description "A blank field is an alarm, not an absence of news." -attr src = "docs/architecture.md §3 (rule 3)" }
Requirement rq_sink "Explicit NOT_TESTED: phone data says nothing about the sink" { -description "Modules that could tempt you (display, codec, projection) emit an explicit NOT_TESTED observation." -attr src = "docs/architecture.md §3 (rule 4) · docs/PRINCIPLES.md P3" }
Principle p1 "P1 - A finding without a grade is not a finding" { -description "Every fact carries a LabStatus. If you cannot say how strongly something is evidenced, you have not finished measuring it." -attr src = "docs/PRINCIPLES.md P1" }
Principle p2 "P2 - NOT_TESTED is not a weaker UNSUPPORTED" { -description "'We could not run the test' and 'the test showed it is absent' are different claims about the world. No function maps one to the other." -attr src = "docs/PRINCIPLES.md P2" }
Principle p3 "P3 - Phone-side capability says nothing about the sink" { -description "The Z Fold having eight H.264 encoders tells you nothing about what the Mirai accepts." -attr src = "docs/PRINCIPLES.md P3" }
Principle p4 "P4 - A probe crash is data, not an outage" { -description "safeObserve converts any throwable into an ERROR observation. A probe may never take the app down." -attr src = "docs/PRINCIPLES.md P4" }
Principle p5 "P5 - Absence of evidence is stated as such" { -description "Root not detected is INFERRED, not CONFIRMED. Package-visibility failures on Android 11+ are INFERRED/NOT_TESTED, never UNSUPPORTED." -attr src = "docs/PRINCIPLES.md P5" }
Principle p6 "P6 - The app instruments; it does not pretend to connect" { -description "Establishing a Wi-Fi Display session is not available to a third-party app on a non-rooted device. The app opens the right settings panel, then measures what changed." -attr src = "docs/PRINCIPLES.md P6" }
Principle p7 "P7 - The safety boundary is structural, not a policy" { -description "No INTERNET permission, so uploading is impossible rather than merely forbidden. No vehicle-bus code, so interlock interference is impossible rather than merely disallowed." -attr src = "docs/PRINCIPLES.md P7" }
Principle p8 "P8 - Evidence is written as it happens" { -description "Append-only JSONL, flushed per record. The moment evidence matters most is a vehicle session, which is exactly when the process is most likely to be killed." -attr src = "docs/PRINCIPLES.md P8" }
Principle p9 "P9 - The tester is in a car" { -description "Large targets, one tap from the dashboard to any experiment, one tap back. No ADB required to run a session." -attr src = "docs/PRINCIPLES.md P9" }
Principle p10 "P10 - Modules do not import each other" { -description "Everything depends on core. ProbeRegistry and LabNavHost are the only places that know about many modules, and they hold references, not logic." -attr src = "docs/PRINCIPLES.md P10" }
Principle p11 "P11 - Offline, dependency-light, no assets" { -description "The diagnostic scene is generated in code. The tones are synthesised in code. Nothing here needs the network, a media file, or a licence from anyone." -attr src = "docs/PRINCIPLES.md P11" }
Principle p12 "P12 - Comment the why" { -description "Notes on an Observation explain why a status is what it is, because in six months that note is the only thing standing between a reader and a wrong conclusion." -attr src = "docs/PRINCIPLES.md P12" }
Constraint cs_rule "No defeating, patching, bypassing, spoofing or disabling safety interlocks" { -description "Taken verbatim from section 1 of the build specification; binding on every module, every future change, every contributor - human or AI." -attr src = "SAFETY.md 'The rule'" }
Constraint cs_can "No CAN bus message injection" { -description "Directly manipulates vehicle state." -attr src = "SAFETY.md forbidden table" }
Constraint cs_spoof "No vehicle-state spoofing (park / drive / speed)" { -description "Defeats the interlock by lying to it." -attr src = "SAFETY.md forbidden table" }
Constraint cs_firmware "No patching or replacing head-unit firmware" { -description "Defeats the interlock by removing it." -attr src = "SAFETY.md forbidden table" }
Constraint cs_aaspoof "No Android Auto protocol spoofing" { -description "Section 3 of the spec forbids it explicitly." -attr src = "SAFETY.md forbidden table" }
Constraint cs_aacat "No circumventing Android Auto app-category restrictions" { -attr src = "SAFETY.md forbidden table" }
Constraint cs_bus "No code path that transmits to a vehicle bus" { -description "No legitimate use in a capability explorer." -attr src = "SAFETY.md forbidden table" }
Constraint cs_evasion "No detection evasion of any kind" { -description "Nothing here needs to hide." -attr src = "SAFETY.md forbidden table" }
Constraint cs_internet "No INTERNET permission - uploading is impossible by construction" { -attr src = "SAFETY.md 'Privacy and data' · AndroidManifest.xml (no INTERNET)" }
Constraint cs_private "All evidence in app-private storage only" { -description "filesDir/evidence and filesDir/reports. No personal data is collected." -attr src = "SAFETY.md 'Privacy and data'" }
Constraint cs_bench "Bench, simulator or stationary vehicle only" { -description "Not on a public road, and not while driving." -attr src = "SAFETY.md 'Where testing belongs'" }
Constraint cs_root "No root, and no accessibility service as a workaround" { -attr src = "SAFETY.md 'Privacy and data' · docs/architecture.md §7" }
Constraint cs_escal "Escalation: record the limitation as a finding and stop" { -description "If a future change appears to need something on the forbidden list, the correct outcome is to record the limitation as a finding - not to route around it." -attr src = "SAFETY.md 'Escalation'" }
Constraint cs_novehicle "Non-goal: no vehicle interaction" { -description "See SAFETY.md." -attr src = "docs/architecture.md §7" }
Constraint cs_nomiracast "Non-goal: no re-implementation of Miracast" { -description "Establishing a Wi-Fi Display session is not available to a third-party app on a non-rooted Android device. The app guides and instruments; it does not connect." -attr src = "docs/architecture.md §7" }
Constraint cs_nohidden "Non-goal: no hidden-API dependence in the primary path" { -description "Where a proprietary surface is probed it is reflective, guarded, isolated, and degrades to UNSUPPORTED/NOT_TESTED - never to a crash and never to a guess." -attr src = "docs/architecture.md §7" }
Capability cap_inventory "Device capability inventory (12 read-only probes)" { -attr src = "README.md 'What it does' · core/ProbeRegistry.kt" }
Capability cap_wizard "Guided Miracast session (seven-step wizard)" { -attr src = "README.md · docs/toyota-mirai-2025-test-procedure.md §2" }
Capability cap_scene "Diagnostic scene on an external display" { -attr src = "README.md · docs/architecture.md §4.4" }
Capability cap_audio "Audio characterisation (six modes)" { -attr src = "README.md · test procedure §4" }
Capability cap_touchback "Touch-back measurement (3x5 target grid)" { -attr src = "README.md · test procedure §3" }
Capability cap_projection "MediaProjection measurement (VirtualDisplay + H.264)" { -attr src = "README.md · docs/architecture.md §4.5" }
Capability cap_matrix "Android Auto coexistence matrix A-F" { -attr src = "README.md · test procedure §6" }
Capability cap_motion "Motion-state observation (recording only)" { -attr src = "README.md · SAFETY.md 'motion-state experiment'" }
Capability cap_report "Graded reporting: 20 sections, Markdown / JSON / CSV" { -attr src = "README.md · report/ReportGenerator.kt" }
ApplicationComponent mod_core "core - the contract everything depends on" { -description "LabStatus, LabCategory, Observation, LogRecord, SessionLogger, Probe, LabPermissions, Json, DashboardKeys, ProbeRegistry." -attr src = "docs/architecture.md §2 · ARCHITECTURE.md Units (8 files)" }
ApplicationComponent c_evidence "Evidence.kt - LabStatus, LabCategory" { -description "Depends on nothing; depended on by everything." -attr src = "docs/architecture.md §4.1" }
ApplicationComponent c_probe "Probe / safeObserve / probeValue" { -description "The read-only probe contract and its failure discipline. Used by all 12 probes." -attr src = "docs/architecture.md §4.1 · core/Probe.kt" }
ApplicationComponent c_sessionlogger "SessionLogger - the single sink" { -description "In-memory StateFlow plus append-only JSONL on disk. One process, one log." -attr src = "docs/architecture.md §4.1" }
ApplicationComponent c_proberegistry "ProbeRegistry - the ordered probe list" { -description "runAll with progress. One of the two places that legitimately knows about many modules." -attr src = "docs/architecture.md §4.1 · core/ProbeRegistry.kt" }
ApplicationComponent c_labpermissions "LabPermissions" { -description "Optional-permission model with a tester-facing reason per permission. Used by net, audio, projection, input." -attr src = "docs/architecture.md §4.1" }
ApplicationComponent c_dashboardkeys "DashboardKeys" { -description "The probe-to-dashboard contract, plus gap detection." -attr src = "docs/architecture.md §4.1" }
ApplicationComponent c_json "Json - dependency-free serialisation" { -description "core/Json.kt is 50 lines and dependency-free; no JSON library is pulled in." -attr src = "docs/architecture.md §4.1 · docs/DEPENDENCIES.md" }
DataObject do_observation "Observation(key, value, status, note, category)" { -description "Every fact the app produces is an Observation carrying its own epistemic status." -attr src = "docs/architecture.md §3" }
DataObject do_logrecord "LogRecord(timestamp, elapsedRealtimeMs, testRunId, category, event, status, details)" { -attr src = "README.md 'Structured evidence' · docs/architecture.md §4.1" }
ApplicationComponent mod_scan "scan - device and codec inventory (4 files)" { -description "Arrows point into core only. No module imports another module's implementation." -attr src = "docs/architecture.md §2 · ARCHITECTURE.md Units" }
ApplicationComponent mod_display "display - topology, MediaRouter, live listener (4)" { -description "Arrows point into core only. No module imports another module's implementation." -attr src = "docs/architecture.md §2 · ARCHITECTURE.md Units" }
ApplicationComponent mod_net "net - Wi-Fi Direct, connectivity, interfaces (4)" { -description "Arrows point into core only. No module imports another module's implementation." -attr src = "docs/architecture.md §2 · ARCHITECTURE.md Units" }
ApplicationComponent mod_audio "audio - routes, synthesised tones, A/V pulse (3)" { -description "Arrows point into core only. No module imports another module's implementation." -attr src = "docs/architecture.md §2 · ARCHITECTURE.md Units" }
ApplicationComponent mod_input "input - event monitor, touch-back grid (4)" { -description "Arrows point into core only. No module imports another module's implementation." -attr src = "docs/architecture.md §2 · ARCHITECTURE.md Units" }
ApplicationComponent mod_projection "projection - capture, VirtualDisplay, H.264, FGS (4)" { -description "Arrows point into core only. No module imports another module's implementation." -attr src = "docs/architecture.md §2 · ARCHITECTURE.md Units" }
ApplicationComponent mod_scene "scene - diagnostic pattern, controls, presentation host (5)" { -description "Arrows point into core only. No module imports another module's implementation." -attr src = "docs/architecture.md §2 · ARCHITECTURE.md Units" }
ApplicationComponent mod_samsung "samsung - Smart View and DeX surfaces, Miracast wizard (5)" { -description "Arrows point into core only. No module imports another module's implementation." -attr src = "docs/architecture.md §2 · ARCHITECTURE.md Units" }
ApplicationComponent mod_auto "auto - Android Auto matrix, motion-state observation (4)" { -description "Arrows point into core only. No module imports another module's implementation." -attr src = "docs/architecture.md §2 · ARCHITECTURE.md Units" }
ApplicationComponent mod_report "report - 20-section generator, exports, live log viewer (3)" { -description "Arrows point into core only. No module imports another module's implementation." -attr src = "docs/architecture.md §2 · ARCHITECTURE.md Units" }
ApplicationComponent mod_ui "ui - theme, shared components, dashboard (4)" { -attr src = "ARCHITECTURE.md Units" }
ApplicationComponent c_labnavhost "LabNavHost - the 14 destinations" { -description "dashboard, device_scan, miracast, display, network, audio, input, projection, android_auto, motion, dex, scene, report, log. A flat list of string routes: one tap from the dashboard to any experiment and one tap back." -attr src = "ui/LabNavHost.kt · docs/architecture.md §2" }
Device dev_zfold "Samsung Galaxy Z Fold  <<TBD: exact model>>" { -description "The repository names 'Samsung Galaxy Z Fold' without a model number; row 2.1 of docs/protocol-findings.md has model / One UI version / build fingerprint as NOT TESTED." -attr src = "README.md · docs/protocol-findings.md 2.1" }
Device dev_mirai "Toyota Mirai 2025 head unit  <<TBD: software version>>" { -description "Head-unit software version is a field the tester fills in the EXPORT REPORT vehicle form; no value is recorded anywhere in the repository." -attr src = "README.md · test procedure §0 (vehicle form)" }
SystemSoftware ss_android "Android - minSdk 29, compileSdk 35, targetSdk 35" { -description "minSdk 29 is the floor for AudioPlaybackCaptureConfiguration and the mediaProjection FGS type." -attr src = "docs/DEPENDENCIES.md Build · BUILD.md" }
Node n_app "MiraiCast Lab process - largeHeap, allowBackup=false" { -attr src = "app/src/main/AndroidManifest.xml <application>" }
Node n_mainactivity "MainActivity - LAUNCHER, singleTask, single-activity host" { -description "Single-activity host for all fourteen destinations." -attr src = "AndroidManifest.xml · ARCHITECTURE.md Runtime" }
Node n_presentation "scene.PresentationHostActivity - setLaunchDisplayId" { -description "Exists only to place the scene on a secondary display via ActivityOptions.setLaunchDisplayId - how an ordinary app reaches a Miracast/DeX screen with no privileged API. Itself one of the project's findings." -attr src = "AndroidManifest.xml · docs/architecture.md §4.4" }
Node n_projservice "projection.ProjectionService - FGS type mediaProjection" { -description "Holds no capture logic. It exists because Android 14+ refuses createVirtualDisplay unless such a service is already running." -attr src = "AndroidManifest.xml · docs/architecture.md §4.5" }
Node n_fileprovider "FileProvider ${applicationId}.fileprovider" { -description "Shares exported reports." -attr src = "AndroidManifest.xml · res/xml/file_paths.xml" }
Node n_filesdir "App-private storage - filesDir/evidence, filesDir/reports" { -description "Never external storage: nothing here leaves the device." -attr src = "ARCHITECTURE.md Storage · SAFETY.md" }
Artifact art_jsonl "run-<ts>-<id>.jsonl - append-only, flushed per record" { -description "Each record is flushed as its own line, so a kill loses at most the current record." -attr src = "core/SessionLogger.kt · docs/architecture.md §4.1" }
Artifact art_md "miraicastlab-report-<id>.md - 20 sections" { -description "Sections 15-18 derived mechanically from LabStatus." -attr src = "report/ReportGenerator.kt" }
Artifact art_json "report .json export" { -attr src = "report/ReportScreen.kt (EXPORT JSON)" }
Artifact art_csv "report .csv export" { -attr src = "report/ReportScreen.kt (EXPORT CSV)" }
Node n_hc55 "HorseCloud55 - build host (Ubuntu 24.04, SDK 35, JDK 21)" { -description "Measured 2026-08-30: HC55 builds this project end to end." -attr src = "BUILD.md 'Toolchain' · CLAUDE.md" }
Node n_gradle "Gradle 8.11.1 wrapper - AGP 8.7.3, Kotlin 2.1.0, Compose BOM 2024.12.01" { -attr src = "BUILD.md · docs/DEPENDENCIES.md Build" }
Artifact art_apk_debug "app-debug.apk - applicationIdSuffix .debug" { -description "Debug and release can be installed side by side during a vehicle session." -attr src = "BUILD.md" }
Node n_emulator "Emulator smoke gate - emulator-5584 (mandatory)" { -description "Green = FATAL count 0 and a live pid. Green unit tests do not substitute." -attr src = "BUILD.md 'Emulator smoke gate' · CLAUDE.md release protocol" }
Artifact art_apk_dist "dist/MiraiCastLab-v0.1.0-Mirai-debug.apk" { -attr src = "ARCHITECTURE.md Deploy · dist/ · version.json" }
Node n_horseapk "HorseAPK - /APKDeploy to horsecloud55.ddns.net:4443/MiraiCastLab/" { -attr src = "ARCHITECTURE.md Deploy" }
Artifact art_tools_adb "tools/adb - collect-dumpsys.sh, watch-session.sh, pull-evidence.sh" { -description "Read-only diagnostics for the developer workstation. None require root, none change device or vehicle state." -attr src = "tools/adb/README.md" }
Artifact art_tools_report "tools/report/render_report.py" { -description "Re-renders an old run, and renders a session that never reached the export screen. A torn final line - the signature of a killed process - is reported on stderr and skipped, never dropped silently." -attr src = "tools/report/README.md" }
CommunicationNetwork cn_wifi "Wi-Fi Direct / p2p0 - 5 GHz radio contention" { -attr src = "docs/DEPENDENCIES.md platform surfaces · test procedure §6" }
CommunicationNetwork cn_usb "USB-C - wired Android Auto" { -attr src = "test procedure §6 row C/D" }
CommunicationNetwork cn_adb "ADB - workstation link, optional during a session" { -description "The tester should not need to remember ADB commands while sitting in the vehicle." -attr src = "tools/adb/README.md · test procedure §0" }
ApplicationComponent ui_dashboard "DashboardScreen - RUN DEVICE SCAN" { -description "The whole device state on one page, with a status chip per fact." -attr src = "README.md screens table · test procedure §1" }
ApplicationFunction c_safeobserve "Probe.safeObserve(context)" { -description "Runs Probe.observe and converts any throwable into an ERROR observation." -attr src = "core/Probe.kt · docs/PRINCIPLES.md P4" }
DataObject do_obs_error "Observation.error - note: the capability itself is untested" { -description "A probe that blows up must degrade the report, not the app. A probe may never turn its own failure into a negative result about the device." -attr src = "docs/architecture.md §3 rule 2 · core/Probe.kt" }
ApplicationComponent p_device "1. DeviceProbe" { -description "Read-only, idempotent and non-throwing. It never mutates device state and never asks for permission." -attr src = "core/ProbeRegistry.kt (scan order) · scan/DeviceProbe.kt" }
ApplicationComponent p_codec "2. CodecProbe" { -description "Read-only, idempotent and non-throwing. It never mutates device state and never asks for permission." -attr src = "core/ProbeRegistry.kt (scan order) · scan/CodecProbe.kt" }
ApplicationComponent p_display "3. DisplayProbe" { -description "Read-only, idempotent and non-throwing. It never mutates device state and never asks for permission." -attr src = "core/ProbeRegistry.kt (scan order) · display/DisplayProbe.kt" }
ApplicationComponent p_mediaroute "4. MediaRouteProbe" { -description "Read-only, idempotent and non-throwing. It never mutates device state and never asks for permission." -attr src = "core/ProbeRegistry.kt (scan order) · display/MediaRouteProbe.kt" }
ApplicationComponent p_wifip2p "5. WifiP2pProbe" { -description "Read-only, idempotent and non-throwing. It never mutates device state and never asks for permission." -attr src = "core/ProbeRegistry.kt (scan order) · net/WifiP2pProbe.kt" }
ApplicationComponent p_connectivity "6. ConnectivityProbe" { -description "Read-only, idempotent and non-throwing. It never mutates device state and never asks for permission." -attr src = "core/ProbeRegistry.kt (scan order) · net/ConnectivityProbe.kt" }
ApplicationComponent p_audioroute "7. AudioRouteProbe" { -description "Read-only, idempotent and non-throwing. It never mutates device state and never asks for permission." -attr src = "core/ProbeRegistry.kt (scan order) · audio/AudioRouteProbe.kt" }
ApplicationComponent p_inputdevice "8. InputDeviceProbe" { -description "Read-only, idempotent and non-throwing. It never mutates device state and never asks for permission." -attr src = "core/ProbeRegistry.kt (scan order) · input/InputDeviceProbe.kt" }
ApplicationComponent p_projcap "9. ProjectionCapabilityProbe" { -description "Read-only, idempotent and non-throwing. It never mutates device state and never asks for permission." -attr src = "core/ProbeRegistry.kt (scan order) · projection/ProjectionCapabilityProbe.kt" }
ApplicationComponent p_smartview "10. SmartViewProbe" { -description "Read-only, idempotent and non-throwing. It never mutates device state and never asks for permission." -attr src = "core/ProbeRegistry.kt (scan order) · samsung/SmartViewProbe.kt" }
ApplicationComponent p_dex "11. DexProbe" { -description "Read-only, idempotent and non-throwing. It never mutates device state and never asks for permission." -attr src = "core/ProbeRegistry.kt (scan order) · samsung/DexProbe.kt" }
ApplicationComponent p_androidauto "12. AndroidAutoProbe" { -description "Read-only, idempotent and non-throwing. It never mutates device state and never asks for permission." -attr src = "core/ProbeRegistry.kt (scan order) · auto/AndroidAutoProbe.kt" }
DataObject ds_stateflow "SessionLogger.records: StateFlow - last 5000 in memory" { -description "The JSONL file is uncapped; the viewer says how many records it is showing." -attr src = "core/SessionLogger.kt (MAX_IN_MEMORY) · docs/architecture.md §8" }
ApplicationComponent ui_logviewer "LogViewerScreen - live structured log" { -description "Category/status filters and search." -attr src = "README.md screens table · report/LogViewerScreen.kt" }
ApplicationComponent ui_projection "MediaProjectionScreen" { -description "Capture FPS, VirtualDisplay metrics, a real H.264 encode with encoder statistics." -attr src = "README.md screens table · projection/MediaProjectionScreen.kt" }
SystemSoftware api_mpm "MediaProjectionManager - consent intent" { -attr src = "projection/CaptureEngine.kt · docs/DEPENDENCIES.md platform surfaces" }
SystemSoftware api_mp "MediaProjection - registerCallback" { -attr src = "projection/CaptureEngine.kt" }
SystemSoftware api_vd "VirtualDisplay - createVirtualDisplay" { -attr src = "projection/CaptureEngine.kt · docs/architecture.md §4.5" }
SystemSoftware api_imagereader "ImageReader on a HandlerThread" { -description "All capture work happens on one HandlerThread. The main thread only reads state." -attr src = "projection/CaptureEngine.kt" }
SystemSoftware api_mediacodec "MediaCodec - H.264 encoder" { -attr src = "projection/H264Encoder.kt · docs/protocol-findings.md 1.15" }
Constraint rq_a14order "Android 14+ order: consent, FGS, getMediaProjection, registerCallback, createVirtualDisplay" { -description "Lifecycle order matters and is not negotiable on Android 14+. Getting that order wrong throws SecurityException, and a MediaProjection that is not released is a crash on the next start." -attr src = "projection/CaptureEngine.kt (KDoc) · docs/DEPENDENCIES.md platform surfaces" }
DataObject do_nottested_sink "NOT_TESTED observation: local capture says nothing about what the sink receives" { -description "Emitted as an explicit NOT_TESTED observation at the end of every session so the claim cannot drift in the report." -attr src = "projection/CaptureEngine.kt (KDoc) · docs/PRINCIPLES.md P3" }
ApplicationComponent ui_report "ReportScreen - EXPORT REPORT / JSON / CSV / SHARE" { -attr src = "report/ReportScreen.kt · test procedure §8" }
ApplicationComponent c_reportgen "ReportGenerator - 20 sections, mechanical grading" { -description "All twenty sections are always emitted. An absent section would read as a silent negative." -attr src = "report/ReportGenerator.kt · docs/architecture.md §5" }
Node n_chooser "ACTION_SEND chooser - FLAG_GRANT_READ_URI_PERMISSION" { -attr src = "report/ReportScreen.kt (shareFile)" }
BusinessActor ba_tester "Tester - stationary vehicle, in park, ignition on" { -description "Nothing in this procedure is to be executed while driving. See SAFETY.md." -attr src = "docs/toyota-mirai-2025-test-procedure.md §0" }
BusinessProcess bp_prep "§0 Before you leave - install, baseline at home, charge" { -description "Install a debug build and confirm it starts; open the app at home and press RUN DEVICE SCAN for a baseline with no car present; export it as baseline-no-vehicle; optionally take a laptop; charge the phone." -attr src = "docs/toyota-mirai-2025-test-procedure.md §0" }
BusinessObject bo_baseline "baseline-no-vehicle report (Markdown)" { -description "The single most useful reference you will have." -attr src = "docs/toyota-mirai-2025-test-procedure.md §0 step 3" }
BusinessProcess bp_rowa "§1 Row A - baseline in the vehicle, nothing connected" { -description "Note the number of displays, whether any p2p* interface exists, the active audio output, and whether Android already reports car mode. Every later observation is a diff against it." -attr src = "docs/toyota-mirai-2025-test-procedure.md §1" }
BusinessProcess w1 "W1 Scan baseline" { -description "Tap Scan baseline. The app records a display / route / Wi-Fi / P2P / audio snapshot." -attr src = "docs/toyota-mirai-2025-test-procedure.md §2 wizard table" }
BusinessProcess w2 "W2 Start Smart View from the quick panel and pick the Mirai" { -description "The app cannot connect for you; it opens the right settings panel if the intent resolves." -attr src = "docs/toyota-mirai-2025-test-procedure.md §2 wizard table" }
BusinessProcess w3 "W3 Wait, watching the live change list" { -description "Every display, route, interface and audio-route change, timestamped." -attr src = "docs/toyota-mirai-2025-test-procedure.md §2 wizard table" }
BusinessProcess w4 "W4 Launch scene on external display" { -description "The diagnostic pattern appears on the car screen." -attr src = "docs/toyota-mirai-2025-test-procedure.md §2 wizard table" }
BusinessProcess w5 "W5 For each sub-test, tap what you actually see" { -description "VISIBLE / DISTORTED / BLACK / AUDIO ONLY / NOT SHOWN." -attr src = "docs/toyota-mirai-2025-test-procedure.md §2 wizard table" }
BusinessProcess w6 "W6 Touch the numbered targets on the car screen" { -description "Any input event that arrives on the phone is recorded." -attr src = "docs/toyota-mirai-2025-test-procedure.md §2 wizard table" }
BusinessProcess w7 "W7 Export" { -description "A wizard summary with a status per line." -attr src = "docs/toyota-mirai-2025-test-procedure.md §2 wizard table" }
BusinessEvent ev_intent "Does the settings intent resolve?" { -description "A resolvable intent is CONFIRMED; an unresolvable one is UNSUPPORTED for this device - both are real answers." -attr src = "docs/toyota-mirai-2025-test-procedure.md §2 step 2 · AndroidManifest.xml <queries>" }
BusinessEvent ev_display "Did a display appear, and at what size did Android draw to it?" { -description "What you can establish: whether a display appeared, at what size Android drew to it, whether audio moved, whether motion survived the link, and whether anything came back the other way." -attr src = "docs/toyota-mirai-2025-test-procedure.md §2 'honest limits'" }
BusinessObject bo_structural "Permanently NOT_TESTED: negotiated resolution, framerate, codec, HDCP, UIBC advertisement" { -description "Those live in the Wi-Fi Display / wpa_supplicant layer, which is not exposed to third-party apps on a non-rooted device. That is a finding of this project, not a gap in it." -attr src = "docs/toyota-mirai-2025-test-procedure.md §2 · docs/protocol-findings.md 1.6-1.10" }
BusinessObject bo_photo "Photograph of the car screen: 60-position sweep + frame counter" { -description "The number of distinct sweep positions in one photo gives the delivered frame rate; both frame counters in one frame give the end-to-end latency in frames." -attr src = "docs/toyota-mirai-2025-test-procedure.md §2 'What to look at' · 'What a complete session should have produced'" }
BusinessProcess bp_input "§3 Input test - 3x5 target grid on the car's screen" { -description "Touch each numbered target on the car's screen, then read the verdict." -attr src = "docs/toyota-mirai-2025-test-procedure.md §3" }
BusinessEvent ev_mouse "Mouse events logged? If not the monitor is broken" { -description "Sanity-check the instrument first: pair a Bluetooth mouse or plug in a USB-C mouse. If the monitor does not log its movement, any negative result below is meaningless." -attr src = "docs/toyota-mirai-2025-test-procedure.md §3 step 1" }
BusinessEvent ev_input "Did any input event arrive?" { -description "NO INPUT OBSERVED is recorded as such - not as 'unsupported'. If input does arrive, the app records source, device id, coordinates, pressure and the derived coordinate transform." -attr src = "docs/toyota-mirai-2025-test-procedure.md §3 · docs/protocol-findings.md 1.10" }
BusinessObject bo_verdict "Touch-back verdict with its percentage and source list" { -attr src = "docs/toyota-mirai-2025-test-procedure.md 'What a complete session should have produced'" }
BusinessProcess bp_audio "§4 Audio - silence, mono, stereo L/R, continuous, latency click, A/V sync pulse" { -description "The app logs its own internal timestamps; the end-to-end number can only come from an external recording, and the app says so rather than pretending otherwise." -attr src = "docs/toyota-mirai-2025-test-procedure.md §4" }
BusinessObject bo_avrec "One external A/V recording for the latency estimate" { -attr src = "docs/toyota-mirai-2025-test-procedure.md §4 · 'What a complete session should have produced'" }
BusinessProcess bp_dex "§5 Samsung DeX - density and flags vs plain Smart View" { -description "A different density is the usual discriminator between DeX and mirroring." -attr src = "docs/toyota-mirai-2025-test-procedure.md §5" }
BusinessEvent ev_dex "Is the Mirai offered as a Wireless DeX target?" { -description "Record the exact limitation if DeX refuses the Mirai - the wording of the refusal is data." -attr src = "docs/toyota-mirai-2025-test-procedure.md §5" }
BusinessProcess bp_auto "§6 Android Auto coexistence - rows C-F" { -description "C: USB on / Miracast off. D: USB on / on. E: wireless on / off. F: wireless on / on. Watch the Wi-Fi frequency readout: wireless AA and Miracast both want the 5 GHz radio." -attr src = "docs/toyota-mirai-2025-test-procedure.md §6" }
BusinessEvent ev_order "Both connection orders for D and F" { -description "AA first then Miracast, and the reverse. They can behave differently, and which one survives is the finding." -attr src = "docs/toyota-mirai-2025-test-procedure.md §6" }
BusinessObject bo_matrix "One report per matrix row, or an explicit NOT_TESTED" { -attr src = "docs/toyota-mirai-2025-test-procedure.md 'What a complete session should have produced'" }
BusinessProcess bp_motion "§7 Motion state - observation only, the car stays stationary" { -description "Markers: PARK, VIDEO VISIBLE, VIDEO BLOCKED, AUDIO ONLY, CONNECTION LOST. MotionStateScreen contains exactly two things: a passive liveness recorder and large marker buttons. It sends nothing anywhere." -attr src = "docs/toyota-mirai-2025-test-procedure.md §7 · SAFETY.md" }
BusinessEvent ev_bench "Bench head unit or legitimate stationary test environment available?" { -description "Only then record what the receiver does when its vehicle-state input changes. Nothing in this project changes that input." -attr src = "docs/toyota-mirai-2025-test-procedure.md §7 · SAFETY.md" }
BusinessEvent ev_blocked "Head unit blanks video?" { -description "If the Toyota head unit disables video based on vehicle state, that is recorded as a finding and the project stops there. No bypass is built." -attr src = "SAFETY.md 'The motion-state experiment'" }
BusinessProcess bp_close "§8 Close the session - export MD/JSON/CSV, disconnect" { -description "With a laptop: collect-dumpsys.sh after and pull-evidence.sh. Without: the in-app share sheet. Then disconnect Smart View and Android Auto." -attr src = "docs/toyota-mirai-2025-test-procedure.md §8" }
BusinessObject bo_jsonl_run "The JSONL evidence file for the whole run" { -attr src = "docs/toyota-mirai-2025-test-procedure.md 'What a complete session should have produced'" }
BusinessObject bo_notfilled "Anything you did not do stays NOT_TESTED" { -description "Do not fill it in from memory afterwards." -attr src = "docs/toyota-mirai-2025-test-procedure.md closing line" }

# -------------------------------------------------------------- relations
dr_unknown -> go_empirical "raises" { -attr id = "r001" -attr src = "README.md" }
dr_unknown -> go_experiments "raises" { -attr id = "r002" -attr src = "docs/architecture.md §1" }
dr_false -> go_evidence "motivates" { -attr id = "r003" -attr src = "docs/architecture.md §7" }
as_state -> go_empirical "still open at v0.1.0" { -attr id = "r004" -attr src = "README.md + version.json" }
rq_grade -> p1 "realises" { -attr id = "r005" -attr src = "docs/PRINCIPLES.md P1" }
rq_mechanical -> p2 "realises" { -attr id = "r006" -attr src = "docs/architecture.md §3" }
rq_safeobserve -> p4 "realises" { -attr id = "r007" -attr src = "docs/architecture.md §3" }
rq_missing -> p5 "realises" { -attr id = "r008" -attr src = "docs/architecture.md §3" }
rq_sink -> p3 "realises" { -attr id = "r009" -attr src = "docs/architecture.md §3" }
cs_rule -> p7 "bounds" { -attr id = "r010" -attr src = "SAFETY.md" }
cs_can -> p7 "bounds" { -attr id = "r011" -attr src = "SAFETY.md" }
cs_spoof -> p7 "bounds" { -attr id = "r012" -attr src = "SAFETY.md" }
cs_firmware -> p7 "bounds" { -attr id = "r013" -attr src = "SAFETY.md" }
cs_aaspoof -> p7 "bounds" { -attr id = "r014" -attr src = "SAFETY.md" }
cs_aacat -> p7 "bounds" { -attr id = "r015" -attr src = "SAFETY.md" }
cs_bus -> p7 "bounds" { -attr id = "r016" -attr src = "SAFETY.md" }
cs_evasion -> p7 "bounds" { -attr id = "r017" -attr src = "SAFETY.md" }
cs_internet -> p7 "bounds" { -attr id = "r018" -attr src = "SAFETY.md" }
cs_private -> p7 "bounds" { -attr id = "r019" -attr src = "SAFETY.md" }
cs_bench -> p7 "bounds" { -attr id = "r020" -attr src = "SAFETY.md" }
cs_root -> p7 "bounds" { -attr id = "r021" -attr src = "SAFETY.md" }
cs_escal -> p7 "bounds" { -attr id = "r022" -attr src = "SAFETY.md" }
cs_novehicle -> p7 "bounds" { -attr id = "r023" -attr src = "SAFETY.md" }
cs_nomiracast -> p6 "bounds" { -attr id = "r024" -attr src = "docs/architecture.md §7" }
cs_nohidden -> p5 "bounds" { -attr id = "r025" -attr src = "docs/architecture.md §7" }
cap_inventory -> go_experiments "realises" { -attr id = "r026" -attr src = "README.md" }
cap_wizard -> go_experiments "realises" { -attr id = "r027" -attr src = "README.md" }
cap_scene -> go_experiments "realises" { -attr id = "r028" -attr src = "README.md" }
cap_audio -> go_experiments "realises" { -attr id = "r029" -attr src = "README.md" }
cap_touchback -> go_experiments "realises" { -attr id = "r030" -attr src = "README.md" }
cap_projection -> go_experiments "realises" { -attr id = "r031" -attr src = "README.md" }
cap_matrix -> go_experiments "realises" { -attr id = "r032" -attr src = "README.md" }
cap_motion -> go_experiments "realises" { -attr id = "r033" -attr src = "README.md" }
cap_report -> go_evidence "realises" { -attr id = "r034" -attr src = "README.md" }
p1 -> go_evidence "grades every fact" { -attr id = "r035" -attr src = "docs/PRINCIPLES.md P1" }
p2 -> go_evidence "keeps the grading honest" { -attr id = "r036" -attr src = "docs/PRINCIPLES.md P2" }
p8 -> go_evidence "writes as it happens" { -attr id = "r037" -attr src = "docs/PRINCIPLES.md P8" }
mod_core -> c_evidence "contains" { -attr id = "r038" -attr src = "docs/architecture.md §4.1" }
mod_core -> c_probe "contains" { -attr id = "r039" -attr src = "docs/architecture.md §4.1" }
mod_core -> c_sessionlogger "contains" { -attr id = "r040" -attr src = "docs/architecture.md §4.1" }
mod_core -> c_json "contains" { -attr id = "r041" -attr src = "docs/architecture.md §4.1" }
mod_core -> c_labpermissions "contains" { -attr id = "r042" -attr src = "docs/architecture.md §4.1" }
mod_core -> c_dashboardkeys "contains" { -attr id = "r043" -attr src = "docs/architecture.md §4.1" }
mod_core -> c_proberegistry "contains" { -attr id = "r044" -attr src = "docs/architecture.md §4.1" }
mod_ui -> c_labnavhost "contains" { -attr id = "r045" -attr src = "ui/LabNavHost.kt" }
mod_scan -> mod_core "depends on" { -attr id = "r046" -attr src = "docs/architecture.md §2 ('arrows point into core only')" }
mod_display -> mod_core "depends on" { -attr id = "r047" -attr src = "docs/architecture.md §2 ('arrows point into core only')" }
mod_net -> mod_core "depends on" { -attr id = "r048" -attr src = "docs/architecture.md §2 ('arrows point into core only')" }
mod_audio -> mod_core "depends on" { -attr id = "r049" -attr src = "docs/architecture.md §2 ('arrows point into core only')" }
mod_input -> mod_core "depends on" { -attr id = "r050" -attr src = "docs/architecture.md §2 ('arrows point into core only')" }
mod_projection -> mod_core "depends on" { -attr id = "r051" -attr src = "docs/architecture.md §2 ('arrows point into core only')" }
mod_scene -> mod_core "depends on" { -attr id = "r052" -attr src = "docs/architecture.md §2 ('arrows point into core only')" }
mod_samsung -> mod_core "depends on" { -attr id = "r053" -attr src = "docs/architecture.md §2 ('arrows point into core only')" }
mod_auto -> mod_core "depends on" { -attr id = "r054" -attr src = "docs/architecture.md §2 ('arrows point into core only')" }
mod_report -> mod_core "depends on" { -attr id = "r055" -attr src = "docs/architecture.md §2 ('arrows point into core only')" }
mod_ui -> mod_core "depends on" { -attr id = "r056" -attr src = "docs/architecture.md §2" }
c_proberegistry -> mod_scan "lists probes of" { -attr id = "r057" -attr src = "core/ProbeRegistry.kt (imports)" }
c_proberegistry -> mod_display "lists probes of" { -attr id = "r058" -attr src = "core/ProbeRegistry.kt (imports)" }
c_proberegistry -> mod_net "lists probes of" { -attr id = "r059" -attr src = "core/ProbeRegistry.kt (imports)" }
c_proberegistry -> mod_audio "lists probes of" { -attr id = "r060" -attr src = "core/ProbeRegistry.kt (imports)" }
c_proberegistry -> mod_input "lists probes of" { -attr id = "r061" -attr src = "core/ProbeRegistry.kt (imports)" }
c_proberegistry -> mod_projection "lists probes of" { -attr id = "r062" -attr src = "core/ProbeRegistry.kt (imports)" }
c_proberegistry -> mod_samsung "lists probes of" { -attr id = "r063" -attr src = "core/ProbeRegistry.kt (imports)" }
c_proberegistry -> mod_auto "lists probes of" { -attr id = "r064" -attr src = "core/ProbeRegistry.kt (imports)" }
c_labnavhost -> mod_scan "lists screens of" { -attr id = "r065" -attr src = "ui/LabNavHost.kt (imports)" }
c_labnavhost -> mod_display "lists screens of" { -attr id = "r066" -attr src = "ui/LabNavHost.kt (imports)" }
c_labnavhost -> mod_net "lists screens of" { -attr id = "r067" -attr src = "ui/LabNavHost.kt (imports)" }
c_labnavhost -> mod_audio "lists screens of" { -attr id = "r068" -attr src = "ui/LabNavHost.kt (imports)" }
c_labnavhost -> mod_input "lists screens of" { -attr id = "r069" -attr src = "ui/LabNavHost.kt (imports)" }
c_labnavhost -> mod_projection "lists screens of" { -attr id = "r070" -attr src = "ui/LabNavHost.kt (imports)" }
c_labnavhost -> mod_scene "lists screens of" { -attr id = "r071" -attr src = "ui/LabNavHost.kt (imports)" }
c_labnavhost -> mod_samsung "lists screens of" { -attr id = "r072" -attr src = "ui/LabNavHost.kt (imports)" }
c_labnavhost -> mod_auto "lists screens of" { -attr id = "r073" -attr src = "ui/LabNavHost.kt (imports)" }
c_labnavhost -> mod_report "lists screens of" { -attr id = "r074" -attr src = "ui/LabNavHost.kt (imports)" }
c_evidence -> do_observation "defines" { -attr id = "r075" -attr src = "docs/architecture.md §4.1" }
c_evidence -> do_logrecord "defines" { -attr id = "r076" -attr src = "docs/architecture.md §4.1" }
c_probe -> do_observation "produces" { -attr id = "r077" -attr src = "core/Probe.kt" }
c_sessionlogger -> do_logrecord "writes one LogRecord per observation" { -attr id = "t30" -attr src = "core/SessionLogger.kt" }
c_json -> do_logrecord "serialises" { -attr id = "t31" -attr src = "core/Json.kt · docs/architecture.md §4.1" }
c_dashboardkeys -> do_observation "validates keys of" { -attr id = "r080" -attr src = "docs/architecture.md §4.1" }
dev_zfold -> ss_android "runs" { -attr id = "r081" -attr src = "BUILD.md" }
ss_android -> n_app "hosts" { -attr id = "r082" -attr src = "AndroidManifest.xml" }
n_app -> n_mainactivity "hosts" { -attr id = "r083" -attr src = "AndroidManifest.xml" }
n_app -> n_presentation "hosts" { -attr id = "r084" -attr src = "AndroidManifest.xml" }
n_app -> n_projservice "hosts" { -attr id = "r085" -attr src = "AndroidManifest.xml" }
n_app -> n_fileprovider "hosts" { -attr id = "r086" -attr src = "AndroidManifest.xml" }
n_mainactivity -> n_presentation "launches on displayId" { -attr id = "r087" -attr src = "docs/architecture.md §4.4" }
n_presentation -> dev_mirai "draws the scene on the secondary display" { -attr id = "r088" -attr src = "docs/architecture.md §4.4" }
n_mainactivity -> n_projservice "starts before capture" { -attr id = "r089" -attr src = "docs/architecture.md §4.5" }
n_app -> n_filesdir "writes to" { -attr id = "r090" -attr src = "ARCHITECTURE.md Storage" }
n_filesdir -> art_jsonl "holds" { -attr id = "r091" -attr src = "core/SessionLogger.kt" }
n_filesdir -> art_md "holds" { -attr id = "r092" -attr src = "report/ReportGenerator.kt (write)" }
n_filesdir -> art_json "holds" { -attr id = "r093" -attr src = "report/ReportScreen.kt" }
n_filesdir -> art_csv "holds" { -attr id = "r094" -attr src = "report/ReportScreen.kt" }
n_fileprovider -> art_jsonl "shares" { -attr id = "r095" -attr src = "BUILD.md 'or use the in-app share sheet'" }
n_hc55 -> n_gradle "runs" { -attr id = "r096" -attr src = "BUILD.md" }
n_gradle -> art_apk_debug "assembleDebug produces" { -attr id = "r097" -attr src = "BUILD.md" }
art_apk_debug -> n_emulator "install + am start" { -attr id = "r098" -attr src = "BUILD.md smoke gate" }
art_apk_debug -> dev_zfold "adb install -r -d" { -attr id = "r099" -attr src = "BUILD.md 'Install on the Galaxy Z Fold'" }
art_apk_debug -> art_apk_dist "copied to dist/" { -attr id = "r100" -attr src = "ARCHITECTURE.md Deploy" }
n_emulator -> n_horseapk "gate must pass first" { -attr id = "r101" -attr src = "CLAUDE.md release protocol" }
art_apk_dist -> n_horseapk "/APKDeploy" { -attr id = "r102" -attr src = "ARCHITECTURE.md Deploy" }
cn_wifi -> dev_zfold "carries" { -attr id = "r103" -attr src = "docs/DEPENDENCIES.md" }
cn_wifi -> dev_mirai "carries" { -attr id = "r104" -attr src = "test procedure §2" }
cn_usb -> dev_zfold "carries" { -attr id = "r105" -attr src = "test procedure §6 row C" }
cn_usb -> dev_mirai "carries" { -attr id = "r106" -attr src = "test procedure §6 row C" }
cn_adb -> dev_zfold "connects" { -attr id = "r107" -attr src = "tools/adb/README.md" }
art_tools_adb -> cn_adb "dumpsys / logcat over" { -attr id = "r108" -attr src = "tools/adb/README.md" }
art_tools_adb -> art_jsonl "pull-evidence.sh pulls" { -attr id = "r109" -attr src = "tools/adb/README.md" }
art_tools_report -> art_jsonl "renders" { -attr id = "r110" -attr src = "tools/report/README.md" }
art_tools_report -> art_md "produces the same 20 sections" { -attr id = "r111" -attr src = "tools/report/README.md" }
ui_dashboard -> c_proberegistry "RUN DEVICE SCAN" { -attr id = "t01" -attr src = "core/ProbeRegistry.kt runAll" }
c_proberegistry -> c_safeobserve "runAll, in scan order" { -attr id = "t02" -attr src = "core/ProbeRegistry.kt" }
c_safeobserve -> p_device "runs each probe in scan order" { -attr id = "t03" -attr src = "core/ProbeRegistry.kt all[] order (probe 1 of 12)" }
c_safeobserve -> p_codec { -attr id = "t04" -attr src = "core/ProbeRegistry.kt all[] order (probe 2 of 12)" }
c_safeobserve -> p_display { -attr id = "t05" -attr src = "core/ProbeRegistry.kt all[] order (probe 3 of 12)" }
c_safeobserve -> p_mediaroute { -attr id = "t06" -attr src = "core/ProbeRegistry.kt all[] order (probe 4 of 12)" }
c_safeobserve -> p_wifip2p { -attr id = "t07" -attr src = "core/ProbeRegistry.kt all[] order (probe 5 of 12)" }
c_safeobserve -> p_connectivity { -attr id = "t08" -attr src = "core/ProbeRegistry.kt all[] order (probe 6 of 12)" }
c_safeobserve -> p_audioroute { -attr id = "t09" -attr src = "core/ProbeRegistry.kt all[] order (probe 7 of 12)" }
c_safeobserve -> p_inputdevice { -attr id = "t10" -attr src = "core/ProbeRegistry.kt all[] order (probe 8 of 12)" }
c_safeobserve -> p_projcap { -attr id = "t11" -attr src = "core/ProbeRegistry.kt all[] order (probe 9 of 12)" }
c_safeobserve -> p_smartview { -attr id = "t12" -attr src = "core/ProbeRegistry.kt all[] order (probe 10 of 12)" }
c_safeobserve -> p_dex { -attr id = "t13" -attr src = "core/ProbeRegistry.kt all[] order (probe 11 of 12)" }
c_safeobserve -> p_androidauto { -attr id = "t14" -attr src = "core/ProbeRegistry.kt all[] order (probe 12 of 12)" }
c_safeobserve -> do_obs_error "throwable -> ERROR" { -attr id = "t15" -attr src = "core/Probe.kt safeObserve" }
do_obs_error -> do_observation "an ERROR observation, not a negative result" { -attr id = "t16" -attr src = "docs/PRINCIPLES.md P4" }
p_device -> do_observation "returns" { -attr id = "t17" -attr src = "scan/DeviceProbe.kt" }
p_codec -> do_observation "returns" { -attr id = "t18" -attr src = "scan/CodecProbe.kt" }
p_display -> do_observation "returns" { -attr id = "t19" -attr src = "display/DisplayProbe.kt" }
p_mediaroute -> do_observation "returns" { -attr id = "t20" -attr src = "display/MediaRouteProbe.kt" }
p_wifip2p -> do_observation "returns" { -attr id = "t21" -attr src = "net/WifiP2pProbe.kt" }
p_connectivity -> do_observation "returns" { -attr id = "t22" -attr src = "net/ConnectivityProbe.kt" }
p_audioroute -> do_observation "returns" { -attr id = "t23" -attr src = "audio/AudioRouteProbe.kt" }
p_inputdevice -> do_observation "returns" { -attr id = "t24" -attr src = "input/InputDeviceProbe.kt" }
p_projcap -> do_observation "returns" { -attr id = "t25" -attr src = "projection/ProjectionCapabilityProbe.kt" }
p_smartview -> do_observation "returns" { -attr id = "t26" -attr src = "samsung/SmartViewProbe.kt" }
p_dex -> do_observation "returns" { -attr id = "t27" -attr src = "samsung/DexProbe.kt" }
p_androidauto -> do_observation "returns" { -attr id = "t28" -attr src = "auto/AndroidAutoProbe.kt" }
do_observation -> c_sessionlogger "SessionLogger.logAll" { -attr id = "t29" -attr src = "core/ProbeRegistry.kt" }
do_logrecord -> art_jsonl "appended, flushed per line" { -attr id = "t32" -attr src = "docs/architecture.md §5" }
c_sessionlogger -> ds_stateflow "StateFlow" { -attr id = "t33" -attr src = "core/SessionLogger.kt" }
ds_stateflow -> ui_logviewer "live log viewer" { -attr id = "t34" -attr src = "docs/architecture.md §5" }
ds_stateflow -> ui_dashboard "dashboard and screens" { -attr id = "t35" -attr src = "docs/architecture.md §5" }
c_proberegistry -> c_sessionlogger "capability_scan_complete" { -attr id = "t36" -attr src = "core/ProbeRegistry.kt" }
c_dashboardkeys -> ui_dashboard "missingFrom(): a blank field is an alarm" { -attr id = "t37" -attr src = "docs/architecture.md §3 rule 3" }
ui_projection -> c_labpermissions "asks with a reason" { -attr id = "t40" -attr src = "core/LabPermissions.kt" }
ui_projection -> api_mpm "createScreenCaptureIntent" { -attr id = "t41" -attr src = "projection/CaptureEngine.kt" }
api_mpm -> n_projservice "consent -> start FGS" { -attr id = "t42" -attr src = "projection/CaptureEngine.kt" }
n_projservice -> api_mp "getMediaProjection" { -attr id = "t43" -attr src = "projection/CaptureEngine.kt" }
api_mp -> api_vd "registerCallback first" { -attr id = "t44" -attr src = "projection/CaptureEngine.kt" }
api_vd -> api_imagereader "Surface" { -attr id = "t45" -attr src = "projection/CaptureEngine.kt" }
api_imagereader -> api_mediacodec "frames" { -attr id = "t46" -attr src = "projection/H264Encoder.kt" }
api_mediacodec -> c_sessionlogger "fps, metrics, encoder statistics" { -attr id = "t47" -attr src = "projection/MediaProjectionScreen.kt" }
rq_a14order -> api_vd "Android 14+ refuses without a running FGS" { -attr id = "t48" -attr src = "docs/architecture.md §4.5" }
api_mediacodec -> do_nottested_sink "says nothing about the sink" { -attr id = "t49" -attr src = "projection/CaptureEngine.kt KDoc" }
do_nottested_sink -> c_sessionlogger "logged as an observation" { -attr id = "t50" -attr src = "docs/PRINCIPLES.md P3" }
n_projservice -> c_sessionlogger "projection_service_started" { -attr id = "t51" -attr src = "projection/ProjectionService.kt" }
ui_report -> c_reportgen "EXPORT REPORT -> build" { -attr id = "t60" -attr src = "report/ReportScreen.kt" }
c_sessionlogger -> c_reportgen "supplies the run's LogRecords" { -attr id = "t61" -attr src = "report/ReportGenerator.kt" }
c_reportgen -> art_md "20 sections; 15-18 from the enum" { -attr id = "t62" -attr src = "report/ReportGenerator.kt" }
ui_report -> art_json "EXPORT JSON" { -attr id = "t63" -attr src = "report/ReportScreen.kt" }
ui_report -> art_csv "EXPORT CSV" { -attr id = "t64" -attr src = "report/ReportScreen.kt" }
art_md -> n_fileprovider "getUriForFile" { -attr id = "t65" -attr src = "report/ReportScreen.kt shareFile" }
art_json -> n_fileprovider "getUriForFile" { -attr id = "t66" -attr src = "report/ReportScreen.kt" }
art_csv -> n_fileprovider "getUriForFile" { -attr id = "t67" -attr src = "report/ReportScreen.kt" }
n_fileprovider -> n_chooser "content:// URI, read permission granted" { -attr id = "t68" -attr src = "report/ReportScreen.kt" }
ui_report -> c_sessionlogger "export_shared" { -attr id = "t69" -attr src = "report/ReportScreen.kt" }
ba_tester -> w1 "runs" { -attr id = "j01" -attr src = "docs/toyota-mirai-2025-test-procedure.md §2" }
w1 -> w2 "baseline recorded" { -attr id = "j02" -attr src = "docs/toyota-mirai-2025-test-procedure.md §2" }
w2 -> ev_intent "opens the panel if it resolves" { -attr id = "j03" -attr src = "docs/toyota-mirai-2025-test-procedure.md §2" }
ev_intent -> w3 "then watch" { -attr id = "j04" -attr src = "docs/toyota-mirai-2025-test-procedure.md §2" }
w3 -> ev_display "timestamped change list" { -attr id = "j05" -attr src = "docs/toyota-mirai-2025-test-procedure.md §2" }
ev_display -> w4 "project the scene" { -attr id = "j06" -attr src = "docs/toyota-mirai-2025-test-procedure.md §2" }
w4 -> w5 "pattern on the car screen" { -attr id = "j07" -attr src = "docs/toyota-mirai-2025-test-procedure.md §2" }
w5 -> w6 "record what you saw" { -attr id = "j08" -attr src = "docs/toyota-mirai-2025-test-procedure.md §2" }
w6 -> ev_input "touch the car screen" { -attr id = "j09" -attr src = "docs/toyota-mirai-2025-test-procedure.md §2" }
ev_input -> w7 "then export" { -attr id = "j10" -attr src = "docs/toyota-mirai-2025-test-procedure.md §2" }
w7 -> bo_matrix "wizard summary" { -attr id = "j11" -attr src = "docs/toyota-mirai-2025-test-procedure.md §2" }
w4 -> bo_photo "photograph the screen" { -attr id = "j12" -attr src = "docs/toyota-mirai-2025-test-procedure.md §2" }
w3 -> bo_structural "stays NOT_TESTED" { -attr id = "j13" -attr src = "docs/toyota-mirai-2025-test-procedure.md §2" }
ba_tester -> bp_prep "prepares" { -attr id = "j20" -attr src = "docs/toyota-mirai-2025-test-procedure.md §0" }
bp_prep -> bo_baseline "exports" { -attr id = "j21" -attr src = "docs/toyota-mirai-2025-test-procedure.md §0" }
bp_prep -> bp_rowa "drive to the car" { -attr id = "j22" -attr src = "docs/toyota-mirai-2025-test-procedure.md §1" }
bp_rowa -> w1 "row A recorded; the wizard drives §2" { -attr id = "j23" -attr src = "docs/toyota-mirai-2025-test-procedure.md §1-2" }
w7 -> bp_input "next experiment" { -attr id = "j24" -attr src = "docs/toyota-mirai-2025-test-procedure.md §3" }
bp_input -> ev_mouse "sanity-check the instrument" { -attr id = "j25" -attr src = "docs/toyota-mirai-2025-test-procedure.md §3" }
ev_mouse -> ev_input "only then run the grid" { -attr id = "j26" -attr src = "docs/toyota-mirai-2025-test-procedure.md §3" }
ev_input -> bo_verdict "verdict recorded" { -attr id = "j27" -attr src = "docs/toyota-mirai-2025-test-procedure.md §3" }
bp_input -> bp_audio "next experiment" { -attr id = "j28" -attr src = "docs/toyota-mirai-2025-test-procedure.md §4" }
bp_audio -> bo_avrec "record on a second phone" { -attr id = "j29" -attr src = "docs/toyota-mirai-2025-test-procedure.md §4" }
bp_audio -> bp_dex "next experiment" { -attr id = "j30" -attr src = "docs/toyota-mirai-2025-test-procedure.md §5" }
bp_dex -> ev_dex "offered as a target?" { -attr id = "j31" -attr src = "docs/toyota-mirai-2025-test-procedure.md §5" }
ev_dex -> bp_auto "next experiment" { -attr id = "j32" -attr src = "docs/toyota-mirai-2025-test-procedure.md §6" }
bp_auto -> ev_order "rows D and F" { -attr id = "j33" -attr src = "docs/toyota-mirai-2025-test-procedure.md §6" }
ev_order -> bo_matrix "which order survives is the finding" { -attr id = "j34" -attr src = "docs/toyota-mirai-2025-test-procedure.md §6" }
bp_auto -> bp_motion "next experiment" { -attr id = "j35" -attr src = "docs/toyota-mirai-2025-test-procedure.md §7" }
bp_motion -> ev_bench "bench available?" { -attr id = "j36" -attr src = "docs/toyota-mirai-2025-test-procedure.md §7" }
ev_bench -> ev_blocked "observe the receiver" { -attr id = "j37" -attr src = "SAFETY.md" }
ev_blocked -> bp_close "recorded as a finding; no bypass" { -attr id = "j38" -attr src = "SAFETY.md" }
bp_motion -> bp_close "close the session" { -attr id = "j39" -attr src = "docs/toyota-mirai-2025-test-procedure.md §8" }
bp_close -> bo_jsonl_run "pull the evidence" { -attr id = "j40" -attr src = "docs/toyota-mirai-2025-test-procedure.md §8" }
bp_close -> bo_notfilled "never from memory" { -attr id = "j41" -attr src = "docs/toyota-mirai-2025-test-procedure.md closing line" }

# ------------------------------------------------------------- viewpoints
Viewpoint conceptueel "Conceptueel" {
  -description "Drivers, goals, the evidence-grading principles P1-P12, the SAFETY.md boundary and the capabilities."
  -attr src = "README.md · docs/architecture.md §1,§3,§7 · docs/PRINCIPLES.md · SAFETY.md · version.json"
  -attr canvas = "1560,980"
  -attr elements = "dr_unknown,dr_false,as_state,go_empirical,go_evidence,go_experiments,rq_grade,rq_mechanical,rq_safeobserve,rq_missing,rq_sink,p1,p2,p3,p4,p5,p6,p7,p8,p9,p10,p11,p12,cs_rule,cs_can,cs_spoof,cs_firmware,cs_aaspoof,cs_aacat,cs_bus,cs_evasion,cs_internet,cs_private,cs_bench,cs_root,cs_escal,cs_novehicle,cs_nomiracast,cs_nohidden,cap_inventory,cap_wizard,cap_scene,cap_audio,cap_touchback,cap_projection,cap_matrix,cap_motion,cap_report"
  -attr layout = "dr_unknown:30,24|dr_false:222,24|as_state:414,24|go_empirical:798,24|go_evidence:990,24|go_experiments:1182,24|rq_grade:30,142|rq_mechanical:222,142|rq_safeobserve:414,142|rq_missing:606,142|rq_sink:798,142|p1:30,260|p2:222,260|p3:414,260|p4:606,260|p5:798,260|p6:990,260|p7:1182,260|p8:1374,260|p9:30,378|p10:222,378|p11:414,378|p12:606,378|cs_rule:30,496|cs_can:222,496|cs_spoof:414,496|cs_firmware:606,496|cs_aaspoof:798,496|cs_aacat:990,496|cs_bus:1182,496|cs_evasion:1374,496|cs_internet:30,614|cs_private:222,614|cs_bench:414,614|cs_root:606,614|cs_escal:798,614|cs_novehicle:990,614|cs_nomiracast:1182,614|cs_nohidden:1374,614|cap_inventory:30,732|cap_wizard:222,732|cap_scene:414,732|cap_audio:606,732|cap_touchback:798,732|cap_projection:990,732|cap_matrix:1182,732|cap_motion:1374,732|cap_report:30,850"
}
Viewpoint logisch "Logisch" {
  -description "The module graph: core at the centre, ten feature modules depending on it only, ProbeRegistry and LabNavHost as the two places that know many modules."
  -attr src = "docs/architecture.md §2, §4.1 · ARCHITECTURE.md Units · core/ProbeRegistry.kt · ui/LabNavHost.kt"
  -attr canvas = "1560,530"
  -attr elements = "c_evidence,c_probe,c_sessionlogger,c_json,c_labpermissions,c_dashboardkeys,do_observation,c_labnavhost,mod_ui,mod_core,c_proberegistry,do_logrecord,mod_scan,mod_display,mod_net,mod_audio,mod_input,mod_projection,mod_scene,mod_samsung,mod_auto,mod_report"
  -attr layout = "c_evidence:30,24|c_probe:220,24|c_sessionlogger:410,24|c_json:600,24|c_labpermissions:790,24|c_dashboardkeys:980,24|do_observation:1360,24|c_labnavhost:30,148|mod_ui:220,148|mod_core:600,148|c_proberegistry:1170,148|do_logrecord:1360,148|mod_scan:30,272|mod_display:220,272|mod_net:410,272|mod_audio:600,272|mod_input:790,272|mod_projection:980,272|mod_scene:1170,272|mod_samsung:410,396|mod_auto:600,396|mod_report:790,396"
}
Viewpoint fysiek "Fysiek" {
  -description "Activities, the mediaProjection foreground service, FileProvider, app-private evidence and report files, the Gradle build, the APK, the HorseAPK publish target and the workstation tools."
  -attr src = "AndroidManifest.xml · ARCHITECTURE.md Runtime/Storage/Deploy · BUILD.md · docs/DEPENDENCIES.md · tools/*/README.md"
  -attr canvas = "1560,690"
  -attr elements = "n_hc55,n_gradle,art_apk_debug,n_emulator,art_apk_dist,n_horseapk,art_tools_adb,dev_zfold,ss_android,cn_wifi,dev_mirai,cn_adb,n_app,cn_usb,art_tools_report,n_mainactivity,n_presentation,n_projservice,n_fileprovider,n_filesdir,art_jsonl,art_md,art_json,art_csv"
  -attr layout = "n_hc55:30,24|n_gradle:220,24|art_apk_debug:410,24|n_emulator:600,24|art_apk_dist:790,24|n_horseapk:980,24|art_tools_adb:1360,24|dev_zfold:410,150|ss_android:600,150|cn_wifi:980,150|dev_mirai:1170,150|cn_adb:1360,150|n_app:600,276|cn_usb:980,276|art_tools_report:1360,276|n_mainactivity:30,402|n_presentation:220,402|n_projservice:410,402|n_fileprovider:600,402|n_filesdir:980,402|art_jsonl:790,528|art_md:980,528|art_json:1170,528|art_csv:1360,528"
}
Viewpoint transacties "Transacties" {
  -description "Three animated chains: the capability scan (with the safeObserve failure path), the MediaProjection consent chain with its Android 14 ordering constraint, and the report chain."
  -attr src = "docs/architecture.md §5, §4.5 · core/ProbeRegistry.kt · core/Probe.kt · projection/CaptureEngine.kt · report/ReportScreen.kt"
  -attr canvas = "1790,940"
  -attr scenarios = "scan_chain,projection_chain,report_chain"
  -attr scenario_default = "scan_chain"
  -attr elements = "ui_dashboard,c_proberegistry,c_safeobserve,do_obs_error,p_device,p_codec,p_display,p_mediaroute,p_wifip2p,p_connectivity,p_audioroute,p_inputdevice,p_projcap,p_smartview,p_dex,p_androidauto,do_observation,c_dashboardkeys,c_sessionlogger,ds_stateflow,ui_logviewer,c_json,do_logrecord,art_jsonl,ui_projection,c_labpermissions,api_mpm,n_projservice,api_mp,rq_a14order,api_vd,api_imagereader,api_mediacodec,do_nottested_sink,ui_report,c_reportgen,art_md,art_json,art_csv,n_fileprovider,n_chooser"
  -attr layout = "ui_dashboard:30,24|c_proberegistry:222,24|c_safeobserve:414,24|do_obs_error:606,24|p_device:30,136|p_codec:222,136|p_display:414,136|p_mediaroute:606,136|p_wifip2p:30,248|p_connectivity:222,248|p_audioroute:414,248|p_inputdevice:606,248|p_projcap:30,360|p_smartview:222,360|p_dex:414,360|p_androidauto:606,360|do_observation:222,472|c_dashboardkeys:30,472|c_sessionlogger:606,472|ds_stateflow:798,472|ui_logviewer:990,472|c_json:414,584|do_logrecord:606,584|art_jsonl:606,696|ui_projection:990,24|c_labpermissions:990,136|api_mpm:1182,24|n_projservice:1374,24|api_mp:1566,24|rq_a14order:1374,136|api_vd:1566,136|api_imagereader:1566,248|api_mediacodec:1566,360|do_nottested_sink:1374,360|ui_report:990,584|c_reportgen:1182,584|art_md:1374,584|art_json:1374,696|art_csv:1374,808|n_fileprovider:1566,696|n_chooser:1566,808"
}
Viewpoint journeys "Journeys" {
  -description "The seven-step Miracast wizard and the whole in-car session, with the decision points and what the tester records at each one."
  -attr src = "docs/toyota-mirai-2025-test-procedure.md · SAFETY.md"
  -attr canvas = "1790,730"
  -attr scenarios = "miracast_wizard,in_car_session"
  -attr scenario_default = "miracast_wizard"
  -attr elements = "ba_tester,bp_prep,bo_baseline,bp_rowa,w1,w2,ev_intent,w3,ev_display,w4,w5,w6,ev_input,w7,bo_structural,bo_photo,bo_matrix,bp_input,ev_mouse,bo_verdict,bp_audio,bo_avrec,bp_dex,ev_dex,bp_auto,ev_order,bp_motion,ev_bench,ev_blocked,bp_close,bo_jsonl_run,bo_notfilled"
  -attr layout = "ba_tester:30,24|bp_prep:222,24|bo_baseline:414,24|bp_rowa:606,24|w1:30,144|w2:222,144|ev_intent:414,144|w3:606,144|ev_display:798,144|w4:990,144|w5:1182,144|w6:1374,144|ev_input:1566,144|w7:30,264|bo_structural:606,264|bo_photo:990,264|bo_matrix:1374,264|bp_input:222,384|ev_mouse:414,384|bo_verdict:606,384|bp_audio:990,384|bo_avrec:1182,384|bp_dex:222,504|ev_dex:414,504|bp_auto:798,504|ev_order:990,504|bp_motion:222,624|ev_bench:414,624|ev_blocked:606,624|bp_close:990,624|bo_jsonl_run:1182,624|bo_notfilled:1374,624"
}

# ------------------------------------------------- scenarios (animation)
Slide scan_chain "a. Capability scan (dashboard -> 12 probes -> evidence)" {
  -attr src = "docs/architecture.md §5 · core/ProbeRegistry.kt"
  -attr step_01 = "t01 :: Tester taps RUN DEVICE SCAN on the dashboard; ProbeRegistry.runAll(context, onProgress) starts. [docs/architecture.md §5]"
  -attr step_02 = "t02 :: runAll walks ProbeRegistry.all in scan order and calls probe.safeObserve for each. [core/ProbeRegistry.kt]"
  -attr step_03 = "t03 :: Probe 1/12: DeviceProbe - read-only, idempotent, non-throwing. [scan/DeviceProbe.kt]"
  -attr step_04 = "t04 :: Probe 2/12: CodecProbe - read-only, idempotent, non-throwing. [scan/CodecProbe.kt]"
  -attr step_05 = "t05 :: Probe 3/12: DisplayProbe - read-only, idempotent, non-throwing. [display/DisplayProbe.kt]"
  -attr step_06 = "t06 :: Probe 4/12: MediaRouteProbe - read-only, idempotent, non-throwing. [display/MediaRouteProbe.kt]"
  -attr step_07 = "t07 :: Probe 5/12: WifiP2pProbe - read-only, idempotent, non-throwing. [net/WifiP2pProbe.kt]"
  -attr step_08 = "t08 :: Probe 6/12: ConnectivityProbe - read-only, idempotent, non-throwing. [net/ConnectivityProbe.kt]"
  -attr step_09 = "t09 :: Probe 7/12: AudioRouteProbe - read-only, idempotent, non-throwing. [audio/AudioRouteProbe.kt]"
  -attr step_10 = "t10 :: Probe 8/12: InputDeviceProbe - read-only, idempotent, non-throwing. [input/InputDeviceProbe.kt]"
  -attr step_11 = "t11 :: Probe 9/12: ProjectionCapabilityProbe - read-only, idempotent, non-throwing. [projection/ProjectionCapabilityProbe.kt]"
  -attr step_12 = "t12 :: Probe 10/12: SmartViewProbe - read-only, idempotent, non-throwing. [samsung/SmartViewProbe.kt]"
  -attr step_13 = "t13 :: Probe 11/12: DexProbe - read-only, idempotent, non-throwing. [samsung/DexProbe.kt]"
  -attr step_14 = "t14 :: Probe 12/12: AndroidAutoProbe - read-only, idempotent, non-throwing. [auto/AndroidAutoProbe.kt]"
  -attr step_15 = "t15 :: FAILURE PATH: a probe throws. safeObserve catches the throwable. [core/Probe.kt]"
  -attr step_16 = "t16 :: It becomes Observation.error whose note says the capability itself is untested - an ERROR observation, never a negative result about the device. [docs/PRINCIPLES.md P4]"
  -attr step_17 = "t23 :: Every probe returns a List<Observation>; each observation carries its own LabStatus. [docs/architecture.md §3]"
  -attr step_18 = "t29 :: SessionLogger.logAll(found): the single sink for the whole process. [core/ProbeRegistry.kt]"
  -attr step_19 = "t30 :: One LogRecord per observation: timestamp, elapsedRealtimeMs, testRunId, category, event, status, details. [README.md]"
  -attr step_20 = "t31 :: core/Json.kt serialises it - 50 lines, dependency-free. [docs/DEPENDENCIES.md]"
  -attr step_21 = "t32 :: Appended to evidence/run-<ts>-<id>.jsonl and flushed per line, so a process kill loses at most the current record. [docs/architecture.md §4.1]"
  -attr step_22 = "t33 :: The same record lands in the in-memory StateFlow, capped at 5000. [core/SessionLogger.kt]"
  -attr step_23 = "t35 :: Dashboard and screens collect the StateFlow; DashboardKeys.missingFrom() marks any key no probe produced. [docs/architecture.md §3, §5]"
  -attr step_24 = "t36 :: capability_scan_complete is logged with the probe and observation counts. [core/ProbeRegistry.kt]"
}
Slide projection_chain "b. MediaProjection consent chain (Android 14 ordering)" {
  -attr src = "projection/CaptureEngine.kt KDoc · docs/architecture.md §4.5"
  -attr step_01 = "t40 :: MediaProjectionScreen requests POST_NOTIFICATIONS through LabPermissions, with a tester-facing reason. Denial degrades the probe to NOT_TESTED; it never blocks the app. [SAFETY.md]"
  -attr step_02 = "t41 :: MediaProjectionManager.createScreenCaptureIntent - the consent dialog. Step 1 of the non-negotiable order. [projection/CaptureEngine.kt]"
  -attr step_03 = "t42 :: Consent granted -> start the foreground service, type mediaProjection. Step 2. [docs/architecture.md §4.5]"
  -attr step_04 = "t51 :: ProjectionService logs projection_service_started (CONFIRMED) with the SDK level. It holds no capture logic. [projection/ProjectionService.kt]"
  -attr step_05 = "t43 :: getMediaProjection(resultCode, data). Step 3. [projection/CaptureEngine.kt]"
  -attr step_06 = "t48 :: CONSTRAINT: Android 14+ refuses createVirtualDisplay unless the FGS is already running, and requires registerCallback before it. Getting the order wrong throws SecurityException. [docs/architecture.md §4.5, docs/DEPENDENCIES.md]"
  -attr step_07 = "t44 :: registerCallback (step 4) and only then createVirtualDisplay (step 5). [projection/CaptureEngine.kt]"
  -attr step_08 = "t45 :: The VirtualDisplay draws into the Surface of an ImageReader. [projection/CaptureEngine.kt]"
  -attr step_09 = "t46 :: Frames arrive on a single HandlerThread and feed the H.264 encoder. [projection/H264Encoder.kt]"
  -attr step_10 = "t47 :: Capture FPS, VirtualDisplay metrics and encoder statistics are logged. [README.md screens table]"
  -attr step_11 = "t49 :: P3: what this proves is what the phone can capture and encode locally - nothing about what the Toyota head unit receives. [projection/CaptureEngine.kt KDoc]"
  -attr step_12 = "t50 :: So an explicit NOT_TESTED observation is logged at the end of every session, so the claim cannot drift in the report. [docs/PRINCIPLES.md P3]"
}
Slide report_chain "c. Report chain (SessionLogger -> Markdown / JSON / CSV -> share)" {
  -attr src = "docs/architecture.md §5 · report/ReportGenerator.kt · report/ReportScreen.kt"
  -attr step_01 = "t60 :: EXPORT REPORT on the report screen calls ReportGenerator.build. [report/ReportScreen.kt]"
  -attr step_02 = "t61 :: SessionLogger supplies every LogRecord of the run. [report/ReportGenerator.kt]"
  -attr step_03 = "t62 :: 20 sections are always emitted; sections 15-18 (confirmed / inferred / unsupported / still requires hardware) are derived mechanically from LabStatus, so no prose step can promote NOT_TESTED. [docs/architecture.md §3]"
  -attr step_04 = "t63 :: EXPORT JSON writes SessionLogger.exportJson(). [report/ReportScreen.kt]"
  -attr step_05 = "t64 :: EXPORT CSV writes SessionLogger.exportCsv(). [report/ReportScreen.kt]"
  -attr step_06 = "t65 :: FileProvider.getUriForFile(packageName + '.fileprovider', file) for the Markdown report. [report/ReportScreen.kt shareFile]"
  -attr step_07 = "t66 :: The same for the JSON export. [report/ReportScreen.kt]"
  -attr step_08 = "t67 :: The same for the CSV export. [report/ReportScreen.kt]"
  -attr step_09 = "t68 :: ACTION_SEND chooser with FLAG_GRANT_READ_URI_PERMISSION. The files never leave app-private storage otherwise - the app has no INTERNET permission. [report/ReportScreen.kt, SAFETY.md]"
  -attr step_10 = "t69 :: export_shared is itself logged, so the evidence file records that the evidence was exported. [report/ReportScreen.kt]"
}
Slide miracast_wizard "Seven-step Miracast wizard (test procedure §2)" {
  -attr src = "docs/toyota-mirai-2025-test-procedure.md §2"
  -attr step_01 = "j01 :: The tester opens MIRACAST TEST. The seven-step wizard drives the whole of section 2 (matrix row B)."
  -attr step_02 = "j02 :: Step 1 - Scan baseline. Recorded: display / route / Wi-Fi / P2P / audio snapshot."
  -attr step_03 = "j03 :: Step 2 - Start Smart View from the quick panel and pick the Mirai. DECISION: the app cannot connect for you; it opens the right settings panel only if the intent resolves. A resolvable intent is CONFIRMED, an unresolvable one UNSUPPORTED for this device."
  -attr step_04 = "j04 :: Step 3 - Wait, watching the live change list."
  -attr step_05 = "j05 :: Recorded: every display, route, interface and audio-route change, timestamped."
  -attr step_06 = "j13 :: HONEST LIMIT: negotiated resolution, framerate, codec, HDCP state and UIBC advertisement are reported NOT_TESTED, permanently. They live in the Wi-Fi Display / wpa_supplicant layer, not exposed to third-party apps on a non-rooted device."
  -attr step_07 = "j06 :: Step 4 - Launch scene on external display."
  -attr step_08 = "j12 :: Recorded by the tester: photograph the car screen with another phone. The number of distinct sweep positions gives the delivered frame rate; both frame counters in one frame give the latency."
  -attr step_09 = "j07 :: The diagnostic pattern appears on the car screen: bouncing object, frame counter, 60-position sweep, 1-pixel bars, RGB/greyscale blocks."
  -attr step_10 = "j08 :: Step 5 - For each sub-test, tap what you actually see: VISIBLE / DISTORTED / BLACK / AUDIO ONLY / NOT SHOWN."
  -attr step_11 = "j09 :: Step 6 - Touch the numbered targets on the car screen."
  -attr step_12 = "j10 :: DECISION: any input event that arrives on the phone is recorded with its source, device id, coordinates and pressure. Nothing arriving is NO INPUT OBSERVED for this session - never 'the sink has no back channel'."
  -attr step_13 = "j11 :: Step 7 - Export: a wizard summary with a status per line."
}
Slide in_car_session "In-car session, sections 0-8 (test procedure)" {
  -attr src = "docs/toyota-mirai-2025-test-procedure.md"
  -attr step_01 = "j20 :: §0 Before you leave. Install a debug build and confirm it starts. Grant nearby-devices and notifications when the app asks and explains why; audio and Bluetooth are optional - denying them yields NOT_TESTED, which is correct and harmless."
  -attr step_02 = "j21 :: §0 step 3 - export a Markdown report labelled baseline-no-vehicle: a baseline with no car present, the single most useful reference you will have."
  -attr step_03 = "j22 :: §1 In the car. Vehicle stationary, in park, ignition on (SAFETY.md)."
  -attr step_04 = "j23 :: §1 Row A (OFF / OFF): RUN DEVICE SCAN, then ANDROID AUTO COEXISTENCE TEST row A. Record before anything is connected. Note displays, any p2p* interface, active audio output, and whether Android already reports car mode."
  -attr step_05 = "j02 :: §2 Row B - the seven-step Miracast wizard runs here (see the wizard scenario)."
  -attr step_06 = "j24 :: §3 Touch-back: does the Mirai screen drive the phone?"
  -attr step_07 = "j25 :: DECISION: sanity-check the instrument first with a Bluetooth or USB-C mouse. If the event monitor does not log its movement, the monitor is broken and any negative result below is meaningless."
  -attr step_08 = "j26 :: Run the 3x5 target grid, touching each numbered target on the car's screen."
  -attr step_09 = "j27 :: Recorded: the touch-back verdict with its percentage and source list. NO INPUT OBSERVED is recorded as such, not as 'unsupported'."
  -attr step_10 = "j28 :: §4 Audio: silence, mono tone, stereo L/R identify, continuous tone, latency click, A/V sync pulse."
  -attr step_11 = "j29 :: Recorded: one external A/V recording. The app logs its own timestamps; the end-to-end number can only come from that recording, and the app says so rather than pretending otherwise."
  -attr step_12 = "j30 :: §5 Samsung DeX: start Wireless DeX from the quick panel."
  -attr step_13 = "j31 :: DECISION: is the Mirai even offered as a target? Compare the external display's density and flags against plain Smart View - a different density is the usual discriminator. Record the exact wording if DeX refuses; the wording of the refusal is data."
  -attr step_14 = "j32 :: §6 Android Auto coexistence, rows C-F, worked in order."
  -attr step_15 = "j33 :: DECISION: for D and F, try both connection orders - AA first then Miracast, and the reverse. Watch the Wi-Fi frequency readout: both want the 5 GHz radio."
  -attr step_16 = "j34 :: Recorded: one report per matrix row, plus time-until-disconnect from the in-app timer whenever a session drops. Which order survives is the finding."
  -attr step_17 = "j35 :: §7 Motion state - observation only. Read the safety card at the top of the screen. The car stays stationary."
  -attr step_18 = "j36 :: DECISION: only with a bench head unit or a legitimate stationary test environment is the receiver's vehicle-state input varied - and nothing in this project changes that input."
  -attr step_19 = "j37 :: Record with the marker buttons what the head unit does: PARK, VIDEO VISIBLE, VIDEO BLOCKED, AUDIO ONLY, CONNECTION LOST."
  -attr step_20 = "j38 :: If the Toyota head unit disables video based on vehicle state, that is recorded as a finding and the project stops there. No bypass is built for use on public roads, or anywhere else. [SAFETY.md]"
  -attr step_21 = "j39 :: §8 Close the session: EXPORT REPORT to Markdown, JSON and CSV; note the paths shown on screen."
  -attr step_22 = "j40 :: With a laptop: collect-dumpsys.sh after and pull-evidence.sh. Without: the in-app share sheet. Then disconnect Smart View and Android Auto."
  -attr step_23 = "j41 :: Anything you did not do stays NOT_TESTED in the report. Do not fill it in from memory afterwards."
}

# -------------------------------------------------------------- positions
pos dr_unknown 30, 24
pos dr_false 222, 24
pos as_state 414, 24
pos go_empirical 798, 24
pos go_experiments 1182, 24
pos go_evidence 990, 24
pos rq_grade 30, 142
pos rq_mechanical 222, 142
pos rq_safeobserve 414, 142
pos rq_missing 606, 142
pos rq_sink 798, 142
pos p1 30, 260
pos p2 222, 260
pos p3 414, 260
pos p4 606, 260
pos p5 798, 260
pos p6 990, 260
pos p7 1182, 260
pos p8 1374, 260
pos p9 30, 378
pos p10 222, 378
pos p11 414, 378
pos p12 606, 378
pos cs_rule 30, 496
pos cs_can 222, 496
pos cs_spoof 414, 496
pos cs_firmware 606, 496
pos cs_aaspoof 798, 496
pos cs_aacat 990, 496
pos cs_bus 1182, 496
pos cs_evasion 1374, 496
pos cs_internet 30, 614
pos cs_private 222, 614
pos cs_bench 414, 614
pos cs_root 606, 614
pos cs_escal 798, 614
pos cs_novehicle 990, 614
pos cs_nomiracast 1182, 614
pos cs_nohidden 1374, 614
pos cap_inventory 30, 732
pos cap_wizard 222, 732
pos cap_scene 414, 732
pos cap_audio 606, 732
pos cap_touchback 798, 732
pos cap_projection 990, 732
pos cap_matrix 1182, 732
pos cap_motion 1374, 732
pos cap_report 30, 850
pos mod_core 600, 148
pos c_evidence 30, 24
pos c_probe 220, 24
pos c_sessionlogger 410, 24
pos c_proberegistry 1170, 148
pos c_labpermissions 790, 24
pos c_dashboardkeys 980, 24
pos c_json 600, 24
pos do_observation 1360, 24
pos do_logrecord 1360, 148
pos mod_scan 30, 272
pos mod_display 220, 272
pos mod_net 410, 272
pos mod_audio 600, 272
pos mod_input 790, 272
pos mod_projection 980, 272
pos mod_scene 1170, 272
pos mod_samsung 410, 396
pos mod_auto 600, 396
pos mod_report 790, 396
pos mod_ui 220, 148
pos c_labnavhost 30, 148
pos dev_zfold 410, 150
pos dev_mirai 1170, 150
pos ss_android 600, 150
pos n_app 600, 276
pos n_mainactivity 30, 402
pos n_presentation 220, 402
pos n_projservice 410, 402
pos n_fileprovider 600, 402
pos n_filesdir 980, 402
pos art_jsonl 790, 528
pos art_md 980, 528
pos art_json 1170, 528
pos art_csv 1360, 528
pos n_hc55 30, 24
pos n_gradle 220, 24
pos art_apk_debug 410, 24
pos n_emulator 600, 24
pos art_apk_dist 790, 24
pos n_horseapk 980, 24
pos art_tools_adb 1360, 24
pos art_tools_report 1360, 276
pos cn_wifi 980, 150
pos cn_usb 980, 276
pos cn_adb 1360, 150
pos ui_dashboard 30, 24
pos c_safeobserve 414, 24
pos do_obs_error 606, 24
pos p_device 30, 136
pos p_codec 222, 136
pos p_display 414, 136
pos p_mediaroute 606, 136
pos p_wifip2p 30, 248
pos p_connectivity 222, 248
pos p_audioroute 414, 248
pos p_inputdevice 606, 248
pos p_projcap 30, 360
pos p_smartview 222, 360
pos p_dex 414, 360
pos p_androidauto 606, 360
pos ds_stateflow 798, 472
pos ui_logviewer 990, 472
pos ui_projection 990, 24
pos api_mpm 1182, 24
pos api_mp 1566, 24
pos api_vd 1566, 136
pos api_imagereader 1566, 248
pos api_mediacodec 1566, 360
pos rq_a14order 1374, 136
pos do_nottested_sink 1374, 360
pos ui_report 990, 584
pos c_reportgen 1182, 584
pos n_chooser 1566, 808
pos ba_tester 30, 24
pos bp_prep 222, 24
pos bo_baseline 414, 24
pos bp_rowa 606, 24
pos w1 30, 144
pos w2 222, 144
pos w3 606, 144
pos w4 990, 144
pos w5 1182, 144
pos w6 1374, 144
pos w7 30, 264
pos ev_intent 414, 144
pos ev_display 798, 144
pos bo_structural 606, 264
pos bo_photo 990, 264
pos bp_input 222, 384
pos ev_mouse 414, 384
pos ev_input 1566, 144
pos bo_verdict 606, 384
pos bp_audio 990, 384
pos bo_avrec 1182, 384
pos bp_dex 222, 504
pos ev_dex 414, 504
pos bp_auto 798, 504
pos ev_order 990, 504
pos bo_matrix 1374, 264
pos bp_motion 222, 624
pos ev_bench 414, 624
pos ev_blocked 606, 624
pos bp_close 990, 624
pos bo_jsonl_run 1182, 624
pos bo_notfilled 1374, 624
