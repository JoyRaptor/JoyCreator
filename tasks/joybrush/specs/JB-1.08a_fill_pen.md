# JB-1.08a — The fill pen: a brush whose stroke is a filled shape (maths + brush format)

| | |
|---|---|
| **Tier** | T2 |
| **Status** | see ROADMAP.md |
| **Depends on** | JB-0.03 / 0.03b (brush format), JB-0.01 (`StrokeSmoother`) — Built |
| **Owner area** | EDIT `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/brush/BrushPreset.kt` (comments only), `BrushJson.kt` (version rule), `BrushValidate.kt` (accept `"fill"` and `"behind"`); NEW `.../core/brush/FillPen.kt`; NEW `joybrush/brushes/fill/brush.json` (+ its line in `brushes/index.txt`); tests `.../commonTest/.../brush/FillPenTest.kt` + additions to `BrushJsonTest` / `BrushValidateTest` |
| **Estimated size** | ~140 lines + ~200 lines of tests |

## Goal (owner, 2026-09-29)
"The lasso fill is actually a PEN — it has the same smoothing and nudging as other vector lines, so
much so that when you select it and pick another brush (say a pencil) it redraws that shape as if it
were done in pencil to begin with. And vice versa: select a pencil or pen stroke and pick the fill pen
and it becomes THAT shape as a solid fill, with a line joining the ends."

So the fill pen is **a brush like any other**, recorded as an ordinary `StrokeRecord`. Only how the
stroke is DRAWN differs: a stamp brush drags dabs along the line; the fill pen fills the closed shape
the line makes. Because the stroke is a recording, re-brushing works in both directions for free
(`StrokeEdit.rebrush`, JB-5.03a), and so do smoothing, reshape and nudge.

## Contract
```kotlin
package cc.joycreator.joybrush.core.brush

import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.shape.Pt

object FillPen {
    /**
     * The closed outline a fill-pen stroke fills, in document px: the stroke's samples smoothed
     * exactly as a stamp stroke's would be (`StrokeSmoother.smoothAll(samples, smoothing, screenPerDoc)`),
     * then closed by a straight segment from the last point back to the first. The polygon is
     * filled NON-ZERO (JB-2.05a), so a loop drawn twice is still solid.
     * Fewer than 3 usable points → empty list (nothing is drawn).
     */
    fun outline(samples: List<PenSample>, smoothing: Float, screenPerDoc: Float): List<Pt>
}
```

## Decisions
1. **Brush format:** `engine = "fill"` is a new engine value, and `blend = "behind"` a new blend
   value (paint only where the layer is not already opaque — colouring under line art on the same
   layer). LEAD_RULINGS R3 says new constants need a version bump, so **BRUSH_VERSION becomes 2**;
   `BrushJson.encode` writes `"version": 2` ONLY when the brush uses `"fill"` or `"behind"` and
   `1` otherwise (the lowest version that can express the file, so ordinary brushes stay readable by
   older builds). `decode` accepts 1 and 2; a v1 file that says `"fill"` is refused ("needs a newer
   Joy Brush" is wrong here — say "engine \"fill\" needs brush version 2").
2. Fields a fill brush uses: `opacity.base` (the fill's opacity), `smoothing`, `blend`
   (`normal` | `erase` | `behind`), `color` jitter (per stroke only). Tip, size, spacing, grain and
   scatter are ignored — validation WARNS nothing about them (a fill pen re-brushed from a pencil
   keeps the pencil's fields in the file; that is expected).
3. `outline`: smoothing is the same `StrokeSmoother` path the stamp engine uses (batch mode — its
   streaming output equals its batch output), so a shape looks the same drawn as ink or as fill.
   Consecutive duplicate points are dropped. The closing segment is implicit (the list is NOT
   repeated at the end).
4. **Shipped preset** `joybrush/brushes/fill/brush.json`: id `fill`, name "Fill pen", engine
   `fill`, opacity 1, smoothing 0.3, blend `normal`, license CC0.
5. Pressure and tilt are recorded (the stroke is a normal recording) but do not change a fill. They
   come back into play the moment the stroke is re-brushed to a pencil — which is the point.

## Tests
1. A square drawn as 4 sides → a 4-corner-ish outline whose polygon area is within 2 % of the square's.
2. An open "C" → closed by a straight chord (last point then first point are consecutive in the loop).
3. Same samples + same smoothing → `outline` equals the centreline StrokeSmoother gives a stamp
   stroke (point for point).
4. < 3 points, or all points identical → empty.
5. Brush JSON: the fill preset round-trips; encodes with `"version": 2`; the ink preset still encodes
   `"version": 1`; a v1 file with `"engine": "fill"` is refused with a message naming the version;
   `blend: "behind"` validates; `blend: "sideways"` does not.
6. The shipped `fill/brush.json` decodes and validates with no problems.

**Command:** `./gradlew -p joybrush :core:jvmTest` — 0 failures (and every existing brush test).

## Do not
No rendering here (paint layers: JB-2.06b; ink layers: the Lead's JB-5.01). No change to stamp
behaviour. Do not rename any existing field or value.

## Definition of done
Tests pass (paste) · commit `JB-1.08a: fill pen brush` · ROADMAP row → 🟧 Built.

## Questions
