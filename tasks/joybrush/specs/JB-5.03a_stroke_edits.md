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
