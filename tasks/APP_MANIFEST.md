# APP MANIFEST — every destination, one line each (2026-09-22)

Source: 4 parallel code sweeps today. Shell = `MainActivity` tabs 0-6 + `AndroidManifest.xml`.
Prior art: `tasks/design/UI-Five-front-doors-options.html` §02 inventory (25 destinations).
This file is the code-true version of that checklist — nothing lost, nothing invented.

## Shell today
- 6 Lobby (default, bar GONE here) — `ui/lobby/LobbyFragment.java` — router only
- 0 Capture/Home — `ui/HomeFragment.java` / `fadrec/ui/FadRecHomeFragment.java`
- 1 Library/Records — `ui/RecordsFragment.java` (6238 lines)
- 2 Remote — `ui/RemoteFragment.java`
- 3 Studio list — `ui/FaditorMiniFragment.java` → `ui/faditor/FaditorEditorActivity.java`
- 4 Setup — `ui/SettingsHomeFragment.java`
- 5 Finder/Lab — `forensics/ui/ForensicIntelligenceFragment.java`
- Swipe 0↔5, lobby excluded. Back always → lobby. House icon = way back.

## Lobby routes (all verified in buildRooms())
Studio→pos3/editor, Capture→pos0, Sprites→`SpriteSheetEditorActivity`, Avatar→`AvatarStudioActivity`,
Viz Lab→NOWHERE (falls back to pos3 — placeholder room), Finder→5, Remote→2, Bits→0, Packs→4, Setup→4.
Hero/recents/New row/floor/Joybot orb/search/library door/bot-line/export hairline — all live.

## Capture (pos 0)
- Home camera + FadRec screen home (mode-switched) — viewfinder, start/stop/pause, torch, zoom, AF/EV chips
- Sidebar (`HomeSidebarFragment`) — profiles, What's New (`WhatsNewActivity`), Tips, preview toggles, Discord, mini-apps entry
- Fullscreen preview, Photo capture (`PhotoCaptureActivity`), Pro upsell (`FadCamProActivity`), quick video settings sheet
- HOME INFO CARDS (the widgets you named): `cardStorage` rows = camera / estimate-time / storage-ring (3 tappers → 3 different sheets), `cardClock`, `cardStats`, `cardElapsedHero`, recording tiles. Customizable via long-press sheets. Best-themed surface in the old app. MUST KEEP all three storage rows + clock + stats.

## Library (pos 1)
- Records: 7 filter chips (All/Camera/Screen/Faditor/Stream/Shot/MiniApps) + 4 hidden sub-rows (camera/faditor/shot/miniapps), sort+grid side sheet, skeleton loading, multi-select batch (save / open-in-Faditor / delete), deletion dock with ETA, swipe-refresh, fast scroller
- `searchView` DECLARED NEVER ASSIGNED (line 4897) — dead. Lobby magnifier just opens library.
- Projects: TWO systems — (A) Faditor projects (`FaditorMiniFragment`, tokenized, primary), (B) FadRec annotation projects (`dialog_project_management`, green/red hardcodes, legacy)
- Photos: no dedicated grid — intermixed SHOT category + sub-filters → `ImageViewerActivity`
- Trash (`TrashFragment`): restore / delete-permanent / empty-all, ALL/VIDEOS/EVIDENCE filters, auto-delete info, applock overlay
- Player (`VideoPlayerActivity`, ExoPlayer + fragmented-MP4 path) + Viewer (`ImageViewerActivity`) + Watch variants + fullscreen preview

## Remote (pos 2)
- LAN HLS streaming: toggle, root URL+copy, recording-mode row, endpoints/clients sheets, quality preset, uptime/battery/segments/network/memory/storage/data rows, auth/autolock/logout, cloud upload + account, e2e row, streaming-mode row. Watch = start/stop only.
- Theming: mixed — `#000000` root + server-room imagery predate JoyCreator.

## Studio (pos 3) — ~20% unified, your number
- List (`FaditorMiniFragment`): start project (file picker sheet), recents tap→editor / long-press multi-delete, export dock
- Editor (`FaditorEditorActivity`, ~36k lines): timeline/layers/keyframes/masks/chroma/GL/speed/text/sprites/puppets/waveform/transcript. Chrome unified (tokens, transport, lanes, minimap, playhead, tapes, contextual row, dark scrim). Drawer CONTENTS still fragmented (blues left, ~30 one-offs).
- SpriteLab (`SpriteSheetEditorActivity`): nearly complete, icons generated from `tools/spritelab/*.html` via `genicons.py`
- Avatar (`AvatarStudioActivity`): largest 🟡 block — substantial, never driven by hand
- Puppet: NO activity — inside editor + floating webcam bubble. Viz Lab: NO implementation.

## Finder (pos 5)
- `ForensicIntelligenceFragment` hosts gallery + stats + sidebar → export center / insights / events / pickers. Evidence-board theming intentional (dark dossier, torn paper) — keep as its own mood, just de-FadCam it.
- Motion Lab: settings-only (`MotionLabSettingsFragment` + debug terminal), no tab, no gadget row. Engine hooks in `RecordingService`.
- Digital Forensics settings: master/evidence/scope/overlay toggles + preview.

## Setup (pos 4) — 15+ screens, all custom rows + picker sheets (no PreferenceScreen system)
Appearance (9 themes incl. JoyCreator default / 13 langs / 16 icons / logo / eye colors) · Video · Audio · DualCam · ScreenRec · Watermark (+location/speed/altitude/weather toggles) · LocationPrivacy · Storage (SAF) · Behavior · Security (PIN/cloak/privacy-black) · Notifications · Shortcuts&Widgets (9 shortcuts + clock widget pin) · MotionLab · DigitalForensics · Player · Watch hub · About (hardcode-heavy) · Review · Readme · Onboarding toggle · Auto-update · Debug log · Prefs backup sheet · Editor-only sheets (Faditor settings, waveform style, export).
Theming: rows already `?attr/colorHeading` → JoyCreator fixed centrally. Outliers: home `@color/s_ink_faint`, watermark `#7A000000`, dualcam banner/preview hex, motion debug greens, forensics icon tints, `0xFF4CAF50/0xFF666666` popup checks, About `WHITE/BLACK/#E43C3C` branches.

## Gadgets / widgets ("Bits")
- REAL: Torch (4 surfaces: tool fragment + home button + shortcut activity + notification, singleton manager) · QR Scanner (activity, CameraX+ZXing, dark-only hardcodes). QR-maker = coming-soon.
- COMING-SOON (no code, only sidebar rows + strings): compass, sound meter, sensor dashboard, speedometer, level, pedometer, metal detector, parking marker. Backends exist unused (`SensorDataProvider`, `NoiseMonitor`).
- Clock widget (real AppWidget, hardcoded bitmap) + home clock card (themed). Widget tap → Settings tab.
- No MiniAppsActivity — container is sidebar section + info sheets + Records MINIAPPS category.

## Onboarding / system
- `SplashActivity` → `OnboardingActivity` (Welcome/Permissions/Human) → `WhatsNewActivity` (WebView changelog) → Main. Re-enable via Settings.
- Standalone: 15 cloaked icon aliases, Wear OS suite, TextEditor/ColorPicker/ProjectNaming/ProjectSelection/TransparentPermission, WebView/CloudAccount/PrivacyBlack, TorchToggle/RecordingStart/Stop/Screenshot shortcuts, headless ApplyEdits/SlideRender/WaveformDebug/DecoderProbe.

## Grades (facelift vs merge vs rebuild)
- FACELIFT ONLY (structure sound, theme via attrs): all Setup rows, Records/Trash/Player/Viewer, Home info cards, Torch/QR, project list A, forensics gallery shell.
- MERGE (two places, one job): Faditor projects + FadRec projects → one Projects; MotionLab settings + Forensics settings + Finder tab → one Finder room; 9 shortcuts + gadget rows + clock widget → one Bits surface; Appearance themes → prune to JoyCreator + 1-2 (9 themes is a test matrix, not a feature).
- REBUILD (missing or dead): Viz Lab (no code), coming-soon gadgets (no code — decide cut vs build), Records search (declared, never wired — build or remove icon), lobby floor words (currently 5 grey words → need the BETTER solution below).
- KEEP AS-IS (intentional moods): forensics evidence-board dark, QR scanner dark, motion debug terminal dark.

## The nav-bar replacement problem
Dropping the bar orphans: Capture home, Records, Remote, Studio list, Setup(15), Finder, Sidebar(What's New/Tips/mini-apps), Trash, Player/Viewer, Photo capture, Fullscreen preview, Pro upsell, Watch, onboarding toggle.
Constraint from you: replacement must be BETTER in all ways, keep every FadCam capability (incl. storage ring rows, clock, stats, shortcuts, widgets), and give Setup's 15 screens a home without a 6-icon bar.
Options on the table (from Five-Front-Doors record): Floor-Plan geography (fixed positions) + Bench hero (resume) + Deck hold-to-fan + Marquee verbs for first-run only + Stage viewport (untested, GPU cost). Decision owed before any more facelift passes.
