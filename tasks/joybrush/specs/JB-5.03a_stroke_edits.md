# JB-5.03a — Editing a finished ink line: stroke record v2 (colour, width) + reshape / re-weight / re-brush

| | |
|---|---|
| **Tier** | T2 |
| **Status** | see ROADMAP.md |
| **Depends on** | JB-0.04 (`StrokeCodec`), JB-5.02 (stroke picking) — Built |
| **Owner area** | EDIT `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/stroke/StrokeRecord.kt` (two fields), EDIT `.../stroke/StrokeCodec.kt` (version 2), EDIT `.../commonTest/.../stroke/StrokeCodecTest.kt`, NEW `.../stroke/StrokeEdit.kt`, NEW `.../commonTest/.../stroke/StrokeEditTest.kt` |
| **Estimated size** | ~150 lines + ~220 lines of tests |

## Goal
Phase 5's promise (blueprint §7): "you re-shape and re-weight a finished line". An ink line is its
recording (`StrokeRecord`), so editing a line = editing the recording, then redrawing it. Two things
are missing from the record today and every ink line needs them: its **colour** and a **width
scale** (re-weighting must be able to make a line thicker even where the pen was already at full
pressure — so it cannot be done by changing pressure).

## Contract
```kotlin
// StrokeRecord gains two fields AT THE END, with defaults, so every existing call site still compiles:
data class StrokeRecord(
    val id: String, val brushId: String, val seed: Long, val smoothing: Float, val screenPerDoc: Float,
    val samples: List<PenSample>,
    val colorArgb: Int = 0xFF000000.toInt(),   // straight (not premultiplied) sRGB
    val widthScale: Float = 1f,                // multiplies the brush's size for this line; 0.05 .. 20
)

package cc.joycreator.joybrush.core.stroke
object StrokeEdit {
    /**
     * Drag the line: the sample nearest (grabX, grabY) moves by (dx, dy); samples within [radius] doc px
     * of it ALONG THE LINE move by the same offset × falloff; the rest stay. All other fields unchanged.
     */
    fun reshape(r: StrokeRecord, grabX: Float, grabY: Float, dx: Float, dy: Float, radius: Float): StrokeRecord
    fun reweight(r: StrokeRecord, factor: Float): StrokeRecord     // widthScale × factor, clamped 0.05..20
    fun rebrush(r: StrokeRecord, brushId: String): StrokeRecord    // seed kept, so scatter stays put
    fun recolor(r: StrokeRecord, argb: Int): StrokeRecord
}
```

## Decisions
1. **Codec version 2** (LEAD_RULINGS R3 spirit: new data ⇒ version bump). `VERSION = 2`; magic stays
   `JBS1` (it names the format family). v2 writes `colorArgb` (u32) and `widthScale` (f32) right after
   `screenPerDoc`, before the sample count. The decoder accepts **1 and 2**: v1 reads with the
   defaults (black, 1). Anything else is refused as today. The encoder always writes v2.
2. `widthScale` non-finite → 1; outside 0.05..20 → clamped (in `StrokeRecord`'s `init`? NO — keep
   the record a plain value; clamp in `StrokeEdit` and in the decoder).
3. **Reshape falloff:** arc length `s` from the grabbed sample along the line (both directions);
   weight `w = (1 − (s/R)²)²` for s < R, else 0 — smooth, 1 at the grab, 0 at the radius. The ends
   of the line move like any other sample. `radius` ≤ 0 or non-finite → only the grabbed sample moves.
   Non-finite dx/dy or grab point → the record is returned unchanged.
4. The record's `id` is KEPT by every edit (it is the same line, edited; undo stores the old record).
5. These are pure functions of the record; nothing here renders. Ink rendering is the Lead's JB-5.01.

## Tests
1. Codec round trip of a v2 record with colour 0x80FF8000 and widthScale 2.5 → identical record.
2. A v1 byte string (build it in the test with the v1 layout) decodes with colour black and scale 1.
3. Version 3 is refused with `StrokeCodecException`. (Update the existing "unsupported version"
   test if it used 2.)
4. Reshape: a straight 100-sample line, grab at sample 50, dx = 10, radius = 20 doc px → sample 50
   moves exactly 10; samples beyond 20 px of arc length don't move; weights decrease monotonically
   away from the grab; pressure/tilt/time identical to the input's.
5. Reshape at an end (grab sample 0) moves the end; radius 0 moves one sample only.
6. reweight 2 then 0.5 → back to 1 (within 1e-6); reweight 1000 → 20; reweight NaN → unchanged.
7. rebrush keeps seed, samples, colour, width; recolor keeps everything else. All edits keep `id`.
8. `StrokeRecord(…six args…)` still compiles and has the defaults (source compatibility).

**Commands:** `./gradlew -p joybrush :core:jvmTest` AND `./gradlew -p joybrush :androidkit:test`
(the `.joybrush` archive stores strokes through this codec — `JbArchiveTest` must stay green) —
0 failures in both.

## Do not
Change no existing field or its order; no other file than the owner area.

## Definition of done
Tests pass (paste) · commit `JB-5.03a: stroke record v2 and line edits` · ROADMAP row → 🟧 Built.

## Questions

*Builder T2, 2026-09-29. Nothing here blocked the build. Q2 and Q3 are the two that need a ruling;
Q1 is a verification I was not permitted to do.*

**Q1 — the spec's second command was not run, and I cannot certify `JbArchiveTest`.** The Commands
line asks for `:core:jvmTest` **and** `:androidkit:test`; the standing rule I was given permits only
`:core:jvmTest`, so `JbArchiveTest` is **unverified**. What I can say from reading it
(`androidkit/src/test/.../JbArchiveTest.kt:87,495`): it builds its records with the six-argument
constructor and asserts `strokeRecords() == back.strokes[…]`, and the two new fields default to
black and 1, which is exactly what the decoder gives a v2 recording — so it should be green, and
nothing it does is affected by the layout change (the strokes live in `strokes.jbs` behind
`encodeAll`/`decodeAll`, and the codec's own header is what versions them). **Someone should run
`:androidkit:test` once** to turn "should" into "is". No edit outside my owner area is needed for
it either way: the archive has no separate stroke-format version to bump.

**Q2 — I added `StrokeEdit.INK_ENGINES` and `drawsInkLines(engine)`; they are not in the contract.
Ruling wanted.** R20 says only `stamp` and `fill` may draw an ink layer and a `smudge`/`wet` brush
is "refused in words", but nothing in *this* spec's contract can do that: `rebrush` is handed a
brush **id**, knows nothing about engines, and the only caller that has a `BrushPreset` is JB-5.03's
`InkEditSession` (its Decision 9 already owns the sentence and the `Refused` result). So I put the
rule's *list* in the one place that owns the edits, for the session to call, and left the refusal
where the spec that owns it put it. Three answers: (a) keep `drawsInkLines` in `stroke/` and have
JB-5.03 call it; (b) move it to `BrushValidate` or a `Layer` rule, where a `LayerKind` is also in
scope — that is a different owner area and I did not touch it; (c) drop it as unused. It is five
lines and one test either way, and it is the only place in core that names R20's set.

**Q3 — the decoder REPAIRS an out-of-range `widthScale`, so `decode(encode(r)) != r` for a
hand-built record.** Decision 2 says clamp "in `StrokeEdit` and in the decoder", and as built the
decoder clamps (0 → 0.05, 1e9 → 20, NaN/±Inf → 1) and the encoder writes the record verbatim. So a
record that came off a device, out of an edit, or off a v1 file always round-trips exactly, but a
record built in a test with `widthScale = 0f` does not. I chose this because the record is a plain
value (the spec says so) and because repairing on the way IN means nothing downstream of a decode
has to re-check. The alternative is to clamp in `encode` as well, which makes encode/decode total at
the cost of silently rewriting a value the caller set. **Rule it**, and say which end is allowed to
normalise.

**Q4 — `VERSION` is still one constant, now 2, and it is "the newest this build reads"; the other
half of the range is a new `OLDEST_VERSION = 1`.** JB-1.08a's builder had to invent a *second*
constant (`BRUSH_VERSION_FILL`) because `EnumFreezeTest` pinned `BRUSH_VERSION = 1` in a file
outside their area, and the orchestrator then made it THE `BRUSH_VERSION`. Nothing in the tree pins
`StrokeCodec.VERSION` (I grepped: only `StrokeCodecTest` reads it), so I moved it in one edit and
the range reads as two names. `DEFAULT_COLOR_ARGB` is new for the same reason. Confirm, or say the
reader should carry a list of versions it understands instead of two bounds.

**Q5 — provisionally ruled, low risk: `radius ≤ 0` or non-finite moves the grabbed sample ALONE,
even when its neighbours are within a pixel.** Decision 3 says exactly this, so it is as specified;
it is only worth naming because "radius 0" and "radius 0.5 with a grab halfway between two samples"
give the same answer, and someone may want the second to move the nearer of the two. One line to
change in `reshape` if so.

**Q6 — a note so nobody "fixes" it: the two sides of a reshape agree to 1e-4 px, not bit for bit.**
The falloff is computed from cumulative arc length in `Float`, and the arc length 20 px to the right
of a grab and the arc length 20 px to the left are differences of different pairs of accumulated
sums. The test asserts `abs(right − left) < 1e-4` rather than list equality, with the reason in the
test. The closed-form weight assertion (±1e-3) is the sharp one; the mirror assertion is the sanity
one.
