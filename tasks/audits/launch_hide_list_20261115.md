# Launch hide list, proposed to the owner (2026-11-15)

**What this is.** Testers start on 11-21. Some doors in the app open onto things that are built but not yet proven on a
phone, or onto rows that only say "coming soon". This page proposes, door by door, which ones to **HIDE** for the
test and launch and which to **KEEP**. Hiding never deletes anything: each key below goes into one list file
(`app/src/main/assets/launch/hidden_features.json`, read by `com.fadcam.launch.LaunchGate`), and taking the key out
brings the door straight back. I leaned to KEEP where JoyRaptor already ruled something a launch feature (Remote
Studio and Smart Detection, 10-17) and to HIDE where a tester could lose work, spend money, or meet a screen that
looks broken or empty. Proof levels come from `tasks/STATE.md` (🟢 used by the owner on a phone; 🟡 ✔ or "agent-checked"
= an agent checked it on a phone; plain 🟡 = built, never seen on a phone), with the STATE row's date. JoyRaptor picks;
nothing is hidden until he does (the list file is empty today). Sources: STATE.md, `tasks/audits/launch_triage_20261114.md`
(L37, SHELL-40, SHELL-15, SHELL-58, AN-03), `tasks/LAUNCH_MONTH_20261114.md`, `tasks/audits/feature_reach_20261028.md`,
`tasks/ROADMAP.md`. Drafted by a read-only helper and checked by the app-shell lane against STATE and the code (Motion Lab has no STATE row; FadMic and Profiles are "coming soon" stubs; Palette knife 3 is on LAUNCH_MONTH's after-launch list; Remote and Smart Detection ruled launch features 10-17, ROADMAP decision 1).

**What the switch already honours (landed with L37):** lobby rooms (`room_*`), New chips (`new_*`), floor words (`floor_*`), Settings hub rows and their search (`setting_*`), the nine gadget rows in classic Settings and Capture's side menu (`gadget_*`, `gadget_stubs`), and Joybot's help answers, tips and Take me there for any hidden room or floor place. **Keys in this table that need their doors wired after JoyRaptor picks** (section 3 lists each door): `joybot_pictures`, `joybot_bridge`, `joybot_hands_free`, `joybot_recording_control`, `joybrush_palette_knife_3`, `joybrush_tile_board`, `joybrush_pen_diagnostics` (the JoyBrush module needs its own read of the list), `capture_fadmic`, `capture_profiles`, and the side doors of `room_avatar` (Library chip, Studio's avatar button, the rig action).

**11-23 engineering hide:** `studio_motion_blur` removes the Studio carousel and All tools entry until C3 preview/export parity. The launch list keeps this hide independently of the owner picks below.

## 1. Features (most important first)

| key | feature | where its door is | proof | proposal | why |
|---|---|---|---|---|---|
| `room_avatar` | Avatar Studio: puppet characters with face tracking and lip sync | Lobby Avatar room; Library "Avatars" chip; Studio sprite picker's avatar rows; classic Studio list's avatar button; Joybot "open avatar" / Take me there; Joybot's rig proposal | 🟡 ~ (STATE §5 "Avatar rigs", undated; "JoyRaptor has not driven it"); AN-03 open 11-13 | **HIDE** | You have never used it yet; it needs a project first, so a tester tapping the room lands somewhere confusing. |
| `joybot_pictures` | Joybot makes a picture for you (paid picture service) | Joybot chat: "Describe it and I'll get it made" card, "Help me make a picture", picture requests in chat | 🟡 10-15, corrected 11-13; never tried with a paid picture | **HIDE** | It spends the tester's own money and has never been tried from start to finish. |
| `setting_motion_lab` | Motion Lab: record when something moves in a room | Settings hub › Labs › Motion Lab (and its search words "cctv", "surveillance") | no STATE row at all (old FadCam part, never checked) | **HIDE** | A security-camera tool, not a way to make things; store reviewers may read it as spying. |
| `joybot_bridge` | Connect my computer: an AI on your computer drives Joybot | Joybot › Connect my computer; Joybot help "bridge"; Joybot's setup talk | 🟡 10-28; agent-checked over a cable, never with a real computer AI; the talk half is not built | **HIDE** | A tool for programmers that opens a door on your Wi-Fi; testers will not need it and it is half done. |
| `joybot_hands_free` | Hands-free Joybot: hold the mic, speak, he answers aloud and waits for "yes" or "undo" | Joybot chat: hold the mic button; Joybot help "hands_free" | 🟡 10-15; needs your voice, never tried | **HIDE** | Never tried with a real voice; a mishearing could change work without a tap. Tap-to-dictate stays. |
| `joybot_recording_control` | Ask Joybot to start, pause or stop a camera recording | Joybot chat ("start recording"); Joybot help "record" | 🟡 11-13; tried on the computer only, never on a phone | **HIDE** | A camera that fails to start when asked looks broken, and starting the camera by chat should be seen working first. |
| `joybrush_palette_knife_3` | Palette knife 3, the knife held like a real knife | JoyBrush brush drawer, oil knives shelf | 🟡 11-11, robot strokes only; parked until after launch (LAUNCH_MONTH §5) | **HIDE** | You parked it yourself; Palette knife 1 and 2 stay. |
| `joybrush_tile_board` | Tile board: a repeating pattern that wraps strokes across its edges | JoyBrush ⋯ › Boards › "New Tile board…"; Joybot help "joybrush_tile" | 🟡 BUILT, agent checks from early October only | **HIDE** | It refuses the realistic pencils, watercolour and oil, which a tester will read as broken; not rechecked since October. |
| `joybrush_pen_diagnostics` | Pen numbers laid over the drawing (an engineer's check) | JoyBrush: hold the ⋯ button | no STATE row (engineer tool) | **HIDE** | A hidden test screen; a tester who holds ⋯ by accident sees numbers over their painting. |
| `capture_fadmic` | "FadMic" audio-only recording mode | Capture's mode switcher, third segment | not built: only says "coming soon" | **HIDE** | A button that leads nowhere, under the old FadCam name. |
| `capture_profiles` | Recording "Profiles" | Capture's side menu, Profiles row | not built: only says "coming soon" | **HIDE** | Another row that leads nowhere. |
| `gadget_stubs` | The nine "coming soon" gadgets (table 2) | Classic Settings mini-app cards; Capture's side menu | not built | **HIDE** | Nine dead rows; none of them makes anything. |
| `floor_remote` (+ `setting_remote`) | Remote Studio: watch and control the phone's camera from a browser on the same Wi-Fi | Lobby floor word "Remote"; Settings hub › Labs › Remote Studio; Joybot "open Remote" | 🟡 ✔ 10-28 (agent on the Note 9), names 11-13 | **KEEP** | You ruled it a launch feature (10-17); it works on the phone; the cloud parts were already removed. |
| `floor_detect` (+ `setting_smart_detection`) | Detect (Smart Detection): finds people, motion and objects in recordings | Lobby floor word "Detect"; Settings hub › Labs › Smart Detection; Joybot "open Smart Detection" | 🟡 ✔ 11-13 (restyle and names seen on the Note 9) | **KEEP** | You ruled it a launch feature (10-17); your test clip is still owed to prove the detector itself. |
| `room_textlab` | TextLab: film-poster title text, its looks on Studio titles, export | Lobby TextLab room; Studio › a text › Look / Edit in TextLab; Library look cards; Joybot | 🟡 agent-checked on the Note 9, 11-12 night | **KEEP** | Checked on the phone and nothing in it can lose a project; you still owe it a try (TX-16). |
| `joy_packs` | Joy packs: one file that brings in looks (later brushes, papers, fonts) | Open a `.joypack` file › Install; lobby floor word "Packs" | 🟡 agent-checked 11-12 | **KEEP** | Your planned way to sell and share packs; the pack file format must be frozen before the public release. |
| `joybrush_boards` | JoyBrush Image, Animation and Sprite boards, onion skin, board export | JoyBrush ⋯ › Boards › New Image / Animation / Sprite board | 🟡 BUILT, agent checks from early October | **KEEP** | Animating is half of what JoyBrush promises; worth one try by you in week 2, and hide it then if it misbehaves. |
| `joybrush_wet_watercolour` | Watercolour on the wet sheet, Water detail (Detailed / Fast), painting on when the paint room is full | JoyBrush brush drawer, watercolour shelf; ⋯ › Water detail | 🟡 agent-checked on the Note 9, 11-10 / 11-11 | **KEEP** | The heart of JoyBrush; your long painting session (JBX-40) is the proof still owed. |
| `joybrush_brush_import` | Bring in Photoshop, Procreate, Krita and MyPaint brushes | JoyBrush brush drawer › "Import…" | 🟡 agent-checked 11-13 / 11-14 (your Photoshop set, 17 of 19 brushes) | **KEEP** | It says plainly which brushes came in and which could not, so nothing looks broken. |
| `joybrush_unibrush` | Unibrush stamp brushes and picture lines | JoyBrush brush drawer; hold a brush for its editor | 🟡 ON 10-31 (you switched it on), Note 9 only | **KEEP** | You turned it on yourself; only very big soft stamps are slow. |
| `joybrush_symmetry` | Mirror and kaleidoscope drawing | JoyBrush top bar | 🟡 agent-checked on the Note 9, 10-30 | **KEEP** | Checked on the phone; easy to turn off again. |
| `studio_puppet` | Puppet a picture in Studio: pins, bones, live takes, the on-picture helper strip | Studio › a picture › drawer › Puppet tab; marionette badge | 🟢 keyframes and takes 9-16 (you rigged a character); the rest 🟡 9-16 | **KEEP** | You used the core of it on the phone; one undo brings back anything Start over removes. |
| `studio_audio_clean` | Audio drawer's Clean tab (Fix audio's Revert and Redo) | Studio › an audio clip › drawer › Clean | 🟡 11-13, not seen on a phone | **KEEP** | Revert puts the sound back, so it cannot lose work; part of the Studio checks owed (STU-B1). |
| `studio_ducking` | Music dips under a voice ("Duck under…") | Studio › lane menu › Duck under…; Joybot | 🟡 rebuilt 10-15 | **KEEP** | It writes ordinary volume points you can see and change; on the keyless tester check list (BOT-A12). |
| `joybot_add_marker` | Joybot drops a mark on the Studio timeline, one Undo takes it back | Joybot chat in Studio | 🟡 11-10 | **KEEP** | Small and undoable; its phone check is already on the launch list (BOT-A1). |
| `joybot_connect_ai` | Connect an AI by talking to Joybot (the key stays on the phone) | Joybot greeting "Connect an AI"; Settings hub › AI and Joybot | 🟡 10-28 | **KEEP** | Testers need a way to switch on the smart half of Joybot; hiding it leaves only the offline help book. |
| `joybot_report_reply` | Report a Joybot reply (the flag beside Copy) | Every Joybot answer | 🟡 seen on the test tablet 11-14 | **KEEP** | Google Play asks for it in any app with AI answers. |
| `library_paste_picture` | Paste a picture copied in another app | Library › options › Add › Paste a picture | 🟡 11-14 | **KEEP** | Small, saves a copy, cannot harm anything already there. |
| `space_bulk_trash` | Space screen: move several unused files to Trash at once | Settings hub › Folder › Space | 🟡 ✔ 11-14 (multi-move agent-checked on the Note 9: one file moved and put back) | **KEEP** | Files go to Trash, so they can be brought back. |
| `tools_torch_qr` (`setting_torch`, `setting_qr_scanner`) | Torch and QR scanner, the two gadgets that work | Lobby floor word "Bits"; Settings hub › Shortcuts; classic Settings cards | 🟡 ~ (STATE §1 "MiniApps") | **KEEP** | They work and cost nothing; your call is still owed on them (SHELL-58). |
| `classic_screens` | Classic Records, Classic Studio list, Classic Settings, Classic About | Library › options; Settings hub last row; About page's foot link | FadCam-era screens, unchanged | **KEEP** | Your earlier ruling keeps them reachable until the new screens are better in every way; they look like FadCam, so worth a second look. |

Counts: 31 rows, 12 HIDE, 19 KEEP.

## 2. The nine "coming soon" gadgets (SHELL-15)

Each shows as a card in the classic Settings (`SettingsHomeFragment.setupMiniAppCards`, lines about 390-433) and as a
row in Capture's side menu (`HomeSidebarFragment`, lines 272-314); tapping one only shows a "coming soon" message
(`HomeSidebarFragment.showMiniAppComingSoon`). None is built.

| key | gadget | proposal | why |
|---|---|---|---|
| `gadget_compass` | Compass | **CUT** | A phone already has one; nothing to do with making art. |
| `gadget_sound_meter` | Sound meter | **CUT** | Studio's audio meters already show loudness where it matters. |
| `gadget_sensor_dashboard` | Sensor dashboard | **CUT** | An engineer's toy, not a creative tool. |
| `gadget_speedometer` | Speedometer | **CUT** | Unrelated to making things. |
| `gadget_clinometer` | Clinometer (tilt meter) | **CUT** | Unrelated; the pen-tilt brushes do not need it. |
| `gadget_pedometer` | Pedometer | **CUT** | A fitness app's job. |
| `gadget_metal_detector` | Metal detector | **CUT** | A novelty that would make a creative studio look like a junk-drawer app. |
| `gadget_parking_marker` | Parking marker | **CUT** | Unrelated to making things. |
| `gadget_qr_generator` | QR code maker | **CUT** for launch (keep the card) | The one with a creative use (a QR on a poster or video end card), but it would belong in Studio or TextLab, not a gadget row; idea for later. |

All nine at once: `gadget_stubs`. Torch and QR scanner (which work) are in table 1 as KEEP.

## 3. Doors that already exist for each hidden key (for the engineer gating them)

The gate is `com.fadcam.launch.LaunchGate.hidden(context, key)` (plain-Java side for Joybot's tested code: `com.fadcam.launch.LaunchList`). Already wired and landed: lobby rooms
(`LobbyFragment` line ~565, `roomKey()` gives `room_avatar` for `ROOM_AVATAR`), New chips (~1930-1959), floor words
(`addFloorWord`, ~2188), Settings hub rows (`SettingsCatalog.shown`, ~168, key `setting_` + `SettingsKey` in lower case),
and gadget rows (`LaunchGate.hideGadgetRows`, called at `SettingsHomeFragment` ~434 and `HomeSidebarFragment` ~316).
Joybot's HelpBook, TipBook and NavigateActions skip any hidden room or floor place. Everything else below still needs a gate call.

- **`room_avatar`** (lobby room already gated). Still open: `LobbyFragment` `ROOM_AVATAR` hero actions (~1278-1291);
  `ui/library/LibraryKind.AVATARS` chip and `LibraryOpener.openAvatar` (~128); `FaditorEditorActivity.openAvatarStudioManager`
  (~43986, called ~43135 from the sprite picker); `ui/FaditorMiniFragment` `R.id.btn_avatar_studio` (~182-187);
  `MainActivity` route `case "avatar"` (~1054); `ChatAssistantActivity` avatar intent (~2376); `ai/actions/NavigateActions.ROOMS`
  entry `"avatar"` (~22); `ai/joybot/HelpBook` topic `"avatar"` (~170) and its Take me there; `AIToolExecutor`
  `"author_avatar_rig"` (~158) and its prompt text (~475).
- **`joybot_pictures`**: `ChatAssistantActivity.startPictureFlow` (~2807) and its callers (~1007, ~1034, the
  "Describe it and I'll get it made" card ~1104, ~1528), `makePicture` (~1028, ~2847); `ai/joybot/JoybotPictures.canGenerate`
  is the natural single switch (return false when hidden).
- **`setting_motion_lab`**: `SettingsKey.MOTION_LAB` (`group_motion_lab`), row in `SettingsCatalog` Labs section; already
  covered by the hub gate. Also the classic Settings row `R.id.group_motion_lab` and `SettingsDestinations` `case MOTION_LAB`
  (~93, `MotionLabSettingsFragment`).
- **`joybot_bridge`**: `ChatAssistantActivity` route `case "bridge"` (~2408-2411) and the setup host's `openBridge()` /
  `bridgeReady()` (~4298; `ai/joybot/SetupFlow` ~29-31, ~63: returning false from `bridgeReady()` drops the offer);
  `ai/bridge/BridgeActivity.intent`; `HelpBook` topic `"bridge"` (~198).
- **`joybot_hands_free`**: `ChatAssistantActivity` `btnMic.setOnLongClickListener(v -> { dictateAndSend(); ... })` (~340),
  which leads to `startDictation(true)` setting `handsFree = true` (~2505); `HelpBook` topic `"hands_free"` (~628).
- **`joybot_recording_control`**: `ai/actions/CaptureActions` action `"recording"` (~27); `HelpBook` topic `"record"`
  wording about asking Joybot.
- **`joybrush_palette_knife_3`**: `joybrush/core/.../core/media/Paste.kt` `PasteBrushes.ALL` entry `"Palette knife 3"`
  (~182), which feeds the brush drawer's oil shelf (`joybrush-android/.../chrome/BrushDrawerView.kt`). JoyBrush is a
  separate module: it needs its own read of the list (or a flag passed in from the app).
- **`joybrush_tile_board`**: `joybrush-android/.../board/BoardRuntimeController.showMenu` label `"New Tile board…"`
  (~850, index 3 → `beginPlacement(BoardKind.CANVAS, true)`); `HelpBook` topic `"joybrush_tile"` (~368).
- **`joybrush_pen_diagnostics`**: `joybrush-android/.../JoyBrushActivity.kt` ⋯ button
  `setOnLongClickListener { toggleDiagnostics(); true }` (~842).
- **`capture_fadmic`**: `ui/components/ModeSwitcherComponent` `segmentFadMic` (`R.id.segment_fadmic`, layout
  `mode_switcher_segmented.xml` ~67; click ~406, `onComingSoonRequested("FadMic")` ~423).
- **`capture_profiles`**: `ui/HomeSidebarFragment` `R.id.row_profiles` (~124-130, `showProfilesComingSoon`; layout
  `fragment_home_sidebar.xml` ~65).
- **`gadget_stubs`** / `gadget_<id>`: already gated through `LaunchGate.hideGadgetRows` (ids `group_mini_<id>`,
  `group_mini_sensor` for the sensor dashboard, and `row_mini_app_<id>` in the side menu).

## 4. Left for JoyRaptor to decide

- Avatar Studio: hide (proposed), or keep it with a "beta" label (AN-03).
- Torch and QR scanner: keep (proposed) or cut with the other gadgets (SHELL-58). Wear OS screens were not found in
  this branch's `app/src` (only `debug`, `main`, `play` source sets), so nothing to list for them here.
- The classic FadCam screens: keep for now (your earlier ruling) or hide once the new screens cover them.
