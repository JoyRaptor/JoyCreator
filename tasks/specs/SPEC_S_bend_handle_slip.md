# SPEC S — A crowded bend dot must slip to the OUTER side on release

**Difficulty: LOW-MEDIUM. Drawing and hit-testing only — no geometry, no model, no renderers.**
**Read `_RULES_READ_FIRST.md` first.**

## What JoyRaptor asked for

Originally, during the prototype rounds:

> "We need some way to see that bend grab handle when it gets pulled in too much."

And again on 2026-09-09, after confirming everything else in the transform tool now works:

> "The only thing that I think needs a little bit of work is... when bend handles get brought in
> too much, how they are to slip from an inner side buffer to an outer side buffer past a
> threshold **after finger lifts**."

## The problem

Bend dots are drawn **inboard** of the net point they drive — pulled toward the net's centre by
`bendInboardPx()`, with a hairline tether back to the true point
(`TransformOverlayView.bendLayout`, ~line 1074, and `drawBendNet`). That inboard offset exists so a
dot can never sit on top of a corner or edge glyph, which is what used to lock the user inside Bend
mode (SPEC M §1).

But when a net point is dragged **toward the centre**, its inboard dot is pulled further in still —
so the dots crowd together near the middle, overlap each other, and become impossible to tell apart
or grab. The offset that solves one problem creates another at the other end of the range.

## The behaviour to build

**Past a threshold, the dot flips to the OUTER side of its net point instead of the inner side.**
The tether still points at the true net point, so the reading stays unambiguous — the dot has
simply moved to the other end of it.

Three requirements, and the third is the one JoyRaptor named explicitly:

1. **Threshold, with hysteresis.** Flip to outer when the point is closer to the reference than
   some distance; flip back to inner only at a *larger* distance. Without hysteresis a dot sitting
   exactly on the boundary will chatter between the two sides.
2. **The reference is what `bendLayout` already uses** — the centre handle on an odd-sided net,
   otherwise the quad's centroid. Do not invent a second one.
3. **THE SWAP HAPPENS ON FINGER LIFT, NEVER MID-DRAG.** This is the whole point. A dot that jumps
   to the other side of its own net point while the finger is on it is worse than a crowded one —
   the picture stops following the finger and the user loses their place. Hold the current side for
   the duration of the gesture and re-evaluate once, on release.

Follow the same pattern the rest of this view uses: `bendDragIndex >= 0` marks a live bend drag,
and the existing gesture-end path is where the re-evaluation belongs.

## Do not

- Do not move the net point itself. **This is a drawing and hit-testing change only** — the mesh
  pose, `MeshPoseTrack`, and everything in `transform/mesh/**` are untouched.
- Do not change `bendInboardPx()`'s magnitude to paper over the crowding. The fix is the side, not
  the distance.
- Do not reintroduce the SPEC M bug: an outer-side dot must still never land on a corner or edge
  glyph. If flipping outward would put it under a structural handle, that handle still wins the
  tap (the tie-break M established).

## Acceptance criteria

1. Drag several net points toward the centre until they crowd: each dot flips outward on release
   and the group is legible and individually grabbable again.
2. Nothing moves under the finger during a drag — the side is fixed for the whole gesture.
3. A dot near the threshold does not chatter when nudged back and forth across it.
4. An outer dot never sits on a corner or edge glyph, and structural handles still win a contested
   tap.
5. The picture is unchanged — the same bend renders identically before and after this change.
   `project.json` is unaffected.
6. `./gradlew assembleDefaultDebug --console=plain` → BUILD SUCCESSFUL, and specr 13/13, specq 7/7,
   mesh 66/66, spech 36/36, bakepop, escape, pinbudget, speck, flip, rotation, preview-parity,
   frame-parity, persist-lint all stay green.

## Deliver

The threshold and hysteresis values you chose and why; where the on-release re-evaluation happens;
device screenshots of a crowded net before and after; confirmation the rendered picture is
unchanged. Build verdict; compile-verified vs device-verified.
