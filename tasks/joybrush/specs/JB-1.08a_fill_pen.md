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

## Orchestrator rulings (2026-09-29)

**Q1 — ruled: the preset is written, and `size.base` is 8.** Both files the builder could not reach
were written by me: `joybrush/brushes/fill/brush.json` and the `fill` line in `brushes/index.txt`.
The existing `ShippedBrushFilesTest` now covers the folder like the other two. On the unspecified
number: **`size.base = 8`**, the builder's value, kept because its own test already pins the file and
changing the number would change a test for no gain. The reasoning, so a later reader does not
"tidy" it: the fill engine NEVER reads size — the key is there only because `BrushPreset.size` has no
default and the file cannot decode without it, and because the size rule (`0 < base ≤ 4096`) always
runs and the value must be legal. **What I will not do is skip the size rule for `engine: "fill"`**,
because that is a validator change that would leave one engine's files unvalidated for a cosmetic
reason. Low-risk, ruled provisionally; the Lead may change the number freely.

**Q2 — ruled: (b), it is THE `BRUSH_VERSION`.** Two constants both meaning "the newest version this
build reads" is exactly the drift the R3 freeze exists to prevent, and the workaround's own KDoc
admitted it was one line of `EnumFreezeTest` away from being unnecessary. Done as a single edit,
which is what R3 asks for:
- `BrushJson.kt`: `BRUSH_VERSION` is now `2`; `BRUSH_VERSION_FILL` is gone, renamed into it.
- `BrushPreset.kt`: `version`'s default is now `BRUSH_VERSION` rather than a second literal `1`.
  This is the half that is easy to forget, and `EnumFreezeTest` asserts the two are equal for
  exactly that reason.
- `doc/EnumFreezeTest.kt`: pins `2` for both.

Note for the Lead: `encode` still writes `"version": 1` for an existing ordinary brush, because
`versionFor` only bumps a file that actually uses a v2 word. So this is a version bump without
re-writing every brush anybody already has.

**Q6 — not mine to fix, referred to the Lead.** The builder is right that R20's "only `stamp` and
`fill` on ink layers" rule is implemented NOWHERE: there is no engine↔`LayerKind` check in core at
all, and `FillPen` is deliberately layer-agnostic. That is a real gap against R20, but it is a
contract question about where the check belongs (a validator? a `Layer` op? the UI?) rather than a
missing line, so it goes to the Lead rather than being invented here.

---

## Questions

*Builder T2, 2026-09-29. Q1 is a blocker for part of this spec; Q2–Q4 are rules the builder had to
pick and the orchestrator should confirm or overturn; Q5–Q6 are notes.*

**Q1 — the shipped preset is outside the owner area, and one of its numbers is unspecified.**
`joybrush/brushes/fill/brush.json` and the `fill` line in `joybrush/brushes/index.txt` are not in
this builder's area (`core/…/brush/` only), so neither was written. The exact file content, verified
by the builder's own test and by the existing `jvmTest/ShippedBrushFilesTest` once it lands on disk:

```json
{
  "format": "joybrush.brush",
  "version": 2,
  "id": "fill",
  "name": "Fill pen",
  "engine": "fill",
  "size": { "base": 8 },
  "opacity": { "base": 1 },
  "blend": "normal",
  "smoothing": 0.3,
  "license": "CC0"
}
```

Decision 4 does not say what `size.base` is, and the fill engine never reads it — but
`BrushPreset.size` has no default, so the key must be there for the file to decode at all, and the
size rule (0 < base ≤ 4096) always runs, so it must be legal. **8 is a guess; rule on it** (or say a
fill preset should carry `"size": {"base": 1}` as an obvious placeholder, or that the size rule
should be skipped for `engine: "fill"` — that is a validator change, not a file change).

**Q2 — `BRUSH_VERSION` did not move; a second constant did. Needs a ruling.**
`commonTest/doc/EnumFreezeTest.kt:81` pins `assertEquals(1, BRUSH_VERSION)` and that file is outside
this builder's area, so bumping `BRUSH_VERSION` to 2 would have turned the suite red in a file the
builder may not touch. As built, everything Decision 1 asks for on disk is true — `encode` writes 2
when, and only when, the brush uses `fill`/`behind`; `decode` reads 1 and 2 and refuses a v1 file
that uses either word; a file newer than 2 is still "from a newer Joy Brush" — and the *name* of the
constant that means "the newest version this build reads" is `BRUSH_VERSION_FILL = 2`
(`BrushJson.kt`). Two rulings possible:
(a) keep it (green suite, two constants); or
(b) make it THE `BRUSH_VERSION`: rename `BRUSH_VERSION_FILL` → `BRUSH_VERSION` in `BrushJson.kt` and
`BrushValidate.kt`, delete the old `const val BRUSH_VERSION = 1`, and update `EnumFreezeTest.kt:81`
to 2. If `BrushPreset.version`'s default moves to 2 with it, `MypaintImportTest.kt:247` moves too
(it asserts imported presets are version 1). (b) is what the spec's Decision 1 literally says; the
builder stopped short of it rather than edit outside the area.

**Q3 — "fewer than 3 usable points" is counted on BOTH sides. Orchestrator rule, confirm it.**
The contract says "fewer than 3 usable points → empty list", after saying consecutive duplicates are
dropped. Read on the smoothed list only, a two-sample flick down a 50 px line yields 51 collinear
points — a degenerate polygon of zero area, not an empty list. As built, `FillPen.outline` returns
empty when *either* the de-duplicated input has under 3 points or the de-duplicated output has under
3. A tap, a still pen and a 3-sample stroke of one place are empty under both readings; only the
two-sample-flick case differs, and there the conservative answer (empty) is the one that can never
paint a sliver. One line to reverse if the Lead disagrees.

**Q4 — the v1+`fill` refusal lives in `decode` AND is mirrored in `validate`. Orchestrator rule.**
Decision 1's sentence sits next to "decode accepts 1 and 2", and its parenthetical ("'needs a newer
Joy Brush' is wrong here") reads as "a different function's sentence from the validator's", so
`BrushJson.decode` throws `BrushException("engine \"fill\" needs brush version 2")` — exact message,
asserted by equality. `BrushValidate` carries the same sentence as one problem line, because a
preset built in Kotlin (or hand-edited) can reach validation without passing through `decode`. A
reviewer who expects the check in only one of the two should know both are there.

**Q5 — `BrushJsonTest` / `BrushValidateTest` do not exist.** The spec's owner area names additions to
them; the tests they would extend are all in `commonTest/…/brush/BrushTest.kt`, so that is where the
new format tests went. Two existing assertions there had to move from version 2 to version 3
(`unknownKeysAreIgnoredAndANewerVersionIsRejected`, `validationSaysOneThingPerRule`): version 2 used
to be the "from a newer Joy Brush" example and is now the fill pen's version.

**Q6 — R20's layer rule is not implemented here, and nothing in core implements it.** "Only `stamp`
and `fill` engines are allowed on ink layers; picking a `smudge`/`wet` brush while ink strokes are
selected is refused in words" is a brush-pick/layer concern with no home in this spec and no existing
function in core that pairs an engine with a `LayerKind`. `FillPen` is deliberately engine-agnostic
about layers, so the fill pen can be used on a paint layer too (JB-2.06b). Whoever owns the pick UI
needs to add the check; nothing in JB-1.08a blocks it.

