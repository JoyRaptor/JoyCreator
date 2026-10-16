# SPEC — The Joy Creator folder (file management for the whole studio)

> **LIVE SPEC. This file describes what is BUILT and what the owner decided, as of 2026-10-14.**
> Code that matches it is intended. Do not revert it, re-route recordings back into category
> subfolders, rename files back to `FadCam_…`/`FadRec_…` inside the folder, or add a second Trash /
> browser / picker beside the existing ones. If something here looks wrong, ask the owner — do not
> "fix" it toward an older document. Older planning text was removed on purpose; git history has it.
>
> Owner of this lane: the cross-cutting Claude account. JoyBrush's half is briefed in
> `tasks/joybrush/briefs/JOY_FOLDER_FOR_JOYBRUSH.md` (JoyBrush lanes build it).

## 1. What the owner decided (2026-10-13)

- **Uninstalling Joy Creator must never lose work.** ("its important that if someone uninstalls JoyCreator the projects are not lost")
- **One folder the user chooses, as close to the top of storage as possible.** Not `Documents/…`.
- **Folders you earn.** Work sits at the top; the app never makes a folder in advance. Few folders, readable names, no code-like names. "Let users organize; give good tools and hints."
- **The Obsidian model:** it is just your folder; brushes, styles, projects are plain files you can open and edit.
- **Share-in:** with a project open → wherever that project's file lives; with nothing open → the Assets folder if the user has one, else the top.
- **Consolidate** = gather a project into a folder named after it; files other projects also use → leave them or move them to Assets.
- **Terms he likes:** Assets, Exports / Finished, Recordings, Supplies (over "Kit").
- **2026-10-14: choosing the folder is a required first step**, not a reminder. "Its important that they actually choose a folder at the beginning and stick with it … so they dont get a fractured body of work rather then trying to manage a migration afterwards." No "keep it inside the app" options in the folder screen.
- **2026-10-14: earned folders only for clutter.** "If the majority is recordings then thats their main workflow. Folders are only helpful if things are cluttered and disorganized."
- **Reuse before building:** for every major piece, extend what already exists (the Library, the Trash, the recorder's storage setting…) instead of a parallel one.

Design page with his picks: `tasks/design/Joy-Creator-folder-options.html` ("Folders you earn").

## 2. The rules (implemented in `studiokit/.../joyfolder/Placement.java`)

1. **Anything that belongs to a piece of work goes wherever that work lives** (its exports, its imports, share-ins while it is open).
2. **Anything that belongs to no work goes to its folder if the user has one, otherwise the top** (recordings → Recordings; share-ins → Assets).
3. **Nothing breaks when things move.** Files are tracked by content fingerprint; a project whose files were moved or renamed inside the folder is pointed at their new place before it loads.

A folder the user makes to collect a kind wins for that kind (an Exports folder takes every export). Folder roles are adopted by name ("Recordings", "Assets", "Exports", "Finished", "Supplies") and remembered in `.joycreator/folders.json`.

## 3. What the folder looks like

```
Joy Creator/                         (user-chosen; suggested at the top of phone storage)
  Lecture 3.joyproject               a Studio project: readable JSON, media referenced as joy:<path>
  Lecture 3.mp4                      its export, beside it
  Lecture 3 image.png                media that only lived inside the app, copied in once (deduped)
  Camera 2026-10-13 23.51.mp4        recordings: readable names, no Camera/Back/Screen subfolders
  Screen recording … · Photo … · Selfie … · Screenshot … · Dual camera … · Stream … · Converted … · Merged …
  Recordings/ Assets/ Exports/ …     only if the user makes them
  .joycreator/                       hidden: folders.json, index.json, studio.json, trash.json, Trash/
```

## 4. What is built, and how proven

Legend: **📱** driven on the Note 9 by the agent · **🔨** compiles (watcher BUILD SUCCESSFUL), not yet on a phone · **🧪** unit-tested (`bash tools/joyfolder-tests/run.sh`, 21 tests).

| Piece | Where | Proof |
|---|---|---|
| Core: names, kinds, placement rules, roles, fingerprints, index, atomic writes + crash recovery, Trash store, Consolidate planner, usage, suggestions | `studiokit/src/main/java/com/fadcam/joyfolder/` | 🧪 |
| Folder access over Android's picker (SAF), the chosen folder, picker opening at the top of storage | `studiokit/.../joyfolder/android/` | 📱 |
| Setup screen: make / "I already have one" / reconnect / unavailable state; recordings switch; consent showing the media size | `app/.../joyfolder/studio/JoyFolderSetupActivity.java` | 📱 |
| Reached from Settings → Storage Location → "Joy Creator folder", and a banner on the Studio project list | `StorageSettingsFragment`, `FaditorMiniFragment` (via `JoyFolderBanner`) | 📱 |
| Every Studio project mirrored to the folder ~4 s after each save; app-only media copied in once, deduped by content | `StudioMirror` + hook in `ProjectStorage.writeProjectJson` | 📱 |
| Restore after reinstall ("Bring back projects"); projects open with media from the folder | `StudioMirror.restoreMissing` | 📱 |
| Moved/renamed files found again before a project loads (rule 3) | `StudioMirror.healMovedFiles` + hook in `ProjectStorage.load` | 🔨 |
| **One Trash:** folder deletions appear in the existing "Recently Deleted" screen (long-press the Lobby logo); Restore / Delete / Delete all / the user's auto-delete setting all apply | `JoyTrashBridge` + `TrashFragment` / `TrashAdapter` | 📱 |
| Delete a project → "Moved to Trash · Undo" (8 s); delete also removes the app copy's subfolders | `FaditorMiniFragment`, `ProjectStorage.delete` | 📱 |
| **Recordings into the folder:** choosing the folder points the recorder's existing Storage Location at it; recordings land at the top (or Recordings/), readable names, split pieces "… part 2", webcam side file "… webcam.mp4" | `RecordingStoragePaths` (central), name hooks in `RecordingService`, `ScreenRecordingService`, `DualCameraRecordingService`, `PhotoStorageHelper`, `BatchMediaActionService` | 📱 camera · 🔨 the rest |
| **Library (Records tab) reads the folder:** recordings by readable name, exports by the folder's record, older in-app recordings still listed, project media copies kept out | `FastFileScanner` | 📱 |
| **Exports into the folder:** named after the project (the export dialog pre-fills it), beside the project or in Exports/, recorded so the Library shows them | `ExportManager.copyTempToJoyFolder`; dialog default in `FaditorEditorActivity` | 📱 |
| Raw camera/screen recordings inside a picked folder can be clipped and exported: export resolves picked-folder files to real paths exactly as preview does (the first device export failed with "Illegal clipping: not seekable to start" before this) | `utils/SafPaths` (moved out of the editor), used by `ExportService` / `ExportManager` | 📱 |
| **Share-in:** "Share to Joy Creator" for pictures, clips and sounds; saved per the owner's rule (beside the open project, else Assets, else the top), readable names ("From ChatGPT …"), no duplicate copies; "Add to <open project>" inserts at the playhead through the asset browser's insert path (one undo); "Start a project with it" for a video; asks for the folder first when none is chosen | `ShareInActivity`, `ShareInbox`, two hooks in `FaditorEditorActivity` (`takeSharedFiles`) | 📱 |
| **Gather into one folder:** project rename dialog → "More…" (the revived link-options menu) → "Gather into one folder" when the folder is reachable (else the old "Consolidate project"). Moves the project, its media and its exports into a folder named after it; files other projects use: leave, or move to Assets (preselected if Assets exists); "also move the recordings it uses" box. Gathering twice never nests. Every project is re-pointed | `ConsolidatePlan`, `StudioMirror.planGather/applyGather`, `FaditorEditorActivity.showGatherDialog` | 📱 |
| Library also lists exports and recordings inside gathered folders (scans the folders that hold them) | `FastFileScanner.scanJoyFolder` | 📱 |
| **"Recently deleted"** row under Settings → Storage Location opens the one Trash | `StorageSettingsFragment` | 📱 |
| **Not used by any project:** the editor asset browser's filter cycles All → this project uses → this project doesn't use → **no project uses** (link-off icon, a one-line toast names each mode). Counts every Studio project's links *and* the files the folder's project copies point at, so the uninstall safety copies are never shown as unused | `AssetBrowserPanel`, `StudioMirror.usedByAnyProject/usageKey` | 📱 |
| **Earned folders:** at 20+ recordings (known by their recorder names) or exports at the top **that are fewer than half of everything at the top** (clutter, not the main work), the folder screen offers "Make Recordings folder" / "Not now" (remembered). Yes = make it, move the pile in, give it the role so new ones follow, re-point every project; refused while a recording runs | `Suggestions.check/plan`, `StudioMirror.suggestion/fileAway`, `JoyFolderSetupActivity` | 🧪 + 🔨 (not yet triggered on a phone: needs 20 loose recordings) |
| Camera recorder whose Joy Creator folder is unreachable (SD card out, folder renamed, permission revoked) keeps that recording inside the app, with a notice, instead of refusing to start | `RecordingService.effectiveStorageMode` | 🔨 |
| **Required first step:** the main screen opens the folder screen ("First, choose where your work lives") until a folder is chosen; Back leaves the app; "Start creating" once chosen. Covers new installs (after onboarding) and existing users alike. Skipped only on devices with no folder picker. Replaced the 3-day reminder | `JoyFolderSetupActivity.requireChoice`, one line in `MainActivity.onResume` | 📱 |
| **Big files are linked, with a warning; the yellow dot; bring them in** (owner 2026-10-14). Pictures and clips up to 25 MB (asset browser) / 10 MB (pickers) are copied as before; a bigger one from outside the folder is linked where it is, with "This is a large file (41 MB), so it's linked where it is, not copied. [Copy]". A **yellow dot** marks a project that links to files outside the folder: after the editor title, on the Studio project list, and on Lobby project cards. Tap it (editor or list) for "N files are outside your folder (size) … Bring them in" — copied beside the project, links re-pointed (clips, overlays, audio, image overlays), same file brought in once. Folder links (the browser's pinned folder) don't count | `StudioMirror.isOutside/outside/bringIn/bringInAll` (counted on each folder save), `FaditorEditorActivity` (dot, warning, in-memory swap), `FaditorMiniFragment`, `LobbyFragment` | 📱 |
| **Copy · Move · Leave per file** (owner 2026-10-14) + Copy/Move/Leave all; app recordings default to Move. Move = copy, re-point every project, then remove the original (file / picked folder / Android's delete prompt); a ledger keeps undo history and reopened projects pointing at the copy | `BringInDialog`, `OriginalsRemover`, `StudioMirror.repointOthers/rememberMoved/movedTo` | 📱 Move tested (original removed, copy reused) |
| **Space** (Settings → Storage Location → Space): folder by kind + Trash, inside-the-app sizes with Clear for rebuildable caches, biggest files with "no project uses it" → Trash | `SpaceActivity` | 📱 |
| **JoyBrush drawings** — many drawings with thumbnails, each copied to the folder as "<Name>.joybrush", restored from the folder; gallery; the Lobby's Joy Brush room shows them | `joybrush-android/.../library/` (DrawingLibrary, DrawingsActivity), 2 hooks in `JoyBrushActivity`, `LobbyFragment` | 📱 (status for the JoyBrush lanes: `tasks/joybrush/briefs/JOY_FOLDER_FOR_JOYBRUSH.md` §0) |
| fsync before the project file rename; uninstall offers "keep app data?" (`hasFragileUserData`); AI chat defaults to the free router | `ProjectStorage`, manifest, AI files | 🔨 |

## 5. Existing parts this extends (do not duplicate)

| Job | Existing part | How it is used |
|---|---|---|
| Media browser | Library = `RecordsFragment` / `FastFileScanner` / `VideoIndexRepository` | scans the Joy folder when the recorder points at it |
| Trash | `TrashFragment` / `TrashManager` ("Recently Deleted") | folder items added through `JoyTrashBridge` |
| Where recordings go | the recorder's "Storage Location" custom-folder mode | set to the Joy folder by the setup screen |
| Missing media | `ProjectIntegrity` + `RelinkCatalogBottomSheet` | still the fallback when a file is truly gone |
| Project zip / consolidate into the app | `ProjectBundle`, `ProjectConsolidator` (old entry `btnRelinkMedia` only shows when media is missing) | menu reached from the rename dialog's "More…"; Consolidate becomes "Gather into one folder" when the Joy folder is reachable |
| Used / unused filter | editor asset browser `AssetBrowserPanel` (ALL / USED / UNUSED, current project) | fourth mode added: no project uses |

## 6. Still to do — handoff list (updated 2026-10-15, end of the cross-cutting account's week)

Everything in §4 is pushed. This is the complete open list; nothing else is half-done in a working copy.

**A. Phone checks — run 2026-10-15 on the Note 9 (Haiku tester, evidence verified by the lead):**
- [x] Redo after undoing past the session start: undo ×6 then redo ×5, "Redid: Add clip", logcat has no "Redo skipped". ✅
- [x] Screen-recording and photo names: "Screen recording 2026-10-15 17.50.mp4", "Photo 2026-10-15 17.50.jpg". ✅
- [x] JoyBrush gallery: rename (folder file renamed too), delete → Recently deleted → Restore. ✅
- [x] Space → Clear: rebuildable 105 MB → 0 B. ✅
- [ ] Undo after a Move: not exercised (the test clip was 20 MB, under the 25 MB copy limit, so it was copied, not linked). Needs a >25 MB outside file. The Move itself is proven (10-14).
- [ ] Camera recording with the folder unreachable, and the earned-folder card: left for real use (testing either means renaming or littering the owner's real folder).
- [ ] Space "Move unused to Trash…" (multi-select, added 10-15): compiles, not yet on the phone.

**B. Small, unbuilt, in this lane:**
- [x] Space multi-select → Trash (5a0a3a4a).
- [ ] Share-in of a large file: today copied whatever the size; should follow the Copy/Move/Leave rule (link + warning above 25 MB).
- [x] "What's new" text is Joy Creator's (5a0a3a4a).

**C. Needs the owner:**
- [ ] Application ID (decided: not tied to the domain; candidates `studio.joycreator.app`, `cc.joycreator.app`). Switch only after he picks; the folder is the bridge (the new app restores from it).
- [ ] README.md / PRIVACY.md drafts: read, contact email, publish date.
- [ ] Intro copy approved 2026-09-09 was never saved to the repo — he must paste it.

**D. Other lanes (briefed, not ours):**
- [ ] JoyBrush lanes — `tasks/joybrush/briefs/JOY_FOLDER_FOR_JOYBRUSH.md` §0: owner's 10-12 drawing needs the media-tile reader to preview; textured paper in thumbnails; thumbnail = last view (§0 3b); "Open…" should add a new drawing; doc name on rename; autosave during continuous drawing.
- [ ] Play build split (roadmap 1.1–1.4): needs a build the watcher doesn't make.

**E. Later (not started):** presets/settings into the folder; version history of a project; the application ID change itself.

## 7. Test leftovers on the Note 9

`/sdcard/Joy Creator (first test)` (pre-dedupe run) and the project "Restore video test" (a copy of ZA_CONTROL) — both safe to delete. "Restore test" is in Trash. Also from the 10-13/14 runs: `Camera 2026-10-13 23.51.mp4` and `z1.png` at the top of the folder (both "no project uses"), and the "Restore video test/" folder made by the Gather test.
