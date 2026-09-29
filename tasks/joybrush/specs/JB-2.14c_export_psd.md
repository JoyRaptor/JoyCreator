# JB-2.14c — Export PSD: our own writer, 8-bit, layered

| | |
|---|---|
| **Tier** | T2 |
| **Status** | 📝 Draft spec — **ruling R38 is applied in full and closes the question the row was stuck on: Q1 is withdrawn as WRONG, `SUBTRACT`/`DIVIDE` write their keys, `ERASE_BELOW` refuses with an offer, INK layers are omitted with a warning naming them, Decision 6's stream-of-consciousness is replaced with a decision, and the contract now imports what it uses and types every parameter.** Two questions remain, both **file-format** decisions R38 does not cover, both of which a builder would have to guess: **Q4** the merged image's alpha, and **Q5** whether a `luni`/`lsct` resource block is written. Each has a one-line recommended answer at the foot. Nothing else is open. |
| **Who** | spec writer `openrouter/stealth/space-bunny-alpha` 2026-09-29 · **xr: openrouter/stealth/space-bunny-alpha 2026-09-29** — R38 applied; **restructured: `PsdLayer` is gone entirely** (the writer now picks the layers itself, exactly as `OraExport` does), which removes the `strokes` field with no Decision using it *and* `rect` *and* the caller-assembled `tiles` map *and* the duplicated tile-size check, all in one move. Verified against the landed code: `OraExport.write(out, contents, boardId, frameId, includePaper): Unit` (`OraExport.kt:168`) is the sibling this row now matches shape for shape; `OraExport.omittedBecause` (`:346-355`) already owns the "INK / hidden / 0 % / no cel" rule, so this row reuses that rule instead of inventing one; `BlendRgb.kt:296` really is `max(b - s, 0)` for SUBTRACT and `:302` `min(b / max(s, EPS), 1)` for DIVIDE, which is what R38 says Photoshop's are — so the divergence the previous draft was built on does not exist. |
| **Needs** | JB-2.13a (`RegionRenderer`, `Blend`), JB-2.14a (`PngWriter`), JB-0.08a (`JbArchive`/`JbContents`) |
| **Owner area** | NEW `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/io/PsdWriter.kt` · NEW `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/io/PsdBlend.kt` (the mode → PSD 4-character table, and **nothing else in this file**) · NEW `joybrush/androidkit/src/test/kotlin/cc/joycreator/joybrush/androidkit/io/PsdWriterTest.kt` · NEW `joybrush/androidkit/src/test/kotlin/cc/joycreator/joybrush/androidkit/io/PsdReaderForTest.kt` (test source only, never `main` — Decision 9) |
| **Estimated size** | ~300 lines + ~260 lines of tests + ~110 lines of the test reader |
| **Command** | `./gradlew -p joybrush :androidkit:compileKotlin :androidkit:test` — BUILD SUCCESSFUL, 0 failures |

## Goal

Blueprint §3.4: *"Files: a native zip in the open OpenRaster shape (layers + JSON for boards, frames,
stroke recordings). Export PNG, OpenRaster, **PSD (own writer)**, GIF/WebP/MP4, PNG sequence, sprite
sheet."* R4: PSD is the format every other tool on a desktop reads, and there is no library in the
build to lean on — so the writer is ours and it is a **file format task**, which is exactly what a T2
builder is good at when the contract is written down.

Nobody copies a PSD writer from a library: it is a documented binary format, we write it from the
specification, and the tests read the file back with a reader written **only** for the tests. That is
the same shape as JB-2.14b's `.ora`, whose writer landed as `OraExport.kt` and whose tests
(`OraExportTest.kt`) decode the file with `javax.imageio` and a DOM parser — neither of which wrote
these bytes.

## Contract

```kotlin
package cc.joycreator.joybrush.androidkit.io

import cc.joycreator.joybrush.core.doc.BlendMode
import cc.joycreator.joybrush.core.doc.Board
import cc.joycreator.joybrush.core.doc.Cel
import cc.joycreator.joybrush.core.doc.DocOps
import cc.joycreator.joybrush.core.doc.Layer
import cc.joycreator.joybrush.core.doc.LayerKind
import cc.joycreator.joybrush.core.doc.RectPx
import cc.joycreator.joybrush.core.render.MAX_REGION_PX
import cc.joycreator.joybrush.core.render.RegionRenderer
import cc.joycreator.joybrush.core.render.TileSource
import java.io.OutputStream

/** Every refusal from this file, as a sentence a person can read. Never a truncated value. */
class PsdException(message: String) : Exception(message)

/**
 * The writer. **`PsdLayer` does not exist and no caller assembles a layer list** — the writer takes
 * the document and decides what goes in, exactly as [OraExport] does, so the layer order, the
 * omissions and the blend keys cannot be got wrong by a caller and cannot disagree between two
 * callers. See Decision 3.
 */
object PsdWriter {

    /**
     * The largest side PSD's own header documents. In practice `MAX_REGION_PX` refuses first: a
     * 30 000 × 30 000 board is 900 M pixels against a budget of 8 388 608, so this constant is a
     * stated bound rather than the one a person will meet.
     */
    const val MAX_DIMENSION = 30_000

    /**
     * Writes a complete, layered, 8-bit RGB PSD of board [boardId] of [contents] to [out].
     *
     * [out] is NOT closed and NOT wrapped: the caller owns the folder, the SAF `Uri` and the
     * `cacheDir` staging (R11), the same division of labour as [OraExport.write].
     *
     * @param frameId which frame of an animated board, or null for a static document.
     * @param includePaper composite the paper colour under everything for the merged image.
     * @return **the warnings, in document order** — one sentence per thing that was left out of the
     *   file, naming it ("layer \"Line\" is not in this file: an INK layer; its strokes are drawn by
     *   JB-5.01, not exported as pixels yet"). The caller shows them. **An export with nothing left
     *   out returns an empty list, not null.**
     * @throws PsdException for every refusal: no such board, a board with no room, a side over
     *   [MAX_DIMENSION], a side over 65 535 (a PNG and a PSD layer record are both 2-byte fields),
     *   a paper colour that is not `#RRGGBB`, or a layer whose blend mode PSD cannot express.
     */
    fun write(
        out: OutputStream,
        contents: JbContents,
        boardId: String,
        frameId: String?,
        includePaper: Boolean,
    ): List<String>

    /** The whole write as bytes. For the SAF "Save as" path and for the tests. */
    fun toBytes(
        contents: JbContents,
        boardId: String,
        frameId: String?,
        includePaper: Boolean,
    ): ByteArray
}
```

```kotlin
package cc.joycreator.joybrush.androidkit.io

import cc.joycreator.joybrush.core.doc.BlendMode

/**
 * The 4-character blend keys PSD understands, and NOTHING ELSE in this file — no arithmetic, no
 * constants, no helper beyond the table lookup.
 *
 * This is a TABLE OF NAMES, not a second implementation of a mode: the arithmetic is
 * `Blend`/`BlendRgb`'s, already proven equal to the Studio's (JB-2.20a) and used by `RegionRenderer`
 * for every other exporter. A mode PSD cannot express is **null**, and the writer then refuses in
 * words rather than writing a near-miss key (Decision 6).
 *
 * **THE KEY SET IN `keyFor` IS PROVISIONAL — see *Questions* Q1.** It is transcribed from the
 * published Photoshop/PDF blend-mode list, which is **not in this repo** and could not be checked
 * from here. The Lead's ruling settled the only question that was ever open about the *meanings*
 * (R38: Photoshop's Subtract is `base − blend` and Divide is `base ÷ blend`, clipped, the same as
 * ours), and said "write the keys" — so the keys are written here and the owner check in Krita is
 * what verifies them. Verify before trusting them.
 */
object PsdBlend {

    /**
     * The 4-character key for [mode], exactly as it is written into the file (including any
     * trailing space), or **null** for a mode PSD cannot express.
     *
     * Written as an exhaustive `when` over the enum with NO `else` branch, so a 28th mode is a
     * **compile error** and not a runtime surprise. This is the same promise `OraExport`'s
     * `compositeOp` (`OraExport.kt:472-498`) keeps and the same one `Blend`'s `term` keeps, and
     * JB-2.20a made it fire for real: it appended nineteen modes and both files stopped compiling.
     */
    fun keyFor(mode: BlendMode): String?

    /** The modes with no key, as a set — so the refusal message and the test cannot name a
     *  different four than [keyFor] returns null for. */
    fun unexpressible(): Set<BlendMode>
}
```

**The landed code this file calls, pasted so nothing above says "see the other file"** (every line
number checked in the tree on 2026-09-29):

```kotlin
// ---- cc.joycreator.joybrush.core.doc.DocJson.kt ---------------------------------------------
class DocException(message: String) : Exception(message)                        // :8

// ---- cc.joycreator.joybrush.core.doc.DocModel.kt --------------------------------------------
@Serializable data class RectPx(val x: Int, val y: Int, val w: Int, val h: Int)  // :36
@Serializable enum class LayerKind { PAINT, INK }                                 // :92
@Serializable data class Board(                                                  // :69
    val id: String, val name: String, val kind: BoardKind, val rect: RectPx,
    val clipToBoard: Boolean = false, val fps: Float = 12f,
    val frames: List<Frame> = emptyList(), val grid: SpriteGrid? = null,
)

/** `DocModel.kt:124-131` — TWENTY-SEVEN entries, in this order. APPEND-ONLY (R3). */
@Serializable enum class BlendMode {
    NORMAL, MULTIPLY, SCREEN, OVERLAY, ADD, DARKEN, LIGHTEN, ERASE_BELOW,
    // ---- appended, in `BlendModes.ALL` / modeCode order. FROZEN (R3, DOC_VERSION 2) ----
    DIFFERENCE, COLOR, COLOR_DODGE, COLOR_BURN, LINEAR_BURN,
    HARD_LIGHT, SOFT_LIGHT, VIVID_LIGHT, LINEAR_LIGHT, PIN_LIGHT, HARD_MIX,
    EXCLUSION, SUBTRACT, DIVIDE, DARKER_COLOR, LIGHDER_COLOR,
    HUE, SATURATION, LUMINOSITY,
}

/** `DocModel.kt:139-150`. Note `opacity` is a **Float 0..1**, not a byte. */
@Serializable data class Layer(
    val id: String, val name: String, val kind: LayerKind,
    val visible: Boolean = true, val locked: Boolean = false,
    val opacity: Float = 1f, val blend: BlendMode = BlendMode.NORMAL,
    val animatedIn: String? = null, val cels: List<Cel>, val frameCel: Map<String, String> = emptyMap(),
)

/** `DocModel.kt:152-162`. **`val layers` is at line 159** — `bottom -> top`. Line 160 is
 *  `activeLayerId`, which is where the earlier draft's `:160` citation came from and why it was
 *  wrong. */
@Serializable data class JbDocument(
    val format: String = DOC_FORMAT, val version: Int = DOC_VERSION,
    val id: String, val name: String, val paper: Paper = Paper(),
    val boards: List<Board>,
    val layers: List<Layer>,                // bottom -> top      <-- DocModel.kt:159
    val activeLayerId: String? = null, val activeBoardId: String? = null,
)

// ---- cc.joycreator.joybrush.core.doc.DocOps.kt ---------------------------------------------
/** The frame-to-cel rule. **This row never derives it a second way.** */
fun celFor(layer: Layer, frameId: String?): Cel?                               // :178
fun key(tx: Int, ty: Int): String = "${tx}_$ty"                                 // :185

// ---- cc.joycreator.joybrush.core.doc.BlendRgb.kt -- the two modes R38 ruled on -----------------
// SUBTRACT:  for (i in 0..2) out[i] = max(b[i] - s[i], 0f)                        // :294-298
// DIVIDE:    for (i in 0..2) out[i] = min(b[i] / max(s[i], EPS), 1f)              // :300-304
// i.e. base - blend and base / blend (clipped) — which is what R38 says Photoshop's are.

// ---- cc.joycreator.joybrush.core.render.RegionRenderer.kt ------------------------------------
fun interface TileSource { fun tile(layerId: String, celId: String, tx: Int, ty: Int): ByteArray? } // :24
const val MAX_REGION_PX = 8_388_608L                                              // :89
const val REGION_TILE_BYTES = RegionRenderer.TILE_BYTES   // 256*256*4 = 262_144   // :176

fun RegionRenderer.render(
    doc: JbDocument, tiles: TileSource, rect: RectPx, frameId: String?, paper: String?,
): ByteArray                                                                   // :196
// -> STRAIGHT (un-premultiplied) RGBA8, `rect.w * rect.h * 4` bytes, row 0 = TOP.
// -> RegionException over MAX_REGION_PX. A paper string that is neither null nor `#RRGGBB`
//    throws IllegalArgumentException at RegionRenderer.kt:426, and a tile of the wrong size at
//    :290. **Both of those are strictly downstream of this writer's own checks (which are
//    strictly smaller numbers), which is the argument `OraExport.kt:134-139` makes.**

// ---- cc.joycreator.joybrush.androidkit.io.JbArchive.kt --------------------------------------
class JbArchiveException(message: String) : Exception(message)                  // :34
const val TILE_BYTES = TILE_SIZE * TILE_SIZE * 4   // :31 -- the SAME number as RegionRenderer's
data class JbContents(                                                          // :44-51
    val doc: JbDocument,
    val tiles: Map<Triple<String, String, String>, ByteArray>,   // (layerId, celId, "tx_ty")
    val strokes: Map<Pair<String, String>, List<StrokeRecord>>,
    val thumbnailPng: ByteArray? = null,
)

// ---- cc.joycreator.joybrush.androidkit.io.OraExport.kt -- THE SIBLING THIS ROW MATCHES -------
fun write(out: OutputStream, contents: JbContents, boardId: String, frameId: String?,
          includePaper: Boolean)                                                 // :168
private fun omittedBecause(layer: Layer, frameId: String?): String? = when {    // :346-355
    layer.kind != LayerKind.PAINT -> "an INK layer; its strokes are drawn by JB-5.01, not exported as pixels yet"
    !layer.visible -> "it is hidden"
    opacityOf(layer) <= 0f -> "it is 0% opaque"
    DocOps.celFor(layer, frameId) == null -> "it has no cel for this frame"
    else -> null
}
private fun opacityOf(layer: Layer): Float {                                      // :508-514
    val o = layer.opacity
    if (o.isNaN()) return 0f
    if (o < 0f) return 0f
    if (o > 1f) return 1f
    return o
}
private fun tileSource(contents: JbContents) = TileSource { layerId, celId, tx, ty -> // :358-360
    contents.tiles[Triple(layerId, celId, DocOps.key(tx, ty))]
}
```

**The blend-key table itself, spelled out** (PROVISIONAL — *Questions* Q1). Every key is exactly
**four** characters; `"lum "` has a trailing space and is the one that gets trimmed by a careless
`trim()`, and `COLOR_DODGE` is `idiv` while `DIVIDE` is `div`, which is the one that gets swapped:

| `BlendMode` | PSD key | note |
|---|---|---|
| `NORMAL` | `norm` | |
| `MULTIPLY` | `mul` | |
| `SCREEN` | `scr` | |
| `OVERLAY` | `over` | |
| `DARKEN` | `dark` | |
| `LIGHTEN` | `lite` | |
| `ADD` | `lddg` | Photoshop's **Linear Dodge (Add)**. PROVISIONAL |
| `DIFFERENCE` | `diff` | |
| `COLOR` | `colr` | |
| `COLOR_DODGE` | `idiv` | **not** `div` |
| `COLOR_BURN` | `gblb` | |
| `LINEAR_BURN` | `lbrn` | |
| `HARD_LIGHT` | `hLit` | capital L |
| `SOFT_LIGHT` | `sLit` | capital L |
| `VIVID_LIGHT` | `vLit` | capital L |
| `LINEAR_LIGHT` | `lLit` | capital L |
| `PIN_LIGHT` | `pLit` | capital L |
| `HARD_MIX` | `hMix` | capital M |
| `SUBTRACT` | `sub` | **R38: the same meaning as ours. Written.** |
| `DIVIDE` | `div` | **R38: the same meaning as ours. Written.** |
| `HUE` | `hue` | |
| `SATURATION` | `sat` | |
| `LUMINOSITY` | `"lum "` | **four characters, trailing space** |
| `ERASE_BELOW` | **null** | refused in words, with the flattened-export offer (R38) |
| `EXCLUSION` | **null** | PSD has no exclusion blend |
| `DARKER_COLOR` | **null** | PSD has no key |
| `LIGHTER_COLOR` | **null** | PSD has no key |

## Decisions

1. **8-bit, RGB colour mode 3, one composite at the end, transparency in the per-layer alpha.**
   Depth 8, mode 3 (RGB), and every layer carries channels `-1, 0, 1, 2` — alpha, R, G, B. *Why:*
   that is what Photoshop actually uses, and a layer's transparency is a property of the layer, not
   of the file.
2. **Every layer's record covers the WHOLE canvas rectangle (`0, 0, w, h`), with the alpha channel
   doing the cropping.** No per-layer bounding box is computed, and `PsdLayer.rect` does not exist.
   *Why:* every exporter in this repo already renders the full rect and lets alpha do the cropping
   (`OraExport.Entry.pixels` renders `rect`, `OraExport.kt:278-285`); a bounding box is a tile scan
   that can be wrong by one tile, and the only thing it buys is a few kilobytes Photoshop does not
   need.
3. **There is no `PsdLayer` type: the writer takes a `JbContents` and chooses the layers itself,
   exactly as `OraExport` does.** The earlier draft's contract carried a caller-assembled
   `PsdLayer(name, kind, visible, opacity, blend, rect, tiles)` **and a `strokes: List<StrokeRecord>`
   that no Decision used** — a field the writer would have had to decide what to do with, which is a
   design decision left in a contract. *Why it is not just a deletion:* taking `JbContents` also
   removes the caller's tile-map assembly, removes a **second copy** of the tile-size check
   (`RegionRenderer.kt:290` already refuses a wrong-sized tile, loudly and by name), and removes any
   chance of two callers producing two different PSDs for one document. `JbContents` is the type
   `JbArchive` and `OraExport` already pass around, so this is sharing, not copying (R23).
4. **The blend-key table is a TABLE, exhaustive over all twenty-seven names, with no `else`, and a
   mode PSD cannot express is `null`.** *(This replaces the draft's Decision 6, which was a
   paragraph of thinking-out-loud about whether Photoshop's Subtract meant the same thing as ours.
   R38 answered it: it does. `BlendRgb.kt:296` is `max(b - s, 0)` and `:302` is
   `min(b / max(s, EPS), 1)` — `base − blend` and `base ÷ blend`, clipped — which is what the ruling
   says Photoshop's are. So `SUBTRACT` is `sub` and `DIVIDE` is `div`, and the divergence the old
   text was built on does not exist.)* *Why the table and not the arithmetic:* the arithmetic is
   `Blend`'s, used by `RegionRenderer` for every other exporter; a second implementation here is how
   the screen and the file start disagreeing. *Why `null` and not a near-miss key:* the nine-mode
   refusal in `OraExport.compositeOp` (`OraExport.kt:457-462`) is the precedent and the reason — a
   key that is nearly the right one produces a file that opens and is quietly wrong.
5. **A mode with no key REFUSES THE WHOLE EXPORT, in words, naming the layer and the mode** — and
   for `ERASE_BELOW` the message offers the two ways out: *"this drawing has layer \"X\" in Erase
   Below mode, which PSD cannot express — export it flattened, or change that layer's mode."*
   *(R38: "`ERASE_BELOW` refuses, with an offer of a flattened export.")* *Why a refusal and not an
   approximation:* a PSD that opens and composites differently is the failure this row exists to
   prevent, and "refuse rather than approximate" applied to a format limit is the same rule as
   applied to bad input. **What this row does not do is implement "flattened":** flattening is a
   render, this row writes a container and changes no arithmetic (Decision 2 of the old draft, kept),
   and the wording of the offer is R38's, not an invented second button. See *Questions* Q3.
6. **INK layers, hidden layers, layers at 0 % opacity and layers with no cel for this frame are
   OMITTED, and every one of them is named in a returned warning** *(R38: "INK layers are OMITTED
   with a warning naming them — an empty layer confuses")*. The rule and the four reasons are
   **verbatim `OraExport.omittedBecause`** (`OraExport.kt:346-355`), so the two exporters agree
   about one document. *Why omitted rather than written empty:* an empty layer in Photoshop is
   something a person deletes without knowing why, which is R38's point and the previous draft's
   error. *Why hidden layers go too, given PSD can express them:* consistency with the sibling
   exporter, and because expressing "hidden" needs an `lsct` block — which is Q5, and this row does
   not wait on Q5 to omit a layer it cannot describe.
7. **The composite (merged image) is produced by `RegionRenderer.render` — never by this writer.**
   Every exporter renders through that one function, so the PSD's flat image and the `.ora`'s merged
   image and the PNG's are the same bytes by construction. This row adds a *container*, not a
   renderer. **What the merged image's alpha channel is, is Q4 — the only open format decision that
   changes bytes.**
8. **Per-layer channel data is RLE-compressed (compression method 1), never raw.** A 256² RGBA layer
   is 262 144 raw bytes per channel; a dozen layers raw is hundreds of megabytes and no phone writes
   that. PSD's RLE is **PackBits**, the same algorithm as the TIFF variant, and the row-length table
   is **two bytes per ROW, `height` entries, repeated for each channel in channel order** — so a
   channel's table is `2 × height` bytes and the whole table is `2 × height × channels`. Getting this
   wrong is the classic "Photoshop says the file is corrupt", so it is Test 3. *(The earlier draft
   said the table was "`2 + width` bytes each"; that is wrong on both counts — it is two bytes per
   row, and there are `height` rows, not `width`.)*
9. **Layer records are written TOP FIRST, so `doc.layers` is reversed.** `doc.layers` is bottom → top
   (`DocModel.kt:159`); PSD's layer records start with the topmost. Asserted, not assumed — Test 2.
10. **A layer with no pixels writes a record with zero channel data, not a skipped record.** *Why:*
    a layer nobody has drawn on yet is a real layer in the real stack and the next thing the person
    does is draw on it — the same reasoning as `OraExport`'s.
11. **All size arithmetic is `Long`, and every extent is checked BEFORE the first byte is written**
    (R19 — the lesson of `SpritePacker` and `SpriteGridMath`, where an `Int` overflow produced a
    *silently wrong cell* rather than a crash). `rect` over `MAX_REGION_PX`, over
    `MAX_DIMENSION`, or with a side over 65 535 is a `PsdException` with the numbers in the message.
12. **Layer names are Pascal strings, padded to a multiple of 4, truncated to 255 bytes on a CHARACTER
    boundary.** A name cut mid-UTF-8 is a file some readers reject. A longer name gets the tail cut,
    **never an exception** — a name is not worth refusing a drawing over.
13. **The resolution is 72 dpi in the header, and no `psd:` resource block beyond the minimum.** A
    made-up resource block is one more thing that can disagree with the truth. **Whether `luni` (the
    Unicode layer name) and `lsct` (the section state) are written is Q5 and is not decided here** —
    see *Questions*.
14. **Every refusal is checked before the first byte, so a refused export leaves `out` untouched.**
    The board, the room, both size ceilings and the paper colour are all checked up front, for the
    same reason `OraExport` checks them (`OraExport.kt:134-141`): a refusal that leaves a half file
    at a name the person chose is the outcome R11 exists to prevent.
15. **The tests read the file back with a reader written for the tests**, in test source and never in
    `main`. A writer tested only against itself proves nothing; a writer tested against an
    independent parser that walks the same published format proves the format. The reader's RLE
    decoder is written from the PackBits description, not copied from the writer.

## Decision → Test map

| Decision | Pinned by |
|---|---|
| 1 (8-bit RGB, per-layer alpha) | `theHeaderIsExactlyWhatPhotoshopExpects` |
| 2 (full-canvas layer records) | `everyLayerRecordIsTheWholeCanvasRect` |
| 3 (no `PsdLayer`; the writer chooses) | `theWriterNeedsNothingFromTheCallerButTheDocument` (reflection over `PsdWriter`'s parameters) |
| 4 (exhaustive table, `null` for the four) | `everyModeHasAKeyOrIsOneOfTheFourNulls`, and a **compile error** for a 28th |
| 5 (refuse, with the flattened offer) | `anEraseBelowLayerIsRefusedWithTheOfferNamed` |
| 6 (INK / hidden / 0 % / no cel omitted and named) | `anInkLayerIsOmittedAndNamed`, `theOmissionsMatchOrasOwnRule` |
| 7 (composite = `RegionRenderer`'s bytes) | `theMergedImageIsExactlyWhatRegionRendererReturns` — **gated on Q4** |
| 8 (RLE, `2 × height` per channel) | `theRowLengthTableIsTwoBytesPerRowForEveryChannel` |
| 9 (top first) | `layersComeBackTopFirst` |
| 10 (empty layer, zero channels) | `aLayerNobodyHasDrawnOnStillHasARecordAndAName` |
| 11 (Long arithmetic, refused up front) | `anOversizedBoardIsRefusedWithZeroBytesWritten` |
| 12 (names truncate on a character boundary) | `aNameWithAnEmojiDoesNotProduceInvalidUtf8` — **gated on Q5** |
| 13 (no invented resource blocks) | `thereIsNoResourceBlockWeDidNotDecideOn` — **gated on Q5** |
| 14 (refusals before the first byte) | `aRefusedExportLeavesTheStreamEmpty` |
| 15 (independent test reader) | the reader lives in test source; `git status --short` proves it |

## Tests — `PsdWriterTest.kt` (JVM, `:androidkit`)

Fixture: build `JbContents` the way `OraExportTest` does — a `JbDocument` with a 300 × 200 board, a
background layer, a layer at opacity 0.4, a hidden layer, an INK layer and (for Test 5) a layer in
each of the twenty-three expressible modes — and `tiles` keyed
`Triple(layerId, celId, "0_0")`. The TileSource is `OraExport.kt:358-360`'s line, verbatim.

1. **Header:** `8BPS`, version 1 (BE), six reserved zero bytes, channels 4, **height then width,
   big-endian**, depth 8, colour mode 3, and the rest of the colour-mode data zero. A 300 × 200 board
   reads back as exactly those numbers — asserted **against a hand-computed byte at a known offset**,
   not against the writer's own field.
2. **Order:** three layers `[bottom, middle, top]` come back `["top", "middle", "bottom"]` (D9).
3. **RLE, and the row table:** a 300-px-wide solid layer compresses to far less than
   `300 × 200 × 4` bytes, the reader's PackBits decoder returns the original bytes **exactly**, and
   **the row-length table is `2 × height × channels` bytes and its per-channel sums equal the
   decompressed channel lengths** (D8). This is the "Photoshop says the file is corrupt" check.
4. **A gradient** (no two pixels alike) RLE-decompresses byte-for-byte and is **larger** than the
   same pixels raw — proving the writer did not fake compression with a short buffer.
5. **Preview = export:** the merged image in the file is byte-identical to
   `RegionRenderer.render(doc, tiles, rect, frameId, paper)` for the same arguments. This is the most
   valuable assertion in the file. *(Gated on Q4 only as to the alpha channel — see *Questions*.)*
6. **Opacity and visibility:** a layer at `opacity 0.4` is written as `102` (0.4 × 255, and the test
   says so — `Layer.opacity` is a **Float**, `DocModel.kt:145`), and the merged image still matches
   `RegionRenderer`'s.
7. **Blend keys:** `keyFor` is non-null for twenty-three modes and `null` for exactly
   `{ERASE_BELOW, EXCLUSION, DARKER_COLOR, LIGHDER_COLOR}`; every non-null key is **exactly four
   characters**; `"lum "` keeps its **trailing space**; and `COLOR_DODGE != DIVIDE`. The `when` has
   no `else`, so the 28th-mode guard is the **compiler**; the test guards the *content* by walking
   `BlendMode.entries` and asserting the partition above.
8. **Refusals, in words:** a 30 001-px board, a 65 536-px board, a board over `MAX_REGION_PX`, a
   missing board, a `paper.color` of `"white"`, and a layer in `ERASE_BELOW` — each throws
   `PsdException` whose message **names the offending thing**, and **the output stream has had zero
   bytes written** (D14). The `ERASE_BELOW` message is additionally asserted to contain `flattened`
   (R38's offer).
9. **Long arithmetic:** a rect at `Int.MAX_VALUE` is refused, and the test's comment says why `w * h`
   in `Int` would have wrapped (D11).
10. **An empty layer** writes a record with zero channels, a name and a rect — not skipped (D10).
11. **Omissions:** a document with an INK layer, a hidden layer and a 0 % layer returns **three**
    warnings, each naming its layer and one of `OraExport`'s four reasons verbatim, and the file
    contains the other layers only (D6). Plus: `theOmissionsMatchOrasOwnRule` walks every `BlendMode`
    / visibility / opacity / cel combination and asserts this file's rule equals `OraExport`'s.
12. **Names:** a 300-character name truncates to ≤ 255 bytes **on a character boundary** and the
    file still parses; a name with an emoji does not produce invalid UTF-8 (D12).
13. **No invented blocks:** the reader walks the image-resource section and asserts it contains only
    the blocks this spec names — resolution, and whatever Q5 settles. *(Gated on Q5.)*
14. **Determinism:** two writes of the same document produce **byte-identical** files. *(The `.ora`
    cannot promise this because `ZipOutputStream` stamps entries with the clock; a PSD has no
    timestamps, so it can — and a writer with a `HashMap` iteration order in it would fail.)*
15. **The reader is independent:** `PsdReaderForTest.kt` is in the test source set and the file is
    in no `main` source set. Asserted by `git status --short`, pasted in the report.

**Command:** `./gradlew -p joybrush :androidkit:compileKotlin :androidkit:test` — BUILD SUCCESSFUL,
0 failures. **Do not pass `-Pjoybrush.androidJar=<path>`.** The property is real
(`androidkit/build.gradle.kts:19`) but it is a **fallback**: `findAndroidJar()` tries it first, then
`$ANDROID_HOME`, then `$ANDROID_SDK_ROOT`, then `sdk.dir` in the repo's `local.properties`, newest
platform wins — and `local.properties` exists in this repo. A builder on a machine with an SDK
configured passes nothing at all, so the unrunnable placeholder form of the command is a spec that
cannot prove its own tests.

## Owner check (Note 9) — **📱 the owner's, not the builder's**

**Open the exported file in Krita, with one layer per blend mode, and confirm.** R38's instruction,
and it is the **oracle for the one thing this spec could not verify** — the 4-character key table
above (Q1). So: a 300 × 200 board, one layer per expressible mode, each a recognisable colour,
Export → PSD → open in Krita → every layer is there, in the right order, each compositing as it does
in Joy Brush. **Pay attention to `SUBTRACT` and `DIVIDE` specifically: those are the two the ruling
re-opened and closed, and Krita's reading of them is the check.** Then: one hidden layer, one at
40 %, and an INK layer — the two survivors must composite exactly as the screen shows, and the INK
layer's absence must have been **said** before the export started. A 4000 × 3000 board exports; a
30 000-px board says why not.

**And the ruling's own caveat, kept in front of the owner: *verify before trusting this ruling*.**
R38's claim about Photoshop's Subtract and Divide could not be checked from this repo — there is no
PSD writer, no Photoshop and no PSD specification document in the tree — and the key spellings came
from the same published list. If Krita shows `subtract` or `divide` compositing differently from the
screen, **that is the finding**, and it is filed against the table in `PsdBlend`, not worked around
in the writer.

## Do not

- **Do not render anything.** `RegionRenderer` renders; this file writes a container. A second
  renderer in a PSD writer is how preview and export start disagreeing, and Test 5 exists to catch it.
- **Do not add a library dependency.** It is a format; we write it. (R4.)
- **Do not implement** ink rasterisation, layer masks, adjustment layers, 16/32-bit, CMYK, spot
  channels, PSD's vector or pattern blocks, or flattening. v1 is 8-bit RGB, paint layers, one
  composite, and "export it flattened" is an **offer in a sentence**, not a second code path (Q3).
- **Do not "fix" a blend mode** by substituting a near-miss key. Four modes are `null` and are
  refused by name. A PSD that opens and composites differently is worse than a PSD that was not
  written.
- Do not open-code `OraExport`'s `omittedBecause` with different reasons "to be clearer". The four
  reasons are shared on purpose (R23) and Test 11 fails if they drift.
- Do not put `android.net.Uri` or any other `android.*` type in this file. `android.jar` is
  `compileOnly`; staging into `cacheDir` and streaming to the `Uri` is R11 and belongs to the row
  that owns the screen, exactly as it does for `OraExport`.
- **Never run gradle on the app build.** Only `./gradlew -p joybrush …` is permitted, and only the
  `:core` / `:androidkit` tasks. Anything in `:app` or `joybrush-android/` is proved by the watcher's
  `build.log`, never by running it.
- Never widen the owner area to edit `Blend.kt`, `BlendRgb.kt`, `RegionRenderer.kt`, `OraExport.kt` or
  `JbArchive.kt`. This row writes a container and changes no arithmetic and no rules.

## Stop rule

If anything here is ambiguous, or a claim about landed code turns out to be false when you open the
file, **STOP**: write the question in *Questions* under the heading `for the Lead`, set this row
`⛔ Blocked`, commit, push, and take another task. In particular: **never invent a PSD resource
block, a version number, a blend key or a channel count to get past an unanswered question here.** A
PSD Photoshop calls corrupt is worse than no PSD export, and a made-up key is exactly how that
happens. Two questions are the Lead's (Q4, Q5) and the stop rule names them.

## Definition of done

- [ ] `./gradlew -p joybrush :androidkit:compileKotlin :androidkit:test` output pasted, 0 failures
- [ ] `git status --short` shows only the four owner-area paths (and that `PsdReaderForTest.kt` is
      under `src/test`, not `src/main`)
- [ ] owner check noted, with Krita open on a file with one layer per mode
- [ ] committed `JB-2.14c: PSD export`
- [ ] the ROADMAP row is set by the Lead (this spec does not touch `ROADMAP.md`)

## Questions

_(Spec writer: `openrouter/stealth/space-bunny-alpha`, 2026-09-29. R38 is applied above and Q1 is
withdrawn as wrong-premise. **Two questions are left, and they are the whole of why this row is
still Draft.** Both are **file-format** decisions R38 does not cover, and both change bytes, so
neither is mine or a cross-reviewer's.)_

### ⛔ For the Lead — the two that hold the row

**Q4. What is the merged image's alpha?** Decision 7 says the composite is
`RegionRenderer.render`'s bytes and nothing else. `render` with `paper = null` returns a **STRAIGHT
RGBA8 image whose empty pixels are `0,0,0,0`** (`RegionRenderer.kt:207-219`: alpha 0 takes `k = 0` and
stays transparent, by design, and the `.ora` writer writes exactly those bytes as `mergedimage.png`).
So "the merged image" is either transparent or black, and PSD colour mode 3 permits either four or
five channels:

- **(a) Write five channels for the composite** — `-1, 0, 1, 2, -1` (alpha, R, G, B, alpha again).
  Photoshop shows a transparent background, which is the truth, and it is **byte-identical to what the
  PNG exporter and the `.ora` exporter write today**, so all three agree.
- **(b) Write four opaque channels**, passing `paper = "#000000"` when `includePaper` is false.
  Matches an older draft's sentence; disagrees with the other two exporters.
- **(c) Always composite over `doc.paper.color`**, so "include paper" only means "what is under it".

**My answer is (a)**, because "preview = export" (Test 5) and "all exporters agree" are the two
properties this file is built on and (a) is the only reading that keeps both — but it is a colour
decision about what a person sees when they open the file, so it is yours. **Which is it?** One word
is enough.

**Q5. Is a `luni` / `lsct` resource block written at all?** The real answers are: **write `luni` on
every layer** (an emoji or a CJK name survives the round trip, at the cost of one resource block
nobody has asked for); **write it only when the name needs it** (a `when` that makes two otherwise
identical files differ, which is the kind of quiet difference that is undebuggable six months later);
or **write it never** (Decision 12's 255-byte Pascal truncation then genuinely loses characters, with
nothing in the file saying so). `lsct` is the same question for "this layer is hidden". **My answer
is to write `luni` always and not to write `lsct`** — one block, uniform, and hidden layers are
omitted anyway (Decision 6) so `lsct` would have nothing to say. **Is that right?** Tests 12 and 13
are the only ones waiting on it, and they are written.

### Also for the Lead — the part R38 settled but the Krita check has to confirm

**Q1. The blend-key table is PROVISIONAL and I could not verify it here.** R38 settled the
*meanings* (`SUBTRACT` is `base − blend`, `DIVIDE` is `base ÷ blend` clipped — and `BlendRgb.kt:296`
and `:302` confirm ours is exactly that, so the divergence the previous draft was built on does not
exist). What I could not verify is the **spelling** of twenty-three 4-character keys, because the
Photoshop/PDF blend-mode list is not in this repo and no PSD reader exists here to check against. Two
spellings are the ones to look at: `LUMINOSITY` is `"lum "` with a **trailing space**, and
`COLOR_DODGE` is `idiv` while `DIVIDE` is `div`. The owner check in Krita, one layer per mode, is the
oracle; **verify before trusting this ruling** is the Lead's own caveat and it applies to the table as
much as to the meaning. If a key is wrong, the fix is one line in `PsdBlend` and nothing else —
which is why the table is its own file with nothing else in it.

**Q3. "Export it flattened" is an offer in a sentence, not a button.** R38 says `ERASE_BELOW` refuses
*with an offer of a flattened export*, and this row writes the offer into the message (Test 8 asserts
the word is there). It does **not** implement the flattened path, because flattening is a render and
Decision 7 forbids a second renderer. If you want the button to exist, say so and I will add it as a
two-line branch here (`write` gains a `flatten: Boolean`, the layer list goes empty and the composite
stands alone) — that is what the previous draft proposed and it is *your* call whether a person told
"PSD cannot express this" should get a working file instead of a sentence.

### Checked against the landed code — true, so nobody re-checks it

| This spec says | The tree says |
|---|---|
| `doc.layers` is bottom → top at `DocModel.kt:159` | **True. Line 159** is `val layers: List<Layer>,  // bottom -> top`; line 160 is `activeLayerId`, which is where the earlier `:160` came from. Corrected here. |
| `BlendMode` has 27 entries, `ERASE_BELOW` is Joy Brush's own | **True.** `DocModel.kt:124-131`: 8 + 19 = 27. `ERASE_BELOW` is ordinal 7. |
| `SUBTRACT` is `max(b - s, 0)` and `DIVIDE` is `min(b / max(s, EPS), 1)` | **True.** `BlendRgb.kt:294-304`. This is what makes R38's ruling land: ours *is* `base − blend` and `base ÷ blend` clipped. |
| `Layer.opacity` is 0..1 | **True.** `DocModel.kt:145` — a `Float`, so the byte in the file is `(o * 255 + 0.5)` and Test 6 pins 0.4 → 102. |
| `LayerKind { PAINT, INK }` | **True.** `DocModel.kt:92`. |
| `RegionRenderer.render(doc, tiles, rect, frameId, paper)` returns straight RGBA8, row 0 = top | **True.** `RegionRenderer.kt:196-221`, and the un-premultiply with `k = 0` at alpha 0 is at `:213`. |
| `MAX_REGION_PX = 8_388_608` | **True.** `RegionRenderer.kt:89`; `RegionRenderer.TILE_BYTES = 262_144` at `:176`. **There is a second `TILE_BYTES` at `JbArchive.kt:31` with the same value** — name which one you mean if you import it. |
| `BlendMode` names are appended at the END and a new one needs a `DOC_VERSION` bump | **True.** R30 item 3: **the version number is assigned AT LANDING, never in a spec** — this row adds no serialised field, so it needs no bump at all. |
| `JbContents(doc, tiles, strokes, thumbnailPng)` | **True.** `JbArchive.kt:44-51`. |
| `OraExport.write(out, contents, boardId, frameId, includePaper)` | **True.** `OraExport.kt:168`. Its `omittedBecause` (`:346-355`), `opacityOf` (`:508-514`) and `tileSource` (`:358-360`) are pasted above and reused verbatim. |
| The row's test command | `./gradlew -p joybrush :androidkit:compileKotlin :androidkit:test`, which is also the one ROADMAP §2 rule 2 permits. `local.properties` exists in the repo, so `findAndroidJar()` resolves without the property. |