# Launch bug hunt, 2026-11-15

Session "Launch bug hunt" (Claude Fable 5.2), branch `claude/bughunt-20261115`, base `joy-creator` 74787f446.
Every wing except the paint engine (GlPaintEngine, gl/media, JbCanvasView, JbArchive, the JoyBrush save path).
Method: the crash records the test devices keep themselves (`dumpsys dropbox`, read on the Note 9, tablet and
Fold), then seven read-only reviewers, one per area, whose claims I re-read in the code before writing them here.

**How sure each line is**
- **DEVICE**: a crash record on a test device, and the same code is still there today.
- **READ**: I followed it in the code myself, line by line. Not seen on a device.
- **REVIEWER**: a reviewer traced it; I have not re-read every step. Treat as "very likely", check before a big fix.

**Not covered:** in the Studio editor, the insides of the drawers and tools (dead buttons there), the timeline's
touch code and the overlay / player / captions folders: section E is the editor's opening, undo and sheets only.
Also not read: most of `RecordsFragment`, the playback seek code, the Library's folder index internals, the
3,000 lines of JoyBrush Select.

**What is proven about my fixes:** see "Fixes on this branch" at the end. Read that before trusting a ✅.

---

## A. What a tester hits first

| # | What happens | Sure? | State |
|---|---|---|---|
| A1 | **The first-run intro closes the app** if you tap on while a slide is fading. Seen twice on the Fold (11-14 15:56, 16:01). | DEVICE | Fix ready, handed to the shell-lobby lane (it holds the file) |
| A2 | **The app closes when it comes to the front** while Android still counts it as in the background (screen locking as it opens is enough). Seen on the tablet, Android 15, 11-14 09:41. | DEVICE | ✅ fixed here |
| A3 | **Settings closes the app** if you leave it at the wrong moment. Seen on the Note 9, 11-15 02:44. | DEVICE | Fix ready, shell-lobby lane |
| A4 | **"Support" on the very first intro screen does nothing** (the link is empty), right under a paragraph asking for support. | REVIEWER (certain) | Shell-lobby lane: hide the button while the link is empty |
| A5 | **Deleting a project loses its pictures and short clips for good**, though the app says "You can put it back from Trash". Hits anyone who chose their folder before making a project (every new user): the folder copy then never includes pictures kept inside the app, and delete removes the only copy. | READ (the three steps; not run) | **BIG, lost work.** Orchestrator |
| A6 | **Two quick taps on a drawing open it twice**; the copy underneath later saves its older picture over the newer one. | READ | ✅ tap guard fixed here. The same thing through Joybot ("take me to JoyBrush" while a drawing is open) is NOT fixed: needs "one open screen per drawing" in the engine session's save path |
| A7 | **Drawings list closes the app** if the phone is turned or the window resized while it is still syncing, renaming or deleting. | READ | ✅ fixed here |
| A8 | **Photo viewer shows "EVIDENCE • conf 0.00 • Unknown time"** on any ordinary photo, and its ⋮ opens a forensics sheet (device check F6). `ImageViewerActivity` :122, :280-305. | REVIEWER (certain), seen on the Fold 11-15 | Small. Orchestrator |
| A9 | **A missing file opens a blank 00:00 player**, and every video open asks for notification permission (F7). `LibraryOpener.openMedia` :147, `VideoPlayerActivity` :1341, :2930. | REVIEWER (certain), seen on the Fold 11-15 | Small. Orchestrator |
| A10 | **Tablet / Fold: edits made in Studio beside Joybot's floating chat card are thrown away** when the chat closes (the editor counts as "off screen", skips its saves, then reloads Joybot's file). Undo cannot bring them back. | REVIEWER (code path certain; needs one device check that the editor takes touches beside the card) | **Lost work.** Joybot-sprites lane (it owns the card). Smallest cure: no free-floating card over Studio |
| A11 | **Joybot double tap: "Thinking…" forever.** Two chats open; closing the top one disconnects the one underneath from its answers. | REVIEWER | Joybot lanes: tap guard in `JoybotOpen.start`, re-attach in `onResume` |
| A12 | **SpriteLab: Auto-centre, Feet and Match move every frame again on each further press.** | READ | ✅ fixed here |
| A13 | **TextLab loses unsaved changes** if you press Home and Android later closes the app: no draft, no question. | REVIEWER | Medium. TextLab lane |
| A14 | **VisLab opened from a look in the Library on a fresh start shows the last look, not the one tapped.** | REVIEWER | Small. VisLab lane |

## B. Google Play problems (the review can reject the app for these)

| # | What | Sure? | State |
|---|---|---|---|
| B1 | **A recording could hide behind a fake "System Update" / "Downloading" / "Syncing" notice with no Stop button**, in the Play build too. Play calls this stalkerware behaviour. | READ | ✅ Play build now always shows the honest notice with Stop (two switches in `SharedPreferencesManager`). Still to do (shell-lobby lane): hide the two rows in Settings → Notifications on the Play build, they now do nothing there |
| B2 | **Any other app on the phone can start a recording or take a silent photo through Joy Creator**, with no question asked: `RecordingStartActivity`, `RecordingStopActivity`, `PhotoCaptureActivity` (a 1-pixel invisible window), `TorchToggleActivity`, `TorchService`, `AlarmService`, `TorchToggleReceiver` are open to all apps. | READ (manifest) | **Blocker.** Orchestrator / Play-build lane: close them in the Play manifest, then check the home-screen shortcuts still work on a phone |
| B3 | **Remote Studio has no lock at all.** With Remote on, anyone on the same Wi-Fi can watch and listen live, start or stop recording, ring the alarm, switch the torch. The password only guards the web page's own login screen; the phone never checks it (`validateAuthToken` is called from one place: change password). It listens on every network and answers any web page. | READ | **Blocker for safety and Play.** BIG. Orchestrator. Until fixed: hide Remote in the Play build |
| B4 | **The Play build offers updates from GitHub** (automatic check on by default; "Visit GitHub", "Get the beta"). Play forbids updating outside Play, and that download is a different app. `HomeFragment` :1361, `UpdateAvailableBottomSheet` :150, `AboutVersion` :112. | REVIEWER (certain) | Small. Play-build / shell-lobby lane: off under `PLAY_BUILD` |
| B5 | **Asks for access to all your videos on the way into Capture**, but the Play build never reads the video library. `CaptureAccess` :72, manifest :16. | REVIEWER, unsure | Play-build lane: drop it if Records and the Library do not need it |
| B6 | **Android 15: any long-running helper closes the app after six hours in a day** (Joybot's computer bridge, Remote, long exports, Joybot jobs), because none of them stops when Android says time is up. | READ (no `onTimeout` anywhere) | ✅ bridge, Joybot jobs and Remote now stop cleanly. The Studio lane added the other four (`ExportService`, `FileOperationService`, `BatchMediaActionService`, `RecordsDeletionService`) on claude/export-safety-20261115; an export stopped this way says so and keeps the project. Play-form question for the store lane: Remote and the bridge are declared as "data sync", which they are not |
| B7 | **"Report this reply" reports "Thinking…"** instead of Joybot's answer (the first answer of each request). Play's AI rule expects the report to carry the reply. | READ | ✅ fixed here |
| B8 | The Play build still says it runs on watches and TVs (manifest :27, :29, :106); Play may review it as a Wear / TV app. | REVIEWER, unsure | Play-build lane |
| B9 | Tapping the title on Capture says "First enable Privacy Black Screen", a setting the Play build does not have. | REVIEWER (certain) | Small. Shell-lobby lane |

## C. Less often, still launch-grade

| # | What | Sure? | State |
|---|---|---|---|
| C1 | **Cancel during an export's "Finalizing" cancels nothing** but drops the keep-alive; if Android then closes the app, a custom folder is left with a cut-off video under the finished name. `ExportService.cancelExport` :1054. | READ (the cancel code), REVIEWER (the rest) | Small. Studio lane (not the cuts code) ✅ 11-15 Studio lane (claude/export-safety-20261115): Cancel during Finalizing now says it is finishing the file and keeps the keep-alive; the screen greys Cancel out. Fold: seen greyed during a Clean Audio export, file finished whole. The notification Cancel is code-checked only. |
| C2 | **Queued exports (several named regions) die with the phone locked** after the first one: the service leaves the foreground between jobs and may not come back (:556, :435). | REVIEWER | Small. Studio lane ✅ 11-15 Studio lane: the service stays in front while exports are queued; a refused start ends in a plain message. Fold: two queued exports, screen locked before the first ended; the second started and finished while the phone slept. |
| C3 | **A big .joypack closed the app** (read whole into memory; out-of-memory was not caught), and turning the phone while a pack is read crashed its dialog. | READ | ✅ now says it cannot be read; the real cure (read packs to disk) is BIG, pack lane |
| C4 | **A Joybot request could close the app** when Android refuses its "working" notice (app in the background). | READ | ✅ fixed here |
| C5 | **Remote "Start recording" with the app in the background** fails, or closes the app when battery saving is off for Joy Creator (Android 14 does not let the camera start from the background). `LiveM3U8Server` :650, `RecordingService` :4983. | REVIEWER | BIG. With B3 |
| C6 | **Dual-camera shortcut with the microphone denied closes the app** (`DualCameraRecordingService` :300-353 stops before showing its notice). Same shape in `RecordingService` :1161-1192 when Start arrives within 1.5 s of Stop. | REVIEWER | Small each. Orchestrator |
| C7 | **A pinned reference photo that lives only in the cloud** holds up opening the drawing and every save while offline (decoded on the one save thread). `JoyBrushActivity` :658, :2195. | REVIEWER | Small, but in the engine session's file |
| C8 | **Raw error text reaches people** (the "no technical errors, ever" bar): JoyBrush `DrawingLibrary.kt:201`, `JoyBrushActivity.kt:2573, 2771, 2905`, `BoardExportCoordinator.kt:211`, `JoyBrushBoardImportActivity.java:172`; Joybot `JoybotAgent` :1199, :1243, :377 ("API error 403: {…"); packs `PackOpenActivity` (zip messages). | READ (grep) | Small each. Lanes |
| C9 | A deleted drawing can come back as "Recovered drawing N", or a blank "My drawing" appears (delete while its screen is open underneath; tapping the card while the delete still copies). | REVIEWER | Small |
| C10 | SpriteLab on a sheet made from separate frame files: the align tools measure frame 1 for every frame, and the offset means different distances in preview, thumbnails and export. `SpriteSheetEditorActivity.inkBox` :3524, `SpriteSheetRenderer` :335. | REVIEWER | Small. Animation lane |
| C11 | SpriteLab "Write .sprite.json" writes into the app's private storage: nobody can reach the file, and Import cannot find it. | REVIEWER (certain) | Small. Animation lane |
| C12 | VisLab: a song that cannot be decoded fails silently (no message, the picture just stops reacting). `SongPlayer` :75, :154. | REVIEWER | Small. VisLab lane |
| C13 | Library: "Move to Trash" refuses a project made a few seconds ago ("no copy"); Select mode forgets chosen recordings whenever the screen comes back. `LibraryActions` :278, `LibraryFragment` :252. | REVIEWER (certain) | Small each |
| C14 | The export helper opens its own copy of the Joy Creator folder and can delete a file the main app is still writing. `ExportManager` :8113, `JoyFolder` :54. | REVIEWER, unsure of real harm | BIG. Note for FILE-1 |
| C15 | Joybot's bridge listens on every network, not only Wi-Fi, and its lock-out message is wrong ("switch off and on for a new code" does not give one). It is protected by its code and pairing. | REVIEWER | Small. Joybot lane |
| C16 | Screen recorder adds the microphone in a second step that can be refused (Android 14) after the file is open. `ScreenRecordingService` :744. | REVIEWER, unsure | Small |
| C17 | Closing What's New restarts the whole app on the lobby; the Capture permission step can show "blocked, open Settings" after a tap outside Android's question. | REVIEWER | Small. Shell-lobby lane |
| C18 | Known and still open: the "Soon" cards in Classic settings and the Home side panel (SHELL-15, owner decision). | REVIEWER | Owner |

## E. Studio editor (added after the main list; E1 to E4 belong up beside A5 for a tester who makes podcasts)

All in `FaditorEditorActivity.java` unless named. Nothing here is fixed on this branch: several lanes are editing
that file, so these go to the orchestrator.

| # | What happens | Sure? | Size |
|---|---|---|---|
| E1 | **A new "Blank audio project" opens with Play, Export, Volume, Speed and Canvas dead** until you leave and reopen it. `startBlankAudioProject` (:1908) never runs the set-up that wires those buttons (`initPlayer`, `initTimeline`, `initToolbar`, `initExport`, `initBackHandler`, :3965-3983). | READ | Small ✅ 11-15 Studio lane. Fold and tablet: Play, Export, Add and Back work on a new blank audio project. |
| E2 | **An audio project with nothing left in it can never be opened again** ("No video selected", editor closes): delete the last audio clip, or start a blank audio project and leave before adding sound. Its titles, sprites and undo history are stranded. `hasLoadableContent` :1932, :1880. | READ | Small ✅ 11-15 Studio lane. Fold: the audio project reopens (a first build crashed here on every saved project: fixed in 5fb7be4f6 before landing). |
| E3 | **Add → Video / Image → "Browse files" always fails in an audio-only project** ("could not add"): inserts at position 1 of an empty row. `onVideoAssetPicked` :49268, `onImageAssetPicked` :49196. | READ | Small (clamp, as the multi-add path does) ✅ 11-15 Studio lane. Fold: Add → Image (5s still) → Browse files adds the picture to an audio project. |
| E4 | **Undo / Redo in a project with no video leaves the timeline and the sound showing the old clips**; later edits can land on a different clip than the one shown selected. `refreshEditorAfterUndoRedo` :15317 returns early. | READ (the early return), REVIEWER (the knock-on) | Small ✅ 11-15 Studio lane, code-checked: the no-video branch now refreshes lanes, players and overlays. Not reachable on a device today (see E11, E12). |
| E5 | **A project whose first video is missing stays covered by "Opening project…"** if you close the Relink sheet: every tap is swallowed, only Back works. :1859, :5832, `checkMissingMedia` :4392. | REVIEWER | Small ✅ 11-15 Studio lane, code-checked: the cover lifts when no frame can come. |
| E6 | **Open sheets come back dead** (Volume, Speed, Canvas, Crop, Flip, Add, Relink, Video source, Settings) after Android rebuilds the editor: unfolding the Fold, dark mode switching at sunset, a font-size change. Their buttons then do nothing. Manifest :133 lacks `uiMode` and `smallestScreenSize`. | REVIEWER (certain in code) | Small each: a restored sheet with no owner closes itself ✅ 11-15 Studio lane, code-checked: a restored sheet with no owner closes itself. Manifest left to TAB-1. |
| E7 | **After "Open last backup", a rebuild of the screen loads the backup again and saves it over newer work.** :26985, :1697, `ProjectStorage.loadBackupOnly` :657. Rare path, real loss. | REVIEWER | Small ✅ 11-15 Studio lane, code-checked: the backup loads only on the first open. |
| E8 | **A drawer left open across an Undo edits a copy that is no longer saved** (after reopening a project, or after a Joybot change): Level and Mute changes do not stick. `showAudioDrawer` :32601, :15239. | REVIEWER | Small: close or re-bind the drawer on a restore ✅ 11-15 Studio lane, code-checked: the drawer re-binds to the restored clip after Undo. |
| E9 | **Tapping the export notification after leaving the editor opens nothing** ("No video selected"): the notification carries no project. `ExportService` :1111, :1211. | REVIEWER | Small ✅ 11-15 Studio lane, code-checked: the notice opens the exported project. |
| E10 | Leaving the editor writes the whole undo history on the main thread every time; possible multi-second hang on Home for big projects. `saveProjectNow(true)` :2345, `ProjectStorage` :1152. Not measured. ✅ **Measured on the Note 9 (11-16), no change made:** with a full 50-step undo history (2 MB, `restore-test-0002`), the blocking save on Home took 134, 108, 109 and 105 ms, with no skipped-frame warning. The largest project file on that phone is about 3.5 times bigger, which suggests about 0.4 s at worst; Android's hang limit is 5 s. Writing synchronously is what keeps the last edits safe if Android kills the app, so it stays. Every leave now logs `FaditorEditor: E10 blocking save took`. | REVIEWER, unsure | Medium |
| E11 | **An audio-only project shows an empty timeline**: no ruler, no playhead, no sound clip to tap, trim or delete, until a picture or video is added. `EditorTimelineView.onDraw` returns when there is no picture clip (from the FadCam fork, 2026-06-05). Fold and tablet, 11-15. | DEVICE (Studio lane) | Medium. Timeline drawing; the podcast flow depends on it |
| E12 | **Undo/Redo of adding a picture to an audio project is not exact**: Undo leaves the automatic black spacer behind (a 20 s black clip the user never made), and Redo pushes the voice track 5 s later (offset 0 → 5000 ms, project 0:20 → 0:25). `AddClipAction` and `syncTrailingBlankForOverhang`. Fold, 11-15. | DEVICE (Studio lane) | Small to medium. Undo lane |

## D. Old crash records, checked: already fixed

- Brush drawer closing with imported picture brushes (tablet and Fold, 11-13 19:49, 19:58): fixed 9ffc56639.
- Studio title look crash (Note 9, 11-12 19:14): fixed.
- Studio not opening a project without a video clip, three records (Note 9, 11-12 10:14 to 10:27): fixed the same
  morning (ba41ad8c0). Only the fix's presence was confirmed; empty projects were the cut-off reviewer's job.

No "app not responding" (freeze) records on any of the three devices.

## Checked and found sound (so nobody re-checks)

A tester with no AI key gets answers from the help book, no spinner, no dead Send. The AI key is stored encrypted
and never logged. Pack files cannot write outside their folder. Same-name saves never overwrite (TextLab, VisLab).
Screen-recording start order is right for Android 14. Nothing starts at boot. No code installs an app. The Play
build's removed parts (disguise icons, screenshot service, black screen) are not reachable, except B9's message.
Library and Trash act on the item you chose (no wrong-row deletes). Project saves are atomic.

## Fixes on this branch

| Fix | Files | Proof so far |
|---|---|---|
| A2 foreground crash | `FadCamApplication` | see "Build and device" below |
| Two delayed buttons asking a page that has gone | `FaditorMiniFragment`, `NetworkHealthBottomSheet` | read only |
| A6, A7 drawings list | `joybrush-android/.../library/DrawingsActivity.kt` | read only |
| A12 SpriteLab align | `SpriteSheetEditorActivity` (autoCentre, matchToCurrent) | read only: press twice on a device still owed |
| B1 honest recording notice on Play | `SharedPreferencesManager` | Play build only: not seen on a device |
| B6 six-hour stop | `AIJobService`, `BridgeService`, `RemoteStreamService` | cannot be shown without a six-hour run |
| B7 report flag | `ChatAssistantActivity` (two spots: the flag's tag) | read only: needs an AI key on the device |
| C3 pack too big / turned | `PackOpenActivity` | read only |
| C4 Joybot notice refused | `JoybotAgent`, `AIJobService` | read only |

Handed over, not landed: A1 and A3 as `launch_bughunt_20261115/shell_lobby_two_crashes.patch`.

### Build and device

- **Build:** cloud run 43743671183, debug, commit b682d76a4: green in 5.2 minutes. So everything in the table
  compiles. The Play flavour (B1's switch) was not built.
- **Device: the test Fold (Android 14), 11-15 about 13:18**, installed over the existing app, data kept. The tablet
  (where A2 was first recorded) was off Wi-Fi and the Note 9 was in use.
- **A2 is proven fixed there.** The Fold's own records show the same crash at 08:00 that morning on the older build
  (`Failed to call observer method`, the "went to the background" half of the same code). With this build I put the
  Fold to sleep, started the app, and woke it: Android refused both messages (`recorder not told:
  ACTION_APP_BACKGROUND`, then `ACTION_APP_FOREGROUND`, each with `BackgroundServiceStartNotAllowedException` in the
  log), the app stayed open on the lobby, and no new crash record was written. Shot:
  `tasks/screens/bughunt-20261115/fold_after_wake.png`.
- **Everything else in the table is "builds, read, not tried on a device"**: the drawings list, SpriteLab align
  (press Auto-centre twice), the pack screen, the report flag (needs an AI key), the Play-only notice switch, the
  six-hour stop. They are small and guarded, but nobody has watched them work.
- One slip to record: I wrote the screenshot to the Fold's Download folder with a raw adb call instead of
  `phone.ps1` (I held the lock; the file is removed).
