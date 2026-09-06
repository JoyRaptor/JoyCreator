# SPEC I — Two fingers drive the SELECTION, not whatever is under them

**Difficulty: MEDIUM. Touch routing only — no geometry, no renderers, no storage.**
**Read `_RULES_READ_FIRST.md` first.**

## What JoyRaptor asked for, verbatim (2026-09-05)

> "Another thing that I miss is when I do a two finger pan or rotate but my two fingers are off,
> the image doesn't transform. This is valuable for when I want to drag on something that maybe is
> a little bit hard to hit because it's too small. It's nice to be able to just tap and drag on it
> and use two fingers to zoom in so that it's a manageable size. Currently, my two fingers have to
> be actually both landing on the object... And if I try to do a two finger move gesture when the
> font is selected, the two fingers match and both land on an image with a new helper that eats the
> gesture, and it goes to the picture instead. Tapping to select something or tap dragging to
> select something is good. But for doing two finger at the same time, that should affect the thing
> that was previously selected, not select a new thing under you because it was a two finger tap or
> a two finger drag, not a one finger tap and a one finger drag."

It makes sense and it is the right rule. The contract:

- **ONE finger = selection.** A tap or tap-drag may select whatever is under it. Unchanged.
- **TWO fingers = manipulation of the CURRENT selection**, wherever the fingers are — inside the
  object, outside it, or over a completely different object. Two fingers must **never** change what
  is selected.

## Why it matters

It is how you grab a small object: select it with one finger, then pinch from well outside it to
bring it to a workable size. Today both fingers must land on the object, so a small object cannot
be pinched at all — and a two-finger drag that crosses another object hands the gesture to that
object instead.

## Where the code is — verified 2026-09-06

- `overlay/PreviewHandlesOverlay.onTouchEvent` — already contains a partial version of this idea:
  `nearEnoughToPinch(...)` holds the stream on a near-miss so `ACTION_POINTER_DOWN` can arrive.
  **Read the comment there first**; it documents a 2026-08-12 bug of exactly this family. "Near
  enough" is the part that is too strict.
- `transform/TransformOverlayView` — owns two-finger scale+rotate about the finger midpoint for
  images, text and PiP. This is where the selected object's pinch lives now.
- `FaditorEditorActivity.previewSelectionSource()` (~line 24826) — `selectAt` is what a DOWN
  consults. It must not be consulted for a second finger.

## The task

Route by pointer count, not by hit test:

1. A DOWN with an existing selection begins a *candidate* gesture. If a second pointer arrives
   before the gesture resolves, it is a **pinch on the current selection**, regardless of where
   either finger is — no `selectAt`, no retarget.
2. A DOWN that resolves as a single-finger tap or drag keeps today's behaviour exactly.
3. With NOTHING selected, two fingers do nothing. Do not invent a rule.

## Traps

1. **Do not set `PreviewHandlesOverlay` to GONE.** It is the only view that answers "what did I just
   tap", and it was only just fixed (2026-09-06) so that it exists *before* the first selection.
   Null its *target* if it must not draw.
2. **Do not regress the mid-gesture handoff.** `SelectionSource.handoffGesture` forwards a
   still-running one-finger stream to the transform surface so the first tap-drag on an unselected
   image is not a dead drag. That is a ONE-finger path and must keep working.
3. Two sibling views both reading MotionEvents over the preview is the bug
   `PreviewHandlesOverlay`'s class doc was written about. Exactly one owns a gesture at a time.

## Acceptance criteria

1. Select a small object, put both fingers well outside it, pinch — the SELECTED object scales and
   rotates about the finger midpoint.
2. With a text box selected, a two-finger drag whose fingers land on an image still moves the
   TEXT. The selection does not change.
3. One-finger tap-select, tap-drag-select and the mid-gesture handoff behave exactly as today.
4. Works for image, text and PiP selections.
5. `./gradlew assembleDefaultDebug --console=plain` → BUILD SUCCESSFUL.

## Deliver

Where the pointer-count decision is made; what you did about `nearEnoughToPinch`; proof that
one-finger selection and the handoff are unchanged; the build verdict; compile-verified vs
device-verified.
