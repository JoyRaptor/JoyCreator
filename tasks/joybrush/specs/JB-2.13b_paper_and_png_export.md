# JB-2.13b — Paper colour + Export PNG (what's on screen · the whole drawing · a board), "Include paper"

| | |
|---|---|
| **Tier** | T2 + T3 phone check |
| **Status** | 🟦 Ready — filled out to the template by the cross-reviewer (2026-09-29). **One dispatch warning:** the owner area edits `JoyBrushActivity.kt`, an app file, and **four rows want it** (this one, JB-2.15, JB-3.06b, and JB-0.10's Q2). Nothing inside this spec is a design question. |
| **Who** | spec writer (unattributed in the original) · **xr: openrouter/stealth/space-bunny-alpha 2026-09-29** — this was the thinnest spec of the eight: it had **no stop rule, an empty Questions section, six Decisions, no verification that `doc.paper.color` / `doc.paper.includeInExport` exist (they do), and a "Do not" that was one sentence.** Filled all of it in, pasted the landed types verbatim (`Paper`, `JbDocument.paper`, `RegionRenderer.render`, `PngWriter.encode`, `ViewTransform`), named who computes the SCREEN rect, added the failure words, the cancellation rule, the paper-write-back rule and the stop rule, and expanded the Definition of done. Verified true: `Paper.color` defaults `#FFFFFF` and `includeInExport` defaults `false` (`DocModel.kt:38-43`); `PngWriter.encode(width, height, rgba, compressionLevel = 6): ByteArray` (`PngWriter.kt:85`); `RegionRenderer.render` returns **straight** RGBA8 row-0-top, which is exactly what `PngWriter` takes; `JbCanvasView.paperArgb: Int` exists (`JbCanvasView.kt:94`); `spacing`-style range rules are in `BrushValidate`, not here. |
| **Depends on** | JB-2.13a (`RegionRenderer`) 🟧, JB-2.14a (`PngWriter`) 🟧, JB-0.08b (save/open, SAF + the atomic write) 🟧 — **not** D.02. D.02 is `⚪ Outline` and Lead-owned, so this row must not wait for it: Decision 1 below uses the **existing** colour picker if one is reachable and otherwise a plain `AlertDialog` colour field, and the rule for which is written down. |
| **Owner area** | NEW `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/io/PngExport.kt`, NEW `joybrush/androidkit/src/test/kotlin/cc/joycreator/joybrush/androidkit/io/PngExportTest.kt`, EDIT `joybrush-android/src/main/kotlin/cc/joycreator/joybrush/android/JoyBrushActivity.kt` (an "Export…" pill and a "Paper" pill — nothing else in that file). **🔴 that last path is an APP FILE — see "Do not".** |
| **Estimated size** | ~220 lines + ~150 lines of tests + ~120 lines of wiring |

## Goal
Blueprint §3: "Paper is a setting, not a layer … Export has an **Include paper** checkbox" and
"Export three ways: what's on screen · the selected objects · the whole board." This spec does
screen, whole drawing and board; "selected objects" arrives with selection (JB-2.05).

## Contract
```kotlin
package cc.joycreator.joybrush.androidkit.io

import cc.joycreator.joybrush.core.doc.JbDocument
import cc.joycreator.joybrush.core.doc.RectPx
import cc.joycreator.joybrush.core.render.TileSource

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

### The landed types this contract leans on — pasted, not "see the other file"

```kotlin
// cc.joycreator.joybrush.core.doc.DocModel.kt:38 — the paper setting, verified in the tree
@Serializable data class Paper(
    val color: String = "#FFFFFF",          // #RRGGBB — the SAME format RegionRenderer parses
    val textureId: String? = null,          // a grain asset id, null = plain
    val textureScale: Float = 1f,
    val includeInExport: Boolean = false,   // the "Include paper" checkbox default
)
@Serializable data class RectPx(val x: Int, val y: Int, val w: Int, val h: Int)

// DocModel.kt:152
@Serializable data class JbDocument(
    val format: String = DOC_FORMAT,
    val version: Int = DOC_VERSION,
    val id: String,
    val name: String,
    val paper: Paper = Paper(),
    val boards: List<Board>,
    val layers: List<Layer>,                // bottom -> top   (DocModel.kt:159)
    val activeLayerId: String? = null,
    val activeBoardId: String? = null,
)

// cc.joycreator.joybrush.core.render.RegionRenderer.kt:24
fun interface TileSource { fun tile(layerId: String, celId: String, tx: Int, ty: Int): ByteArray? }

// cc.joycreator.joybrush.core.render.RegionRenderer.kt:196 — the ONE renderer
fun render(doc: JbDocument, tiles: TileSource, rect: RectPx, frameId: String?,
           paper: String?): ByteArray
//  -> STRAIGHT (un-premultiplied) RGBA8, rect.w * rect.h * 4 bytes, row 0 = top
//  -> `paper` is "#RRGGBB" or null. Anything else is IllegalArgumentException.
//  -> throws RegionException above MAX_REGION_PX = 8_388_608
//  -> a side of 0 returns an EMPTY image rather than throwing; a NEGATIVE side throws.

// cc.joycreator.joybrush.androidkit.io.PngWriter.kt:85 — the ONE writer
fun encode(width: Int, height: Int, rgba: ByteArray, compressionLevel: Int = 6): ByteArray

// cc.joycreator.joybrush.androidkit.JbCanvasView.kt:94
@Volatile var paperArgb: Int = 0xFFFFFFFF.toInt()
```

**The two documents' byte orders are the same, which is the reason this row is short.** `render`
returns *straight* RGBA8 row-0-top and `encode` takes exactly that, so there is no un-premultiply and
no row flip in this file. **Do not add one.** A builder who "fixes" the alpha here produces the
classic double-un-premultiply that turns a soft edge into a halo.

**Who computes `screenDoc`.** Not this object. The caller — `JoyBrushActivity`, on the UI thread, at
the moment the person taps — computes the axis-aligned bounds of the rotated screen in document px
and passes them in, using `ViewTransform`'s own inverse mapping. `areaRect` **does not** look at a
view, a display or a `Matrix`, because it lives in `androidkit` and must stay testable with a map for
tiles and nothing else. `screenDoc = null` with `area == SCREEN` returns `null` (nothing to export).

## Decisions
1. **Paper pill:** tap opens a colour picker titled "Paper" — **which one is decided by Decision 11**,
   and it must not be D.02's, because D.02 is `⚪ Outline`. The chosen colour goes to
   `JbCanvasView.paperArgb` **and** into the document (`doc.paper.color`, written exactly as Decision
   9 says), so it survives a save and a reopen. Paper TEXTURE is JB-1.05c's; not here, and
   `textureId` is never touched (Decision 10).
2. **Export… pill:** a small dialog (plain `AlertDialog` is fine — the real chrome restyles it later):
   three choices *What's on screen* / *The whole drawing* / *This board* (greyed when the document
   has no active board), a checkbox **Include paper** (default = `doc.paper.includeInExport`, and
   remembered into the document when changed), a scale choice **1× / 2× / 4×**, then the system
   "Save as" (`ACTION_CREATE_DOCUMENT`, `image/png`, name `"<doc name> <yyyy-MM-dd HHmm>.png"`).
3. **Scale** multiplies pixel dimensions by nearest-neighbour upscaling of the rendered image (crisp
   pixel art; a painter who wants a smooth 2× paints at 2×). The scale is checked **before the
   render**, in `Long` arithmetic, and refused with the toast **"That export would be
   <w>×<h> pixels; the most is <MAX_REGION_PX pixels>."** — thrown as `RegionException` and caught by
   the dialog, never discovered halfway through an encode. `scale` outside `{1, 2, 4}` is refused by
   name (Test 8).
4. DRAWING trims to the exact non-transparent bounds (a scan of the rendered alpha of the tile union)
   so the PNG has no empty margin. **The scan is over the RENDERED alpha, including paper** — an export
   that includes paper is never "empty", because every pixel of it has alpha 255 — so an
   include-paper export of a blank board is the board's full extent, and a blank board with no paper
   gives the toast **"Nothing to export yet."** The scan bounds are computed in `Int` off `RectPx`
   values and the running min/max are `Int`, never `Short`.
5. Rendering + encoding run on a background thread from tile bytes read on the GL thread (same rule
   as JB-2.06b). Writing to the Uri happens off the UI thread; failures say so in words, and the
   words are fixed so a test and a screen agree: **"Couldn't write the file. Your drawing is safe."**
   on a write failure, and **"Couldn't save: <the exception's message>."** when the render or the
   encode throws first. The second is R11's exact shape — the drawing is safe because nothing about
   it was touched, and the toast says so rather than leaving a person wondering.
6. Helpers (grids, guides) are never in an export — they are not tiles, so this is automatic; the
   test pins it anyway by asserting only layer pixels appear.
7. **The whole PNG is built in memory and then written once.** `PngExport.png` returns a `ByteArray`,
   so the SAF `ACTION_CREATE_DOCUMENT` `OutputStream` is opened, written, and closed **once**, after
   the encode has succeeded. Never stream incrementally into a SAF Uri: it has no rename and no
   atomic replace, so a failure at 80 % leaves a file at a name the person chose that is not a PNG.
   (This is R11's rule, verbatim — the same one JB-2.15 and JB-3.06b obey.)
8. **A cancelled save dialog is a cancellation, not a failure.** If the person backs out of
   `ACTION_CREATE_DOCUMENT` (result `RESULT_CANCELED`, or no Uri returned), **no toast** and no file.
   Never leave a zero-byte file at the name they were offered.
9. **The paper colour is written back to the document, and the checkbox is too.** Tapping the Paper
   pill and choosing a colour sets `canvas.paperArgb` **and** puts the colour into the document as
   `paper.color`, formatted the way the document formats it: `"#%06X" % (0xFFFFFF and argb)` in
   `Locale.US` — which is exactly what `JbCanvasView` does internally. Toggling "Include paper"
   writes `paper.includeInExport`. Both therefore **survive a save/reopen**, and the next export
   starts where the person left off (Decision 7 of JB-3.06b relies on this). No new field, no
   `DOC_VERSION` bump, no validator change: `Paper` already has both.
10. **Only the colour. `textureId` is not touched.** Paper *texture* is JB-1.05c's and the screen
    cannot show it (a document with a texture is **refused at open** by `JbCanvasView.refusalFor`,
    `:519-521`), so a row that sets one would produce a drawing this screen refuses to reopen.
    **Never set `paper.textureId` in this row.**
11. **Which colour picker, decided.** D.02 is `⚪ Outline` and Lead-owned, so this row must not wait
    for it and must not build one. The rule: **if a shared colour picker is reachable from the screen
    at build time, call it, titled "Paper"; otherwise a plain `AlertDialog` with an `EditText` for a
    `#RRGGBB` value, pre-filled with the current one, refused in words if what was typed does not
    match `^#[0-9a-fA-F]{6}$`.** Both paths end at the same two writes as Decision 9, so the rest of
    the row does not care which was used, and swapping to D.02's picker later is one call site. The
    chosen path is named in the builder's report so the swap is a one-liner someone can find.

## Tests (`PngExportTest`, JVM, with an in-memory `TileSource`)
1. DRAWING of one 10 × 10 red square at (300, −20) → rect (300, −20, 10, 10) exactly; PNG decodes
   (ImageIO) to 10 × 10 red.
2. Include paper on a transparent area → the paper colour, opaque; off → alpha 0.
3. BOARD → the board's rect; unknown board → null.
4. Scale 2 → 20 × 20, each source pixel a 2 × 2 block; over-budget scale → `RegionException`.
5. Empty drawing → `areaRect(DRAWING)` = null.
6. A layer with `visible = false` is not in the export.
7. **`ImageIO` is the oracle, and it must be the one.** Every pixel assertion decodes the produced
   PNG with `javax.imageio.ImageIO.read` rather than inspecting the `ByteArray` this file's own
   `png()` returned. A test that reads back its own buffer proves the buffer is the buffer; only a
   second implementation proves the file is a PNG. (The same lesson JB-3.06a's reviewer applied at
   cost — "the suite must contain at least one assertion made by a decoder that did not write the
   bytes".) **Non-vacuity:** assert `ImageIO.read(ByteArrayInputStream(bytes)) != null` before
   decoding anything else, so a broken writer cannot make the rest of the suite quietly vacuous.
8. **Scale 3 is refused, not rounded.** `scale` is 1, 2 or 4; anything else throws
   `IllegalArgumentException` naming the number. A scale the dialog cannot produce must not be a
   scale the object silently accepts.
9. **A negative-origin board exports.** A `Board` at `(-40, 300, 32, 32)` → `areaRect(BOARD)` is that
   exact rect and `png` is 32 × 32. Negative document coordinates are ordinary here (the canvas is
   unbounded) and an unsigned cast in the trim loop is the bug this pins.
10. **`includePaper` does not change the rect.** The same document exported both ways has byte-equal
    width and height and a **different** alpha at a transparent pixel. An export whose paper changed
    the crop is cropping on the wrong channel.
11. **The paper colour is used verbatim.** `doc.paper.color = "#8B7355"` with `includePaper = true`
    → an empty area decodes to exactly `(139, 115, 85)`. This pins that the `#RRGGBB` string reaches
    `RegionRenderer` unconverted, and that the alpha of a paper-backed pixel is **255**.

**Commands:** `./gradlew -p joybrush :androidkit:compileKotlin :androidkit:test` — BUILD SUCCESSFUL,
0 failures. Do **not** pass `-Pjoybrush.androidJar=<path>`: that property exists
(`androidkit/build.gradle.kts:19`) but is a fallback after `$ANDROID_HOME`, `$ANDROID_SDK_ROOT` and
`sdk.dir`, so the placeholder form is not a runnable command. Then the watcher compiles
`:joybrush-android:compileDebugKotlin` green, and the two greps in *Do not* are pasted.

## Owner check (Note 9)
Draw something, set paper to cream; Export → The whole drawing, include paper, 2× → the PNG in
Gallery is cream-backed, tightly cropped, twice the size. Export again without paper → transparent.
Then: **force-stop, reopen, Export again** — the paper colour and the Include-paper checkbox are
still what you left them as (Decision 9). And: cancel the save dialog → no file is left behind
(Decision 8).

## Do not
- **Do not change `RegionRenderer` or `PngWriter`.** No change, no optimisation, no "fix" — this row
  calls two functions that exist and are proven. The 5 of 8 blend modes with no GPU implementation is
  JB-2.20b's row, not a reason to touch the CPU renderer.
- **Do not un-premultiply, or flip a row, anywhere in this file.** `render` returns *straight* RGBA8
  row-0-top and `encode` takes exactly that. A second un-premultiply turns a soft edge into a halo
  and the only symptom is an export that looks slightly wrong.
- **Do not add a new export format here.** PSD is JB-2.14c; GIF / MP4 / WebP / PNG sequence /
  sprite sheet are JB-3.06b. A fourth format appearing in this file is a fourth format with no
  contract and no test.
- **Do not set `paper.textureId`** (Decision 10), and do not add a paper *texture* control. A
  document with a texture is refused at open by the very screen that would export it.
- **Do not add a `DOC_VERSION` bump.** `Paper` already carries `color` and `includeInExport`; this
  row writes existing fields.
- **Do not stream into the SAF Uri** (Decision 7), and do not leave a file behind on a cancel
  (Decision 8).
- **Do not touch `JbCanvasView.kt`.** `paperArgb` is read and written, not refactored — it is one of
  the two hottest files in the project.
- **🔴 Do not edit `JoyBrushActivity.kt` without the Lead naming the slot.** It is an app file, and
  the board's note is that app-file work is serialised: one at a time, never beside `JB-0.09`. Four
  rows want this one file — **this one, JB-2.15, JB-3.06b, and JB-0.10's Q2** — and `D.02c` and
  `D.05` are sitting on the board Ready to take it. If this row cannot have the file, `PngExport.kt`
  and its tests are still worth landing on their own: they are pure and 11 of the tests above need no
  screen. Say so and I will split the spec that way.
- Never run gradle on the app build. **Only** `./gradlew -p joybrush …` is permitted (ROADMAP §2
  rule 2). `joybrush-android/` is proved by the watcher's `build.log`, never by running it.

## Stop rule

If anything here is ambiguous, or a claim about `RegionRenderer` / `PngWriter` / `JbCanvasView` turns
out to be false when you open the file, **STOP**: write the question in *Questions* under a heading
`for the cross-reviewer`, set this row `⛔ Blocked`, commit, push, and take another task. Two things
are never a builder's call in this file: **adding an export format** and **touching the renderer**.
And if the screen refuses to open the drawing you were asked to export, **say so in the report and
stop** — do not "work around" `JbCanvasView`'s refusals, which exist to tell a person their drawing
cannot be shown here.

## Definition of done
- [ ] `./gradlew -p joybrush :androidkit:compileKotlin :androidkit:test` green, output pasted
- [ ] watcher `build.log` shows `:joybrush-android:compileDebugKotlin` EXECUTED inside `BUILD SUCCESSFUL`
- [ ] `git status --short` shows only the three owner-area paths
- [ ] `grep -c PngExport joybrush-android/src/main/kotlin/.../JoyBrushActivity.kt` > 0, pasted —
      the wiring actually uses the object this row built
- [ ] owner check noted, including the **reopen** half (Decision 9) and the **cancel** half
      (Decision 8)
- [ ] committed `JB-2.13b: paper and PNG export`; pushed
- [ ] ROADMAP row → 🟧 Built (set by the Lead)

## Questions

_(Cross-reviewer, `openrouter/stealth/space-bunny-alpha`, 2026-09-29. This section was **empty** in
the draft and the spec was dispatched-incomplete without it. There is nothing open that a builder
must answer — the D.02 dependency is resolved by Decision 11 and the app-file collision is referred
below.)

**Referred to the Lead, and neither blocks this row:**

1. **App-file slot.** Four rows name `JoyBrushActivity.kt` and two more (`D.02c`, `D.05`) are Ready
   for app-file work. Recommend: the Lead picks one, and the other three either queue or are split
   into their pure half. **Not a spec defect.**
2. **D.02 was listed as a dependency and is `⚪ Outline` and Lead-owned.** Removed from *Depends on*
   and replaced by Decision 11, which does not wait for it. If the Lead would rather this row *wait*
   for a real shared picker, say so and the paper pill is one call site to re-point.
3. **A `Paper` texture control** is JB-1.05c's, and a document with a texture is refused at open
   today. No question, just recorded so the refusal is not mistaken for a bug later.

**Verified true** (tree, 2026-09-29): `Paper.color` defaults `"#FFFFFF"` and `includeInExport`
defaults `false` (`DocModel.kt:38-43`); `PngWriter.encode(width, height, rgba, compressionLevel = 6)`
returns a `ByteArray` (`PngWriter.kt:85`); `RegionRenderer.render` returns *straight* RGBA8 row-0-top
and parses `paper` as `^#[0-9a-fA-F]{6}$` (`RegionRenderer.kt:179`, `:185`); `JbCanvasView.paperArgb`
is a `@Volatile var Int` (`JbCanvasView.kt:94`) and the screen formats paper back with
`String.format(Locale.US, "#%06X", 0xFFFFFF and argb)` (`JbCanvasView.kt:593`) — Decision 9 uses
that exact format rather than inventing one; and `refusalFor` refuses a document with
`paper.textureId != null` (`JbCanvasView.kt:519-521`), which is what makes Decision 10 a rule rather
than a preference.
