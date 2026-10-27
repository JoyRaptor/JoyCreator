# Adversarial review — JB-1.20 PC Brush Lab (one HTML file, shared shaders, pen)

- Reviewer: muse-spark (cross-reviewer, no browser and no vision — code-only; builder was an unrelated subagent — different family).
- Task status: 🟧 Built. Commit reviewed: `17a5f409` (code) + driver-verification fixes in the spec's Q10 (same owner area).
- Spec reviewed: `tasks/joybrush/specs/JB-1.20_pc_brush_lab.md` (decisions 1–9, verification, Do-not + Questions 1–10).
- §5b checks: diff touches only NEW `tools/brushlab/BrushLab.html` + `README.md` — inside the owner area; `joybrush/shaders/*` unmodified per "Do not"; no frameworks/build/npm. Verification available to me: `node --check` equivalent is the orchestrator's (parses); the Q10 headless-Edge run (screenshots, readPixels probe, clean console) is taken from the spec's evidence writeup — I verified the two fixes present in code and the probe logic by reading, not pixels (no browser, no vision here; the byte-exact `rgb(16,16,20)` probe + white-core-before distinction is the right evidence class and is kept).
- Severity scale per §5b: BLOCKER / MAJOR / MINOR.

## Finding 1 (MAJOR): `e.twist` (degrees) is used as radians — a twist-reporting pen spins the tip ~57× too far
Proof: `BrushLab.html:1088-1090` stores raw `e.twist`; `barrelFor` (`:1109-1113`) returns it unconverted while the fake-rotation path is converted (`V.fakeAngle * DEG`). PointerEvents `twist` is degrees 0–359 (like `tiltX/tiltY`; only `altitudeAngle/azimuthAngle` are rad). A 90° twist rotates ~90 rad (≈5156°) instead of 90°. Spec Decision 2 §33 says "in radians" — the spec repeats the unit error, so the code follows the spec literally into the bug. Fix is `barrel = e.twist * DEG` + one word in the spec. Blast radius is narrow (few pens report twist; the latch is "non-zero ever"), but when exercised the tip direction is garbage — and the lab's whole job is judging look.

## Finding 2 (MAJOR): a shaped `size.inputs` curve is destroyed on save, contradicting "nothing is lost"
Proof: load maps any non-empty `size.inputs` to the `linear` shortcut (`:447-448`); save overwrites with `linear | []` (`:399-400`). Every other curve survives via `setParam` preservation — but the size curve, the one curve the lab itself writes, does not round-trip. Loading a phone brush with a hard S-curve then saving silently flattens its size response (while drawing the flat version — file and picture agree with each other, both disagree with the loaded file). The spec's "written back untouched" sentence (`:117-118`) is false for exactly this field. Either preserve episodes unknown to the lab (parse-and-carry like the rest) or downgrade the sentence. Data loss on save — MAJOR, even in a tuning tool.

## Finding 3 (MINOR): loaded `id` is always overwritten — identity does not round-trip
Proof: `:389` `id = slug(name)` on every save; `:412` always adds `writtenBy`. A loaded custom `id` is replaced even when the user changed nothing identifying. The "lossless" lists (`README:119-121`, spec Q6) omit `id` — honestly scoped lists, but `id` is the preset's identity and the phone decodes it. Keep the slug for new brushes; preserve a loaded `id` when the name is unchanged.

## Finding 4 (MINOR): dab quad is diameter+4, spec says diameter+2 — budget meter understates fill
Proof: `:512` `halfPx = radius + 2.0` (1 px transparent skirt per side) vs Decision 5's `diameter + 2px` (`radius + 1`). Harmless overdraw, but the budget meter (`:1179`, `πr²` without skirt) then understates the fill it warns about. Align spec or code (1 px).

## Finding 5 (MINOR): paper grain lives in device px, tip grain in dpr-normalised px
Proof: tip tile divides by the dpr-scaled size (`:988`) so grain sticks to the tip; paper tile uses raw `gl_FragCoord/(paperScale·256)` (`:581`) — at `dpr = 2` the paper grain is half the CSS size of the tip grain. Decide which space `paperScale` lives in; one-line normalisation either way.

## Finding 6 (MINOR): `mergePreset` drops explicit `null`s — conflates null with missing
Proof: `:347` `if (v === null) continue` keeps the default instead of `null`. Fine for `image: null` today, but any future nullable field where `null` is meaningful (vs absent) will not survive a load. Carry `null` through; defaults apply only when absent.

## Verified (proof — read in full, not skimmed)
- No-copy discipline: `__SHARED__` + function-replacer (no `$&` eat), absolute-then-relative fetch, fatal messages naming the `http.server` command for fetch/WebGL2/compile failures (never a private copy). No embedded GLSL.
- WebGL2-only (no WebGL1 fallback, per spec) with RGBA16F-or-RGBA8 retry path + visible badge; pen mapping (coalesced-first, mouse-0.5, altitude-vs-tiltX/Y with the vertical-sensor tiebreak, azimuth `wrapPi` incl. NaN→NaN); spacing leftover arithmetic verified (strict `<`, junction ownership, tap→dot, zero-length burn, 20000/event cap); lean-vector (not angle) interpolation — the +179/−179 trap avoided; follow-damping `k = 1−exp(−step/6)` + 0.17 lean override, matching the `DirectionTracker` claim.
- Wash/buildup per the Q10-corrected reading (`MAX,MAX` + the bleaching essay — the literal spec text was wrong and the fix makes Q7 true); commit-on-pointer-up with live opacity cap; full control panel per Decision 7 with spec-correct ranges/defaults from `BrushPreset` (spacing 0.04, hardness 0.9, flow/opacity 1, follow false); unknown-field preservation via whole-preset carry + `extensions` parking for colours/fake-rotation (Q4's question correctly asked, not answered unilaterally).
- Q10 fixes confirmed in code: `DYNAMIC_DRAW` (+comment naming the desktop-GL trap), buffers allocated before first frame, clean-console discipline. The readPixels probe (darkest pixel exactly the brush colour, byte-for-byte, white-core before) is retained as the regression test that matters.
- Q2/Q3/Q5/Q6/Q7/Q8 all correctly flagged as open fidelity gaps with in-UI disclosure (curve report, erase-preview note, untouched-field list) — flagged, not hidden; no finding for honest scoping.

## Recommendation
Fix Findings 1–2 (unit conversion + curve preservation — both corrupt what the tuner sees/saves); 3–6 are MINOR. The visual half (three screenshots, console) stays with the owner per R6 — but with the readPixels probe in the README, any future regression is a number, not an opinion.
