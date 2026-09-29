# Adversarial review — JB-2.06a Fill maths (flood fill with tolerance, gap closing)

- Reviewer: muse-spark (cross-reviewer; builder was an unrelated subagent — different family).
- Task status: 🟧 Built. Commit reviewed: `103cda10`.
- Spec reviewed: `tasks/joybrush/specs/JB-2.06a_flood_fill.md` (contract, decisions 1–5, tests 1–6 + orchestrator rulings on 3 corrected expectations).
- §5b checks: diff touches only NEW `fill/FloodFill.kt` + NEW `fill/FloodFillTest.kt` + the spec's rulings section — inside the owner area. Suite run by me: `FloodFillTest` 28/28, 0 failures (fresh `:core:jvmTest` 410/0).
- Severity scale per §5b: BLOCKER / MAJOR / MINOR.

## Finding 1 (MINOR): the tolerance kdoc's arithmetic is wrong (the code is right)
Proof: `FloodFill.kt:17-20` claims the scale is "Euclidean over all four channels, divided by two — so … (0,0,0,0) and (255,255,255,255) exactly 1.0 apart". Euclidean distance between those corners is √260100 = 510; divided by two is 255, not 1.0 (that would need ÷510). The implementation (`MAX_DIST_SQ = 260100.0`, `limitSquared = MAX_DIST_SQ·t²`, exact-Int comparison at `:250-257`) is self-consistent — t=1 covers the whole space, t=0 the seed colour only — so no behaviour depends on the prose. But the comment is the contract a future caller reads to pick a tolerance, and its derivation does not derive. One-line fix to the kdoc (name the squared-sum normalisation actually implemented).

## Verified (proof)
- The 2.13a lesson visibly applied: `MAX_FILL_PX = 2²³` in Long, checked BEFORE allocation (`checkedPixels`, `:207-220` — negative throws, zero returns empty like the renderer, oversize refused with a costed message incl. the strip-filling guidance); the kdoc even names the blocker as the reason and justifies the `render` value-parity without importing it (`:35-59`), with a peak-footprint table (24 MiB engine + the caller's reference).
- One predicate (`SeedTest.within`, `:244-258`): seed-measured (gradients stop instead of ramping), alpha-inclusive (transparent-vs-nearly-transparent distinguished, as the kdoc's load-bearing argument states), exact-Int sum ≤ 260100 (no sqrt, no float drift, cross-platform identical); NaN tolerance → 0 with the dead-tap rationale (`limitSquared`, `:229-232`); out-of-range options clamped per the slider contract (`:150, :190`).
- Gap closing per decision 2 exactly: wall → square-dilate (seals up to 2·gap, as the `FillOptions` kdoc states) → flood non-wall → dilate-back restricted to within-tolerance pixels (no moat) → swallowed-seed fallback to plain flood, with the array-reuse wipe discipline written out at each handoff (`:157-188` — every reuse wiped or cleared-by-`dilate`, and `dilate` refuses aliased arrays, `:351`).
- Grow per decision 3 (unrestricted square, documented why the rim pixels it must cover are exactly the out-of-tolerance ones); 4-connected flood vs 8-direction grow rationale stated and matched in code (`flood` seeds only row±1, `dilate` 8-way).
- Termination/memory argued from the image, not the artwork (mask-is-visited ⇒ ≤2·w·h pushes; stack doubling from 256; serpentine bound costed against the reference), and the UNITS trap that cost the first test run is now a naming convention with a scar comment (`:288-320`).
- The 3 corrected expectations: implementation-unchanged, and the rulings' geometry (no-moat push-back at `:184-186`; Chebyshev derivation in-test; 4-connectivity enclosure) agrees with the code paths cited — the tests now prove the properties they are named for rather than the counts that were guessed.

## Recommendation
No send-back. One MINOR (kdoc arithmetic). No BLOCKER or MAJOR open.
