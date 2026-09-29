# Adversarial review — JB-1.02 Grain maths (`jb_grain.glsl`)

- Reviewer: muse-spark (cross-reviewer, different family from builder Claude).
- Task status: 🟧 Built. Commit reviewed: `a44c91d0` (shared shader).
- Spec: none in `tasks/joybrush/specs/` (T1 pre-spec work; contract = the header comment `jb_grain.glsl:1-16`).
- §5b checks: owner area (`joybrush/shaders/jb_grain.glsl`) clean — nothing else in either Built diff touches it. Verified by inclusion-graph: `jb_dab.frag` includes only `jb_tip.glsl`; `jb_commit.frag` includes neither; so grain is currently *unwired* (no CPU or GPU caller) — the finding below is therefore latent, not live. No dedicated test suite exists for this file (nothing to run; the WebGL2 syntax check in `tools/shader_check.js` was inspected, not executed — no browser in this session).
- Severity scale: Medium = will corrupt paint when wired as specified; Low = edge; Info = verified-good.

## Finding 1 (Medium, latent): `jb_grainLevel` NaN-poisons the common finger case — whoever wires it must guard NaN lean/tilt first
Proof:
1. `jb_grain.glsl:41` — `plane = tiltGradient * tiltAmount * dot(localN, leanDir)`. IEEE: `0 * NaN = NaN`. A finger stroke reports `tilt = NaN`, `azimuth = NaN` by contract (`PenSample.kt:32-42`; `MotionEventSamples` emits NaN for fingers, `:43-47`). Any NaN among `tiltAmount` (= `sin(NaN)`), `leanDir` (from NaN azimuth), or their dot makes `plane` NaN *regardless* of the other factors.
2. `jb_grain.glsl:43` — `level = clamp(depth * tipCov + NaN - dome, …) = NaN` (GLSL `min/max` propagate NaN: `NaN < x` is false).
3. `jb_heightCoverage(NaN…)` (`:22-26`) → NaN, and `jb_grainedCoverage` (`:48-50`) → NaN → NaN alpha into the stroke buffer (same spread mechanics as JB-1.01 Finding 1).
4. The shipped `pencil` brush sets `tiltGradient: 0.6` (`joybrush/brushes/pencil/brush.json:25`) and is a *finger-plausible* brush — so the first wiring that passes raw pen channels into these functions breaks the pencil-on-finger path completely (every dab NaN) while pen paths look fine. A test that only strokes with a full pen would never catch it.
Fix direction (for the wiring spec, JB-1.04/brush-lab, not this task): define the NaN contract now — e.g. `plane = 0 when tiltAmount or leanDir is NaN` (equivalently: tilt-absent pens get no tilt gradient), and pin it with a CPU-side `GrainMath` twin test using `tilt = NaN, azimuth = NaN` (the `StrokeSmootherTest.missingChannelsStayMissing` pattern). Not a send-back: the maths is exactly the specified Photoshop/Krita Height behaviour for the *defined* inputs; what's missing is the NaN row in the contract.

## Verified good (with proof, for defined inputs)
- `jb_heightCoverage`: `edge` floored at `1e-3` (`:23`) so crisp-clump mode can't divide by zero; `level` 0/1 endpoints behave (nothing takes / everything takes); `+Inf depth` (the JB-0.03 `1e999` case) degrades to solid paint (`threshold = -Inf`, coverage clamps to 1) rather than NaN — fail-visible, not corrupt.
- `jb_grainLevel` dome/plane signs match the documented picture (lean-side takes first; `radial` drops the rim); `jb_grainedCoverage` correctly bounds grain by the antialiased tip edge so dabs can't leak past their shape (`:46-50`).
- Coordinate-space discipline documented in the header (`:12-16`, tip = dab space, paper = canvas space, sampling is the caller's job) — the exact trap JB-1.20's lab must respect.

## Recommendation
No send-back — as specified, for specified inputs, the maths is right, and it is unwired so nothing is live. Recommend the Lead append the NaN row (Finding 1) to the wiring spec (JB-1.04 or the brush-lab follow-up) before anyone connects pen channels to these functions; that is where this detonates.
