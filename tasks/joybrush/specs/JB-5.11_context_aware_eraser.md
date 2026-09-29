# JB-5.11 — The one eraser: it erases lines on ink, pixels on paint

| | |
|---|---|
| **Tier** | T2 |
| **Status** | 🟦 Ready |
| **xr** | stealth/space-bunny-alpha 2026-09-29 — Lead review fixes applied: Q1 answered from the landed `VectorEraser.kt` (the 0.5-doc-px speck rule is already in it, once, for all three modes — inherited, not re-specified), Decision 5 and test 6 rewritten so the eraser erases everything else and REPORTS the one line it could not replay, R10's document-px rule stated as decided. |
| **Needs** | JB-5.10 (`VectorEraser`, `EraseMode`, `EraserPath`, `Piece`, `InkLine` — **already Built, and it already carries the 0.5-doc-px speck rule**), JB-5.01 (`InkReplay` — the one hard dependency) |
| **Owner area** | (1) NEW `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/vector/InkErase.kt` · (2) NEW `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/vector/InkEraseTest.kt` · **NOTHING ELSE.** Not `VectorEraser.kt` — **the speck rule is already in it** (Decision 1) — not `InkGeometry.kt`, not `StrokeEdit.kt`, not `StrokeRecord.kt`, not `InkReplay.kt`. All consumed, never edited. |
| **Estimated size** | ~210 lines of code, ~250 lines of tests |
| **Command** | `./gradlew -p joybrush :core:jvmTest` — `BUILD SUCCESSFUL`, 0 failures in `joybrush/core/build/test-results/jvmTest/` |

## Goal

Owner constraint, verbatim: *"Eraser, context aware: on vector/ink it erases vectors, on raster it
erases pixels with raster settings."* One eraser control; what it *does* is decided by the layer it
is used on. Blueprint §2 gives the three ink modes — **partial** (cut at eraser width), **whole
line**, and **to intersection** (trim an overhang back to where it crosses another line) — and
JB-5.10 built their geometry.

JB-5.10 stops at pieces: it says which stretches of which line survive, in fractional point indices.
It deliberately does not turn those into strokes, because that needs the ink layer's own truth.
**This spec is that step, and it is the last piece of Phase 5.**

## What JB-5.10 already built — inherited, not re-specified

**Read `VectorEraser.kt` before writing anything. Decision 1 is the first thing this spec does, and it
is a decision to do *nothing*.**

The 0.5-doc-px speck rule is **already in the landed file**, applied once, to all three modes, after
the `when`. `VectorEraser.kt:72`:

```kotlin
    /**
     * Survivor pieces thinner than this many doc px of centreline are dropped as specks. Applied
     * once, to every mode's result — a sub-half-doc-px stub is noise whether it is what is left of
     * a partial cut or what a trim to a nearby crossing left behind (JB-5.10 R28, Q1).
     */
    private const val MIN_PIECE_ARC = 0.5
```

and `VectorEraser.kt:143-159`, where the comment says the same thing again in prose:

```kotlin
            // The speck rule is applied here, ONCE, to whatever the mode produced: 0.5 doc px is the
            // same noise floor for a partial cut, a whole stroke and a trim to a crossing. It is
            // vacuous for WHOLE_STROKE, which returns no pieces at all.
            val pieces: List<Piece> = when (mode) { … }
            out[line.id] = pieces.filter { it.arcLengthIn(line) >= MIN_PIECE_ARC }
```

**RULED (R28, Q1; confirmed by R37): the rule applies to ALL THREE modes and lives in
`VectorEraser`, once.** The previous draft of this spec referred the question, warned that a
sub-0.5-doc-px sliver would survive, and told the builder not to fix it. That is all obsolete: the
question is closed, the code is landed, and **this spec inherits it by calling `VectorEraser.erase`
and nothing else.** There is no second speck filter here, there is no second `0.5` literal, and
Decision 8's 2-sample floor below is a *different, larger* filter on a *different* thing (samples,
not arc length) — the two do not overlap and neither replaces the other.

The other landed pieces this spec consumes, pasted:

```kotlin
// VectorEraser.kt:13
enum class EraseMode { PARTIAL, WHOLE_STROKE, TO_INTERSECTION }

// VectorEraser.kt:28 — "The eraser's path for this gesture, and its radius, both in doc px.
                        //  A single sample is a dot; two or more are the polyline the eraser swept."
data class EraserPath(val xs: DoubleArray, val ys: DoubleArray, val radius: Double)

// VectorEraser.kt:48 — "A piece of a line that SURVIVES, named in fractional point indices of the
                        //  line it came from: 3.25 is a quarter of the way from point 3 to point 4,
//                        //  and [0, n-1] is the whole line."
data class Piece(val sourceId: String, val from: Double, val to: Double)

// VectorEraser.kt:54 — "What survived. A line whose id is absent was never touched. An id present
                        //  with an empty list is a line the eraser removed."
data class EraseResult(val survivors: Map<String, List<Piece>>)

// VectorEraser.kt:110
fun erase(lines: List<InkLine>, eraser: EraserPath, mode: EraseMode): EraseResult
```

Note the two idioms that carry decisions: **an absent id means untouched**, and **a present id with
an empty list means the line is gone**. `InkErase` must honour both exactly, and test 8 is the test
that stops "empty result = no change" from quietly losing a whole line.

## Contract (verbatim)

```kotlin
package cc.joycreator.joybrush.core.vector

import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.stroke.StrokeRecord

/** Which eraser is in the user's hand right now. A tool setting; never stored in a document. */
enum class InkEraseMode { PARTIAL, WHOLE_STROKE, TO_INTERSECTION }

/**
 * A line the eraser could NOT erase, and why, in one sentence a screen can show.
 *
 * The gesture still happened: every other line it touched was erased (Decision 5).
 */
data class NotReplayed(
    val strokeId: String,
    val brushId: String,
    val reason: String,
)

/** What the eraser did to an ink cel. */
sealed interface InkEraseOutcome {
    /** The eraser touched nothing: no line came near it. Nothing changed and nothing is wrong. */
    data object Untouched : InkEraseOutcome

    /**
     * The gesture happened. [records] is the whole surviving cel, in drawing order — the records
     * that were NOT touched are in it unchanged, so a caller replaces the cel with this list and
     * needs no second source of truth.
     *
     * [before] holds **only** the ids whose record is absent from [records] or differs from it, so
     * one undo step restores exactly those and nothing else (Decision 1).
     *
     * [notReplayed] is empty in the ordinary case. When it is not, it is the report Decision 5
     * promises, and it does **not** stop the erasure: a person who rubbed over three lines and one
     * of them could not be measured gets three lines erased and one sentence, not a refusal and an
     * untouched drawing.
     */
    data class Erased(
        val celId: String,
        val records: List<StrokeRecord>,
        val before: Map<String, StrokeRecord>,
        val notReplayed: List<NotReplayed>,
    ) : InkEraseOutcome
}

/**
 * The ink eraser. [InkErase.run] is pure and total. The PAINT half of the context-aware eraser is
 * NOT here (it already exists: a `blend = "erase"` stroke through `GlPaintEngine`), and choosing
 * between the two is the caller's one-line switch on `Layer.kind` (Decision 2).
 */
object InkErase {
    /**
     * Erase along [path] from every record in [records], in the cel [celId].
     *
     * Lines come from `InkReplay.dabs` (JB-5.01): an ink line IS its recording, so the eraser
     * measures against the same geometry the layer draws. A record that cannot be replayed — no
     * brush in the library, or a `smudge`/`wet` brush — has no geometry to measure against; it is
     * **left alone and reported**, and every other line is still erased (Decision 5).
     *
     * @return [InkEraseOutcome.Erased] with the whole surviving cel, whenever the eraser touched
     *   anything at all — including when the only thing it touched was a line it could not replay
     *   (that is [Erased.notReplayed] with an empty [Erased.before], and it is not [Untouched],
     *   because something went wrong) — or [InkEraseOutcome.Untouched] when nothing came near it.
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

1. **The speck rule is JB-5.10's and this spec does not touch it.** See the section above: it is
   landed, it is in `VectorEraser`, it runs once for all three modes, and this spec calls
   `VectorEraser.erase` and inherits it. **There is no `0.5` literal in this file.** Decision 8's
   2-sample floor is a different filter (see Decision 8) and is not a second copy of this one.

2. **One session, one undo step, per gesture, and it stores RECORDS, not tiles.** `Erased.before`
   holds only the records that changed, so undo restores exactly those and nothing else. This
   mirrors `InkEditStep` from JB-5.03 and, like it, stores records rather than tiles — because
   `UndoLog` never holds a `StrokeRecord` (`JB-0.01`'s review finding), and a `StrokeRecord` is pure
   value with nothing to release, which is also why the budget is a step count and not a byte count.

3. **The caller picks the maths, the eraser does not.** On `LayerKind.PAINT` the eraser is an ordinary
   `blend = "erase"` stroke, which `GlPaintEngine` and `RefCanvas` already do, and none of this file
   runs. On `LayerKind.INK` this file runs. The switch is on `Layer.kind` — a value the document
   already carries and `DocOps.validate` already checks against `Cel.tiles` / `Cel.strokesFile`
   (`DocOps.kt:143-145`, "layer … is paint, so cel … cannot have strokes" / "is ink, so cel … cannot
   have tiles") — so there is nothing new to store and nothing new to validate.

4. **The eraser radius is the current brush's size, in DOCUMENT px, halved. RULED (R37, Q2): R10
   stands.** `BrushPreset.size` is a `Param` whose `base` is a **diameter in document px**
   (`BrushPreset.kt:66`), and R10 ruled brush size is document px because "a stroke must look the
   same when you zoom back out". The eraser is a brush — R37's own words: "the eraser is the brush
   with an erase blend" — so the same rule applies to it and there is exactly one size rule in the
   product.    `EraserPath.radius = size.base / 2` (a radius, because `VectorEraser` measures
   `radius + halfWidth` and a dab's own half-width is already a radius — `BrushDabber.kt:110`,
   `val radius = max(diameter, preset.tip.minPx) / 2f`).
   **Consequence, stated so nobody is surprised:** the eraser gets bigger on screen as you zoom in,
   exactly like the brush. That is R10 applied, not a new rule. **It is one line to change** — the
   `radius` argument at the single call site in `run` — if the owner ever wants a finger-sized eraser
   that stays finger-sized; and it is one line *here* precisely because nothing else in the file
   knows the radius.

5. **A record the eraser cannot draw is LEFT ALONE AND REPORTED — it does not refuse the gesture.
   RULED (R37, Q3); this is the rewrite the previous draft needed and why the row was held.**
   No brush in the library, or a `smudge`/`wet` brush: `InkReplay.dabs` refuses it, so there is no
   geometry to measure against, and an ink line with no geometry cannot be cut without guessing.
   The three available answers were: **delete it** (that is data loss caused by a missing asset),
   **measure it against a default nib** (that erases a line the person cannot see, using a width
   nobody chose), or **leave it and say so**. The third is the answer, and here is why it is right
   rather than merely convenient:
   - The failure this guards against is "this layer silently kept a line you rubbed over", and the
     report is exactly what defeats it: the erasure happens, the sentence appears, and the person
     learns which line is stuck and why.
   - Refusing the whole gesture is **worse for the person** than erasing what can be erased. A layer
     with one missing brush id would have an eraser that does nothing at all, every time, for a
     reason they have to work out. That is the "one missing brush id makes the eraser unusable on
     that layer" outcome, and R37 rejected it.
   - The record is **not** deleted, so a later build that has the brush, or a re-import, can still
     draw it. Erasing is not the place to throw anything away.
   So: every other line is erased exactly as it would have been, the un-replayable one comes back in
   `records` **unchanged and `==` to its input**, it contributes **nothing** to `before`, and it is
   named in `notReplayed` with a sentence naming the stroke id, the brush id and the reason — the
   same two facts `InkReplay.refusal` already produces, so the wording exists in one place.
   A `fill` record is reported the same way and for the same reason: JB-5.01 Decision 12 gives a
   `fill` record no dabs, so it has no centreline to cut, and it is not this row's job to invent one
   (see Questions).

6. **`Piece`s are turned back into recordings by re-slicing the SAMPLES, not by slicing dabs.** A
   `Piece` names fractional indices along the *replayed line's* point list, which is the recording's
   dabs — many more points, at a spacing of the brush's own, and not the samples. So each survivor
   is rebuilt by naming the **sample index range** its piece covers: the piece's endpoints are
   converted to positions along the line by `InkLine.pointAt`, and then the recording's samples are
   taken from the first sample at or before the piece's start position to the last sample at or
   after its end position, **walking the samples in order**. Both endpoint tests are inclusive on
   purpose: a survivor that dropped the sample just outside the cut would come back visibly short of
   where the ink stops.
   This keeps a survivor a real recording — the same `PenSample`s, the same `seed`, the same
   `widthScale` and `colorArgb` — so **re-brushing a half-erased line still works** (R20), which is
   the whole reason a line is a recording and not a bag of dabs. A survivor is a contiguous
   **sublist** of the original samples, asserted by identity in test 2.

7. **Survivor identity.** A line cut in two becomes **two records**: the first keeps the original id,
   the second gets `<id>~2`, a third `<id>~3`, and so on in order along the line. Reason, and it is
   load-bearing: JB-5.02's review filed a MAJOR that two lines sharing an id make `StrokePicker`'s
   cycle return the same line forever (`ROADMAP.md`, JB-5.02's row: "two lines sharing an id …
   make `indexOf` stop on the first copy, so taps 3+ return the same line FOREVER"), so duplicate
   ids on an ink layer are a known defect. `~n` over a fresh uuid because the first survivor keeping
   its id is what lets an undo, a re-render and a timelapse all still refer to "that line"; the
   suffix is unambiguous and cannot collide with a uuid.

8. **A survivor with fewer than 2 samples is not a line — and this is NOT the speck rule.** One sample
   is a dab; `InkLine` legally allows length 1 (a dot, `InkGeometry.kt:14`), but a *recording* of one
   sample cannot be re-brushed into anything, and `DabPlacer` needs two points to have a segment.
   Fewer than 2 surviving samples → the piece is dropped, and the record is gone from the outcome.
   **This floor is in SAMPLES and Decision 1's is in ARC LENGTH, and they are not substitutes.** A
   0.4-doc-px sliver of a line sampled every 2 doc px holds one sample and is dropped by this rule;
   a sliver 0.4 doc px long on a line sampled every 0.1 doc px holds four samples, passes this rule,
   and was already dropped upstream by `VectorEraser`'s `MIN_PIECE_ARC`. Both filters are needed
   and neither does the other's job. Test 4 exercises each separately.

9. **Nothing is erased twice.** The eraser runs **once** per gesture, over the records as they were
   at the start, not over the progressively rebuilt survivors. Running it repeatedly over its own
   output is the classic eraser-drag bug — a fast drag cuts a staircase — and it is one line to get
   wrong. A caller wanting a second pass calls `run` again with the outcome's own `records`.

10. **An empty path, a zero-radius path, or an empty record list → `Untouched`.** Not an exception.
    `VectorEraser.erase` is already tolerant here (it returns `EraseResult(emptyMap())` for a path
    with no samples, `VectorEraser.kt:112-113`), and a half-built path is a real state mid-gesture, so
    this spec matches it rather than adding a rule the geometry below does not have. A path whose
    `radius` is `NaN` or infinite is `Untouched` too: `VectorEraser` would compare against `NaN` and
    match nothing, silently, and a sentence is better than silence.

11. **A brush-bound constant is never copied, and no new document field is added.** `InkEraseMode` is
    not a serialised enum — nothing writes these names to a file — so adding a constant needs no
    `DOC_VERSION` bump (R3 covers serialised enums; `MaskPaintMode` is the precedent, and its KDoc
    says so). `EraseMode`'s three constants are JB-5.10's, read not copied, and the mapping is a
    `when` over `InkEraseMode` with exactly three arms, so a fourth constant in **either** enum is a
    compile error rather than a silent fall-through. The eraser mode is a **tool setting**: storing it
    in the document would be a version bump and a migration for a preference.

## Steps

1. Read `VectorEraser.kt` and `VectorEraserTest.kt` first. They are the geometry and its proof; this
   spec is the round trip through recordings.
2. Tests first, written from Decisions 1–11, into `InkEraseTest.kt`.
3. `InkErase.kt`.
4. Run the command. If it is green, stop. There is no view, no gesture and no wiring step.

## Tests

`./gradlew -p joybrush :core:jvmTest`. Passing = `BUILD SUCCESSFUL` and 0 failures in
`joybrush/core/build/test-results/jvmTest/`.

**Source set, and this is a decision with a reason.** `InkEraseTest` goes in **`commonTest`**, and it
is the only file in the review's slip #3 that was wrongly put there. Its cases open **no files**:
every fixture is a `StrokeRecord` and a `BrushPreset` built in code, exactly as `VectorEraserTest`
(which is in `commonTest` and passes) builds its `InkLine`s. **If a case is added that needs to read
a shipped `brush.json` off disk — a canary that the real Ink and Pencil presets erase sanely — that
case must be a SEPARATE file in `core/src/jvmTest/kotlin/…/vector/`**, importing `java.io.File`, and
it must not be mixed into this one. `commonTest` must stay platform-neutral: it is compiled for the
JS and iOS targets this engine is written for, and `java.io` does not exist there.

**Fixtures.** `inkBrush(id = "joybrush.ink", name = "Ink", engine = "stamp", size = Param(6f), tip =
TipSpec(corner = 2f, hardness = Param(1f)), spacing = 0.04f, accumulate = "wash")` — the shipped
Ink's numbers, so a dab's radius is exactly 3.0 (`BrushDabber.kt:110`,
`max(6f, 1f) / 2f`) and
`VectorEraser`'s `pad = radius + max(h0, h1)` is arithmetically checkable. `pencilBrush()` = Ink with
`id = "joybrush.pencil"`, `name = "Pencil"`. `smudgeBrush()` / `wetBrush()` = Ink with that `engine`.
`rec(id, n, x0, y0, step)` builds a record of `n` samples 2 doc px apart along `y = y0` from
`x = x0`, `seed = 7L`, `brushId = "joybrush.ink"`, `smoothing = 0f`, `screenPerDoc = 1f`.
`dot(x, y, r)` = `EraserPath(doubleArrayOf(x), doubleArrayOf(y), r)` — one sample is a dot, per
`EraserPath`'s own KDoc.

1. **PARTIAL cuts, WHOLE_STROKE removes, TO_INTERSECTION trims (D4, D11).** `h` = `(0,0)-(200,0)`;
   `v` = `(100,-20)-(100,20)`; both sampled every 2 doc px. An eraser dot at `(140, 0)` radius 3.
   Derivation, so the numbers below are not guesses: a dab's half-width is 3.0 and the dot's radius
   is 3, so `VectorEraser`'s `pad` is `3 + 3 = 6`, and the cut reaches from `140 − 6 = 134` to
   `140 + 6 = 146`.
   - `PARTIAL` → `h` survives as **two** records, one ending at x = 134 and one starting at x = 146,
     asserted with a tolerance of one sample step (2 doc px), because the samples are 2 doc px apart
     and the bracketing in Decision 6 can only be that accurate. `v` is absent from `survivors`' keys
     and comes back `==` to its input.
   - `WHOLE_STROKE` → `h` is **absent from the outcome's `records` entirely**, and `v` is still there
     unchanged.
   - `TO_INTERSECTION` → the overhang from x = 100 (where `v` crosses) to the end is gone; `h`
     survives as one record ending at x = 100, within one sample step. This is
     `VectorEraserTest.toIntersectionTrimsAnOverhangBackToTheCrossing` re-asserted through the
     recording round trip, which is the point: it fails if the sample bracketing loses the cut.
2. **Survivors are real recordings (D6, D7, D10).** A line of 200 samples (398 doc px long) cut once
   at x = 140 gives **two** records; both have the ORIGINAL `seed` (`7L`), the original `brushId`,
   the original `widthScale` and `colorArgb`, and their samples are a contiguous **sublist** of the
   original — asserted with `original.samples.subList(i, i + n) == survivor.samples`, by identity of
   the `PenSample` values, not by position within a tolerance. A builder who rebuilt samples from
   interpolated coordinates fails here and nowhere else. Re-brushing either survivor with
   `pencilBrush()` changes only `brushId` and gives a non-empty dab list.
3. **Ids (D7).** The first survivor's id equals the original; the second is `"<id>~2"`; a line cut
   three ways gives `<id>`, `<id>~2`, `<id>~3`, **all distinct**, asserted as a set of size 3 — the
   assertion that keeps JB-5.02's MAJOR from coming back through this door.
4. **The two speck filters are different filters, and each is tested on its own (D1, D8).**
   - **The inherited one, as a precondition, not a re-test.** A line sampled every 2 doc px with a cut
     that leaves 0.4 doc px of arc: that piece is dropped by `VectorEraser` and `InkErase` never sees
     it — the record comes back whole. Assert it is whole, and the test's comment says the filter
     that did it is `MIN_PIECE_ARC` in `VectorEraser.kt`, **not** anything in this file. (If this
     case ever starts passing through, a second `0.5` filter has crept in here. That is the trap this
     test exists to catch.)
   - **This spec's own, the 2-sample floor.** A line sampled every **0.1** doc px (so a 0.4-doc-px
     sliver holds 4 samples and survives the inherited rule) cut so that a piece holds exactly **one**
     sample → that piece produces **no record**; the same geometry with the cut 0.3 doc px further
     along, leaving two samples, does produce one. Non-vacuity, both directions.
5. **Erase once, not repeatedly (D9).** A zig-zag path over one line that touches it at three
   separate places leaves exactly **3** pieces under `PARTIAL` — not 4, not 6 — and the cut edges sit
   at the path's own crossing positions, ± 6 doc px of half-width, within one sample step. A builder
   who loops `run` over its own output cuts a staircase and fails here.
6. **A record that cannot be replayed is REPORTED, and everything else is still erased. RULED (R37,
   Q3) — this is the rewritten test, and the old one asserted the opposite.** Three records: `a` and
   `b` under the eraser with a resolvable brush, `c` also under it with `brushOf` returning `null`.
   `PARTIAL` with a dot at `(140, 0)` radius 3 over lines at y = 0, 50 and 100:
   - the outcome is **`Erased`**, not `Refused` and not `Untouched` — the old draft's test asserted
     `Refused` and "every record `==` to its input", which is the behaviour R37 overturned;
   - `a` and `b` **are** cut, exactly as they would be with no `c` present (assert their survivor
     counts and ids against the same fixture without `c`, so "the rest was erased" is measured
     against a baseline and not merely asserted);
   - `c` is present in `records` and `==` to its input — **not deleted**;
   - `c` contributes **nothing** to `before` (assert `c.id !in before.keys`);
   - `notReplayed` has **exactly one** entry, whose `strokeId` is `c`'s id, whose `brushId` is the id
     `brushOf` was asked for, and whose `reason` contains both — the same two facts
     `InkReplay.refusal(c, null)` produces, so the wording exists in one place.
   The same fixture with `smudgeBrush()` instead of `null` gives the same shape, with the engine word
   in the reason.
7. **A record that cannot be replayed and is NOT touched is not even a report (D5).** The same
   missing brush, with the eraser 500 doc px away from every line → `Untouched`, no `notReplayed`.
   Non-vacuity: the same fixture with the eraser moved onto `a` gives `Erased` **with** a
   `notReplayed` entry, so "a missing brush somewhere on the layer" is not confused with "a missing
   brush under the eraser" — the difference is whether the person was trying to erase something.
8. **Nothing touched is `Untouched`; fully erased is `Erased` (D2, D11).** An eraser path far from
   every line → `Untouched`, and `Untouched` is only ever returned when nothing came near anything.
   An eraser path over the whole line in `WHOLE_STROKE` → `Erased` with an **empty** `records` list
   and a `before` holding the record. These two being different is the test that stops "empty result
   = no change" from losing a whole line — and the first half is asserted from both sides: an
   `Untouched` outcome must have an empty path, a zero radius, or an empty record list, and this spec
   never returns `Untouched` for any other reason.
9. **Empty and hostile inputs (D10, D11).** An `EraserPath` with no samples, `radius = 0.0`, a
   `NaN` radius and an infinite radius, and an empty record list, each return `Untouched` and do not
   throw. And a record with **zero samples** among replayable ones is simply not a line (JB-5.01
   Decision 13): it is not erased and not reported, and the others are.
10. **One gesture is one step (D2).** `Erased.before` contains exactly the records whose ids are
    missing from `records` **or** whose value differs from it — asserted in **both** directions, so a
    step carrying untouched records (harmless but wasteful) and a step missing a changed one (broken
    undo) both fail. With test 6's fixture, `before` has keys `a` and `b` and **not** `c`.
11. **The whole surviving cel comes back, in drawing order (D5).** With five records of which two are
    cut, `records` has the two survivors of the first cut line **at the position the original held**,
    then the untouched records in their original relative order. Asserted by walking both lists, not
    by size — a caller replaces the cel with this list, so an order change is a visible reordering
    of the drawing.
12. **Idempotence (D9's other half).** Running `run` again over the outcome's own `records` with the
    **same** path and mode produces no further change: the same survivor ids, the same sample counts,
    and — for `WHOLE_STROKE` — an empty `records` again. Non-vacuity: the first pass must have
    actually cut something, or this test passes for the wrong reason.

## Do not

- Do **not** edit `VectorEraser.kt`. The speck rule is already there, applied once, for all three
  modes (Decision 1). This spec inherits it. A `0.5` literal written in this file is a second rule
  about the same geometry and the two will drift.
- Do **not** edit `InkGeometry.kt`, `StrokeEdit.kt`, `StrokeRecord.kt`, `StrokeCodec.kt`,
  `InkReplay.kt` or `InkRaster.kt`. You consume all of them.
- Do **not** refuse the whole gesture because one line cannot be replayed. Erase everything else and
  **report** the one (Decision 5). This is the single most expensive mistake available in this file
  and it is the one the previous draft of this spec made.
- Do **not** delete an un-replayable record, and do **not** measure it against a default nib. Leaving
  it alone and saying so is the decision.
- Do not implement the PAINT half. It is `blend = "erase"` and it already works.
- Do not store the eraser mode in the document, and do not add a `DOC_VERSION` bump. `InkEraseMode` is
  not serialised and the mode is a tool setting (Decision 11).
- Do not loop `run` over its own output inside this function.
- Do not add an `import java.io.File` to `commonTest`. If a case needs a file on disk, it is a
  `jvmTest` file, and it is named in the Tests section.
- Do not re-specify `VectorEraser`'s geometry, its work counters or its test cases. Read the file.

## Definition of done

- [ ] `./gradlew -p joybrush :core:jvmTest` is green; the output is pasted into the report.
- [ ] Only the two owner-area files changed; `git status --short` is pasted into the report.
- [ ] `grep -n "0\.5" InkErase.kt` returns **nothing** (Decision 1) and the result is in the report.
- [ ] Two non-vacuity mutations were run and are reported: making the un-replayable record get
      **deleted** instead of reported, and making the 2-sample floor `>= 1` instead of `>= 2`. Both
      must redden at least one test.
- [ ] Committed as `JB-5.11: context-aware eraser`; pushed.
- [ ] ROADMAP row → 🟧 Built — awaiting T1 review.

## Stop rule

**Stop and write a question in `## Questions` if any of these is true; do not guess.**

1. Implementing a decision above needs a change to `VectorEraser`, `InkGeometry`, `StrokeEdit`,
   `StrokeRecord` or `InkReplay`. Every one of those has an owner that is not this row — and
   `VectorEraser` in particular is landed, reviewed work with a reviewed test suite.
2. A derived number in a test does not come out as derived. R9: an expected value may change only
   with its derivation written into the test, never to make a run go green. The `pad = radius +
   halfWidth = 6` figures in test 1 and the sample counts in test 4 are the ones most likely to be
   "adjusted", and they are the ones that must not be.
3. Replaying a record twice in the same run gives different dabs, or `VectorEraser.erase` returns
   pieces whose `sourceId` is not a line's id. That is a defect upstream; report it, do not work
   around it here.
4. A `Piece` cannot be turned back into a contiguous sublist of the recording's samples without
   inventing geometry — for instance a cut that lands between two samples where the line doubles
   back on itself. **A survivor that is not a contiguous sublist is not a recording**, and inventing
   a non-contiguous one to make a case pass is the failure this row exists to prevent. Stop and ask.
5. Anything here needs `JbCanvasView`, `GlPaintEngine` or a Gradle file.

## Questions — for the Lead

*(Nothing blocks this row. Both items are decided above and listed here so they are visible.)*

**Q1 — a `fill` recording has no centreline, so the ink eraser cannot cut it (Decisions 5, 6).**
JB-5.01's Decision 12 gives a `fill` record **no dabs** — its raster is `InkRaster.fill` on
`FillPen.outline` — so there is nothing for `VectorEraser` to measure against, and by Decision 5 a
fill stroke under the eraser is **left alone and reported** with a sentence. R21 makes the fill pen a
brush whose stroke is a filled shape, and R20 says fill pen → pencil gives the outline stroke, so a
person will meet this: draw a closed shape with the fill pen, try to erase part of it, get a message.
**The decision taken here is to report rather than guess**, because cutting a shape needs a rule for
what the pieces *are* (regions? outlines?) and R41 is still settling the fill pen's fill kinds.
**What I need ruled:** does a `fill` recording get a centreline (the outline's own polyline, which
`FillPen.outline` already returns) so the eraser can cut it, or does the fill pen's erase stay a
whole-shape operation for now? This is a one-arm change in Decision 5 either way and it is not this
row's to choose. Flagged against the Lead's Q1 in JB-5.03, which is the same gap seen from the other
side.

**Q2 — the eraser's radius is document px, so it grows on screen as you zoom (Decision 4).** R10
ruled brush size is document px because "a stroke must look the same when you zoom back out", and
R37 confirms R10 stands here, on the grounds that the eraser is the brush with an erase blend and a
product with two size rules has neither. For a brush that is clearly right. For an eraser it is
arguable the other way — a finger-sized eraser that stays finger-sized at every zoom is what Concepts
does, and the eraser is the one control a finger aims rather than a line. **The two cannot both be
true.** The decision taken is R10, applied without inventing a second rule, and the cost is stated
rather than hidden: the eraser is `size.base / 2` doc px and it is **one line** at the single call
site in `run` to make it screen px instead. **Provisional — Claude to confirm**, and if the owner
ever disagrees it is a one-line change and not a redesign.
