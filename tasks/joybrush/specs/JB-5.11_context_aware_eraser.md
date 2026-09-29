# JB-5.11 — The one eraser: it erases lines on ink, pixels on paint

| | |
|---|---|
| **Tier** | T2 |
| **Status** | see ROADMAP.md |
| **Needs** | JB-5.10 (`VectorEraser`, `EraseMode`, `InkLine` — Built), JB-5.01 (`InkReplay`, `InkRaster`) |
| **Owner area** | NEW `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/vector/InkErase.kt`, NEW `.../commonTest/.../vector/InkEraseTest.kt`. **Nothing else.** |
| **Estimated size** | ~200 lines + ~220 lines of tests |
| **Command** | `./gradlew -p joybrush :core:jvmTest` — 0 failures |

## Goal

Owner constraint, verbatim: *"Eraser, context aware: on vector/ink it erases vectors, on raster it
erases pixels with raster settings."* One eraser control; what it *does* is decided by the layer it is
used on. Blueprint §2 gives the three ink modes — **partial** (cut at eraser width), **whole line**,
and **to intersection** (trim an overhang back to where it crosses another line) — and JB-5.10 built
their geometry.

JB-5.10 stops at pieces: it says which stretches of which line survive, in fractional point indices.
It deliberately does not turn those into strokes, because that needs the ink layer's own truth. This
spec is that step, and it is the last piece of Phase 5.

## Contract (verbatim)

```kotlin
package cc.joycreator.joybrush.core.vector

import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.stroke.StrokeRecord

/** Which eraser is in the user's hand right now. */
enum class InkEraseMode { PARTIAL, WHOLE_STROKE, TO_INTERSECTION }

/** What the eraser did to an ink cel. */
sealed interface InkEraseOutcome {
    /** The cel is unchanged and nothing is wrong (the pen had not moved; nothing was touched). */
    data object Untouched : InkEraseOutcome
    /** Records changed — possibly to none at all. One undo step. */
    data class Erased(val celId: String, val before: Map<String, StrokeRecord>) : InkEraseOutcome
    /** Refused, with a sentence a screen can show. The cel is byte-identical to before. */
    data class Refused(val reason: String) : InkEraseOutcome
}

/**
 * The ink eraser. [InkErase.run] is pure and total; the PAINT half of the context-aware eraser is
 * NOT here (it already exists: a `blend = "erase"` stroke through `GlPaintEngine`), and choosing
 * between the two is the caller's one-line switch (Decision 2).
 */
object InkErase {
    /**
     * Erase along [path] from every record in [records], in the cel [celId].
     *
     * Lines come from `InkReplay.dabs` (JB-5.01): an ink line IS its recording, so the eraser measures
     * against the same geometry the layer draws. A record that cannot be replayed (missing brush, or
     * a `smudge`/`wet` brush) is **left alone and reported** — see Decision 5.
     *
     * @param mode which of the three ways to erase.
     * @param brushOf the brush each record was drawn with.
     * @return [InkEraseOutcome.Erased] with the records that survive — including when NONE do (a
     *   fully erased line is a change, not a no-op) — or [InkEraseOutcome.Untouched] when the eraser
     *   touched nothing.
     */
    fun run(
        celId: String,
        records: List<StrokeRecord>,
        path: EraserPath,
        mode: InkEraseMode,
        brushOf: (StrokeRecord) -> BrushPreset?,
    ): InkEraseOutcome
}
```

## Decisions

1. **One session, one undo step, per gesture.** `EraseResult.before` holds the records that changed, so
   undo restores exactly those and nothing else. This mirrors `InkEditStep` from JB-5.03 and, like
   it, stores records rather than tiles — because `UndoLog` never holds a `StrokeRecord`
   (`JB-0.01`'s review finding).
2. **The caller picks the maths, the eraser does not.** On `LayerKind.PAINT` the eraser is an ordinary
   `blend = "erase"` stroke, which `GlPaintEngine` and `RefCanvas` already do, and none of this file
   runs. On `LayerKind.INK` this file runs. The switch is on `Layer.kind` — a value the document
   already carries and `DocOps.validate` already checks against `Cel.strokesFile` /
   `Cel.tiles` (rule 8), so there is nothing new to store.
3. **The eraser radius is the current brush's size, in DOCUMENT px, halved.** `Brush.sizePx` (or a
   preset's `size.base`) is already document px by R10's ruling, and the ink layer's geometry is
   document px, so `EraserPath.radius = size.base / 2`. **Consequence, stated:** the eraser gets
   bigger on screen as you zoom in, exactly like the brush. That is R10's decision applied a second
   time and I am not re-litigating it here; it is noted as **Q2** because an eraser is the one control
   where a screen-constant size is arguably what a finger wants.
4. **A line is built from the recording's own dabs, and the mode is JB-5.10's `EraseMode`
   unchanged.** `InkEraseMode` maps 1:1 onto `PARTIAL / WHOLE_STROKE / TO_INTERSECTION` by name and is
   a separate enum so this file's public surface does not change when JB-5.10's does. The mapping is a
   `when` with three arms, so a fourth constant in either enum is a **compile error** rather than a
   silent fall-through. (`EraseMode` is a Kotlin enum, not a serialised one, so no version bump.)
5. **A record the eraser cannot draw is left alone, and named.** No brush in the library, or a
   `smudge`/`wet` brush: `InkReplay.dabs` refuses it, so there is no geometry to measure against. It is
   **not** deleted (that would be data loss caused by a missing asset) and **not** measured against a
   default nib (that would erase a line the user cannot see). It stays, and the outcome carries a
   sentence. To keep the outcome one value, an un-replayable record that the eraser would otherwise have
   touched produces `Refused(reason)` — **and the records are unchanged**, so a screen can show the
   message and the drawing is intact either way.
6. **`Piece`s are turned back into recordings by re-slicing the samples, not by slicing dabs.** A
   `Piece` names fractional indices along the *replayed line's* point list, which is not the recording.
   So each survivor is rebuilt by **replaying the surviving piece's parameter range through the same
   `DabPlacer`** to get its dabs, and then taking the recording samples whose positions bracket that
   dab run. Concretely: a survivor keeps the samples from the first sample at or before the piece's
   first dab centre to the last sample at or after its last dab centre. This keeps a survivor a real
   recording — same `PenSample`s, same `seed` — so **re-brushing a half-erased line still works**
   (R20), which is the whole reason a line is a recording. Decision 7 pins the exact bracketing so it
   is not a matter of taste.
7. **Survivor identity.** A line cut in two becomes **two records**: the first keeps the original id,
   the second gets `<id>~2`, a third `<id>~3`. Reason, and it is a load-bearing one: JB-5.02's review
   filed a MAJOR that two lines sharing an id make `StrokePicker`'s cycle return the same line forever,
   so duplicate ids on an ink layer are a known defect. `~n` is chosen over a fresh uuid because the
   first survivor keeping its id is what lets an undo, a re-render and a timelapse all still refer to
   "that line"; the suffix is unambiguous and cannot collide with a uuid.
8. **A survivor with fewer than 2 samples is not a line.** One sample is a dab; `InkLine` allows
   length 1 (a dot) but a *recording* of one sample cannot be re-brushed into anything, and
   `DabPlacer` needs two points to have a segment. Fewer than 2 surviving samples → the piece is
   dropped, and the record is gone from the outcome. This is the **speck filter**, and see Decision 9:
   JB-5.10's own 0.5-doc-px rule is PARTIAL-only and is still referred, so this spec does not inherit
   it.
9. **This spec does not apply JB-5.10's 0.5-doc-px speck rule and does not change it.** `EraseMode`'s
   surviving pieces are what they are; where the referred question (JB-5.10 Q1, still open in
   `ORCHESTRATOR_LOG.md`: does the speck filter apply to all three modes?) lands, the answer changes
   JB-5.10, not this file. Provisionally, **and marked**: a sub-0.5-doc-px sliver survives to Decision
   8's 2-sample floor. I have deliberately not tightened it, because tightening a referred question's
   answer from another file is how two specs end up disagreeing about the same geometry.
10. **Nothing is erased twice.** The eraser runs once per gesture over the records as they were at
    `begin`, not over the progressively rebuilt survivors. Running it repeatedly over its own output
    is the classic eraser-drag bug (a fast drag cuts a staircase), and it is one line to get wrong.
    A caller wanting a second pass calls `run` again with the outcome's own records.
11. **An empty path, a zero-radius path, or an empty record list → `Untouched`.** Not an exception.
    `VectorEraser` is already tolerant here (its Q2 was ruled provisional-tolerate, and the ruling says
    an empty path is a real state mid-gesture), so this spec matches it rather than adding a rule the
    geometry below does not have.
12. **A brush-bound constant is never copied.** `InkEraseMode` is not a serialised enum, so adding a
    constant needs no `DOC_VERSION` bump (R3 covers serialised enums). `EraseMode`'s three constants
    are JB-5.10's, read not copied.

## Steps

1. Tests first, from Decisions 1–12.
2. `InkErase.kt`.

## Tests

**`InkEraseTest`** — helper `lines(n)` builds `n` records along the x axis at y=0, sampled every 2 doc
px, all `brushId = "ink"`, all with `BrushLibrary`-shaped `stamp` presets of diameter 6, `spacing`
0.04.

1. **PARTIAL cuts, WHOLE_STROKE removes, TO_INTERSECTION trims (D4):** three lines crossing at
   x = 100; an eraser dot at (140, 0) radius 3:
   - `PARTIAL` → the horizontal line survives as two pieces with a gap around x = 140;
   - `WHOLE_STROKE` → the horizontal line is **absent from the outcome's records entirely**;
   - `TO_INTERSECTION` → the overhang from x = 100 to the end is gone, the part left of 100 survives.
   This is `VectorEraser`'s own table re-asserted through the recording round trip, which is the point:
   it fails if the sample bracketing (Decision 7) loses the cut.
2. **Survivors are real recordings (D6, D7):** a line of 200 samples cut once gives two records; both
   have the ORIGINAL `seed`, the original `brushId`, and their samples are a contiguous **sublist of
   the original samples** (assert by `subListOf` on identity of the `PenSample` values — not by
   position within a tolerance, or the test proves nothing). Re-brushing either survivor with a pencil
   preset changes only `brushId` and draws (the dabs are non-empty).
3. **Ids (D7):** the first survivor's id equals the original; the second is `"<id>~2"`; a line cut
   three ways gives ids `<id>`, `<id>~2`, `<id>~3`, **all distinct**. No two records in the outcome
   share an id — this is the assertion that keeps JB-5.02's MAJOR from coming back through this door.
4. **The speck floor (D8):** a cut that leaves exactly one sample produces **no record** for it; a cut
   that leaves two produces a record. Non-vacuity: the same geometry with the cut 3 px further along
   leaves three samples and does produce one.
5. **Erase once, not repeatedly (D10):** a zig-zag path over one line that touches it at three
   separate places leaves exactly **3** pieces (`PARTIAL`), not 4 or 6 — and the cut edges are at the
   path's three sample positions, not at the midpoints of them.
6. **An un-replayable record is refused, and nothing changes (D5):** a record whose `brushOf` returns
   `null` sits under the eraser path → `Refused`, `reason` contains the stroke id and the brush id, and
   every record (including the eraserable ones) is `==` to its input. The same for a `smudge` preset:
   `reason` contains `"smudge"`.
7. **An un-replayable record the eraser does NOT touch is not even a refusal (D5):** the same missing
   brush, with the eraser 500 px away → `Untouched`, no `Refused`.
8. **Nothing touched is `Untouched`, and fully erased is `Erased` (D11 vs D1):** an eraser path far
   from every line → `Untouched`; an eraser path over the whole line in `WHOLE_STROKE` → `Erased` with
   an **empty** record list. These two being different is the test that stops "empty result = no change"
   from losing a whole line.
9. **Empty inputs (D11):** an `EraserPath` with no samples, radius 0, and an empty record list each
   return `Untouched` and do not throw.
10. **One gesture is one step (D1):** `Erased.before` contains exactly the records whose ids are
    missing from the outcome **or** whose samples differ — assert both directions, so a step carrying
    untouched records (harmless but wasteful) and a step missing a changed one (broken undo) both fail.
11. **Idempotence (D10's other half):** running the same `run` twice — once, then again over the
    outcome's own records with the SAME path — is refused or untouched rather than cutting further,
    because a line the first pass already removed is not there to cut.

**Command:** `./gradlew -p joybrush :core:jvmTest`. Passing = `BUILD SUCCESSFUL`, 0 failures.

## Do not

- Do not edit `VectorEraser.kt`, `InkLine`/`InkGeometry.kt`, `StrokeEdit`/`StrokeRecord` or
  `InkReplay.kt` — you consume them all.
- Do not implement the PAINT half. It is `blend = "erase"` and it already works.
- Do not store the eraser mode in the document. It is a tool setting, and a document field is a
  version bump and a migration for a preference.
- Do not loop `run` over its own output inside this function.
- Do not "fix" JB-5.10's speck rule from here (Decision 9).

## Definition of done

- [ ] tests pass (paste output)
- [ ] only owner-area files changed (paste `git status --short`)
- [ ] committed as `JB-5.11: context-aware eraser`; pushed
- [ ] ROADMAP row → 🟧 Built

## Questions

**Q1 — BLOCKING, and it is a referred question I am deliberately not deciding. JB-5.10 Q1 is still open**
(`ORCHESTRATOR_LOG.md`: Decision 2 mandates dropping surviving pieces under 0.5 doc px, Decision 4 says
only "the complement is the survivor list", the builder implemented it for `PARTIAL` only, and the
orchestrator's note says in as many words *"do not treat the sliver behaviour as settled"*). Decision 9
leaves it alone and Decision 8 applies a *different*, larger floor (2 samples). **Consequence you
should know about:** on an ink layer a sub-0.5-doc-px sliver now survives where `PARTIAL` alone would
have dropped it, because a sliver that survives `VectorEraser` almost always has 2 samples by the
time it gets here. If the Lead rules that the speck filter applies to all modes, this file needs one
extra line. **The ruling I need:** does the 0.5-doc-px speck rule apply to `WHOLE_STROKE` and
`TO_INTERSECTION` too, and if so does the filter live in `VectorEraser` (JB-5.10's file, one edit) or
here (this file, applied twice)?

**Q2 — Decision 3: the ink eraser's radius is document px, so it grows on screen as you zoom.** R10
ruled brush size is document px because "a stroke must look the same when you zoom back out", and I
applied that to the eraser. For a brush that is clearly right. For an eraser it is arguable the other
way — a finger-sized eraser that stays finger-sized at every zoom is what Concepts does. The two
cannot both be true. **My Decision 3 applies R10 because I am not going to quietly invent a second size
rule**, but this is a product call the owner should make: **should the ink eraser's radius be screen
px?**

**Q3 — Decision 5 refuses the whole gesture when one line cannot be replayed.** The alternative is to
erase everything else and report the one line it could not touch. I chose refusal because "this layer
silently kept a line you rubbed over" is the exact failure mode Phase 8's importers were ruled on,
and refusal is this project's house answer. **But it means one missing brush id makes the eraser
unusable on that layer**, which is a worse day for a person than a message. Rule it either way; it is
one `when` arm.
