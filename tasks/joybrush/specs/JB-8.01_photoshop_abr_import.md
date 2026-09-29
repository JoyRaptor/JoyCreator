# JB-8.01 — Import Photoshop `.abr` brushes

| | |
|---|---|
| **Tier** | T2 |
| **Status** | see ROADMAP.md |
| **Needs** | JB-0.03 (brush format + validation). **Only JB-0.03. JB-8.03's open MAJOR is a warning that applies here too.** |
| **Owner area** | NEW `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/brush/imports/AbrImport.kt`, NEW `.../imports/AbrReader.kt` (big-endian container + Action Descriptor reader), NEW `.../commonTest/.../brush/imports/AbrImportTest.kt`, NEW `.../commonTest/.../brush/imports/AbrReaderTest.kt`, NEW `.../imports/ImportSupport.kt` (**only if it does not already exist** — Decision 2); EDIT `.../brush/BrushValidate.kt` (**two words only**: `MAX_INPUTS` and `MAX_CURVE_POINTS` `private` → `public`, so Decision 9 reads them instead of copying them) |
| **Estimated size** | ~600 lines + ~400 lines of tests |
| **Command** | `./gradlew -p joybrush :core:jvmTest` — 0 failures |

## Goal

R4 §F ranks `.abr` first for a reason: it is the interchange format every competitor reads
(Procreate, Clip Studio, Affinity, ArtRage, Krita, GIMP all import it), so one importer reaches the
most people's existing brush library. The owner wants this for Phase 8: *"Your favourite Photoshop
brush works."*

The reader is a **port of ag-psd's `src/abr.ts` (MIT)**, whose format knowledge is the best available
and whose licence is compatible. R4 §5: *"keep their copyright notices on anything ported"* — the
notice goes at the top of `AbrReader.kt` and is non-negotiable.

## THE Decision this row exists to get right

**What happens to input the reader cannot express.** Never silence. Every field falls into exactly
one of three buckets and the bucket is decided here, per field, before a line of code:

| Bucket | Meaning | Where it goes |
|---|---|---|
| **MAPPED** | Joy Brush has the setting and can express this value | the `BrushPreset` field |
| **LOSSY** | Joy Brush has the setting but not this much of it | the closest field, **plus a warning naming the setting and what was lost**, **plus the raw value in `extensions`** |
| **REFUSED** | Joy Brush cannot draw this at all — the result would be a *different brush*, not a coarser one | the **whole brush** is dropped from the library, with a sentence naming it |

The distinction that matters: **a slightly different brush is acceptable if the person is told. A
completely different brush is not acceptable at all.** That is why a bristle tip is REFUSED (a strand
simulation has no analogue — a round dab is a different brush wearing its name) while an airbrush tip
is LOSSY (a soft round dab is recognisably an airbrush, minus the spatter).

**And the rule that came out of the JB-8.03 review is a hard constraint here:** that review found the
MyPaint table *silently dropping* `opaque`'s and `hardness`'s input curves, so two of three fixture
brushes lost their opacity ramp and one imported nearly invisible. **Every curve-shaped input
(`szVr`, `opVr`, `prVr`, `H   `, `Brgh`, `Strt`, `scatterDynamics`, `countDynamics`, …) gets a
pressure/tilt/fade curve where its `bVTy` control maps, or it goes in `extensions` with a warning.**
None of them may be read as "just the base value".

## Contract

```kotlin
package cc.joycreator.joybrush.core.brush.imports   // "import" is a Kotlin keyword

/** A whole `.abr`: the brushes that converted, and the ones that were refused with reasons. */
data class ImportLibrary(
    val brushes: List<ImportResult>,            // REUSED from MypaintImport — not a second type (R23)
    val refused: List<RefusedBrush>,
) {
    /** One sentence for a screen: how many converted, how many were refused and why. Never empty. */
    fun summary(): String
}

data class RefusedBrush(val index: Int, val name: String, val reason: String)

object AbrImport {
    /**
     * Reads [bytes] and converts every brush it can.
     *
     * @param idPrefix prefix for generated ids; a brush's own name is used for its id, sanitised.
     * @throws BrushException if the FILE itself cannot be read (bad magic, unknown version, a
     *   section that overruns). One bad BRUSH is never a `BrushException` — it is a [RefusedBrush].
     */
    fun convert(bytes: ByteArray, idPrefix: String): ImportLibrary
}
```

`ImportResult(preset, warnings)` is **the type `MypaintImport` already returns** and is reused as it
stands. Do not define a second one (R23).

## The mapping (decided)

Leader: **base first, dynamics second** — a Photoshop brush with a size curve but no pressure control
must not lose the curve, so every dynamics object is read from its `{jitter, bVTy, fStp, Mnm }` shape
in full, and only then mapped.

### Tip

| Photoshop | ABR key | Joy Brush | Bucket |
|---|---|---|---|
| Diameter px | `Brsh.Dmtr` | `size.base` | MAPPED (clamped to `BrushValidate.MAX_SIZE_PX`, warn) |
| Hardness % | `Brsh.Hrdn` | `tip.hardness.base` = `Hrdn/100` | MAPPED |
| Angle ° | `Brsh.Angl` | `tip.angle.base` | MAPPED |
| Roundness % | `Brsh.Rndn` | `tip.aspect` = `−(1 − Rndn/100)` | MAPPED |
| Spacing % + on/off | `Brsh.Spcn`, `Brsh.Intr` | `spacing` = `Spcn/100` | MAPPED; **`Intr` off → `extensions["abr.spacingMode"] = "speed"`, LOSSY warning** |
| Sampled tip bitmap | `samp` + `Brsh.sampledData` | `tip.source = "procedural"`; diameter and a hardness from the bitmap | **LOSSY** (Decision 4) |
| Bristle tip | `dBrush` | — | **REFUSED** |
| Erodible tip | `dTips` (`dtipsType`) | — | **REFUSED** |
| Airbrush tip | `dTips` with `Shp ` = 5 | soft procedural (`hardness` from `dtipsHardness`) | **LOSSY**, warn |
| Flip X/Y | `Brsh.flipX/flipY` | — | LOSSY, `extensions`, warn (no flip field yet) |

### Shape dynamics — every one of these has a curve

| Photoshop | ABR | Joy Brush | Bucket |
|---|---|---|---|
| Size jitter % | `szVr.jitter` | `sizeJitter` | MAPPED |
| Size **control** | `szVr.bVTy` | `size.inputs[pressure|tilt]` multiply curve | **MAPPED** (control → curve, never dropped) |
| Size minimum % | `szVr.Mnm `, `minimumDiameter` | folded into that curve's y | MAPPED |
| Size fade steps | `szVr.fStp` | `size.inputs[distance]` | MAPPED |
| Angle jitter % | `angleDynamics` | `angleJitter` = `jitter × 360` | MAPPED |
| Angle control | `angleDynamics.bVTy` | `tip.angle.inputs[…]` | **MAPPED** |
| Roundness jitter | `roundnessDynamics` | `tip.aspect.inputs[…]` | MAPPED |
| Scatter % | `scatterDynamics` | `scatter.amount` | MAPPED |
| Scatter both axes | `bothAxes` | `scatter.bothAxes` | MAPPED |
| Count | `Cnt ` | `scatter.count` | MAPPED |
| Count jitter | `countDynamics.jitter` + `.bVTy` | `scatter.countJitter` + no curve (no field) | LOSSY if `bVTy ≠ 0`, warn |
| Tilt scale | `tiltScale` | y of `size.inputs[tilt]` | MAPPED |

### Texture, colour, transfer, toggles

| Photoshop | ABR | Joy Brush | Bucket |
|---|---|---|---|
| Pattern | `Txtr.Idnt` → `patt` | `paperGrain.source = "cloud"` | **LOSSY** — see Decision 5 |
| Pattern invert / scale / depth / each-tip / blend mode | `InvT`, `textureScale`, `textureDepth`, `TxtC`, `textureBlendMode` | `paperGrain.invert`(no field), `.scale`, `.depth`, `.enabled` | as marked; each unmapped key → `extensions` + warn |
| Texture brightness / contrast | `textureBrightness`, `textureContrast` | folded into the depth curve | **LOSSY**, warn (R4 A.5: the exact curve is unknown) |
| Opacity jitter + control + min | `opVr` | `opacity.inputs[…]` + base | **MAPPED (control → curve)** |
| Flow jitter + control | `prVr` | `flow.inputs[…]` | **MAPPED** |
| Hue / Sat / Brightness jitter | `H   `, `Strt`, `Brgh` | `color.hue/saturation/value` | **MAPPED** (all are 0..1 already) |
| Apply per tip | `colorDynamicsPerTip` | `color.perDab` | MAPPED |
| Wetness / mix jitter | `wtVr`, `mxVr` | — | **LOSSY**, `extensions`, warn (JB-1.06 not built) |
| Noise | `Nose` | — | LOSSY, warn |
| Wet edges | `Wtdg` | — | LOSSY, warn (Phase 6) |
| Build-up | `Rpt ` | `accumulate = "buildup"` | MAPPED |
| Tool opacity / flow | `toolOptions.Opct`, `toolOptions.flow` | `opacity.base`, `flow.base` | MAPPED |
| Smoothing % | `toolOptions.smoothingValue` | `smoothing` = `/100` | MAPPED |
| Everything else | any key not in this table | `extensions["abr.<path>"] = <raw>`, one warning **per group** | LOSSY |

### `bVTy` (the control) — R4 A.2's disputed codes

`0` Off · `1` Fade · `2` Pen Pressure · `3` Pen Tilt · `4` Stylus Wheel · **`5` Initial Direction ·
`6` Direction · `7` Initial Rotation · `8` Rotation** (ag-psd + abrkit's table, the most likely
reading; `abr-to-krita` disagrees on 6/7). Codes 5–8 have no `BrushInput` today:
`5`–`8` → `extensions["abr.bVTy.<setting>"]`, **one warning per file** saying the control codes are
inferred (Decision 8), and the **size/opacity control falls back to the fade curve so the jitter is
still bounded by `Mnm `** rather than being dropped.

### Metadata

`license = "unknown"` (`.abr` carries no licence; **never `"CC0"`**), `author = ""`,
`sourceFormat = "abr"`, `accumulate` per `Rpt `, `engine = "stamp"`.

## Decisions

1. **One bad brush never fails the file.** A pack is 200 brushes; a person wants the 190 that work. So
   `convert` throws `BrushException` only for a **file-level** fault (bad magic, unknown version, a
   section length that overruns the file, a descriptor tree deeper than the cap). Every other fault
   removes **that brush** and lands in `refused` with a sentence.
2. **`ImportLibrary`, `RefusedBrush` and `summary()` live in `ImportSupport.kt`, and the three Phase 8
   importers are SERIALISED against each other for that one file.** Whoever lands first creates it
   with exactly this content; the others read it and do not touch it. **Do not run two of JB-8.01,
   JB-8.02, JB-8.04 at once.** (This is the same discipline as the board's app-file order, for the
   same reason: two agents writing one new file is how a merge conflict becomes a lost hour.)
3. **Every budget is a constant in `AbrImport.kt`, named, and every one is a refusal.** The numbers
   below are the cap **and** the test that it is enforced. A declared size is a wish; a length inside a
   file bounds only the file's claim about itself.
   | Budget | Cap |
   |---|---|
   | `MAX_FILE_BYTES` | 64 MiB |
   | `MAX_SECTIONS` | 4 096 |
   | `MAX_SECTION_BYTES` | 32 MiB |
   | `MAX_BRUSHES` | 2 048 |
   | `MAX_DESCRIPTOR_DEPTH` | 32 |
   | `MAX_DESCRIPTOR_NODES` | 200 000 |
   | `MAX_LIST_ITEMS` | 20 000 per list |
   | `MAX_STRING_BYTES` | 64 KiB |
   | `MAX_TIP_DIM` | 2 048 px on a side |
   | `MAX_TIP_PIXELS` | 4 194 304 (2 048²) |
   | `MAX_TIP_BYTES` | 16 MiB decoded |
   | `MAX_EXTENSION_BYTES` | 256 KiB total per brush |
4. **A sampled tip is LOSSY, not refused** (Decision: a stand-in that is recognisably the same brush).
   The stand-in is a procedural tip at the bitmap's own diameter, with `hardness` taken from the
   bitmap's mean alpha (`0.25 + 0.7 × mean`), and the bitmap's raw bytes go into
   `extensions["abr.sampledTip"]` (base64, capped by `MAX_EXTENSION_BYTES`) so a future tip-image
   engine can use them without re-importing. The warning says: *"bitmap tip: imported as a soft round
   tip, because Joy Brush has no image-tip engine yet (JB-1.05c)."* **The bitmap is never decoded into
   pixels** — no PNG, no inflate, no image maths in this row.
5. **A texture pattern is LOSSY and the grain is left off.** `patt` pattern data is not an image file
   Joy Brush can load, and `paperGrain.source = "image"` would need the same unbuilt image engine as
   Decision 4 — and worse, it would **validate clean and then draw nothing**, which is the
   silent-nonsense door. So: the pattern's id and its descriptor values go to `extensions`, the grain
   stays disabled, and the warning says *"texture pattern kept, not drawn yet."*
6. **`Intr` (spacing off) cannot be expressed and is a LOSSY warning**, because our placer always
   spaces. Being honest about it beats pretending the spacing is Photoshop's.
7. **A zlib-compressed `samp` payload refuses that one brush.** R4 notes some newer files compress tip
   data; decoding zlib would mean an inflate decoder in `commonMain`, which JB-8.02 needs anyway and
   this row must not become the owner of (Decision 2's serialisation). So: refuse the brush, name the
   compression, and **keep reading the file** — a pack with a few compressed tips still gives the rest.
8. **The `bVTy` codes 5–8 are inferred and the file says so once.** One warning per `convert` call, not
   per brush (see the JB-8.03 review's Finding 2: a warning that fires on every brush trains people to
   ignore warnings). Never per-brush noise for a whole-file fact.
9. **A shared constant is never copied.** This row's curve budgets are
   `BrushValidate.MAX_INPUTS` / `MAX_CURVE_POINTS`, **which this spec makes `public const`** in
   `BrushValidate.kt` (two words changed) and then reads. It does not declare its own 64.
   *(R19's rule, and see Q1 — `MypaintImport.kt:35` currently holds a private copy of that same 64.)*
10. **Long before Int.** Tip bounds arrive as four signed 32-bit numbers read as **Long**, and the
    width/height subtraction is done in Long and refused if the result is ≤ 0, above `MAX_TIP_DIM`, or
    above `Int.MAX_VALUE`. Never `(right - left).toInt()`.
11. **Everything ends up validate-clean or absent.** Every converted preset must pass
    `BrushValidate.validate` with **no problems**; each is clamped *before* the call with its own
    warning naming the field and the number, and anything still left over is a **refusal of that
    brush** with the validator's own sentence. (JB-8.03's Q9 chose "leftover problems become
    warnings"; here a leftover problem means the mapping is wrong, and the house rule is *refuse in
    words*.)
12. **`sourceFormat = "abr"` and no `BRUSH_VERSION` bump.** `sourceFormat` is already in the preset's
    documented word list (`"abr"` is written in `BrushPreset`'s own KDoc), so no R3 bump is needed and
    `validate` must not be edited to allow it.

## Steps

1. Tests first: `AbrReaderTest` (container) then `AbrImportTest` (mapping).
2. `ImportSupport.kt` **if absent** — exactly Decisions 2 and `ImportLibrary`'s declaration.
3. `BrushValidate.kt`: `MAX_INPUTS` and `MAX_CURVE_POINTS` `private` → `public`.
4. `AbrReader.kt`: big-endian cursor, `8BIM` sections, `samp`, `desc` (Action Descriptor), `patt`,
   `phry`; the ag-psd MIT notice at the top; a hard `BrushException` on any budget overrun.
   **`ByteReader` is NOT reused and NOT edited** — it is little-endian and throws
   `StrokeCodecException`, which is a stroke-format exception.
5. `AbrImport.kt`: budgets → sections → brushes → the mapping table, field by field.

## Tests

**Fixtures.** Structural cases are **hand-built byte strings in the test** so the suite needs no
download. The end-to-end case is **one real `.abr` pasted as a base64 constant**, with its name,
author and licence in a comment beside it — the same discipline JB-8.03 used for its three `.myb`
brushes. Use a CC0 or public-domain pack (R4 §B.9 names K. M. Alexander's cartography set as CC0).

**`AbrReaderTest`**
1. **Big-endian, not little:** a 4-byte `0x01020304` reads as `0x01020304`, **not** `0x04030201`. This
   test exists because `ByteReader` is the other endianness and a builder who reaches for it will get
   a file that parses and is nonsense.
2. **Every budget refuses (D3), one test each, at cap+1:** file over `MAX_FILE_BYTES`; section count
   over `MAX_SECTIONS`; a section whose declared length overruns the file; descriptor depth 33;
   `MAX_DESCRIPTOR_NODES`; a list of `MAX_LIST_ITEMS + 1`; a string of `MAX_STRING_BYTES + 1`;
   `MAX_BRUSHES + 1` brushes. Each throws `BrushException` **naming the cap**, and none throws
   `OutOfMemoryError`.
3. **A truncated file is a refusal, not a partial brush (D1):** a file cut in half mid-`desc` throws
   with a message containing "truncated"; nothing is returned.
4. **A section count that disagrees with the sections present refuses** rather than being believed.
5. **PackBits decoding** (RLE across a 40 000-byte tip): a known run-length pattern decodes to the
   expected gray ramp, and a truncated run at the end of the payload refuses rather than filling with
   zeros.

**`AbrImportTest`**
6. **The real `.abr` converts, every brush validates clean, and the count is asserted (D11):** each
   preset has `sourceFormat = "abr"`, `license = "unknown"` (never `"CC0"`), `engine = "stamp"`, and
   `BrushValidate.validate` returns **empty**. Non-vacuity: assert `brushes.isNotEmpty()`.
7. **THE JB-8.03 TEST, written for this file: no mapped curve is dropped.** Build a synthetic brush
   whose `szVr` has `jitter = 50` and `bVTy = 2` (pen pressure) and whose `opVr` has `jitter = 40`,
   `bVTy = 2`, `Mnm ` = 10 — then assert `size.inputs` and `opacity.inputs` are **non-empty** and each
   carries the expected `BrushInput.pressure`. The same for `scatterDynamics`, `countDynamics` and
   `angleDynamics`. **This is the test that would have caught JB-8.03's Finding 1**, and it is the
   single most important assertion in this spec.
8. **Diameter and roundness convert exactly (D-mapping):** `Dmtr = 40` → `size.base == 40f`;
   `Rndn = 50` → `tip.aspect == -0.5f`; `Hrdn = 75` → `tip.hardness.base == 0.75f`; `Spcn = 25` →
   `spacing == 0.25f`. Each within 1e-4.
9. **Each LOSSY bucket says so (D4, D5, D6, D8):** a synthetic sampled-tip brush produces
   `extensions["abr.sampledTip"]` **and** a warning containing "bitmap tip"; a textured brush produces
   a warning containing "texture pattern kept, not drawn"; an `Intr = false` brush warns about
   spacing; a `bVTy = 6` brush records the control in `extensions` and the file-level warning appears
   **exactly once** across the whole library. **Per-brush warnings for whole-file facts are the JB-8.03
   review's Finding 2 and there is a test that fails if they come back.**
10. **Each REFUSED bucket drops exactly that brush (D1):** a synthetic file with a good brush, then a
    `dBrush` bristle brush, then a `dTips` erodible brush, then a zlib-compressed tip → two brushes
    survive, `refused.size == 3`, and **each reason names the brush** (`name` is non-empty and
    contains "bristle" / "erodible" / "compressed"). Non-vacuity: assert the two survivors are the
    good ones by id.
11. **One bad brush does not kill the file (D1):** the fixture from test 10 round-trips through
    `convert` and the survivors are `==` what a file containing only them would have produced.
12. **Long arithmetic (D10):** a sampled tip whose declared bounds are `left = -2147483648`,
    `right = 2147483647` is **refused** with a message about the size, not a negative `Int` and not a
    crash. A tip of `MAX_TIP_DIM + 1` is refused the same way.
13. **`MAX_EXTENSION_BYTES` is real (D3):** a brush whose unmapped `extensions` would exceed 256 KiB is
    refused for that, not silently truncated. A brush just under it is fine and its `extensions` are
    complete.
14. **Nothing validate-clean is left broken (D11):** a brush with `spacing` of 0.001 (outside
    `0.005..5`) is **clamped and warned** — the warning names the field and both numbers — and the
    result validates clean. A value that cannot be clamped into range refuses the brush.
15. **Malformed input is a refusal or an exception, never a partial preset:** random bytes, a valid
    header with a garbage `desc`, an empty file, and a 3-byte file each throw `BrushException` with a
    message; none returns an `ImportLibrary` holding an empty `brushes` list without a `refused` entry
    or an exception.

**Command:** `./gradlew -p joybrush :core:jvmTest`. Passing = `BUILD SUCCESSFUL` and 0 failures in
`joybrush/core/build/test-results/jvmTest/`.

## Do not

- Do not **reuse or edit `ByteReader`** — it is little-endian and throws `StrokeCodecException`.
- Do not define a second `ImportResult`/`ImportLibrary` shape; reuse `ImportResult` verbatim (R23).
- Do not decode an image. No PNG, no inflate, no zlib in this row (Decisions 4 and 7).
- Do not map a control (`bVTy`) to a base value. A curve or `extensions` — never "just the base".
- Do not set `license = "CC0"`. `.abr` carries no licence; R4 §5 is explicit that redistributing
  converted third-party packs is not ours to do.
- Do not clamp silently. Every clamp has a warning that names the field and both numbers.
- Do not write `extensions` past `MAX_EXTENSION_BYTES`.
- Do not touch `BrushPreset.kt`, `BrushJson.kt` or `dynamics/` — JB-0.03b owns validation and
  `BRUSH_VERSION`.

## Definition of done

- [ ] tests pass (paste output)
- [ ] only owner-area files changed (paste `git status --short`)
- [ ] the ag-psd MIT notice is at the top of `AbrReader.kt` (paste the first 10 lines)
- [ ] committed as `JB-8.01: Photoshop .abr import`; pushed
- [ ] ROADMAP row → 🟧 Built

## Questions

**Q1. `MypaintImport.kt:35` has a private copy of `MAX_CURVE_POINTS = 64`, and JB-8.03's own KDoc
concedes it is "`BrushValidate`'s own per-curve cap". Decision 9 makes that constant public so this row
can read it — which means there are now two literals that can drift, and the second one is in Built
code.** R19 says a shared constant is never copied, and the review that caught the MyPaint opacity
curves is the same family of bug. **Ruling needed:** may I edit `MypaintImport.kt` to delete its copy
and read the shared constant? It is one line and it is outside this spec's owner area, so I have not
touched it — but leaving it is knowingly shipping a drift.

**Q2. BLOCKING for the sampled-tip call. Decision 4 LOSSY-maps a bitmap tip to a soft procedural stand-in.
Is that right, or should a sampled-tip brush be REFUSED?** I chose LOSSY because the owner's Phase 8
acceptance is *"your favourite Photoshop brush works"* and **the overwhelming majority of real `.abr`
brushes are sampled tips** — refusing them all would import perhaps the minority and the row would look
broken. But the JB-8.03 review's reasoning cuts the other way: *"imports successfully, draws
differently is the worse outcome."* My reconciliation is that a bitmap tip's stand-in is *recognisably
the same brush* (right size, right hardness, same place on the stack), whereas MyPaint's `opaque` base
of `2.5e-05` made a visible brush **invisible**. **If you disagree, Decision 4 and tests 9 and 10 are
the two places to change.**

**Q3. Does `hardness` derived from the bitmap's *mean* alpha match anything?** R4 A.3 is explicit that
Adobe has never published the hardness curve, and lists four incompatible implementations (GIMP's
`exponent = 0.4/(1−hardness)` Gaussian-ish, MyPaint's two linear segments in `r²`, Krita's
`hfade = Hrdn/100`, brushkit's uncalibrated approximation). My `0.25 + 0.7 × mean` is a third guess and
it is **not in any of the four**. It is one line and it is pinned by a test so it is at least visible
when someone calibrates a real Photoshop screenshot — which is R4's own advice and is not in any row.
**Do you want a calibration task, or is "documented and wrong-but-sane" acceptable for now?**

**Q4. Budgets (Decision 3) — the two I am least sure of.** `MAX_FILE_BYTES` at 64 MiB: large CC0
`.abr` packs exist and I have not measured one. `MAX_BRUSHES` at 2 048: I have no idea what the largest
real pack is. Both are one constant each and both are refusals, so being too low means a pack that
"does not import" with a clear message. **Would you rather they were generous (and a big file took
30 s) or tight (and some packs refuse)?**

**Q5. `paperGrain.source = "image"` exists in the brush format and `BrushValidate` accepts it, but
nothing in the engine reads an image.** Decision 5 refuses to set it for that reason. **Is there a row
for image tips and image grain, or should this importer set `source = "image"` anyway and let the
untouched engine ignore it?** My answer is no — a file that validates clean and then draws nothing is
the exact "silent nonsense" `BrushJson.decodeChecked`'s own KDoc was written to prevent, and the refusal
plus a warning is this project's house answer.
