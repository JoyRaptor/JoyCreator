# JB-9.03b — Paper follow-ups from the audit: exact zero slope, twins that really match, a safe upload, no dead knobs

| | |
|---|---|
| **Tier** | T1 |
| **Status** | 🟦 Ready (paper specialist, 2026-10-01, after the adversarial audit of JB-9.01/9.02/9.03/9.09) |
| **Builder** | Codex (one owner for every twin, so the CPU, the GPU and pack.py change in ONE commit) |
| **Depends on** | JB-9.01, 9.02, 9.03, 9.09 (all landed) |
| **Owner area** | `core/paper/SurfaceMaps.kt`, `core/paper/HexTile.kt`, `core/paper/PaperTexture.kt` + their tests, `ImportedTextureTest` (encoding literals only); `joybrush/tools/paper/pack.py`; `joybrush/assets/paper/surface_pulp_artisan.png` (regenerated) + `catalogue.json` (slopeRange only if it changes); `joybrush/shaders/jb_paper.glsl`, `jb_grain_sample.glsl`, `jb_tuft.frag` (paper lines only); `androidkit/.../gl/GrainTextures.kt`; `core/grain/GrainMath.kt`; `core/brush/TuftStroke.kt`; `core/brush/BrushPreset.kt` (KDoc only); `core/chrome/BrushKnobs.kt`; `joybrush/brushes/pencil/brush.json`; tests that pin these (`PencilOnAFingerTest`, `GrainWiringTest`, `BrushKnobsTest`/`BrushTuningTest`, `TuftTuningTest`) |
| **Estimated size** | ~200 lines changed + tests |

Hot-file rule as in JB-9.03 (log check, rebase, one line under "Questions for the Lead" in `LEAD_DESK.md`; **append, never edit
the file's instructions**, which a previous write corrupted).

## The fixes (each is a Decision; no open choices)

1. **Slope encoding: exact zero.** 256 levels cannot hold ±r symmetrically with an exact 0, and today 0 decodes as +r/255.
   New contract (every twin):
   `encodeSlope(s, r) = round(127 + 127 · clamp(s / r, −1, 1))` → 0..254 (255 is never written);
   `decodeSlope(b, r) = (b − 127) / 127 · r`.
   GLSL decodes the FILTERED value: `slope = (v · 255.0 − 127.0) / 127.0 · r`. Update pack.py, SurfaceMaps, HexTile and jb_paper.glsl
   together, and regenerate `surface_pulp_artisan.png` with pack.py (same height source, `out/pulp_artisan_512_h.png` is not in git:
   take the B channel of the shipped PNG as the height. B is unchanged by this fix).
2. **SurfaceMaps in Double.** Compute slopes in Double, matching pack.py's float64, then assert **0** differing bytes against the regenerated asset
   (not "±1"). If a few still differ, report the count and why; do not loosen the test.
3. **Pin dy.** Add the transposed sine-ramp test (`h = 0.5 + 0.5·sin(2πy/64)`): dy equals the central difference, and dx = 0. Mutation check: `10 → 2` in the
   **dy** kernel goes red.
4. **CPU twin decodes like the GPU.** `HexTile` decodes slopes from the bilinear-filtered float, with no `byteOf` and no re-quantising. That
   makes CPU and GPU the same order of operations (decode is linear, so filter-then-decode is exact).
5. **Double lattice.** `HexTile.lattice` keeps `a, b, fa, fb` and the `fa + fb > 1` test in Double; `PaperTexture.bilinear` weights in Double.
6. **Seed offset for octaves.** `sampleSurface` and `sampleLook` gain `seed: Int = 0`, added to every hash's k: `hash(i, j, k + seed)`.
   Same in `jb_paperSurface(docPx, seed)`. JB-9.06's detail octave uses `seed = 10`.
7. **Height-only read.** `jb_paperHeight(docPx)` does the lattice, offsets and rotation of the three reads, but skips slope decoding and
   back-rotation. `jb_paperGrainHeight` uses it. (The slope path stays for JB-9.08 and the screen pass.)
8. **`precision highp float;`** at the top of `jb_paper.glsl` too, not only `highp int`.
9. **A safe, testable upload.** Data textures (surfaces) never go through `GLUtils.texImage2D`. Decode with `inPremultiplied = false`,
   read `getPixels` (documented un-premultiplied for such a bitmap), pack to RGBA bytes in a pure function
   (`GrainTextures.rgbaBytes(argb: IntArray): ByteArray`, unit-tested on a pixel with A = 0 and R,G,B ≠ 0 → bytes unchanged), then
   `glTexImage2D(GL_RGBA8, …, ByteBuffer)`. Height² alpha is data, and a premultiplying path would turn every pit's slopes into −r.
10. **Folder by catalogue, not by name prefix.** `loadPackagedBitmap` takes the folder from the caller. Paper surfaces load from
    `paper/`; grain clouds from `grain/`. No `startsWith("surface_")`.
11. **No magic scale.** `GrainMath` passes the paper pitch as the surface's `texelPx` (a named constant from the catalogue entry,
    `DEFAULT_SURFACE_TEXEL_PX = 2f`), not as `scale = 32` pushed through `pitchPxFor`.
12. **Sable.** Delete the unused `PAPER_TOOTH_SCALE`. `paperPitchPx` is the surface `texelPx` only when the tooth or stray-hair stutter is in use
    (`tooth > 0` or stray hairs > 0), else 0, so a loaded Sable makes no paper reads it ignores.
13. **Pencil's file stops lying.** Remove `paperGrain.image` and `paperGrain.scale` from `brushes/pencil/brush.json`. `GrainSpec`'s KDoc says
    `paperGrain.image/scale` are ignored since JB-9.03: the DOCUMENT's paper decides (R10 P4). The tip texture still uses its own.
14. **No dead sliders (owner rule: finish everything shown).** Hide the three JB-9.09 knobs (`paper.influence`, `paper.directional`,
    `paper.wet`) from `BrushKnobs.forBrush` until JB-9.08 lands, with one boolean `PAPER_ENGINE_LIVE = false` that JB-9.08 flips. Remove the
    "(takes effect when …)" tail. The brush-file values stay.
15. **PencilOnAFingerTest** reads paper height through `HexTile.sampleSurface` (the TODO(JB-9.02) goes).

## Tests
Everything above that says "test". All JB-9.01/9.02/9.11 tests updated to the new encoding (encodeSlope(0) = 127, (r) = 254, (−r) = 0); `ImportedTextureTest`'s flat-board check ("R = G = 128") becomes `encodeSlope(0f, r)`, not a literal.
`shader_check.js`: the no-repeat check still < 0.3 vs control ≈ 1. A new check: a flat surface (all slope bytes 127) decodes to exactly 0
slope on the GPU.

**Gradle rule (memory is tight, ~1 GB free):** take the build lock first (see `PAPER_DISPATCH.md`), run with `--no-daemon`, release it after.

## Definition of done
- [ ] tests pass (paste counts read from the XML of THIS worktree, with the time stamp of the newest file) · [ ] mutation: dy kernel → red
- [ ] LEAD_DESK line appended · [ ] commit "JB-9.03b: …", rebased, pushed · [ ] main folder fast-forwarded (or refusal reported)

## Questions
