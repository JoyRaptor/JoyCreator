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

*Raised by the JB-5.02 build, 2026-09-28. None of these blocked the build; they are decisions I took
that a later spec may want to overrule.*

1. **The 12-px slop decides HOW MANY candidates there are, never WHICH one wins.** Decision 1's test
   `d ≤ halfWidthAt + 12 / screenPerDoc` is the same statement as Decision 2's `score = d − halfWidthAt`
   being `≤ 12 / screenPerDoc`. So a line that falls outside the slop always scores worse than every
   line still inside it, and dropping it cannot change the answer to a first tap — only the length of
   the cycle. I implemented Decision 1 literally and pinned the zoom effect through the cycle (at
   zoom 8 a line 11 doc px away is not a candidate, so the second tap stays on the first line; at
   zoom 1 it is, so the second tap cycles to it). If the intent was that zoom changes *which* stroke
   wins, Decision 1 needs a different rule — a slop capped at a fraction of the half-width, or a
   slop that does not add to the half-width.
2. **"Scores within 1 screen px of each other" is not transitive, so it needs a tie rule of its own.**
   A three-way pile-up where A–B and B–C are inside a pixel but A–C is not has no answer the words
   give. I grouped scores into clusters anchored on the cluster's BEST score — so no cluster ever
   spans more than 1 screen px, and the literal pairwise promise holds for every pair inside one —
   and ordered by recency inside a cluster. So if A and B tie and C is more than a pixel below
   them, the ranking is A and B (recency) then C, rather than A, C, B or B, A, C; anchoring on the
   previous member instead would chain the whole pile-up into one cluster. Confirm the anchor,
   because a plain comparison sort would leave this to the sort implementation.
3. **Cycling is one step per tap, never a loop, and never null while a candidate exists.** Three
   situations return the same line twice in a row, all documented in the KDoc: a ranking of one
   candidate (a wrap onto itself), a previous pick that has dropped out of the ranking (the finger
   moved or the canvas changed, so the tap restarts at the first candidate), and two lines sharing
   an id (`InkLine` permits it; the contract assumes ids are stroke ids, as in JB-5.10's `survivors`
   map). The ranking is recomputed on every tap rather than pinned for the length of a cycle, which
   is what makes those three cases possible and also what guarantees the picker never returns a line
   the finger is no longer near. Pinning it would give a steadier rotation at the price of stale
   picks. Say which the owner wants.
4. **`pick` carries the cycle state, and there is no way to ask for a pick without disturbing it** —
   no `reset()`, and no read-only "what is under this point" for a hover highlight or a drag-time
   preview. A second `StrokePicker` has its own cycle. Not needed by this spec; flagging it so the
   next spec that wants a highlight adds it deliberately.
5. **A backwards clock and a non-positive or NaN `screenPerDoc` are both read as "no cycle" and
   "zoom 1".** A time that jumps backwards is a new document reusing the picker, not a second tap,
   and a zero zoom would divide the slop to infinity and make every line a candidate. Both are
   guarded and tested; neither is in the spec.
6. **On an exact crossing, a fat line beats a thin line drawn on top of it.** Both centrelines pass
   through the tap, so both are 0 away and the scores are `−halfWidth`: a 2-wide line scores -2 and
   a 0.2-wide one -0.2, and 1.8 is wider than the 1 screen px tie window, so the wider line wins on
   how far inside its own ink the tap landed rather than on recency. In cross-hatching — exactly the
   dense work this feature exists for — that is the surprising answer, because the thin line is the
   one the user can see on top. Give them the same half-width and recency takes over as intended
   (both tests: `aCrossingIsRankedByHowFarInsideTheInkTheTapLands`). If the owner wants the top ink
   to win at a crossing, Decision 2 needs a rule that a tap with `d == 0` on several lines is always
   a tie — which the picker can see, because it knows `d` and `halfWidth` per line, without ever
   asking for segment intersections.

