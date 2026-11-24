# EXPORT-FORMATS audit, 2026-11-24 (read-only)

By Claude Opus 5.5, taken from the JoyBrush roadmap's unheld OPEN AUDIT row EXPORT-FORMATS. Source: `joy-creator` at `27af1dc90`. Nothing was built, run or installed. A read-only scout read the code; I re-checked the load-bearing claims (the `"wt"` opens at `JoyBrushActivity.kt:2929` and `BoardExportCoordinator.kt:196`, `exportBytes` passing no warning sink at `JoyBrushActivity.kt:3478-3488`, the success toast at `:2845`). Tween export internals are excluded (entry point only).

## What a person can export from JoyBrush today

Every export goes through Android's own file picker (create-a-file or choose-a-folder); nothing goes to the gallery, Downloads by itself, a share sheet or the Joy Creator folder. Send to Studio and Open in SpriteLab are hand-offs through the app's cache, not files the person keeps.

| Export | Where you start it | What it writes | Proof on a phone |
|---|---|---|---|
| PNG of one board | board corner icon, or Export… > This board > Flat picture > Choose a format | flat RGBA PNG, transparent unless "Include paper" | 10-01 Note 9: decoded pixel for pixel (fixed a readback bug); code changed since |
| PNG of the whole drawing ("Flat picture") | ⋯ > Export… > Whole drawing | flat PNG of the painted area | none |
| Layered file (OpenRaster .ora: Krita, GIMP, MyPaint) | ⋯ > Export… > Layered file | one PNG per layer + stack.xml + merged image + thumbnail; paper as a bottom layer when included | none; never seen opened in Krita/GIMP/MyPaint |
| All image boards | board sheet, scope "All image boards" | a NEW folder `<name> export <8 hex>` of numbered PNGs (never overwrites) | not phone-proven |
| GIF / PNG frames / this frame (Animation board) | board sheet | `<board>.gif` (loops, holds kept, at most 50 drawings a second, with a warning when some are dropped) or a new folder of `0001.png...` + a timing file | 10-06 Note 9: GIF decoded (7 frames, the right delays) and a PNG-frames range; GIF timing changed 11-15 (cloud-tested only) |
| Sprite sheet + `.sprite.json` | board sheet (Animation or Sprite board) | a new folder holding the sheet and its grid file | 10-06/07 Note 9 in Android Files |
| Play all (scene) | Play all export | `Play all.gif` or a new folder of frames + `timing.csv`, capped at 600 frames | never |
| Send to Studio / Open in SpriteLab | Export… > Whole drawing > Send to Studio; board sheet chips | staged in cache, handed to Studio's import (a new project or a layer), staging deleted after | 10-07 Note 9 (SpriteLab, and an animation into an MP4 from Studio); FX-1 picture "seen on the Fold" 11-16 |
| PSD, SVG, MP4/WebM, animated WebP as files | not offered | PSD not built; SVG core exists (`core/vector/svg`) with no screen (JBX-19); MP4 comes from Studio by design | n/a |
| Tween boards | same sheet | refused: "board "<id>" uses tween exposures; its export needs the live tween renderer" (no renderer is passed from the screen) | n/a |

Every export class has changed since its phone receipt except `PngWriter`; nothing newer than 11-16 is device-proven. Encoders are well tested (CanvasPng, ExportPicture, OraExport 43, AnimExport 31, BoardExport 7, SpritePacker 31, GIF, scene, layout and coordinator tests); the activity glue that a person actually drives (`exportMenu`, `exportBytes`, `onActivityResult`, the toasts) has no test.

## The 10-12 SVG handoff against today
Built as promised: the pure SVG core (outlines, dashes and taper through `LineStyle`, deterministic XML, a refusal for asset-rendered watercolour instead of a flattened copy) with its tests, and "no SVG export UI" is still true. Not built: the GPU picture adapter the handoff suggested for watercolour lines.

## Risks, most serious first
1. **A single-file export writes in place, not through a temporary file** (`openOutputStream(uri, "wt")`, `JoyBrushActivity.kt:2929`, `BoardExportCoordinator.kt:196`). The picture is encoded in memory first, so the window is short, but if the write dies part-way the file at the chosen place is left cut off; and if the person picks an existing file in the picker, it is replaced. (A new name in the picker is a new file: the overwrite needs the person to choose the old one.)
2. **Warnings are dropped for the flat picture, the layered file and the whole-drawing Studio hand-off.** `exportBytes` passes no warning sink, so "Paper <file> could not be loaded; exported the flat base colour." never reaches the person there, while the board sheet does show it.
3. **The layered file leaves out hidden layers, 0 % layers and layers with no drawing on that frame, and says nothing** (`OraExport.kt:430-437`; they are named only inside `stack.xml`), while the row promises "Every layer kept separate" and the toast says "Layered file exported".
4. Success is announced after the write call returns, with no read-back check.
5. Some refusals show internal ids ("board "<id>" ..."), and the tween refusal names the renderer.
6. The Studio side of the hand-off (`JoyBrushBoardImportActivity`, `BoardAttachPlan`) has no test.
7. The OpenRaster code comment says ink layers are left out; the code now renders them (stale comment; R51 lists the ink gaps for animation and sprite exports).
Files: the export classes (`io/*`, `board/BoardExport*`) are not claimed on BOARD; `JoyBrushActivity.kt` is shared with Codex's save work and the atlas-solid save path. Fixes 1 to 3 are small and outside the engine, but touch `JoyBrushActivity`: ask Codex and the JoyBrush lead first.

## Owed on a phone (phone lane)
Export the whole drawing as a flat picture and as a layered file; open the `.ora` in a real program (Krita or GIMP on the PC is enough) and check every visible layer is there; export a GIF after the 11-15 timing change and check the delays; run a Play all export once.

## Fixed the same day (2026-11-24, approved by the lead session with the JoyBrush lead offline)
Risks 1 to 3 are fixed outside the paint engine, touching only the export lines of `JoyBrushActivity.kt` (`SaveDest.Png`, its branch in the save worker, `exportBytes` and its success toast), not the save and restore lifecycle Codex holds.
1. **A write that does not arrive whole is never left looking like a good file.** `board/ExportWrite` writes, reads back the size the place holds, and when it is short or the write died part-way removes the cut-off file (where the folder allows) and says so in plain words; if it cannot be removed, it tells the person to delete it. `ExportWriteTest` 6 checks.
2. **The flat picture, the layered file and the whole-drawing Send to Studio now show their warnings** (a paper texture that would not load), as the board sheet always did: `exportBytes` passes a warning sink and the success toast carries them.
3. **The layered file names the layers it left out** ("2 layers were left out of the layered file: "Sketch" (it is hidden); "Wash" (it is 0% opaque)"), through `OraExport`'s own warning receiver: `OraExportTest.leftOutLayersAreSaidToThePersonNotOnlyWrittenInTheStack` (fails on the old code).
Proof: cloud build 45422934066 green; androidkit 1380 tests and the Android suite 612 tests pass on `eaee17345` (the Android suite's first run stopped on a Gradle configuration-cache error before any test ran; the re-run passed). Not seen on a phone: export a layered file with a hidden layer and read the message; the cut-off case needs a full phone or a pulled card.
