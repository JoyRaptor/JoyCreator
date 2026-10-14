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
- **Share-in:** with a project open → wherever that project's file lives; with nothing open → the Assets folder if the user has one, else the top. (Not built yet; see §6.)
- **Consolidate** = gather a project into a folder named after it; files other projects also use → leave them or move them to Assets.
- **Terms he likes:** Assets, Exports / Finished, Recordings, Supplies (over "Kit").
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

Legend: **📱** driven on the Note 9 by the agent · **🔨** compiles (watcher BUILD SUCCESSFUL), not yet on a phone · **🧪** unit-tested (`bash tools/joyfolder-tests/run.sh`, 18 tests).

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
| **Exports into the folder:** named after the project (the export dialog pre-fills it), beside the project or in Exports/, recorded so the Library shows them | `ExportManager.copyTempToJoyFolder`; dialog default in `FaditorEditorActivity` | 🔨 |
| Raw camera/screen recordings inside a picked folder can be clipped and exported: export resolves picked-folder files to real paths exactly as preview does (the first device export failed with "Illegal clipping: not seekable to start" before this) | `utils/SafPaths` (moved out of the editor), used by `ExportService` / `ExportManager` | 🔨 |
| fsync before the project file rename; uninstall offers "keep app data?" (`hasFragileUserData`); AI chat defaults to the free router | `ProjectStorage`, manifest, AI files | 🔨 |

## 5. Existing parts this extends (do not duplicate)

| Job | Existing part | How it is used |
|---|---|---|
| Media browser | Library = `RecordsFragment` / `FastFileScanner` / `VideoIndexRepository` | scans the Joy folder when the recorder points at it |
| Trash | `TrashFragment` / `TrashManager` ("Recently Deleted") | folder items added through `JoyTrashBridge` |
| Where recordings go | the recorder's "Storage Location" custom-folder mode | set to the Joy folder by the setup screen |
| Missing media | `ProjectIntegrity` + `RelinkCatalogBottomSheet` | still the fallback when a file is truly gone |
| Project zip / consolidate into the app | `ProjectBundle`, `ProjectConsolidator` (entry hidden: `btnRelinkMedia` is GONE) | to be revived as the Consolidate entry (§6) |
| Used / unused filter | editor asset browser `AssetBrowserPanel` (ALL / USED / UNUSED, current project) | to be extended across projects (§6) |

## 6. Still to do (in this order)

- [ ] Device-check the export into the folder after the SafPaths fix (Note 9: "Restore video test" draft export → `Restore video test.mp4` beside its project).
- [ ] Device-check screen recording and photo names, and their Library categories.
- [ ] **Share-in** (nothing exists): an exported share target; place per the owner's rule; with a project open, add to its timeline.
- [ ] **Entry points:** the folder step in first-run onboarding (`Onboarding*` — another session has edits open there; coordinate); a visible way into Trash.
- [ ] **Consolidate:** revive the hidden link-options menu (Consolidate / Export bundle / Import bundle) and add "Gather into one folder" using `ConsolidatePlan`.
- [ ] **Suggestions** ("38 recordings at the top — make a Recordings folder?") using `Suggestions`; "not used in any project" via `UsageMap` in the asset browser.
- [ ] Camera recorder refuses to start when its custom folder is missing (pre-existing): fall back to inside the app with a notice.
- [ ] JoyBrush half (JoyBrush lanes): drawings in the folder, thumbnails, multiple drawings, autosave while drawing.
- [ ] Later: presets/settings into the folder; storage screen ("free up space"); version history; the application ID change only after the folder ships (the folder is the bridge to a new ID).

## 7. Test leftovers on the Note 9

`/sdcard/Joy Creator (first test)` (pre-dedupe run) and the project "Restore video test" (a copy of ZA_CONTROL) — both safe to delete. "Restore test" is in Trash.
