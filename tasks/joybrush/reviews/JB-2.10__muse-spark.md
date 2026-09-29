# Adversarial review — JB-2.10 Hold-to-shape maths (recognise + perfect)

- Reviewer: muse-spark (cross-reviewer; builder was the Lead — different family from reviewer by construction, R12).
- Task status: 🟧 Built. Commit reviewed: `bab2b0e4`.
- Spec reviewed: `tasks/joybrush/specs/JB-2.10_shape_recognizer.md` (API, decisions 1–8, tests 1–11, Do-not + Lead's Questions notes on the three spec-forced changes).
- §5b checks: diff touches only NEW `shape/` (Geometry, Shape, ShapeRecognizer, ShapePerfecter, StrokeMaker) + NEW shape tests + spec questions — inside the owner area; `input/`, `stroke/`, everything else untouched per "Do not"; pure `kotlin.math` only. Suite run by me in a clean HEAD worktree: `ShapeRecognizerTest` 12/12 + `ShapePerfecterTest` 5/5, 0 failures.
- Severity scale per §5b: BLOCKER / MAJOR / MINOR.

## Findings: none. Verified instead

1. **All eight decision thresholds confirmed in code with correct boundary operators:** 5 pts / `L<12`; closure `dist < max(20, 0.12L)`; RDP `eps = max(3, 0.035D)` with `worst > eps` split; 25° merge repeated to fixpoint; line `worst < 0.04·chord` with original first/last as endpoints; polygon side `≥ 0.15·L/n`; rect `|turn−90°| ≤ 18°` (correctly via `turn = π−interior`); ellipse residual `< 0.10` + circle `min/max > 0.88 → mean, rotation 0`; arc Kåsa `< 0.05R`, `|sweep| < 330°`, `R < 4D`. Every `<`/`>=` matches the spec's accept/reject wording — the classic off-by-boundary class is absent.
2. **The Lead's three spec-forced changes are present and marked `Lead:`:** one-lap cut + 3-round span-reweighted PCA refit (plain PCA provably under-reads rx 160→147 on arc-length-spread strokes — the mutation check is recorded, not just claimed); closure overshoot `2·eps` second-drop; cyclic merge including vertex 0 (with the mid-side-rectangle test proving it). Test 1's "within 2 px" reading clarified as specified (ends ARE the drawn samples, ≤4.5 px from ideal under roughening).
3. **Rectangle snap math re-derived:** `θ = atan2(Σsin4φ, Σcos4φ)/4` folds correctly into [0,90°); centre = corner mean; widths/heights = mean opposite-side projections; corner ordering (nearest-to-first, drawing direction via shoelace reversal) matches the spec sentence.
4. **Perfecting contract holds structurally:** arc-length fractions of the ORIGINAL stroke; shape parametrised from its start (line/arc), corner 0 closed (polygon), 720-sample ellipse from the first point's angle in stroke direction; `copy(x,y)` preserves pressure/tilt/azimuth/barrel/tool/time and count exactly (empty→empty). Direction tests pass by construction (line `a→b`, signed arc sweep, shoelace-consistent polygon/ellipse).
5. **Screen-px discipline throughout:** `×screenPerDoc` up front with non-finite/non-positive zoom refused, all `L/D/eps/close` in screen px, `÷screenPerDoc` back with angles untouched; zoom-invariance test expects `<0.01` round-trip. Non-finite samples filtered, degenerate fits (`chord≤0`, `l2≤0`, singular `solve3`) return null rather than NaN — the garbage test covers refusal-not-throw.
6. Non-bug notes (recorded, not filed): `rectangle()` falls back to the raw quad when opposite-side pairing isn't 2+2 (lenient, spec-silent); ellipse endpoint weights halve at missing neighbours (sums to ~1 interior weight — no threshold impact).

## Recommendation
No send-back. No BLOCKER, MAJOR, or MINOR open. The hold-to-shape UI (JB-2.11) inherits sound maths.
