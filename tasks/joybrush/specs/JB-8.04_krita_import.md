# JB-8.04 — Import Krita `.kpp` / `.bundle` brushes (pixel and colour smudge engines only)

| | |
|---|---|
| **Tier** | T2 |
| **Status** | 🟦 Ready |
| **Who** | spec writer (unattributed in the original) · **xr: openrouter/stealth/space-bunny-alpha 2026-09-29** — confirmed the contract cannot carry embedded-tip bytes (read `ImportResult` at `MypaintImport.kt:29` and `ImportLibrary` as declared in JB-8.01); confirmed `BrushValidate` rule 23 (`BrushValidate.kt:236`) makes `tip.source = "image"` require a non-blank `tip.image`; confirmed `MAX_CURVE_POINTS` is `private`; fixed the truncated budget unit `8 Mi`; confirmed the Decision 1 claim about `InkEraseMode`'s exhaustive mapping is a *style* reference, not a shared helper.<br>**xr: openrouter/stealth/space-bunny-alpha 2026-09-29 (R40 pass)** — this spec is now executable end to end. **Decision 4 was unimplementable** (it told the importer to return bytes in a result that has no field for them) and **R40 removes the need for them**: the tip's bytes go into `extensions`, which already exists on `BrushPreset`, so no shared type is widened and no contract changes. R40's three rulings for this row are carried as Decisions 4, 2b and 14: **ColorSmudge imports with a warning** (worded exactly, and pinned by a test), **a brush using both `xtilt` and `declination` carries its own warning** (Decision 2b), and **licence and author are kept** and surfaced later in the brush detail sheet (Decision 14, referred, not this row's). The R40 paragraph is now byte-identical to JB-8.01's and JB-8.02's. `MAX_BASE64_CHARS` was already un-truncated by the previous pass and is kept; the whole of Decision 7's table was re-checked for units and every row has one. Also added a **Sequencing** section: this row reads `BrushValidate.MAX_CURVE_POINTS`, `MAX_INPUTS`, `ImportSupport.MAX_EXTENSION_BYTES`, `TEXTURE_NOT_DRAWN` and `brushId`, and four of those five exist only after JB-8.01. |
| **Needs** | JB-0.03 (brush format + validation), **and JB-8.01** — see "Sequencing", which is not decoration: this row does not compile until JB-8.01 lands. |
| **Owner area** | NEW `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/brush/imports/KritaImport.kt`, NEW `.../imports/PngChunks.kt` (PNG signature + `tEXt`/`iTXt`/`zTXt` chunk reader — **no decoding, no inflating**), NEW `joybrush/core/src/commonTest/.../brush/imports/PngChunksTest.kt`, NEW `joybrush/core/src/commonTest/.../brush/imports/KritaImportTest.kt`; `ImportSupport.kt` **only if absent** (JB-8.01's Decision 2). **NOT** `Inflate.kt` / `Inflate.jvm.kt` (JB-8.02's, Decision 5). **NOT** `ImportResult` / `ImportLibrary`. |
| **Estimated size** | ~500 lines `KritaImport.kt`, ~250 lines `PngChunks.kt`, ~420 lines of tests |
| **Command** | `./gradlew -p joybrush :core:jvmTest` — 0 failures |

## Sequencing — the one thing to read before dispatching this

**This row does not compile until JB-8.01 has landed, and that is a fact about the tree, not a
preference.** It reads five things, four of which exist only after JB-8.01:

| Read | Where it lives today | Who makes it |
|---|---|---|
| `BrushValidate.MAX_CURVE_POINTS` (Decision 3) | `private const val` — `BrushValidate.kt:31` | **JB-8.01** (two words: `private` → `public`) |
| `ImportLibrary`, `RefusedBrush`, `summary()` | does not exist | **JB-8.01** (creates `ImportSupport.kt`) |
| `ImportSupport.MAX_EXTENSION_BYTES` (R40, Decision 4) | does not exist | **JB-8.01** |
| `ImportSupport.TEXTURE_NOT_DRAWN` (R40) | does not exist | **JB-8.01** |
| `ImportSupport.brushId` (ids) | does not exist | **JB-8.01** (R23) |

`BrushValidate.MAX_INPUTS` is **not** read by this row — a Krita preset carries at most one sensor per
`Param` in the cases this spec maps, so a cap on inputs is not needed here, and declaring one anyway
would be inventing a reason to depend on a constant the row does not need.

The board's *Needs* column for this row says `0.03` only, and that is **wrong**; the orchestrator's
change is in the report that came with this spec, not in this file. **Dispatch order is
JB-8.01 → (this row | JB-8.02), never two at once** — they share `ImportSupport.kt`.

## Goal

R4 §F ranks Krita fourth, and this is the friendliest format of the three: **".kpp (PNG + XML) is open,
easy to read"**, and the content is CC-BY / CC0 / WTFPL (R4 §B.3: David Revoy's presets are CC-BY 4.0;
Ramón Miranda's hairy presets are WTFPL; Krita's default bundle is mixed and must be checked per
resource).

The row says exactly what it will and will not take: **pixel and colour smudge engines only.** R4
§B.3: *"Maps well for the Pixel ('paintbrush') engine … and a colour smudge engine. It does **not** map
for Krita's non-stamp engines: Hairy/Bristle, Sketch, Deform, Particle, Spray (partially), Curve,
Hatching, Grid, Shape, Filter, Tangent Normal, Quick. Import only the paintbrush and colorsmudge
engines and skip the rest with a message."*

**Skip with a message is the whole Decision of this row**, and it is the same rule the JB-8.03 review
produced: a refusal you can see beats an approximation you cannot.

## THE Decision this row exists to get right

**What happens to input the reader cannot express.** Krita's own engine list is the cleanest version of
this question in the whole of Phase 8, because Krita tells you what the brush is and most of those
things have no analogue in a stamp engine:

| Bucket | Krita | Where it goes |
|---|---|---|
| **MAPPED** | `Pixel` engine (`paintbrush`), `ColorSmudge` engine, auto-mask, sensors, texture, masking brush, scatter, rotation, mirror, mirroring, opacity/flow sensors, spacing, stabilisation | the `BrushPreset` field |
| **LOSSY** | a sensor Joy Brush has no `BrushInput` for; a Krita texture mode Joy Brush's grain has no equivalent; a base64-embedded GBR/GIH tip; a **predefined** tip named by a file that is not in the file | closest field, **warning naming the sensor or mode**, **raw in `extensions`** |
| **REFUSED** | every other `paintopid` — `Hairy`, `Sketch`, `Deform`, `Particle`, `Curve`, `Hatching`, `Grid`, `Shape`, `Filter`, `TangentNormal`, `Quick`, `Spray`; a base64-embedded `GBR`/`GIH`/`ABR` tip; a malformed file | that brush, **with the engine's own id or the format's own name in the sentence** |

The refusal reason must contain the literal `paintopid` string, because that is what a person will see
in Krita's brush editor, and it is what makes the message actionable rather than "unsupported".

Two more facts that must be honoured rather than glossed:

- **`ColorSmudge` is legal and is not "unsupported."** R4 §E.3 maps it to `pickup`, and JB-1.06 (smudge
  and nudge) is `⚪ Outline`. So a colorsmudge brush imports as `engine = "smudge"` — a word
  `BrushValidate` already accepts — and **warns that the engine is not built yet** (Decision 11). That
  is LOSSY, not REFUSED: the brush is real, the engine is late.
- **R20 still applies.** A `smudge` brush may not be used on an ink layer. That is a pick-time rule
  (JB-5.03's Q1), not an import rule, so this spec just has to not do anything clever.

## The `.kpp` and `.bundle` formats (R4 §B.3, restated)

* **`.kpp`** — a **PNG** whose text chunks carry the settings:
  * `tEXt` / `iTXt` keyword **`version`**,
  * `tEXt` / `iTXt` keyword **`preset`** — the XML paint-op settings.
  The XML has `<paintop id="…">` (the engine id), a `<brush_definition>` (auto mask, or a predefined
  PNG / GBR / GIH / ABR tip, **often base64-embedded**), and sensors: `PressureSize`, `SizeSensor`,
  `…commonCurve "x,y;x,y;"`, and the sensor ids `pressure`, `xtilt`, `ytilt`, `ascension`,
  `declination`, `rotation`, `speed`, `drawingangle`, `fuzzy`, `fuzzystroke`, `fade`, `distance`,
  `time`, `perspective`, `tangentialpressure`.
* **`.bundle`** — an ODF-style zip: `mimetype`, `meta.xml`, `META-INF/manifest.xml`, `preview.png`,
  `brushes/`, `patterns/`, `paintoppresets/`.

## Contract (verbatim)

```kotlin
package cc.joycreator.joybrush.core.brush.imports

object KritaImport {
    /**
     * One `.kpp` file's bytes → one brush, or a refusal.
     *
     * @param idPrefix the caller's library prefix; ids are `ImportSupport.brushId(idPrefix, name)`.
     * @throws BrushException if the FILE itself cannot be read (not a PNG, no `preset` chunk, a chunk
     *   length that overruns). One bad brush inside a bundle is never a `BrushException` — it is a
     *   `RefusedBrush`.
     */
    fun convertKpp(bytes: ByteArray, idPrefix: String): ImportLibrary

    /**
     * A `.bundle` zip → every `.kpp` inside `brushes/` and `paintoppresets/`, in name order, with
     * the bundle's own `patterns/` entries available to Decision 4's texture fork.
     */
    fun convertBundle(bytes: ByteArray, idPrefix: String): ImportLibrary
}
```

`ImportLibrary`, `RefusedBrush`, `summary()`, `MAX_EXTENSION_BYTES`, `TEXTURE_NOT_DRAWN` and
`brushId` are **JB-8.01's** (its Decision 2). The whole file is pasted, verbatim, in the Contract
section of `JB-8.01_photoshop_abr_import.md`; **if `ImportSupport.kt` exists when you start, it is
already correct and you do not create it.** You call `ImportSupport.brushId(idPrefix, name)` for every
id. You never write your own sanitiser.

`PngChunks` is **this row's**, and its whole surface is:

```kotlin
package cc.joycreator.joybrush.core.brush.imports

/** One text chunk out of a PNG. [compressed] is true for `zTXt` and for an `iTXt` that asks for it. */
data class PngTextChunk(val keyword: String, val text: String, val compressed: Boolean)

/**
 * Every `tEXt` and `iTXt` chunk of a PNG, in file order.
 *
 * **Never decodes pixels and never inflates** (Decisions 5 and 6). A `zTXt` chunk, and an `iTXt` with
 * a non-zero compression flag, are returned with `compressed = true` and an **empty** `text` — the
 * caller refuses them in words, and a reader that silently returned nothing would make that
 * indistinguishable from a file that simply has no such chunk.
 *
 * @throws BrushException on a bad signature, a chunk length that overruns the file, a length that is
 *   negative read as a signed Int, or any budget overrun (Decision 7).
 */
fun readPngTextChunks(bytes: ByteArray): List<PngTextChunk>
```

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
   * **This row:** a base64-embedded tip really is a PNG, so the name is **`tip.png`** — and *not* the
     `<brush_definition>` attribute's own name, which is a Krita resource path, not a path in a brush
     folder.
   * **JB-8.01** writes `tip.packbits` (PackBits gray) and **JB-8.02** writes `tip.png` / `grain.png`.

The alternative — a procedural stand-in and no stored bytes — was considered for all three rows and
**refused for all three**. It loses the artist's actual tip, and refusing the brush instead refuses
most of Procreate and much of Revoy's Krita pack.

**When a file carries a texture Joy Brush cannot put a name on, the warning is still this one.** The
sentence is the same whether the texture was stored, could not be stored, or was never an image: this
row's texture modes and its **predefined** tips (Decisions 4 and 12) and JB-8.02's built-in grain
refusal both emit it, as does JB-8.01's Photoshop `patt` pattern. A reader must be able to grep the
three importers for one string.

## Decisions — the mapping

1. **`Pixel` and `ColorSmudge` are the only accepted `paintopid`s.** Every other value is REFUSED with
   the id in the sentence. **This is a `when` with no `else` branch that returns a brush** — adding a
   Krita engine later is a compile error until someone decides where it goes, which is the point
   (the same trick `InkEraseMode`'s mapping uses; that is a *style* reference, not a helper to import).
2. **Krita sensor → `BrushInput`, and there are exactly these.** `pressure` → `pressure`;
   `xtilt`/`declination` → `tilt` (both are "how far from upright"); `ascension` → `lean`;
   `rotation` → `barrel`; `speed` → `speed`; `drawingangle` → `direction`; `distance` → `distance`.
   **The rest — `ytilt`, `fuzzy`, `fuzzystroke`, `fade`, `time`, `perspective`,
   `tangentialpressure` — have no `BrushInput`** and go to `extensions["krita.<sensor>"]` with a
   warning naming the sensor. **`BrushInput` gains no constant here:** R3 makes that a `BRUSH_VERSION`
   bump and a validator change, and Joy Brush's inputs are the engine's business, not Krita's.
2b. **A brush that uses BOTH `xtilt` and `declination` carries its own warning (R40).** They both map
   to `BrushInput.tilt`, and Joy Brush's `PenSample.tilt` is a **single magnitude** — so the second
   one is not a rounding error, it is a second axis of a two-axis sensor folded onto one number. The
   warning is its own sentence, once per brush, and reads:
   **"this brush uses two tilt sensors (xtilt and declination); Joy Brush has one tilt value, so the
   second is ignored"** — and both raw values stay in `extensions["krita.xtilt"]` /
   `["krita.declination"]` whether or not the brush also triggered the general per-sensor warning.
   *This is the difference between "silently coarser" and "told", and it is one extra line that is very
   hard to add once people have imported the packs.* *(PROVISIONAL — Claude to confirm: R40 ruled that
   the warning exists; the exact wording is this spec's, and test 9b pins it.)*
3. **`commonCurve "x,y;x,y;"` parses to `[[x, y], …]` and obeys the shared caps.** Over
   `BrushValidate.MAX_CURVE_POINTS` points, an x outside 0..1, a non-finite y, or a point that is not
   exactly two numbers **refuses that brush**. Never trimmed.
4. **`brush_definition` decides the tip, and the tip is the only place this row can go wrong.** Four
   cases, all decided, and the first one is R40's:
   * `type = "auto_mask"` or an empty definition → a procedural tip; `tip.aspect` from the ellipse's
     `rx`/`ry` (a **bare `Float`**, `BrushPreset.kt:37` — no curve, no `base`), `tip.corner` = 2.
     MAPPED. `size.base` comes from `SizeSensor`'s base, or the `PixelSize` value, or the default
     `20f`; whichever it is, it is stated in the test.
   * a **base64-embedded PNG** → **R40**: the bytes are base64'd into
     `extensions["krita.tipImage"]`, `tip.source = "image"`, `tip.image = "tip.png"`, `size.base` is
     the PNG's `IHDR` width (bytes 16..24, big-endian — a header read, not a decode), and the warning
     is `ImportSupport.TEXTURE_NOT_DRAWN` **by value**. **Nothing else changes shape, and nothing is
     added to `ImportResult` or `ImportLibrary` to carry the bytes** — `extensions` is a
     `Map<String, String>` that already exists on `BrushPreset` (`BrushPreset.kt:82`) for exactly this,
     and a preset that wants its bytes back has them.
   * a base64-embedded **GBR / GIH / ABR** → **REFUSED** for this row, with the format named. Reason,
     stated so it is not a bare "no": GIMP's `.gbr` and `.gih` and Adobe's `.abr` are three more
     binary readers, and this row's budget is one XML parser and one PNG chunk reader.
   * a **predefined tip by name** (a resource path Krita expects to find on disk, which is not in the
     file) → LOSSY: procedural tip, the name in `extensions["krita.tipPredefined"]`, and a warning
     naming the missing image **and carrying `ImportSupport.TEXTURE_NOT_DRAWN`** — the texture is kept
     by name and not drawn, so it is the same sentence as everywhere else.
   * **Over the cap (R40, identical wording in JB-8.01's Decision 4a and JB-8.02's Decision 3):** a
     tip whose base64 would push `extensions` past `ImportSupport.MAX_EXTENSION_BYTES` is **not
     stored at all** — never truncated — `tip.source` stays `"procedural"`, `size.base` is unchanged,
     and the warning still carries `TEXTURE_NOT_DRAWN` plus the byte count and the cap.
     *(PROVISIONAL — Claude to confirm.)*
5. **No inflate, and `zTXt` is refused.** Krita writes `preset` as **uncompressed `tEXt`** in
   practice; `zTXt`/`iTXt`-compressed chunks exist. JB-8.02's `expect`/`actual` inflate
   (`Inflate.kt` + `Inflate.jvm.kt`, R40) is `internal` in the **same module**, so it is *reachable*
   from this row — this refusal is a **decision, not a module boundary**, and the reason is this:
   R4 §B.3 says the chunks are uncompressed, so this should never fire on a real file; a DEFLATE
   stream inside a PNG needs a **ratio and output budget of its own**, and a second copy of those
   numbers next to JB-8.02's is precisely the drift R23 forbids. So: **an uncompressed `tEXt`/`iTXt`
   is read; a compressed `zTXt` is refused with a sentence saying the chunk is compressed**, and the
   reader keeps reading the chunks after it. *If the Lead ever wants `zTXt` read, that is Decision 5
   plus a decision about whose budget applies — not a builder's call.*
6. **No PNG decoding.** `PngChunks.kt` reads the 8-byte signature, walks chunks by length, and reads
   `tEXt`/`iTXt` payloads. It **never decodes pixels** and reads only the `IHDR` **width and height**
   (four big-endian bytes each at fixed offsets 16..24) where Decision 4 needs a diameter. Reason:
   Joy Brush has a PNG *writer* (JB-2.14a) and no reader, and a decoder is a large piece of work that
   belongs to its own row.
7. **Every budget is a named constant and every one refuses.** Every cap below has a unit, a name and
   a test at cap+1:

| Budget | Cap |
|---|---|
| `MAX_FILE_BYTES` | 64 MiB |
| `MAX_ENTRIES` (bundle) | 4 096 |
| `MAX_ENTRY_BYTES` | 32 MiB |
| `MAX_ENTRY_NAME_CHARS` | 512 |
| `MAX_CHUNKS` | 4 096 |
| `MAX_CHUNK_BYTES` | 32 MiB |
| `MAX_STRING_BYTES` | 8 MiB per text chunk (a `preset` XML is big) |
| `MAX_XML_DEPTH` | 64 |
| `MAX_XML_NODES` | 200 000 |
| `MAX_XML_ATTRS_PER_NODE` | 256 |
| `MAX_BASE64_CHARS` | 8 Mi chars (8 388 608) — a refusal **before** base64 decoding is attempted |
| `MAX_BRUSHES` | 2 048 |
| `MAX_EXTENSION_BYTES` | **not declared here** — `ImportSupport`'s, read (Decision 4, R40) |

   **The two big caps are not the same as the extension cap, and must not be confused:** `8 Mi chars`
   of base64 is what the reader will refuse to *attempt*; `ImportSupport.MAX_EXTENSION_BYTES`
   (256 KiB) is what the brush may actually *keep*, and over it Decision 4 drops the image whole.
8. **A chunk length that overruns the file refuses**, and a chunk length that is **negative when read
   as a signed 32-bit Int** (PNG's length field is `u32`, and a hostile file sets the high bit) refuses
   **before** any addition — the addition is done in **Long** and refused above `MAX_CHUNK_BYTES`
   first. This is the R19 rule: *check in Long before any Int pixel arithmetic.*
9. **XML is parsed with a hand-written reader, not a regex.** Entities are limited to `&amp;` `&lt;`
   `&gt;` `&quot;` `&apos;` `&#NNN;`; anything else is left as text. **No external entity, no DTD, no
   network** — a `.kpp` is a stranger's file and XXE is the first thing that comes to mind.
10. **The engine id is read from the XML and nowhere else.** `<paintop id="…">`; if the element is
     missing, the brush is **refused** with a sentence saying the file has no engine — not defaulted to
     `Pixel`, which would import a bristle brush as a round one.
11. **`ColorSmudge` → `engine = "smudge"` with one exact warning (R40).** It is a valid `BrushPreset`
     word (`BrushValidate.ENGINES` is `setOf("stamp", "smudge", "wet", ENGINE_FILL)`,
     `BrushValidate.kt:16`), so this needs no version bump and no validator change. The warning reads,
     word for word:
     **"this brush uses Krita's ColorSmudge engine; Joy Brush's smudge engine is not built yet, so this
     brush stamps for now"** — said once per brush, **not** once per file, because the engine really
     is a per-brush fact. *(PROVISIONAL — Claude to confirm: R40 ruled that ColorSmudge imports with a
     warning; the wording is this spec's, and test 14 pins it.)*
12. **Krita's texture maps onto `paperGrain`, and there are exactly two cases — decided, because a
     `.kpp` and a `.bundle` carry the texture differently.**
    * **The `preset` XML names a pattern resource** (a `.kpp` on its own, and most `.bundle` files) →
      the name goes to `extensions["krita.texturePattern"]`, `paperGrain.source` stays `"cloud"`,
      `paperGrain.enabled` is `true` when the mode is one of Height/Subtract, `paperGrain.depth` is
      set, and there are **two** warnings: one naming the **mode** and, when the mode is not one of
      the four, `extensions["krita.textureMode"]` as well; and one carrying
      `ImportSupport.TEXTURE_NOT_DRAWN`, because the texture is kept by name and not drawn.
    * **The name resolves to an entry inside the same `.bundle`** (`patterns/<name>`) → **R40 applies
      to the grain**: those bytes are read (they are a zip entry; a STORED entry is copied, a DEFLATE
      entry needs an inflater this row does not have — so **only a STORED `patterns/` entry is
      stored**, and a DEFLATE one falls to the case above with the warning saying the pattern could
      not be read out of the bundle), base64'd into `extensions["krita.grainImage"]`,
      `paperGrain.source = "image"`, `paperGrain.image = "grain.png"`, `enabled = true`, and the
      `TEXTURE_NOT_DRAWN` warning. The same over-cap rule as Decision 4 applies.
    * **The PS-compatible modes** are Subtract, Height (PS), Linear Height (PS) and Hard Mix, with
      published formulas (R4 §A.5, from Krita's source). **`paperGrain` has no mode field today**, so
      Height and Subtract map to `enabled` + `depth` with a **warning naming the mode**; every other
      mode (Multiply, Overlay, Dodge, Burn, …) goes to `extensions["krita.textureMode"]` with a
      warning. Loud, never silent. **No `GrainSpec` field is added here** — R31 makes a new serialised
      field a version bump and a validator change, and grain belongs to JB-1.05c.
13. **An entry name in a bundle is never trusted** (JB-8.01's Decision 12, same discipline, and the
    `..`/backslash/colon/control/empty-segment rule is restated rather than copied into code twice —
    `ImportSupport.kt` is where it lives once both rows exist).
14. **Licence and author are KEPT, per resource, never assumed (R40).** `license = "unknown"` unless
    the bundle's `meta.xml` states one for that brush; **`never "CC0" by default`.** `author` from the
    bundle metadata when present, else `""`. (R4 §B.9's per-file licence advice, and blueprint §5:
    keep author/licence/source in every imported brush.)
    **Where they are shown is NOT this row.** R40 settles that they are surfaced later, in the **brush
    detail sheet**, and this row's whole obligation is to carry the two strings correctly so that
    sheet has something to show — CC-BY 4.0 (R4 §B.9, Revoy's pack, the one the blueprint names as the
    pack to ship) *requires* attribution, so the data is not optional and is not this row's to
    display. **No sheet exists in the spec set today and no row on the board owns it**; the referred
    item is in the Questions, and it does not block this row.
15. **A `.bundle` with no `brushes/` and no `paintopresets/` refuses** in words rather than importing
    an empty library — an empty result with no explanation is the silence this whole spec forbids.

## Steps

1. **Confirm JB-8.01 has landed** (Sequencing). If `BrushValidate.MAX_CURVE_POINTS` is still `private`,
   stop and say so — do not work around it by declaring your own 64.
2. Tests first: `PngChunksTest`, then `KritaImportTest`. **Both in `commonTest`**: a PNG is
   hand-writable chunk by chunk and a `.bundle` is hand-writable entry by entry (STORED entries need no
   compressor), so this row needs no `java.*` in any test. *If you find yourself reaching for
   `ZipOutputStream` or `ImageIO` in a test here, you are in the wrong source set or you are building
   something the row does not need.*
3. `ImportSupport.kt` **if absent** — only if JB-8.01 has not already created it, which by step 1 it
   has.
4. `PngChunks.kt` — signature, chunk walk, `tEXt`/`iTXt` payloads, Long-guarded lengths, `IHDR` width
   and height, and a `compressed = true` result for `zTXt` that never inflates.
5. `KritaImport.kt` — budgets, the XML reader, the `paintopid` gate (Decision 1), sensors (Decisions 2,
   2b), tip kinds (Decision 4), the texture fork (Decision 12), and the R40 store. Write the base64
   store and the `TEXTURE_NOT_DRAWN` warning **while you are on the tip row**, not at the end.

## Tests

**Fixtures.** `PngChunksTest` builds PNGs by **concatenating hand-written chunks** (a valid signature,
an `IHDR`, the text chunks, an `IEND`) — no PNG library in the test either, so the test proves the
reader rather than a writer. `KritaImportTest` uses **real `.kpp` files pasted as base64** with their
source and licence in a comment — R4 §B.9 names David Revoy's `deevad-krita-brushpresets` (CC-BY 4.0),
which is the pack to ship — plus hand-built minimal PNG+XML for the structural cases, and a
hand-built `.bundle` zip with **STORED** entries for the bundle cases.

**`PngChunksTest`**
1. **Signature and chunk walk:** a PNG with three text chunks returns all three, in order, with their
   keyword and payload; a chunk with a **zero length** and an unknown type is skipped, not an error.
2. **A chunk length that overruns the file refuses** (D8), naming the chunk type and the length.
3. **A length with the high bit set refuses** (D8): `0xFFFFFFFF` as an unsigned length, read as a
   signed Int, must not become `-1` and must not be added to the position. The test asserts the
   refusal message, **not** a crash and not a successful parse.
4. **A `zTXt` chunk is refused by the *caller* with "compressed" in the message** (D5), and the reader
   keeps reading the chunks after it — one compressed chunk must not lose the `preset` that follows it.
   *The reader's own contract is narrower: it returns the `zTXt` with `compressed = true` and an empty
   `text`, and the test asserts **that**, so "the reader tried to decode it" and "the caller forgot to
   check" are two different failures with two different messages.*
5. **`iTXt` uncompressed is read** (compression flag 0) and its text after the language and translated
   keyword is the payload; a **non-zero compression flag** is returned `compressed = true`.
6. **`IHDR` width and height are read** (D6) for a known header, and an `IHDR` shorter than 24 bytes
   refuses.
7. **Every budget refuses (D7), one test each at cap+1:** file bytes, chunk count, chunk bytes, text
   chunk bytes. Each names its cap.

**`KritaImportTest`**
8. **The real `.kpp` presets convert and validate clean (D-mapping):** each preset has
   `sourceFormat = "kpp"`, `license` not `"CC0"` unless stated, `id == ImportSupport.brushId(idPrefix,
   name)`, and `BrushValidate.validate` returns **empty**. Non-vacuity: assert the brush's own Krita
   name appears among the converted names, and assert the count rather than only that it is non-zero.
9. **THE ENGINE GATE (D1), one case per refused id:** a hand-built `.kpp` per engine —
   `Hairy`, `Sketch`, `Deform`, `Particle`, `Curve`, `Hatching`, `Grid`, `Shape`, `Filter`,
   `TangentNormal`, `Quick`, `Spray` — each is refused with a reason **containing that literal
   `paintopid`**. **Non-vacuity in the same test:** `Pixel` and `ColorSmudge` from the same fixture
   shape are **not** refused. This is the row's headline assertion.
9b. **THE BOTH-TILTS WARNING (D2b, R40).** A fixture whose sensors include **both** `xtilt` and
    `declination` produces a warning containing `"two tilt sensors"` and the second raw value stays in
    `extensions["krita.declination"]`. A fixture with **only** `xtilt` produces **no** such warning —
    the assertion is on absence, because a warning that fires whenever a tilt exists says nothing.
10. **A missing `<paintop id>` refuses, naming the omission** (D10) — and does **not** default to
    `Pixel` (assert `brushes.isEmpty()` and `refused.size == 1`).
11. **Sensors map, and unmapped sensors are loud (D2):** a fixture with `pressure`, `xtilt`, `rotation`
    and `fuzzy` sensors produces inputs on `size`/`opacity` carrying `BrushInput.pressure`, `.tilt` and
    `.barrel`, and `extensions["krita.fuzzy"]` plus a warning naming `fuzzy`. **`ytilt` also warns and
    does not become `tilt` a second time.**
12. **THE MAPPED-CURVE TEST (D3):** a `commonCurve "0,0;0.5,0.8;1,1"` becomes
    `[[0,0],[0.5,0.8],[1,1]]` exactly (within 1e-6), and a second pressure curve on the same `Param`
    is **also** present — so a brush with two sensors cannot silently lose one.
13. **A bad curve refuses the brush (D3):** a 65-point curve, `commonCurve "0,0;x,1"`,
    `commonCurve "0,0;1.4,1"` and `commonCurve "0,0;1"` each refuse with distinct reasons; the good
    brushes in the same bundle are `==` what a bundle without them would have produced.
14. **THE R40 TESTS (D4), five of them — and the second one is the test this row used to be
    unable to write.**
    a. **The exact sentence, by value.** An embedded-PNG tip's warnings contain
       `ImportSupport.TEXTURE_NOT_DRAWN` — asserted against the constant, not a retyped literal. A
       **predefined-by-name** tip's warning contains it too, and a texture-named `.kpp` (12) contains
       it, so a grep finds all three shapes.
    b. **The tip is stored, and the round-trip is over `extensions` — not over a result object.** A
       base64 **PNG** tip gives `tip.source == "image"`, `tip.image == "tip.png"` (non-blank, so rule
       23 passes; and **not** the `<brush_definition>` attribute's own name — assert that explicitly),
       `extensions["krita.tipImage"]` non-empty, and base64-decoding it returns the fixture's embedded
       bytes **byte for byte**. And `size.base` equals the PNG's `IHDR` width. *The previous version of
       this test asked for "the decoded bytes in the result", which is a field that does not exist on
       `ImportResult` (`MypaintImport.kt:29`) or on `ImportLibrary`; this row changed the mechanism
       instead of the type, and the type is untouched.*
    c. **Rule 23 holds and nothing is half-written.** Every converted preset with `source == "image"`
       has a non-blank `image`, and **no converted preset has `source == "image"` with a blank
       `image`** — the check that catches a half-written path.
    d. **Over the cap, not truncated (D4).** An embedded tip whose base64 pushes `extensions` past
       `ImportSupport.MAX_EXTENSION_BYTES` has **no** `"krita.tipImage"` key (assert *absent*, not
       empty), `tip.source == "procedural"`, `size.base` unchanged, and a warning carrying
       `TEXTURE_NOT_DRAWN` plus the byte count and the cap. The base64 length is also under this row's
       own `MAX_BASE64_CHARS`, so the two caps are not confused in the fixture.
    e. **GBR and GIH are still refused, by name.** A base64 **GBR** tip and a base64 **GIH** tip each
       refuse with the format named, and a **predefined by name** tip converts with a procedural tip
       and a warning naming the missing image.
15. **Texture: both cases, both decided (D12).** A `Subtract` texture produces `paperGrain.enabled ==
    true` and a warning containing `"Subtract"`; an `Overlay` texture produces
    `extensions["krita.textureMode"]` containing `"Overlay"` and a warning containing it; and **both**
    produce a warning carrying `TEXTURE_NOT_DRAWN` and `extensions["krita.texturePattern"]` naming the
    pattern. All validate clean. Then the **bundle** case: a `.bundle` with a **STORED**
    `patterns/<name>.png` matching a preset's texture name produces `paperGrain.source == "image"`,
    `paperGrain.image == "grain.png"`, `extensions["krita.grainImage"]` round-tripping to the entry's
    bytes, and the `TEXTURE_NOT_DRAWN` warning. And a **DEFLATE**-compressed `patterns/` entry falls
    back to the named case with a warning saying the pattern could not be read out of the bundle —
    because this row has no inflater (Decision 5).
16. **Colorsmudge is legal but says so, with the exact words (D11, R40):** a `ColorSmudge` preset has
    `engine == "smudge"`, validates clean, and its warnings contain
    `"smudge engine is not built yet"` **and** exactly one such warning for the brush. *(The value is
    the literal `"smudge"` — `BrushValidate.ENGINES` already accepts it and this test is what proves
    no validator edit was needed.)*
17. **A bundle walks, names are trusted, and content decides (D13, D15):** a bundle with
    `brushes/{a.kpp,b.kpp}` and `paintoppresets/{c.kpp}` imports all three **in name order**; a bundle
    with `brushes/../evil.kpp` refuses that entry and imports the rest; a bundle with neither directory
    **refuses** rather than importing nothing quietly.
18. **Every budget refuses (D7), one test each at cap+1:** file bytes, chunk count, chunk bytes, text
    chunk bytes, XML depth 65, XML nodes, attributes per node, base64 length, brushes per bundle. Each
    names its cap. The `extensions` budget is **tested by R40 test 14d**, not here, because it is not
    this file's constant.
19. **XML hostile input is refused, not honoured (D9):** a DOCTYPE with an external entity, an
    undefined entity, a mismatched close tag, and a 200-deep nest. Each refuses with a message; none
    reads a file or a URL.
20. **Malformed `.kpp` files throw (D15):** not a PNG, a PNG with no `preset` chunk, a `preset` chunk
    that is not XML, an empty file, a 5-byte file. Each throws `BrushException` with a message.

**Command:** `./gradlew -p joybrush :core:jvmTest`. Passing = `BUILD SUCCESSFUL` and 0 failures in
`joybrush/core/build/test-results/jvmTest/`.

## Do not

- Do **not** decode PNG pixels. Read chunks; read the `IHDR` width and height; stop.
- Do **not** add an `Inflate.kt` here, do **not** call JB-8.02's, and do **not** add a second set of
  DEFLATE budgets (Decision 5). JB-8.02's `inflateRaw` is `internal` and reachable; refusing it is a
  decision with a reason, not a compiler error, so the reason is the thing to hold on to.
- Do **not** widen `ImportResult` or `ImportLibrary`, and do **not** invent a field on either to carry
  the tip's bytes. `extensions` is the field, it already exists, and two other specs read these types.
- Do **not** add a `BrushInput` constant. That is R3, a `BRUSH_VERSION` bump and a validator change,
  and it is not this row's business.
- Do **not** add a `GrainSpec` mode field. R31, a version bump, a validator change, and JB-1.05c's work.
- Do **not** import a non-`Pixel`/`ColorSmudge` engine "approximately". That is the row's whole point.
- Do **not** default a missing `paintopid` to `Pixel`.
- Do **not** set `license = "CC0"` for a pack that does not say so. R4 §B.9: Krita's default bundle is
  mixed and licences are **per resource**.
- **Do not build a licence/author display.** R40 settles that it happens later, in the brush detail
  sheet; this row carries the strings and stops.
- Do **not** re-derive `MAX_CURVE_POINTS`, or declare `MAX_EXTENSION_BYTES` or `TEXTURE_NOT_DRAWN` or
  a second `brushId` locally. Read all four from where they live (Sequencing). A second 64 or a second
  256 KiB is drift no build can catch.
- Do **not** re-derive a colour-jitter scale here — this row does not map colour jitter at all, and
  R4's Krita fidelity list does not claim it. If a fixture turns out to carry one, it goes to
  `extensions["krita.<key>"]` with a warning, and the question goes in the report.
- Do **not** run this spec at the same time as JB-8.01 or JB-8.02 — they share `ImportSupport.kt`.
- **Do not run this spec until JB-8.01 has landed.** See Sequencing. The dispatch order is
  **JB-8.01 → (JB-8.02 | this row)**, never two at once.
- Do **not** write a test that needs `java.*` — this row's fixtures are all hand-buildable, and a
  `jvmTest` file here is a sign that something is being built that the row does not need.

## Stop rule

If anything here is ambiguous, or a claim about the `.kpp` format turns out to be false when you build
the fixture, **STOP**: write the question in *Questions* under a heading `for the Lead`, set this row
`⛔ Blocked`, commit, push, and take another task. Three things are never a builder's call in this
file: **whether a non-`Pixel`/`ColorSmudge` engine may be approximated** (it may not — that is the
row), **whether a compressed `zTXt` may be inflated here** (it may not — Decision 5), and **where the
embedded tip's bytes go** (into `extensions`, as R40 rules — never onto someone else's type). If a
Krita texture turns out to be neither a name nor bytes this row can reach, keep whatever the file did
say in `extensions`, warn, and leave `paperGrain.source` at `"cloud"` — do not invent an image.

## Definition of done

- [ ] tests pass (paste the output of `./gradlew -p joybrush :core:jvmTest`)
- [ ] only owner-area files changed (paste `git status --short`)
- [ ] `ImportSupport.kt` was **read**, not created, if it already existed
- [ ] committed as `JB-8.04: Krita import`; pushed
- [ ] ROADMAP row → 🟧 Built *(there is no `INDEX.md` to update: it is a stub that points at
      `ROADMAP.md`)*

## Questions

### PROVISIONAL — Claude to confirm

Both are wording, both are reversible in one edit, and neither is a contract or a file format. The
builder executes the spec exactly as written.

1. **Decision 2b's sentence** — *"this brush uses two tilt sensors (xtilt and declination); Joy Brush
   has one tilt value, so the second is ignored"*. R40 ruled that this warning exists; the words are
   this spec's, and test 9b pins them.
2. **Decision 11's sentence** — *"this brush uses Krita's ColorSmudge engine; Joy Brush's smudge engine
   is not built yet, so this brush stamps for now"*. R40 ruled that ColorSmudge imports with a
   warning; the words are this spec's, and test 16 pins them.
3. **Decision 4's over-cap behaviour** — the image is dropped whole rather than truncated, and the
   R40 warning is still emitted. JB-8.01's Decision 4a and JB-8.02's Decision 3 are worded identically
   and reverse with this one.

### for the Lead — real, and **not** blocking this row

**Q1. Which row surfaces an imported brush's author and licence?** R40 settles the *what* (kept now,
surfaced later in the **brush detail sheet**) and settles that this row only carries the strings.
**But no row on the board owns that sheet** — it is not JB-2.01 (screen chrome), not JB-1.05b (brushes
in the view) and not any `D.*` row, and a sheet that shows nothing about a brush's provenance is a
sheet nobody will add on their own initiative. **Recommendation: a one-line `⚪ Outline` row, "brush
detail sheet: author, licence, source format", needs nothing, and it is the row R40 points at.** This
is not blocking: Decision 14 is correct and complete without it, and the strings survive in
`brush.json` until the sheet exists. **CC-BY 4.0 attribution is a licence obligation, not a nicety**
(R4 §B.9, Revoy's pack, the pack the blueprint names as the one to ship), so it should not wait on a
convenient row.

**Q2. What reads a stored tip image, and in what format?** Referred to **JB-1.05d**, and identical to
JB-8.01's Question 1 and JB-8.02's Question 1. This row hands JB-1.05d a real PNG; JB-8.02 hands it a
real PNG; JB-8.01 hands it PackBits gray. Nothing reads any of them today — which is why this does not
block — but JB-1.05d has to know, and either it reads by extension (which is why the names are honest)
or each importer needs a PNG encoder none of them has. **Recommendation: put it on the JB-1.05d
outline row's Needs line now.** Do not re-ask it in JB-8.01's or JB-8.02's file; it is already there.

**Q3. A base64 tip inside `brush.json` is a shareable-file-size decision, not an import one.** R40
stores the image in `extensions`, and `brush.json` is *"the smallest thing a person can share"*
(`BrushJson.kt:27-28`), so a 200 KiB tip travels inside every share of that brush — same in all three
rows, and the strongest argument for giving images their own channel rather than the `extensions` text
budget. **It belongs with the document-format rows (JB-0.02d / JB-1.05d), not with an importer.**
Raised once, in whichever of the three files reaches the Lead first.

**Checked and found TRUE against the landed source, 2026-09-29:**

- `ImportResult` is `data class ImportResult(val preset: BrushPreset, val warnings: List<String>)`
  (`MypaintImport.kt:29`) and carries **no bytes**; `ImportLibrary` does not exist yet; **neither type
  is used by anything outside `joybrush/`** (grepped: `MypaintImport` and `ImportResult` appear in
  `MypaintImport.kt` and its test, and nowhere else in the repository). So the previous version of
  Decision 4 really was unimplementable, and the fix is the mechanism, not the type.
- `BrushValidate.ENGINES` is `setOf("stamp", "smudge", "wet", ENGINE_FILL)` (`BrushValidate.kt:16`) and
  `BRUSH_VERSION` is `2` with only `ENGINE_FILL`/`BLEND_BEHIND` needing it (`BrushJson.kt:20`,
  `:118-123`) — so Decision 11 needs no version bump and no validator edit, exactly as Decision 11 and
  test 16 say.
- `TIP_SOURCES`/`GRAIN_SOURCES` contain `"image"` (`BrushValidate.kt:20-21`) and rule 23 (`:236-239`)
  checks only `isNullOrBlank()` — which is why test 14c is reachable and why the warning is not optional.
- `GrainSpec` has `enabled`, `source`, `image`, `scale`, `depth`, `edge`, `tiltGradient`, `radial` and
  **no mode field** (`BrushPreset.kt:44-53`) — so Decision 12's "no `GrainSpec` mode field" is a fact
  about the type, and rule 14's `scale` range is `0 < v ≤ 64`.
- `TipSpec.aspect` is `val aspect: Float = 0f` (`BrushPreset.kt:37`) and `paramsOf`
  (`BrushValidate.kt:254-263`) lists exactly eight `Param`s, so "no curve on `aspect`" is a fact.
- `BrushPreset.extensions` is `Map<String, String>` documented as *"lossless storage of imported raw
  settings"* (`BrushPreset.kt:82`) — which is exactly what R40 needs, and why no type has to change.
- `MAX_CURVE_POINTS` and `MAX_INPUTS` are `private const val` at `BrushValidate.kt:31` and `:28`. The
  Sequencing table is a fact about the tree.
- `PngWriter` is a **writer** only and no PNG reader exists anywhere in `joybrush/`
  (`javax.imageio.ImageIO` appears only in `jvmTest` and `androidkit/src/test`, always as an oracle) —
  which makes Decision 6's "no decoding" a fact about the tree rather than a preference.
- `JbArchive.unsafeReason` is `private fun` at `androidkit/.../io/JbArchive.kt:514`, so Decision 13's
  rule is a restatement across a module boundary, and the comment naming the other copy is what makes
  that honest.
- **`MAX_BASE64_CHARS` was already un-truncated** by the previous pass (`8 Mi chars (8 388 608)`, with
  the note saying so) and is kept; the whole of Decision 7's table was re-checked and **every row has
  a unit**.
