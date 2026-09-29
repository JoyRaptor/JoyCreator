# Adversarial review — JB-1.01 Tip shape maths (`jb_tip.glsl` + `TipMath`)

- Reviewer: muse-spark (cross-reviewer, different family from builder Claude).
- Task status: 🟧 Built. Commit reviewed: `a44c91d0` (shared shaders) + `41102518` (CPU twin `TipMath.kt` + one-line shader touch).
- Spec: none in `tasks/joybrush/specs/` (T1 pre-spec work; contract = the header comments in `jb_tip.glsl:1-13` + `TipMath.kt:1-18` "keep the two in step, same commit").
- §5b checks: shader file compiled for Android API + verified via WebGL2 per board/`tools/shader_check.js` (not re-run here — no browser/phone in this session; GLSL ES 3.00-only constructs inspected statically). CPU twin proven by `PaintTest.tipCoverageShape` (9/9 PaintTest green, 2026-09-28 run). Owner area (`joybrush/shaders/jb_tip.glsl`, `core/paint/TipMath.kt`) clean; the `41102518` touch to the shader is inside the task.
- Severity scale: High = corrupt tiles; Medium = wrong paint; Low = silent reshape; Info = verified-good.

## Finding 1 (Low): NaN in → NaN coverage out, with no guard at either layer (upstream's bug, this task's blast radius)
Proof: `jb_tip.glsl:27-32` — `q / vec2(hx,hy)` with NaN offset (or NaN angle feeding `cos/sin`, `:37`) yields NaN `d`; `max(fwidth(NaN),1e-4)` stays NaN under GLSL `max` semantics (`NaN < x` is false → returns NaN), so `cov` is NaN and `o_color` in `jb_dab.frag:29-30` carries NaN into the stroke buffer, where `(ONE, ONE_MINUS_SRC_ALPHA)` blending spreads it to the tile. CPU twin identical: `TipMath.coverage` (`TipMath.kt:29-37`) propagates NaN the same way (`cov <= 0f` is false for NaN in `RefCanvas.stamp:73`, so the dab is *not* skipped). Reachability today requires a poisoned upstream (NaN angle from `DirectionTracker`'s Inf-tilt case — see JB-0.01 Finding 4 — or NaN coords from `screenPerDoc = 0` — JB-0.01 Finding 1). This task correctly mirrors; the fix belongs upstream. Recording here so the NaN chain has its blast radius attached.

## Finding 2 (Low): `minPx ≤ 0` and out-of-range shape params silently become something else (both layers agree)
Proof: `jb_tip.glsl:47` `halfMin = max(t.minPx, 0.5) * 0.5` and `TipMath.kt:51` identical — negative `minPx` (unranged in `BrushValidate`, JB-0.03 Q3) becomes a 0.25 px floor, never an error. Likewise `corner`/`taper`/`aspect`/`hardness` are clamped identically both sides (`jb_tip.glsl:31,41,54,60`; `TipMath.kt:24,47-48,62,34`). Parity is perfect — which is the point: a typo'd brush renders *something sane* instead of failing, and nothing tells the user. Acceptable rendering behaviour; the validation ruling is JB-0.03's to make.

## Verified good (with proof)
- CPU↔GPU parity on every branch I traced: aspect→half-extents (`jb_tip.glsl:41-43` vs `TipMath.kt:47-55`), razor fade-then-clamp order (`:45-50` vs `:52-55`, incl. the 0.25 floor), taper-along (`:53-54` vs `:61-62`), superellipse exponent clamp (`:30` vs `:23`), hardness clamp + AA band (`:59-61` vs `:33-36`). Razor-at-±1 stays a fading hairline both sides (test `PaintTest.kt:46-48` pins CPU `0.2 < c < 0.8`).
- Far-field safety: huge `u` with exponent ≤ 64 overflows to `+Inf`, and `smoothstep(inner, 1, Inf) = 1` → coverage 0, never NaN (`pow` of non-negative base only, `abs` at `:28`). No division by zero: `hx,hy ≥ 0.25` post-floor.
- `jb_dab.vert:22` extent duplicates `TipMath.extent` constants exactly (`1.4143 + 2.0`).

## Recommendation
No send-back. The twin implementation is the round's cleanest parity story. Findings are upstream-owned (Finding 1) and validation-owned (Finding 2); nothing in this task's own maths is wrong.
