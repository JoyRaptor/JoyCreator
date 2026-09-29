# JB-8.02 — Import Procreate `.brush` / `.brushset` brushes

| | |
|---|---|
| **Tier** | T2 |
| **Status** | 🟦 Ready |
| **Who** | spec writer (unattributed in the original) · **xr: openrouter/stealth/space-bunny-alpha 2026-09-29** — verified `GRAIN_SOURCES`/`TIP_SOURCES` accept `"image"` while nothing in the tree reads one; verified `JbArchive.unsafeReason` is **private** and lives in `androidkit`, so Decision 12's "the same set" is a restatement across a module boundary `commonMain` cannot see; verified `PngWriter.encode(width, height, rgba, compressionLevel = 6): ByteArray` exists (`androidkit/.../io/PngWriter.kt:85`); verified `BrushValidate.MAX_CURVE_POINTS`/`MAX_INPUTS` are `private const` at `BrushValidate.kt:31`/`:28`; corrected this row's own `shapeRoundness` mapping, which pointed at `tip.aspect` as if it took a curve.<br>**xr: openrouter/stealth/space-bunny-alpha 2026-09-29 (R40 pass)** — every blocker this file carried is now **ruled**, and the spec is executable. R40 settled the image question (option (a), uniform) so Q6 is **answered** and the R40 paragraph is now byte-identical to the one in JB-8.01 and JB-8.04; R40 killed the hand-written `Inflate.kt` (Q1) in favour of `expect`/`actual`, so the owner area, Decision 3, Steps and the test source sets are all rewritten; R40 ruled the taper family LOSSY with a warning (Q3) and made the set name a **prefix** (Q5). `tip.image = "shape.png"` is fixed to `"tip.png"`: `shape.png` is a path *inside the `.brush` archive*, and `TipSpec.image` is documented as a path **inside the brush folder** (`BrushPreset.kt:34`) — a folder that does not exist yet and that `commonMain` cannot write. Also verified against the landed source that `core` is a KMP module whose **only enabled target is `jvm()`** (`core/build.gradle.kts:14`), which is why `expect`/`actual` needs no build-file edit, and that the three structural test files must split across `commonTest` and `jvmTest` because a fixture that needs `java.util.zip` cannot live in `commonTest`. |
| **Needs** | JB-0.03 (brush format + validation), **and JB-8.01** — see "Sequencing", which is not decoration: this file will not compile until JB-8.01 lands. |
| **Owner area** | NEW `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/brush/imports/ProcreateImport.kt`, NEW `.../imports/KeyedArchive.kt` (NSKeyedArchiver + XML-plist reader), NEW `.../commonMain/.../imports/Inflate.kt` (**`expect` only** — R40), NEW `joybrush/core/src/jvmMain/kotlin/cc/joycreator/joybrush/core/brush/imports/Inflate.jvm.kt` (**`actual` on `java.util.zip.Inflater`** — R40), NEW `joybrush/core/src/commonTest/.../brush/imports/KeyedArchiveTest.kt`, NEW `joybrush/core/src/jvmTest/.../brush/imports/InflateTest.kt`, NEW `joybrush/core/src/jvmTest/.../brush/imports/ProcreateImportTest.kt`; `ImportSupport.kt` **only if absent** (JB-8.01's Decision 2). **NOT** `core/build.gradle.kts` — see Decision 3. **NOT** any `androidkit` file: R40 put the zip reading in `core`, not in `JbArchive`. |
| **Estimated size** | ~250 lines `Inflate.jvm.kt`, ~400 lines `KeyedArchive.kt`, ~650 lines `ProcreateImport.kt`, ~450 lines of tests |
| **Command** | `./gradlew -p joybrush :core:jvmTest` — 0 failures |

## Sequencing — the one thing to read before dispatching this

**This row does not compile until JB-8.01 has landed, and that is a fact about the tree, not a
preference.** It reads six things that only exist after JB-8.01:

| Read | Where it lives today | Who makes it |
|---|---|---|
| `BrushValidate.MAX_INPUTS` | `private const val` — `BrushValidate.kt:28` | **JB-8.01** (two words: `private` → `public`) |
| `BrushValidate.MAX_CURVE_POINTS` | `private const val` — `BrushValidate.kt:31` | **JB-8.01** (two words) |
| `ImportLibrary`, `RefusedBrush`, `summary()` | does not exist | **JB-8.01** (creates `ImportSupport.kt`) |
| `ImportSupport.MAX_EXTENSION_BYTES` | does not exist | **JB-8.01** (R40) |
| `ImportSupport.TEXTURE_NOT_DRAWN` | does not exist | **JB-8.01** (R40) |
| `ImportSupport.brushId` | does not exist | **JB-8.01** (R23) |

The board's *Needs* column for this row says `0.03` only, and that is **wrong**; the orchestrator's
change is in the report that came with this spec, not in this file. **Dispatch order is
JB-8.01 → (this row | JB-8.04), never two at once** — they share `ImportSupport.kt`.

## Goal

R4 §F ranks Procreate second: "a huge marketplace, and most iPad artists moving to Android own
packs." The owner's brief asks for it outright — *"Import of popular brush packs (Photoshop etc.)
wanted if at all possible."*

A `.brush` is a **zip** holding a binary plist (`Brush.archive`, an NSKeyedArchiver), `Shape.png`,
`Grain.png`, and sometimes a `Sub01/` dual brush. A `.brushset` is a zip of UUID folders plus
`brushset.plist`. Two things have to exist before any parameter is read: **a zip/deflate reader** and
**an NSKeyedArchiver reader**, neither of which this project has. This spec owns both.

## THE Decision this row exists to get right

**What happens to input the reader cannot express.** Same three buckets as JB-8.01, and Procreate is
where it bites hardest, because R4 §B.2 is blunt: *"many value scales are not [known] and many brushes
reference Procreate's built-in shapes and grains, which are not in the file and cannot legally be
shipped."*

So the buckets are:

| Bucket | Procreate cases | Where it goes |
|---|---|---|
| **MAPPED** | shape PNG presence, spacing, scatter, count, flip jitter, roundness/angle, pressure & tilt size/opacity curves, colour jitter, grain depth/scale/brightness/contrast/invert, blend mode, `smoothing`, dual-brush presence | the `BrushPreset` field |
| **LOSSY** | any **≈ scale** R4 marks unverified; the rendering modes; the wet-mix family; moving grain; the taper family | closest field, **warning naming the setting and that the scale is unverified**, **raw value in `extensions`** |
| **REFUSED** | a brush whose `bundledShapePath` (or `bundledGrainPath`) names a **Procreate built-in** (the file has no image for it and R4 §5 forbids shipping Procreate's assets) | that brush, with a sentence naming the missing resource |

And the rule the JB-8.03 review produced: **an unverified scale must be a warning on the first import
of a pack and not on every brush** — a warning that fires 200 times trains people to ignore warnings,
which was the review's own Finding 2.

Two more house rules this format forces:

- **Legal.** R4 §5: *"Never extract or ship the Shape/Grain images from Procreate's default library.
  Substitute original or CC0 lookalikes."* So this importer **never substitutes a lookalike and never
  extracts a built-in**; it refuses the brush and says which resource is missing. A future CC0
  lookalike library is a separate, separately-licensed task (Question, referred).
- **Redistributing is not ours.** R4 §5 / blueprint §5: importing a file the user owns is fine;
  shipping converted third-party packs is not. So `license = "unknown"` unless the archive says
  otherwise, `author` from `authorName` when present, `sourceFormat = "procreate"`.

## Contract (verbatim)

```kotlin
package cc.joycreator.joybrush.core.brush.imports

object ProcreateImport {
    /**
     * One `.brush` (a zip) → its brushes.
     *
     * @param idPrefix the caller's library prefix; ids are `ImportSupport.brushId(idPrefix, name)`.
     * @throws BrushException if the FILE itself cannot be read (not a zip, no `Brush.archive`, a
     *   section that overruns). One bad brush inside is never a `BrushException` — it is a
     *   `RefusedBrush`.
     */
    fun convertBrush(bytes: ByteArray, idPrefix: String): ImportLibrary

    // R44 item 5 — the distinction between the two entry points, which this spec did not state and
    // which the landed code got right. `convertBrush` THROWS a `BrushException` on a BRUSH-LEVEL
    // fault: the bytes are not a Procreate brush at all, the archive is not a zip, an entry is
    // truncated, a DEFLATE stream does not inflate. Those are faults in the FILE, and a single-
    // brush call has nothing to continue to - the honest answer is to fail loudly. A fault that
    // belongs to ONE PRESET inside an otherwise sound pack is never an exception in either entry
    // point; it becomes a `RefusedBrush` and the rest of the pack survives, which is what the
    // sentence above the signature says. So: `convertBrush` throws where `convertBrushSet`
    // refuses, and the dividing line is "is the fault in the container or in one of its contents?"
    // A caller that wants the forgiving behaviour for a single brush must go through
    // `convertBrushSet` with a one-brush folder, or catch - not silently assume it.
    //
    /**
     * A `.brushset` (a zip of UUID folders) → every brush inside, in `brushset.plist` order, and
     * **every preset's `name` is the set's name, then ` · `, then the brush's own name** (Decision 16).
     */
    fun convertBrushSet(bytes: ByteArray, idPrefix: String): ImportLibrary
}
```

`ImportLibrary`, `RefusedBrush`, `summary()`, `MAX_EXTENSION_BYTES`, `TEXTURE_NOT_DRAWN` and
`brushId` are **JB-8.01's** (its Decision 2). The whole file is pasted, verbatim, in the Contract
section of `JB-8.01_photoshop_abr_import.md`; **if `ImportSupport.kt` exists when you start, it is
already correct and you do not create it.** You call `ImportSupport.brushId(idPrefix, name)` for every
id. You never write your own sanitiser.

### `Inflate` — `expect`/`actual`, R40

```kotlin
package cc.joycreator.joybrush.core.brush.imports

/**
 * Inflate a **raw DEFLATE stream (RFC 1951)** — the payload of one zip entry, with no zlib and no
 * gzip wrapper around it. A `.brush` entry carries the zlib two-byte header; `ProcreateImport` reads
 * and checks that header itself and hands this function the bytes after it (Decision 5).
 *
 * @throws BrushException if the range is outside [deflate], if the stream is malformed or ends early,
 *   if the output would pass [maxOut], or if any input byte is left unread after the end of the
 *   stream. Never return a short result: a truncated image is a file that is not what it says.
 */
internal expect fun inflateRaw(deflate: ByteArray, offset: Int, length: Int, maxOut: Int): ByteArray
```

The JVM `actual`, in `core/src/jvmMain/kotlin/cc/joycreator/joybrush/core/brush/imports/Inflate.jvm.kt`:

```kotlin
package cc.joycreator.joybrush.core.brush.imports

import cc.joycreator.joybrush.core.brush.BrushException
import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Inflater

internal actual fun inflateRaw(deflate: ByteArray, offset: Int, length: Int, maxOut: Int): ByteArray {
    if (offset < 0 || length < 0 || offset.toLong() + length > deflate.size) {
        throw BrushException("deflate range $offset..${offset + length} is outside ${deflate.size} bytes")
    }
    if (maxOut < 0) throw BrushException("maxOut $maxOut is negative")
    val inflater = Inflater(true)          // nowrap = true: the caller owns any zlib header
    try {
        inflater.setInput(deflate, offset, length)
        val out = ByteArrayOutputStream(minOf(maxOut, 64 * 1024).coerceAtLeast(64))
        val chunk = ByteArray(64 * 1024)
        var total = 0
        while (!inflater.finished()) {
            val n = try {
                inflater.inflate(chunk)
            } catch (e: DataFormatException) {
                throw BrushException("deflate stream cannot be read: ${e.message}")
            }
            if (n == 0) throw BrushException("deflate stream ends early after $total bytes")
            total += n
            if (total > maxOut) throw BrushException("deflate stream is over the $maxOut byte limit")
            out.write(chunk, 0, n)
        }
        if (inflater.remaining != 0) {
            throw BrushException("deflate stream has ${inflater.remaining} bytes unread after its end")
        }
        return out.toByteArray()
    } finally {
        inflater.end()
    }
}
```

**That is the whole implementation.** `java.util.zip.Inflater` is JVM, `androidkit` already uses
`java.util.zip` in `JbArchive.kt:17-20`, and an iOS `actual` on zlib (`import Compression`) is a later
target's one function — which is the whole point of the `expect`.

**No build-file edit is needed, and that is worth being sure of:** `core` is a Kotlin Multiplatform
module whose only enabled target is `jvm()` (`core/build.gradle.kts:14-17`, with the file's own
comment: *"Only the JVM target is enabled today; js() and the iOS targets are added when a client
needs them"*), and a KMP target creates its `jvmMain`/`jvmTest` source sets by convention, so putting
files under `core/src/jvmMain/kotlin/` is enough. **If the JVM `actual` is not picked up and the build
says the `expect` has no `actual`, that is the stop-rule trigger and `core/build.gradle.kts` is the
Lead's file, not yours.**

## The `.brush` layout (R4 §B.2, restated so no one has to go and look)

```
*.brush      = zip {
    Brush.archive                  binary plist, NSKeyedArchiver ($objects array, root = $objects[1])
    Shape.png                      the tip bitmap (absent when bundledShapePath names a built-in)
    Grain.png                      the texture bitmap (absent when bundledGrainPath names a built-in)
    QuickLook/Thumbnail.png         ignored
    Sub01/                         the DUAL brush: its own Brush.archive + Shape + Grain
}
*.brushset   = zip { <uuid>/{Brush.archive,…}, … , brushset.plist }
```

Curves are arrays of `"{x, y}"` **strings**; a `*Curve` key pairs with a scalar amount.

## R40 — THE ONE RULE ALL THREE PHASE 8 SPECS NOW SHARE

*(LEAD_RULINGS R40, option (a), uniform for JB-8.01, JB-8.02 and JB-8.04. This paragraph is
byte-identical in all three spec files; if you find a word changed in one of them, that is a bug.)*

**An importer stores image tips and image grain, sets `source = "image"`, and says so out loud.**
When a file carries a bitmap for the tip or for the grain, this importer **stores the bytes** —
base64, in `extensions`, under this row's own key — **sets `tip.source = "image"` (or
`paperGrain.source = "image"`)**, points `tip.image` (or `paperGrain.image`) at a **file name inside
the brush folder**, and adds a warning containing, word for word, **`this brush's texture is kept but
not drawn yet`**. Nothing in the engine samples an image tip or an image grain today — there is no
image reader anywhere in `joybrush/` (`javax.imageio.ImageIO` appears in `jvmTest` and `androidkit`'s
tests only, as an oracle) and `jb_dab.frag` samples no texture — while `BrushValidate` rule 23 checks
only that the path is **non-blank**, so a preset can validate clean, name a file nobody wrote, and
draw as procedural. **The warning is the only thing standing between a person and that.** It is not
optional; it is not a per-file warning; it is one per brush that carried the bitmap. The condition is
**JB-1.05d** (per-brush image tips and grain, `⚪ Outline` on the board) landing; when it does, the
warning goes and the writer that puts the bytes beside `brush.json` comes with it.

Two consequences that are the same in all three rows:

1. **The stored image never replaces the numbers.** A brush with an image tip still gets a real
   `size.base` — a diameter in px, so it paints at the right size today — and the bitmap is
   *additional*. "Stored" means kept for JB-1.05d, not drawn. *How the diameter is read is the row's
   own business: this row takes the `samp` bitmap's own width, JB-8.02 and JB-8.04 take a PNG's `IHDR`
   width, which is four big-endian bytes at a fixed offset and is not decoding the image.*
2. **The file name names the encoding of the stored bytes, never the foreign file's name.**
   `TipSpec.image` is *"path inside the brush folder when source == image"* (`BrushPreset.kt:34`), and
   a brush folder is `<folder>/brush.json` (`BrushLibrary.kt:12-14`, `BrushHotReload`'s `BRUSH_FILE`),
   so the value is a bare file name beside that `brush.json` — and its extension says what the bytes
   are, because JB-1.05d will be handed three encodings if nobody says so now.
   * **This row:** the shape and grain really are PNG files in the archive, so the names are
     **`tip.png`** and **`grain.png`** — and *not* `shape.png`, which is a path inside the foreign
     `.brush` archive and a path no brush folder will ever contain.
   * **JB-8.01** writes `tip.packbits` (PackBits gray) and **JB-8.04** writes `tip.png` (a real PNG).

The alternative — a procedural stand-in and no stored bytes — was considered for all three rows and
**refused for all three**. It loses the artist's actual tip, and refusing the brush instead refuses
most of Procreate and much of Revoy's Krita pack.

**When a file carries a texture Joy Brush cannot put a name on, the warning is still this one.** The
sentence is the same whether the texture was stored, could not be stored, or was never an image: this
row's built-in grain refusal (Decision 8), JB-8.01's Photoshop `patt` pattern (its Decision 5) and
JB-8.04's texture modes (its Decision 12) all emit it. A reader must be able to grep the three
importers for one string.

## Decisions — the mapping

1. **MAPPED with the scale stated in the test.** Every mapping below is pinned by a test with its
   derivation, per R9's standing rule ("an expected value in a test may be changed ONLY with its
   derivation written into the test").

| Procreate | Key | Joy Brush | Scale |
|---|---|---|---|
| Shape is an image | `Shape.png` present | `tip.source = "image"`, `tip.image = "tip.png"`, bytes in `extensions["procreate.tipImage"]` | — R40 (Decision 3) |
| Shape size | `maxSize`, else the PNG's `IHDR` width | `size.base` | as stored; clamped to `BrushValidate.MAX_SIZE_PX`, warn |
| Shape rotation / follow stroke | `shapeRotation` | `tip.followDirection` = `v > 0` | boolean, unverified |
| Shape roundness | `shapeRoundness` | `tip.aspect` — **a bare `Float` with no `inputs` and no `base`** (`BrushPreset.kt:37`) | as stored, clamped to `-1f..1f`, warn |
| Shape angle | `shapeAngle` | `tip.angle.base` (`tip.angle` **is** a `Param`) | degrees, as stored |
| Scatter | `plotJitter` | `scatter.amount` | **≈** R4: unverified scale; warn once per pack |
| Count / count jitter | `shapeCount`, `shapeCountJitter` | `scatter.count` (1..16), `scatter.countJitter` | as stored |
| Flip jitter | `shapeFlipXJitter/YJitter` | `extensions` (no flip field) | **LOSSY**, warn |
| Spacing | `plotSpacing` | `spacing` | **≈** R4 B.2 notes p2k guesses `sqrt(v·100)/10`; warn once |
| StreamLine | `plotSmoothing` | `smoothing` | **≈** |
| Pressure size | `dynamicsPressureSize(+Curve)` | `size.inputs[pressure]` multiply | curve points parsed |
| Pressure opacity | `dynamicsPressureOpacity(+Curve)` | `opacity.inputs[pressure]` multiply | curve points parsed |
| Tilt size / opacity | `dynamicsTiltSize`, `dynamicsTiltOpacity` | `size/opacity.inputs[tilt]` multiply | **≈** |
| Grain image | `Grain.png` present | `paperGrain.source = "image"`, `.image = "grain.png"`, `.enabled = true`, bytes in `extensions["procreate.grainImage"]` | — R40 (Decision 3) |
| Grain depth / min | `grainDepth`, `grainDepthMinimum` | `paperGrain.depth` | as stored |
| Grain scale | `textureScale` | `paperGrain.scale` (0 < v ≤ 64) | **≈** |
| Brightness / contrast / invert | `textureBrightness`, `textureContrast`, `textureInverted` | folded into the depth curve / `extensions` | **LOSSY**, warn (R4: contrast is −1…1) |
| Colour jitter | `dynamicsJitterHue/Saturation/Lightness` | `color.hue/saturation/value`, `perStroke = true` | **≈** — the magnitude decides, see Decision 3a |
| Blend mode | `blendMode` | `blend` when it is one of `normal`/`erase`/`behind` | **LOSSY** for any other value, raw kept |
| Taper family | `pencilTaper*`, `taper*` | **no field** — `extensions["procreate.taper"]` + a warning | **LOSSY** (Decision 9) |
| Dual brush | `Sub01/` present | `extensions["procreate.dual"]` + a warning | **LOSSY** (no dual/mask-tip field) |
| Author | `authorName` | `author` | — |
| Licence | any field naming one | `license`; else `"unknown"`, **never `"CC0"`** | — |

2. **`ImportLibrary`, `RefusedBrush`, `summary()`, `MAX_EXTENSION_BYTES`, `TEXTURE_NOT_DRAWN` and
   `brushId` live in `ImportSupport.kt` and belong to JB-8.01.** Create it only if absent; otherwise
   read it. Never a second copy (R23), never a second id sanitiser.
3. **R40's image decision, as this row carries it.** `Shape.png` / `Grain.png` are read out of the zip
   **as bytes** (no PNG decoding — `PngChunks` is JB-8.04's and is not in this row), base64'd into
   `extensions["procreate.tipImage"]` / `["procreate.grainImage"]`, and the sources set to `"image"`
   with `tip.image = "tip.png"` / `paperGrain.image = "grain.png"`. **The warning is
   `ImportSupport.TEXTURE_NOT_DRAWN` by value**, not a retyped literal. `size.base` is `maxSize` when
   the archive has one, else the PNG's `IHDR` width (bytes 16..24, big-endian — a header read, not a
   decode). A texture image whose base64 would push `extensions` past
   `ImportSupport.MAX_EXTENSION_BYTES` is **not stored at all** (never truncated),
   `paperGrain.source` stays `"cloud"`, `paperGrain.enabled` stays `false`, and the warning still
   carries `TEXTURE_NOT_DRAWN` plus the byte count and the cap. *(PROVISIONAL — Claude to confirm;
   identical wording in JB-8.01's Decision 4a, because it is the same decision.)*
3a. **A colour jitter whose scale the archive does not state is read by its own magnitude, and says
   so.** `color.hue` / `.saturation` / `.value` are ranged `0f..1f` by `BrushValidate` rule 15, and the
   Procreate scale is not written down anywhere in this tree (R4 §B.2 lists colour jitter among the
   **"Clean"** *mappings* — `R4_photoshop_and_formats.md:388` — while its fidelity table marks the
   values `≈` at `:893`; nothing states the stored range). So, for a raw jitter `v`:
   * `v == 0f` → `0f`, **no warning** — both readings agree.
   * `0f < v <= 1f` → `v`, **unresolved**; the raw goes to `extensions["procreate.dynamicsJitter…"]`
     and the field is named in the **one** per-pack warning (Decision 10).
   * `v > 1f` → `v / 100`, clamped to `1f`; the field is named in the same per-pack warning, and a
     per-brush clamp warning names the field and both numbers if the divide still left it above 1.

   **The rule is identical to JB-8.01's, word for word, and the four tests are the same four.** A
   blind `/100` is the failure this replaces: on an archive that stores 0..1 it turns a real 0.5
   jitter into 0.005, which is still inside `0f..1f`, so rule 15 says nothing and **no warning can
   fire.**
4. **Every budget is a named constant and every one refuses** (the numbers and the tests are in the
   table in Decision 11).
5. **A zlib wrapper is checked, then skipped, then bounded.** `.brush` zips carry a 2-byte zlib header
   (`CMF`/`FLG`, `0x78 0x9C` and family) before the DEFLATE stream. Check `CMF`'s low nibble is `8` and
   that `((CMF shl 8) + FLG) % 31 == 0`; a header that fails is a refusal naming the two bytes. Then
   `inflateRaw(payload, offset + 2, size - 2, maxOut = min(declaredSize, MAX_INFLATED_BYTES))`. The
   declared uncompressed size is **not believed**: a declared size larger than the cap refuses before
   inflating, a stream that inflates to more than the cap is refused by `maxOut` (never truncated), and
   trailing bytes after the DEFLATE stream are refused by the `remaining` check. **Entry 0 is
   conventionally STORED** (method 0), which is a real case: a STORED entry is copied, not inflated.
6. **A flat XML `Brush.archive` is accepted.** R4 §B.2: "Some Brush.archives are a flat XML plist with
   only name, identifier and author (brushkit issue #152)." So the reader sniffs: a bplist magic
   (`bplist00`) → NSKeyedArchiver; `<` → XML plist; anything else → **refuse that brush** with a
   sentence naming what was found. A brush with only a name and an author **converts** (to a default
   stamp tip) and says so in a warning — it is a real brush, just an empty one.
7. **A UID that points outside `$objects` refuses that brush.** NSKeyedArchiver resolves UIDs against a
   `$objects` array; a dangling UID is a corrupt archive, and reading it would be reading whatever
   happened to be at that index. Refuse in words, name the UID.
8. **Built-in resources REFUSE the brush (Decision: the legal rule).** `bundledShapePath` non-null with
   no matching `Shape.png` → refused: *"needs Procreate's built-in shape X, which Joy Brush does not
   ship and may not extract (R4 §5)."* `bundledGrainPath` with no `Grain.png` → refused the same way,
   and the reason **also** carries `ImportSupport.TEXTURE_NOT_DRAWN`, so a grep for the R40 sentence
   finds the brushes refused for a texture as well as the brushes that stored one. **Never** a
   substitute, **never** an empty tip.
9. **The taper family is LOSSY with a warning (R40).** `pencilTaperStartLength`, `pencilTaperEndLength`,
   `pencilTaperSize`, `pencilTaperOpacity`, `taperStartLength`, `taperEndLength`, `taperSize`,
   `taperOpacity` and their `*Shape`/`*Linked` siblings go to `extensions["procreate.taper"]` as the
   raw key/value pairs, with **one warning per brush** naming the family. **They do not go into
   `tip.taper`**, which is a *shape* taper: R4 §C says so explicitly (*"Photoshop has no taper tip
   shape. Procreate 'taper' means stroke-end size and opacity taper, a different thing. Keep both."* —
   `R4_photoshop_and_formats.md:577`). **R40 ruled this LOSSY, not REFUSED**, and the reason is in the
   ruling: refusing taper refuses most of Procreate, and the brush format has no stroke envelope to
   refuse *into*.
10. **The unverified-scale warning is per PACK, not per brush, and there is exactly ONE such warning.**
    One sentence per `convertBrush` / `convertBrushSet` call naming every field whose scale is
    inferred: the `≈` rows of Decision 1 **and** every colour-jitter field whose scale Decision 3a
    could not resolve. **JB-8.03's review's Finding 2 is a test here**, and "exactly one" is a number
    the test can assert. Per-brush warnings are reserved for facts that really are per-brush.
11. **Budgets (every one a refusal, one test each).** Every cap below is a refusal naming itself:

| Budget | Cap |
|---|---|
| `MAX_ARCHIVE_BYTES` | 256 MiB (a `.brushset` is a pack) |
| `MAX_ENTRIES` | 4 096 |
| `MAX_ENTRY_BYTES` | 64 MiB |
| `MAX_ENTRY_NAME_CHARS` | 512 |
| `MAX_INFLATE_RATIO` | 200:1 (a zip bomb is refused **before** it is inflated) |
| `MAX_INFLATED_BYTES` | 64 MiB per entry |
| `MAX_OBJECTS` | 200 000 (`$objects` entries) |
| `MAX_DEPTH` | 32 |
| `MAX_KEYS_PER_DICT` | 20 000 |
| `MAX_STRING_CHARS` | 64 Ki |
| `MAX_BRUSHES` | 2 048 |
| `MAX_EXTENSION_BYTES` | **not declared here** — `ImportSupport`'s (Decision 2) |

12. **An entry name is never trusted** — `..` anywhere, a leading `/`, a backslash, a colon, a control
    character and every empty segment are refused, on **read and write**, because a hostile `.brush`
    must not be able to write an escaping name either. (`JbArchive`'s `unsafeReason` discipline,
    applied a second time; a shared constant is never copied.)
13. **A shared constant is never copied.** Curve caps are `BrushValidate.MAX_INPUTS` /
    `MAX_CURVE_POINTS` (**public since JB-8.01** — see Sequencing). Everything else Procreate-specific
    lives here.
14. **Everything ends up validate-clean or absent** (JB-8.01's Decision 11): each value clamped with
    its own warning naming the field and both numbers, and anything still left over refuses the brush
    with the validator's sentence. **R40 is not an exception:** `"image"` is an accepted word
    (`BrushValidate.kt:20-21`) and rule 23 is satisfied by a non-blank path, so an image tip validates
    clean *and is not drawn* — which is exactly why the warning is not optional.
15. **`.brush` inside a `.brushset` is imported as a `.brush`.** One code path, one set of warnings.
    `brushset.plist` gives the order; a missing or unreadable one imports every folder found in **name
    order** and warns once.
16. **The set's name prefixes every brush's name (R40).** In `convertBrushSet`, a preset's `name` is
    `"<set name> · <brush name>"` with U+00B7 MIDDLE DOT and one space each side, e.g.
    `"My Pack · Round Hard"`. The set name comes from `brushset.plist`; when it is missing or blank the
    brush's own name stands alone and **one** warning per pack says the set named nothing. A single
    `.brush` opened on its own gets **no** prefix — there is no set. `id` is
    `ImportSupport.brushId(idPrefix, name)` on the **prefixed** name, so ids stay unique across three
    packs that both contain a "Round Hard". *(PROVISIONAL — Claude to confirm: the *prefix* is R40's
    ruling; the separator and the missing-set fallback are a naming default, one edit.)*

## Steps

1. **Confirm JB-8.01 has landed** (Sequencing). If `BrushValidate.MAX_CURVE_POINTS` is still
   `private`, stop and say so — do not work around it by declaring your own 64.
2. Tests first: `KeyedArchiveTest` in **`commonTest`** (a bplist and an XML plist are hand-writable
   with no JVM), then `InflateTest` and `ProcreateImportTest` in **`jvmTest`** (their fixtures need
   `java.util.zip` to *build* a real archive — see the Tests section for why those two are `jvmTest`
   and this one is not).
3. `Inflate.kt` (`commonMain`, `expect`) and `Inflate.jvm.kt` (`jvmMain`, `actual`) — exactly the two
   blocks in the Contract section. Nothing else goes in either file.
4. `KeyedArchive.kt` — bplist v0 (`bplist00`): the object table, `UID` resolution against `$objects`,
   `$top`, plus a small XML-plist reader for Decision 6. Entities limited to `&amp;` `&lt;` `&gt;`
   `&quot;` `&apos;` `&#NNN;`; **no DTD, no external entity, no network** — a `.brush` is a
   stranger's file and XXE is the first thing that comes to mind.
5. `ProcreateImport.kt` — budgets, zip walk, sniffing, per-key mapping, buckets, warnings. Write the
   R40 store and the `TEXTURE_NOT_DRAWN` warning **while you are on the tip row**, not at the end: it
   is the row every R40 test touches, and it is the row a builder will think is finished.

## Tests

**Fixtures, and why the test files are in two different source sets.** `commonTest` is compiled for
every target and cannot see `java.*`; `jvmTest` is the JVM test source set and can. A test whose
*fixture builder* needs `java.util.zip` therefore belongs in `jvmTest` — that is the mechanical error
the Lead's review keeps finding, in this direction. Concretely:

| Test file | Source set | Why |
|---|---|---|
| `KeyedArchiveTest.kt` | `commonTest` | a bplist and an XML plist are both hand-writable byte for byte; no JVM needed, and this reader is the one that has to survive an iOS target |
| `InflateTest.kt` | `jvmTest` | the oracle is `java.util.zip.Deflater`, and the point of the test is that the two agree |
| `ProcreateImportTest.kt` | `jvmTest` | the structural fixtures (missing `Shape.png`, dangling UID, flat XML, zip bomb) are built with `java.util.zip.ZipOutputStream`; hand-rolling a second zip writer inside a test is a second reader to get wrong and a second thing to review |

**`InflateTest`** (jvmTest)
1. **Round trip, four ways.** `java.util.zip.Deflater` at levels 0 (stored — the `actual` refuses it,
   because a STORED entry is not a DEFLATE stream, so this case asserts the *refusal* naming "stored"),
   6 and 9, over inputs of 0, 1, 100 and 100 000 bytes → `inflateRaw` returns the original bytes
   exactly. Non-vacuity: assert `out.size == input.size` as well as content equality, so a reader that
   returns the input unchanged cannot pass on the stored case.
2. **A truncated stream refuses** with a message containing "ends early"; **a stream with trailing
   garbage** after the DEFLATE data refuses with "unread after its end", not read past.
3. **The cap is enforced by the `actual`, not only by the caller (D11).** A stream that inflates to
   10 MiB with `maxOut = 1 MiB` refuses with "1" and the limit in the message, and the test asserts it
   took under a generous timeout — a bomb that allocates before it checks is the failure this exists
   to stop.
4. **A range outside the array refuses** (`offset = -1`, `length = size + 1`, `maxOut = -1`).
5. **Malformed input refuses rather than looping:** 40 random bytes, a truncated fixed-Huffman table, a
   distance before the start of the output. Each throws `BrushException`.

**`KeyedArchiveTest`** (commonTest)
6. **UID resolution, both kinds:** a dictionary and an array of UIDs resolve to the objects the writer
   saw; a UID pointing past `$objects` refuses naming the index (Decision 7).
7. **Flat XML is read (Decision 6):** a minimal `<plist><dict><key>name</key><string>Round</string></dict></plist>`
   yields `"Round"`.
8. **Neither magic refuses with a sentence naming what was found** (Decision 6): `bplist0`,
   `<?xml` with no `<plist>`, and 40 random bytes each give a distinct message.
9. **Hostile XML is refused, not honoured:** a `<!DOCTYPE` with an `ENTITY`, an undefined entity, a
   mismatched close tag, a 200-deep nest. Each refuses with a message; none reads a file or a URL.
10. **Budgets, one test each at cap+1** (Decision 11): objects, depth 33, keys per dict, string
    length. Each names its cap.

**`ProcreateImportTest`** (jvmTest)
11. **The two real `.brush` archives convert and validate clean (Decision 14):** every preset has
    `sourceFormat = "procreate"`, `engine = "stamp"`, `license != "CC0"` unless the archive declares
    one, `id == ImportSupport.brushId(idPrefix, name)`, and `BrushValidate.validate` returns
    **empty**. Non-vacuity: assert the archives' own `name` values appear among the converted names —
    **prefixed** (Decision 16) — and that the count is asserted, not just non-zero.
12. **THE MAPPED-CURVE TEST:** an archive with `dynamicsPressureSize` and `dynamicsPressureOpacity`
    curves produces `size.inputs` and `opacity.inputs` that are **non-empty** and carry
    `BrushInput.pressure`, with the parsed points equal to the archive's `"{x, y}"` strings. **This is
    the assertion that would have caught JB-8.03's Finding 1.**
13. **THE R40 TESTS (Decision 3), four of them.**
    a. **The exact sentence, by value.** A brush with a `Shape.png` and a brush with a `Grain.png` each
       carry `ImportSupport.TEXTURE_NOT_DRAWN` in their warnings — asserted against the constant, not a
       retyped literal. A brush **refused** for a built-in grain also carries it (Decision 8).
    b. **The image is stored and the preset says so.** `tip.source == "image"`,
       `tip.image == "tip.png"` (and **not** `"shape.png"` — assert that explicitly, it is the bug this
       row had), `extensions["procreate.tipImage"]` non-empty, and base64-decoding it returns the
       archive's `Shape.png` **byte for byte**. Same for `paperGrain` / `"grain.png"` /
       `["procreate.grainImage"]`. And the numbers are still real: `size.base` is `maxSize`, or the
       `IHDR` width when the archive has no `maxSize` — and the test fixture with no `maxSize` is what
       proves the `IHDR` path runs.
    c. **Over the cap, not truncated.** A `Grain.png` whose base64 pushes `extensions` past
       `ImportSupport.MAX_EXTENSION_BYTES` has **no** `"procreate.grainImage"` key (assert *absent*),
       `paperGrain.source == "cloud"`, `paperGrain.enabled == false`, and warns with `TEXTURE_NOT_DRAWN`
       plus the byte count and the cap.
    d. **Rule 23 is satisfied and the brush still validates:** every converted preset with
       `source == "image"` has a non-blank `image`. *This is the check that catches a half-written path,
       and it is the one that would let a lying preset through if (b) were got wrong.*
14. **Every ≈ field is a LOSSY with the raw kept (Decision 1, D10):** `plotSpacing`, `plotJitter`,
    `plotSmoothing`, `textureScale`, `grainBlendMode`, `shapeFlipXJitter` each appear in `extensions`
    under a `procreate.` key **and** are named in a warning.
15. **The unverified-scale warning is ONCE PER PACK (Decision 10):** a `convertBrushSet` of 5 brushes
    produces **exactly one** unverified-scale warning across all five. *(JB-8.03's review Finding 2 as
    a named test.)*
16. **THE COLOUR-JITTER SCALE TESTS (Decision 3a) — the same four as JB-8.01's 9c, and for the same
    reason.** All four validate clean. `dynamicsJitterLightness = 0` → `color.value == 0f` and **no**
    scale warning. `= 0.5` → `color.value == 0.5f` (**not** `0.005f`; this is the case the old mapping
    got wrong), the raw in `extensions`, and the one per-pack warning naming the field. `= 100` →
    `color.value == 1.0f`, the per-pack warning naming it as a percentage, and **no** per-brush clamp
    warning. `= 250` → `1.0f` **and** a per-brush warning naming the field and both numbers.
17. **A built-in resource refuses the brush, and substitutes nothing (Decision 8):** an archive with
    `bundledShapePath = "BrushStudio/SmoothRound"` and no `Shape.png` is refused with a reason naming
    that path and the word "built-in"; the other brushes in the same archive survive and none of the
    survivors has an `image` tip pointing at a file that is not in the archive.
18. **The taper family is LOSSY, not refused (Decision 9):** an archive with `pencilTaperSize` and
    `taperOpacity` **converts**, carries `extensions["procreate.taper"]`, and warns naming the family.
    And assert `tip.taper == 0f` — the taper did **not** go into the shape taper, which is the mistake
    R4 §C is written against.
19. **A flat XML archive converts (Decision 6):** name + author only → one preset whose `name` is the
    archive's and whose `author` is the archive's, with a warning that the archive carried no settings.
20. **A bad curve refuses the brush and nothing else (Decision 9 / curve rule):** one brush with a
    65-point curve (over the shared cap), one with `"{0.5}"`, one with an x of 1.4, one with a non-finite
    y — four refusals with four distinct reasons, and the good brushes in the same file are `==` what a
    file without them would have produced.
21. **A hostile entry name refuses (Decision 12):** `"../evil.png"`, `"/etc/passwd"`, `"a\\b.png"`,
    `"c:x.png"` and a name with a control character each refuse; and the test writes nothing outside the
    output directory (assert the directory listing is unchanged).
22. **A zip bomb refuses before allocating (Decision 11):** a 1 KiB entry declaring 100 MiB refuses
    with the ratio named, in under a generous timeout.
23. **Every budget refuses (Decision 11), one test each at cap+1:** archive bytes, entry count, entry
    bytes, entry name length, inflated bytes per entry, brushes per pack. Each names its cap. The
    `extensions` budget is **tested through the R40 over-cap test (13c)**, not here, because it is not
    this file's constant.
24. **Malformed archives throw, they do not return empty (Decision 15):** not a zip, a zip with no
    `Brush.archive`, a truncated bplist, an empty file, a 3-byte file. Each throws `BrushException`
    with a message; none returns an `ImportLibrary` with an empty `brushes` **and** an empty `refused`.
25. **The set's order comes from `brushset.plist`, and the set's name prefixes (Decisions 15, 16):** a
    set whose plist lists brushes B, A, C imports in that order, each named `"<set> · <brush>"`; a set
    with a corrupt plist imports in folder-name order, names no prefix, and warns **once**.

**Command:** `./gradlew -p joybrush :core:jvmTest`. Passing = `BUILD SUCCESSFUL` and 0 failures in
`joybrush/core/build/test-results/jvmTest/`.

## Do not

- Do **not** extract or substitute a Procreate built-in shape or grain. Ever. R4 §5.
- Do **not** write a DEFLATE decoder. R40: no hand-written Inflate. The `actual` is
  `java.util.zip.Inflater` and that is the whole of it. ~350 lines of Huffman bit-twiddling in
  `commonMain` was considered and refused by the ruling, and the Do-not list exists so that a builder
  who "improves" on the ruling does it loudly.
- Do **not** add `java.*` to `commonMain`. `java.util.zip` and `java.io` appear in `Inflate.jvm.kt`
  and in `jvmTest` **only**.
- Do **not** edit `core/build.gradle.kts`. The `jvm()` target already exists
  (`core/build.gradle.kts:14-17`) and adding `core/src/jvmMain/kotlin/` needs no build change. If the
  build disagrees, that is the stop rule, not a licence to edit a build file.
- Do **not** redefine `ImportLibrary` / `RefusedBrush` / `ImportResult` (R23), or write your own id
  sanitiser instead of `ImportSupport.brushId` (Decision 2).
- Do **not** re-derive `MAX_INPUTS` / `MAX_CURVE_POINTS` / `MAX_EXTENSION_BYTES` / `TEXTURE_NOT_DRAWN`.
  Read all four. Declaring a 64 or a 256 KiB in this file is the exact drift R23 and R23's "a shared
  constant is never copied" are about, and **there is no build-time test that can catch it** — which is
  exactly why the Do-not list has to.
- Do **not** set `tip.image = "shape.png"` (or `"grain.png"`, or any archive path). It is a path inside
  the foreign `.brush`, and `TipSpec.image` is a path inside the **brush folder**. Test 13b asserts
  this.
- Do **not** warn per brush about a whole-pack fact (Decision 10) — one unverified-scale warning per
  call, and test 15 counts it.
- Do **not** divide the colour jitters by 100 on a guess (Decision 3a). The magnitude decides.
- Do **not** trim an over-long curve to fit, and do **not** put a Procreate taper into `tip.taper`
  (Decisions 9, R4 §C).
- Do **not** set `license = "CC0"` for an archive that does not say so.
- Do **not** run this spec at the same time as JB-8.01 or JB-8.04 — they share `ImportSupport.kt`.
- **Do not run this spec at all until JB-8.01 has landed.** See Sequencing: six symbols, and
  `BrushValidate.MAX_CURVE_POINTS` is `private const val` until then. The dispatch order is
  **JB-8.01 → (this row | JB-8.04)**, never two at once.
- **Do not restate `JbArchive.unsafeReason` as a shared constant.** It is **private**
  (`JbArchive.kt:514`) and lives in `androidkit`, which `commonMain` cannot see — Decision 12's
  "applied a second time" is a restatement, and the comment naming the other copy is what makes that
  honest. The exact set it refuses: empty name; over `MAX_NAME_CHARS`; any char with `code < 0x20` or
  `code == 0x7F`; a `\`; a leading `/`; a `:`; a path that is nothing but a separator; an empty path
  segment; a segment that is `.` or `..`; and a segment whose `trimEnd(' ', '.')` is empty, `.` or
  `..`. **A substring check for `".."` is not the rule** — the segment rule is, which is why `".. "`,
  `"a/./b"` and `"a/ /../b"` all still climb out.

## Stop rule

If anything here is ambiguous, or a claim about the `.brush` format turns out to be false when you
build the fixture, **STOP**: write the question in *Questions* under a heading `for the Lead`, set this
row `⛔ Blocked`, commit, push, and take another task. Three things are never a builder's call in this
file: **whether a Procreate built-in may be extracted or substituted** (never — R4 §5, and there is no
exception to find), **whether the inflate `actual` may be hand-written instead of
`java.util.zip.Inflater`** (it may not — R40), and **whether a file format may be invented for the
stored tip image** (it may not — the name says what the bytes are, and JB-1.05d decides what reads
them). If the scale of an `≈` field turns out to be unknowable from the fixtures you have, keep the
raw value in `extensions` and warn — do not pick a number.

## Definition of done

- [ ] tests pass (paste the output of `./gradlew -p joybrush :core:jvmTest`)
- [ ] only owner-area files changed (paste `git status --short`); `core/build.gradle.kts` is **not**
      among them
- [ ] `ImportSupport.kt` was **read**, not created, if it already existed
- [ ] committed as `JB-8.02: Procreate import`; pushed
- [ ] ROADMAP row → 🟧 Built *(there is no `INDEX.md` to update: it is a stub that points at
      `ROADMAP.md`)*

## Questions

### PROVISIONAL — Claude to confirm

Both are reversible in one edit, neither is a contract and neither is a file format. The builder
executes the spec exactly as written; if the Lead prefers the other branch, each names the place.

1. **Decision 3's over-cap behaviour** — an image whose base64 does not fit
   `ImportSupport.MAX_EXTENSION_BYTES` is dropped whole, the source stays `"cloud"`/`"procedural"`, and
   the R40 warning is still emitted. The alternative is to raise the shared cap, which is a
   `brush.json` size decision affecting all three rows; the third option is a separate channel for
   images. *JB-8.01's Decision 4a is worded identically and reverses with this one.*
2. **Decision 16's separator and fallback** — U+00B7 with one space each side, and a set with no name
   in `brushset.plist` means no prefix plus one per-pack warning. R40 ruled the *prefix*; the shape of
   the prefix is this spec's default.

### for the Lead — real, and **not** blocking this row

**Q1. What reads a stored tip image, and in what format?** Referred to **JB-1.05d**, and identical to
JB-8.01's Question 1. This row hands JB-1.05d two **real PNGs**; JB-8.01 hands it **PackBits gray**;
JB-8.04 hands it a PNG. Nothing reads any of them today — which is why this does not block — but
JB-1.05d has to know, and either it reads by extension (which is why the names are honest) or each
importer needs a PNG encoder it does not have. **Recommendation: put it on the JB-1.05d outline row's
Needs line now.** Do not add it to JB-8.01's file; it is already there.

**Q2. A CC0 lookalike library for Procreate's built-ins (R4 §B.9 lists p2k's recreation as CC0).**
Decision 8 refuses those brushes, which is legal and honest but means "your favourite Procreate brush"
often will not import. **Is there appetite for a row that ships a CC0 lookalike shape/grain library, or
is refusal the standing answer?** This spec assumes refusal and will not substitute anything without a
ruling — an invented substitute is exactly the "imports successfully, draws differently" outcome the
JB-8.03 review filed.

**Q3. The `≈` scales (R4 §B.2).** `plotSpacing`, `plotJitter`, `plotSmoothing` and `textureScale` are
guessed, and R4's own advice is *"build a calibration set by exporting test brushes from Procreate
with single sliders at known values."* **That needs Procreate and a person, so this spec cannot do
it.** Options: (a) ship guessed scales with the per-pack warning Decision 10 already emits (this spec's
answer); (b) ask the owner for ~10 calibration brushes and add a row; (c) refuse the fields, which
would strip spacing from every imported brush. **Recommended: (a) now, (b) as a follow-up row** — and
the follow-up row's Needs should say 8.02.

**Q4. A base64 `Shape.png` inside `brush.json` is a shareable-file-size decision, not an import one.**
R40 stores the image in `extensions`, and `brush.json` is *"the smallest thing a person can share"*
(`BrushJson.kt:27-28`), so a 200 KiB tip travels inside every share of that brush. Same in all three
rows. **It belongs with the document-format rows (JB-0.02d / JB-1.05d), not with an importer** — and
it is the strongest argument for giving images their own channel. Flagged so it is a decision and not
a surprise. (Identical to JB-8.01's Question 2; raised once, in whichever file reaches the Lead first.)

**Checked and found TRUE against the landed source, 2026-09-29:**

- `BrushValidate` accepts `"image"` for tip and grain (`BrushValidate.kt:20-21`) and rule 23
  (`:236-239`) checks only `isNullOrBlank()` — so R40's warning is load-bearing, and test 13d is the
  test that says so.
- `TipSpec.image` is documented as *"path inside the brush folder when source == image"*
  (`BrushPreset.kt:34`) and a brush folder is `<folder>/brush.json` (`BrushLibrary.kt:12-14`) — which is
  what makes `"shape.png"` wrong rather than merely premature.
- `TipSpec.aspect` is `val aspect: Float = 0f` (`BrushPreset.kt:37`) — no `inputs`, no `base` — and
  `BrushValidate.paramsOf` (`:254-263`) lists exactly eight `Param`s, of which `tip.angle` and
  `scatter.amount` are two. So the four curve rows above are real and `shapeRoundness` is not a curve.
- `ColorJitter` has `perStroke`, not `perDab` (`BrushPreset.kt:57`), and its three floats are ranged
  `0f..1f` by rule 15.
- `MAX_CURVE_POINTS` and `MAX_INPUTS` are `private const val` at `BrushValidate.kt:31` and `:28`.
  **The Sequencing table above is a fact about the tree, not a plan.**
- `ImportResult(val preset, warnings)` (`MypaintImport.kt:29`) and is used by **nothing outside
  `joybrush/`**; `ImportLibrary` exists nowhere yet.
- `core` is a KMP module with only `jvm()` enabled (`core/build.gradle.kts:14-17`) and there is **no
  `expect`/`actual` anywhere in `joybrush/` today** — this row is the first. There is a `jvmTest`
  source set and a `commonTest` source set, and no `jvmMain` yet, which is why `Inflate.jvm.kt` is a
  NEW directory and the "no build edit" claim in Decision 3 is stated as a checkable fact.
- `PngWriter.encode(width, height, rgba, compressionLevel = 6): ByteArray` is in **`androidkit`**
  (`androidkit/.../io/PngWriter.kt:85`), not `core`, and is JVM-only — so "just re-encode it as a PNG"
  is not available to this row.
- `javax.imageio.ImageIO` appears **only** in test code (`jvmTest`, `androidkit/src/test`), always as an
  oracle. There is no image decoder in production code anywhere in `joybrush/`.
- `JbArchive.unsafeReason` is `private fun` at `androidkit/.../io/JbArchive.kt:514`, and `JbArchive`
  uses `java.util.zip` (`:17-20`) — so Decision 12 is a restatement across a module boundary and R40's
  choice of `expect`/`actual` is not a duplicate of what `JbArchive` already has, because `JbArchive` is
  unreachable from `commonMain`.
