# JB-5.02 — Picking the stroke you meant in dense line work

| | |
|---|---|
| **Tier** | T2 |
| **Status** | see ROADMAP.md |
| **Depends on** | JB-5.10 (uses its `InkLine` and distance helpers) |
| **Owner area** | NEW `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/vector/StrokePicker.kt`, NEW `.../commonTest/.../vector/StrokePickerTest.kt` |
| **Estimated size** | ~120 lines + ~150 lines of tests |

## Goal
The owner praised Concepts' selection "even in dense areas where you've done a lot of pen work".
Tapping picks the line you meant; tapping again in the same spot cycles to the next candidate.

## Contract
```kotlin
package cc.joycreator.joybrush.core.vector
class StrokePicker {
    /**
     * @param lines in drawing order (last = most recent, drawn on top).
     * @param screenPerDoc zoom; tolerances are in SCREEN px.
     * @return the picked line id, or null.
     */
    fun pick(lines: List<InkLine>, x: Double, y: Double, timeMs: Double, screenPerDoc: Double): String?
}
```

## Decisions
1. Candidate = a line whose centreline distance d (doc px, nearest point on any segment) satisfies
   `d ≤ halfWidthAt + 12 / screenPerDoc` — within the stroke's own half-width (interpolated at the
   nearest point) plus **12 screen px**.
2. Score = `d − halfWidthAt` (how far outside the ink you tapped; negative = inside). Sort by score;
   scores within **1 screen px** of each other are ties → the more recent line first.
3. **Cycling:** if the previous pick was < **400 ms** ago and within **6 screen px** of this tap, return
   the NEXT candidate in the same ordering (wrapping), instead of the first.
4. No candidates → null (and reset cycling).

## Tests
1. Two parallel lines 3 px apart, tap on the upper → upper. 2. Tap exactly between → the more recent.
3. Tap again quickly at the same spot → the other; a third tap → back to the first.
4. Tap again after 500 ms → first candidate again. 5. Far from everything → null.
6. Zoomed in (screenPerDoc 8): the 12-px tolerance shrinks to 1.5 doc px.

**Command:** `./gradlew -p joybrush :core:jvmTest` — 0 failures.

## Definition of done
Tests pass (paste) · commit `JB-5.02: stroke picking` · ROADMAP row → 🟧 Built.

## Questions
