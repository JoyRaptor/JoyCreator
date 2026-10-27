# JB-9.01 / JB-9.02 — adversarial review (bunny-audit)

Read-only audit of the landed `SurfaceMaps.kt`, `HexTile.kt`, `PaperTexture.kt`, their tests, and the shipped
`assets/paper/surface_pulp_artisan.png`. No gradle was run (R43/R44 item 6), so every finding below is proved
by reading the code and by an **independent Python port of the same maths** (lowbias32 hash, the shear, the
Scharr kernel, the gamma weights, wrapping bilinear, the byte encode/decode) run against concrete inputs.
The port reproduces the two headline numbers the row claims — control correlation 1.0, hex correlation 0.0041,
and the shipped asset packing to the file byte-for-byte in float64 — so the port is trustworthy on the paths
it covers.

Line format: `SEVERITY · claim · file:line · the input · what goes wrong · contradicts spec`.

## Findings

1. **MAJOR** · `centreX`/`centreY` are dead to the whole suite: all nine `HexTileTest` tests stay green with
   the hex centres replaced by `(0,0)`, so a wrong rotation pivot ships undetected · `HexTile.kt:89-91`
   (the two functions, called only from `readAt` at `:182-183`) · input: replace both bodies with
   `0.0`; the spec's test 4 (`HexTileTest.kt:143-160`) computes its own centres inline at `:147-148` instead
   of calling them, and tests 3/5/6/7/8 never touch them · every named test is GREEN under that mutation
   (verified: `test3=True test5=True test7=True`, and 6/8 cannot see it), while the shipped paper changes
   completely — mean |as-landed − broken| = **0.1095** against a field std of 0.1119, i.e. a different
   picture, not a shifted one. The shader carries the same formula inline at `jb_paper.glsl:44`
   (`u_paperHexTexels * vec2(float(i) + float(j)/2.0, float(j)*SQRT3/2.0)`), so the GPU twin is equally
   unpinned and neither side would catch the other drifting · **contradicts spec: no** (the spec does not
   name a test for the centres) — but it is the one number in the row with no guard at all, and the fix is
   one line: have test 4 call `HexTile.centreX/centreY` instead of re-deriving them.

2. **MAJOR** · `HEX_GAMMA` is declared in the contract and named in the spec's maths, but nothing reads it:
   `gammaWeights` hard-codes `w*w*w`, so changing the constant changes nothing · `HexTile.kt:25`
   (declaration, KDoc "Weight contrast: `w^3`, renormalised") against `HexTile.kt:160`
   (`out[k] = w[k] * w[k] * w[k]`) · input: set `HEX_GAMMA = 1f` or `8f` and re-run · the gamma stays 3, the
   build stays green, and the constant lies to every future reader. Verified by `rg -n HEX_GAMMA`: the only
   hits in the whole repo are the declaration (`HexTile.kt:25`) and the KDoc that describes the hard-coded
   multiply (`:152`). Spec line 30 lists `const val HEX_GAMMA = 3f` as part of the contract and line 72 says
   `w'_k = w_k^HEX_GAMMA / Σ w^HEX_GAMMA`, so the spec's own text treats it as the knob ·
   **contradicts spec: yes — JB-9.02 Decision 2 / the maths block**, which presents `HEX_GAMMA` as the
   weight-contrast parameter. Also a "dead knob" of the family JB-9.03b item 14 already sweeps up.

3. **MAJOR** · The slope round trip is asymmetric and the suite documents the asymmetry instead of pinning
   it: `encodeSlope(0, r)` is 128 and `decodeSlope(128, r)` is **+r/255, not 0**, so every flat texel in
   every surface reads back with a constant positive slope in both axes · `SurfaceMaps.kt:85` (encode) and
   `:94` (decode), KDoc at `:89-90` admits "128 ↦ +0.5/255·2·slopeRange ≈ 0, NOT exactly 0" · input: the
   flat board in `ImportedTextureTest.kt:216-218` (a constant texture packs to `R = G = 128`) or the shipped
   asset's dark tail (22 texels at B ≤ 11) · the decoded slope is not zero where the true slope is zero; on
   the shipped surface with `slopeRange = 0.099` that is a bias of **+3.88e-4 per texel** in +x and +y on
   every texel whose neighbourhood is flat, i.e. a spurious uniform "the paper tilts that way" over the
   whole sheet. JB-9.03b item 1 already fixes the encoding (`round(127 + 127·clamp(s/r))`, decode
   `(b−127)/127·r`, 255 never written) · **contradicts spec: no** — the spec's own contract line 40 documents
   this exact behaviour and calls it tested. It is a spec design, not a code slip; filed because the
   *consequence* (a systematic directional bias feeding R10 §5's directional deposit) is not written down
   anywhere.

4. **MAJOR** · `A − B²` is not a variance and is negative on **59.5%** of the shipped surface's texels, yet
   two KDocs and spec Decision 3 both promise it is "a real variance" that "gives the mips the surface's
   roughness" · `SurfaceMaps.kt:105` ("A = h² gives the mips the surface's roughness for free, since
   variance = A − B²"), `HexTile.kt:152-155` ("A - B² has to stay a real variance, because the zoom maths in
   JB-9.06 reads roughness out of it"), spec Decision 3 · input: the shipped
   `surface_pulp_artisan.png`, whose B channel I read and whose A channel I checked against
   `A/255 − (B/255)²` · **155902 of 262144 texels are negative**, minimum **−0.001861**; the reason is that
   `A` is a *quantised* byte of h², so `A/255 − h²` is pure rounding error uniform on ±1/510 (measured std
   0.001157 against a predicted 0.001132), and at mip 0 there is no within-texel variance to recover at all —
   the true 2×2 local variance of this asset is 0.001084, the *same size* as the quantisation noise. It
   stays negative 24% of the time at mip 1 and only goes positive by mip 4 · any consumer that takes
   `sqrt(A − B²)` (JB-9.06's zoom maths, named in the KDoc) gets **NaN** on 59.5% of the shipped surface ·
   **contradicts spec: yes — JB-9.01 Decision 3**, which asserts the variance identity. The layout itself is
   right (the bytes are exactly what pack.py writes — I verified 0 differing channels in float64); it is the
   *claim about what A means* that is false, and that claim is what a later row will build on.

5. **MINOR** · `encodeSlope` has no non-finite guard on the slope itself: a NaN slope survives
   `coerceIn(0f, 255f)` (both comparisons false) and `roundToInt()` maps it to **0**, i.e. the opposite rail ·
   `SurfaceMaps.kt:85` · input: a height field with one NaN texel in a field large enough that the NaNs sort
   *above* the 99.9 percentile — on 512×512 that is 12 NaN slopes out of 524288, the percentile lands on a
   finite value, `defaultSlopeRange` returns a legal `0.001`, and `pack` proceeds · the NaN texels pack to
   `R = G = 0`, read back as slope **−r**, the most negative slope the format can express, with no error and
   no message. On a *small* field (8×8) the same input is caught loudly, because the percentile itself
   lands on the NaN and `require(slopeRange.isFinite())` throws — so the failure mode depends on texture
   size, which is the worst kind. Spec Decision 6 requires the guard only on `slopeRange`, so this is not a
   contract break · **contradicts spec: no**. Note the shipped path cannot produce this: `pack` takes
   `ByteArray`, and both shipped and imported callers build heights from bytes (`ImportedTexture.kt:59`),
   so this is reachable only through a direct `slopes`/`encodeSlope` call with a hand-made FloatArray.

6. **MINOR** · `HexTile` guards `out.size` and (through `decodeSlope`) `slopeRange`, but has **no guard on
   `hexTexels`**, so the one caller-supplied length that divides has none · `HexTile.kt:104`
   (`hexTexels: Double`, no `require`), against `:109` (`require(out.size >= 4)`) and `SurfaceMaps.kt:84` ·
   input: `sampleSurface(tex, px, py, hexTexels = 0.0, …)` → `qx = px / 0` is ±Infinity in Kotlin (no
   throw), `floor` of it saturates, `i0.toInt()` gives `Int.MAX_VALUE`, and the read returns a plausible
   finite-looking sample of the wrong place; `hexTexels = NaN` reaches `Double.toInt()` on a NaN and throws
   a bare `IllegalArgumentException` with no message naming the argument · **contradicts spec: no**. The
   catalogue does validate the shipped value (`PaperCatalogue.kt:152`, 16..size), so this is not reachable
   through the catalogue; it is reachable through `HexTile` used as the row's own "canonical maths".

7. **MINOR** · The no-repeat correlation in the board row and in `HexTileTest.kt:216` is measured on a
   **random-byte** texture, and on a texture with real spatial structure the same measurement reads 0.95,
   so the headline "correlates 0.004" does not describe the shipped paper · `HexTileTest.kt:189-194` (the
   `Rng(0xC0FFEE)` random 32×32 texture) against the board row for JB-9.02 ("One texture period apart the
   read correlates 0.004") · input: the shipped `surface_pulp_artisan.png`, read over a 256×256 grid with
   `hexTexels = 180`, `slopeRange = 0.099`, comparing the read at (x, y) with the read at (x+512, y+512) ·
   the raw correlation is **+0.947** and the plain-tiling control is 1.000. I chased this down before filing:
   it is **not** a repeat. Along a 6000-sample line the hex read's autocorrelation at lag 512 is **−0.0375**
   while a single plain read's is −0.0003, and at lag 7 the hex read is 0.259 against the plain read's
   0.338 — the hex read decorrelates *faster* than the texture's own smoothness. The asset is simply a
   smooth, low-frequency sheet (its B channel already correlates +0.938 with itself at a 7-texel shift), so
   a two-dimensional "field vs field 512 apart" number on it measures the paper, not the tiling ·
   **contradicts spec: no** (the spec's test 5 asks for exactly this test on this texture, and it is the
   right test for the property). Filed as MINOR because the *board's* number is quoted as if it described
   the shipped paper, and it does not; a reader checking the claim against the asset will get 0.95 and
   conclude the row is broken.

8. **MINOR** · `SurfaceAssetTest` asserts `worst <= 1`, a tolerance loose enough to accept a systematically
   wrong encoder on half the channels · `SurfaceAssetTest.kt:72-76` (the assertion) and `:79-82` (the
   hard-coded `SLOPE_RANGE = 0.099f`, commented "The catalogue's value for `pulp_artisan`") · input:
   change the encode scale in `SurfaceMaps.kt:85` from `255f` to `254f` · 262373 of 524288 slope channels
   differ from the shipped file and **worst is still 1**, so the test passes; only at 253 does it go red. The
   row's own verification note leans on the *count* (285 of 1048576) as the reassuring number, but the count
   is a `println`, not an assertion, so nothing guards it · **contradicts spec: no** (spec test 9 says
   "±1 per channel"). Two things compound it: the tolerance is the only cross-check between the Kotlin twin
   and pack.py (nothing else asserts against the shipped PNG), and the catalogue's `slopeRange` is a second
   copy of a shipped value — the same duplication already filed for this file in
   `reviews/JB-9.04_9.05__bunny-audit.md` finding 2.

9. **MINOR** · `surface_pulp_artisan.png` is 512×512 with 22 texels at B ≤ 11, and `A` at those texels is
   0 or 1, so the "A = h²" channel carries no signal exactly where the surface is darkest — the roughness
   claim of finding 4 is weakest at the extremes of the range · `assets/paper/surface_pulp_artisan.png`
   (B min = 1, 22 texels at B ≤ 11) read against `SurfaceMaps.kt:117` · input: any consumer keying on
   `A == 0` to mean "flat and dark" gets a channel that is 0 for two different reasons (a genuinely black
   texel and a rounding artefact) · **contradicts spec: no**. Folded here rather than filed separately; it
   is the tail of finding 4, not a separate defect.

## Not reproduced

Things I suspected, chased, and could **not** prove. Recorded so the next reviewer does not spend the time.

- **A seam at the hex-cell edges on the shipped paper.** Chased hardest, because the brief says a seam here
  is a BLOCKER and this is the bug the row exists to kill. I swept the shipped asset at 0.005-texel steps
  across cell edges: worst height step **0.000580**, against a natural single-read step of **0.000673** on
  the same sweep — the hex blend is *smoother* than the texture. At the test's own 0.25-texel step size the
  worst step is 0.04096 against a 0.06 budget. Mutating the blend down to a single read gives 0.629 (red) and
  to two reads 0.294 (red), so test 3 has real teeth. **No seam.**
- **Disagreement between `sampleSurface(t)` and `sampleSurface(t + period)`.** `PaperTexture.bilinear` is
  exactly periodic: over 2000 random points at ±4000 texels, the worst disagreement between `bilinear(t)` and
  `bilinear(t + tex.w)` (and `t + tex.h`) is **exactly 0.0**. The wrap is correct and the half-texel
  convention holds.
- **A rotation sign error surviving the tests.** `HexTileTest`'s test 7 is the only rotation guard, and it
  checks length (within 5%) and octant spread (≥6 of 8). I ran the sign-flipped back-rotation
  (`R(+θ)` instead of `R(−θ)`): **still 7 octants, still length exactly 0.5 — GREEN.** So test 7 does *not*
  pin the sign of the back-rotation. I did **not** file it, because I then checked the shader and
  `jb_paper.glsl:53` (`mat2(c, -s, s, c) * slope`) is the same `R(−θ)` the CPU applies at `HexTile.kt:129-130`,
  and the length+spread assertions cannot distinguish a rotation from its mirror by construction — the
  property is untestable this way, not wrong. Worth a note to the Lead: **a mirror-symmetric rotation
  cannot be tested by octant spread**, so if anyone later changes which way the turn goes, no test in either
  row will say so. (JB-9.03b item 1 changes the encoding and will touch this line; the sign guard is still
  absent afterwards.)
- **A lattice vertex-set flip at an exact hex centre**, which would make `HexTileTest.kt:143` (`aHexCentreLandsBackInsideItsOwnHex`)
  and the `top > 0.98` filter in test 7 miss. Over 169 centres at `hexTexels = 12` there are **0** misses,
  and over 6561 centres each at 11, 12, 16, 37, 180 and 512 there are **0** rejections by the `top > 0.98`
  filter — so test 7's loop always terminates and test 4 always finds its vertex. (Test 4's `cx + 0.001`
  nudge at `:149` is not load-bearing; the unflipped centre works too.)
- **Corruption from an overflowing `w * h * 4` in `pack`** (`SurfaceMaps.kt:111`). `ByteArray(w * h * 4)`
  overflows Int above `w*h > 536M`, i.e. a 23171×23171 texture. The catalogue caps `size` at 2048
  (`PaperCatalogue.kt:141`), and no importer path reaches it. Not reachable.
- **`defaultSlopeRange` disagreeing with pack.py.** I recomputed it in float64 from the shipped B channel:
  **0.099**, exactly the catalogue's value, and exactly what `np.percentile(..., 99.9)` with NumPy's linear
  interpolation gives. The percentile arithmetic, the `ceil` to 0.001, and the `coerceAtLeast(0.001f)`
  floor all match. **No drift.**
- **The `/32` divisor or the `3,10,3` weights drifting.** I ran the divisor mutation the spec forbids:
  `/16` gives 258539 differing R channels with worst 64, `/8` gives 260457 with worst 96. Both are loudly
  red against the shipped file. **Pinned.**
- **The Scharr kernel's wrap.** On a single-texel bump at (0,0) of an 8×8 field I reproduced the spec's
  derivation exactly: `dx(7,0) = 10/32 > 0` and `dx(1,0) = −10/32 < 0`. The wrap is right.
- **CPU/GPU disagreement in the hash.** My independent lowbias32 (written from the spec's four lines, not
  from `HexTile.kt`) reproduces all five golden values the spec names, and matches the shader's `>>>` and
  `uint` semantics including the `>>>`-on-`UInt` equivalence the KDoc at `HexTile.kt:37-38` claims. **No
  drift.**

## What I could not check

- **Nothing was executed under Gradle**, by the reviewer's instruction not to run it (R43/R44 item 6). Every
  "GREEN under mutation" above means *my port* of the test logic stays green under that mutation, which is
  strong evidence about the assertions but is not the build's own output. The one thing I would want run is
  finding 1's mutation against the real `HexTileTest` on the JVM.
- **The GPU half of the twin.** `jb_paper.glsl` was read and its rotation algebra checked by hand against
  the CPU, but no GL context was run; `shader_check.js`'s no-repeat check was read, not executed. Findings
  1, 3 and 7 concern the shader only insofar as it carries the same unpinned formula; whether the driver
  produces the same floats as the CPU at the shipped scale is untested here.
- **`ImportedTextureTest`, `PaperCatalogueTest`, `ShippedCatalogueTest`** were read for context (and the
  flat-board case in finding 3 is theirs) but are not this review's rows; their verdicts are not mine.
- **Per-pixel cost.** The brief asks for O(n²) in a per-dab or per-pixel path. `sampleSurface` allocates a
  `Lattice`, two `IntArray(3)`, one `FloatArray(3)` and two `FloatArray(4)` per call (`HexTile.kt:110-113`,
  `:141-143`, `:72-83`), which is GC pressure rather than quadratic work, and no caller in the repo invokes
  `sampleSurface` per pixel today (only `PencilOnAFingerTest.kt:34`, which carries a `TODO(JB-9.02)` for it).
  Whether 8 allocations per sample is acceptable for JB-9.06's full-screen CPU export is a decision for that
  row, not one I can settle without a device.
- **Memory ceiling.** With ~2.5 GB free I kept the sweeps to ≤6000 samples and did not attempt the
  512×512 exhaustive covariance builds; the 256×256 grid figure in finding 7 is a subsample.