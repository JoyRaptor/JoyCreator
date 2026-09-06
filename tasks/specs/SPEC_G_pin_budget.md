# SPEC G - Stop trivial edits from running out of corner-pin budget

**Difficulty: MEDIUM-HIGH. One idea, applied in one place. Read `_RULES_READ_FIRST.md` first.**

## The complaint, verbatim (JoyRaptor, 2026-09-06)

> "I do not understand how I am possibly running out of a budget simply by rotating and flipping
> an image. Makes no sense. Even when I did free transform on a corner, I nudged it maybe 5% of
> the height of the overlay object... I am doing trivial edits. And a flip should bake, or be
> reset once it's flipped. Having the app randomly not do trivial edits because of some
> mysterious budget that doesn't make sense to common users will just read as broken. We need
> something other than an 'I can't do that, Dave' error that will just piss people off."

**He is right, and the arithmetic proves it.** This is not a user misunderstanding to be papered
over with a better error message. An error message is explicitly NOT the deliverable.

## Why it happens - the numbers

Every distortion is stored as four corner offsets in units of the picture's own size, capped at
`CornerPin.MAX_OFFSET = 2f`. `CornerPinTransformHost.writeQuad` REFUSES (silently, returning
false) when any offset would exceed it.

The tool offers gestures whose natural magnitude is a large fraction of that cap **on a picture
with no distortion at all**:

| Gesture, starting from an UNDISTORTED picture | Offsets it writes | Budget used |
|---|---|---|
| Flip horizontal (`flip()`: `dx' = s - dx`, `s = +-1`) | 1.0 | **50%** |
| Fold over an edge (reflect the quad across that edge) | 2.0 | **100%** |
| Nudge one corner by 5% | 0.05 | 2.5% |

So one fold spends the entire budget and the second one is refused. One flip spends half. The
user's actual authored distortion - the 5% nudge he cares about - is noise next to the cost of
operations that are not distortions at all.

## THE INSIGHT (JoyRaptor's, and it is the right one)

> "A flip should bake."

A flip, a fold, a rotation and a scale applied to a rectangle all produce a **parallelogram**.
A parallelogram is fully describable by the object's EXISTING fields - centre, size, rotation,
and a mirror - and needs **no corner pin at all**. Only a genuine perspective distortion (a
non-parallelogram: a trapezoid, a keystone) actually requires the pin.

Today all of it is crammed into the pin, so operations that carry no distortion still consume
distortion budget.

## The task

**After every committed gesture, decompose the quad and keep only what is genuinely a
distortion in the pin.**

In `CornerPinTransformHost`, at commit time (NOT during the drag - see the traps):

1. Take the committed quad.
2. Extract the affine part: centre, uniform/per-axis size, rotation, and mirror. Write those to
   the object's existing transform fields.
3. Whatever remains - the deviation from a parallelogram - is the residual pin. For a flip, a
   fold, a rotate or a scale, **the residual is exactly zero and the pin is cleared.**
4. The picture on screen must not move by one pixel across this operation. It is a change of
   REPRESENTATION, not of pose.

### The mirror

There is no mirror field today: `flip()` is deliberately expressed as a pin permutation. Baking a
flip therefore needs somewhere to put it. Two options - pick by what BOTH renderers can already
draw, and say which you chose and why:

- **Negative `scaleX` / `scaleY`.** Check `TextOverlayItem.setScaleX/setScaleY` for clamping, and
  check that the preview View path, the GL Pip path and `ImageOverlayDraw` all handle a negative
  scale identically. If any one of them does not, do NOT choose this.
- **A `mirrorX` / `mirrorY` boolean pair**, persisted sparsely (absent = false, so existing
  projects re-save byte-identically) and applied in all three renderers.

## Traps

1. **Do not normalise mid-drag.** The offsets must stay stable while the finger is down or the
   picture will crawl under it. Normalise on commit only.
2. **Do not change what is on screen.** Round-trip test: for a quad, decompose then recompose and
   assert the four corners return to within a pixel. Put that in the JVM harness -
   `TransformQuad` has zero imports, so this is testable off-device.
3. **Keyframed pins.** If any of the eight pin tracks has keyframes, the pose is animated and
   baking a single static affine would destroy the animation. **Skip normalisation entirely when
   the item has pin keyframes** and say so in your report.
4. **Preview/export parity.** The mirror must be drawn identically by the preview View path, the
   GL Pip path (`TextOverlayLayer.buildPip`) and `export/ImageOverlayDraw`, from one shared
   definition. This is the parity rule in the rules file and it is the most likely thing to go
   wrong here.
5. **Rotation winding.** SPEC A made rotation storage raw and un-normalised (720 != 0). If baking
   a fold adds 180 degrees to the rotation, ADD it - never fold the result into [-180, 180].

## Acceptance criteria

1. From an undistorted picture: flip, flip, fold, fold, fold, flip - all succeed, and the
   pin offsets afterwards are still at or near ZERO.
2. A 5% corner nudge stores ~0.05, not 1.05.
3. The picture does not move by one pixel when a gesture is normalised.
4. A genuine perspective distortion (a real trapezoid) still works and still lives in the pin.
5. An object with pin KEYFRAMES is left alone entirely.
6. An untouched project previews and exports byte-identically.
7. `bash tools/jvm-harness/run-*.sh` for the transform harnesses stay green, plus the new
   decompose/recompose round-trip test.
8. `./gradlew assembleDefaultDebug --console=plain` -> BUILD SUCCESSFUL.

## If, and only if, a refusal is still possible after this

A refusal should never be a dead gesture. Prefer, in order: (a) let it succeed - after
normalisation almost everything can; (b) clamp so the picture keeps following the finger to the
limit; (c) only as a last resort, a brief non-modal hint. Never a dialog, and never silence.

## Deliver

Where you normalise and why that point is safe; how you represent the mirror and the evidence
BOTH renderers draw it the same; the round-trip test; the keyframed-pin skip; before/after
offsets for the flip-flip-fold-fold sequence; the build verdict; compile- vs device-verified.
