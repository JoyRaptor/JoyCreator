# JB-9.03 / JB-9.09 — adversarial review (bunny-audit)

Read-only. No file was edited, no Gradle was run. The primary checkout is diverged and holds other
agents' work; nothing was staged, committed or reverted.

Method note: the hex sampler and the shader were both transcribed into a throwaway Python script
under `%TEMP%/opencode/` and run against the *shipped* `surface_pulp_artisan.png`, so the numbers
quoted below are measured, not read off a comment. The transcriptions are line-for-line with the
Kotlin and the GLSL, including the `f32` roundings on the GPU side and the `byteOf` re-quantisation
on the CPU side. No conclusion below rests on a transcription alone where a file:line also proves it.

**No BLOCKER.** The sampler is continuous, the two twins agree on height, the paper read is in
document space, the version discipline of JB-9.09 is right, and NaN does not slip through any range.
Details in *Not reproduced*.

## Findings

1. **MAJOR** · R31's hole is re-opened by the new `paper` field, and the row's own test pins the
   destruction mechanism · `BrushJson.kt:63` (`ignoreUnknownKeys = true`), `BrushJson.kt:62`
   (`encodeDefaults = true`), `BrushJson.kt:136-138` (`versionFor` never downgrades),
   `BrushJson.kt:158` · input: a version-6 `joybrush/brushes/pencil/brush.json` (paper 1 / 0.6 / 0)
   opened and re-saved by a BRUSH_VERSION 5 build · the old build's `wordsNeedingVersion` has no
   `paper` word, so it does not refuse; `ignoreUnknownKeys` drops the section; `versionFor` returns
   `max(5, 5) = 5`… and then `encodeDefaults = true` writes `"paper": {"influence": 0.0,
   "directional": 0.0, "wet": 0.0}` into a file that still claims version 6. The owner's tuned pencil
   becomes "ignores the paper", silently, and the file lies about its own version forever after ·
   contradicts spec: **yes** — this is R31's named defect verbatim ("re-saving drops unknown keys
   and can stamp a newer version over discarded content"). R31's fix, JB-0.02d, was scoped to
   `DocJson` only; `BrushJson` still has no unknown-key walk and no test that it refuses. The ROADMAP
   row for JB-0.02d says "**R31's freeze is lifted**" and that sentence is what let JB-9.09 lean on
   additive fields. · `BrushTest.kt:960-963` goes further and pins the mechanism as intended: *"a file
   written by `encodeDefaults` says 0/0/0 explicitly and must still be a file an older build can
   open."* That is correct for a brush nobody tuned and wrong for one that was. The test cannot
   distinguish the two cases and says so in the other direction. **For the Lead:** either give
   `BrushJson` the `DocJson` walk, or say in R31 that a brush field is exempt, and record what an
   older build does to a tuned brush.

2. **MAJOR** · "never shows its grid" is tested at ONE of the two periods the sampler has, and the
   untested one is the stronger · `HexTileTest.kt:188-218`,
   `shader_check.js:151` / `:165-177` / `:196` · measured on the shipped asset with the CPU twin at
   the catalogue's own numbers (`texelPx 2.0`, `hexTexels 180`, `slopeRange 0.099`): the hex read
   correlates **+0.224 with itself one hex away (360 doc px)**, **+0.143 one hex row away (312 doc
   px)**, and **−0.105 one TEXTURE period away (1024 doc px)**. The underlying B channel of the
   shipped surface correlates −0.183 at 360 doc px, so the hex lattice is the *only* positive
   self-correlation the field has at any lag above 66 doc px, and it is there because of the sampler,
   not the asset · both tests look only at the 1024 doc px period, and `shader_check.js`'s strip is
   2048×**256** doc px, narrower than one hex row, correlated **along x only** — it cannot see a
   two-dimensional lattice even in principle. `HexTileTest`'s version runs on a 32×32 texture whose
   four channels are all random bytes (`HexTileTest.kt:192`), i.e. white noise, which passes a
   no-repeat bar at *every* lag; it never loads the file the phone loads. · contradicts spec:
   **yes** — the spec, not the code, defines no-repeat as one shift. `JB-9.03_paper_grain_quick_win.md`
   §Tests: *"the correlation of the strip's left half with its right half (a shift of 1024 doc px,
   exactly one texture period) is < 0.3"*. The row's headline claim is a two-dimensional claim and
   the test is a one-dimensional one. +0.224 is under the row's own 0.3 bar, so I am **not** claiming
   a grid is visible; I am claiming the test cannot answer the question the row asks. **For the
   Lead:** the threshold needs a 2-D autocorrelation at the hex pitch on the shipped asset, and the
   number that comes out is a taste call, not a boolean.

3. **MAJOR** · "twins match" is a KDoc claim with nothing behind it, and the KDoc is wrong twice ·
   `HexTile.kt:9-12` (*"`joybrush/shaders/jb_paper.glsl` is its line-for-line twin"*), `HexTile.kt:95`
   (returns slope **per texel**), `HexTile.kt:119-120` + `:202` (`byteOf` re-quantises the filtered
   float to a byte before decoding), `jb_paper.glsl:52` (decodes the *filtered* float, no
   re-quantising), `jb_paper.glsl:56` (`result.xy /= u_paperTexelPx`) · the two differ in units by
   exactly `texelPx` on the slope channels and by up to half a byte in precision, and neither side
   says so at the crossing · the spec's own parity test — *"a jvmTest that evaluates
   `HexTile.sampleSurface` at 20 seeded doc points and a GL readback of the same points agree within
   2/255 in height"* — does not exist in any form, and the spec records why: *"`PencilOnAFingerTest`
   records `TODO(JB-9.02)`… shader/CPU seeded-point parity awaits that twin"*. JB-9.02 landed;
   `PencilOnAFingerTest.kt:34` still carries the TODO. I ran the comparison myself: on height the two
   agree to **0.007/255** worst over 400 seeded points, so the row is not broken today — but nothing
   in the build would notice if it broke, and JB-9.06 (PNG export, CPU) and JB-9.08 (slopes, GPU) are
   both queued to start trusting the slope channels. · contradicts spec: **yes**, the spec's Tests §3
   ("If JB-9.02 has landed") is a conditional that has come true and was not met.
   9.03b Decision 4 covers the re-quantisation; it says nothing about the unit divide.

4. **MAJOR** · half of owner ruling P4 was dropped and the removal was not flagged ·
   `tasks/joybrush/research/R10_paper_and_canvas.md:40` — *"every brush respects it by default
   **unless the brush brings its own texture**"*; `R10_paper_and_canvas.md:88` — per-brush numbers are
   influence, directional, wet **"and an optional own texture override"** ·
   `JB-9.03_paper_grain_quick_win.md:47-48` (Decision 1: *"The brush's `paperGrain.image` / `scale`
   are no longer used for the PAPER read"*), `BrushPreset.kt:85-92` (three fields, no override) ·
   `joybrush/brushes/pencil/brush.json` still says `"image": "cloud_fine_256.png", "scale": 1.5` and
   `GrainMath.paperUniformsFor` (`GrainMath.kt:47-48`) overwrites both, so the file names an override
   the engine cannot honour · there is no word anywhere for "this brush brings its own texture", so
   the carve-out is unreachable, and the *only* way a brush can opt out of the universal paper today
   is `paper.influence = 0` — which is not the same thing and was not the decision as ruled ·
   contradicts spec: **yes** — R10 P4 and R10 §3. The one reading that saves it is that "its own
   texture" means the *tip* texture, which JB-9.03 preserved; on that reading P4's override clause is
   simply unimplemented. **For the Lead:** say which reading governs before JB-9.08 hard-codes it.

5. **MAJOR** · the paper programme's own listed defect has no row and was not fixed by the row that
   landed the read · `R10_paper_and_canvas.md:65` (*"Fixed `edge` with no `fwidth`: will shimmer
   zoomed out — **Fix** (§4.4)"*), `R10_paper_and_canvas.md:104` (*"it is missing today"*),
   `R10_paper_and_canvas.md:131` and `:207` (acceptance criterion **E**: *"No shimmer at 0.05x"*) ·
   `jb_grain.glsl:22-26` — `jb_heightCoverage` still floors `edge` with the constant `1e-3` and takes
   no `fwidth` · the fix is demanded in three places by the ruling the whole 9.x programme is built
   on, JB-9.03 is the row that put the paper into the dab shader, and neither JB-9.03 nor any Ready
   row in the 9.x block (9.06, 9.07, 9.08, 9.11) names it · contradicts spec: **yes** — the ruling.
   **For the Lead:** give it a row or a Decision in 9.08. It is one line in the shader plus its CPU
   twin, and it is the difference between the paper reading as paper at 0.05× and reading as sparkle.

6. **MAJOR** · three shipped dead sliders, and the test named for the rule that forbids them passes
   anyway · `BrushKnobs.kt:57-63` (`forBrush` returns `… + PAPER + SMOOTHING` for every engine that
   lays paint), `BrushKnobs.kt:211` (`NOT_YET`), `BrushKnobs.kt:232-239` · grep of every
   `BrushPreset.paper` reader in the tree: `BrushValidate.kt:298`, `BrushKnobs.kt:221-229` and tests.
   Nothing in the engine, the shaders or `GlPaintEngine` reads it, so a person dragging "Paper" on
   the Pencil settings sheet gets no change of any kind · contradicts spec: **yes** — R39
   (`LEAD_RULINGS.md:452`) *"a dead control is worse than a missing one"*, ruled for the blend chip
   and applied here by the same rule. The row's Decision 5 chose the hint tail instead, and the
   Lead's own `JB-9.03b` Decision 14 reverses that decision without the JB-9.09 row or its spec being
   amended. The row is marked 🟧 Built on the strength of a decision its own follow-up overturns.

7. **MAJOR** · the test that claims to forbid a dead knob checks that a preset field moved, not that
   anything happened · `BrushTuningTest.kt:31` — KDoc: *"**A dead knob is worse than a missing one**
   (owner's rule): every slider must move its brush, both ways, and stay valid"* ·
   `BrushTuningTest.kt:38-46` — the body asserts `lo != hi`, the `get` round-trip,
   `BrushValidate.validate` empty, and non-blank strings. All four hold for a slider that changes
   nothing a person can see, which is exactly the state `paper.*` is in · the same file's
   `thePaperSlidersAreOnEveryBrushThatLaysPaintAndOnNoOther` (`:92-108`) repeats the claim in prose
   at `:88-89` — *"Offering a slider that cannot do anything is the dead control the owner's rule is
   against"* — and then asserts the sliders are present for `ink`, `pencil` and `sable`. · this is
   the name/body mismatch the audit asked for, and it is the guard that would otherwise have caught
   finding 6. Contradicts spec: no (the test is honest, just not about what it says).

8. **MAJOR** · the paper's own numbers live in four places and nothing ties them together ·
   `joybrush/assets/paper/catalogue.json` (`texelPx 2.0 · slopeRange 0.099 · hexTexels 180 ·
   size 512 · rotatable true`), `GrainMath.kt:40-44` (the same five as `const`),
   `SurfaceAssetTest.kt:81` (`SLOPE_RANGE = 0.099f`), `shader_check.js:136-140` (the same four,
   hardcoded) · input: change `hexTexels` to 90 in `catalogue.json` · `ShippedCatalogueTest` stays
   green (it validates the catalogue's shape, not its agreement with the code), `GrainMath`
   keeps 180, `jb_paper.glsl` still reads 180, and the CPU twin — which takes `hexTexels` as an
   argument — is now 2× out of step with the GPU with no red anywhere. The paper's *sampler
   geometry* is the one thing a paper row must get exactly right · `SurfaceAssetTest.kt:80` labels
   its literal *"The catalogue's value for `pulp_artisan` (assets/paper/catalogue.json)"* and then
   never opens the catalogue; that is the same defect the JB-9.04/9.05 audit filed at
   `JB-9.04_9.05__bunny-audit.md:31`, in a second file. Contradicts spec: **yes** — R10 §3 makes the
   catalogue the place a paper's physical size, hex-tile size and `rotatable` live.

9. **MINOR** · the 1×1 placeholder is not the "1×1 white texel" the safety rule rests on, and
   `GrainTextures` has no test at all · `GrainTextures.kt:32-41` — `create()` calls `glTexImage2D`
   and nothing else: no `glGenerateMipmap`, no `glTexParameteri`. `GL_TEXTURE_MIN_FILTER`'s initial
   value is `GL_NEAREST_MIPMAP_LINEAR`, so the texture is not mipmap complete and is **incomplete**;
   GLES 3.0 §3.8.13 says an incomplete texture samples as (0,0,0,1) · `GrainTextures.kt:27-28` calls
   it *"A 1×1 white texel"*, and the whole of R45.4 / 9.03 Decision 5 rests on that being true: a
   missing surface must be "off, never white, which would paint everywhere". It is not provably
   white. For a surface texture "white" would have been worse than harmless anyway — R,G = 255
   decodes to slope `+slopeRange` on **both** axes, not to zero. No live path is hurt today, because
   every caller that binds the placeholder also sets the pitch to 0
   (`GlPaintEngine.kt:800-809`, `:881-882`), so the value is never read; it is a latent trap for
   JB-9.06, which is the first row to read the slopes off a possibly-missing surface · `GrainTextures`
   has **zero** tests: the `loadBitmap` injection seam at `:22` is unused, the folder-by-`startsWith`
   rule at `:90` is untested, and the `GLUtils.texImage2D` upload at `:57` is untested.
   `shader_check.js:70-72` builds its own `dummy` with the identical defect, so the one check that
   looks at this cannot tell. Contradicts spec: **yes** for the KDoc (9.03b Decisions 9 and 10 take
   the upload and the folder rule; nothing takes the placeholder).

10. **MINOR** · `GrainWiringTest` says its embedded pencil is the shipped file "byte for byte" and it
    is not · `GrainWiringTest.kt:15` (*"byte for byte the numbers of
    brushes/pencil/brush.json"*), `GrainWiringTest.kt:21` (`"scale":10`) vs
    `joybrush/brushes/pencil/brush.json` (`"scale": 1.5`) · the two disagree and the test is green ·
   the reason is `GrainMath.kt:47-48`, which overwrites `scale` with `GRAIN_UNIT_PX / SURFACE_TEXEL_PX`
   before anything reads it, so `GrainWiringTest.kt:65`'s `assertEquals(2f, g.paper.pitchPx)` passes
   for **any** input scale. The drift the KDoc promises to catch is the one property the test cannot
   see. The same test's `edge` and `tiltGradient` come from the hand-typed copy, so editing the
   shipped pencil's `edge` leaves the test green. Contradicts spec: no.

11. **MINOR** · the surface's alpha channel is carried end-to-end and consumed by nothing ·
    `SurfaceMaps.kt:117` (A = h²), `HexTile.kt:133` (`out[3]`), `jb_paper.glsl:54`
    (`result += w[n] * vec4(slope, sampleValue.ba)`), `GrainTextures.kt:57` (uploaded and mipmapped) ·
    `jb_grain_sample.glsl:25` returns `.z`; `jb_dab.frag:59` and `jb_tuft.frag:82` use only the
    height; `HexTile.sampleSurface`'s `out[3]` has no caller in the tree · A exists so that
    zoomed-out mips know the surface's roughness — which is the whole of R10 §4.2's anti-shimmer plan
    (`R10_paper_and_canvas.md:79`, `:106-107`) and the `hDry`/`hWet` moment coverage of §5. So the
    one channel the next rows need is currently dead weight, and the prior audit's finding that
    `A − B²` is **negative on 59.5 %** of the shipped surface's texels
    (`JB-9.01_9.02__bunny-audit.md:53`) has to be fixed at the same time, not later, or the
    anti-shimmer maths will be built on a negative variance. Contradicts spec: no (deferred, but
    nobody owns it).

12. **MINOR** · `PaperResponse`'s KDoc states the purpose of `VERSION_PAPER` backwards ·
    `BrushPreset.kt:76-78` (*"a build that predates the section reads a file that carries one as a
    brush with no paper in it — **which is why** a non-default section needs `VERSION_PAPER`"*) ·
    the old build does read it as 0/0/0 — that is the problem — and `VERSION_PAPER` does nothing
    about it, because `VERSION_PAPER` only guards a *new* reader meeting an *old* file
    (`BrushJson.kt:86-88`). The two directions need two guards and there is one. The guard the KDoc
    describes is the one that does not exist, and finding 1 is what its absence costs. Contradicts
    spec: no (documentation).

13. **MINOR** · Sable reads the paper even when it cannot use it · `TuftStroke.kt:606-607`
    (`paperPitchPx = GrainMath.SURFACE_TEXEL_PX` unconditionally) · a Sable with `tooth = 0` and
    `strays = 0` still uploads the 512² surface, bind it, and take three `textureGrad` reads per
    fragment for a value `jb_tuft.frag:129` discards (`reach = 0` → `smoothstep(0, 0.15, 0) = 0` →
    `mix(1.0, …, 0) = 1.0`) · 9.03b Decision 12 already says this; recorded here because the tuft
    path is a per-fragment three-tap read and this row put it on every Sable. `PAPER_TOOTH_SCALE`
    (`TuftStroke.kt:613`) is dead — grep finds no reader. Contradicts spec: no.

14. **MINOR** · the paper-slider census is a hand-picked list, not a census ·
    `BrushTuningTest.kt:92-108` names `ink()`, `pencil()`, `sable()` and the three engines that must
    not have them · `BrushValidate.ENGINES` (`BrushValidate.kt:16`) has six members, one of which
    (`"wet"`) has no brush file and no arm in `forBrush`, so it falls into the `else` at
    `BrushKnobs.kt:62` and silently gains the three · the test's name says "every brush that lays
    paint" and the body is three literals. The drift a shipped file can cause is caught instead by
    `DefaultPresetsTest:455` and `ShippedBrushFilesTest:28`; the drift a future engine can cause is
    caught by nothing. Contradicts spec: no.

## Not reproduced

* **A seam in the hex sampler.** I looked for the BLOCKER this row exists to prevent and could not
  find it. Transcribing `HexTile` exactly and walking five directions at 0.02-texel steps (a
  quarter of the finest step in `HexTileTest.kt:126`) gives a largest height step of **0.0068** to
  **0.0128** over a field whose own gradient is that order — i.e. the field is C0 across every hex
  edge and the "jump" is the gradient, not a discontinuity. The two branches of `lattice`
  (`HexTile.kt:70-85`) hand over at `fa+fb == 1` with identical vertex sets and identical weights,
  and `PaperTexture.bilinear` (`:26-51`) is continuous and exactly periodic under its own wrap, so
  the wrap adds nothing. `readAt` and `sampleSurface` use the same offsets and the same rotation on
  both paths (`:184-195` vs `:117-135`): for `rotatable = true` the CPU computes
  `t = R(θ)(p−c) + c + offset` and `jb_paper.glsl:48-49` computes the same product with the same
  column-major `mat2(c, s, -s, c)`, and the back-rotation `R(−θ)` matches `jb_paper.glsl:53` term for
  term. `Double` lattice vs `highp float` lattice diverges by ~1 ulp, not by a visible amount. **The
  brief's hypothesis — that the sampler is correct in the interior and wrong at the seam — is not
  supported by the code.** What is wrong is the *test* (finding 2).
* **A height drift between preview and export.** Measured: 0.007/255 worst over 400 seeded document
  points, on the shipped asset, with both sides' own roundings. Not a drift. The slope channels
  agree to ~5e-5 once the `texelPx` factor is applied by hand (finding 3 is about the missing
  multiply, not a wrong number).
* **A feedback loop.** `addDabs` (`GlPaintEngine.kt:826-827` then `:835`) and `addTuftStamps`
  (`:864-865` then `:874-882`) both allocate their stroke tiles *before* binding units 0 and 1, and
  `attach()` (`:1354`) touches only the framebuffer, so no allocation clobbers a sampler inside the
  bucket loop. `u_paperSurface` is set to unit 1 and bound every batch on both programs;
  `jb_smudge_dab.frag` includes neither `jb_paper.glsl` nor `jb_grain_sample.glsl` and reads no
  sampler, so the smudge path cannot be the one that reads unit 0.
* **A missing asset painting white.** `beginStroke` (`:800-809`) replaces a failed texture with
  `GrainUniforms.OFF` (pitch 0, so the shader never samples) and zeroes `paperPitchPx` for the tuft.
  R45.4's "off, never white" is honoured on both paths, whatever the placeholder's sampled value
  turns out to be.
* **A paper that slides with the pen.** `jb_dab.vert:30` does emit `v_dabCentre = a_dab.xy` and
  `jb_dab.frag:59` does read `jb_paperGrainHeight(v_dabCentre + v_offset)`. R45's half of the
  premise holds; the KDoc matches the code this time.
* **A NaN or out-of-range slipping a range.** Every range in `BrushValidate` is written `!in` or
  `!(v > lo) || v > hi`, both of which fail NaN. `BrushValidate.kt:299` (`v !in 0f..1f`) refuses
  NaN and both infinities, and `BrushTest.kt:989-994` proves it for all three paper fields with
  −0.1 / 1.1 / NaN / +∞. `PaperResponse.kt:80-83`'s stated ranges are the ranges enforced. I found
  no range that NaN passes.
* **A wrongly stamped shipped brush.** `DefaultPresetsTest.kt:499-516` reads the versions off the
  files and pins `{pencil, sable, ink, marker, softair}` as the only ones at 6, and
  `ShippedBrushFilesTest.kt:28-48` decodes, validates and round-trips all eight on disk. The
  version numbers in `brush.json` are 6/6/6/6/6 for those five and 1/2/3 for eraser/fill/smudge,
  which is what each file's own words need. `wordsNeedingVersion` (`BrushJson.kt:147-160`) carries
  the *introducing* version for every word (2 fill/behind, 3 smudge/push, 4 tuft, 5 response, 6
  paper), so no ordinary brush is swept along, and a v5 file saying `paper` is refused with the
  sentence `paper needs brush version 6` in words, not a stack trace.
* **A missing reflection census filter.** The known defect class is a census that skips a `Map`
  field behind a `Collection`-only test. The one filter of that shape,
  `SpriteBoardHoldsNoGridArithmeticTest.kt:118`, reads
  `Collection::class.java.isAssignableFrom(type) || Map::class.java.isAssignableFrom(type)` — the
  `Map` defect is already fixed there. `SpriteBoardHoldsNoGridArithmeticTest.kt:183` has a separate
  `Map` walk. I found no census over `BrushPreset`'s serialised fields to be defective; the trap for
  that structure is `BrushValidate.paramsOf` (`:324-333`), and `PaperResponse` is not a `Param` and
  has its own rule (26), so it is ranged.
* **The mip chain being dead code.** I expected `texelPx = 2.0` to force `textureGrad` to level 0
  always. It does not: at the Note 9's 0.05× low end (R38/P11) one screen px is 20 doc px = 10 texels,
  so λ = log₂10 ≈ 3.3 and level 3 is genuinely used. The rotated-gradient claim in
  `jb_paper.glsl:50-51` is the right derivation (`dt/dscreen = M·dp/dscreen`, and `M` is a pure
  rotation so it preserves the length that sets λ). I could not test it, but I could not fault it.
* **A dead control in the tuner UI.** `TuftTuningView.kt` (untracked, another agent's in-flight
  work) multiplies every layout constant by density at the use site (`kit.dpi(6f)`, `kit.dp(10f)`),
  which is what R32 asks for. I did not audit that file; it is not JB-9.03 or JB-9.09.

## What I could not check

* **Anything that needs the GPU.** No GL context, no device, no Gradle. Every shader-level claim is
  a reading of the GLSL plus a Python transcription of it, not a run. In particular: whether
  `textureGrad` with a rotated gradient and `LINEAR_MIPMAP_LINEAR` on this driver picks the level I
  predict; whether the 1×1 placeholder is incomplete in practice on this SoC (a conforming driver
  says (0,0,0,1), and no shipped path samples it either way, so nothing observable turns on it);
  whether `GLUtils.texImage2D` with `inPremultiplied = false` really leaves a texel with A = 0 and
  R,G,B ≠ 0 un-premultiplied, which is 9.03b Decision 9's whole subject and needs a real bitmap.
* **Whether the hex pitch at r ≈ 0.22 is visible to a person.** I can measure the number; I cannot
  see it. That is a taste call and belongs to the owner on the Note 9, which is where the row is
  already parked. The spec's bar (0.3, at one period) is the wrong instrument either way.
* **That the suites are green.** I did not run `:core:jvmTest`. The counts in the ROADMAP and in the
  specs (1328 for JB-9.03, 1339 for JB-9.09) are the builders' claims and I have not verified them.
  Note that JB-9.09's landed count of 1339 is *lower* than JB-9.02's 1346, which is possible only
  because the rows were run at different times against a moving tree; it is not evidence of
  anything either way.
* **The owner's T3 verdict.** The row is 🟧 Built *awaiting the owner on the Note 9*. Nothing in this
  review can stand in for "no grid at 1× and at 4×".
* **The `JB-9.03b__codex.md` review named in my brief does not exist** in
  `tasks/joybrush/reviews/`. The `specs/JB-9.03b_paper_audit_fixes.md` spec does, and its fifteen
  Decisions are the Lead's already-written answer to an audit I was not given. Findings 1, 3, 9 and
  13 above overlap its Decisions 4, 10, 12, 9 and 12 and I have said so each time; findings 2, 4, 5,
  6, 7, 8, 10, 11, 12 and 14 are not in it.
