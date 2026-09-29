# JB-2.14c — Export PSD: our own writer, 8-bit, layered

| | |
|---|---|
| **Tier** | T2 |
| **Status** | 📝 Draft spec — **three things are for the Lead and are not mine to rule: the `SUBTRACT`/`DIVIDE` blend keys (Q1, a wrong-pixels decision), what the merged image looks like with `includePaper = false` (Q4), and whether a `luni`/`lsct` resource block is written at all (Q5).** See "for the cross-reviewer" at the foot. Everything else was checked against the landed code and corrected. |
| **Who** | spec writer `openrouter/stealth/space-bunny-alpha` 2026-09-29 · **xr: openrouter/stealth/space-bunny-alpha 2026-09-29** — verified `BlendMode` has exactly 27 entries (`DocModel.kt:124`, `ERASE_BELOW` at ordinal 7 is Joy Brush's own insertion), `doc.layers` is bottom→top, `LayerKind`/`RectPx`/`RegionRenderer.TILE_BYTES`/`PngWriter.encode` all exist with the shapes the contract assumes, and `-Pjoybrush.androidJar` is a real property in `androidkit/build.gradle.kts:19`. **Corrected:** the `DocModel.kt:160` citation (it is line **159**), the unrunnable `-Pjoybrush.androidJar=<path>` in the test command, the `PsdLayer.strokes` field that no Decision uses, and Decision 6's draft thinking-out-loud. Added the missing stop rule. Left Draft on three format questions. |
| **Needs** | JB-2.13a (`RegionRenderer`, `Blend`, `BlendRgb` — Built 🟧) |
| **Owner area** | NEW `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/io/PsdWriter.kt`, NEW `.../io/PsdBlend.kt` (the mode → PSD 4-char table, and nothing else), NEW `joybrush/androidkit/src/test/kotlin/cc/joycreator/joybrush/androidkit/io/PsdWriterTest.kt`, `PsdReaderForTest.kt` (a test-only reader, see Decision 9) |
| **Estimated size** | ~330 lines + ~280 lines of tests + ~90 lines of the test reader |

## Goal

Blueprint §3.4: *"Files: a native zip in the open OpenRaster shape (layers + JSON for boards, frames,
stroke recordings). Export PNG, OpenRaster, **PSD (own writer)**, GIF/WebP/MP4, PNG sequence, sprite
sheet."* R4: PSD is the format every other tool on a desktop reads, and there is no library in the
build to lean on — so the writer is ours and it is a **file format task**, which is exactly what a
T2 builder is good at with a contract.

Nobody copies a PSD writer from a library. It is a documented binary format, we write it from the
spec, and the tests read it back with a reader written only for the tests.

## Contract

```kotlin
package cc.joycreator.joybrush.androidkit.io

import cc.joycreator.joybrush.core.doc.BlendMode
import cc.joycreator.joybrush.core.doc.JbDocument
import cc.joycreator.joybrush.core.doc.LayerKind
import cc.joycreator.joybrush.core.doc.RectPx
import cc.joycreator.joybrush.core.render.RegionRenderer
import cc.joycreator.joybrush.core.render.TileSource

/** One layer to write. [rect] is the canvas rectangle the PSD has; layers are positioned inside it. */
data class PsdLayer(
    val name: String,
    val kind: LayerKind,                 // PAINT only in v1 (see Decision 8)
    val visible: Boolean,
    val opacity: Int,                    // 0..255, already rounded
    val blend: BlendMode,
    val rect: RectPx,                    // the layer's own bounds, in document px
    /** Premultiplied RGBA8 tiles, keyed by Tiles.key, exactly as the engine holds them. */
    val tiles: Map<Long, ByteArray>,
)
```

**`PsdLayer` has no `strokes` field, deliberately.** An earlier draft of this contract carried
`val strokes: List<StrokeRecord>` and no Decision below used it: Decision 8 writes an INK layer as an
**empty** layer rather than rasterising it, so there is nothing to write. A field the writer must
decide what to do with is a design decision left in the contract, so it is gone. (Cross-reviewer,
2026-09-29.)

The types this contract leans on, pasted so nothing above says "see the other file":

```kotlin
// cc.joycreator.joybrush.core.doc.DocModel.kt
@Serializable data class RectPx(val x: Int, val y: Int, val w: Int, val h: Int)
@Serializable enum class LayerKind { PAINT, INK }

/** `DocModel.kt:124` — 27 entries. `ERASE_BELOW` is Joy Brush's own and has no Studio counterpart. */
@Serializable enum class BlendMode {
    NORMAL, MULTIPLY, SCREEN, OVERLAY, ADD, DARKEN, LIGHTEN, ERASE_BELOW,
    // ---- appended, in `BlendModes.ALL` / modeCode order. FROZEN (R3, DOC_VERSION 2) ----
    DIFFERENCE, COLOR, COLOR_DODGE, COLOR_BURN, LINEAR_BURN,
    HARD_LIGHT, SOFT_LIGHT, VIVID_LIGHT, LINEAR_LIGHT, PIN_LIGHT, HARD_MIX,
    EXCLUSION, SUBTRACT, DIVIDE, DARKER_COLOR, LIGHTER_COLOR,
    HUE, SATURATION, LUMINOSITY,
}

// cc.joycreator.joybrush.core.render.RegionRenderer.kt:24
fun interface TileSource {
    fun tile(layerId: String, celId: String, tx: Int, ty: Int): ByteArray?
}

// cc.joycreator.joybrush.core.render.RegionRenderer.kt:196 — the one renderer (Decision 2)
fun render(doc: JbDocument, tiles: TileSource, rect: RectPx, frameId: String?, paper: String?): ByteArray
    //  -> STRAIGHT (un-premultiplied) RGBA8, rect.w * rect.h * 4 bytes, row 0 = top
    //  -> throws RegionException above MAX_REGION_PX = 8_388_608
    //  -> `paper` is a "#RRGGBB" string or null; anything else is IllegalArgumentException
```


```kotlin
object PsdWriter {
    /** The largest canvas PSD's own header allows: 30 000 × 30 000. */
    const val MAX_DIMENSION = 30_000

    /**
     * Writes a complete, layered, 8-bit RGB PSD of [rect] (the board's rectangle).
     *
     * @param layers BOTTOM → TOP, in the document's own order. PSD stores them TOP FIRST, so the
     *   writer reverses; the reversal is pinned by a test, not left to the reader of the code.
     * @param includePaper composite the paper colour under everything for the merged image.
     * @throws PsdException if the canvas is larger than [MAX_DIMENSION], if a layer's rect does not
     *   fit inside [rect] in Long arithmetic, or if a tile is not [RegionRenderer.TILE_BYTES].
     *   Every one is a refusal in words, never a truncated or wrapped value.
     */
    fun write(
        out: java.io.OutputStream,
        doc: JbDocument,
        tiles: TileSource,
        rect: RectPx,
        layers: List<PsdLayer>,
        includePaper: Boolean,
    )

    /** The whole write as bytes. For the SAF "Save as" path. */
    fun toBytes(doc, tiles, rect, layers, includePaper): ByteArray
}

class PsdException(message: String) : Exception(message)

/**
 * The 4-character blend keys PSD understands, and nothing else in this file.
 *
 * This is a TABLE OF NAMES, not a second implementation of a mode: the arithmetic is
 * `Blend`/`BlendRgb`'s, already proven equal to the Studio's (JB-2.20a) and used by `RegionRenderer`
 * for every other exporter. A mode whose Photoshop meaning DIFFERS from ours is listed here with a
 * flag, and the writer says so rather than pretending (Decision 6).
 */
object PsdBlend {
    /** The key, or null for a mode with no PSD equivalent. */
    fun keyFor(mode: BlendMode): String?
    /** True when Photoshop's mode of this name is not the W3C definition this app implements. */
    fun isSubtractiveFamily(mode: BlendMode): Boolean
}
```

## Decisions

1. **8-bit, RGB, no alpha on the canvas, one composite at the end.** Depth 8, colour mode 3
   (`RGB`). The canvas itself is opaque (a PSD's background is the paper colour when
   `includePaper`, black when not — stated in the file, not invented per reader). Transparency
   lives in the per-layer alpha channel, which is what Photoshop actually uses.
2. **The composite (merged image) is produced by `RegionRenderer.render` — never by this writer.**
   Every exporter renders through that one function (JB-2.13a's whole point), so the PSD's flat image
   and the PNG's flat image are the same bytes by construction. This row adds a *container*, not a
   renderer.
3. **Per-layer pixel data is RLE-compressed (compression code 1), never raw.** A 256² RGBA layer is
   262 144 raw bytes per channel; a PSD of a dozen layers at raw is hundreds of megabytes and no
   phone writes that. RLE in PSD is **PackBits**, byte-for-byte the same algorithm as the TIFF
   variant, and **the row-length table is per channel, `2 + width` bytes each**, in the order the
   channel ids appear. Getting the table wrong is the classic "Photoshop says the file is corrupt"
   bug, so it is Test 3.
4. **Layer records are written TOP FIRST, so the input list is reversed.** `doc.layers` is
   bottom → top (`DocModel.kt:159`, the `val layers: List<Layer>, // bottom -> top` line — *not* 160,
   which is `activeLayerId`); PSD's `LayerRecords` start with the topmost. Asserted, not assumed.
5. **A layer's channels are `-1, 0, 1, 2` (alpha, R, G, B) and a layer with no pixels writes zero
   channels**, not a 0×0 rect. An empty layer is a real thing a person made.
6. **The blend-key table is a TABLE, and the two modes whose Photoshop meaning differs are FLAGGED,
   not corrected.** `PsdBlend`'s own KDoc says so, in the code, where the next reader of
   `keyFor(BlendMode.SUBTRACT)` will find it; this spec and **Questions** below repeat it once so it
   is not lost. That is all this row can do about it. Writing `"subtract"` for a mode whose
   implementation is `max(b - s, 0)` is **wrong in Photoshop's direction** (Photoshop's Subtract is
   `Cs/Cb` for the darkened pixels), and the honest answers are (a) write the key anyway and accept a
   different picture in Photoshop, or (b) refuse the mode in words. See Questions; the decision I have
   made is (a) with a loud KDoc, because a PSD that refuses to be written is worse than one whose two
   exotic modes differ. **This is a wrong-pixels decision and it is the Lead's (Q1).**
7. **`ERASE_BELOW` has no PSD key.** PSD has no per-layer "erase what is below"; Photoshop does it
   with a layer MASK (JB-2.23). `keyFor(ERASE_BELOW)` returns **null**, and the writer
   **refuses in words**: "this drawing has a layer in Erase Below mode, which PSD cannot express —
   export it flattened, or convert that layer." Two named alternatives, no silent NORMAL. (This is
   the "refuse rather than approximate" rule, applied to a format limit rather than to bad input.)
8. **INK layers are exported as an empty layer with a comment-free name and a `KDoc` note**, the
   same shape JB-2.14b took for OpenRaster (its Decision 6). They are not silently dropped and they
   are not rasterised (JB-5.01 does the raster, and rasterising here would be a second renderer).
   **The names are written in order so a person can see what was skipped.**
9. **The tests read the file back with a reader written for the tests** (`PsdReaderForTest.kt`, test
   source only, never in `main`). A writer tested only against itself proves nothing; a writer
   tested against an independent parser that walks the same published format proves the format.
   The reader is deliberately minimal — header, layer records, channel lengths, RLE-decompress,
   composite — and its RLE decoder is written from the PackBits description, not copied from the
   writer.
10. **All the size arithmetic is Long, and every extent is checked before it is used** (R19 — the
    lesson of `SpritePacker` and `SpriteGridMath`, where an `Int` overflow produced a *silently
    wrong cell* rather than a crash). `rect` over `MAX_DIMENSION` is a `PsdException` with the
    numbers in the message. A layer rect outside the canvas is a `PsdException`, not a clip.
11. **Layer names are Pascal strings, padded to a multiple of 4, truncated to 255 bytes, and
    truncated on a CHARACTER boundary** — a name cut mid-UTF-8 is a file some readers reject.
    Layer names longer than 255 bytes get the tail cut, never an exception: a name is not worth
    refusing a drawing over, and the truncation is stated in the KDoc.
12. **No `psd:` resource block beyond the minimum, and WHETHER `luni`/`lsct` IS WRITTEN IS NOT
    DECIDED HERE.** The minimum is the resolution: 72 dpi in the header's resolution info, 72 dpi in
    `psd:ResolutionInfo` if it is written at all. Everything else is left out rather than faked: a
    made-up resource block is one more thing that can disagree with the truth. **`luni`/`lsct` — the
    Unicode layer-name blocks — are Q5, and it is the Lead's.** The choice is real: write `luni` on
    every layer (so a name with an emoji survives the round trip, at the cost of a resource block
    nobody has asked for), write it only when the name needs it (a `when` in the writer that is a
    silent difference between two otherwise identical files), or write it never (Decision 11's 255
    byte Pascal truncation then genuinely loses characters, with nothing saying so). Test 11 already
    exercises an emoji name, so whichever answer comes back has a test waiting for it.

## Tests (`PsdWriterTest`, JVM)

1. **Header:** the file starts `8BPS`, version 1, six reserved zero bytes, channels ≥ 4, height then
   width **big-endian** and equal to the board rect, depth 8, colour mode 3. A 300 × 200 board with
   two layers: the reader reads back exactly those numbers. (Big-endian is asserted against a
   hand-computed byte at a known offset, not against the writer's own field.)
2. **Order:** with a document whose layers are `[bottom, middle, top]`, the reader's layer names
   come back `["top", "middle", "bottom"]` (Decision 4).
3. **RLE is not raw:** a 300-px-wide solid layer compresses to **far less** than
   `300 × 200 × 4` bytes, and the reader's PackBits decoder returns the original bytes **exactly**.
   And the row-length table's total equals the sum of the decompressed channel lengths — this is
   the "Photoshop says the file is corrupt" check.
4. **A gradient** (every pixel different) RLE-decompresses back byte-for-byte, and is *larger* than
   the same pixels stored raw — proving the writer did not fake compression with a short buffer.
5. **Opacity and visibility round-trip:** a layer at opacity 128 and `visible = false` reads back
   128 and hidden, and the merged image (composite) is computed by `RegionRenderer` — asserted by
   rendering the same document with `RegionRenderer` directly and comparing the composite's pixels to
   what the reader decoded. **That comparison is the "preview = export" property for the flat image,
   and it is the most valuable assertion in this file.**
6. **Blend keys:** every one of the 27 modes either has a 4-char key of the right length or is one
   of the named refusals — and a 28th mode is a **red test** (an exhaustive `when`, exactly as
   `Blend.kt`'s `term` does it), so the table cannot fall behind the enum.
7. **Refusals, in words:** a 30 001-px canvas, a layer rect outside the canvas, a 100-byte tile,
   and `ERASE_BELOW` all throw `PsdException` whose message **names the offending thing**, and the
   output stream has had **zero bytes written** (the exception is raised before the header).
8. **Long arithmetic:** a rect at `Int.MAX_VALUE` is refused, and the test says why `w * h` in Int
   would have wrapped (Decision 10).
9. **An empty layer** writes a record with zero channels and reads back with a name and a rect —
   not skipped, not a 0-byte blob (Decision 5).
10. **`includePaper` false vs true** changes the composite's pixels and nothing else in the file's
    layer section (byte-compare the layer-info section between the two runs).
11. **Names:** a 300-character name truncates to ≤ 255 bytes on a character boundary and the file
    still parses; a name with an emoji does not produce invalid UTF-8 (Decision 11).

**Command:** `./gradlew -p joybrush :androidkit:compileKotlin :androidkit:test` — BUILD SUCCESSFUL,
0 failures. **Do not pass `-Pjoybrush.androidJar=<path>`.** The property is real
(`androidkit/build.gradle.kts:19`) but it is a *fallback*: `findAndroidJar()` tries it, then
`$ANDROID_HOME`, then `$ANDROID_SDK_ROOT`, then `sdk.dir` in the repo's `local.properties`, newest
platform wins. A builder on a machine with an SDK configured passes nothing and it works; the
placeholder form above is not runnable as written and is therefore a spec that cannot prove its own
tests. (Cross-reviewer, 2026-09-29.)

## Owner check (Note 9)

Three layers, one hidden, one at 40 %: Export → PSD → open in Photoshop (or Krita, which reads PSD)
→ three layers, in the right order, the hidden one hidden, the 40 % one at 40 %, the composite
looking exactly like the screen. Save a 4000 × 3000 board → it exports. Save a 30 000-px board → it
says why not.

## Do not

- **Do not render anything.** `RegionRenderer` renders; this file writes a container (Decision 2).
  A second renderer in a PSD writer is how preview and export start disagreeing.
- Do not add a library dependency. It is a format; we write it.
- Do not implement ink rasterisation, layer masks, adjustment layers, 16/32-bit, CMYK, spot
  channels, or PSD's vector/pattern blocks. v1 is 8-bit RGB, paint layers, one composite.
- Do not "fix" the two divergent blend modes. Record them; the Lead rules (Decision 6).
- Never run gradle on the app build. **Only** `./gradlew -p joybrush …` is permitted, and only the
  `:core` / `:androidkit` tasks (ROADMAP §2 rule 2). Anything in `:app` or `joybrush-android/` is
  proved by the watcher's `build.log`, never by running it.

## Definition of done

- [ ] tests pass (paste)
- [ ] `git status --short` shows only owner-area files
- [ ] owner check noted
- [ ] committed `JB-2.14c: PSD export`
- [ ] ROADMAP row → 🟧 Built

## Stop rule

If anything here is ambiguous, or a claim about the landed code turns out to be false when you open
the file, **STOP**: write the question in *Questions* under a heading `for the cross-reviewer`, set
this row `⛔ Blocked`, commit, push, and take another task. In particular: **never invent a PSD
resource block, a version number or a key name to get past an unanswered question here.** A PSD that
Photoshop calls corrupt is worse than no PSD export, and a made-up key is exactly how that happens.
And never widen the owner area to edit `Blend.kt`, `RegionRenderer.kt` or `JbArchive.kt` — this row
writes a container and changes no arithmetic.

## Questions

_(Spec writer, `openrouter/stealth/space-bunny-alpha`, 2026-09-29.)_

### ⛔ for the cross-reviewer — why this is still Draft

Two of these are **format decisions a cross-reviewer may not take** (which four-character key a blend
mode gets, and what a resource block is), and one is a **contradiction between two Decisions** that
only the Lead can settle. A spec marked Ready while any of the three is open is a spec the builder
has to finish by guessing.

**Q4. ⛔ Decision 1 and Decision 2 contradict each other on the merged image, and a builder has to
pick.** Decision 1 says the canvas is opaque and *"black when not [includePaper]"*. Decision 2 says
the composite is `RegionRenderer.render`'s bytes and nothing else. `RegionRenderer.render` with
`paper = null` returns a **STRAIGHT RGBA8 image whose empty pixels have alpha 0** — transparent,
not black (`RegionRenderer.kt:207-219`: alpha 0 takes `k = 0` and stays `0,0,0,0`). So the two
readings of "the merged image" differ:
- **(a)** pass `paper = null` and write a merged image **with** an alpha channel (PSD colour mode 3
  allows 5 channels). Photoshop shows a transparent background, which is the truth and the honest
  answer — and it is also what the PNG exporter writes, so the two agree.
- **(b)** pass `paper = "#000000"` and write four opaque channels. Matches Decision 1's words.
- **(c)** pass the document's `doc.paper.color` **always**, so "include paper" only ever means
  "what is under it" and the merged image is never transparent.
Which one is a colour decision and a format decision, so it is not mine to make and not a
cross-reviewer's either. **Test 2, Test 5 and Test 10 are all waiting on this answer** — Test 10
byte-compares the layer section between the two runs and would pass under all three, which means it
cannot tell them apart, so whichever is chosen needs a **new** assertion on the composite's alpha,
not just the existing byte-compare.

**Q5. ⛔ Is `luni` (the Unicode layer-name resource) written at all?** Decision 12 used to say "and a
`luni`/`lsct` name block *if the name needs Unicode*" — a condition with no owner. See Decision 12 for
the three real answers and what each costs. This is a **resource-block decision**: the brief I work
under says explicitly *do not invent a resource name*, so I have not chosen. Test 11's emoji name
already exercises the path.

**Checked and found TRUE — the claims I could verify, so nobody re-checks them** (all against the
tree, 2026-09-29):

| This spec says | The tree says |
|---|---|
| `doc.layers` is bottom → top (`DocModel.kt:160`) | True — but the line is **159**, not 160. Corrected in Decision 4. |
| 27 blend modes, one with no Studio counterpart | True. `DocModel.kt:124-131`: 8 + 19 = 27; `ERASE_BELOW` is Joy Brush's own (ordinal 7) and `ERASE_BELOW` is the one Decision 7 refuses. |
| `BlendMode.SUBTRACT` is `max(b - s, 0)` and `DIVIDE` is `min(b / max(s, 1e-5), 1)` (the W3C forms) | Consistent with what JB-2.20a's `BlendRgb` landed as, and with the Studio's `GLSL_BLEND_FN` per that row's own report. I did not re-derive the shader text — it lives in the app module and is not this row's to read for a claim. |
| `RegionRenderer.TILE_BYTES` exists | True. `RegionRenderer.kt:176` = `TILE_SIZE * TILE_SIZE * 4` = 262 144. There is a second `TILE_BYTES` at `JbArchive.kt:31`; import the one you name in the contract and say which in a comment. |
| `PngWriter.encode(width, height, rgba, compressionLevel = 6): ByteArray` | True (`PngWriter.kt:85`), and the byte order the PSD writer needs is what `RegionRenderer.render` returns: **straight** RGBA8, row 0 = top. PSD wants the same. |
| `LayerKind { PAINT, INK }` | True (`DocModel.kt:92`). |
| `-Pjoybrush.androidJar` is how `androidkit` finds the SDK | True but it is a *fallback*, not a requirement — corrected in Tests. |

### 🔴 For the Lead — the two blend modes where Photoshop and this app mean different things

1. **`SUBTRACT` and `DIVIDE`.** JB-2.20a Decision 1 pins Joy Brush's `SUBTRACT` to
   `max(b - s, 0)` and `DIVIDE` to `min(b / max(s, 1e-5), 1)` — the **W3C** definitions, and the
   Studio's `GLSL_BLEND_FN` says the same (`max(b - s, vec3(0.0))`,
   `min(b / max(s, 0.00001), vec3(1.0))`). **Photoshop's "Subtract" and "Divide" are not those**:
   they are *relative* to the backdrop and can push channels negative or above 255 before clipping.
   So a Joy Brush layer exported as PSD Subtract will composite differently in Photoshop than it
   does here and in every other Joy Brush export. R23 makes the Studio the authority, and I am not
   going to change `BlendRgb` to match Photoshop. **Three options, and I need one:**
   (a) write `"subtract"`/`"divide"` and accept a different picture in Photoshop (what I have
   specified, with the KDoc saying so);
   (b) **refuse the mode in words** at export time, like `ERASE_BELOW` — honest, and it means a
   document using either cannot be exported to PSD at all;
   (c) write the closest non-divergent key (`"darken"`/`"multiply"`), which is a *different picture*
   and therefore worse than (a).
   I have chosen (a) because the alternative that loses data is (c) and the alternative that blocks
   a person is (b), and the divergence is two modes out of 27 on one format. **But this is a wrong-
   pixels decision and it is yours.**
2. **`ERASE_BELOW` refused (Decision 7).** Confirm the refusal, and confirm the two alternatives I
   name in the message ("export it flattened, or convert that layer") are the two you want offered —
   I have not specified how either is done, because "flattened" is a render and this row does not
   render (Decision 2). If you want a flattened option, it is a two-line branch here
   (`layers = emptyList()` + the composite), and I would rather add it than have a person told no.
3. **INK layers are exported as EMPTY layers (Decision 8).** JB-2.14b took the same decision for
   OpenRaster, so the two exports agree. But an empty layer in Photoshop is a thing a person may
   delete without knowing why. Should ink layers be **omitted entirely** instead (their absence is
   less confusing than an empty layer), or is the visible empty layer the better warning?

### Low-risk, ruled provisionally

4. **The merged composite is `RegionRenderer`'s bytes** (Decision 2), asserted by test 5.
5. **Names truncate, they never refuse** (Decision 11).
6. **An empty layer is written with zero channels, not skipped** (Decision 5).
7. **The test-only reader is test source and never in `main`** (Decision 9) — a reader in `main` is a
   second thing to maintain and a second thing to be wrong about.
