# BRUSH-IMPORT (JBX-18): bring Photoshop, Procreate, Krita and MyPaint brushes into JoyBrush

**Milestone:** M2  ·  **State:** 🟡  ·  **Owner:** JoyBrush screens lane (Claude Opus 5.5, `claude/joybrush-screens-20261113-1a6f0b`)  ·  **Checked:** 2026-11-13 against `e79a2e216`

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
  and not imported). After the fix, on the PC: **17 of 19 brushes convert**; the 2 left out are "Flat Point 22" and "Flat Point 11", Photoshop bristle brushes (a hair simulation JoyBrush has no equivalent for). Dropped for every brush: the pattern textures. **Test tablet 11-13 19:47** (the build with every fix below): "Import…" → picker → the set → "Added 17 brushes
  from "Battle_demo_brushes". 2 could not come across: Flat Point 22, Flat Point 11." → 17 files in the tablet's
  `Joy Creator/Supplies/Brushes/` (4 KB to 277 KB each, so embedding stays well under 1 MB) → the Imported shelf
  shows all 17 with their own tip pictures → "CLOD 1" paints its cloud tip. Left installed on the tablet (owner's
  wish). Two app closes found and fixed on the way: the drawer's stand-in and its real preview both asked for tip
  pictures they were not given (now the stand-in draws the disc, the preview is handed the pictures, and a row that
  still cannot draw stays empty instead of closing the app).
- **Note 9 11-13 20:24-20:37** (APK of `9ffc56639`): the same set imported through "Import…", same sentence, 17 files
  beside the MyPaint charcoal, all 18 on the Imported shelf with their own previews, no app close; after an app
  restart "CLOD 1" was still in hand and painted (the restart fix, seen). Left installed on the Note 9.
- **What the Photoshop importer drops for this set** (the importer's own per-brush notes, logged under
  `JoyBrushImport` on the Note 9 11-13; never shown to the artist): two bristle brushes refused; brush textures kept
  but not drawn (the textures are Photoshop patterns, not image files, and patterns are not imported); noise (6
  brushes) and wet edges (5) have no JoyBrush equivalent yet; wet and mix variance of the wet mixer (wtVr, mxVr, 5
  each), colour purity and colour variance; roundness dynamics (the tip keeps one fixed roundness); flip X/Y and the
  dual brush; tilt scale clamped to 1 and texture scale clamped to 64. Every unmapped setting is kept raw in the
  brush file (`extensions`), so a later JoyBrush can use it without importing again.
- **Krita: Val's Realistic Ink Pen, v1 and v2** (downloaded 11-13 by the JoyBrush command session after the owner's
  yes, from https://krita-artists.org/t/vals-realistic-ink-pen/144394, into `D:/JoyCreator-brush-samples/`, outside
  the repo, never committed). The forum post states no licence; each bundle's own `meta.xml` says **CC-BY-SA**, author
  "Val / echolessowl", dated 16/10/2025 and 22/11/2025. One preset per bundle. Findings on the PC (11-13):
  1. Every real bundle failed to parse: a CDATA section in the preset text was sliced after the read position had
     moved past it. **Fixed** in `KritaImport` (regression test `aPresetWithACdataSectionConverts`; core 2960 green).
  2. After that fix both are still refused: "its preset XML has no <paintop id=…>". Real Krita presets are
     `<Preset paintopid="paintbrush">` with about 200 `<param name=…>` entries (brush_definition as nested XML,
     Size/Opacity/Flow/Rotation sensors with curves, Scatter, Sharpness, CompositeOp, masking brush…). The importer
     reads a simplified layout of its own design and has **never read a real Krita file**. Owed: a reader for the real
     format (Pixel / "paintbrush" engine first: auto-brush mask, spacing, pressure curves, rotation, scatter, blend),
     tested against these two files. **Built 11-14** (`KritaRealPreset`, a75d17632): the real form is translated
     into the layout `KritaImport` already maps (engine id, auto-brush tip and ratio, diameter, spacing, every
     switched-on sensor with its inputs and curves; the rest kept raw). A 0..1 size curve now scales the diameter
     instead of being read as pixels. On the PC both bundles convert (1 brush each, nothing refused).
  3. **Note 9 11-14 00:04-00:07:** both imported through "Import…" → `!! Val's realistic ink pen.brush.json` and
     `… V2.brush.json` in `Supplies/Brushes`, both on the Imported shelf with fine-ink previews; left installed.
     What the importer drops (its own notes): the "fuzzy" (random) sensor has no JoyBrush input; about 190
     settings are kept raw and unused, among them **CompositeOp multiply** (the pen multiplies in Krita and
     paints normally here), Darken, Sharpness/Softness, the HSV sensors and the whole masking brush.
     **Test tablet 11-14 00:52-00:57** (main-line build 1d56a6cf4, after the owner unlocked it): both imported ("Added
     1 brush from "Vals_Realistic_Ink_Pen"."), 19 files in `Supplies/Brushes`, and the 17 Photoshop brushes are
     still on the Imported shelf after the update. Left installed. **Painting with V2:** a slow stroke draws a clean
     ink line; a fast stroke thins to almost nothing. The preset's size listens to pressure AND speed, and the
     speed input (a ChildSensor with no curve, read as a straight 0..1 line) does not behave as it does in Krita.
     Owed: check Krita's speed default and the speed scale against JoyBrush's speed input, then retest.
  - Found on the way: picking a picture by mistake says "None of the brushes in … could come across" (a PNG is
    tried as a Krita preset); a plainer "isn't a brush file" would be kinder. Small, not fixed yet.
- **Krita: Memileo Impasto and Metallics by Draneria** (both CC-BY-SA per the command session): need a browser
  download by the owner into the same folder; not here yet.
- Entry point moved 11-13: "Import…" sits under the shelf names (always in view); at the end of the All list it was
  out of reach on the tablet.

## What is left
1. [x] Self-contained brush file (`androidkit/brush/BrushFile`), round-trip tests. 11-13.
2. [x] `library/SuppliesBrushes`: read and write `Supplies/Brushes/*.brush.json` through the folder; reading creates
       nothing; name clashes get " 2". 11-13.
3. [x] Drawer "Import…" + picker + sentence; brushes merged into the list; tip pictures handed to the canvas and the
       previews. Tablet and Note 9, 11-13.
4. [ ] Packs: the same reader also lists `PackShelf.installed(fs, "brush")` under each pack's name (JBX-48, step 2 of
       the PACK-1 spec).
5. [~] Real Krita presets: reader built, Val's two bundles on the Note 9 and the tablet (11-14). Owed: the speed
       response (fast strokes vanish), Memileo Impasto and Metallics when the owner downloads them, a Procreate file.
6. [ ] Help mapping for the STATE row (Joybot HelpBook) with the Joybot lane.

## Specs, handoffs and evidence
- Audit row JBX-18 in `tasks/audits/remaining_work_20261113.md`; folder kinds `studiokit/.../joyfolder/FileKind.java`;
  pack spec `tasks/jobs/PACK-1_joypacks.md` ("Spec for the JoyBrush lead").

## Files and traps
- New small files only, plus hooks in `BrushDrawerView` (one row) and `JoyBrushActivity` (brush list, picker result).
  The brush-set session (`claude/brush-set-20261113-81446f`) touches media/Unibrush/bench files, none of these.
- The folder is a SAF tree: read and write through `JoyFs`, never `File` paths.
- Never show an importer's reason text to the artist; log it.
