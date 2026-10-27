# JOY CREATOR NAV SPEC — durable (2026-09-22)

Owner: JoyRaptor. This is the DECISION file. `APP_MANIFEST.md` is the inventory
(code-true). `NAV_PLAN.md` is the proposal history. THIS file is what gets built.
The HTML (`tasks/design/UI-JoyCreator-grand-design.html`) is this file drawn.

## 1. What Joy Creator is
Mobile full creative stack. Five archetypes, all first-class — no "primary user"
that demotes the rest:
- A. CAPTURE-FIRST: camera/screen/voice/dual/photo/triggered/remote. Speed matters.
- B. ANIMATE: generated assets → SpriteLab/Avatar/Puppet → Studio. Never opens Capture.
- C. AUDIO: mixing, cleanup, transcript, visualiser, beats. Timeline is a waveform.
- D. ORGANIZE/WATCH: library as home — browse, search, play, trash, projects.
- E. MULTICAM: security / creative multi-camera — remote phone as camera, motion
  triggers, markers, Story Board review.
Navigation must let each archetype live in 1-2 rooms and never pay for the rest.

## 2. Vocabulary (decided, do not re-debate)
Smart Detection (was Forensics), Snapshots (was Evidence), Story Board (was
Evidence Board), Activity Summary, Overview, Session Report, People, Bits (was
Gadgets), Setup (was Settings), Library, Studio, Capture, Remote, Sound/VizLab.

## 3. Design tokens (frozen — new surfaces use these or add a ROLE token)
Ground `#000000`, surface `#0D0D10`, panel `#111114`, raised `#1C1C22`,
pressed `#26262E`, line `#2C2C35`. Screen ink `#E4E4E7/#A1A1AA/#71717A/#4B4B55`,
label `#C4C4CE`. Drawer ink `#F2F2F5/#C9C9D3/#C4C4CE` OVER `rgba(0,0,0,.64)+blur20`
ONLY. States: armed/selected `#22D3EE`, live `#F43F8E`, careful `#FBBF24`,
off `#33333C`, danger `#FA3D5D`. Go gradient `#35F6BF→#97FE8B`, ink-on-go
`#050507`. Rooms: Studio aqua, Capture red-pink→neon, Sprites pink→violet,
Avatar violet→deep, Library blue→cyan, Sound orange→deep, Finder golden,
Remote indigo→blue, Joybot violet→indigo. Fill=Pill controls, glass pills over
video. Archivo display / IBM Plex body+mono. Press 140ms scale(.97), swap 170ms,
drawer 320ms, 100×/day controls 0ms. One filled action per view.

## 4. Navigation principles (replace the nav bar — no modal)
P1 FLOW CARRIES THE 90%: Capture→Library→Studio, each room ends in its next step.
P2 SEARCH CARRIES THE RARE 10%: swipe-down palette finds every room, all settings,
   projects, recordings, actions. Offline, recency-ranked.
P3 JOYBOT CARRIES INTENT: "take me to…", "turn on…", proactive VERIFIED-only line.
P4 HOLD-TO-FAN CARRIES DEPTH: hold floor word/hero/tile → children arc out under
   thumb. Tap = daily, hold = rare. Mirrors left-hand.
P5 TEMPORAL LOBBY: continue what's live (recording/export/AI/transcribe strip) +
   recents + carry-on. Most opens end in 1 tap.
P6 MODULAR SETTINGS: one fragment per area, single source in Setup hub; rooms show
   REFERENCE LINKS (`OverlayNavUtil.show`), never copies.
P7 DURABLE MODULES: SpriteLab / VizLab / Puppet-Avatar rigs = asset stores OUTSIDE
   the editor, dual-entry (room + in-editor drawer), durable across projects, plus
   LIVE use (leave-on visualizer, puppet-in-recording). New modules follow this.
P8 HOUSE = WAY BACK: back always lands lobby. Orb = everywhere AI + way-back.
   Editor NEVER shows global nav (transport/tool row are sacred).
P9 LIVE STRIP: one persistent strip for recording/export/AI/transcribe, hairline +
   expandable, survives leaving editor. Background work is never lost.

## 5. Every destination — home + solution (exhaustive)
| # | Destination (code) | Archetype | HOME (where it lives) | SOLUTION (how reached / states) |
|---|---|---|---|---|
| 1 | Lobby `LobbyFragment` | all | default launch, back target | marquee + hero + recents + New row + bot line + floor (5 fixed, hold-to-fan) + search + live strip |
| 2 | Capture Home `HomeFragment`/`FadRecHomeFragment` | A,E | marquee CAPTURE, floor —, New Recording | viewfinder + start/stop/pause + torch + zoom + AF/EV chips + 3-row storage card + clock + stats + elapsed hero; hold fans 7 modes; settings via LINKS (Video/Audio/Dual/ScreenRec) |
| 3 | Capture sidebar `HomeSidebarFragment` | A,E | hamburger on Capture | profiles, What's New, Tips, preview toggles, Discord, Bits entry → folds into Setup hub over time, hamburger stays until hub proves itself |
| 4 | Fullscreen preview | A,E | preview expand btn | takeover surface, back = return |
| 5 | Photo capture `PhotoCaptureActivity` | A,D | camera photo btn, shortcuts | headless, lands in Library SHOT |
| 6 | Pro upsell `FadCamProActivity` | — | header crown | DECISION OWED (conflicts "no subscription ever"): keep/reword/cut |
| 7 | Quick video settings `VideoSettingsFragment` | A,E | camera row via overlay | LINKED module (source in Setup hub §17) |
| 8 | Records `RecordsFragment` | D,A,E | marquee LIBRARY door, search | 7 chips + 4 sub-rows + batch + dock + sort/grid sheet; search palette covers global; dead `searchView` removed or wired |
| 9 | Projects-A `FaditorMiniFragment` | B,C,D | Studio list, bot line, search | start (file picker) + recents tap/long-press + export dock; MERGE target for B-system |
| 10 | Projects-B FadRec dialog | (legacy) | — | MERGE into 9, delete dialog |
| 11 | Photos | D | Library SHOT filter | intermixed + sub-filters → viewer; no separate grid (decision: keep) |
| 12 | Trash `TrashFragment` | D | Library menu | restore/permanent/empty-all, filters, auto-delete info, applock |
| 13 | Player `VideoPlayerActivity` | D,E | tap video | ExoPlayer + frag-MP4 path + speed + waveform + resume; Watch variant |
| 14 | Viewer `ImageViewerActivity` | D | tap photo | Glide + docks + snapshot info |
| 15 | Remote `RemoteFragment` | E,A | floor Remote, marquee, search | LAN HLS toggle + URL + modes + endpoints/clients + quality + uptime/battery + auth + cloud; Watch = start/stop |
| 16 | Studio list (=9) | B,C | marquee STUDIO | IS the durable-module shelf for B/C |
| 17 | Editor `FaditorEditorActivity` | B,C | Studio row, Carry on, batch | timeline; NO global nav inside; drawers ×15 to spec (phase LAST) |
| 18 | SpriteLab `SpriteSheetEditorActivity` | B | marquee SPRITES + in-editor drawer | DURABLE module (done pattern) |
| 19 | Avatar `AvatarStudioActivity` | B | marquee AVATAR + in-editor | DURABLE; drive before promoting (largest 🟡) |
| 20 | Puppet (no activity) | B | inside editor + webcam bubble | DURABLE rig store; same dual-entry when split out |
| 21 | VizLab (no code) | C | marquee VIZ (placeholder→Studio fallback today) | BUILD store+live widget, then room. Drawer engine exists. |
| 22 | Sound (drawers+transcript+waveform) | C | in-editor + VizLab room when built | audio people enter via Studio list → audio project; VizLab room is their lobby home later |
| 23 | Story Board (gallery+markers+rough-cut) | E,B | floor Finder → Story Board | reskin in place, Smart-Detection toggle gate; auto-markers → editor timeline + chapters |
| 24 | Smart Detection settings + MotionLab | E | Setup hub + Story Board link | engines stay, copy reskinned (B2 done in strings) |
| 25 | Setup hub `SettingsHomeFragment` | all | floor Setup, search, palette | GROUPED single scroll: Capture / Library / Studio / Sound / System / About. OWNS all fragments below; rooms LINK. Prune 9 themes → JoyCreator + 1 dark alt |
| 26 | Appearance | all | Setup hub | themes/langs/icons/logo/eye (eye = user-choice swatches, keep) |
| 27 | Video / Audio / DualCam / ScreenRec | A,E | Setup hub (SOURCE) + Capture links | modules, never copies |
| 28 | Watermark (+location/speed/etc) | A,E | Setup hub + Capture link | keep toggles, GPS/sensor status |
| 29 | LocationPrivacy / Storage(SAF) / Behavior / Security / Notifications | all | Setup hub | facelift-only |
| 30 | Shortcuts&Widgets + clock widget | A,D | Setup hub | MERGE with Bits rows (one Bits surface) |
| 31 | Player settings / Watch hub / About / Review / Readme / Onboarding toggle / Auto-update / Debug / Prefs backup | all | Setup hub | facelift; About hardcodes fixed; locales sweep parked |
| 32 | Bits (Torch full, QR full, 8 stubs) | A,D | floor Bits, sidebar, search | Torch+QR stay; 8 stubs: CUT-or-COMMIT (no stub facelift). Backends (`SensorDataProvider`,`NoiseMonitor`) reused if committed |
| 33 | Clock widget (AppWidget) + home clock card | D | OS picker + Setup; card on Capture | stays; tap → Setup as today |
| 34 | Joybot `ChatAssistantActivity` | all | orb everywhere + lobby line | chat + 55 tools + routing ("take me to…"); claymation sprites when delivered |
| 35 | Search palette (NEW) | all | swipe-down / tap magnifier | finds rooms+settings+projects+recordings+actions; recency-ranked; REPLACES dead searchView |
| 36 | Live strip (rec/export/AI/transcribe) | all | persistent top (hairline+expand) | survives editor-leave; tap returns; stacked when >1 |
| 37 | Onboarding `OnboardingActivity` → What's New | new | first run, Settings re-enable | keep flow (Welcome/Permissions/Human), slideshow per Marquee §03 |
| 38 | System: 15 icon aliases / Wear suite / headless / shortcuts / PrivacyBlack / Cloud | mixed | OS / settings | Play build strips disguise set (LAUNCH §3); Wear untouched; support_url EMPTY (needs JoyRaptor URL); update org JoyRaptor/JoyCreator (no releases = no offer, correct) |

## 6. Flow map (each arrow is a tap unless noted)
Launch → Lobby → (marquee/hero/recents/New/floor/search/strip).
Capture → (record → Live strip) → Library. Library → (Open in Studio) → Editor.
Editor → (Export → Live strip) → Library. Sprites/Avatar/Viz ↔ Editor (dual-entry).
Story Board → (rough-cut handoff) → Editor. Bits/Setup → overlays (back = return).
Joybot ↔ any (orb). Search → any. Back → Lobby (except editor-back = Studio list).
Full matrix with states is drawn in the HTML.

## 7. Phase order
0 Tokens frozen. 1 Shell (floor+fan, strip, palette, back/house). 2 Setup hub +
links. 3 Merges (Projects, Finder, Bits) + rebuilds (Viz store+widget, search).
4 Facelift per surface, device-verified. 5 Editor drawers LAST. 6 Cut list with owner.

## 9. Adversarial audit fixes (2026-09-22 — 3 sweeps vs code, all folded)
Status tags: FIXED-in-spec (wording corrected below) vs OWED (new work this audit created).

FIXED:
- Library chips = ALL/CAMERA/SCREEN/FADITOR/STREAM/SHOT/MINIAPPS (not All/Camera/Screen/Shots/Projects). Sub-rows: camera ALL/BACK/FRONT/DUAL, faditor ALL/CONVERTED/MERGE, shot ALL/BACK/SELFIE/FADREC, miniapps ALL/QR. DUAL files fold into CAMERA. STREAM has no sub-row.
- Stubs = 9, not 8: compass, sound_meter, sensor, speedometer, clinometer, pedometer, metal_detector, parking_marker, qr_generator. QR scanner FULL vs generator STUB — never merged.
- Floor TODAY misroutes: Bits→Capture(0), Packs→Setup(4). No Bits tab, Packs has no code identity. Spec floor = TARGET state, not current.
- Trash filters = ALL/VIDEOS/EVIDENCE (code) — "Snapshots" is strings-only rename; grid behavior (month headers, count badges, 10-policy auto-delete, restore-DESTINATION picker DOWNLOADS/INTERNAL/SAF, in-place playback, applock) preserved.
- Player has NO share / NO open-in-Studio; viewer has NO share/delete/edit. Real contract kept: gestures, zoom, quick-speed ramp+sheet, waveform toggle, timeout, 5s resume, double-tap seek pref, background audio, forensics deep-link (OPEN_AT_MS/OPEN_PAUSED), dual info sheets (Scan vs Snapshot).
- Photo = DUAL path (in-recording still-grab via RecordingService vs idle headless). Both kept.
- Batch = half-built: BatchSave is coming-soon toast; real surface = actions sheet + save copy/move/tree sheets + direct openSelectedInFaditor (+standard/merge sheet). Two docks share one view (delete/save/export + batch-op). RecordsOptionsBottomSheet = dead (0 refs) → delete or revive.
- Capture = TWO stacks: HomeFragment vs FadRecHomeFragment (MediaProjection+denial path, rowCamera→ScreenRec settings, torch/AF/zoom hidden, floating-controls+AnnotationService+overlay permission, mute dual-role, live-viz arm, resolution card). Facelift covers both.
- Storage/estimate/camera = 3 distinct destinations + picker trees (elapsed 6 sub-pickers, clock color/format/appearance, indicator ring/pill/bar+total). Clock = Gregorian+Hijri. Preview = state machine (avatar sleep/wake, iris guards, preview-only debounce, long-press bounce, zoom HUD+minimap, paused overlay). Torch flash-gating, camswitch debounce, mirror states, GPS banner rule kept.
- Setup: mode filter ALL/FADCAM/FADREC + divider animation KEPT; ScreenRec hidden rows (dual-stream gate, auto-pause, tint) KEPT; sidebar update cards (stable/beta/pro) = third update surface KEPT; hub grouping rewrite must migrate README-first/QuickAccess/inline values; theme prune must answer stored-choice migration; 13 langs/16 icons/logo/eye-live-preview kept.
- Torch depth (patterns/screen-light+brightness/auto-off+units/notification+external-receiver, headless vs full) + QR depth (gallery import, in-scanner torch, rotation decode, JPEG+metadata.json save + broadcast) + widget depth (tap→Shortcuts overlay, time/date/arabic/branding/black-bg, minute ticks, bitmap type) kept.
- Player settings = 9 rows (not facelift). About = full surface (version/source/donate/updates/email/Discord/FAQ, stable→sheet/beta→dialog, hardcoded attribution lines to fix). TV D-pad kept. Photo watermark 15s budget noted. Screenshot = accessibility-trigger with permission/failure path — needs designed states.
- Remote cockpit kept ENTIRE: wifi bars+health, inverted rings, data/segments/health rows, local/cloud client split, mode selector+token refresh, usage card, auto-lock policies, copy guard, URL switch. E2E row (GONE, orphaned key logic) + legacy cloud row (hidden) → explicit dead-or-revive. "Phone B as 2nd camera" + "motion trigger" rows in HTML were INVENTED — multicam ingest/trigger have no native row (new work if E wants it in Remote).
- Sprite = project-LOCKED (null→toast+finish; lobby falls back to Studio) vs Avatar = ALREADY DURABLE (libraryMode+chooser+new-from-image+bundle save). Fix = copy Avatar library mode to Sprite. NOT a joint facelift.
- AI: ~55 tools but prompt numbering broken (19b, 33b-f, dup 33c/d) — renumber risk. NO navigate tool exists — routing is NEW work. Kept: offline FAQ, API-key gate, proposal cards (narrative/b-roll/avatar), EditScript validation + headless apply, AIJobStore resume. Degraded/confirm/survival states must be designed.
- Finder rename = strings done, code still forensics (6 surfaces + tags + DB labels + sort/grid/batch/info/delete + chips + classified-hide). Needs migration/alias plan, not "reskin in place".
- Story Board rough-cut handoff = MISSING (export center is zip-via-SAF only; no timeline intent). New work.
- VizLab = ZERO code (lobby tab=-1 → Studio fallback). HTML room = TARGET, not facelift.
- Search palette / hold-to-fan / live-4-strip / hub-links / Projects-merge = NEW work (zero implementations today). Spec P2/P4/P6/P9 are targets. Overlay nesting bug (dismiss vs popLevel, Watch leak) must be fixed as part of hub-links. Watermark NEW badge never clears (store+key mismatch + wrong string) — fix. Location = 3 surfaces, 1 orphaned — pick canonical. Behavior/LocationPrivacy/Advanced/Watch-hub orphaned (0 inbound refs) — wire or delete. Readme child-FM bug, icon-swap launcher risk, debug-switch dead code, onboarding-lang dialog dead code — fix list.
- FadMic third mode: exists, click blocked to coming-soon, persisted value coerced — acknowledge (cut or commit).
- Pro crown vs "no subscription ever", support_url EMPTY (onboarding reads it live — needs fallback design), update org, card-rail fold/arrival-pulse/fast-scroller/swipe-refresh/stats/applock/hide-thumbnails — all kept until decided.
- Editor entry wider than spec: EXTRA_START_BLANK_AUDIO (audio-first), EXTRA_LOAD_BACKUP (recovery), EXTRA_VIDEO_URIS multi-import, fallback+finish, integrity scan, remux deferral, 5 lane migrations. Audio + broken-project users get nav homes.
- Floor = Finder/Remote/Bits/Packs/Setup — Packs UNDEFINED (no code identity). Marquee = 5 rooms (Studio/Capture/Sprites/Avatar/Viz). New-Character chip uses Sprites index + Avatar glyph (off-by-one — pick one). startNewInRoom collapses non-Capture to Studio (B fails 1-tap — needs per-room new). Recording recents route to Library (2 taps, not 1 — honest).
- Onboarding = 3 slides Welcome/Permissions/Human → Main (WhatsNew does NOT auto-open; WebView+5s gate MARKS onboarding complete on close — do not rewire blindly). HTML "7-phrase slideshow" = Marquee intro slide content, not slide count — reword.

OWED (new work this audit created): palette scope/index/rank spec; fan gesture+mode-list+switcher migration; strip stacking/return/survival; hub grouping migration + link-vs-source + back-stack contract (+Watch pop fix); Projects merge + entry contract; FADREC-shot sorting ruling; search must absorb filterHelper/checklist/sort state; E2E/cloud dead-or-revive; Sprite library-mode copy; AI navigate tool + numbering fix + degraded/confirm states; forensics migration plan; rough-cut handoff; Viz store+widget; Packs definition; per-room New; recording 1-tap ruling; screenshot states; FadMic ruling; Pro/support/update/theme-migration/fallback rulings; blank-audio + backup-recovery homes.

## 8. Open decisions (owner)
D1 Setup grouping + Capture-link pattern (§6). D2 8 stubs cut-or-commit (which?).
D3 Viz build-now or drop-room. D4 Records search build-or-cut. D5 Pro crown.
D6 support_url. D7 theme prune (which dark alt survives?).
