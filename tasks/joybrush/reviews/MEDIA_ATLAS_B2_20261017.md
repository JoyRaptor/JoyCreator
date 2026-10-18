# Media Atlas v2 B2 — free placement, October 17, sonnet helper 1 (Opus)

Branch helper1/media-atlas-b1 (worktree C:/Temp/jb-helper1-atlas), on top of B1 (2c33263d4, 0119b6074) and base
origin/lead/claude-main 23de18dcd. Off by default: `MediaAtlasGl.enabled` is false, so MediaCanvas still builds a
MediaWindow. Nothing goes default-on before the Note 9 probe passes.

## What B2 adds

- **Unbounded layer space in atlas mode.** The engine's origin stays at the layer's 0; there is no window edge. Layer
  rects are not clamped, the wet grow bounds are only the 4 cm cap (WetSpread), and the bead's half-res rect is unclamped.
- **MediaAtlas** (androidkit gl/media) implements the new **MediaPlace** interface that MediaWindow now also implements
  (declarations only — the window's behaviour is unchanged). It sits on A1's MediaAtlasSlots + MediaAtlasResidency:
  15 resident slots + 1 staging. Tiles load per slot: payload blit, look, ground-from-look, page table, paper bake, and
  running water resumes if the tile was wet. Write-back stores the pending planes and the first ground.
- **Per-slot look** through the existing look target with a per-slot `u_pan`, until B4's look atlas.
- **MediaCanvas** chooses MediaAtlas or MediaWindow by the flag; the eraser's held stores go through `heldKeys()`.
- **MediaAtlasPlacement** (core): the reach constants, the tile list of a rect ⊕ reach, and the page-table texture.
  MediaAtlasSlots gained `clean`, `remove` (unpinned only), `clear`.

## The residency rule (guardrail b)

Every pass calls `need(rect, reach)` before it draws. MediaAtlas counts the tiles the pass needs against what can be
freed (unpinned, not this frame's, not held by running water) **before loading any**; if they don't fit it throws a
MediaRoomException in words ("…reaches further than this phone can hold at once (n areas of 15). Paint a smaller area at a
time."). Nothing is clipped. Granularity: the refusal is atomic per pass. In a wet frame the input dabs and the simulation
ask for the same rect, so a refusal always comes before that frame's first draw. A paste frame with several steps, or a
later frame of any stroke, can be refused after earlier steps or frames have drawn; what was drawn stays, whole.

## Reaches (guardrail c)

Wet follows the real read reach: WET_REACH 80 = the bead field's margin (3 + 2·12 half cells = 54 px, written) plus its
blur (24 px, read) = 78, which also covers the flux's 48 px drip taps and the steer ring. The defensive dilation stays for the others: PASTE_REACH 12, ERASE_REACH 3,
dry = ceil(smear px) + 4, LOOK_REACH 56. The running-water rule and the 4 cm cap are unchanged (guardrail d): while water
runs every resident tile is pinned (B3 refines that per tile).

Defensive-dilation finding: the dry dab's quad must cover everywhere the stick's zones can press, or coverage depends on
rasterisation rounding at the quad edge. The B1 harness fixture was undersized (xMax 3.4 → 3.6, yMax → 1.0); Stick.emit's
KDoc now says so (StickContact sizes the shipped quad; the device probe is the check on the phone).

## Deliberate one-time change: the dab frame from the exact cell centre (lead decision ii)

Interpolated varyings carry vertex-snapping error that depends on where the quad lands, so a dab drawn into slot 7 and the
same dab in the window differed by ulps, then by more as wet water amplified it. In **both builds** the dry, wet and erase
dab fragment shaders now derive their frame from `jb_fragPx()` (the exact cell centre) plus flat per-dab data:
`v_i0` = (centre, direction) for dry and wet, `v_cr` = (centre, radius) for erase. Paste, flux, update, bead, apply and
look are untouched.

Measured, new HEAD vs 23de18dcd, same strokes, SwiftShader (tools/media_atlas_parity_check.js, full):

| Pass | Planes that moved | Max abs delta |
|---|---|---|
| dry point stick | paper (crush) | 0.0103; look 83/255 at isolated pixels |
| dry side stick, smear | paper (crush) | 0.0082; look 48/255 |
| eraser | w0 / w1 / p0 / p1 | 0.0153 / 0.0093 / 0.0024 / 0.0142 (cover alpha); look 4/255 |
| wet (flat, tilted, reversed) | all wet planes | chaotic: w0 16–65, p0 ≤ 1.5, look ≤ 74/255 |
| oil, knife | p0 / p1 | ≤ 8e-5 — unchanged by this; the same as B1 before the change |

Dry pigment (p0, p1) is bit-identical; only the paper-crush plane moves. Wet shapes diverge chaotically — a different
ulp at one dab edge sends a bead down a different path — while the statistics stay the same (lab sheets below: mean
brightness within 0.04/255).

Lab sheets, tools/media_render.js on the Iris Xe (d3d11), base tree vs HEAD, per-channel 0–255 deltas:

| Sheet | Mean | p99 | p99.9 | Max | Pixels changed | Noise floor (base vs base) |
|---|---|---|---|---|---|---|
| pencil proto | 0.061 | 0 | 19 | 161 | 1.82% | — |
| pencil scribble | 0.080 | 1 | 21 | 94 | 2.86% | 0.134% changed, max 11 |
| watercolour wash | 0.001 | 0 | 0 | 89 | 0.27% | — |
| watercolour flood | 1.27 | 27 | 185 | 221 | 9.86% | 0 (bit-identical re-render) |
| oil round | 0.000 | 0 | 0 | 1 | 0.00% | — |
| oil impasto | 0.000 | 0 | 0 | 1 | 0.00% | — |

Mean brightness before → after: proto 214.47 → 214.48, scribble 156.73 → 156.72, wash 190.18 → 190.18,
flood 233.42 → 233.46, oil 188.05 → 188.05, impasto 170.38 → 170.38.

## Harness results

**B1 window parity** (media_atlas_parity_check.js, full, 512² layer, 4 slots): atlas vs window **0** mismatched pixels of
207,618,048 compared; the broken-table control detected 21,622,467; non-vacuous; no unset uniforms.
JSON: MEDIA_ATLAS_B2_WINDOW_PARITY_20261017.json.

**B2 free placement** (media_atlas_b2_check.js, new, full): the oracle is the window build on a 1280² layer (5 × 5 tiles);
the atlas engines hold 15 of 16 slots of a 1024² atlas and load tiles on demand from stores. Two placements — orderA
(load order) and orderB (scrambled) — must match the oracle bit for bit; noRing (no reach dilation) must not.
PASS. orderA and orderB: **0** mismatched pixels on all 7 strokes (wet flat, wet tilted with bead/steer/ring, dry point,
dry side smear, eraser, oil round, palette knife), 9 tiles resident each, 10–12 seam-crossing reads each. The noRing
control was caught 76,133 times (wet flat 55,058, wet tilted 21,075). For the dry, eraser and paste strokes the rect's own
tiles already cover their reach, so the noRing control can't see a missing reach there; the reach constants for those
media rest on the shader reading (above), not on this control. The tilted wash was moved 80 px into the block
(from 520,520 to 720,690): at 440,440 its area plus the 80 px wet reach reached tile 0 and needed 16 slots of 15, and
the harness refused it in words, as designed.
JSON: MEDIA_ATLAS_B2_FREE_20261017.json.

**Kotlin:** `:core:jvmTest` + `:androidkit:compileKotlin` + `:androidkit:test`, media filters (`*media*`, `*Media*`):
BUILD SUCCESSFUL in 4m 59s; 47 classes, 281 tests, 0 failed. Serialized under jb-gradle.lock, --max-workers=1, in-process
Kotlin, 3.95 GB free at start. The compile includes the probe's scrambled engine and the restored derived dab shaders.
Then the full `:core:jvmTest` + `:androidkit:test`: BUILD SUCCESSFUL in 3m 54s; 307 classes, 3335 tests, 0 failed (3.94 GB free).

## Device probe bar (condition 3)

MediaAtlasProbe now runs four engines on the same strokes: window, atlas-identity, atlas-scrambled (the 3 × 3 tiles in
slots 4 7 1 / 8 0 5 / 2 6 3, read back un-shuffled) and broken. Pass = atlas-identity vs window **0** and
atlas-scrambled vs window **0** (bit-exact float bits and look bytes) and broken > 0 and non-vacuous. Not run on the
phone (not mine to install); the lead runs it.

**Device result, October 17 (run by the Lead):** PASS on the Note 9 (Adreno 630). Build: joy-creator + d3d3c981f merged
as 1d1d4e19f, APK sha256 7f5242f3e8fedcd3d98ba3164c42f64096d59176572d6360f705e6a546e8a2a4. atlas-identity vs window
**0**, atlas-scrambled vs window **0**, broken caught 2,910,927 pixels, non-vacuous, glError 0. Strokes: pencil HB
(15 frames, 30,226 changed), watercolour Round flat (22, 1,091,452), watercolour wash tilted (22, 3,064,852), oil round
(15, 3,064,848), eraser (8, 2,797,708); atlas and scrambled are 0 on every stroke, and broken is caught on every plane.
JSON: MEDIA_ATLAS_B2_DEVICE_PROBE_NOTE9_20261017.json.

## Not done here

Eviction (B3), the look atlas (B4), any default-on, any device run.

## Merge of origin/joy-creator (Proto 2 pencil, Proto Pencil, brush v11) — October 17

Merged 984485eac into this branch (a107de9ed), as a one-time exception to the never-resolve rule granted by the owner for
this merge only. Three conflicts, each keeping both sides:

- `jb_dry_dab.frag`: the exact cell-centre stick frame calls Proto 2's
  `jb_stickSqueeze(q, …, u_stickBlend, u_stickShape, u_zoneGrad)`.
- `MediaLayerEngine.kt`: B1's per-slot dab draw (`dabs(batch) {…}`, which does the upload) with Proto 2's
  `contactLut(mat.lutPress)`.
- `MediaWindow.kt`: `restored()` keeps B2's `override`; the body is the same on both sides.

Audit: all 184 files only one side changed equal that side; on the 9 files both changed, every line either side added
is present except the 2 combined lines above. The tilt fix (lastSlope, waterStoppedByUndo, monotone WetSpread)
is intact: MediaWindowMath, its test and lab engine.js equal the cherry-pick e7888abef.

**Deliberate follow-up (c07fe316e): the dry footprint ends at its box in the shader.** Proto 2 / Proto Pencil's
footprint is exp() bells that never reach 0, so the quad edge (box + 0.06 mm) decided where the tail stopped, and that
depends on rasterisation: the B2 harness saw 2–4 placement-dependent pixels per Proto stroke (about 3% of the crush
there). The fragment now discards outside (xMin, xMax, ±yMax) in the stick frame. Measured against the merge: three-zone
sticks, wet, eraser, oil and knife are bit-identical; Proto strokes move only the paper-crush plane (max 0.0017) and the
look there (max 71/255 at isolated tooth specks); pigment is unchanged. Lab sheets, the proto sheet drawn with Proto
Pencil and the scribble sheet with Proto 2 (Iris Xe): mean per-channel delta 0.019 / 0.149, p99 1 / 2, max 194 / 106,
mean brightness 237.77 → 237.77 / 181.74 → 181.71; noise floor mean 0.000. Side by side they look the same.

Harnesses now include a Proto 2 point stroke and a Proto Pencil tilted stroke, with the v11 stick uniforms set for every
dry stroke. Results on c07fe316e:

- B2 free placement, full: PASS. orderA and orderB are 0 on all 9 strokes (Proto 2 and Proto Pencil included);
  the noRing control was caught 76,133 times. JSON: MEDIA_ATLAS_B2_FREE_MERGED_20261017.json.
- B1 window parity, full: PASS. Atlas vs window 0 of 257,949,696 compared; the broken table was caught 24,922,054 times.
  JSON: MEDIA_ATLAS_B2_WINDOW_PARITY_MERGED_20261017.json.
- `node joybrush/tools/media_golden.mjs`: golden.json regenerates byte-identical (blob 0c67b5a39, the same as joy-creator).
- Full `:core:jvmTest` + `:androidkit:test` under the lock: 311 classes, 3396 tests, 0 failed, MediaGoldenTest 9/9.
  On c07fe316e the androidkit tests were up-to-date: the shader is not one of their inputs, so their results are
  the merge run's.

