# Storage and Library audit (read-only)

Date: 2026-10-15. Scope: the shared storage backend, the Library screen, old per-feature storage paths, and a step list. No source file was edited, built, or run on a phone. Findings come from reading the code and the spec `tasks/SPEC_20261013_FILE_MANAGEMENT.md`.

## Summary

1. The shared space is the Joy Creator folder, built in `studiokit/src/main/java/com/fadcam/joyfolder/`. It identifies items by path plus a content fingerprint, and it defines 15 kinds by file ending (`FileKind.java`).
2. The Library (the Records tab, `RecordsFragment`) reads only recordings, photos, and exports from that folder, as video or image rows. Projects, drawings, sprite sheets, avatars, audio, documents, fonts, and presets are not shown. There is no working search, and the only filter is for recorder categories.
3. About a dozen user-content storage roots still live outside the shared space (section 3 lists them), for example `filesDir/avatar_library`, `filesDir/fonts`, `filesDir/faditor_audio`, and the drawing working copies. Avatars, fonts, and voiceovers are not mirrored at all. Several `RecordsFragment` legacy scanners are dead code.
4. The fix is to make the Library read the folder through its index rather than through directory heuristics, add a kind model and kind chips, and wire the existing search. Move the remaining app-only kinds in first. Five owner decisions are needed (section 4, last part).
5. Full file list and line numbers are below.

---

## 1. The backend

### 1a. Classes and what each owns

Pure Java core (`studiokit/src/main/java/com/fadcam/joyfolder/`):

| Class | Owns |
|---|---|
| `JoyFolder` | Facade over the folder. Atomic writes with crash recovery (`.joytmp` / `.joyold`), placing new files (`prepareDir`, `freeName`, `addFile`), Trash (`.joycreator/Trash` plus `trash.json`), role folders, the index. |
| `JoyFs` | The file operations the folder needs. Implementations: `FileJoyFs` (tests) and `android/SafJoyFs` (the user's picked folder via Android's picker). |
| `android/JoyFolderHome` | Which folder is picked, whether it is reachable, a cached `JoyFolder`, and the one background thread all folder I/O runs on. |
| `FolderIndex` | Path to size, modified time, fingerprint, and origin (`recording`, `export`, `share-in`, `mirror`, `import`, `joybot`). Rebuildable by walking the folder. Skips dot-names and plugin folders. |
| `Fingerprint` | `v1:<size>:<24 hex>`, from the size plus SHA-256 of three 64 KB samples. Used to find a file again after a move or rename. |
| `Placement` | The one policy for where a new file goes. `What` values: SHARE_IN, IMPORT, EXPORT, RECORDING, VOICEOVER, NEW_WORK, SUPPLY, FINISHED. |
| `FolderRoles` | Role folders (Assets, Recordings, Exports, Finished, Supplies), adopted by name and remembered in `.joycreator/folders.json`. |
| `FileKind` | The kind of a file, from its ending. See 1c. |
| `ReadableNames`, `ConsolidatePlan`, `Suggestions`, `UsageMap` | Readable file names, Gather planning, the "earned folder" offers (at 20+ loose items that are a minority), and which projects use which file. |

App side:

| Class | Owns |
|---|---|
| `app/.../joyfolder/studio/StudioMirror.java` (1365 lines) | Mirrors each Studio project to the folder as `<name>.joyproject`, copies app-only media in as `joy:` references, keeps state in `.joycreator/studio.json`, handles Gather, restore, bring-in, and trash-on-delete. |
| `studio/JoyTrashBridge.java` | Puts folder deletions into the one Trash screen. |
| `studio/SpaceActivity.java` | Settings > Storage > Space. Groups the folder by its own kind rules (`kindOf`, line 160). |
| `studio/ShareInActivity`, `BringInDialog`, `OriginalsRemover`, `JoyFolderSetupActivity`, `JoyFolderBanner` | Share-in, bring-in, moving originals, the folder setup screen, and the banner. |
| `utils/RecordingStoragePaths.java` | The recorder's folder-aware path helpers. `JoyKind` (line 289) holds the readable name prefixes: Camera, Front camera, Dual camera, Screen recording, Stream, Photo, Selfie, Screenshot, Converted, Merged. Also `joyRecordingName`. |
| `data/FastFileScanner.java` | Scans storage into index rows. Holds the Joy-folder branch (`scanJoyFolder`, line 613). |
| `data/VideoIndexRepository.java`, `data/VideoIndexDatabase.java`, `data/entity/VideoIndexEntity.java` | The Library's read model: Room table `video_index`, database version 1, destructive fallback on migration (`VideoIndexDatabase.java:23, 48`). Row key is `uriString` (unique). |
| `joybrush-android/.../library/DrawingLibrary.kt` | Drawings. Working copies in `getExternalFilesDir("joybrush")`, mirrored into the folder as `<Name>.joybrush` (`mirrorFile`, line 365). |

### 1b. How an item is identified

- In the folder: its relative path (for example `Client A/Promo.joyproject`), plus a content fingerprint so a moved or renamed file can be found again (`FolderIndex.find`).
- Projects: the project id stored inside the JSON (`StudioMirror.findById` line 313, `readId` line 322). A renamed `.joyproject` is still the same project.
- Drawings: the drawing's `docId` inside `document.json` (`DrawingLibrary.docIdIn`, line 403).
- Library rows: `uriString` in `video_index`, a `content://` document URI. This is not the fingerprint. A file moved outside the app becomes a new row after a rescan.

### 1c. Kinds

`FileKind.java:12-31`, by ending:

- **Work:** PROJECT `.joyproject`; DRAWING `.joybrush`; SPRITE_SHEET `.sprite.json`; AVATAR `.avatar.json`.
- **Supplies:** BRUSH `.brush.json`; PAPER `.paper.json`; PALETTE `.palette.json`; LOOK `.grade.json` `.caption.json` `.waveform.json` `.cube`; FONT `.ttf` `.otf`; PLUGIN (a folder with `plugin.json` or `mod.json`).
- **Material:** VIDEO `.mp4 .mov .m4v .webm .mkv .3gp`; AUDIO `.m4a .mp3 .wav .ogg .opus .flac .aac`; IMAGE `.png .jpg .jpeg .webp .gif .heic .heif .bmp`; DOCUMENT `.pdf .txt .md .srt .vtt .json .html`; OTHER.

The Library's own model is much narrower. `VideoItem.MediaType` is only VIDEO and IMAGE (`app/.../ui/VideoItem.java:11-14`). `VideoItem.Category` is CAMERA, DUAL, SCREEN, FADITOR, STREAM, SHOT, MINIAPPS, UNKNOWN (lines 16-25).

### 1d. Who writes what into the folder today

| Kind | Writer | Origin tag | Where |
|---|---|---|---|
| PROJECT | `StudioMirror` (NEW_WORK) | `mirror` | `StudioMirror.java:296-300` |
| DRAWING | `DrawingLibrary.mirrorFile` (NEW_WORK) | none | `DrawingLibrary.kt:386` |
| VIDEO / IMAGE recordings | Recorders, via `RecordingStoragePaths.joyRecordingName` | none | `ScreenRecordingService.java:615, 1408`; `PhotoStorageHelper.java:162` |
| VIDEO (exports) | `ExportManager.copyTempToJoyFolder` (EXPORT) | `export` | `ExportManager.java:7343-7347` |
| VIDEO / IMAGE / AUDIO (share-in) | `ShareInActivity` (SHARE_IN) | `share-in` | `ShareInActivity.java:135-164` |
| IMAGE / VIDEO (project media copies) | `StudioMirror.copyIn` (IMPORT) | `mirror` | `StudioMirror.java:428-432` |
| IMAGE / VIDEO (bring-in) | `StudioMirror` (IMPORT) | `import` | `StudioMirror.java:929-930` |
| IMAGE (Joybot pictures) | `JoybotPictures` (IMPORT) | `joybot` | `JoybotPictures.java:285-287` |
| AUDIO, DOCUMENT, FONT, LOOK, BRUSH, PAPER, PALETTE, AVATAR, SPRITE_SHEET, PLUGIN | **No writer found** | | |
| RECORDING, VOICEOVER, FINISHED, SUPPLY placements | **No caller** | | `Placement.java:75` (`case SUPPLY`) |

---

## 2. The Library

### 2a. What the Library is

- The Library is the Records tab. `MainActivity.java:2157` maps tab 1 to `navigation_records`, which is `RecordsFragment`.
- The lobby's Library door (`LobbyFragment.java:380-382`, `1267`), its search icon (`385`), and "Open Library" (`1814`) all route there.
- `SPEC_20261013_FILE_MANAGEMENT.md` section 5 names the Library as the one media browser and says not to add a second one.

### 2b. How it reads items

1. `RecordsFragment.loadRecordsList` (line 5511) calls `repository.getVideos` (line 5565).
2. `VideoIndexRepository.getVideos` (line 127). If `video_index` has rows, it reads them (the fast path). If not, it runs `FastFileScanner.scanAll` and inserts the result (the cold start). An invalidated index runs a delta scan.
3. `FastFileScanner.scanAll` (line 71). When the custom location is the Joy folder (`isJoyFolder`, line 358), it runs `scanSaf`, which calls `scanJoyFolder` (line 613). It also appends `scanInternal()` (line 110) so older in-app recordings still show (lines 87-91).
4. `scanJoyFolder` reads `FolderIndex` only to learn which files are exports (origin `export`) and which are named by a JoyKind. It then lists the top, Recordings, Exports, and any folder that holds an export or a kind-named file. It lists one level only and does not recurse.
5. `scanSafDirectoryFiles` (line 476), joy filter at lines 516-535. It keeps files whose name starts with a JoyKind label. It keeps other files only if their name is in the export set. Everything else is skipped (`continue`).
6. `inferMediaType` (line 598) accepts only `.mp4` and `.jpg` `.jpeg` `.png` `.webp`.
7. `entityToVideoItem` (`VideoIndexRepository.java:522`) turns each row into a `VideoItem`. An unknown media type falls back to VIDEO.

### 2c. Kinds the Library shows

Recordings (camera, front, dual, screen, stream), photos, selfies, screenshots, exports, and older in-app recordings.

### 2d. Kinds in the shared space that the Library does NOT show

- **Studio projects** (`.joyproject`). The lobby recents show them from the private working copy (`LobbyFragment.loadRecentsAsync`, line 1445). The Library does not.
- **Joy Brush drawings** (`.joybrush`). The lobby's Joy Brush room shows them from `DrawingLibrary.list` (`LobbyFragment.java:1985`). The Library does not.
- **Sprite sheets.** No separate file exists. They live inside the project JSON and `project/assets/*.png` (see 3).
- **Avatars.** Not in the folder at all (see 3).
- **Audio:** voiceovers and extracted audio are app-private, and shared-in sounds are dropped by the joy filter. `MediaType` has no AUDIO value.
- **Documents** (`.srt`, `.vtt`, `.pdf`, `.txt`, `.md`, `.json`, `.html`), **fonts**, **looks**, **brushes**, **papers**, **palettes**, **plugins**. No writer exists for most of these (see 1d).
- **Imported and shared-in pictures and clips.** Share-ins, imports, and Joybot pictures are in the folder, but the joy filter drops them unless their names match a kind. Files in Assets are never scanned. The spec intends project media copies to stay out (`mirror`), but this also hides share-ins and imports, which are the user's own material.

### 2e. Search

There is none. `RecordsFragment.java:4938` declares a `SearchView` that is never assigned. `isSearchActive` (line 4946) and `clearSearch` (line 4953) therefore do nothing. No query filtering exists in the file. The lobby's search icon routes into this screen and gets no search.

### 2f. Filters

Only recorder categories. The chips are declared at `RecordsFragment.java:381-409` and wired at `3322-3406`. `setActiveFilter` (line 3445) and the filter loop (around line 3501) work on `VideoItem.Category` and the camera, faditor, shot, and miniapp subtypes. There is no kind filter.

### 2g. Dead legacy code in RecordsFragment

Entry points with no callers (one reference each, the definition):

- `getPrimaryVideoFiles` (line 317)
- `loadPrimaryVideosProgressively` (line 5669)

Reachable only from those two, so also dead:

- `getInternalRecordsList` (2610), `scanCategoryDirectoryInternal` (2644), `scanFaditorDirectoryInternal` (2675), `scanCameraDirectoryInternal` (2708), `scanShotDirectoryInternal` (2742), `scanLegacyRootInternal` (2817), `getSafRecordsList` (2858), `getSafRecordsListProgressive` (2865), `addSafCategoryFiles` (6015), `combineVideoLists` (2593).

The `SearchView` fields at 4938-4957 are dead as well.

### 2h. The lobby is a second reader with its own labels

`LobbyFragment.loadRecentsAsync` (line 1445) merges three sources: `ProjectStorage` projects (private), `DrawingLibrary` drawings (external), and `VideoIndexRepository.getVideos` (the Library's model). It labels rows with its own switch (lines 1468-1488: EXPORT, SCREEN, PHOTO, STREAM, RECORDING). The lobby's "N ITEMS" count (line 1280) counts these recents, not the Library.

---

## 3. Old per-feature storage paths still in use

| Path | Code | What lives there | In the shared space? |
|---|---|---|---|
| `filesDir/faditor/projects/<id>/project.json` | `ProjectStorage.java:84, 955-957` | Project working copy | Mirrored (`ProjectStorage.java:296` hook into `StudioMirror`) |
| `filesDir/faditor/projects/<id>/assets/*.png` | `SpriteSheetEditorActivity.java:168, 3042`; `JoyBrushBoardImportActivity.java:63`; `FaditorEditorActivity.java:41664, 42263, 42480, 45811` | Sprite sheet images, board imports, project pictures | Only if referenced as `project://`, via `atRisk` (`StudioMirror.java:357-365`) |
| `getExternalFilesDir("joybrush")` (+ `library.json`, `thumbs/`) | `DrawingLibrary.kt:79`, `85-130` | Drawing working copies | Mirrored (`mirrorFile`, line 365) |
| `filesDir/avatar_library/` | `AvatarLibrary.java:74-75`; read by `fadrec/ui/FloatingWebcamService.java:484-541` | Avatar rigs | **No.** Never mirrored. |
| `filesDir/fonts/` | `FontLibrary.java:91`; legacy import from `Pictures/FadCam/fonts` at `225-226` | Imported fonts | **No.** |
| `filesDir/faditor_audio/` | `VoiceoverRecorder.java:74-80`; `FaditorEditorActivity.java:4032, 9650` | Voiceover WAVs, extracted audio | **No.** |
| `filesDir/images/` | `ExportManager.java:4672`; `FaditorEditorActivity.java:16977-16979` | Generated black gap and filler PNGs | Generated, rebuildable. Fine to keep. |
| `getExternalFilesDir(null)/FadCam/...` | `RecordingStoragePaths.java:131-135`; `FastFileScanner.scanInternal` (110-204); fallbacks at `ExportManager.java:7197-7204`, `PhotoStorageHelper.java:118`, `ScreenRecordingService.java:1438-1440` | Internal recording tree (old in-app recordings, and the no-folder case) | Partly. Read only as legacy recordings. |
| `getExternalFilesDir(null)/faditor_export_errors` | `ExportManager.java:3105` | Diagnostic dumps | Fine to keep. |
| `getCacheDir()/faditor_export` | `ExportManager.java` (around 7183-7190) | Temporary copy before SAF copy-out | Transient, fine. |
| `getCacheDir()` temp | `PhotoCaptureActivity.java:128` | Temporary photo | Transient, fine. |
| Dead `RecordsFragment` legacy scanners | see 2g | none (dead) | Remove |

Other storage roots exist (about 155 hits in 64 files, from a grep). I did not read every one in depth. Examples are `ProjectBundle`, `BatchMediaActionService`, `FileOperationManager`, `RecordsAdapter`, and `TrashManager`.

---

## 4. Step list to make the Library show every kind, with search and a kind filter

### Phase A: cleanup, no behaviour change

- **A1.** Delete the dead loaders and their helpers in `RecordsFragment` (ranges in 2g). Delete the dead `SearchView` fields (4938-4957). Build must still pass.
- **A2.** Move the joy-folder name rules into one pure function in `studiokit`, for example `LibraryKinds.of(path)`, built on `FileKind` and `RecordingStoragePaths.JoyKind`. This makes it testable on the JVM.

### Phase B: the Library reads the shared space as a whole

- **B1.** Add a kind to the Library model. Either a new `kind` column on `video_index` (database version 1 to 2, with an explicit migration), or a set of new `VideoItem.MediaType` values. The `kind` column is cleaner. `safeParseEnum` (`VideoIndexRepository.java:522-530`) currently coerces unknown values to VIDEO, so new values must be handled there.
- **B2.** Replace the Joy-folder branch of `FastFileScanner.scanAll` with a walk of `jf.index.items()`. Every non-hidden file is a candidate, mapped through `FileKind`. Plugin folders become one row each. Keep `scanInternal()` for old in-app recordings. This removes the directory-by-directory heuristics at lines 516-535 and `scanJoyFolder`.
- **B3.** Decide how to treat the groups the joy filter drops today. Project media copies (origin `mirror`) can go under a "Project media" kind that is hidden by default. Share-ins, imports, and Joybot pictures (origin `share-in`, `import`, `joybot`) should show as Photos, Images, or Audio. Files in Assets should show.
- **B4.** Project rows. Read the folder's `.joyproject` files, match them to the project id through `StudioMirror.State`, and de-duplicate against the lobby's recents by project id. Open a row through the lobby's existing project open path (`LobbyFragment` around 1150-1160).
- **B5.** Drawing rows. Read the folder's `.joybrush` files, match them by `docId` to `DrawingLibrary`, and open them through `DrawingLibrary.openIntent`.
- **B6.** Sprite sheets. Recommended: one row per sheet, opening in Sprite Lab, which needs the project to be open. Otherwise they stay inside their project. Owner decision (section 5).
- **B7.** Move the app-only kinds into the shared space, mirrored the same way projects are.
  - Avatars: `AvatarLibrary.libraryDir` into a folder-backed Avatars area (`FileKind.AVATAR`), with a mirror step like `StudioMirror`.
  - Fonts: into `Supplies/Fonts` via `Placement.What.SUPPLY` (`Placement.java:75`, currently unused).
  - Voiceovers: via `Placement.What.VOICEOVER` (no caller yet), into Recordings or beside the project.
  - Generated `images/` and `faditor_export_errors` stay in the app.
- **B8.** Brush, paper, palette, and look presets. They ship inside the APK, and the spec's Supplies rule would copy them out. This is an owner decision (section 5).
- **B9.** After the upgrade, call `VideoIndexRepository.invalidateIndex` once (`VideoIndexRepository.java:453`) so existing installs see the new kinds without reinstalling. The index is a cache, so rebuilding it is safe.

### Phase C: the screen

- **C1.** Add a kind row of chips above the recorder chips: All, Recordings, Photos, Exports, Projects, Drawings, Sprite sheets, Avatars, Audio, Images, Documents, Fonts and supplies, Other. Reuse the existing `AnimatedChip` and `ChipGroup` pattern (`RecordsFragment.java:381-409`, `3322-3406`) and `setActiveFilter` (line 3445). Keep the recorder subchips under Recordings. Keep kind as its own dimension, separate from `VideoItem.Category`, so the recorder categories are not overloaded.
- **C2.** Wire search. Connect the existing `SearchView` (4938, cleared at 4953) to a case-insensitive match on the display name, applied in the same filter loop as the chips. The lobby search icon already routes here (`LobbyFragment.java:385`).
- **C3.** Use one kind-to-label mapping for the lobby recents (`LobbyFragment.java:1468-1488`) and the Library, so labels match.
- **C4.** Show a count on each chip. Make the lobby's "N ITEMS" count match the Library.
- **C5.** Every new control gets a hover label, per the owner's rule, and all controls must work one-handed on the phone.

### Phase D: proof

- **D1.** Add JVM tests to `tools/joyfolder-tests/JoyFolderTests.java` (run with `bash tools/joyfolder-tests/run.sh`) for the kind mapping and the index walk.
- **D2.** Phone check on the Note 9. Put one file of each kind in the folder, confirm it appears under the right chip, confirm search finds it, confirm each row opens, and confirm delete still goes to Trash.

### Owner decisions needed (plain-language questions)

1. Should project media copies (the copies the app makes inside the folder so projects survive an uninstall) appear in the Library? Recommended: yes, under a "Project media" chip that is off by default.
2. Should pictures and clips that were shared in or imported show in the Library? Recommended: yes.
3. Should each sprite sheet be its own row, or stay inside its project?
4. Should brush, paper, palette, and look presets be copied into the folder's Supplies, or stay inside the app?
5. Where should voiceovers go: the Recordings folder, or beside the project?

---

## 5. Caveats

- Read-only. Nothing was built, run, or tested. Behaviour is inferred from code and the spec.
- `tools/joyfolder-tests` was not run.
- Not every storage hit was read in full (see the end of section 3).
- The "no writer" findings come from grep over `app/src`, `studiokit/src`, and `joybrush-android/src`. A writer named with an unusual alias would have been missed.
