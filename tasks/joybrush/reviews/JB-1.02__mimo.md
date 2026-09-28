# Adversarial review — JB-1.02 grain maths (`jb_grain.glsl`)

- Reviewer: mimo (second adversarial pass; muse-spark filed `JB-1.02__muse-spark.md`).
- Task status: 🟧 Built. Commit reviewed: `a44c91d0` (tree at `b74aaf0e`).
- Spec: none (T1 pre-spec) — contract = header comment `jb_grain.glsl:1-16`.
- Suite: no test suite exists for this file (nothing to run — it is **unwired**: grep over `shaders/` shows `#include` appears only in `jb_dab.frag` → `jb_tip.glsl`; nothing includes `jb_grain.glsl`, and no Kotlin calls its three functions). WebGL2 syntax check in `tools/shader_check.js` inspected, not executed (no browser), same as muse.
- §5b checks: owner area (`shaders/jb_grain.glsl`) only; clean. Severity: BLOCKER / MAJOR / MINOR.

**Verdict: no BLOCKER, no MAJOR — but one MAJOR-shaped latent finding that must be ruled before
this file is wired (muse's F1, confirmed with fresh evidence: the finger+pencil case is live-by-default),
and one MINOR where I disagree with muse's "verified good" list: the header's `level 0 = nothing
takes paint` endpoint does not actually close, and cloud grain guarantees the exact input that
proves it.**

## Status of muse-spark's findings at this commit (shader read in full this pass)

### F1 (muse Medium-latent → **confirmed MAJOR-if-wired**): `jb_grainLevel` NaN-poisons the common finger case; the NaN row must be written into the wiring spec

- Re-verified in current source, step by step:
  1. `plane = tiltGradient * tiltAmount * dot(localN, leanDir)` (`jb_grain.glsl:41`) — IEEE
     `0 * NaN = NaN`, so **any** NaN among `tiltAmount` (= `sin(tilt)`), `leanDir` (from azimuth),
     or their dot poisons `plane` regardless of the other factors. I confirmed the NaN channel
     contract that feeds it: `PenSample` declares "channels a device cannot measure are NaN,
     never a made-up value" (`PenSample.kt:14-15`) with `tilt/azimuth` defaulting to NaN
     (`:33-35`), and the input layer emits exactly that for fingers —
     `MotionEventSamples.kt:44,47` (`tilt = … else Float.NaN`, `azimuth = … else Float.NaN`;
     same for the non-historical path `:58-61`), pressure 1 (`:43,:57`).
  2. `level = clamp(depth * tipCov + plane - dome, 0, 1)` (`:43`) with `plane = NaN` → NaN
     (GLSL `clamp`/`min`/`max` NaN behaviour undefined — see my note below).
  3. `jb_heightCoverage(NaN, …)` (`:22-26`) → NaN; `jb_grainedCoverage` (`:48-50`) → NaN → NaN
     alpha into the stroke buffer (spread mechanics as in JB-1.01 F1).
- **The default-breaking pair is real and shipped today:** `joybrush/brushes/pencil/brush.json:25`
  sets `"tiltGradient": 0.6` (grep-verified) — so the *first* wiring that passes raw pen channels
  into `jb_grainLevel` breaks **pencil-on-finger** (every dab NaN) while pen strokes look fine.
  A test stroked only with a full pen would never catch it.
- **Mitigation already in place (better than when muse looked):** `DabPlacer` now forces the
  dab's `angle` finite (`DabPlacer.kt:79`) — but grain does not consume `angle`; it consumes the
  raw `tilt`/`azimuth` **channels**, which nothing clamps. So muse's fix direction stands and
  should be mandatory in the wiring spec (JB-1.04 / brush lab): define the NaN row explicitly —
  e.g. `tiltAmount = 0` and `leanDir = (0,0)` (or a fixed unit fallback) when either channel is
  NaN, equivalently "a tilt-absent pen gets no tilt gradient" — and pin it with a CPU-side
  `GrainMath` twin test using `tilt = NaN, azimuth = NaN` (the
  `StrokeSmootherTest.missingChannelsStayMissing` pattern). Until that row exists, **do not
  include `jb_grain.glsl` from `jb_dab.frag`.**
- Additional note for the rule: GLSL leaves NaN `clamp/min/max` undefined (my JB-1.01 F3) — so
  the wiring must not rely on "the shader will happen to launder NaN"; the guard has to be in the
  values handed to the shader.

## My findings

### F2 (MINOR — I disagree with muse's "verified good": the `level 0` endpoint does not close; cloud grain guarantees the input that shows it): header says "0 = nothing takes paint", the maths leaves a half-edge-band open

- **Proof (spec/comment):** the parameter doc states `level : 0 = nothing takes paint,
  1 = everything takes paint` (`jb_grain.glsl:19-20`).
- **Proof (maths):** `jb_heightCoverage` returns `clamp((height - threshold) / w + 0.5, 0, 1)`
  with `threshold = 1 - level` (`:24-25`) and `w = max(edge, 1e-3)` (`:23`). At `level = 0`:
  `threshold = 1`, so for the brightest possible texel `height = 1` the result is
  `(1-1)/w + 0.5 = 0.5` — **50% coverage at "nothing takes paint"**. The `+0.5` half-band overhangs
  the top of the `[0,1]` height domain, and there is no clamp room above `height = 1` to absorb it.
- **Proof that `height = 1` is guaranteed, not hypothetical:** JB-1.03 normalises every cloud
  field so **max == 1 exactly** (its contract, pinned by `heightsRunFromZeroToOne`) — the
  brightest texel exists in every shipped grain asset. So with `depth → 0` (a feather-light
  touch through a zeroed pressure curve) and no tilt/radial contribution, `level → 0` and the
  texture's brightest points print at up to 50% alpha through `jb_grainedCoverage`
  (`:48-50`, whose `smoothstep(0, 0.06, tipCov)` passes full-opacity centres untouched).
- **Severity:** MINOR — a contract-comment mismatch with an arguable artistic reading (speckle at
  zero depth could be *wanted* dry-brush behaviour), reachable only once wired (file unwired
  today). It belongs with the same Lead ruling as JB-1.03's F1 and JB-5.10's two items: **one
  sentence to settle** — either "0 closes within one edge band; texels above the threshold's
  reach may still take up to `0.5 · edge`" (accept), or change `+0.5` semantics at the wiring
  commit and re-verify both endpoints. Flagging now because muse listed this endpoint as
  "behaves" — it does not, and the cloud generator makes it deterministic.

## Verified sound (re-derived independently, for defined inputs)

- **`jb_heightCoverage` hardening:** `w` floored at `1e-3` (`:23`) so crisp-clump mode (`edge → 0`)
  cannot divide by zero; `level = 1` → threshold 0 → every texel ≥ 0.5 coverage ("everything
  takes" holds); `+Inf depth` (JB-0.03's `1e999` case) → `clamp(+Inf) = 1` (defined for Inf) →
  level 1 → near-solid paint, fail-visible rather than NaN — as muse said, with the correction
  that level 1's floor is 0.5, not "solid everywhere" (see F2's arithmetic; the deep pits sit at
  0.5, the rest at 1).
- **Signs match the documented picture:** `plane` positive on the lean side
  (`tiltGradient > 0` → `dot(localN, leanDir) > 0` → level higher → "the side the pen leans
  towards takes paint first", `:36-37`); `dome = radial · dot(localN, localN)` subtracts, so the
  rim takes less ("radial drops the rim", `:42`).
- **Bounding claim:** `jb_grainedCoverage` multiplies by `smoothstep(0, 0.06, tipCov)`
  (`:49`), so grain can never leak past the tip's antialiased edge — the invariant holds for all
  finite inputs (and fails only under F1's NaN, which is the wiring gate).
- **Coordinate-space discipline** documented in the header (`:12-16`: tip texture in dab space,
  paper grain in canvas space, "sampling is the caller's job") — the exact trap JB-1.20's lab
  must respect; nothing in the file violates it (it samples nothing).
- **Unwired status re-verified this pass:** grep of `shaders/` for `#include` finds only
  `jb_dab.frag:11 → jb_tip.glsl`; the three `jb_grain*` functions have no callers — F1 is
  genuinely latent, as muse said.

## Bottom line

No send-back (nothing in this file can fail a test — there are none, by design, while unwired).
**Gate for the wiring spec (JB-1.04/brush lab): the NaN row from F1 is mandatory, with the
finger+pencil case as its first test;** F2 goes to the Lead as a one-sentence contract ruling
at the same time, together with JB-1.03's normalisation sentence — the two sentences define what
"0..1 height, 0..1 level" actually means end to end.
