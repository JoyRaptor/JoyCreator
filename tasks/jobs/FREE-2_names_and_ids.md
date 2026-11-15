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
Translations: the 13 locale files say `Downloads/FadCam` too; they change with 1.
