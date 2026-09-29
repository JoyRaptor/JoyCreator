# JB-8.02 — Import Procreate `.brush` / `.brushset` brushes

| | |
|---|---|
| **Tier** | T2 |
| **Status** | 📝 Draft spec — **blocked on two things a cross-reviewer may not decide.** (1) **Q6: may an importer set `source = "image"` on a tip or a grain at all?** This spec's table says *yes, unconditionally*, which is a different answer from JB-8.01's Decisions 4–5 (*no*) and from JB-8.04's Q1 (which leans *yes*), so whichever lands first silently decides it for all three — and `BrushValidate` **verified true** accepts it (`GRAIN_SOURCES = setOf("cloud","image")`, `BrushValidate.kt:21`), so a preset that points at a file nobody wrote validates clean and draws nothing. That is the "silent nonsense" door in its purest form. (2) **Q1: `Inflate.kt` in `commonMain` or `androidkit`** — it decides whether a third-party bitstream format sits inside the platform-neutral engine, which is a contract-shaped call. Also blocked: `MAX_INPUTS` / `MAX_CURVE_POINTS` are still `private` and become public in JB-8.01, so **this spec does not compile until JB-8.01 lands** (Decision 13 reads them). |
| **Who** | spec writer (unattributed in the original) · **xr: openrouter/stealth/space-bunny-alpha 2026-09-29** — verified `GRAIN_SOURCES`/`TIP_SOURCES` accept `"image"` while nothing in the tree reads one (the basis of Q6); verified `JbArchive.unsafeReason` is **private** and lives in `androidkit`, so Decision 12's "the same set" is a restatement across a module boundary `commonMain` cannot see; verified `PngWriter.encode(width, height, rgba, compressionLevel = 6): ByteArray` exists (`PngWriter.kt:85`), which Decision 1's `Shape.png → tip.image = "shape.png"` depends on; verified `BrushValidate.MAX_CURVE_POINTS`/`MAX_INPUTS` are `private const` at `:28`/`:31`. Fixed the truncated `8 Mi` unit in JB-8.04's twin table is *not* here — but corrected this row's own `shapeRoundness` mapping, which pointed at `tip.aspect` as if it took a curve. Left Draft. |
| **Needs** | JB-0.03 (brush format + validation) |
| **Owner area** | NEW `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/brush/imports/ProcreateImport.kt`, NEW `.../imports/KeyedArchive.kt` (NSKeyedArchiver reader), NEW `.../imports/Inflate.kt` (raw DEFLATE), NEW `.../commonTest/.../brush/imports/ProcreateImportTest.kt`, NEW `.../commonTest/.../brush/imports/KeyedArchiveTest.kt`, NEW `.../commonTest/.../brush/imports/InflateTest.kt`; `ImportSupport.kt` **only if absent** (Decision 2) |
| **Estimated size** | ~650 lines + ~450 lines of tests |
| **Command** | `./gradlew -p joybrush :core:jvmTest` — 0 failures |

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
| **REFUSED** | a brush whose `bundledShapePath` names a **Procreate built-in** (the file has no image for it and R4 §5 forbids shipping Procreate's assets) | that brush, with a sentence naming the missing resource |

And the rule the JB-8.03 review produced: **an unverified scale must be a warning on the first import
of a pack and not on every brush** — a warning that fires 200 times trains people to ignore warnings,
which was the review's own Finding 2.

Two more house rules this format forces:

- **Legal.** R4 §5: *"Never extract or ship the Shape/Grain images from Procreate's default library.
  Substitute original or CC0 lookalikes."* So this importer **never substitutes a lookalike and never
  extracts a built-in**; it refuses the brush and says which resource is missing. A future CC0
  lookalike library is a separate, separately-licensed task (Q4).
- **Redistributing is not ours.** R4 §5 / blueprint §5: importing a file the user owns is fine;
  shipping converted third-party packs is not. So `license = "unknown"` unless the archive says
  otherwise, `author` from `authorName` when present, `sourceFormat = "procreate"`.

## Contract

```kotlin
package cc.joycreator.joybrush.core.brush.imports

object ProcreateImport {
    /** One `.brush` (a zip) → its brushes. Throws BrushException on a file-level fault only. */
    fun convertBrush(bytes: ByteArray, idPrefix: String): ImportLibrary

    /** A `.brushset` (a zip of UUID folders) → every brush inside, in `brushset.plist` order. */
    fun convertBrushSet(bytes: ByteArray, idPrefix: String): ImportLibrary
}
```

`ImportLibrary` / `RefusedBrush` are **JB-8.01's** (Decision 2) — read them, do not redefine them.

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

## Decisions — the mapping

1. **MAPPED with the scale stated in the test.** Every mapping below is pinned by a test with its
   derivation, per R9's standing rule ("an expected value in a test may be changed ONLY with its
   derivation written into the test").

| Procreate | Key | Joy Brush | Scale |
|---|---|---|---|
| Shape is an image | `Shape.png` present | `tip.source = "image"`, `tip.image = "shape.png"` | — **⛔ see Q6** |
| Shape rotation / follow stroke | `shapeRotation` | `tip.followDirection` = `v > 0` | boolean, unverified |
| Shape roundness / angle | `shapeRoundness`, `shapeAngle` | `tip.aspect` (a bare `Float`), `tip.angle.base` (`tip.angle` is a `Param`) | as stored |
| Scatter | `plotJitter` | `scatter.amount` | **≈** R4: unverified scale; warn once per pack |
| Count / count jitter | `shapeCount`, `shapeCountJitter` | `scatter.count`, `scatter.countJitter` | as stored |
| Flip jitter | `shapeFlipXJitter/YJitter` | `extensions` (no flip field) | **LOSSY**, warn |
| Spacing | `plotSpacing` | `spacing` | **≈** R4 B.2 notes p2k guesses `sqrt(v·100)/10`; warn once |
| StreamLine | `plotSmoothing` | `smoothing` | **≈** |
| Pressure size | `dynamicsPressureSize(+Curve)` | `size.inputs[pressure]` multiply | curve points parsed |
| Pressure opacity | `dynamicsPressureOpacity(+Curve)` | `opacity.inputs[pressure]` multiply | curve points parsed |
| Tilt size / opacity | `dynamicsTiltSize`, `dynamicsTiltOpacity` | `size/opacity.inputs[tilt]` multiply | **≈** |
| Grain image | `Grain.png` present | `paperGrain.source = "image"`, `.image = "grain.png"`, `.enabled = true` | — **⛔ see Q6 — this is the row that decides the question for all three** |
| Grain depth / min | `grainDepth`, `grainDepthMinimum` | `paperGrain.depth` | as stored |
| Grain scale | `textureScale` | `paperGrain.scale` | **≈** |
| Brightness / contrast / invert | `textureBrightness`, `textureContrast`, `textureInverted` | folded into the depth curve / `extensions` | **LOSSY**, warn (R4: contrast is −1…1) |
| Colour jitter | `dynamicsJitterHue/Saturation/Lightness` | `color.hue/saturation/value`, `perStroke = true` | **≈** — divide by 100, then clamp with a warning, exactly as JB-8.01's ruling |
| Blend mode | `blendMode` | `blend` when it is one of `normal`/`erase`/`behind` | **LOSSY** for any other value, raw kept |
| Author | `authorName` | `author` | — |
| Licence | any field naming one | `license`; else `"unknown"`, **never `"CC0"`** | — |

2. **`ImportLibrary`, `RefusedBrush`, `summary()` live in `ImportSupport.kt` (JB-8.01's Decision 2) and
   the three Phase 8 importers are serialised for it. Create it only if absent; otherwise read it.**
3. **`Inflate.kt` and `KeyedArchive.kt` are NEW and owned by this row** — they are Procreate's
   containers, nothing else needs them yet, and JB-8.01 deliberately does not (its Decision 7 refuses
   a compressed tip rather than pulling inflate across an import order). **`Inflate` is raw DEFLATE
   (RFC 1951), written in Kotlin for `commonMain`:** no `java.util.zip`, no platform type. It is
   exercised by round-tripping against `java.util.zip.Deflater` **in `jvmTest` only** — the production
   path stays platform-neutral.
4. **Every budget is a named constant and every one refuses** (the numbers and the tests are in the
   table further down; Decision 11 lists them).
5. **A zlib wrapper is unwrapped, then bounded.** `.brush` zips carry a 2-byte zlib header
   (`0x78 0x9C` family) before the DEFLATE stream. Both the header and the stream are length-bounded;
   an entry whose *declared* size is smaller than what inflates is a refusal, and one whose declared
   size is larger is truncated at the declared size (and warns) rather than read past.
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
   ship and may not extract (R4 §5)."* **Never** a substitute, **never** an empty tip.
9. **Curve parsing is a refusal, never a trim.** A `"{x, y}"` string that is not two numbers, a curve
   over `BrushValidate`'s shared point cap, an x outside 0..1, or a non-finite y **refuses that
   brush** — the same rule JB-8.03 chose and the same reason: a trimmed curve is a curve the artist
   did not draw. The x-domain check applies to *mapped* curves only (JB-8.03 Q6), because Procreate's
   own domains vary (`shapeAngle` is 0..360).
10. **The unverified-scale warning is per PACK, not per brush.** One sentence per `convert` call
    naming the fields whose scale is inferred (`spacing`, `scatter`, `smoothing`, `grain.scale`). **The
    JB-8.03 review's Finding 2 is a test here.**
11. **Budgets (every one a refusal, one test each):**

| Budget | Cap |
|---|---|
| `MAX_ARCHIVE_BYTES` | 256 MiB (a `.brushset` is a pack) |
| `MAX_ENTRIES` | 4 096 |
| `MAX_ENTRY_BYTES` | 64 MiB |
| `MAX_ENTRY_NAME_CHARS` | 512 |
| `MAX_INFLATE_RATIO` | 200:1 (a zip bomb is refused before it is inflated) |
| `MAX_INFLATED_BYTES` | 64 MiB per entry |
| `MAX_OBJECTS` | 200 000 (`$objects` entries) |
| `MAX_DEPTH` | 32 |
| `MAX_KEYS_PER_DICT` | 20 000 |
| `MAX_STRING_CHARS` | 64 Ki |
| `MAX_BRUSHES` | 2 048 |
| `MAX_EXTENSION_BYTES` | 256 KiB per brush |

12. **An entry name is never trusted** — `..` anywhere, a leading `/`, a backslash, a colon, a control
    character and every empty segment are refused, on **read and write**, because a hostile `.brush`
    must not be able to write an escaping name either. (`JbArchive`'s `unsafeReason` discipline,
    applied a second time; a shared constant is never copied.)
13. **A shared constant is never copied.** Curve caps are `BrushValidate.MAX_INPUTS` /
    `MAX_CURVE_POINTS` (public since JB-8.01). Everything else Procreate-specific lives here.
14. **Everything ends up validate-clean or absent** (JB-8.01's Decision 11): each value clamped with
    its own warning naming the field and both numbers, and anything still left over refuses the brush
    with the validator's sentence.
15. **`.brush` inside a `.brushset` is imported as a `.brush`.** One code path, one set of warnings.
    `brushset.plist` gives the set's name and its order; a missing or unreadable one imports every
    folder found in **name order** and warns once.

## Steps

1. Tests first: `InflateTest` (against `java.util.zip.Deflater`), then `KeyedArchiveTest`, then
   `ProcreateImportTest`.
2. `ImportSupport.kt` **if absent**.
3. `Inflate.kt` — RFC 1951. Stored (method 0) and fixed and dynamic Huffman (methods 1 and 2). Bit
   reader over `ByteArray` in `Long`-sized arithmetic; never a negative index.
4. `KeyedArchive.kt` — bplist v0 (`bplist00`), the object table, UID resolution, `$objects` /
   `$top`, plus a small XML-plist reader for Decision 6.
5. `ProcreateImport.kt` — budgets, zip walk, sniffing, per-key mapping, buckets, warnings.

## Tests

**Fixtures.** `InflateTest` and `KeyedArchiveTest` build their own inputs (deflate a known byte string
with `java.util.zip.Deflater`; hand-build a bplist with a small writer **in the test file** — never a
dependency). `ProcreateImportTest` uses **two real `.brush` archives pasted as base64** with their
source and licence in a comment — R4 §B.9 names Catherine's Basic Procreate Brushes (cOborski) as
CC0/PD — plus hand-built minimal archives for the structural cases (missing `Shape.png`, dangling UID,
flat XML, zip bomb).

**`InflateTest`**
1. **Round trip, three ways:** `java.util.zip.Deflater` at levels 0 (stored), 6 and 9 → `inflate`
   returns the original bytes exactly, for inputs of 0, 1, 100 and 100 000 bytes.
2. **A truncated stream refuses** with a message naming the truncation; **a stream with trailing
   garbage past the declared output length** is refused, not read past.
3. **The ratio cap is enforced BEFORE inflating (D11):** a stream declaring a 10 MiB output from 1 KiB
   of input refuses with `MAX_INFLATE_RATIO` named, and the test asserts it took under a generous
   timeout — a bomb that allocates before it checks is the failure this exists to stop.
4. **A malformed Huffman table refuses** rather than looping: garbage after the header, a code-length
   code that overflows, a distance beyond the output start. Each throws `BrushException`.

**`KeyedArchiveTest`**
5. **UID resolution, both kinds:** a dictionary and an array of UIDs resolve to the same objects the
   writer saw; a UID pointing past `$objects` refuses naming the index (D7).
6. **Flat XML is read (D6):** a minimal `<plist><dict><key>name</key><string>Round</string></dict></plist>`
   yields `"Round"`.
7. **Neither magic refuses with a sentence naming what was found** (D6):`bplist0`, `<?xml` with no
   `<plist>`, and 40 random bytes each give a distinct message.
8. **Budgets (D11), one test each at cap+1:** objects, depth 33, keys per dict, string length.

**`ProcreateImportTest`**
9. **The two real `.brush` archives convert and validate clean (D14):** every preset has
   `sourceFormat = "procreate"`, `engine = "stamp"`, `license != "CC0"` unless the archive declares
   one, and `BrushValidate.validate` returns **empty**. Non-vacuity: assert the expected brush names
   are present (the archives' own `name` values).
10. **THE MAPPED-CURVE TEST:** an archive with `dynamicsPressureSize` and `dynamicsPressureOpacity`
    curves produces `size.inputs` and `opacity.inputs` that are **non-empty** and carry
    `BrushInput.pressure`, with the parsed points equal to the archive's `"{x, y}"` strings. **This is
    the assertion that would have caught JB-8.03's Finding 1.**
11. **Every ≈ field is a LOSSY with the raw kept (D1, D-mapping):** `plotSpacing`, `plotJitter`,
    `plotSmoothing`, `textureScale`, `grainBlendMode`, `shapeFlipXJitter` each appear in `extensions`
    under a `procreate.` key **and** produce a warning naming the field.
12. **The unverified-scale warning is ONCE PER PACK (D10):** a `convertBrushSet` of 5 brushes produces
    **exactly one** spacing warning across all five, and test 11's warnings are per-brush only for
    fields that are genuinely per-brush. *(This is JB-8.03's review Finding 2 as a named test.)*
13. **A built-in resource refuses the brush, and substitutes nothing (D8):** an archive with
    `bundledShapePath = "BrushStudio/SmoothRound"` and no `Shape.png` is refused with a reason naming
    that path and the word "built-in"; the other brushes in the same archive survive and none of the
    survivors has an `image` tip pointing at a file that is not in the archive.
14. **A flat XML archive converts (D6):** name + author only → one preset whose `name` is the archive's
    and whose `author` is the archive's, with a warning that the archive carried no settings.
15. **A bad curve refuses the brush and nothing else (D9):** one brush with a 65-point curve (over the
    shared cap), one with `"{0.5}"`, one with an x of 1.4, one with a non-finite y — four refusals
    with four distinct reasons, and the good brushes in the same file are `==` what a file without
    them would have produced.
16. **A hostile entry name refuses (D12):** `"../evil.png"`, `"/etc/passwd"`, `"a\\b.png"`, `"c:x.png"`
    and a name with a control character each refuse; and the test writes nothing outside the output
    directory (assert the directory listing is unchanged).
17. **A zip bomb refuses before allocating (D11):** a 1 KiB entry declaring 100 MiB refuses with the
    ratio named, in under a generous timeout.
18. **Every budget refuses (D11), one test each at cap+1:** archive bytes, entry count, entry bytes,
    entry name length, inflated bytes per entry, brushes per pack, `extensions` per brush. Each names
    its cap.
19. **Malformed archives throw, they do not return empty (D15):** not a zip, a zip with no
    `Brush.archive`, a truncated bplist, an empty file, a 3-byte file. Each throws `BrushException`
    with a message; none returns an `ImportLibrary` with an empty `brushes` and an empty `refused`.
20. **The set's order comes from `brushset.plist` when it is readable (D15):** a set whose plist lists
    brushes B, A, C imports in that order; a set with a corrupt plist imports in folder-name order and
    warns **once**.

**Command:** `./gradlew -p joybrush :core:jvmTest`. Passing = `BUILD SUCCESSFUL`, 0 failures.

## Do not

- Do **not** extract or substitute a Procreate built-in shape or grain. Ever. R4 §5.
- Do **not** add `java.*` to `commonMain` — `java.util.zip` appears in `jvmTest` **only**, as the
  oracle for the Kotlin inflate.
- Do **not** redefine `ImportLibrary` / `RefusedBrush` / `ImportResult` (R23).
- Do **not** warn per brush about a whole-pack fact (D10).
- Do **not** trim an over-long curve to fit (D9).
- Do **not** set `license = "CC0"` for an archive that does not say so.
- Do **not** run this spec at the same time as JB-8.01 or JB-8.04 — they share `ImportSupport.kt`.
- **Do not run this spec at all until JB-8.01 has landed.** Decision 13 reads
  `BrushValidate.MAX_INPUTS` / `MAX_CURVE_POINTS`, and **JB-8.01 is what makes them public**
  (its Decision 9). They are `private const` in the tree today, so this spec does not compile until
  then. The board's *Needs* column for this row says `0.03` only; that is a board inaccuracy and the
  dispatch order is **JB-8.01 → (this row | JB-8.04)**, never two at once.
- **Do not restate `JbArchive.unsafeReason` as a shared constant.** It is **private**
  (`JbArchive.kt:514`) and lives in `androidkit`, which `commonMain` cannot see — Decision 12's
  "applied a second time" is a restatement, and the comment naming the other copy is what makes that
  honest. The exact set it refuses: empty name; over `MAX_NAME_CHARS`; any char with `code < 0x20` or
  `code == 0x7F`; a `\`; a leading `/`; a `:`; a path that is nothing but a separator; an empty path
  segment; a segment that is `.` or `..`; and a segment whose `trimEnd(' ', '.')` is empty, `.` or
  `..`. **A substring check for `".."` is not the rule** — the segment rule is, which is why
  `".. "`, `"a/./b"` and `"a/ /../b"` all still climb out.

## Stop rule

If anything here is ambiguous, or a claim about the `.brush` format turns out to be false when you
build the fixture, **STOP**: write the question in *Questions* under a heading `for the cross-reviewer`,
set this row `⛔ Blocked`, commit, push, and take another task. Three things are never a builder's
call in this file: **whether a Procreate built-in may be extracted or substituted** (never — R4 §5,
and there is no exception to find), **whether a DEFLATE decoder belongs in `commonMain`** (Q1 — build
the reading side behind the interface the way the spec says and stop if you cannot), and **whether an
unverified scale may be shipped** (Q2 — guessed scales are allowed *only* with the per-pack warning of
Decision 10; a scale you cannot defend is a field that goes to `extensions` with a warning).

## Definition of done

- [ ] tests pass (paste output)
- [ ] only owner-area files changed (paste `git status --short`)
- [ ] committed as `JB-8.02: Procreate import`; pushed
- [ ] ROADMAP row → 🟧 Built

## Questions

### ⛔ for the cross-reviewer — why this is still Draft

**Q6 (NEW, and it is the one that matters). May an importer set `source = "image"` on a tip or a
grain?** The three Phase 8 specs currently give three different answers:

| Spec | Answer |
|---|---|
| JB-8.01, Decisions 4–5 | **No.** A sampled tip becomes a soft procedural stand-in with the raw bytes in `extensions`; a texture pattern is kept raw and **the grain stays disabled**. |
| **this spec**, Decision 1's table | **Yes, unconditionally** — `tip.source = "image"` with `tip.image = "shape.png"`, and `paperGrain.source = "image"` with `.enabled = true`. |
| JB-8.04, Q1 | Leans **yes**, provisionally, and says so. |

**Verified in the tree, 2026-09-29:** `BrushValidate` **accepts** all three words —
`TIP_SOURCES = setOf("procedural", "image")` and `GRAIN_SOURCES = setOf("cloud", "image")`
(`BrushValidate.kt:20-21`) — and rule 23 only checks that an image source has a **non-blank path**,
not that the file exists. So this row's presets would **validate clean and then draw as procedural**,
because nothing in the engine reads an image (`PngWriter` is a writer only, and there is no reader
anywhere in `joybrush/`). That is precisely the "silent nonsense" failure that
`BrushJson.decodeChecked`'s own KDoc exists to prevent, and it is the failure the JB-8.03 review
filed against MyPaint's dropped curves.

There is a second, worse problem with this row's specific version, and it is not a policy question at
all: **`tip.image = "shape.png"` names a file inside the `.brush` archive, and the importer never
writes it out.** A `tip.image` is *"path inside the brush folder when source == image"*
(`BrushPreset.kt:34`) — a path in a folder the **brush library** owns. Nothing in this row, or in any
other, turns the archive's `Shape.png` into a file at such a path, and `commonMain` cannot write one.
So `tip.image = "shape.png"` is not merely undrawn — it points at a path that does not exist, and no
amount of an image-tip engine later fixes that without a writer. **The two answers the Lead has are
(a) an image-tip row that also owns *writing* the bitmap out and the library lookup, or (b) these
importers refuse a brush whose tip or grain is a bitmap, with a sentence saying why.** (a) is a real
row of real work; (b) is what JB-8.01 already does and is honest today. I am not choosing: it is a
file-format-shaped decision, it reaches three specs, and whoever lands first currently decides it by
accident.

**Q1 (structural, also blocking). `Inflate.kt` in `commonMain` or `androidkit`?** See below. The
cross-reviewer's note: a hand-written DEFLATE decoder is *permitted* in `commonMain` — it uses no
`java.*`, so it does not violate the module's own rule, and it serves the iOS door the blueprint §3.1
insists on. But it is ~350 lines of bit-twiddling inside the engine for a container one format uses,
and that is a "does a third-party format live in the engine" decision, not a naming one.

**Q3 and Q5 (product calls, ruling provisionally available but not blocking).** Q3 asks whether the
taper family is a LOSSY warning (my Decision 1) or a REFUSAL. **Ruled: LOSSY**, because refusing
taper refuses most of Procreate and the format genuinely has no envelope field yet
(`TipSpec.taper` is a *shape* taper — R4 §C says so explicitly). **PROVISIONAL — Claude to confirm.**
Q5 asks whether a set name should prefix each brush name; that is a naming choice inside this row and
does not block anything.

**Checked and found TRUE** (tree, 2026-09-29): `BrushValidate` accepts `"image"` as a tip and grain
source; `TipSpec.image` is documented as a *path inside the brush folder*, which is what makes this
row's `"shape.png"` wrong rather than merely premature; `PngWriter.encode(width, height, rgba,
compressionLevel = 6): ByteArray` exists (`PngWriter.kt:85`) and there is **no PNG reader** anywhere
in `joybrush/`, so "write the bytes out" is not a one-liner in any module; `MAX_CURVE_POINTS` and
`MAX_INPUTS` are `private const` (`BrushValidate.kt:28`, `:31`) and only JB-8.01 makes them public.


**Q1. BLOCKING on `Inflate.kt`. Is a hand-written DEFLATE decoder in `commonMain` the right call, or
should the zip reading happen in `androidkit`?** The case for core: `commonMain` is platform-neutral by
construction and the PC Brush Lab / iOS door need it; the case against: ~350 lines of bit-twiddling
for a container only this format uses, and `androidkit` already has `java.util.zip` in `JbArchive`.
**This is a Lead call because it decides whether a third-party compression format sits inside the
engine.** If the answer is `androidkit`, this spec's owner area changes and its tests move to
`:androidkit:test` (still JVM-testable, and the folder is a fifth importer instead of three).

**Q2. The unverified scales (R4 §B.2, `≈` in my mapping table).** `plotSpacing`, `plotJitter`,
`plotSmoothing`, `textureScale` and the taper family are guessed, and R4's own advice is *"build a
calibration set by exporting test brushes from Procreate with single sliders at known values."* **I
cannot do that — it needs Procreate.** Options: (a) ship guessed scales with the per-pack warning this
spec already emits (my provision); (b) ask the owner to export ~10 calibration brushes and add a row;
(c) refuse the fields, which would strip spacing from every imported brush. **I have written (a) and
would like (b) as a follow-up.**

**Q3. The taper family (`pencilTaper*` / `taper*`).** R4 maps it to a `stroke.taper{start, end, size,
opacity}` envelope that **the brush format does not have** — `TipSpec.taper` is a *shape* taper, and
R4 §C says explicitly *"Photoshop has no taper tip shape. Procreate 'taper' means stroke-end size and
opacity taper, a different thing. Keep both."* So the honest answer is that the format cannot express
it yet and every tapered Procreate brush imports without its taper. **Ruling needed:** is that a LOSSY
warning (my Decision 1, provisional) or a REFUSAL? Refusing means refusing a large share of Procreate
packs, since taper is on most of them.

**Q4. The CC0 lookalike library for Procreate's built-ins (R4 §B.9 lists p2k's recreation as CC0).**
Decision 8 refuses those brushes, which is legal and honest but means "your favourite Procreate brush"
often will not import. **Is there appetite for a row that ships a CC0 lookalike shape/grain library, or
is refusal the standing answer?** I have assumed refusal and will not substitute anything without a
ruling — an invented substitute is exactly the "imports successfully, draws differently" outcome the
JB-8.03 review filed.

**Q5. `.brushset` order and the set's name (Decision 15).** I read `brushset.plist` for order and
ignore its name (the presets are named individually). **Should the set name become a prefix on the brush
names** — `"MyPack · Round"` — so a person who imported three packs can tell them apart in the picker?
Trivial to add now and annoying to add later.
