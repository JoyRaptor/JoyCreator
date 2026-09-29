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
