# JB-9.11 — Imported brush textures become surfaces (slopes derived automatically)

| | |
|---|---|
| **Tier** | T1 (pure core) |
| **Status** | 🟦 Ready for the core half (paper specialist, 2026-10-01). The drawing half belongs to JB-1.05d (spec file still missing; the Lead's row) |
| **Builder** | OpenCode free agent |
| **Depends on** | JB-9.01 |
| **Owner area** | NEW `joybrush/core/.../paper/ImportedTexture.kt` + test; EDIT the importers' texture-storing code ONLY to call it (`core/.../import/` or wherever JB-8.01/8.02/8.04 store `patt`, `Grain.png` and Krita patterns: find them with grep and list them in your report) |
| **Estimated size** | ~120 lines + ~150 lines of tests |

## Goal
Owner P4: "if a brush came with a texture that's like a height map, we should derive a normal map out of it automatically … so a
Photoshop brush loaded in will work better in our app than in its native app." Importers already KEEP these textures (R40,
"kept but not drawn yet"). This row converts each one, at import time, into our surface layout (JB-9.01), so JB-1.05d/JB-9.08 can draw it
with direction.

## Contract (verbatim)
```kotlin
package cc.joycreator.joybrush.core.paper

object ImportedTexture {
    /**
     * Any imported greyscale pattern (w×h bytes, any size ≥ 3, tileable as the source app tiles it) → a packed surface.
     * invert = the source app's "Invert" flag (Photoshop Texture → Invert; Procreate Grain is WHITE = paint lands, i.e. high).
     * Returns the RGBA bytes plus the slopeRange used (SurfaceMaps.defaultSlopeRange).
     */
    fun toSurface(grey: ByteArray, w: Int, h: Int, invert: Boolean): Surface
    class Surface(val w: Int, val h: Int, val rgba: ByteArray, val slopeRange: Float)

    /** Colour patterns (ABR patterns can be RGB): luminance = (0.2126 R + 0.7152 G + 0.0722 B) on sRGB bytes, rounded. */
    fun luminance(rgb: ByteArray, w: Int, h: Int): ByteArray
}
```

## Decisions already made
1. **Height convention:** our height 1 = peak = takes paint first under light pressure. Photoshop's Height modes treat WHITE as high
   (R4). Procreate's grain: white = paint shows (high). Krita: as Photoshop. A source "invert" flag flips it. Verify each against R4/R3 and quote the line in your report.
2. **Store, do not replace:** the importer keeps the original texture bytes as today AND adds the packed surface beside it. A re-import can always be redone.
3. **No resizing.** Keep the source size. `GrainTextures` already handles any size.
4. A pattern that is flat (all one value) gets slopeRange 0.001 and is flagged "flat texture" in the import report.

## Tests
- A 4×4 checkerboard (0/255) → B round-trips, slopes non-zero at the edges, `invert` flips B (255 − b) and negates slopes.
- `luminance` on pure R, G, B, white and black against the formula.
- Each importer that now calls `toSurface`: one existing real-file test (JOYBRUSH_TESTDATA, LEAD_DESK order 5) asserts a packed surface is stored for a brush that has a texture. Synthetic files alone do not count (R44).

**Command:** `JOYBRUSH_TESTDATA=C:\+Projects\Screenrecorder\FadCam\joybrush\testdata-local ./gradlew --no-watch-fs -p joybrush :core:jvmTest --rerun-tasks` in your worktree. Never commit anything from `testdata-local`.

## Do not
Do not draw anything (JB-1.05d/JB-9.08). Do not remove the "kept but not drawn yet" warning; that goes when drawing lands.

## Definition of done
- [ ] tests pass with counts · [ ] the importer list in the report · [ ] pushed · [ ] ROADMAP → 🟧 Built

## Questions

**2026-10-01, OpenCode agent (JB-9.11): the CORE HALF has since landed; the importer half is still STOPPED.**
`ImportedTexture` (pure commonMain, on JB-9.01's `SurfaceMaps`) with its five tests is pushed; **no importer calls it
yet**, so nothing is stored and nothing is drawn. Q1-Q5 below are still open and still block the wiring. Two notes
from the build, neither of which blocks anything:

- **The Tests bullet 1 input cannot do what the bullet says.** A 4x4 checkerboard of SINGLE texels has period 2, so
  `H[y][x+1] == H[y][x-1]` at every texel and every Scharr difference is exactly zero: dx and dy are 0 everywhere and
  "slopes non-zero at the edges" is false by construction, not by a bug. The test pins that board's true behaviour (B
  round-trips, R = G = 128, range at the flat floor) and pins the non-zero-edge claim on the same 4x4 with a 2-texel
  cell, which is the smallest pattern on a 4-wide grid that has any slope. Corner (0,0) there reads dx = dy =
  −10/32 = −0.3125 exactly, off the wrap (its left neighbour is x = 3).
- **`luminance` reads INTERLEAVED RGB triples, three bytes a texel** (`rgb.size == w*h*3`, pinned by a test). A
  Photoshop `.pat` file stores its channels PLANAR — all the reds of a row, then all the greens — so whoever decodes a
  `patt` has to repack before calling this. That repack belongs to the ABR question below, not to this function.

Mutation check, as promised under Q6, both on the full suite: dropping the `invert` branch in `toSurface` turns
`invertFlipsTheHeightBytesAndNegatesTheSlopes` red (1 of 1362), and swapping the red and blue luma weights
(0.0722 -> 0.2126) turns `luminanceIsTheSrgbLumaFormula` red (1 of 1362). Restored, green, 1362/0/0/0 over 91 suites.
A ±0.0001 nudge of a weight would NOT go red: the five literals round the same way, and the test's own copy of the
formula cannot detect a coefficient change at all. The literals pin blue to `0.06863 <= w < 0.07255` and green to
`0.71316 <= w < 0.71725` — that is the honest width of this test, and a tighter one needs a colour whose luma lands
off a rounding boundary.

### Q1 — There is no PNG pixel decoder in `core`, and the importers refuse to decode pixels (blocking)

Every texture this row names is stored as **PNG bytes**: Procreate `Shape.png` and `Grain.png`, Krita embedded tips and
bundle `patterns/*.png`. The importers keep the bytes and never look at the pixels on purpose —
`ProcreateImport.kt:759-760` says the width comes from "`IHDR` … a header read and not a decode", and `PngChunks.kt:27`
explains why a chunk walk is expensive. But `toSurface(grey, w, h, invert)` takes **greyscale bytes**, so *someone* has to
turn those PNGs into pixels inside `core`, and there is none: the only PNG decoders in the repo are `javax.imageio` in
`jvmTest` and `BitmapFactory` in `androidkit/.../gl/GrainTextures.kt:93-99` — a different module, and not this row's
owner area. Which is it?
- (a) this row grows a PNG→grey decoder in `core` (IDAT inflate + the five filters + colour types + interlace — well past
  "~120 lines", and it wants its own row and owner area), or
- (b) `toSurface` stays pure (it already is) and only the **callers** get wired once a decoder row lands — which row id
  writes it, and does it go before JB-1.05d?, or
- (c) the decode happens on the phone at draw time in `androidkit` (JB-1.05d), and JB-9.11 lands `ImportedTexture` plus
  its two core tests with no importer wiring at all.

Until this is answered, two of the three bullets in **Tests** cannot be written, so I stopped rather than ship a row whose
middle step is invented.

### Q2 — Where the packed surface is stored, and it does not fit in `extensions` (blocking)

Decision 2 says the importer "adds the packed surface beside it" the original bytes. The only place an importer can put
bytes is `BrushPreset.extensions`, base64 text, capped at `MAX_EXTENSION_BYTES = 256 * 1024` characters **total across
every key** (`ImportSupport.kt:37-45`; its KDoc says on purpose that it "counts the base64 of a stored tip or grain image
as well as every unmapped setting", and `extensionChars()` sums all values in Abr/Procreate/Krita). A packed surface is
**4 bytes per pixel** and Decision 3 forbids resizing. Measured on the real files in
`joybrush/testdata-local/krita/deevad-v8-2.bundle`:

| stored texture | size | packed RGBA | as base64 | the PNG's own base64 |
|---|---|---|---|---|
| `brushes/3_texture.png` | 454×448 | 794 KiB | **1 059 KiB** | 211 KiB |
| `brushes/3_paint-sketch-b.png` | 256×256 | 256 KiB | 341 KiB | 44 KiB |
| `brushes/3_rake.png` | 150×150 | 88 KiB | 117 KiB | 9 KiB |

So the largest real texture needs 4× the entire budget on its own, and the smallest only fits if nothing else is stored.
Every option touches something outside the owner area, which is why I am asking:
- (a) raise `MAX_EXTENSION_BYTES` (a shared `const` whose KDoc says the number is not this row's to change),
- (b) add a `BrushPreset` field (a brush-format version bump, outside the owner area), or
- (c) store a downscaled surface (Decision 3 says no resizing), or
- (d) **do not persist the surface in this row** — land `ImportedTexture` alone and let JB-1.05d hold it in memory at
  draw time.

If (a) or (b), please also name the extension key and where `w`, `h` and `slopeRange` live, because the surface is raw RGBA
and nothing else in `extensions` has a header.

### Q3 — the real-file test (R44) cannot be written for both importers today

Tests bullet 3: "Each importer that now calls toSurface: one existing real-file test (JOYBRUSH_TESTDATA, LEAD_DESK
order 5) asserts a packed surface is stored for a brush that has a texture. Synthetic files alone do not count (R44)."
From the corpus:
- `joybrush/testdata-local/procreate/` is **empty**; `ProcreateImportTest.kt:31-46` already records that there is no real
  Procreate file. R44 says synthetic does not count, so a Procreate assertion cannot satisfy this bullet today.
- `deevad-v8-2.bundle` has 96 entries and **no `patterns/` entry at all** (only `brushes/`, `paintoppresets/`,
  `mimetype`, `preview.png`), so the Krita **grain** branch (`KritaImport.kt:902-921`) is not exercised by the real file;
  only the embedded tips are.

Is a real-file assertion on the **Krita tip path alone** enough for this row, with Procreate left for whenever a real
`.brush` lands — or does the row wait for real files?

### Q4 — is ABR (JB-8.01) in this row at all?

The owner area says to edit the code that "stores `patt`, `Grain.png` and Krita patterns". Grep result: **JB-8.01 stores
no pattern bytes.** `AbrImport.kt:409-417` writes `extensions["abr.texturePattern"]` as a *rendered string* only, and
`AbrReader.kt:515` says outright "Nothing here decodes a pattern's pixels" (`AbrPattern`, `AbrReader.kt:163`, carries
only id/byteLength/details). The bytes JB-8.01 does store are the tip (`AbrFile.storedBytes`, `AbrReader.kt:75-99`),
PackBits or raw grey, and that is a **tip mask, not a height map**. So ABR can only reach `toSurface` by first decoding
`patt`, which is a new reader feature well beyond this row. In, or out?

### Q5 — the height polarity: the sources and Decision 1 do not agree

Decision 1 asks me to verify each app against R4/R3 and quote the line. The quotes:
- R4:176 — "at 100% low points in the texture receive no paint"
- R4:181 — `t` is the texture "**valley-ness** after invert, brightness and contrast"
- R4:189 — Height (PS) is `a' = clamp(10·d·a − t, 0, 1)`, i.e. "Paint exists where `t < 10·d·a`" (R4:193: "Light
  pressure catches only the peaks")

Those three say a **high pattern byte is a valley**, so in our convention (`GrainMath.heightCoverage:68-72`,
`threshold = 1 − level`, high height paints first) the bytes would need **negating**. R3:422 says the same from Krita's
side: "Here t is the depth of paper valleys: after subtraction, low t receives paint first." But Decision 1 says
"Photoshop's Height modes treat WHITE as high (R4)" and "Procreate's grain: white = paint shows (high)", which is the
opposite reading, and Tests bullet 1 pins it (`invert` flips B and negates slopes, so pass-through and negation differ).

Please rule per app — Photoshop, Procreate, Krita — and say whether the importer's own Invert flag goes into the
`invert` argument as-is (Decision 1's reading) or whether `toSurface` also needs a fixed per-app negation. Getting this
backwards inverts every imported grain, so I will not pick.

### Q6 — two small ones

- Decision 4: "flagged 'flat texture' in the import report". Is the import report the per-brush `warnings` list
  (`ImportResult.warnings` / `BrushImport`'s `warn`), and what exact sentence should the flag use? I read Decision 4 as
  "substitute 0.001 for the range before `SurfaceMaps.pack` (which refuses `slopeRange <= 0`), keep B = the constant
  byte, R = G = 128, and add a warning" — confirm or correct.
- **Definition of done has no mutation check**, while the dispatch prompt and LEAD_DESK order 6 require one. Unless told
  otherwise I will mutate the `invert` negation in `toSurface` and the `0.0722` blue weight in `luminance` and report
  whether Tests 1 and 2 go red.

### Note on the dependency — CLEARED

JB-9.01 landed as `6630f344` and its `SurfaceMaps` carries exactly the contract this spec quotes (`slopes`,
`defaultSlopeRange`, `encodeSlope`, `decodeSlope`, `pack`, `SCHARR_NORM`), so the core half compiled and ran against it
with nothing to change here. Two things worth knowing for the wiring row: `defaultSlopeRange` already ends in
`coerceAtLeast(0.001f)`, which is where Decision 4's flat-pattern number comes from — a flat texture packs to R = G = 128
and never trips `pack`'s refusal; and `pack` re-derives the Scharr pass itself, so `toSurface` runs the kernel twice
(deliberate: the byte layout is written in exactly one place).
