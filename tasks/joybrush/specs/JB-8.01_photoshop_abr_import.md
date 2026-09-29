# JB-8.01 — Import Photoshop `.abr` brushes

| | |
|---|---|
| **Tier** | T2 |
| **Status** | 🟦 Ready |
| **Who** | spec writer (unattributed in the original) · **xr: openrouter/stealth/space-bunny-alpha 2026-09-29** — checked the whole mapping table against `BrushPreset.kt` and `BrushValidate.kt` and **found two fields that do not exist**: the row mapped `roundnessDynamics` to `tip.aspect.inputs[…]` and `colorDynamicsPerTip` to `color.perDab`, and `tip.aspect` is a plain `Float` with no `inputs` while the per-dab flag is `color.perStroke`. Both corrected below. Verified true: `BrushValidate.MAX_INPUTS`/`MAX_CURVE_POINTS` are `private const` (`:28`, `:31`) so making them public is two words; `MAX_SIZE_PX` is already public (`:25`) so the diameter clamp reads the shared one; `spacing` really is ranged `0.005..5` by rule 4; `sourceFormat` carries no validator rule and `"abr"` is already in the documented word list at `BrushPreset.kt:81`; `MypaintImport.kt:35` holds the `MAX_CURVE_POINTS = 64` copy the Q1 names, **and `:201` holds a second drift the Q1 did not name**. Ruled provisionally on Q1, Q2, Q3, Q4 and Q5 — all reversible, none a contract. Added the stop rule and a hard sequencing rule against JB-8.02 / JB-8.04.<br>**xr: openrouter/stealth/space-bunny-alpha 2026-09-29 (R40 pass)** — the image-source question is now settled by LEAD_RULINGS **R40** (option (a), uniform for all three importers) and this row's Decisions 4 and 5 were **rewritten to it**: every image tip/grain is stored and set `source = "image"` with the R40 warning verbatim. Consequences: Decision 2 now also publishes the shared `MAX_EXTENSION_BYTES` (all three rows state `256 KiB` and all three now read one constant), Decision 9 is now **R40-ruled** — R40 permits this row to edit `MypaintImport.kt`, so the two drifting copies are deleted instead of left — and `MypaintImport.kt` is in the owner area. The 0–100 % colour-jitter claim was **wrong twice**: the note claimed a blind `/100` is "correct whether the file stores 0..1 or 0..100", which is false (under the 0..1 reading it silently divides a real jitter by 100 with no warning); it is now a **magnitude-detecting** normalise, cited to R4 §A.7, with four named tests. Budgets and the hardness stand-in are R40-ruled, not provisional. Q6 is **answered** — removed, and its answer is the identical paragraph now in JB-8.02 and JB-8.04. |
| **Needs** | JB-0.03 (brush format + validation). **Only JB-0.03.** JB-8.03's open MAJOR is a warning that applies here too. **This row CREATES what JB-8.02 and JB-8.04 need** (Decision 2, Decision 9); the board records neither direction, so the orchestrator's Needs change is in the report, not here. |
| **Owner area** | NEW `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/brush/imports/AbrImport.kt`, NEW `.../imports/AbrReader.kt` (big-endian container + Action Descriptor reader), NEW `.../commonTest/.../brush/imports/AbrImportTest.kt`, NEW `.../commonTest/.../brush/imports/AbrReaderTest.kt`, NEW `.../imports/ImportSupport.kt` (**only if it does not already exist** — Decision 2); EDIT `.../brush/BrushValidate.kt` (**two words only**: `MAX_INPUTS` and `MAX_CURVE_POINTS` `private` → `public`, so Decision 9 reads them instead of copying them); EDIT `.../imports/MypaintImport.kt` (**permitted by R40, four lines: delete the private `MAX_CURVE_POINTS` at `:35` and the private `MAX_SIZE_PX` at `:201`, add the two `BrushValidate` imports** — nothing else in that file changes) |
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
stands. Do not define a second one (R23). It is declared, verbatim, at `MypaintImport.kt:29`:

```kotlin
data class ImportResult(val preset: BrushPreset, val warnings: List<String>)
```

### `ImportSupport.kt` — created by this row, read by all three (Decision 2)

Pasted here in full so JB-8.02 and JB-8.04 can be executed without going to look for it:

```kotlin
package cc.joycreator.joybrush.core.brush.imports

/** A whole imported pack: the brushes that converted, and the ones that were refused with reasons. */
data class ImportLibrary(
    val brushes: List<ImportResult>,
    val refused: List<RefusedBrush>,
) {
    /** One sentence for a screen: how many converted, how many were refused and why. Never empty. */
    fun summary(): String
}

data class RefusedBrush(val index: Int, val name: String, val reason: String)

/**
 * The most `extensions` text one brush may carry, in characters, across every key.
 *
 * 256 KiB, because all three Phase 8 importers say 256 KiB and R23 says a number that is written
 * three times is one writer believing it three times. It counts the base64 of a stored tip or grain
 * image as well as every unmapped setting, so Decision 4a and Decision 5 decide what happens when
 * an image does not fit — not a fourth number in a fourth place.
 */
const val MAX_EXTENSION_BYTES = 256 * 1024

/**
 * The exact sentence every Phase 8 importer puts in a warning when a tip or grain texture is
 * carried but not drawn yet. LEAD_RULINGS R40, option (a), uniform for JB-8.01 / 8.02 / 8.04.
 *
 * It is `const` and not a builder-time constant so that the three importers cannot drift apart, and
 * the test for it in each row asserts the *string*, not the message around it.
 */
const val TEXTURE_NOT_DRAWN = "this brush's texture is kept but not drawn yet"

/**
 * A library-unique id for an imported brush, from the caller's [prefix] and the brush's own [name].
 *
 * One sanitiser for all three Phase 8 importers, because the three rules below are the ones that
 * matter and a second copy of them is three chances to disagree: lower-case; every run of characters
 * that is not `a`–`z`, `0`–`9`, `.`, `-` or `_` becomes a single `-`; the result never begins or ends
 * with `-`; empty parts are dropped; a result with nothing left in it is `"brush"`. Ids become file
 * and folder names, so this is the one place the "ids are safe" rule and the "a name from a stranger"
 * rule meet.
 */
fun brushId(prefix: String, name: String): String {
    fun clean(s: String) = s.lowercase()
        .map { c -> if (c in 'a'..'z' || c in '0'..'9' || c == '.' || c == '-' || c == '_') c else '-' }
        .joinToString("")
        .replace(Regex("-+"), "-")
        .trim('-')
    val parts = listOf(clean(prefix), clean(name)).filter { it.isNotEmpty() }
    return parts.joinToString(".").ifEmpty { "brush" }
}
```

**One more thing in that file, and it is R40's, not this row's:** the shared warning sentence lives
here too, so "the wording is the same in all three specs" is a fact about the code and not a promise
in three markdown files. Each importer's test asserts `TEXTURE_NOT_DRAWN` by value. `brushId` is here
for the same reason: the three importers all turn a stranger's brush name into a library id, and three
copies of a sanitiser is three chances to disagree about which characters are safe.

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
   * **This row:** its sampled tips are **PackBits gray, not a PNG**, so it writes
     `tip.image = "tip.packbits"`. Writing `"tip.png"` over PackBits bytes would be a lie recorded in
     a file a future writer would then materialise, and the spec's own rule (*never substitute a thing
     the artist did not make*) forbids it.
   * **JB-8.02** and **JB-8.04** do have real PNGs in the file, so they write `tip.png` and
     `grain.png`.

The alternative — a procedural stand-in and no stored bytes — was considered for all three rows and
**refused for all three**. It loses the artist's actual tip, and refusing the brush instead refuses
most of Procreate and much of Revoy's Krita pack.

**When a file carries a texture Joy Brush cannot put a name on, the warning is still this one.** The
sentence is the same whether the texture was stored, could not be stored, or was never an image: this
row's Photoshop `patt` pattern (Decision 5) and JB-8.04's texture modes (JB-8.04 Decision 12) both
emit it. A reader must be able to grep the three importers for one string.

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
| Sampled tip bitmap | `samp` + `Brsh.sampledData` | `tip.source = "image"`, `tip.image = "tip.packbits"`, bytes in `extensions["abr.tipImage"]`; **plus** a procedural stand-in for the numbers (diameter, hardness) | **LOSSY + R40** (Decisions 4, 4a) |
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
| Angle control | `angleDynamics.bVTy` | `tip.angle.inputs[…]` | **MAPPED** (`tip.angle` is a `Param`, so it takes inputs) |
| Roundness jitter | `roundnessDynamics` | **no field** — `extensions["abr.roundnessDynamics"]` + one warning | **LOSSY** |
| Scatter % | `scatterDynamics` | `scatter.amount` (a `Param`, so it takes inputs) | MAPPED |
| Scatter both axes | `bothAxes` | `scatter.bothAxes` | MAPPED |
| Count | `Cnt ` | `scatter.count` | MAPPED |
| Count jitter | `countDynamics.jitter` + `.bVTy` | `scatter.countJitter` + no curve (no field) | LOSSY if `bVTy ≠ 0`, warn |
| Tilt scale | `tiltScale` | y of `size.inputs[tilt]` | MAPPED |

> **Cross-reviewer correction (2026-09-29): roundness dynamics has no home.** The original table said
> `roundnessDynamics → tip.aspect.inputs[…]`. There is no such thing: `TipSpec.aspect` is
> `val aspect: Float = 0f` (`BrushPreset.kt:37`) — a bare float with **no `inputs`, no `base`** — so a
> curve cannot be attached to it at all, and writing one would not compile. Roundness is the same
> shape of loss as "flip X/Y" two rows up: Joy Brush has the *setting* but not a curve on it. So it
> follows the row's own rule — LOSSY, raw value in `extensions["abr.roundnessDynamics"]`, one warning
> naming the setting. The jitter *percentage* is the part that could map, and there is nowhere to put
> it either, so it rides in the same `extensions` entry.
>
> By contrast `tip.angle` (`val angle: Param = Param(0f)`), `size`, `opacity`, `flow` and
> `scatter.amount` **are** `Param`s and do take `inputs` — those five curves are real.

### Texture, colour, transfer, toggles

| Photoshop | ABR | Joy Brush | Bucket |
|---|---|---|---|
| Pattern | `Txtr.Idnt` → `patt` | **no image to store** — the id and the descriptor values go to `extensions["abr.texturePattern"]`, the grain stays `enabled = false` | **LOSSY + R40 warning** — see Decision 5 |
| Pattern invert / scale / depth / each-tip / blend mode | `InvT`, `textureScale`, `textureDepth`, `TxtC`, `textureBlendMode` | `paperGrain.invert`(no field), `.scale`, `.depth`, `.enabled` | as marked; each unmapped key → `extensions` + warn |
| Texture brightness / contrast | `textureBrightness`, `textureContrast` | folded into the depth curve | **LOSSY**, warn (R4 A.5: the exact curve is unknown) |
| Opacity jitter + control + min | `opVr` | `opacity.inputs[…]` + base | **MAPPED (control → curve)** |
| Flow jitter + control | `prVr` | `flow.inputs[…]` | **MAPPED** |
| Hue / Sat / Brightness jitter | `H   `, `Strt`, `Brgh` | `color.hue/saturation/value` | **MAPPED, scale decided by the magnitude** — see the note below |
| Apply per tip | `colorDynamicsPerTip` | `color.perStroke = true` | MAPPED (and see the note below) |
| Wetness / mix jitter | `wtVr`, `mxVr` | — | **LOSSY**, `extensions`, warn (JB-1.06 not built) |
| Noise | `Nose` | — | LOSSY, warn |
| Wet edges | `Wtdg` | — | LOSSY, warn (Phase 6) |
| Build-up | `Rpt ` | `accumulate = "buildup"` | MAPPED |
| Tool opacity / flow | `toolOptions.Opct`, `toolOptions.flow` | `opacity.base`, `flow.base` | MAPPED |
| Smoothing % | `toolOptions.smoothingValue` | `smoothing` = `/100` | MAPPED |
| Everything else | any key not in this table | `extensions["abr.<path>"] = <raw>`, one warning **per group** | LOSSY |

> **Two corrections in these last two rows (cross-reviewer, 2026-09-29).**
>
> **(a) The per-dab flag is `color.perStroke`, not `color.perDab`.** `ColorJitter` is
> `data class ColorJitter(val hue: Float = 0f, val saturation: Float = 0f, val value: Float = 0f,
> val perStroke: Boolean = false)` (`BrushPreset.kt:57`). There is no `perDab`; `color.perDab = true`
> would not compile. Set `perStroke = true`.
>
> **(b) "All are 0..1 already" cannot be verified from this tree, and a blind `/100` is worse than
> no answer.** `color.hue/saturation/value` are all ranged `0f..1f` by `BrushValidate` rule 15, and
> Decision 11 says a preset that does not validate clean is a **refusal of the whole brush**. So the
> scale has to be right, and it is not written down anywhere in this repo: **R4 §A.7 states the
> *Photoshop UI* is "0–100% random offsets in HSB" (`R4_photoshop_and_formats.md:220`) while R4's own
> mapping table marks the file mapping `≈` with "Distribution unknown" (`:774`)** — the UI scale is
> documented, the *stored* scale is not. An earlier pass of this spec asserted the divide was
> "correct whether the file stores 0..1 or 0..100". **That claim is false, and it fails silently:** if
> the file stores 0..1, `Brgh = 0.5` becomes `0.005` — a 100× too-small jitter that is still inside
> `0f..1f`, so rule 15 says nothing, no clamp fires, and **no warning can be produced**. That is the
> exact "imports successfully, draws differently" outcome this spec exists to prevent, written into
> the mapping itself.
>
> **The mapping is therefore written so the magnitude decides, and the ambiguity is said out loud.**
> For a raw jitter `v` in any of the three fields:
>
> | Raw `v` | Read as | Warning |
> |---|---|---|
> | `v == 0f` | `0f` | **none** — both readings agree |
> | `0f < v <= 1f` | `v`, **unresolved** — it is either `v` or `v/100` | the **per-file** warning of Decision 8, naming the field as an unresolved scale |
> | `v > 1f` | `v / 100`, clamped to `1f` | the **per-file** warning of Decision 8, naming the field as a percentage; **plus** a per-brush clamp warning if the divide still left it above `1f` |
>
> Nothing is picked silently, nothing is divided on a guess, the raw value always goes to
> `extensions["abr.<key>"]`, and a whole pack of 0–1 files produces **one** warning for the whole file
> rather than 200 (Decision 8's rule, JB-8.03's Finding 2). **Four named tests** — see Tests 9b.

### `bVTy` (the control) — R4 A.2's disputed codes

`0` Off · `1` Fade · `2` Pen Pressure · `3` Pen Tilt · `4` Stylus Wheel · **`5` Initial Direction ·
`6` Direction · `7` Initial Rotation · `8` Rotation** (ag-psd + abrkit's table, the most likely
reading; `abr-to-krita` disagrees on 6/7). Codes 5–8 have no `BrushInput` today:
`5`–`8` → `extensions["abr.bVTy.<setting>"]`, **one warning per file** saying the control codes are
inferred — the *same* one sentence that carries the unresolved colour-jitter scales (Decision 8, one
warning per `convert`, listing every whole-file fact) — and the **size/opacity control falls back to
the fade curve so the jitter is still bounded by `Mnm `** rather than being dropped.

### Metadata

`license = "unknown"` (`.abr` carries no licence; **never `"CC0"`**), `author = ""`,
`sourceFormat = "abr"`, `accumulate` per `Rpt `, `engine = "stamp"`.

## Decisions

1. **One bad brush never fails the file.** A pack is 200 brushes; a person wants the 190 that work. So
   `convert` throws `BrushException` only for a **file-level** fault (bad magic, unknown version, a
   section length that overruns the file, a descriptor tree deeper than the cap). Every other fault
   removes **that brush** and lands in `refused` with a sentence.
2. **`ImportLibrary`, `RefusedBrush`, `summary()`, `MAX_EXTENSION_BYTES`, `TEXTURE_NOT_DRAWN` and
   `brushId` live in `ImportSupport.kt`, and the three Phase 8 importers are SERIALISED against each
   other for that one file.** Whoever lands first creates it with exactly the content in the Contract
   section above; the others read it and do not touch it. **This row is the one that creates it**,
   because it is first on the board, and because it is also the only one of the three that makes
   `BrushValidate.MAX_INPUTS` / `MAX_CURVE_POINTS` public (Decision 9) — and **JB-8.02 and JB-8.04
   read those two constants**, so neither of them compiles until this row lands. Under R40 they read
   two more things from this file (`MAX_EXTENSION_BYTES`, `TEXTURE_NOT_DRAWN`, `brushId`), so the
   dependency is now five symbols wide, not two. Their *Needs* say `0.03` only; that is a board
   inaccuracy and this row's Do-not list says so in words. **Do not run two of JB-8.01, JB-8.02,
   JB-8.04 at once.** (This is the same discipline as the board's app-file order, for the same reason:
   two agents writing one new file is how a merge conflict becomes a lost hour.)
3. **Every budget is a constant in `AbrImport.kt`, named, and every one is a refusal.** The numbers
   below are the cap **and** the test that it is enforced. A declared size is a wish; a length inside a
   file bounds only the file's claim about itself. `MAX_EXTENSION_BYTES` is the one budget that is
   **not** declared here — it is `ImportSupport`'s, read (Decision 2), because all three rows state
   256 KiB.
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
4. **A sampled tip is LOSSY and, under R40, STORED — the two are not alternatives.** Decision: a
   stand-in that is recognisably the same brush, **and** the artist's own bytes kept for JB-1.05d.
   * **The numbers come from the bitmap, so the brush paints at the right size today:** `size.base` is
     the bitmap's own width, and `tip.hardness.base = 0.25 + 0.7 × meanAlpha` (R40 accepts this
     stand-in; calibration is a later row and does not block this one). A procedural
     `tip.source` is **not** what is written — R40 writes `"image"`.
   * **The bytes go to `extensions["abr.tipImage"]`, base64**, and `tip.image = "tip.packbits"` (R40's
     "the name names the encoding").
   * **The warning is the R40 sentence, word for word:** `this brush's texture is kept but not drawn
     yet`, from `ImportSupport.TEXTURE_NOT_DRAWN`, **plus** the existing half that says what the
     numbers are standing in for. The full warning is two sentences: the R40 one, then
     *"bitmap tip: imported as a soft round tip, because Joy Brush has no image-tip engine yet
     (JB-1.05d)."*
   * **The bitmap is never decoded into pixels** — no PNG, no inflate, no image maths in this row.
     `PackBits` → `meanAlpha` needs one pass over the decoded bytes, which `MAX_TIP_BYTES` bounds.
4a. **A tip whose base64 does not fit `MAX_EXTENSION_BYTES` is NOT stored, and is not truncated.**
   The bytes are dropped whole, `tip.source` stays `"procedural"`, the **numbers and the hardness
   stand-in of Decision 4 are unchanged**, and the warning carries the R40 sentence **plus** the size
   and the cap. Storing a prefix of an image is the one thing this spec will not do: it is a file
   that is not the thing it says it is. *(PROVISIONAL — Claude to confirm. Reversible in one branch
   of one function: the alternative is to raise the shared cap, which is a `brush.json` size
   decision, not this row's.)*
5. **A texture pattern carries the R40 warning, and the grain stays off.** `patt` pattern data is not
   an image file under any name Joy Brush could give it, so there is nothing to store and
   `paperGrain.enabled` stays `false`. What is kept is the pattern's id and its descriptor values, in
   `extensions["abr.texturePattern"]`, and the warning carries `ImportSupport.TEXTURE_NOT_DRAWN`
   **word for word**, with the id named beside it — so the same grep finds it here, in JB-8.02's
   grain and in JB-8.04's texture. *Why not `source = "image"` for it: R40's rule is "store the
   image", and there is no image.*
6. **`Intr` (spacing off) cannot be expressed and is a LOSSY warning**, because our placer always
   spaces. Being honest about it beats pretending the spacing is Photoshop's.
7. **A zlib-compressed `samp` payload refuses that one brush.** R4 notes some newer files compress tip
   data; decoding zlib would mean an inflater in `commonMain`, which JB-8.02 owns after R40's
   `expect`/`actual` ruling and this row must not become the owner of (Decision 2's serialisation).
   So: refuse the brush, name the compression, and **keep reading the file** — a pack with a few
   compressed tips still gives the rest. *(The file to import it with will be
   `core/.../imports/Inflate.kt` + `Inflate.jvm.kt` — JB-8.02's, not this row's.)*
8. **A whole-file fact is said once per `convert` call, and the file-level warning is ONE sentence
   carrying every one of them.** Two whole-file facts exist: the `bVTy` control codes 5–8 are
   inferred, and any colour-jitter field whose scale the note above could not resolve. Both go into
   **one** warning per `convert`, listing every fact, so a pack of 200 brushes produces one line
   rather than 200 (JB-8.03 review's Finding 2 — a warning that fires on every brush trains people to
   ignore warnings) and so "exactly one" is a number a test can assert. Never per-brush noise for a
   whole-file fact.
9. **A shared constant is never copied — and R40 now lets this row fix the two copies that exist.**
   This row's curve budgets are `BrushValidate.MAX_INPUTS` / `MAX_CURVE_POINTS`, which this spec makes
   `public const` in `BrushValidate.kt` (two words changed) and then reads; it does not declare its
   own 64. **R40 permits this row to edit `MypaintImport.kt` to read the shared `MAX_CURVE_POINTS`,
   and this spec does that edit**: delete `private val MAX_CURVE_POINTS = 64` (`MypaintImport.kt:35`)
   and `private val MAX_SIZE_PX = 4096f` (`MypaintImport.kt:201`), import the two `BrushValidate`
   constants, and change nothing else in the file. The second literal is the same rule one line
   further down, and its own KDoc already says *"`BrushValidate.MAX_SIZE_PX`, restated here"* while
   the constant it names is **already public** (`BrushValidate.kt:25`). R40 names the first; taking
   the second in the same four-line edit is the same rule and R23 says do not copy. *(PROVISIONAL —
   Claude to confirm: the ruling permits the edit, and this spec's reading is that it permits both
   literals in it.)* **Do not touch any other line of that file** — it is Built, reviewed code and the
   JB-8.03 open MAJOR is a warning, not an edit.
10. **Long before Int.** Tip bounds arrive as four signed 32-bit numbers read as **Long**, and the
     width/height subtraction is done in Long and refused if the result is ≤ 0, above `MAX_TIP_DIM`, or
     above `Int.MAX_VALUE`. Never `(right - left).toInt()`.
11. **Everything ends up validate-clean or absent.** Every converted preset must pass
    `BrushValidate.validate` with **no problems**; each is clamped *before* the call with its own
    warning naming the field and the number, and anything still left over is a **refusal of that
    brush** with the validator's own sentence. (JB-8.03's Q9 chose "leftover problems become
    warnings"; here a leftover problem means the mapping is wrong, and the house rule is *refuse in
    words*.) **R40 is not an exception to this:** `"image"` is an accepted word
    (`TIP_SOURCES`/`GRAIN_SOURCES`, `BrushValidate.kt:20-21`) and rule 23 is satisfied by a non-blank
    `tip.image`, so an image tip validates clean *and is not drawn* — which is precisely why Decision
    4 makes the warning non-optional.
12. **`sourceFormat = "abr"` and no `BRUSH_VERSION` bump.** `sourceFormat` is already in the preset's
    documented word list (`"abr"` is written in `BrushPreset`'s own KDoc), so no R3 bump is needed and
    `validate` must not be edited to allow it. R40 adds no new field to `BrushPreset`, so it triggers
    no R31 bump either — everything it stores goes in `extensions`, which already exists.

## Steps

1. Tests first: `AbrReaderTest` (container) then `AbrImportTest` (mapping). Both in **`commonTest`** —
   `.abr` is a byte container and this row uses no `java.*`, so `commonTest` is correct. (The rule the
   Lead's review keeps re-learning: a test that opens a file or calls `java.*` belongs in `jvmTest`.)
2. `ImportSupport.kt` **if absent** — exactly the Contract section's block: `ImportLibrary`,
   `RefusedBrush`, `summary()`, `MAX_EXTENSION_BYTES`, `TEXTURE_NOT_DRAWN`, `brushId`.
3. `BrushValidate.kt`: `MAX_INPUTS` and `MAX_CURVE_POINTS` `private` → `public`. Two words.
4. `MypaintImport.kt`: delete `:35` and `:201`, add the two imports. Four lines (Decision 9).
5. `AbrReader.kt`: big-endian cursor, `8BIM` sections, `samp`, `desc` (Action Descriptor), `patt`,
   `phry`; the ag-psd MIT notice at the top; a hard `BrushException` on any budget overrun.
   **`ByteReader` is NOT reused and NOT edited** — it is little-endian and throws
   `StrokeCodecException`, which is a stroke-format exception.
6. `AbrImport.kt`: budgets → sections → brushes → the mapping table, field by field. Write the
   base64 store and the `TEXTURE_NOT_DRAWN` warning **while you are still on the tip row**, not at
   the end: it is the one row every R40 test touches, and it is the row a builder will think is
   finished.

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
   preset has `sourceFormat = "abr"`, `license = "unknown"` (never `"CC0"`), `engine = "stamp"`,
   `id == ImportSupport.brushId(idPrefix, its own name)`, and `BrushValidate.validate` returns
   **empty**. Non-vacuity: assert `brushes.isNotEmpty()`.
6b. **`brushId` is the one sanitiser (Decision 2), and it is tested as a function, not through a
    brush.** `"My Pack" , "Round/Hard 2"` → one id, lower-case, no space, no `/`, no space-separated
    words, and **no leading or trailing `-`**. Four more one-liners: `"..", ".."` → not blank and not
    `".."`; `"", ""` → exactly `"brush"`; `"A--B", "c"` → `"a-b.c"` (the run of two `-` collapses to
    one); `"Ä", "日本"` → not blank and every character outside `a`–`z`/`0`–`9`/`.`/`-`/`_` replaced.
    *This test is here because JB-8.02 and JB-8.04 use the same function on names from files they have
    never seen, and an id is a folder name.*
7. **THE JB-8.03 TEST, written for this file: no mapped curve is dropped.** Build a synthetic brush
   whose `szVr` has `jitter = 50` and `bVTy = 2` (pen pressure) and whose `opVr` has `jitter = 40`,
   `bVTy = 2`, `Mnm ` = 10 — then assert `size.inputs` and `opacity.inputs` are **non-empty** and each
   carries the expected `BrushInput.pressure`. The same for `scatterDynamics`, `countDynamics` and
   `angleDynamics`. **This is the test that would have caught JB-8.03's Finding 1**, and it is the
   single most important assertion in this spec.
8. **Diameter and roundness convert exactly (D-mapping):** `Dmtr = 40` → `size.base == 40f`;
   `Rndn = 50` → `tip.aspect == -0.5f`; `Hrdn = 75` → `tip.hardness.base == 0.75f`; `Spcn = 25` →
   `spacing == 0.25f`. Each within 1e-4.
9. **Each LOSSY bucket says so (D4, D4a, D5, D6, D8):** a synthetic sampled-tip brush produces
    `extensions["abr.tipImage"]` **and** a warning containing "bitmap tip"; a textured brush produces
    a warning naming the pattern id; an `Intr = false` brush warns about spacing; a `bVTy = 6` brush
    records the control in `extensions` and the file-level warning appears **exactly once** across
    the whole library. **Per-brush warnings for whole-file facts are the JB-8.03 review's Finding 2
    and there is a test that fails if they come back.**
9b. **THE R40 TESTS (D4, D4a, D5) — four of them, and they are the reason R40 is safe.**
   a. **The exact sentence, by value.** A sampled-tip brush's warnings contain the string
      `ImportSupport.TEXTURE_NOT_DRAWN` — assert against the **constant**, not a retyped literal, so
      the three importers cannot drift. A textured brush's warning contains it too.
   b. **The image is stored and the preset says so.** A sampled-tip brush has
      `tip.source == "image"`, `tip.image == "tip.packbits"` (non-blank, so rule 23 passes),
      `extensions["abr.tipImage"]` non-empty, and `base64`-decoding it returns the fixture's `samp`
      payload **byte for byte**. *And* the numbers are still real: `size.base` is the bitmap's own
      width, `tip.hardness.base == 0.25f + 0.7f × meanAlpha` within 1e-4.
   c. **Over the cap, not truncated (D4a).** A synthetic tip whose base64 exceeds
      `ImportSupport.MAX_EXTENSION_BYTES` has **no** `"abr.tipImage"` key, `tip.source ==
      "procedural"`, still carries the size and hardness of (b), and warns with `TEXTURE_NOT_DRAWN`
      plus the byte count and the cap. Non-vacuity: assert the key is **absent**, not empty —
      "absent" and "truncated" must not both pass.
   d. **`paperGrain` is untouched by a pattern (D5).** A textured brush has
      `paperGrain.enabled == false` and `extensions["abr.texturePattern"]` carrying the pattern id.
      This is the assertion that says "R40 does not mean *invent* an image".
9c. **The colour-jitter scale rule, four cases (the note above, D8, D11).** One synthetic brush per
    case, all four validating clean:
    * `Brgh = 0f` → `color.value == 0f`, and **neither** a scale warning nor a clamp warning.
    * `Brgh = 0.5f` → `color.value == 0.5f` (**not** `0.005f` — this is the case the old mapping got
      wrong), `extensions["abr.Brgh"]` holds `0.5`, and the **one** per-file warning names `Brgh` as
      an unresolved scale.
    * `Brgh = 100f` → `color.value == 1.0f`, the per-file warning names `Brgh` as a percentage, and
      there is **no per-brush** clamp warning (the divide landed in range).
    * `Brgh = 250f` → `color.value == 1.0f` **and** a per-brush warning naming `Brgh` and both numbers
      (1.0 and 250).
    Non-vacuity for the first bullet: assert the warning list is empty on that brush, so "the scale
    warning fired for everything" cannot pass.
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
13. **`MAX_EXTENSION_BYTES` is real (D3, read from `ImportSupport`):** a brush whose unmapped
    `extensions` — **counting a stored tip image** — would exceed the cap is refused for that, not
    silently truncated. A brush just under it is fine and its `extensions` are complete. The constant
    this test reads is `ImportSupport.MAX_EXTENSION_BYTES`; a second `256` written in `AbrImport.kt`
    is a bug this test cannot see, which is why the Do-not list forbids it.
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
- Do not write `extensions` past `ImportSupport.MAX_EXTENSION_BYTES` — and **do not declare your own
  copy of it.** All three rows say 256 KiB; one constant in one file is what makes that true.
- **Do not retype `TEXTURE_NOT_DRAWN`.** Assert and append `ImportSupport.TEXTURE_NOT_DRAWN`; a
  string literal copied into `AbrImport.kt` is how the three importers drift apart again, which is
  the thing R40 was written to end.
- **Do not write `tip.image = "tip.png"`.** These bytes are PackBits gray, not a PNG, and the name
  goes into a file a future writer will materialise. `"tip.packbits"` is the honest name (R40's
  "the name names the encoding").
- **Do not drop a tip image to save room, and do not store a prefix of one.** Over the cap is
  Decision 4a: nothing stored, warning louder.
- **Do not divide the colour jitters by 100 on a guess.** The magnitude decides (the note under the
  mapping table) — a blind `/100` turns a real 0.5 jitter into 0.005 with nothing to show for it.
- Do not touch `BrushPreset.kt`, `BrushJson.kt` or `dynamics/` — JB-0.03b owns validation and
  `BRUSH_VERSION`.
- **Do not touch anything in `MypaintImport.kt` except the two literals and the two imports**
  (Decision 9). It is Built, reviewed code; the JB-8.03 MAJOR is a warning, not an edit, and a
  tidy-up pass over that file is how a review finding turns into a merge conflict.
- **Do not write your own id sanitiser.** `ImportSupport.brushId` is the one, because JB-8.02 and
  JB-8.04 need exactly this rule and three copies of "which characters are safe in a folder name" is
  three chances to disagree. If `brushId` turns out to be wrong, fix it **there** and say so in the
  report — do not add a second one next to it.
- **Do not run JB-8.02 or JB-8.04 beside this row**, and do not go and create `ImportSupport.kt` for
  them: they read it, `BrushValidate.MAX_CURVE_POINTS`, `BrushValidate.MAX_INPUTS`,
  `ImportSupport.MAX_EXTENSION_BYTES`, `ImportSupport.TEXTURE_NOT_DRAWN` and `ImportSupport.brushId`
  from **this** row (Decision 2).
- Do not change a `BrushPreset` field name to make a mapping fit. Two of them did not fit and both
  were corrected to the field that exists (`color.perStroke`, and roundness into `extensions`) rather
  than to a field that does not.

## Stop rule

If anything here is ambiguous, or a claim about the `.abr` format turns out to be false when you
build the fixture, **STOP**: write the question in *Questions* under a heading `for the cross-reviewer`,
set this row `⛔ Blocked`, commit, push, and take another task. This file reads strangers' binaries,
so the three failure modes that matter are: **never decode an image, never trust a length or a
count, and never substitute a brush that draws differently without saying so in the warnings.** A
brush that imports "successfully" and draws differently is the outcome this whole spec exists to
avoid. If a field's *scale* turns out to be unknowable from the fixtures you have, keep the raw value
in `extensions` and warn — do not pick a number.

## Definition of done

- [ ] tests pass (paste output)
- [ ] only owner-area files changed (paste `git status --short`)
- [ ] the ag-psd MIT notice is at the top of `AbrReader.kt` (paste the first 10 lines)
- [ ] committed as `JB-8.01: Photoshop .abr import`; pushed
- [ ] ROADMAP row → 🟧 Built

## Questions

### Answered by LEAD_RULINGS R40 — recorded here so the answer is not re-asked

The five provisional questions this file used to carry are now rulings, and they are **not** open any
more. Each one names where it lives now, so nobody goes looking for the old text.

| Was | Ruling | It now lives in |
|---|---|---|
| **Q1** — may this row delete `MypaintImport.kt`'s private `MAX_CURVE_POINTS`? | **Yes**, explicitly. The second literal (`MAX_SIZE_PX` at `:201`) goes in the same edit, on R23 + the file's own KDoc. | **Decision 9** |
| **Q3** — is `0.25 + 0.7 × meanAlpha` an acceptable stand-in for now? | **Accepted, calibration later.** It does not block this row and needs no calibration row to ship. | **Decision 4** |
| **Q4** — the budgets: `MAX_FILE_BYTES` 64 MiB, `MAX_BRUSHES` 2 048? | **Ruled at exactly those numbers.** | **Decision 3** |
| **Q5** — `paperGrain.source` stays `"cloud"` and the grain stays off? | **Superseded.** R40 stores image tips/grain and sets `source = "image"`; for `patt` there is no image to store, so the grain still stays off **and now carries the R40 warning word for word**. | **Decision 5** + the R40 section |
| **Q6** — may an importer set `source = "image"` at all? | **Option (a), uniformly, for all three rows.** The answer paragraph is now byte-identical in JB-8.01, JB-8.02 and JB-8.04. | **The R40 section** |

### PROVISIONAL — Claude to confirm

Two, both reversible in one edit, neither a contract and neither a file format. The builder executes
the spec exactly as written; if the Lead prefers the other branch, each names the place to change it.

1. **Decision 4a — over the cap, the image is dropped whole rather than truncated, and the tip falls
   back to `source = "procedural"`.** The alternative is to raise the shared
   `ImportSupport.MAX_EXTENSION_BYTES`, which is a `brush.json` size decision affecting all three
   rows. **A third option the Lead may prefer, and this spec does not take it:** give stored images
   their own channel rather than the `extensions` text budget. *Reversible: one branch of one
   function.*
2. **Decision 9 — R40 permits the `MypaintImport.kt` edit for `MAX_CURVE_POINTS`; this spec reads
   that permission as covering `MAX_SIZE_PX` in the same four-line edit.** If the Lead wants only the
   one literal, delete `:201` from Decision 9 and from the owner area; nothing else changes.
   *Reversible: two lines.*

### for the Lead — real, and **not** blocking this row

**Q1 (new, referred, not blocking). What is an image tip *file*?** R40 says the importer stores the
image and names the file. This row's sampled tips are **PackBits gray**, so it writes
`tip.image = "tip.packbits"`; JB-8.02 and JB-8.04 have real PNGs and write `tip.png` / `grain.png`.
**So the three importers will hand JB-1.05d three encodings.** Nothing reads any of them today, which
is exactly why this does not block the three rows — but JB-1.05d has to know, and there are only
three answers: (a) JB-1.05d reads all three, keyed by the file's extension, which is why the
extension is the honest one; (b) JB-1.05d requires PNG and this row needs a PNG **encoder** in
`commonMain` — and there is none, `PngWriter` is in `androidkit` and is JVM-only
(`androidkit/.../io/PngWriter.kt:85`) — which is a row of real work, not a one-liner; (c) the
importers keep the bytes in `extensions` and JB-1.05d's writer converts on the way out, which puts a
PNG encoder in JB-1.05d instead. **This spec's answer is (c) and it names the extension either way**,
so the decision is not needed to build and is cheap to change. **Recommendation: put it on the
JB-1.05d outline row's Needs line now**, before JB-1.05d is written.

**Q2 (new, referred, not blocking). Where does the stored base64 go when the brush is saved?** R40
puts the bytes in `extensions`, so they are in `brush.json`, and `brush.json` is *"the smallest thing
a person can share"* (`BrushJson.kt:27-28`). A 200 KiB tip therefore travels inside every share of
that brush. That is the cost of the option R40 chose, it is the same in all three rows, and it is a
**document-format consequence rather than an import consequence** — which puts it with JB-0.02d /
JB-1.05d, not here. Flagged so it is a decision and not a surprise.

**Checked and found TRUE against the landed source, 2026-09-29** (each of these was verified by
reading the file, not by trusting this spec's own restatement of it):

- `BrushValidate.MAX_INPUTS` and `MAX_CURVE_POINTS` are `private const val` at `BrushValidate.kt:28`
  and `:31`. `MAX_SIZE_PX` is **`const val` and public** at `:25`, with a KDoc that says every size
  control clamps to the same number.
- `TipSpec.aspect` is `val aspect: Float = 0f` (`BrushPreset.kt:37`) — a bare float, so the original
  `tip.aspect.inputs[…]` could never have compiled. `tip.angle`, `tip.hardness`, `size`, `opacity`,
  `flow` and `scatter.amount` are `Param`s, and `BrushValidate.paramsOf` (`:254-263`) confirms
  exactly those eight are the only `Param`s, so those are the only places a curve can go.
- `ColorJitter` is `(hue, saturation, value, perStroke)` (`BrushPreset.kt:57`). There is no
  `perDab`; the original `color.perDab = true` could never have compiled.
- `color.hue/saturation/value` are ranged `0f..1f` by rule 15 (`:141-143`).
- `TIP_SOURCES`/`GRAIN_SOURCES` accept `"image"` (`:20-21`) and rule 23 (`:236-239`) checks only
  `isNullOrBlank()`. So an image tip validates clean with a path nobody wrote — the door R40 closes
  with a warning rather than with a refusal.
- `ENGINES` contains `"smudge"` (`:16`), `sourceFormat` carries no rule and `"abr"` is in the
  documented list (`BrushPreset.kt:81`), and `BRUSH_VERSION` is `2` with only `"fill"`/`"behind"`
  needing it (`BrushJson.kt:20`, `:118-123`) — so this row bumps nothing.
- `MypaintImport.kt:35` holds `private val MAX_CURVE_POINTS = 64` and `:201` holds
  `private val MAX_SIZE_PX = 4096f` with the KDoc *"`BrushValidate.MAX_SIZE_PX`, restated here"*.
- `ImportResult` is `data class ImportResult(val preset: BrushPreset, val warnings: List<String>)`
  (`MypaintImport.kt:29`) and is used by **nothing outside `joybrush/`** — so the "the types this row
  is told to reuse" claim in JB-8.04's Q1a is about a type with exactly one other reader.
- There is **no image decoder anywhere in `joybrush/`**: `javax.imageio.ImageIO` appears only in
  `jvmTest` and in `androidkit`'s tests, always as an oracle. `PngWriter` is a writer, in
  `androidkit`, JVM-only.
- A brush folder is `<folder>/brush.json` (`BrushLibrary.kt:12-14`; `BrushHotReload`'s `BRUSH_FILE`),
  which is what makes `TipSpec.image` "a path inside the brush folder" and `"shape.png"` (JB-8.02)
  wrong rather than merely premature.


