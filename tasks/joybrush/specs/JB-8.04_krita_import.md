# JB-8.04 — Import Krita `.kpp` / `.bundle` brushes (pixel and colour smudge engines only)

| | |
|---|---|
| **Tier** | T2 |
| **Status** | 📝 Draft spec — **Decision 4 cannot be built as written: the contract has no place to put what Decision 4 returns, and two tests assert it.** `ImportLibrary(brushes, refused)` and `ImportResult(preset, warnings)` — the types this spec is told to reuse (JB-8.01's Decision 2) — carry **no bytes at all**, so "the importer returns the bytes in its result" and test 12's round-trip on "the decoded bytes in the result" are unimplementable without changing a type two other specs read. **⛔ Plus the unreplied Q1** (may an importer set `source = "image"` at all? JB-8.01 says no, JB-8.02 says yes) **and a compile blocker:** Decision 3 reads `BrushValidate.MAX_CURVE_POINTS`, which is `private` until JB-8.01 lands. |
| **Who** | spec writer (unattributed in the original) · **xr: openrouter/stealth/space-bunny-alpha 2026-09-29** — confirmed the contract cannot carry embedded-tip bytes (read `ImportResult` at `MypaintImport.kt:29` and `ImportLibrary` as declared in JB-8.01); confirmed `BrushValidate.rule 23` (`BrushValidate.kt:236`) makes `tip.source = "image"` require a non-blank `tip.image`, so Decision 4's "written out by the caller" is a promise nothing in this row keeps; confirmed `MAX_CURVE_POINTS` is `private`; fixed the truncated budget unit `8 Mi` and two missing units in Decision 7; confirmed the Decision 1 claim about `InkEraseMode`'s exhaustive mapping is a *style* reference, not a shared helper, so it needs no import. Left Draft. |
| **Needs** | JB-0.03 (brush format + validation) |
| **Owner area** | NEW `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/brush/imports/KritaImport.kt`, NEW `.../imports/PngChunks.kt` (PNG signature + `tEXt`/`zTXt`/`iTXt` chunk reader — **no decoding**), NEW `.../commonTest/.../brush/imports/KritaImportTest.kt`, NEW `.../commonTest/.../brush/imports/PngChunksTest.kt`; `ImportSupport.kt` **only if absent** (JB-8.01's Decision 2). **NOT** `Inflate.kt` (Decision 5) |
| **Estimated size** | ~500 lines + ~380 lines of tests |
| **Command** | `./gradlew -p joybrush :core:jvmTest` — 0 failures |

## Goal

R4 §F ranks Krita fourth, and this is the friendliest format of the three: **".kpp (PNG + XML) is open,
easy to read"**, and the content is CC-BY / CC0 / WTFPL (R4 §B.9: David Revoy's presets are CC-BY 4.0;
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
| **LOSSY** | a sensor Joy Brush has no `BrushInput` for; a Krita texture mode Joy Brush's grain has no equivalent; a base64-embedded GBR/GIH tip | closest field, **warning naming the sensor or mode**, **raw in `extensions`** |
| **REFUSED** | every other `paintopid` — `Hairy`, `Sketch`, `Deform`, `Particle`, `Curve`, `Hatching`, `Grid`, `Shape`, `Filter`, `TangentNormal`, `Quick`, `Spray` | that brush, **with the engine's own id in the sentence** |

The refusal reason must contain the literal `paintopid` string, because that is what a person will see
in Krita's brush editor, and it is what makes the message actionable rather than "unsupported".

Two more facts that must be honoured rather than glossed:

- **`ColorSmudge` is legal and is not "unsupported."** R4 §E.3 maps it to `pickup`, and JB-1.06 (smudge
  and nudge) is `⚪ Outline`. So a colorsmudge brush imports as `engine = "smudge"` — a word
  `BrushValidate` already accepts — and **warns that the engine is not built yet**. That is LOSSY, not
  REFUSED: the brush is real, the engine is late.
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

## Contract

```kotlin
package cc.joycreator.joybrush.core.brush.imports

object KritaImport {
    /** One `.kpp` file's bytes → one brush, or a refusal. */
    fun convertKpp(bytes: ByteArray, idPrefix: String): ImportLibrary

    /** A `.bundle` zip → every `.kpp` inside `brushes/` and `paintopresets/`, in name order. */
    fun convertBundle(bytes: ByteArray, idPrefix: String): ImportLibrary
}
```

`ImportLibrary` / `RefusedBrush` are JB-8.01's — read them, do not redefine them (R23).

## Decisions — the mapping

1. **`Pixel` and `ColorSmudge` are the only accepted `paintopid`s.** Every other value is REFUSED with
   the id in the sentence. **This is a `when` with no `else` branch that returns a brush** — adding a
   Krita engine later is a compile error until someone decides where it goes, which is the point
   (the same trick `InkEraseMode`'s mapping uses).
2. **Krita sensor → `BrushInput`, and there are exactly these.** `pressure` → `pressure`;
   `xtilt`/`declination` → `tilt` (both are "how far from upright"); `ascension` → `lean`;
   `rotation` → `barrel`; `speed` → `speed`; `drawingangle` → `direction`; `distance` → `distance`.
   **The rest — `ytilt`, `fuzzy`, `fuzzystroke`, `fade`, `time`, `perspective`,
   `tangentialpressure` — have no `BrushInput`** and go to `extensions["krita.<sensor>"]` with a
   warning naming the sensor. **`BrushInput` gains no constant here:** R3 makes that a `BRUSH_VERSION`
   bump and a validator change, and Joy Brush's inputs are the engine's business, not Krita's.
3. **`commonCurve "x,y;x,y;"` parses to `[[x, y], …]` and obeys the shared caps.** Over
   `BrushValidate.MAX_CURVE_POINTS` points, an x outside 0..1, a non-finite y, or a point that is not
   exactly two numbers **refuses that brush** (D-refuse-curve, below). Never trimmed.
4. **`brush_definition` decides the tip, and the tip is the only place this row can go wrong.**
   * `type = "auto_mask"` or an empty definition → a procedural tip; `tip.aspect` from the ellipse's
     `rx`/`ry`, `tip.corner` = 2. MAPPED.
   * a **base64-embedded PNG** → `tip.source = "image"` and a file name in `tip.image`. **How the
     bytes get from the archive to a file the brush library can resolve is ⛔ NOT DECIDED — see
     Q1.** As written this row is unimplementable: `ImportLibrary` and `ImportResult` (the types it
     must reuse, JB-8.01's Decision 2) hold `List<ImportResult>` and `List<RefusedBrush>` and
     **no bytes at all**, so "the importer returns the bytes in its result" names a field that does
     not exist, and `commonMain` cannot write a file even if it did. **This is a contract decision on
     a type two other specs read, so it is not a cross-reviewer's to make.**
   * a base64-embedded **GBR / GIH / ABR** → **REFUSED** for this row, with the format named. Reason,
     stated so it is not a bare "no": GIMP's `.gbr` and `.gih` and Adobe's `.abr` are three more
     binary readers, and this row's budget is one XML parser and one PNG chunk reader.
   * a **predefined tip by name** (an image Krita expects to find on disk) → LOSSY: procedural tip,
     warning naming the missing image.
5. **No inflate, and `zTXt` is refused.** Krita writes `preset` as **uncompressed `tEXt`** in practice,
   but `zTXt`/`iTXt`-compressed chunks exist. Reading them needs a DEFLATE decoder, which is
   **JB-8.02's `Inflate.kt`** and which this row must not depend on (Decision 2 serialisation, and two
   importers must not each own an inflater). So: **an uncompressed `tEXt`/`iTXt` is read; a compressed
   `zTXt` is refused with a sentence saying the chunk is compressed.** R4 B.3 says the chunks are
   uncompressed, so this should never fire on a real file — and if it does, the message says why.
6. **No PNG decoding.** `PngChunks.kt` reads the 8-byte signature, walks chunks by length, and reads
   `tEXt`/`iTXt`/`zTXt` payloads. It **never decodes pixels**. Reason: Joy Brush has a PNG *writer*
   (JB-2.14a) and no reader, and a decoder is a large piece of work that belongs to its own row.
7. **Every budget is a named constant and every one refuses:**

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
| `MAX_BASE64_CHARS` | 8 Mi chars (8 388 608) — **was written "8 Mi", a truncated unit; corrected 2026-09-29** |
| `MAX_BRUSHES` | 2 048 |
| `MAX_EXTENSION_BYTES` | 256 KiB per brush |

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
11. **`ColorSmudge` → `engine = "smudge"` with a warning** that the engine is not built yet (JB-1.06).
    It is a valid `BrushPreset` word (`BrushValidate`'s `ENGINES`), so this needs no version bump and no
    validator change.
12. **Krita's texture maps onto `paperGrain`, with the modes we cannot do refused rather than faked.**
    Krita's PS-compatible modes are Subtract, Height (PS), Linear Height (PS) and Hard Mix, with
    published formulas (R4 §A.5, from Krita's source). **`paperGrain` has no mode field today**, so:
    Height/Subtract → `paperGrain.enabled` + `depth` with a **warning naming the mode**; every other
    mode (Multiply, Overlay, Dodge, Burn, …) → `extensions["krita.textureMode"]` + a warning. Loud,
    never silent.
13. **An entry name in a bundle is never trusted** (JB-8.01's Decision 12, same discipline, and the
    `..`/backslash/colon/control/empty-segment rule is restated rather than copied into code twice —
    `ImportSupport.kt` is where it lives once both rows exist).
14. **Licence: per resource, never assumed.** `license = "unknown"` unless the bundle's `meta.xml`
    states one for that brush; **`never `"CC0"` by default`.** `author` from the bundle metadata when
    present, else `""`. (R4 §B.9's per-file licence advice, and blueprint §5: keep author/licence/
    source in every imported brush.)
15. **A `.bundle` with no `brushes/` and no `paintoppresets/` refuses** in words rather than importing
    an empty library — an empty result with no explanation is the silence this whole spec forbids.

## Steps

1. Tests first: `PngChunksTest`, then `KritaImportTest`.
2. `ImportSupport.kt` **if absent**.
3. `PngChunks.kt` — signature, chunk walk, `tEXt`/`iTXt` payloads, Long-guarded lengths.
4. `KritaImport.kt` — budgets, XML reader, `paintopid` gate (Decision 1), sensors (Decision 2),
   tip kinds (Decision 4), texture modes (Decision 12).

## Tests

**Fixtures.** `PngChunksTest` builds PNGs by **concatenating hand-written chunks** (a valid
signature, an `IHDR`, the text chunks, an `IEND`) — no PNG library in the test either, so the test
proves the reader rather than a writer. `KritaImportTest` uses **real `.kpp` files pasted as base64**
with their source and licence in a comment — R4 §B.9 names David Revoy's `deevad-krita-brushpresets`
(CC-BY 4.0), which is the pack to ship — plus hand-built minimal PNG+XML for the structural cases.

**`PngChunksTest`**
1. **Signature and chunk walk:** a PNG with three text chunks returns all three, in order, with their
   keyword and payload; a chunk with a **zero length** and an unknown type is skipped, not an error.
2. **A chunk length that overruns the file refuses** (D8), naming the chunk type and the length.
3. **A length with the high bit set refuses** (D8): `0xFFFFFFFF` as an unsigned length, read as a
   signed Int, must not become `-1` and must not be added to the position. The test asserts the
   refusal message, **not** a crash and not a successful parse.
4. **A `zTXt` chunk is refused with "compressed" in the message** (D5), and the reader keeps reading
   the chunks after it — one compressed chunk must not lose the `preset` that follows it.
5. **`iTXt` uncompressed is read** (compression flag 0) and its text after the language and translated
   keyword is the payload; a **non-zero compression flag** is refused like `zTXt`.

**`KritaImportTest`**
6. **The real `.kpp` presets convert and validate clean (D-mapping):** each preset has
   `sourceFormat = "kpp"`, `license` not `"CC0"` unless stated, and `BrushValidate.validate` returns
   **empty**. Non-vacuity: assert the brush's own Krita name appears among the converted names.
7. **THE ENGINE GATE (D1), one case per refused id:** a hand-built `.kpp` per engine —
   `Hairy`, `Sketch`, `Deform`, `Particle`, `Curve`, `Hatching`, `Grid`, `Shape`, `Filter`,
   `TangentNormal`, `Quick`, `Spray` — each is refused with a reason **containing that literal
   `paintopid`**. **Non-vacuity in the same test:** `Pixel` and `ColorSmudge` from the same fixture
   shape are **not** refused. This is the row's headline assertion.
8. **A missing `<paintop id>` refuses, naming the omission** (D10) — and does **not** default to
   `Pixel` (assert `brushes.isEmpty()` and `refused.size == 1`).
9. **Sensors map, and unmapped sensors are loud (D2):** a fixture with `pressure`, `xtilt`, `rotation`
   and `fuzzy` sensors produces inputs on `size`/`opacity` carrying `BrushInput.pressure`, `.tilt` and
   `.barrel`, and `extensions["krita.fuzzy"]` plus a warning naming `fuzzy`. **`ytilt` also warns and
   does not become `tilt` a second time.**
10. **THE MAPPED-CURVE TEST (D3):** a `commonCurve "0,0;0.5,0.8;1,1"` becomes
    `[[0,0],[0.5,0.8],[1,1]]` exactly (within 1e-6), and a second pressure curve on the same `Param`
    is **also** present — so a brush with two sensors cannot silently lose one.
11. **A bad curve refuses the brush (D3):** a 65-point curve, `commonCurve "0,0;x,1"`,
    `commonCurve "0,0;1.4,1"` and `commonCurve "0,0;1"` each refuse with distinct reasons; the good
    brushes in the same bundle are `==` what a bundle without them would have produced.
12. **Embedded tips (D4):** a base64 **PNG** tip yields `tip.source = "image"`, a non-blank
    `tip.image`, and the decoded bytes in the result matching the fixture (round-trip); a base64
    **GBR** tip and a base64 **GIH** tip are each refused with the format named; a **predefined by
    name** tip warns naming the missing image and converts with a procedural tip. **And the
    `BrushValidate` rule 23 assertion: no converted preset has `source = "image"` with a blank
    `image`** — that is the check that catches a half-written image path.
13. **Texture modes are named, not faked (D12):** a `Subtract` texture produces
    `paperGrain.enabled == true` and a warning containing "Subtract"; an `Overlay` texture produces
    `extensions["krita.textureMode"]` containing `"Overlay"` and a warning containing it. Both
    validate clean.
14. **Colorsmudge is legal but says so (D11):** a `ColorSmudge` preset has `engine == "smudge"`,
    validates clean, and its warnings contain a sentence about the engine not being built yet.
    *(The value is the literal `"smudge"` — `BrushValidate.ENGINES` already accepts it and this test
    is what proves no validator edit was needed.)*
15. **A bundle walks, names are trusted, and content decides (D13, D15):** a bundle with
    `brushes/{a.kpp,b.kpp}` and `paintopresets/{c.kpp}` imports all three **in name order**; a bundle
    with `brushes/../evil.kpp` refuses that entry and imports the rest; a bundle with neither directory
    **refuses** rather than importing nothing quietly.
16. **Every budget refuses (D7), one test each at cap+1:** file bytes, chunk count, chunk bytes, text
    chunk bytes, XML depth 65, XML nodes, attributes per node, base64 length, brushes per bundle,
    `extensions` per brush. Each names its cap.
17. **XML hostile input is refused, not honoured (D9):** a DOCTYPE with an external entity, an
    undefined entity, a mismatched close tag, and a 200-deep nest. Each refuses with a message; none
    reads a file or a URL.
18. **Malformed `.kpp` files throw (D15):** not a PNG, a PNG with no `preset` chunk, a `preset` chunk
    that is not XML, an empty file, a 5-byte file. Each throws `BrushException` with a message.

**Command:** `./gradlew -p joybrush :core:jvmTest`. Passing = `BUILD SUCCESSFUL`, 0 failures.

## Do not

- Do **not** decode PNG pixels. Read chunks; stop.
- Do **not** add an `Inflate.kt` here, and do **not** depend on JB-8.02's (Decision 5 — a compressed
  `zTXt` is refused with a sentence).
- Do **not** add a `BrushInput` constant. That is R3, a `BRUSH_VERSION` bump and a validator change,
  and it is not this row's business.
- Do **not** import a non-`Pixel`/`ColorSmudge` engine "approximately". That is the row's whole point.
- Do **not** default a missing `paintopid` to `Pixel`.
- Do **not** set `license = "CC0"` for a pack that does not say so. R4 §B.9: Krita's default bundle is
  mixed and licences are **per resource**.
- Do **not** run this spec at the same time as JB-8.01 or JB-8.02 — they share `ImportSupport.kt`.
- **Do not run this spec until JB-8.01 has landed.** Decision 3 reads
  `BrushValidate.MAX_CURVE_POINTS`, and **JB-8.01 is what makes it public**; it is `private const` in
  the tree today. The board's *Needs* column for this row says `0.03` only; that is a board
  inaccuracy, and the dispatch order is **JB-8.01 → (JB-8.02 | this row)**, never two at once.
- Do **not** invent a field on `ImportResult` / `ImportLibrary` to carry the embedded-tip bytes. They
  are JB-8.01's and two other specs read them; changing the shape is Q1 and the Lead's.
- Do **not** assume `source = "image"` may be set. `BrushValidate` accepts it (`:20`, `:21`) and rule
  23 (`:236`) only checks that the path is non-blank — so a preset can validate clean, name a file
  that was never written, and draw as procedural. That is the silent-nonsense door and it is open.

## Stop rule

If anything here is ambiguous, or a claim about the `.kpp` format turns out to be false when you build
the fixture, **STOP**: write the question in *Questions* under a heading `for the cross-reviewer`, set
this row `⛔ Blocked`, commit, push, and take another task. Three things are never a builder's call in
this file: **whether a non-`Pixel`/`ColorSmudge` engine may be approximated** (it may not — that is
the row), **whether a compressed `zTXt` may be inflated here** (it may not — Decision 5), and **how
an embedded tip's bytes reach a file** (Q1). Never reach for Q1 by adding a field to someone else's
type.

## Definition of done

- [ ] tests pass (paste output)
- [ ] only owner-area files changed (paste `git status --short`)
- [ ] committed as `JB-8.04: Krita import`; pushed
- [ ] ROADMAP row → 🟧 Built

## Questions

### ⛔ for the cross-reviewer — why this is still Draft

**Q1 is now three questions wearing one name, and the first of them is a contract decision.**

**Q1a (⛔ blocking, mechanical). Where do an embedded tip's bytes go?** Decision 4 says the importer
*"returns the bytes in its result"* and the caller writes them; test 12 asserts the decoded bytes
round-trip *"in the result"*. **Neither is possible.** `ImportResult(val preset: BrushPreset, val
warnings: List<String>)` (`MypaintImport.kt:29`) has no bytes, `RefusedBrush(index, name, reason)` has
no bytes, and `ImportLibrary(brushes, refused)` is the only container this spec is told to use — so
"the bytes in the result" is a field that does not exist. Separately, a `tip.image` is *"path inside
the brush folder when source == image"* (`BrushPreset.kt:34`), **not** a path inside the `.bundle`:
so `"Shape.png"` is the wrong value for it whatever the container question is answered, and
`commonMain` cannot write the file even if the container could carry it. **Writing this down because
the spec reads as though it were already answered, and it is not.** The three candidate shapes each
have a different cost, and each touches a type JB-8.01 owns:
- widen `ImportResult` with an `images: Map<String, ByteArray>` — one type, three specs, and it works
  only once a caller exists that writes them out;
- keep the bytes out of the types and put base64 in `extensions["krita.tipPng"]` (what JB-8.01
  Decision 4 does) — no contract change, but it collides with `MAX_EXTENSION_BYTES` at 256 KiB for a
  real 2048² tip, and the preset is still not drawable;
- refuse an embedded-bitmap tip outright (what JB-8.01 does) — honest, and it refuses a large part of
  Revoy's pack, which is the pack the blueprint names as the one to ship.

**Q1b (⛔ blocking, policy). May an importer set `source = "image"` at all?** JB-8.01 says **no**
(Decisions 4–5: procedural stand-in, grain left disabled, raw kept in `extensions`); JB-8.02's table
says **yes, unconditionally**; this spec leans yes. Nothing in the board makes them agree, so the
first to land decides for all three. Verified: `BrushValidate` accepts `"image"` for both tip and
grain (`:20`, `:21`) and rule 23 (`:236`) checks only that the path is non-blank — so the answer "yes"
produces presets that validate clean, point at a file nobody wrote, and draw as procedural. **One
ruling for all three importers is needed, and it is the Lead's.**

**Q1c (not blocking).** The calibration remark stands — there is no third-party decoder to oracle a
new format here, and there is none needed because this row writes no image.

**Q2 (ruled, PROVISIONAL).** `ColorSmudge` imports as `engine = "smudge"` with a warning. **Verified
true:** `ENGINES = setOf("stamp", "smudge", "wet", ENGINE_FILL)` (`BrushValidate.kt:16`), so no
validator edit and no `BRUSH_VERSION` bump is needed — which is what Decision 11 and test 14 say.
A preset is a fact; an engine being late is not the preset being wrong. **PROVISIONAL — Claude to
confirm.**

**Q3 (ruled, PROVISIONAL).** A brush using **both** `xtilt` and `declination` carries its own warning
("Joy Brush has one tilt value; this brush uses two"). One extra line, and it is the difference
between "silently coarser" and "told". **PROVISIONAL — Claude to confirm.**

**Q4 (ruled, PROVISIONAL).** Keep the texture mode raw and warn; do **not** add a `GrainSpec` mode
field. A new field is a `BRUSH_VERSION` bump and a validator change and belongs to whoever builds
grain properly (JB-1.05c, Phase 6). **PROVISIONAL — Claude to confirm.**

**Q5 (out of scope, referred).** Nothing displays an imported brush's author and licence, and CC-BY
requires attribution. This row can carry the data and cannot surface it — a UI row, not this one.
Correct as written; flagged so it is not lost.

**Checked and found TRUE** (tree, 2026-09-29): `ENGINES` contains `"smudge"` (Decision 11, test 14);
`TIP_SOURCES`/`GRAIN_SOURCES` contain `"image"` and `GrainSpec.image` exists, so rule 23 is reachable
exactly as test 12 describes; `BrushValidate.MAX_CURVE_POINTS` is `private const`
(`BrushValidate.kt:31`) and only JB-8.01 makes it public; `PngWriter` is a **writer** only and no PNG
reader exists anywhere in `joybrush/`, which is what makes Decision 6's "no decoding" a fact about the
tree rather than a preference.


**Q1. An embedded PNG tip means Joy Brush writes an image file per imported brush, and nothing in the
project reads an image back.** Decision 4 returns the bytes and sets `tip.source = "image"` — a word
`BrushValidate` accepts today, which means the preset **validates clean and then draws as a procedural
tip**, because `jb_dab.frag` samples no texture (I read it: five float uniforms and `jb_tip.glsl`).
That is precisely the "silent nonsense" `BrushJson.decodeChecked`'s KDoc was written to prevent, and it
is the same tension I referred to the Lead from JB-8.01 Q5. **The ruling must cover one thing for all
three importers:** either (a) image tips and image grain get a row and these specs set `source =
"image"` knowing it is not drawn yet, or (b) these importers **refuse** a brush whose tip or grain is a
bitmap, and say why. **I have written (a) provisionally** because refusing would refuse most Krita and
many Revoy presets, but (b) is the more honest option under this project's own stated rule, and I am
not going to ship a file that lies by omission without you saying which it is.

**Q2. Should `ColorSmudge` import at all, given JB-1.06 is `⚪ Outline`?** Decision 11 imports it as
`engine = "smudge"` with a warning. The alternative is to **refuse** it as "an engine Joy Brush has not
built", which is more honest in the strict sense and refuses a whole category of Krita presets. I chose
import-with-warning because the preset is real and the engine is merely late, and because a person who
imports a pack on the train should not lose half of it. **Rule it.**

**Q3. `xtilt` and `declination` both becoming `tilt` (Decision 2) — is that right?** Krita has separate
tilt-x and tilt-y tilt sensors; Joy Brush's `PenSample.tilt` is a single magnitude. Mapping both is
lossy and I have **not** warned about it beyond the general per-sensor rule. **Should a brush that uses
BOTH carry a specific warning** ("Joy Brush has one tilt value; this brush uses two")? It is one extra
warning line and it is the kind of thing that is much harder to add once people have the packs.

**Q4. The texture-mode refusals (Decision 12) name the mode but drop the maths.** Krita's formulas are
published (R4 §A.5 quotes them from source), and `paperGrain` has no mode field anyway — so even a
"mapped" mode would have nowhere to go. **Should the mode be a new `GrainSpec` field** (a
`BRUSH_VERSION` bump to 3, a validator change, and JB-1.05c's work), or is "keep it raw and warn" the
standing answer until Phase 6 does grain properly? My answer is the latter and I want it on the record.

**Q5. Revoy's presets are CC-BY 4.0 and the design records say attribution is required.** Decision 14
keeps the licence string, but **nothing in Joy Brush displays it.** Is there a row that shows an
imported brush's author and licence — the About screen, the brush picker's detail sheet, or a
`sources.txt` beside the imported files? **This row can carry the data and cannot surface it**, and R4
§B.9 explicitly says CC-BY attribution goes in the About screen.
