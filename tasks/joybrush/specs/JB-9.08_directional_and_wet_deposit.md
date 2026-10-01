# JB-9.08 — Paint that knows which way it came from: directional dry deposit, wet pooling

| | |
|---|---|
| **Tier** | T1 + T3 phone check |
| **Status** | 🟦 Ready (paper specialist, 2026-10-01) |
| **Builder** | Codex |
| **Depends on** | JB-9.03 (surface + `jb_paper.glsl`), JB-9.09 (brush fields `paper.directional`, `paper.wet`, `paper.influence`). If 9.09 has not landed, build it first from its spec, in its own commit |
| **Owner area** | EDIT `joybrush/shaders/jb_grain.glsl` (new function only), `jb_dab.frag`, `jb_dab.vert`, `jb_tuft.frag`; EDIT `GlPaintEngine.kt` (per-dab travel direction in the instance record); EDIT `core/grain/GrainMath.kt` (CPU twin); EDIT `core/paint/*` where the dab instance is built (`BrushDabber`, `Dab`); tests. **Hot files: JB-9.03's rule.** |
| **Estimated size** | ~250 lines + ~200 lines of tests |

## Goal
Owner P3: "raised points receive ink FROM the direction that paint is applied … dry paint scraped right to left: the right side
of the raised edges collects more paint, the left side remains clean. Dry paint only does the surface, and water paint pools in
the cracks." Proven on the PC (`research/R10_img/dir_test.jpg`). Research: Rudolf, Mould & Neufeld 2005 (crayon: slopes rising
into the stroke catch more wax); Murakami, Tsuruno & Genda 2005/06 (pastel deposit = paper lit from the stroke's direction).
No patent found on this (R10 §0).

## Contract (verbatim)
```glsl
// jb_grain.glsl (NEW function; jb_heightCoverage, jb_grainLevel unchanged)
// surf = jb_paperSurface(docPx): xy = slope per doc px, z = height, w = height²
// v    = unit travel direction of the stroke at this dab, doc space; (0,0) when unknown (finger tap, dwell)
// slopeRangeDocPx = u_paperSlopeRange / (u_paperTexelPx · scale)
// hCoarse = height from a coarser mip of the same hex read (textureGrad derivatives × 8)
float jb_paperEffectiveHeight(vec4 surf, float hCoarse, vec2 v, float slopeRangeDocPx,
                              float directional, float wet) {
    float face = clamp(dot(surf.xy, v) / max(slopeRangeDocPx, 1e-6), -1.0, 1.0);  // > 0: faces the oncoming brush
    float hDry = surf.z + JB_DIR_GAIN * directional * face;
    float hWet = surf.z + JB_WET_GAIN * (hCoarse - surf.z);                        // valleys rise toward their surroundings
    return mix(hDry, hWet, wet);
}
const float JB_DIR_GAIN = 0.35;   // from the PC proof; the owner tunes
const float JB_WET_GAIN = 1.0;
```
In `jb_dab.frag`, the paper height fed to `jb_heightCoverage` becomes `jb_paperEffectiveHeight(...)`, and the paper's grain factor becomes
`mix(1.0, paperCoverage, influence · bite)` (influence: brush; bite: document, JB-9.05).
**Brushes with NO paper grain settings** (`paperGrain.enabled == false`) but `paper.influence > 0` use `depth = 0.85`, `edge = 0.3`,
`tiltGradient = 0`, `radial = 0` (constants `GrainMath.UNIVERSAL_*`). That is how "every brush respects the paper by default" (P4) works
without each brush file carrying grain settings.

**Sign check (write it into the test):** v = (−1, 0) (dragging right-to-left). Then face = −∂h/∂x / range, which is positive on faces that fall
toward +x, i.e. the RIGHT faces of bumps. They load first. ✔ P3.

## Decisions already made
1. **Per-dab travel direction.** Widen the dab instance record by one `vec2 travel` (the direction from the previous dab, unit; (0,0) for
   the first dab and for zero movement). This settles JB-6.03 Q1 in favour of the wider record. Measure upload cost on the Note 9 if installed.
   The tuft engine passes its own travel direction (it already has one in stroke space; convert to doc space).
2. **Wet uses the coarser mip** (×8 derivatives) as "the surroundings". That is cheaper than a blur and matches Painter's separate flow relief in spirit.
3. **Smudge/push never use this** (they move pixels, they do not deposit).
4. The CPU twin (`GrainMath.paperEffectiveHeight`) mirrors the GLSL line for line. `hCoarse` on the CPU = the mean of the B channel over the
   8×8 texel box around the point (documented tolerance vs the GPU mip: ±0.03).
5. Grain depth stays per stroke (R38). Only the direction is per dab.

## Tests
- CPU twin: a synthetic surface with one ridge running north–south, slopes ±r on its two faces. With v = (−1,0) and directional 1 the east face's
  effective height rises by 0.35 and the west face's falls by 0.35. v = (+1,0) is the mirror. v = (0,0) → no change.
- wet = 1: a pit (h below its 8×8 mean) gets a HIGHER effective height than with wet = 0.
- `influence = 0` → coverage identical to a brush with paper off (byte-exact on the R16F buffer in `shader_check.js`).
- `shader_check.js`: a dry horizontal stroke right-to-left and the same left-to-right on the surface produce different buffers. Their
  difference correlates POSITIVELY with −∂h/∂x for the right-to-left stroke (the right faces loaded).
- Existing pencil/Sable/smudge tests stay green; pinned numbers that change are updated with a reason.

## T3
Owner judges dry scraping in two directions, wet pooling, and that Ink with a small influence still reads as solid ink.

## Do not
Do not normalise slopes to unit normals before `face` (the range normalisation is the scale). Do not install. Do not change tip-texture behaviour.

## Definition of done
- [ ] tests pass (paste) · [ ] mutation: flip the sign of `face` → the ridge test goes red · [ ] LEAD_DESK line · [ ] pushed · [ ] ROADMAP → 🟧 Built — awaiting the Note 9

## Questions
