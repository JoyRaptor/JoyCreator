# JB-4.01 — adversarial audit (muse, for bunny)

- Auditor: `muse-spark-1.3-contributor-free`, family Muse Spark (Meta). Read-only: no gradle,
  no edits. Spec `tasks/joybrush/specs/JB-4.01_sprite_board.md` read whole; ORCHESTRATOR_LOG
  JB-4.01 section + LEAD_RULINGS R19 read. Local HEAD `7bc60742`.
- Target: 🟧 Built (core) 2026-09-30 (sibling orchestrator's builder; 3 new files, 24 tests).
- Files read fully: `core/.../sprite/SpriteBoard.kt` (258), `SpriteGridMath.kt` (329),
  `SpriteBoardTest.kt` (778), `SpriteBoardHoldsNoGridArithmeticTest.kt` (209).

**AUDIT-VERDICT: CLEAN (0 BLOCKER, 0 MAJOR) + 2 MINOR notes for the Lead.**

## Attacked, all held

1. **Test-5 fixture**: `byCount(500×300,3,2)` → cells 166×150, `fitRect` 498×300; `cellAt(499,10)`
   = -1, `edgeAt(499,10,1)` = RIGHT — old test's null+-1 at one point truly unsatisfiable.
2. **Corner-vs-edge**: corner-first, two-sided `|Δ|·zoom ≤ 22`; t11 exact coords, t12 labels,
   t13 61×61 sweep counts, t14 density pins all re-derived, hold.
3. **Overflow**: `cellRect`/`subGridLines`/`grownCell` all `Long` + refusal; `fitRect` products
   safe after clamps; no plain-Int `x+col·cellW` remains. R19 holds.
4. **`cellAt` huge/NaN**: `!isFinite→-1`, Double path, range-check before any `toInt()`,
   `toInt()` only on values < 4096. No JVM-saturation reliance. `edgeAt` same; products
   ≤1e77 < 1e308 finite.
5. **Stepper (R36 Q2)**: single-arg `bySize/byCount`, no density; `COLS+1` on 500 px = 62 (not
   64) correctly asserted re-filled, not bit-identical; round-trip clamp rows re-derived.
6. **Sub-grid 2..8**: `2·(div-1)` per cell; div 0/1/negative/9/MAX/MIN → empty; `subGridDiv`
   absent (comment only) — deleted as claimed. J1 one-Float-const holds.
7. **Vacuous sweep (Lesson 9 family)**: no no-repeat-across-boundary claim; t21
   `before==after` is spec-stated true-by-construction with non-empty/empty checks, not filed.

## MINOR (for the Lead, no breaking input)

- **m1 — `withGrid` vs `fitted` guard inconsistency** (`SpriteBoard.kt:57-60` vs `:42-47`):
  CANVAS+grid throws via `withGrid` but succeeds via `fitted` (`board.copy`, no require).
  Spec maps C5 only to test 5 (`withGrid`), so the implementation matches the spec text but
  contradicts C5's "SPRITE only" intent — spec gap. Rule whether `fitted` (and
  `dragged`/`stepped` on CANVAS+grid) should also require SPRITE.
- **m2 — spec rationale false** (spec:406-409 + `DocOps.kt:90-98`): claims "validator will
  complain (rule 5)" for CANVAS+grid, but landed `validate` loops only `kind==SPRITE` → zero
  errors. No behaviour break; fix the rationale (or add the validator rule).
