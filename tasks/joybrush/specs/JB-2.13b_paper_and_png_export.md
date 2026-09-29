# JB-2.13b — Paper colour + Export PNG (what's on screen · the whole drawing · a board), "Include paper"

| | |
|---|---|
| **Tier** | T2 + T3 phone check |
| **Status** | see ROADMAP.md |
| **Depends on** | JB-2.13a (`RegionRenderer`), JB-2.14a (`PngWriter`), JB-0.08b (save/open, SAF) — Built; D.02 (colour picker, for the paper colour only) |
| **Owner area** | NEW `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/io/PngExport.kt`, NEW `.../androidkit/io/PngExportTest.kt` (JVM test, `src/test`), EDIT `joybrush-android/.../JoyBrushActivity.kt` (an "Export…" pill and a "Paper" pill) |
| **Estimated size** | ~220 lines + ~150 lines of tests |

## Goal
Blueprint §3: "Paper is a setting, not a layer … Export has an **Include paper** checkbox" and
"Export three ways: what's on screen · the selected objects · the whole board." This spec does
screen, whole drawing and board; "selected objects" arrives with selection (JB-2.05).

## Contract
```kotlin
package cc.joycreator.joybrush.androidkit.io

enum class ExportArea { SCREEN, DRAWING, BOARD }

object PngExport {
    /**
     * The document px rectangle to export. SCREEN = the view's visible area (axis-aligned bounds of
     * the rotated screen, in doc px, rounded outward). DRAWING = the union of every layer's
     * non-empty tiles, trimmed to the exact non-transparent pixels. BOARD = the board's rect.
     * Null = nothing to export (an empty drawing / no such board).
     */
    fun areaRect(area: ExportArea, doc: JbDocument, tiles: TileSource, screenDoc: RectPx?, boardId: String?): RectPx?
    /** PNG bytes of [rect]: RegionRenderer.render(…, paper = if (includePaper) doc.paper.color else null) → PngWriter. */
    fun png(doc: JbDocument, tiles: TileSource, rect: RectPx, includePaper: Boolean, scale: Int = 1): ByteArray
}
```

## Decisions
1. **Paper pill:** tap opens the Studio colour picker (D.02) titled "Paper"; the chosen colour goes
   to `JbCanvasView.paperArgb` and into the document (`doc.paper.color`, already saved by JB-0.08b).
   Paper TEXTURE is JB-1.05c's; not here.
2. **Export… pill:** a small dialog (plain `AlertDialog` is fine — the real chrome restyles it later):
   three choices *What's on screen* / *The whole drawing* / *This board* (greyed when the document
   has no active board), a checkbox **Include paper** (default = `doc.paper.includeInExport`, and
   remembered into the document when changed), a scale choice **1× / 2× / 4×**, then the system
   "Save as" (`ACTION_CREATE_DOCUMENT`, `image/png`, name `"<doc name> <yyyy-MM-dd HHmm>.png"`).
3. **Scale** multiplies pixel dimensions by nearest-neighbour upscaling of the rendered image (crisp
   pixel art; a painter who wants a smooth 2× paints at 2×). Refuse (`RegionException` → toast in
   words) if the scaled image exceeds `RegionRenderer.MAX_REGION_PX`.
4. DRAWING trims to the exact non-transparent bounds (a scan of the rendered alpha of the tile union)
   so the PNG has no empty margin. Empty drawing → toast "Nothing to export yet."
5. Rendering + encoding run on a background thread from tile bytes read on the GL thread (same rule
   as JB-2.06b). Writing to the Uri happens off the UI thread; failures say so in words.
6. Helpers (grids, guides) are never in an export — they are not tiles, so this is automatic; the
   test pins it anyway by asserting only layer pixels appear.

## Tests (`PngExportTest`, JVM, with an in-memory `TileSource`)
1. DRAWING of one 10 × 10 red square at (300, −20) → rect (300, −20, 10, 10) exactly; PNG decodes
   (ImageIO) to 10 × 10 red.
2. Include paper on a transparent area → the paper colour, opaque; off → alpha 0.
3. BOARD → the board's rect; unknown board → null.
4. Scale 2 → 20 × 20, each source pixel a 2 × 2 block; over-budget scale → `RegionException`.
5. Empty drawing → `areaRect(DRAWING)` = null.
6. A layer with `visible = false` is not in the export.

**Commands:** `./gradlew -p joybrush :androidkit:test` — 0 failures; watcher compiles
`joybrush-android` green.

## Owner check (Note 9)
Draw something, set paper to cream; Export → The whole drawing, include paper, 2× → the PNG in
Gallery is cream-backed, tightly cropped, twice the size. Export again without paper → transparent.

## Do not
No change to `RegionRenderer` or `PngWriter`. No new export formats here (PSD is 2.14c, GIF/MP4 3.06b).

## Definition of done
Tests + builds green (paste) · owner check noted · commit `JB-2.13b: paper and PNG export` ·
ROADMAP row → 🟧 Built.

## Questions
