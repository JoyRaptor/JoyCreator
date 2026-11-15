# FREE-2: Names: app ID, packages, phone folders, watermark, translations

**Milestone:** M3  ·  **State:** ⛔ waiting on S1 (the app ID)  ·  **Owner:** project lead  ·  **Checked:** 2026-10-27

## Why it matters
The app ID is permanent once published: changing it later loses every user. The Joy Creator folder is the
bridge: a new app ID restores everything from the folder, which is why the folder had to ship first (it has).

## Done when
- [ ] The release app ID is the owner's pick (recommended `cc.joycreator.app`, matching JoyBrush's `cc.joycreator.joybrush`).
- [ ] Moving from the current debug ID (`com.fadcam.beta`) is proven on the Note 9: install the new ID alongside, restore from the folder, nothing lost.
- [ ] No user-visible "FadCam"/"FadRec": watermark, translations, folder names on the phone (`Downloads/FadCam`, `Pictures/FadCam/assets`, the "Faditor_" file prefix).
- [ ] Java package renames (`com.fadcam.*`): decide with [FREE-1](FREE-1_fadcam_inventory.md) whether to rename or let rebuilt code take new packages.

## What is left
- [ ] S1 from the owner.
- [ ] Plan the folder-name change on the phone so old files are still found.
- [ ] Watermark text and translations (also in [UI-1](UI-1_app_ui_leftovers.md)).

## Design: FadCam names on the phone, together with the app ID (SHELL-44 / L16; Play build lane, 2026-11-14)

Inventory (every writer and reader, file:line): `tasks/launch/FADCAM_NAMES_INVENTORY_20261114.md`.

**Who has FadCam-named files at all.** A Play install is a new app (`cc.joycreator.app`) that must choose its Joy
Creator folder first; recordings there already get readable names. It meets FadCam names only through a handful of
writers that ignore the folder (below). Old FadCam-named files exist only for people who used the side-loaded
build (`com.fadcam` / `com.fadcam.beta`): today the owner. Android's scoped storage lets the Play app read the
side-load build's videos but not rename, move or even list its other files, and the side-load build's
`Android/data/<package>/` folder is deleted with it. **So moving old files is the side-load build's job; the Joy
Creator folder is the bridge to the Play app** (the rule FREE-2 already set).

**1. New names from now on (both builds).** Every writer to storage a person can see uses Joy Creator names:

| Was | Becomes |
|---|---|
| `Download/FadCam/` (debug log, "save to gallery", Forensics copy, Watch save, Trash "restore to Downloads") | `Download/Joy Creator/` |
| `Pictures/FadCam/assets/` (AI B-roll, fonts) | `Pictures/Joy Creator/assets/` |
| `Documents/FadRec/Projects/FadRec_<ts>/project.fadrec` (screen-drawing projects) | `Documents/Joy Creator/Screen drawings/Screen drawing <date time>/project.fadrec` |
| fallback recordings when the folder cannot be reached, internal mode: `FadCam_`, `FadRec_`, `FadShot_`, `Faditor_Std_`, `Faditor_Merge_`, export `Faditor_<ts>` | the readable names the folder already uses (`Camera 2026-11-14 18.43.mp4`, `Screen recording …`, `Photo …`, `Converted …`, `Merged …`, export `Joy Creator <date time>`), in `Android/data/<package>/files/Joy Creator/` |

**2. Old names are read forever.** Every scanner, classifier and parser keeps the old prefixes and folders and adds
the new ones (additive only; nothing stops recognising a FadCam name). The inside-the-folder scan also accepts
legacy `FadCam_*` names (today it skips them).

**3. Moving existing files (side-load build only, once, then never again).**
- Shared folders the app made (`Download/FadCam`, `Pictures/FadCam/assets`, `Documents/FadRec/Projects`): each file
  is moved with MediaStore where the app owns it (a rename on the same disk: the file's content address stays the
  same, so anything holding that address keeps working), else copy → verify size and SHA-256 → re-point every
  project that names the old path (`StudioMirror.repointOthers`, the Move tool's code) → delete the original. A
  half-copied file is written as `*.partial` and removed on the next start; a file already at the destination with
  the same size and hash only has its original removed. Safe to kill at any moment, safe to run twice. The old
  folder is removed only when empty. Nothing is overwritten: a name clash gets " 2".
- Recordings inside the side-load build's own `Android/data` folder: already handled by the folder's existing
  "Bring in" tool (Copy · Move · Leave, app recordings default to Move, re-points projects, undo ledger). No second
  mover is built.

**4. Moving to the Play app (the app ID change).** In the side-load build: "Save all projects into the folder now"
plus Bring in. Then install the Play app, choose "I already have one" and pick the same folder. Proof owed on the
Note 9 against a COPY of its sandbox Joy Creator folder (made with `cp -r` on the phone), so the debug app's real
folder is never written by two apps.

**Order:** 1 and 2 first (no file moves, no risk to old work); then 3, phone-tested on the Note 9 sandbox; then 4.

**Progress 11-14:** 1 and 2 are on joy-creator (commit ad19f0570 and its landing): the writers in section A of the
inventory that make shared folders (Download, Pictures, Documents) and the export fallback name use Joy Creator
names; the Library also lists FadCam-named recordings inside the Joy Creator folder; strings in 13 languages.
Cloud debug build installed over the Note 9 sandbox app: Library 259 items before and after, old folders untouched.
**Not yet renamed (row 4 of the table):** the fallback and internal-mode recording names (`FadCam_`, `FadRec_`,
`FadShot_`, `Faditor_Std_/Merge_`, `AppData/FadCam/...`), the QR scanner and Forensics snapshot names; these sit in
the recording pipeline and go with step 3's tests. Screenshots: `tasks/screens/shell44-20261114/`.

### Step 3 brief (for whoever builds it; read the conditions above first)

- **What moves (side-load build only, `!BuildConfig.PLAY_BUILD`):** `Download/FadCam/*` → `Download/Joy Creator/`;
  `Pictures/FadCam/assets/**` (fonts, `.broll_tags.json`) → `Pictures/Joy Creator/assets/` (then `BRollBucket` stops
  preferring the old folder by itself: it only does so while the new one does not exist); `Documents/FadRec/Projects/*/`
  → `Documents/Joy Creator/Screen drawings/` (`ProjectFileManager` already finds a project in either place). The
  Note 9 sandbox today: one video in `Download/FadCam`, `Pictures/FadCam/assets/fonts`, an empty `Documents/FadRec/Projects`.
- **Reuse, do not rebuild:** `StudioMirror.rememberMoved(old→new)` (every project load then heals links to the old
  file through `healMovedOriginals`), `StudioMirror.repointOthers(moved, null)` (rewrites saved projects now),
  `OriginalsRemover` (deletes originals, MediaStore delete where needed). The move log goes in the Joy Creator folder
  (`.joycreator/` is the folder's hidden home; a readable `Moved from FadCam folders.txt` at its top is easier to
  find by hand: owner taste, pick one and say which).
- **Phase 1 (one launch):** per file: copy to `<name>.partial` beside the destination, compare size and SHA-256 with
  the original, rename to the final name (clash → " 2"), `rememberMoved` + `repointOthers`, log line `copied`.
  Phase 2 (a later launch): for each `copied` line, re-hash the copy; delete the original only if it matches and no
  saved project still names the old path (scan `ProjectStorage` JSON for it), log `removed`. Any failure: log
  `kept`, leave both, say nothing to the person. Leftover `*.partial` files are removed at the start of phase 1.
- **Dry run:** a debug-only entry (an `adb shell am start` extra, like `DecoderBudgetProbeActivity`) that writes the
  plan to the log as `would-move` lines and changes nothing. Test against a copy made on the phone
  (`cp -r /sdcard/Download/FadCam /sdcard/Download/FadCam-testcopy` etc.), never the Note 20.
- **Tests:** a JVM test like `tools/joyfolder-tests` for the planner and the kill-midway cases (partial copy,
  copy done but not logged, logged but original still there, run twice).

**Orchestrator's conditions for 3 (2026-11-14, binding):** (a) two phases: copy, verify and repoint on one launch;
the old files are deleted only on a LATER launch, after every repointed project has opened and found its media; a
crash between phases leaves both copies, never neither. (b) A readable move log in the Joy Creator folder, one line
per file: old path, new path, hash, state, so any file can be found or put back by hand. (c) A dry run first that
lists what it would move and changes nothing; test only against a copy on the Note 9, never the Note 20. (d) Any
failed check stops and keeps the old file; no technical error is ever shown to the person.
Translations: the 13 locale files say `Downloads/FadCam` too; they change with 1.
