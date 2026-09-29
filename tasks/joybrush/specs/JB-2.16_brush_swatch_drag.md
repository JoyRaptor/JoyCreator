# JB-2.16 — Brush size and opacity by dragging the brush swatch, and nudge that scales with zoom

| | |
|---|---|
| **Tier** | T2 |
| **Status** | 📝 Draft spec — see ROADMAP.md |
| **Needs** | JB-2.01 (screen chrome — Draft), JB-2.16a (`SizeOpacityDrag`, `Nudge` — Built 🟩) |
| **Owner area** | NEW `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/BrushSwatchView.kt`; EDIT `joybrush-android/.../JoyBrushActivity.kt` (host the swatch in the cluster, wire the two listeners) |
| **Estimated size** | ~230 lines; tests are the maths's and already exist |

## Goal

Blueprint §3.5: *"**Values change by dragging on the control**, never by opening a panel. Drag off the
active colour swatch = instant colour picker; **drag on the brush swatch = size**. Any control that
has a useful drag gets one."*

JB-2.16a built the arithmetic and it is reviewed and green. **This row is the swatch, the circle you
can see while you drag, and the two places the number has to land.** There is almost no maths here,
which is exactly why it goes wrong: the two places a size can be written (the view's fallback
`Brush` and the brush FILE's `size.base`) have different units in the reader's head and only one of
them is right.

## 🔴 The ruling this row implements, verbatim

**LEAD_RULINGS R10** (from JB-2.02's Q5):

> *"brush size in DOCUMENT px is correct (a stroke must look the same when you zoom back out). For
> JB-2.16: **the size control shows the on-screen circle at its true screen size (radius × zoom)
> while you drag, and writes `size.base` in document px (screen px ÷ zoom).**"*

And JB-2.16a's own Q4, which is a trap in the direction of the wrong answer:

> *"`screenPerDoc` **is** `ViewTransform.zoom` (screen px per doc px), so the caller passes `view.zoom`
> and **not** `1f / view.zoom`."*

## Contract

```kotlin
package cc.joycreator.joybrush.androidkit

import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.tool.SizeOpacityDrag

/**
 * The brush swatch: a circle that is the brush's TRUE width on screen, draggable.
 *
 * R10: the circle's radius is `size.base / 2 × view.zoom` — SCREEN px — and the value written back
 * is DOCUMENT px. A builder who divides where the maths multiplies produces a swatch that shrinks
 * as you zoom in, which looks like a bug in the brush and is a bug in the control.
 */
class BrushSwatchView(context: Context) : View(context) {

    /** The brush file's size in DOCUMENT px. Written on every drag frame, read at construction. */
    var sizeDocPx: Float
        set(v) { field = v; invalidate() }

    /** 0.01..1. Read for the swatch's fill. */
    var opacity: Float
        set(v) { field = v; invalidate() }

    /** `view.zoom` — screen px per document px. NEVER its inverse (JB-2.16a Q4). */
    var screenPerDoc: Float
        set(v) { field = v; invalidate() }

    /** The brush's colour, for the fill and the ring. ARGB. */
    var brushArgb: Int

    /** Called on every frame of the drag, with the DOCUMENT-px size and the opacity. */
    var onSizeChanged: ((Float) -> Unit)? = null
    var onOpacityChanged: ((Float) -> Unit)? = null

    /** True while a drag is in progress — the cluster uses it to keep the ring visible. */
    val dragging: Boolean

    /** The maths, exposed so the ring's radius and the tests read the SAME number. */
    val previewRadiusScreenPx: Float
}
```

## Decisions

1. **One drag, two axes, JB-2.16a's `SizeOpacityDrag` unchanged.** Construct it at `onTouchEvent`
   ACTION_DOWN with the CURRENT size in document px, the current opacity, `screenPerDoc =
   screenPerDoc` (= `view.zoom`) and the display density; feed it the **total** offset since
   finger-down; read `size`, `opacity` and `previewRadiusScreenPx` back each frame. **Do not
   reimplement the lock, the exponential or the clamps** — they are 16 green tests and Decision 1
   of that spec says the axis never changes mid-gesture.
2. **The ring is the brush's true on-screen size: `previewRadiusScreenPx` at R10's own
   `size / 2 × screenPerDoc`.** Drawn as a 1 dp `DRAWER_INK` ring outside the swatch's filled
   circle, with the ring *clipping* at the swatch's bounds when the brush is larger than the swatch
   — because a 400 px brush on a 40 dp swatch would otherwise draw a ring the width of the screen.
   The clipping is a view concern and it is the only drawing decision in this spec.
3. **The value written to `size.base` is `drag.size`, verbatim — document px, no conversion at the
   write site.** The multiplication by zoom happens *once*, in the ring's radius. A builder who
   "helpfully" converts at the write site is the exact bug this spec exists to prevent, and test 3
   is aimed at it.
4. **The hard-coded `JbCanvasView.Brush` path takes the same number.** `brush.sizePx` is document px
   too (JB-2.02 Q5's ruling, and the reason a "12 px" brush is 48 screen px at 4×). So when
   `canvas.preset == null` the swatch writes `canvas.brush = canvas.brush.copy(sizePx = drag.size,
   opacity = drag.opacity)`, and when a file is set it writes into a **copy** of the preset with
   `size.base` and `opacity.base` replaced. **Neither path scales by zoom, and both are the same
   number** — which is why test 4 asserts the two paths agree at zoom 0.25 and at 4.
5. **The brush file is not edited in place.** `BrushPreset` is a `@Serializable` data class with
   `val`s; the swatch holds a copy and the Activity assigns `canvas.preset = copy`. A shared preset
   object is the one that lives in `BrushLibrary`, and mutating it would change the *next* document
   to use that brush. (This is the same class of bug JB-1.05b's `smoothingFromUser` was invented to
   avoid.)
6. **The opacity drag is on the SAME swatch, vertically** — "drag up is more opaque", per
   `SizeOpacityDrag` Decision 3. There is no second control and no panel, because blueprint §3.5
   says *never by opening a panel*.
7. **A TAP on the swatch does nothing.** No panel, no dialog, no reset. A tap that is not a drag
   leaves every value exactly where it was. (The *colour* swatch is where the picker lives —
   JB-2.03a — and the two swatches must not be confused by a person with a thumb.)
8. **Nudge scaled to zoom is part of this row because it is the same number.** `Nudge.stepDoc(
   screenPerDoc, big)` — 1 screen px, 10 for the big one, ÷ `view.zoom`. The row's "nudge scaled to
   zoom" in the board text is this: the row owns making the value *reachable*, and the caller
   (JB-2.02b's NUDGE finger mode, or a key) is not in this spec's owner area. **The swatch
   therefore also hosts the two nudge buttons** in the cluster, which call `Nudge.stepDoc` and hand
   the document-px delta to whoever is listening. Testable as a one-liner; listed below.
9. **The swatch shows the FILL PEN's colour and size like any other brush.** A fill pen's `size.base`
   is 8 and the engine ignores it (JB-1.08a's ruling Q1), so the swatch's ring is drawn **greyed
   with the words "no size"** rather than a circle that lies. Small, but it is a control showing a
   number that does nothing.

## Tests

The maths is JB-2.16a's and is already covered (16 tests, 🟩). What this row adds is the **write
path**, and it is tested in the only place a write path can be tested honestly:

1. **Wiring assertions in `JoyBrushActivity` / `BrushSwatchView`, checked on the device and by three
   source-level tests in `:core:jvmTest` (a `commonTest` that reads the two `.kt` files as text,
   the same technique JB-2.04 uses for its no-hard-coded-list check):**
   - `theSwatchPassesZoomAndNotItsInverse`: `BrushSwatchView.kt` contains `screenPerDoc` and the
     string `1f / ` nowhere near an assignment to it. **This is JB-2.16a Q4 turned into a test.**
   - `theWriteSiteDoesNotScale`: no multiplication or division by `screenPerDoc` appears between
     `drag.size` and the `onSizeChanged` call.
   - `theSwatchDoesNotReimplementTheDrag`: `BrushSwatchView.kt` contains no `pow`, no `2.0.pow`, no
     `coerceIn(0.5f` and no `LOCK_TRAVEL` — it calls `SizeOpacityDrag` and nothing else.
2. **The ring's radius** is asserted through the same property the maths exposes, at three zooms
   (0.25, 1, 4) for a 10 px brush: 1.25, 5, 20 screen px. (JB-2.16a test 7 covers zoom 4 only; the
   other two are the directions the rounding is easy to get wrong.)
3. **Both write paths agree** (Decision 4): at `screenPerDoc = 0.25` and at `4`, the number that
   reaches `canvas.brush.sizePx` equals the number that reaches `preset.copy(size.base)` for the same
   drag, **exactly** (`assertEquals`, not a tolerance — they are the same `Float`).
4. **No preset mutation**: after a drag, `canvas.preset?.size?.base` on the *library's* object is
   unchanged; only the copy handed to the canvas moved.
5. **A tap changes nothing** (Decision 7): down then up inside `TAP_SLOP_PX`, with no `move`, and
   `onSizeChanged` was called zero times.

**Command:** `./gradlew -p joybrush :core:jvmTest` (for 1–4's source-level and maths coverage) and
the watcher green for the rest. The device assertions are the owner check below.

## Owner check (Note 9)

With Ink (6 px) selected, drag the swatch right → the ring grows; drag up → the stroke is more
opaque. Zoom to 400 % → the ring is four times the size on screen and the NEXT stroke is exactly the
same width in document terms as it was at 100 % (draw the same stroke at both zooms and they
overlay). Zoom out to 25 % → the ring is tiny and the stroke is unchanged. Select the fill pen → the
ring is greyed and says "no size". Drag left a long way → it stops at 0.5 px; drag right a long way
→ it stops at 4096. Tap the swatch → nothing happens. Nudge buttons: at zoom 1 one nudge moves the
selection 1 screen px; at zoom 4, a quarter of a document px (zoom in and it is visibly finer).

## Do not

- **Do not touch `SizeOpacityDrag` or `Nudge`.** Both are Built and 🟩; JB-2.16a Q5's old
  "the constant is copied" worry is closed (`MAX_SIZE` IS `BrushValidate.MAX_SIZE_PX`).
- **Do not invert `screenPerDoc`.** It is `view.zoom`. Test 1 exists.
- Do not write your own lock/clamp/exponential. Decision 1 and test 1-3.
- Do not add a size panel, a numeric field, or a long-press menu to the swatch. Blueprint §3.5: the
  drag IS the control.
- Do not mutate a `BrushPreset` you did not copy (Decision 5).
- Never run gradle on the owner's PC.

## Definition of done

- [ ] tests pass (paste)
- [ ] `git status --short` shows only owner-area files
- [ ] watcher green
- [ ] owner check noted
- [ ] committed `JB-2.16: brush swatch drag`
- [ ] ROADMAP row → 🟧 Built

## Questions

_(Spec writer, `openrouter/stealth/space-bunny-alpha`, 2026-09-29. R10 and JB-2.16a Q4 are the whole
of the hard part and both are Decisions here.)_

### 🔴 For the Lead

1. **Decision 9 — the fill pen's size control shows "no size".** JB-1.08a ruled that a fill
   brush's `size.base` (8) exists only because the file cannot decode without it and the size rule
   always runs; the engine never reads it. So a swatch showing a draggable ring for the fill pen is
   **a control that visibly does nothing**. I have ruled it to grey the ring and say so. The
   alternative — let the fill pen's ring drag do nothing at all — is worse, and the other
   alternative (giving the fill pen a real size, which would mean a margin/inset on the shape) is
   **JB-1.08a's contract to change, not this row's**. Confirm, or say the fill pen's swatch should
   be replaced by something else entirely (a "fill / behind / erase" chip, which is what it
   actually does).
2. **The opacity axis is vertical and shares the swatch with the size axis.** JB-2.16a locks the
   axis at 12 dp of travel, so this works, but a person who wants a bigger brush and a lighter one
   has to do two gestures with a lock between them. The alternative used by Infinite Painter (which
   the blueprint cites approvingly for the three-finger swipe) is a single axis at a time chosen by
   the gesture — which is exactly what the lock already is. **So this is settled by the maths, and I
   only want it on the record that the double-drag is a consequence and not an oversight.**
3. **Depends on JB-2.01, which is Draft.** The swatch lives in the control cluster and moves if
   2.01's ruling moves the cluster. **This row could be built as a standalone view now** and hosted
   later — the only coupling is where it sits. If you want the runway filled rather than the
   ordering perfect, say so and this row's owner area can be re-scoped to the view alone.

### Low-risk, ruled provisionally

4. **A tap on the swatch does nothing** (Decision 7). No panel, no reset, no picker — the picker is
   on the COLOUR swatch (JB-2.03a) and the two must not collide under a thumb.
5. **The ring clips at the swatch's bounds** when the brush is larger than the swatch (Decision 2).
6. **Both write paths get the same `Float`, no tolerance** (test 3).
7. **The nudge buttons are hosted but their binding is not this row's** (Decision 8) — the
   `NUDGE` finger mode (JB-2.02b) or a hardware key is the caller.
