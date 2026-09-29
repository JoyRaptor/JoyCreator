# JB-2.16a — Size & opacity by dragging, and a nudge that scales with zoom (the maths)

| | |
|---|---|
| **Tier** | T2 |
| **Status** | see ROADMAP.md |
| **Depends on** | JB-0.03b (size limit 4096), JB-2.02 (zoom) — Built |
| **Owner area** | NEW `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/tool/SizeOpacityDrag.kt`, NEW `.../tool/Nudge.kt`, NEW `.../commonTest/.../tool/SizeOpacityDragTest.kt`, `NudgeTest.kt` |
| **Estimated size** | ~120 lines + ~140 lines of tests |

## Goal
"Values change by dragging on the control" (blueprint §3.5): drag on the brush swatch to change size
and opacity; the three-finger swipe (JB-3.08a) uses the SAME mapping so both feel identical. And the
owner's "nudge scales with zoom": one nudge moves the same distance on screen.

## Contract
```kotlin
package cc.joycreator.joybrush.core.tool

/**
 * One drag gesture, from finger-down to finger-up. Sizes are DOCUMENT px (LEAD_RULINGS R10); the
 * preview circle is drawn in screen px. Construct at finger-down; call [move] with the TOTAL offset
 * since finger-down (screen px, +x right, +y DOWN).
 */
class SizeOpacityDrag(val startSize: Float, val startOpacity: Float, val screenPerDoc: Float, val density: Float = 1f) {
    enum class Axis { NONE, SIZE, OPACITY }
    val axis: Axis                       // NONE until the lock decision, then fixed for the gesture
    val size: Float                      // current, document px
    val opacity: Float                   // current, 0.01..1
    val previewRadiusScreenPx: Float     // size / 2 × screenPerDoc
    fun move(dxScreen: Float, dyScreen: Float)
}

object Nudge {
    /** Document px moved by one nudge: 1 screen px (10 when [big]) at the current zoom. */
    fun stepDoc(screenPerDoc: Float, big: Boolean = false): Float
}
```

## Decisions
1. **Axis lock:** nothing changes until the finger has travelled `12 × density` screen px from its
   start. Then the axis is whichever of |dx|, |dy| is larger (ties → SIZE) and it NEVER changes
   during the gesture. Changing both at once is what makes such controls fiddly.
2. **Size:** exponential, so small brushes get fine control and big ones move quickly:
   `size = startSize × 2^((dx − lockDx) / (160 × density))` — every 160 dp to the right doubles it,
   to the left halves it. `lockDx` = dx at the moment of locking, so the value does not jump when
   the lock engages. Clamped to 0.5 … 4096 (BrushValidate's limit).
3. **Opacity:** linear, UP is more: `opacity = startOpacity − (dy − lockDy) / (300 × density)`,
   clamped 0.01 … 1 (never 0 — an invisible brush looks broken).
4. The axis not locked stays at its start value.
5. Non-finite inputs are ignored (the previous values stay). A non-finite or ≤ 0 `startSize`
   starts from 1; `screenPerDoc` ≤ 0 or non-finite is treated as 1.
6. **Nudge:** `stepDoc = (big ? 10 : 1) / screenPerDoc` (same guard as 5). At zoom 4 a nudge is
   0.25 document px — that is the point: zoom in for fine moves.

## Tests
1. Under 12 px of travel nothing changes and axis is NONE; at 13 px along x, axis = SIZE.
2. SIZE: +160 px beyond the lock doubles, −160 halves, −10000 clamps to 0.5, +10000 to 4096.
3. No jump at the lock: the size right after locking equals startSize (within 1e-4).
4. OPACITY: 150 px up beyond the lock from 0.5 → 1.0; 300 px down clamps to 0.01; SIZE unchanged throughout.
5. Once locked to SIZE, a later huge vertical move changes nothing.
6. density 2: the lock needs 24 px and doubling needs 320 px.
7. previewRadiusScreenPx at zoom 4 of a 10 px brush = 20.
8. Nudge: zoom 1 → 1, zoom 4 → 0.25, zoom 0.5 → 2; big at zoom 2 → 5; zoom 0 → 1 (guard).

**Command:** `./gradlew -p joybrush :core:jvmTest` — 0 failures.

## Do not
Touch no existing file. The swatch UI itself is JB-2.16 (needs JB-2.01).

## Definition of done
Tests pass (paste) · commit `JB-2.16a: size and opacity drag` · ROADMAP row → 🟧 Built.

## Questions

_(Builder: subagent of openrouter/stealth/space-bunny-alpha, 2026-09-29. Could not run anything.
I ran `:core:jvmTest`: **476 tests, 0 failures**, of which 12 are `SizeOpacityDragTest` and 4
`NudgeTest`. All four of its questions were mine to rule.)_

1. ✅ **"Travelled 12 × density" — which distance?** **Ruling: straight-line distance from the finger-down
   point** (`sqrt(dx² + dy²)`), not `max(|dx|,|dy|)`. A drag lock is about *intent*, and a person
   dragging diagonally has moved their finger 12 dp even when neither axis has. The two readings
   differ only on a diagonal, and the builder pinned it with a named test at `(9, 9)`. Provisional.
2. ✅ **The lock comparison is `>=`, not `>`.** Confirmed, and it is not a choice: spec test 6's "the
   lock needs 24 px" at density 2 is only true with `>=`. A test in the Lead's own spec outranks taste.
3. ✅ **Two guards the spec does not name: `density` and `startOpacity`.** **Ruling: keep both.** A zero
   density divides by zero in *both* mappings and would lock on the first pixel; a NaN opacity prints
   "NaN" on the swatch. Three of the builder's tests lock the behaviour in, and deleting them is the
   cost of being wrong here — which is the right trade. Provisional.
4. 🔴 **For the Lead, and it is the easy mistake to make: the direction of `screenPerDoc`.**
   `screenPerDoc` **is** `ViewTransform.zoom` (screen px per doc px), so the caller passes
   `view.zoom` and **not** `1f / view.zoom`. `previewRadiusScreenPx = size / 2 × screenPerDoc` with
   `size` in document px, as R10 requires. Recorded on JB-2.16's row so the UI half cannot invert it.
5. 🔴 **For the Lead — a maintenance trap, the same shape as `BrushValidate.paramsOf`.** The builder
   needed the 4096 px brush-size limit and `BrushValidate.MAX_SIZE_PX` is `private`, so
   `SizeOpacityDrag.MAX_SIZE = 4096f` is a **local copy of a number that already exists**. Nothing can
   catch the two drifting apart: no test can assert "these two constants are equal" without reflection,
   and the build forbids new dependencies. If the limit ever moves in one place, the other silently
   disagrees and the symptom is a size slider that stops at a number nobody wrote. The structural fix
   is to make the constant visible, which is `BrushValidate.kt` and therefore not this spec's file.
