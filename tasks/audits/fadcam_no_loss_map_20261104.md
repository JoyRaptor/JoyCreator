# FadCam no-loss map: every FadCam-derived screen and capability, what replaces it, what is missing

Job: [FREE-1](../jobs/FREE-1_fadcam_inventory.md) / [FOLD-1](../jobs/FOLD-1_fold_fadcam_features_in.md)  ·  Date: 2026-11-04  ·  Docs only, no code changed, no phone.
Code read at `origin/joy-creator` `63c054ac6`. Companion to [fadcam_inventory_20261028.md](fadcam_inventory_20261028.md) (which says what is FadCam's); this page says **what the owner would lose if the old screen went today**.

**Owner's concern:** while FadCam menu and settings screens are replaced, no FadCam functionality may be lost.
Standing text for it: FOLD-1 "Done when": "Nothing a FadCam user had is lost without the owner's yes." (10-28); `APP_MANIFEST.md` (09-22): "keep every FadCam capability (incl. storage ring rows, clock, stats, shortcuts, widgets)"; Library ruling f (comment in `ui/library/LibraryOptionsSheet.java`, undated): classic Records and Studio list "stay whole and reachable until this one is better in every way".

How to read: **Verified** = I opened the file or grepped the key. **Unverified** = taken from an older doc, not checked in code today. Paths are under `app/src/main/java/com/fadcam/` unless shown.

## The headline

1. **The Settings hub is a front door, not a replacement.** Each hub row opens the same classic fragment (`ui/settingshub/SettingsDestinations.java` `route()` says so in its class comment). So no setting key is lost by the hub itself; **nothing is lost as long as the classic fragments stay.** The risk is the day a classic fragment is deleted: its keys then have no screen. Every classic screen is still reachable from the hub, with the exceptions in table C.
2. **One setting already has no screen at all** (verified): "embed GPS location in the video file" (`Constants.PREF_EMBED_LOCATION_DATA` = `"embed_location_data"`). Its only toggle was `ui/LocationPrivacySettingsFragment` (121 lines), which nothing opens (no `new LocationPrivacySettingsFragment` anywhere; `git grep` finds the class only in its own file and a comment). `RecordingService` still reads the key (lines 268, 1491, 5628, 6483). The watermark screen sets a *different* key (`PREF_LOCATION_DATA`, the on-screen location text). So the user cannot turn the metadata GPS on or off from any screen. This is a loss that has already happened.
3. **The Library (v2) can open, rename, share and trash one item. The old Records could do much more** (table A). That is the biggest functional gap versus what the owner ruled ("better in every way").
4. The 10-28 inventory is partly stale: the cloud and promo cut (S19) has landed. `FadCamProActivity`, `KoFiSupportBottomSheet`, `CloudAccountActivity`, `CloudAuthManager`, `WebViewActivity`, `ReadmeBottomSheetFragment` no longer exist (Glob, 11-04).

---

## A. Library: Records, Trash, Player, Viewer

| Screen / capability | Where now (verified) | Joy Creator replacement | Ruling | Gap versus the old screen |
|---|---|---|---|---|
| **Records list** (7 kind chips Camera/Screen/Faditor/Stream/Shot/MiniApps, sort, grid) | `ui/RecordsFragment.java` + `RecordsAdapter`, `RecordsSidebarFragment`, `RecordsOptionsBottomSheet` (tab 1 in `MainActivity.switchFragment`) | `ui/library/LibraryFragment` (+30 files): kind chips, sort and view cycles in the header, search at the bottom, Shared-in cycle | Ruling f (above, undated): classic stays whole until the new one is better in every way. Inventory 10-28: CUT classic once Library v2 has the batch actions | Library kinds are `PROJECTS/DRAWINGS/SPRITE_SHEETS/AVATARS/RECORDINGS/PHOTOS/EXPORTS/CLIPS/...` (`LibraryKind`). **Recordings are one kind: no Camera / Screen / Stream / Dual sub-filter** (`LibraryClassifier.Capture` only has NONE/RECORDING/PHOTO/STUDIO_OUTPUT). No MiniApps category |
| **Records batch actions** (multi-select: save to gallery, open in Faditor, delete; deletion dock with ETA; background `BatchMediaActionService`, `RecordsDeletionService`) | `RecordsFragment` (`btnActionBatchSave/Faditor/Delete`), `service/Batch*`, `service/RecordsDeletionService` | none | Inventory 10-28 A5: "once Library v2 has the batch actions it lacks" | **Library has no multi-select at all** (grep "select" in `LibraryAdapter`: 0; `LibraryFragment`: 2). Item sheet offers only open / rename / share / trash (`LibraryItemSheet`, `LibraryActions`). **No save-to-gallery, no open-in-Studio, no batch delete** |
| **Video info sheet** (details of a recording) | `ui/VideoInfoBottomSheet.java` | none in Library | no ruling | Lost from Library path (still in classic Records) |
| **Records search** | declared, never wired (APP_MANIFEST) | Library search field (`LibraryDock`) | built | None, an improvement |
| **Trash screen** (restore, delete forever, empty all, ALL/VIDEOS/EVIDENCE filter, auto-delete info, app-lock gate) | `ui/TrashFragment.java` (still reached from `RecentlyDeletedFragment:287`, `StorageSettingsFragment:98`, Lobby) | `ui/trash/RecentlyDeletedFragment` + 20 files: selection (`TrashSelection`), kinds, expiry line (`TrashPolicy`: 10 keep-for options), app lock kept (`TrashLock`) | Inventory 10-28: REPLACE screen, REBUILD `TrashManager` | Close to parity. Still calls `utils/TrashManager` for recordings (`TrashActions`). EVIDENCE filter: `TrashKind` has its own kinds (not checked one by one: **unverified** whether "evidence" items map) |
| **Video player** (ExoPlayer, fMP4 seek, settings) | `ui/VideoPlayerActivity.java`, `VideoPlayerSettingsFragment` (hub row `VIDEO_PLAYER`) | none; Library opens it via `LibraryOpener` | no ruling | Same screen, FadCam-styled; fine for no-loss. Four fMP4 seek classes have no caller (inventory) |
| **Image viewer, Photo viewing** | `ui/ImageViewerActivity.java` | Library PHOTOS kind opens it | none | Same screen |
| **Watch variants of Records/Trash/Player** | `ui/Watch*Fragment`, `WatchVideoPlayerActivity` | none | Inventory: CUT for launch; **FUTURE_PLANS: no ruling found** | Wear OS only; **unverified** on device |

## B. Capture: Home recorder, FadRec, Photo, sidebar

| Screen / capability | Where now (verified) | Joy Creator replacement | Ruling | Gap |
|---|---|---|---|---|
| **Home recorder** (viewfinder, start/stop/pause, zoom, AF/EV, torch button, quick video sheet) | `ui/HomeFragment.java` (tab 0, `MainActivity` case 0), `FullscreenPreviewActivity`, `services/RecordingService` | Skin only: `ui/home/HomeRec{Skin,Button,StartButton,Tile}`; Lobby "Capture" room routes to tab 0 (`LobbyFragment` `TAB_CAPTURE`) | 10-17 (HANDOFF_STUDIO_UI): Home recorder restyled (STATE §7.6 row). APP_MANIFEST 09-22: keep all three storage rows + clock + stats | Nothing lost: it is the same screen, restyled. Landscape Home still old (STATE row) |
| **Home info cards** (storage rows/ring, clock, stats, elapsed hero; `StorageProgressRingView`) | `ui/HomeFragment` (`cardClock` l.381, `cardElapsedHero` l.267), `ui/utils/StorageProgressRingView` (used by Home and Remote) | same screen | APP_MANIFEST (09-22) "MUST KEEP"; FUTURE_PLANS C3 (09-24): bring the storage radio widget back, re-themed | Present in code; the "storage ring" row (`cardStorage`) by name not found by grep: **unverified** whether it is still shown |
| **FadRec screen recording** | `fadrec/services/ScreenRecordingService`, `fadrec/ui/FadRecHomeFragment`, `FloatingControlsService`; settings `fadrec/ui/ScreenRecordingSettingsFragment` (hub row `SCREEN_RECORDING`) | webcam overlay `FloatingWebcamService` is Joy Creator's own | 10-28 S20: "Annotation folds into recording (and later a JoyBrush layer)" | None lost now. Dual-stream webcam file never rolls over on long takes (FOLD-1) |
| **Annotation (draw on screen)** | `fadrec/ui/AnnotationService.java` (+ view, layers, undo) | none | 10-28 S20/FOLD-1: fold in; later a JoyBrush layer. FUTURE_PLANS C4: IDEA, no JoyBrush row yet | Keep. Do not delete until a layer exists |
| **Photo capture** (+ headless, still-grab, shortcuts) | `PhotoCaptureActivity.java`; shortcut ids `capture_photo`, `capture_photo_front` (`shortcuts/ShortcutsManager`) | none | FUTURE_PLANS C6 (`NAV_SPEC` §9): both paths kept | None; screenshot path is Accessibility-based, see D |
| **Dual camera** (front+back) | `dualcam/` (state, config, service `DualCameraRecordingService`), settings `dualcam/ui/DualCameraSettingsFragment`, reached through a row in `VideoSettingsFragment` (l.150-156) | none | 10-28 S20: "dual/multi-camera feeds Capture"; STATE §1 🟡 | Settings screen is **not a hub row**: only reachable inside Camera & video. Fine, but a hub search for "dual camera" finds nothing unless `SettingsCatalog` lists it (**unverified**) |
| **Home sidebar** (profiles, What's New, Tips, preview toggles, mini-apps) | `ui/HomeSidebarFragment`, `TipsCarouselFragment` (opened only from the sidebar), `WhatsNewActivity` | hub row `WHATS_NEW` | no ruling on Tips | **Tips carousel has no hub entry** (only the sidebar opens it). Profiles / preview toggles: **unverified** |
| **Onboarding** (welcome, permissions, "verify human") | `ui/OnboardingActivity`, `OnboardingPermissionsFragment`, `OnboardingHumanFragment`; welcome is new (`OnboardingWelcomeFragment`) | DOOR-3 planned; hub `SHOW_INTRO` re-enables | Inventory: cut the "human" slide | None lost |
| **Pro upsell, Ko-fi, README sheet, cloud account** | files gone (Glob 11-04) | none wanted | S19 10-28 "yes, cut" | Intentional cut; no gap |

## C. Settings: every sub-screen the APP_MANIFEST lists

Hub = `ui/settingshub/SettingsCatalog` rows; "Hub row" gives the `SettingsKey`. Every hub row opens the classic fragment named (verified in `SettingsDestinations`).

| Classic screen (APP_MANIFEST name) | Where now | Hub row / replacement | Ruling | Gap |
|---|---|---|---|---|
| Appearance (9 themes, 13 langs, 16 icons, logo, eye colours) | `ui/AppearanceSettingsFragment` | `APPEARANCE` -> same fragment | APP_MANIFEST: prune to JoyCreator + 1-2 themes; icons: SHOW-1 | Same screen; disguise icon pick is a Play-policy cut (SHOW-1), a deliberate loss to confirm |
| Video | `ui/VideoSettingsFragment` | `CAMERA_VIDEO` | none | Same |
| Audio | `ui/AudioSettingsFragment` | `AUDIO` | none | Same |
| DualCam | `dualcam/ui/DualCameraSettingsFragment` | none directly (inside Video) | none | See B |
| Screen recording | `fadrec/ui/ScreenRecordingSettingsFragment` | `SCREEN_RECORDING` | none | Same |
| Watermark (+location, speed, altitude, weather) | `ui/WatermarkSettingsFragment` | `WATERMARK` | none | Same |
| **Location privacy (embed GPS in file)** | `ui/LocationPrivacySettingsFragment` | **none, unreachable** | no ruling | **LOST (verified)**, see headline 2 |
| Storage (SAF) | `ui/StorageSettingsFragment` | `RECORDING_LOCATION`, plus `JOY_FOLDER`, `SPACE` | Joy folder ruled 10-14 (folder chosen first) | Same, plus new |
| Behavior (onboarding toggle, auto-update toggle) | `ui/BehaviorSettingsFragment` (no caller) | `SHOW_INTRO` and `UPDATE_CHECK` rows (keys `show_onboarding`, `auto_update_check_enabled`) | none | Covered by the two rows. Not lost |
| Advanced | `ui/AdvancedSettingsFragment` (57 lines, no caller) | none | none | Content not inspected beyond size: **unverified**; confirm it had no keys before deleting |
| Security (PIN, cloak, privacy black) | `ui/SecuritySettingsFragment`, `PrivacyBlackActivity` | `SECURITY` | none | Same |
| Notifications | `ui/NotificationSettingsFragment` | `NOTIFICATIONS` | none | Its own comment says custom text editing and preview "will be migrated verbatim later": **simplified preset dialog only (verified comment)**. Possible loss vs old Behavior screen |
| Shortcuts and widgets (9 shortcuts, clock widget pin) | `ui/ShortcutsSettingsFragment`, `shortcuts/ShortcutsManager` (ids: torch_toggle, record_start, record_start_front, record_start_current, record_start_dual, record_stop, capture_photo, capture_photo_front, capture_screenshot), `widgets/ClockWidgetProvider` + manifest receiver | `SHORTCUTS` | Inventory: start/stop rebuilt with A1; clock widget "owner call" | Same screen; 9 shortcut ids verified |
| Motion Lab (+ debug terminal) | `ui/MotionLabSettingsFragment`, `motion/` | `MOTION_LAB` | 10-17 owner: Smart Detection launch feature; FUTURE_PLANS F5 PLAN (motion-triggered capture) | Same |
| Digital forensics / Smart Detection settings | `ui/DigitalForensicsSettingsFragment` | `SMART_DETECTION` | F1 OWNER RULING 10-17, F6 | Hub row opens **settings only**, not the Finder gallery (see D) |
| Player | `ui/VideoPlayerSettingsFragment` | `VIDEO_PLAYER` | none | Same |
| Watch hub | `ui/WatchSettingsFragment` | none (watch only) | none | **Unverified** |
| About / Review / Readme | `ui/about/AboutJoyFragment` (new), classic `AboutFragment`; rate in `SettingsDestinations.rate` | `ABOUT`, `RATE` | About "View source" decision S9 (UI-1) | Readme sheet gone (cut). No licences screen yet (inventory) |
| Onboarding toggle / Auto-update / Debug log / Prefs backup | `SettingsSwitchSheets`, `DebugLogBottomSheetFragment`, `PrefsBackupBottomSheetFragment` | `SHOW_INTRO`, `UPDATE_CHECK`, `DEBUG_LOG`, `SETTINGS_BACKUP` | none | Same |
| Editor-only sheets (Faditor settings, waveform style, export) | `ui/faditor/FaditorSettingsBottomSheet` | `EDITOR` | Joy Creator's own | Same |
| **Coming-soon gadget cards** (compass, sound meter, sensor dashboard, speedometer, level, pedometer, metal detector, parking marker, QR maker) | `ui/SettingsHomeFragment.java:391-431` (`setupMiniCard`, all "coming soon" except Torch and QR scanner) | none | Inventory/APP_MANIFEST: "decide cut vs build"; **no ruling** | Never worked (no code), so nothing is lost, but the old Settings tab advertises them and the hub does not. Needs a call |

## D. Remote, Finder, gadgets, widgets, system

| Screen / capability | Where now (verified) | Joy Creator replacement | Ruling | Gap |
|---|---|---|---|---|
| **Remote** (LAN HLS stream, web page, auth, quality, clients) | `ui/RemoteFragment` (tab 2), `streaming/{LiveM3U8Server,RemoteStreamManager,RemoteStreamService,RemoteAuthManager}`, `assets/web/`, sheets in `ui/bottomsheet` | Lobby floor word "Remote" (`LobbyFragment:1974`), hub `REMOTE`; restyled 10-20 | **10-17 owner: launch feature** (`HANDOFF_STUDIO_UI_20261017`, FUTURE_PLANS F1). 10-28 S19: cloud cut, "LAN Remote on your own Wi-Fi stays". S20: becomes Remote Studio | Nothing lost. Web page loads hls.js/flatpickr/Font Awesome from CDNs, broken with no internet (STATE l.97). No "remote phone as second camera" yet (FOLD-1) |
| **Finder / Forensics gallery, events, insights, export centre** | `forensics/ui/*` (10 files), tab 5 `ForensicIntelligenceFragment`; Room DB `forensics/data` | Lobby floor word "Finder" (`LobbyFragment:1973`). Story Board not built | **F1 10-17 owner ruling + F6 ("hide/park behind a toggle, never delete")**, DESIGN §7 (07-02): Evidence board -> Story Board, detectors -> auto-markers | Reachable. The *hub* Smart Detection row opens only settings. Story Board / auto-markers not built (FOLD-1) |
| **Smart Detection engine** (people/object/motion/noise) | `motion/`, `forensics/service`, asset `models/efficientdet_lite1.tflite` | none | F1 launch feature; **conflicts with inventory 10-28 "CUT from first public build"**. ROADMAP S20 (10-28) says fold in | The inventory recommendation is overruled by S20; FUTURE_PLANS flags the conflict. Owner call D4 |
| **Torch** (tool, home button, shortcut, notification) | `ui/miniapps/{TorchToolFragment,TorchManager,TorchNotificationReceiver}`, `TorchToggleActivity`, `services/TorchService`, `receivers/TorchToggleReceiver` | hub `TORCH` | none (inventory: keep for now, hide behind Labs) | Same |
| **QR scanner** | `ui/miniapps/QRScannerActivity` (+overlays, zxing) | hub `QR_SCANNER` | none | QR generator is "coming soon" only |
| **Clock widget** | `widgets/ClockWidgetProvider`, `WidgetPreferences`, manifest receiver at l.507; pin from `ShortcutsSettingsFragment` | none | Inventory: "owner call" | Present |
| **Shortcuts: Start/Stop, Torch, Photo, Screenshot** | `RecordingStartActivity`, `RecordingStopActivity`, `TorchToggleActivity`, `PhotoCaptureActivity`, `ScreenShotCaptureActivity` | same | SHOW-1 | Screenshot uses `service/FadRecScreenshotAccessibilityService`: SHOW-1 removes it from the Play build and **names no replacement** (S20: "still a Play-policy question") |
| **Disguise icons** (15 aliases) | `AndroidManifest.xml` `activity-alias` x15 (l.196-308); `AppIconGridBottomSheet` (no caller) | hub Appearance icon pick | SHOW-1: remove for Play build; S20: policy question | Deliberate loss for Play build; side-load build keeps |
| **Wear OS watch** | `ui/Watch*` (8 files) | none | No ruling (inventory suggests CUT for launch) | Owner call D6 |
| **Cloud streaming / account** | gone | none | S19 10-28: cut | Intentional |
| **Settings backup (prefs export/import)** | `ui/PrefsBackupBottomSheetFragment`, hub `SETTINGS_BACKUP` | same | none | Keep; verify it includes the Joy Creator prefs: **unverified** |

---

## Top 10 gaps (what could be lost or is already lost)

| # | Gap | Evidence |
|---|---|---|
| 1 | **Embed GPS in the video file: setting has no screen** (already lost) | `LocationPrivacySettingsFragment` unreferenced; key `embed_location_data` read by `RecordingService` |
| 2 | **Library has no multi-select / batch (save, open in Studio, delete)** | `LibraryAdapter`: 0 hits on "select"; item sheet is open/rename/share/trash only |
| 3 | **No "save to gallery" and no "open in Studio" from the Library item sheet** | `LibraryItemSheet` actions list |
| 4 | **No Camera / Screen / Stream / Dual / Shot sub-filter in the Library** (one RECORDINGS kind) | `LibraryKind`, `LibraryClassifier.Capture` |
| 5 | **No video details sheet in the Library** (`VideoInfoBottomSheet` only used by classic Records) | not referenced by `ui/library` |
| 6 | **Notification customisation is a simplified preset dialog**; old text editing and preview "will be migrated later" | `NotificationSettingsFragment` header comment |
| 7 | **Tips carousel reachable only from the Home sidebar**, no hub entry | `TipsCarouselFragment` has one caller, `HomeSidebarFragment:453` |
| 8 | **Annotation (~18,000 lines) has no replacement**; any cut loses draw-on-screen | `fadrec/ui/AnnotationService`; FOLD-1 says later a JoyBrush layer |
| 9 | **Screenshot shortcut depends on the Accessibility service that SHOW-1 removes**, with no named replacement | `ShortcutsManager.ID_SCREENSHOT`, `FadRecScreenshotAccessibilityService` |
| 10 | **Smart Detection / Finder: hub row opens settings only; Story Board and auto-markers unbuilt**, and the 10-28 inventory verdict "cut" contradicts the 10-17 launch ruling | `SettingsDestinations` `SMART_DETECTION`; FUTURE_PLANS F1 note |

Honourable mentions: `AdvancedSettingsFragment` content not inspected; dual-camera settings not a hub row; coming-soon gadget cards advertised on the old tab only.

---

## Small, source-only rebuild jobs, in order

Priority rule: first anything that is already lost, then what blocks deleting a classic screen, then polish. Sizes: S = a day or less, M = a few days, L = a week or more.

| Pri | Job (source only, no phone claim until tried) | Size |
|---|---|---|
| 1 | **GPS-in-file toggle**: add a row/switch to a live screen (Camera & video or Watermark) writing `Constants.PREF_EMBED_LOCATION_DATA`, with the existing permission prompt logic from `LocationPrivacySettingsFragment`. Keep the old fragment until it ships | S |
| 2 | **Library: recording sub-filter** (Camera / Screen / Stream / Dual) from the file name word `LibraryClassifier.Capture` already reads | S |
| 3 | **Library: item sheet adds "Save to gallery" and "Open in Studio"** reusing what `RecordsFragment` batch save/open-in-Faditor call | M |
| 4 | **Library: multi-select + batch delete** (pattern exists in `ui/trash/TrashSelection`, `TrashDock`); then batch save/open-in-Studio | M |
| 5 | **Library: details sheet** (port `VideoInfoBottomSheet` content as a Library sheet) | S |
| 6 | **Hub rows for Tips and Dual-camera settings** (add `SettingsKey`s opening the existing fragments, so search finds them) | S |
| 7 | **No-loss test**: a JVM test listing every key the classic settings screens write (`SharedPreferencesManager`/`Constants.PREF_*`) against the keys a live screen exposes, failing when a screen is deleted without a home for its key | M |
| 8 | **Notification text editor + preview** finish (the "migrated verbatim later" part) | M |
| 9 | **Hub Smart Detection row opens the Finder room** (gallery) and settings as a second tap, so the feature is found | S |
| 10 | **Screenshot replacement for the Play build** (MediaProjection one-shot, no Accessibility) so SHOW-1 loses nothing | M |
| 11 | **Annotation as a JoyBrush layer** (FOLD-1 / JoyBrush roadmap row owed) | L |
| 12 | **Story Board + auto-markers** from the forensics Room DB and detector events (FOLD-1 order) | L |

## Items needing an owner call

| # | Question (plain words) | Why it blocks |
|---|---|---|
| D1 | Do you want "put location in the video file" back as a setting? (It silently disappeared.) | Job 1 |
| D2 | Is the new Library allowed to replace classic Records once jobs 2-5 land? Or must classic stay reachable forever (ruling f says "until better in every way")? | Deleting 10,500 lines |
| D3 | Annotation (draw on a screen recording): rebuild as a JoyBrush layer, or drop it? | No decision recorded; ROADMAP S20 says "fold in" without a date |
| D4 | Smart Detection/Finder: the 10-17 ruling says launch feature, the 10-28 inventory says cut. Confirm launch (TensorFlow + OpenCV + 6 MB model stay) | App size and Play build |
| D5 | Screenshot shortcut: accept losing it on the Play build, or build a replacement (job 10)? | SHOW-1 |
| D6 | Wear OS watch app: keep, cut, or later? | No ruling found |
| D7 | Coming-soon gadgets (compass, sound meter, pedometer ...): build, or remove the cards? Clock widget and Torch/QR: keep? | Only Torch and QR scanner work |
| D8 | Disguise icons: acceptable to lose on the Play build only (side-load keeps them)? | SHOW-1 |

## What I did not verify

Whether the Home storage ring row (`cardStorage`) still shows; Home sidebar profiles and preview toggles; `AdvancedSettingsFragment` content; `WatchSettingsFragment`; whether the Settings backup covers Joy Creator prefs; whether `SettingsCatalog` search names include dual camera and tips; whether trash EVIDENCE items map to a `TrashKind`. Nothing here was tried on a phone.
