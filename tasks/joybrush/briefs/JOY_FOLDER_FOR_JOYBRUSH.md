# Brief — JoyBrush drawings into the Joy Creator folder

**For:** the JoyBrush feature lane (the JoyBrush-features Claude account) — and the engine lane (Codex) only for §3.
**From:** the cross-cutting Claude account, which built the folder (2026-10-13). **Owner's words:** "its important that if someone
uninstalls JoyCreator the projects are not lost", and "Drawings definitely need to get thumbnails. And currently,
I don't think there's any way to load different drawings in from the lobby."

The full design is `tasks/SPEC_20261013_FILE_MANAGEMENT.md` §3 and §3.6 ("Folders you earn"); the owner's picks are
on the page `tasks/design/Joy-Creator-folder-options.html`. Read §3.6 first: it is short.

## 0. STATUS 2026-10-14 — done by the cross-cutting account while the JoyBrush lanes were on cooldown (owner asked)

Built and on the Note 9 (commit "JoyBrush: many drawings, thumbnails…"):
- `joybrush-android/.../library/DrawingLibrary.kt` — list (library.json beside the working copies), create, rename,
  delete-to-Trash, thumbnails (`thumbs/<file>.png`, rendered a row at a time), the folder copy `<Name>.joybrush`
  (found again by doc id if moved), restore from the folder (`syncFromFolder`).
- `JoyBrushActivity`: **two hooks only** — `workingFile()` asks `DrawingLibrary.fileToOpen(intent)` (fixed per run);
  after a working save, `DrawingLibrary.onSaved(...)`. Nothing else in saving/recovery/history changed.
- `library/DrawingsActivity.kt` (gallery) and the Lobby's Joy Brush room (hero = last drawing, row = drawings).

Owed to the JoyBrush lanes (not touched):
1. **"Open…" still replaces the current drawing's content** in its working file. With a library it should probably
   import as a NEW drawing (`DrawingLibrary.create` then open). Your call.
2. **The owner's `current.joybrush` (10-12, media layers) can't be read by this branch's `JbArchive`**
   ("1_3.g is not a tx_ty tile key"): the media-tile reader isn't merged here yet. The drawing screen correctly
   refuses to overwrite it. Thumbnails for it will appear once the reader lands.
3. Thumbnails use the paper's flat `color`; a textured look (kraft) shows white. Use `CanvasPng.paperRendererFor` if
   you want the look.
3b. **Thumbnail framing — owner asked, cross-cutting account recommends (2026-10-14):** like Concepts, the picture
   is **the last view the person was looking at, clipped to the board** (that's what they recognise; a whole huge
   canvas is a smudge). Fall back to the whole board when that view is mostly empty paper; for an animation board, the
   frame they were on; later, "Use this view as the cover". Needs one hook from the canvas: pass the current view
   rectangle (document coordinates) to `DrawingLibrary.onSaved`, and render that rect in `thumbnailOf` instead of
   `board.rect`. Today it renders the whole active board.
4. A drawing's `doc.name` isn't updated on rename (the library's name is). Decide whether the document name follows.
5. §3 below (autosave during continuous drawing) is still open.

## 1. What already exists (use it, don't copy it)

`studiokit` (JoyBrush already depends on it) has the folder, in plain Java:

| Class | Package | What it gives you |
|---|---|---|
| `JoyFolderHome` | `com.fadcam.joyfolder.android` | `isChosen`, `isReachable`, `open(ctx)` → a ready `JoyFolder` (call on `JoyFolderHome.io()`, the one folder thread), `fsOf(jf)` for content URIs |
| `JoyFolder` | `com.fadcam.joyfolder` | `writeAtomic(rel, bytes)` (crash-safe), `prepareDir(What, workRel, browsingDir, kind)`, `freeName(dir, name)`, `addFile(...)`, `trash(rel, now)` / `restore` |
| `Placement` | same | the three rules: where a new drawing, an export, a share-in goes |
| `FileKind` | same | `.joybrush` is `DRAWING`, a WORK kind: it sits at the top of the folder |
| `ReadableNames` | same | sanitize a drawing's name for any file system; "Dino walk 2.joybrush" on clashes |

The Studio half (`app/.../joyfolder/studio/StudioMirror.java`) is the worked example: the app keeps its fast working
copy, and a few seconds after each save the work is written to the folder by name. Restore after a reinstall reads it
back.

## 2. What JoyBrush needs (feature lane)

1. **Write each drawing to the folder after its save** as `<Drawing name>.joybrush` (the same archive bytes), with
   `JoyFolder.writeAtomic` on `JoyFolderHome.io()`. New drawings go where `Placement.dirFor(NEW_WORK, …)` says (the top,
   or the folder the gallery is showing). Remember drawing id → folder path, so a rename in the app renames the file,
   and a file the user moved is found again (see `StudioMirror.placeProject` / `findById`).
2. **Multiple drawings.** "New drawing" makes a new document instead of reopening `current.joybrush`. The lobby lists
   and opens any drawing (owner: the lobby must be fully polished before launch).
3. **Thumbnails.** Write `thumbnail.png` into every saved archive (today `thumbnailPng` is always null,
   `JbCanvasView.kt:1833`). The gallery and the lobby read it from the archive, so no separate thumbnail file is needed.
4. **Restore.** After a reinstall, `.joybrush` files in the folder appear in the gallery and open normally.
5. **Exports** go through `Placement.dirFor(EXPORT, drawingRel, …)`: beside the drawing, or into Exports if the user
   has an Exports folder. Readable names, e.g. "Dino walk.gif".
6. **Supplies.** User brushes, papers and palettes live in `Supplies/<Kind>/` as `Soft pencil.brush.json` plus
   same-name pictures beside it (`Soft pencil.grain.png`); a set is a folder; one folder per brush is also accepted.
   Plugins are a folder each. The importers already built (`core/.../brush/imports`) write here.

## 3. Engine lane (Codex), one item

Autosave only fires after 30 s with no drawing (`JoyBrushActivity.kt:118`, a debounce), so continuous drawing is never
saved, and an install or force-stop (no `onPause`) loses the whole session. Owner rule: "Future saving and undo must
remain dependable." Ask: also save at a stroke's end when ≥ 60 s have passed since the last save.

## 4. Don'ts

- Nothing the user would miss goes in app-private storage or `Android/data`; only rebuildable caches.
- No UUIDs or codes in file names.
- Never `adb uninstall` to test restore. Push a copy of a `.joybrush` with a new id into the folder instead.
