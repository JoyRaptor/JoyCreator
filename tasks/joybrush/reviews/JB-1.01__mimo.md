# Adversarial review — JB-1.01 tip shape maths (`jb_tip.glsl` + `TipMath`)

- Reviewer: mimo (second adversarial pass; muse-spark filed `JB-1.01__muse-spark.md`).
- Task status: 🟧 Built. Commits reviewed: `a44c91d0` (shader) + `41102518` (CPU twin), tree at `b74aaf0e`.
- Spec: none (T1 pre-spec) — contract = `jb_tip.glsl:1-13` header + `TipMath.kt:1-18` ("keep the two in step, same commit").
- Suite: `./gradlew -p joybrush :core:jvmTest --rerun-tasks` at `b74aaf0e` → BUILD SUCCESSFUL, 280 tests / 0 failures (`PaintTest.tipCoverageShape` green — pins the CPU twin only; GL still statically reviewed, no phone/emulator in session, as the board itself discloses).
- §5b checks: owner area (`shaders/jb_tip.glsl`, `core/paint/TipMath.kt`) only. Severity: BLOCKER / MAJOR / MINOR.

**Verdict: no BLOCKER, no MAJOR. Both muse findings confirmed with independent line-level
derivations; F2's validation half has since been closed by JB-0.03b. My addition: the CPU↔GPU
parity contract is undefined (not merely untested) for NaN inputs, and the finite-input AA
difference has no pinned tolerance — both are handover notes for the first phone milestone.**

## Status of muse-spark's findings at this commit (both shaders read in full this pass)

### F1 (muse Low → **confirmed**): NaN in → NaN coverage out, no guard at either layer — upstream's bug, this task's blast radius

- **Independent CPU derivation (I re-traced every step in current source):** with `t.angle = NaN`
  (or NaN offsets): `cos/sin(NaN)` = NaN (`TipMath.kt:41-42`) → `q` NaN (`:59-60`) → `d` NaN
  (`:63`, `superellipse` `:21-26` — `abs(NaN)`/`pow` propagate) → `aa` = `max(NaN, 1e-4f)` at
  `:33`. Here the CPU *swallows* NaN into `aa = 1e-4` (Kotlin's `kotlin.math.max` is
  `if (a >= b) a else b`, so NaN compares false and `b` is returned) — but that does not save it:
  `inner` stays finite (`:34`) while `smoothstep(inner, 1 + 0.5·aa, d = NaN)` (`:70-74`):
  `e1 > e0` → `t = NaN` → `coerceIn(0,1)` returns NaN (NaN fails both comparisons) → `cov = 1 - NaN`
  = NaN (`:35`) → `cov * g.fade` = NaN (`:36`, `fade` is finite: the `max(rawX, 0.25f)` floor at
  `:53` swallowed the NaN radius). Then `RefCanvas.stamp`: `cov <= 0f` is **false for NaN**
  (`RefCanvas.kt:74`) → `buf[i] = … NaN …` (`:76`) → a NaN pixel lands in the stroke buffer and
  spreads through premultiplied blending at commit — exactly muse's chain, now proven from the
  current code rather than asserted.
- **GPU side:** same structure (`jb_tip.glsl:37` cos/sin → `:38` q → `:56` d → `:59` aa →
  `:61` cov), but with a sharper caveat than muse wrote — see my F3 below: GLSL leaves NaN
  `min/max/clamp/smoothstep` **undefined**, so the GPU's answer is not "also NaN" so much as
  "not guaranteed".
- **Reachability at HEAD (better than when muse looked):** the pen path is now clamped at the
  placer — `DabPlacer.emit` forces radius/angle/flow/cap finite (`DabPlacer.kt:78-81`). What is
  still ungated: dab **x/y** (not checked, `:79`) and any direct `TipMath`/`RefCanvas` consumer.
  The live ingress is a corrupt/hostile stroke recording replaying NaN coordinates — filed as my
  **M1 in `JB-0.01__mimo.md`** (silent truncation + this NaN-pixel path), remedy = refuse
  non-finite floats at decode/smooth, before JB-0.08 wires replay. This task correctly mirrors;
  nothing to fix inside the twin.

### F2 (muse Low → **confirmed; validation half since FIXED, rendering half unchanged by design**): shape params silently floor rather than error

- **Confirmed as rendering behaviour:** both layers floor `minPx` identically —
  `halfMin = max(t.minPx, 0.5) * 0.5` (`jb_tip.glsl:47` == `TipMath.kt:51`) and fade by the ratio
  (`:48` == `:53`); `corner` clamped to 0.5..64 (`:30` == `:24`), `taper`/`aspect`/`hardness`
  clamped identically (`jb_tip.glsl:41,54,60` == `TipMath.kt:48,62,34`). Parity is perfect — the
  point muse made: a typo'd brush renders something sane instead of failing.
- **Changed since muse reviewed:** `tip.minPx` is no longer unranged — JB-0.03b's ruling table
  ranges it `0.25..16` and `BrushValidate` enforces it (rule at `BrushValidate.kt:83` region;
  `everyRangedNumberIsChecked` pins the table), so a file with `minPx = -5` is now *refused at
  load* rather than floored at render. The remaining unvalidated params (`corner`, `taper`,
  `aspect`, `hardness` — finite-only, JB-0.03b Q3's list) still rely on the shared clamps, which
  is the intended division of labour: **validate what files can abuse, clamp what renderers must
  survive.** Nothing further for this task.

## My findings

### F3 (MINOR — contract gap for the next reviewer, not a wrong number): the "keep the two in step" parity promise is *undefined*, not just untested, for NaN inputs — and the one deliberate difference has no pinned tolerance

- **Proof (contract vs reality):** `TipMath.kt:11-18` promises the twin mirrors the shader
  "in the same commit". For NaN inputs the two languages disagree *by specification*:
  - CPU: `kotlin.math.max(a, b)` = `if (a >= b) a else b` — **defined** return of the other
    operand when one is NaN (my F1 derivation shows `aa` swallowed at `TipMath.kt:33`, `fade` at
    `:53`, `hx/hy` at `:54-55`), while `coerceIn`/`smoothstep` in the same file **propagate**
    (NaN fails both comparisons) — so the CPU's final answer for `angle=NaN` is a definite NaN pixel;
  - GPU: `max`/`min`/`clamp`/`smoothstep` with NaN operands are **undefined in GLSL**
    (the spec's min/max family is documented as undefined if an operand is NaN; `smoothstep` is
    undefined for out-of-domain edges) — the same shader may emit NaN, 0, or an opaque dab
    depending on driver.
  So for NaN the CPU and GPU *cannot* be claimed to agree, no test can pin them without a device,
  and there is currently no test at all (`PaintTest.tipCoverageShape` `:46-48` exercises finite
  CPU values only).
- **The finite-input difference too:** `aa` uses `fwidth(d)` on GPU (`jb_tip.glsl:59`) vs an
  analytic forward-difference on CPU (`TipMath.kt:33`, disclosed in `TipMath.kt:15-17` as
  "matches it closely at the rim"). "Closely" has no pinned tolerance anywhere — a device
  screenshot comparison at the first phone milestone (per the board's own "not yet run on a
  phone" note) should bound it, ideally as a shader_check.js extension.
- **Not a send-back:** both inputs are the *same bug upstream* (F1/M1 of JB-0.01), and NaN parity
  only matters if NaN is allowed in — which is the ingress guard. Recorded so the first phone
  pass knows what to look at.

## Verified sound (branch-by-branch parity walk, fresh line pairs)

- Superellipse shell: exponent clamp `clamp(n, 0.5, 64)` (`jb_tip.glsl:30` == `TipMath.kt:24`),
  `abs(q/h)` (`:28` == `:22`), `pow(Σ, 1/e)` (`:31` == `:25`).
- Rotation frame (`:37-38` == `:41-42,:59-60`); aspect→half-extents
  (`:41-43` == `:48-50,:54-55` incl. the quarter-pixel razor floor and fade ratio, same order
  of operations both sides).
- Taper `widthScale` floor `1e-3` (`:54` == `:62`); taper along axis (`:53` == `:61`).
- AA band + hardness: `inner = min(clamp(hardness), 1-aa)` and `cov = 1 - smoothstep(inner, 1+0.5·aa, d)`
  (`:60-61` == `:34-35`).
- Far-field safety (muse's claim, re-derived): huge finite `d` overflows `pow` to `+Inf`, and
  `smoothstep` with `x=+Inf`: `t = +Inf` → `coerceIn/clamp` → `1` (defined for Inf both sides) →
  `cov = 0` → skipped by `RefCanvas.stamp:74` — **0, never NaN**; no division by zero
  (`hx,hy ≥ halfMin > 0` post-floor, `TipMath.kt:54-55`).
- Extent constant identical: `jb_dab.vert:22` `a_dab.z * 1.4143 + 2.0` (grep-verified) ==
  `TipMath.extent` (`TipMath.kt:68`).
- Razor-at-±1 stays a fading hairline both sides (fade ratio, tests pin CPU `0.2 < c < 0.8`).
- The only consumer-side NaN story is upstream: placer clamps angle/radius/flow/cap
  (`DabPlacer.kt:78-81`); x/y still ungated → tracked in `JB-0.01__mimo.md` M1.

## Bottom line

No send-back — the twin is exact where it can be (finite inputs, every branch I traced) and the
two findings are upstream/validation-owned as muse said. F3 adds the missing words to the parity
contract for the first device run: *NaN is undefined on GPU, Inf is fine, AA needs a tolerance.*
