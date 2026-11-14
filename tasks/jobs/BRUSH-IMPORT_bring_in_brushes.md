# BRUSH-IMPORT (JBX-18): bring Photoshop, Procreate, Krita and MyPaint brushes into JoyBrush

**Milestone:** M2  ·  **State:** 🔨  ·  **Owner:** JoyBrush screens lane (Claude Opus 5.5, `claude/joybrush-screens-20261113-1a6f0b`)  ·  **Checked:** 2026-11-13 against `02c782894`

## Why it matters (for the user)
An artist arrives with brushes they already love. Today JoyBrush can convert four kinds of brush file, but there is no
button for it, so those converters are useless. The owner asked about it on 11-13.

## Done when
- [ ] Brush drawer → last row **"Import brushes…"** opens the phone's file picker (any file; the type is worked out).
- [ ] The file converts off the screen thread. One plain sentence says how many brushes came in and names the few that
      could not come across, never a technical reason ("Added 12 brushes from \"Kyle's Inks\". 2 could not come across: …").
- [ ] Each brush is written to the person's own folder as **`Supplies/Brushes/<Name>.brush.json`**. That is the place the
      folder design names (`FileKind.BRUSH`), and packs will read the same place (PACK-1 spec, JBX-48). One file per
      brush, tip pictures inside it, so a brush can be copied, shared or hand-edited as one file and survives an uninstall.
- [ ] The drawer shows them on the **Imported** shelf (existing `BrushShelf.Kind.IMPORTED`); they paint, hold opens
      their settings, and a drawing that used one keeps drawing if the file is deleted (the drawing already freezes its
      brushes, `FrozenBrushRegistry`).
- [ ] Unit tests: format detection, conversion sentence, file round trip (recipe + pictures), shelf reading. Real
      sample files of each kind kept under `tasks/joybrush/evidence/brush-import/` with what came across, and a Note 9 run.
- [ ] STATE row (benefit, entry point, proof, help mapping) and the JoyBrush roadmap row.

## Where it stands
- Converters exist and are tested: `core/brush/imports/*` (AbrImport, ProcreateImport, KritaImport, MypaintImport) and
  `androidkit/MaterializedBrushImports` (tip pictures as real PNGs). Four ABR real-file tests are skipped: no files here.
- 11-13: `core/brush/imports/BrushFileKind` (which converter a picked file goes to, by name, then by its first bytes)
  and `androidkit/BrushFileImport` (runs the converter, makes the sentence) are written. **Not built yet**, not landed.
- No reader or writer for `Supplies/Brushes` in JoyBrush yet (PACK-1 line 99).
- 11-13 evening, branch `claude/joybrush-screens-brush-import-20261113` (not landed): steps 1-3 built. JoyBrush
  suites green (core 2950, androidkit 1340, Android 583). Note 9 run (13:59-14:10, build before the restart fix):
  "Import brushes…" → picker → a CC0 MyPaint `charcoal.myb` (the fixture the MyPaint tests already hold) →
  `Joy Creator/Supplies/Brushes/Charcoal (MyPaint CC0).brush.json` (6 KB) → Imported shelf → painted with its grain.
  Found there: after an app restart the tool fell back to Ink (the folder loads later); fixed (the saved tools are
  kept until the folder's brushes are in, then restored), unit-tested, not yet re-run on a device.

## Format (decided 11-13)
One shape for shipped, Unibrush, pack and imported brushes: the ordinary brush recipe (`BrushJson`, the same fields
every `brush.json` in `joybrush/brushes/` and a pack's `.brush.json` carry), readable and first in the file. Tip
pictures, when a brush has any, come last as one `"assets"` object (path → base64 PNG, the paths the recipe already
names). A recipe-only file is exactly an ordinary brush file. **Size:** the MyPaint charcoal is 6 KB (no pictures).
Photoshop tips can be large; sizes of the owner's set go here once it imports. If a brush file passes about 1 MB
the choice between embedding and a side picture file goes back to the lead, not silently either way.

## Real files (11-13)
- **Owner's Photoshop set** (`Battle_demo_brushes.abr`, 35 MB, format version 9; copied read-only from his asset
  folder to a scratch folder outside the repo; never committed). First run on the PC: the whole file was refused
  because its pattern section (33.9 MB of textures) was over the 32 MB per-section cap, although nothing reads that
  section. Fixed in `AbrReader` (the cap now guards only the parsed tip and brush sections; patterns are still skipped
  and not imported). After the fix, on the PC: **17 of 19 brushes convert**; the 2 left out are "Flat Point 22" and "Flat Point 11", Photoshop bristle brushes (a hair simulation JoyBrush has no equivalent for). Dropped for every brush: the pattern textures. Device run and file sizes still owed.
- **Krita packs** (owner said yes, three named threads on krita-artists.org): not downloaded yet; asked the owner in
  this session to confirm the downloads (files, source) first.

## What is left
1. [ ] Self-contained brush file: `BrushFile` (androidkit) = `BrushJson` recipe plus an `"assets"` object (path → PNG,
       base64), read back through `FrozenBrushPngs` with its existing size limits. Round-trip tests.
2. [ ] `SuppliesBrushes` (joybrush-android/library): list, read and write `Supplies/Brushes/*.brush.json` through the
       studiokit folder (`JoyFs`, SAF tree; never by path), on the folder thread. Name clashes get " 2", " 3".
3. [ ] Drawer row + picker + toast; the activity adds the shelf's brushes to its list and gives the canvas their
       pictures (the `brushPngs` lookup the Unibrush store already feeds).
4. [ ] Packs: the same reader also lists `PackShelf.installed(fs, "brush")` under each pack's name (JBX-48, step 2 of
       the PACK-1 spec), so packs come almost for free.
5. [ ] Sample files (MyPaint and Krita brushes are freely licensed; Photoshop and Procreate samples need the owner's
       own files or freely licensed ones), Note 9 run, STATE + roadmap rows.

## Specs, handoffs and evidence
- Audit row JBX-18 in `tasks/audits/remaining_work_20261113.md`; folder kinds `studiokit/.../joyfolder/FileKind.java`;
  pack spec `tasks/jobs/PACK-1_joypacks.md` ("Spec for the JoyBrush lead").

## Files and traps
- New small files only, plus hooks in `BrushDrawerView` (one row) and `JoyBrushActivity` (brush list, picker result).
  The brush-set session (`claude/brush-set-20261113-81446f`) touches media/Unibrush/bench files, none of these.
- The folder is a SAF tree: read and write through `JoyFs`, never `File` paths.
- Never show an importer's reason text to the artist; log it.
