# The owner's test stroke (2026-11-14): the reference every brush preview follows

Two drawings by the owner, made in Infinite Painter as the model for JoyBrush's one standard test stroke
(job card [BRUSH-UI](../../../jobs/BRUSH-UI_one_brush_editor.md), item 3). They replace the first test stroke
landed at a3b1c0175, which he saw and rejected: "it just won't do."

- `owner_test_stroke_A.png`: the stroke to follow.
- `owner_test_stroke_B_heavier_end.png`: the same, weighted with a smaller start taper and a bigger end taper,
  because a brush that grows with pressure needs more room at the end.

## What the test area holds, in his words (11-14)

1. **Swatches** (top left): a couple of short marks "at the same angle, but either different tilt or different
   pressure or combination of the two."
2. **Straight lines** that go off the box diagonally, up and to the right (three or four, thinning).
3. **Three colour stripes** (pink, cyan, yellow in his drawing) that the stroke passes through, "with whatever
   color you have selected, going through all three. So you can see how it picks up smears or blurs or other
   inks."
4. **The curve, exactly as drawn**: it starts at its thinnest, "as light as possible", lower left; light getting
   heavier as it goes; a wiggle (S-turns) through the colour stripes; then a big loop that **crosses over
   itself**; ending in a hook. Along it:
   - "maximums of tilt to minimums of size, and maximum of pressure to minimum of pressure, all in one line";
   - tilt either constant or varying across the stroke's length;
   - **concave and convex** turns;
   - what the brush does **where it crosses itself**.
   "Each one of these elements is very important. I try to put all in a single stroke."
5. From 11-13 and still standing: a sudden lift at the end rather than a gradual fade; the stroke rebuilds
   live while a slider is dragged.

## How to use the drawings
Trace the curve's path from drawing A as normalised points (the box is about 4.3 : 1, as drawn). Pressure and
tilt along it are read from the mark's width and edge in his drawing; where unclear, follow the list above.
Keep the drawing as the visual check: our stroke rendered with a comparable brush should read like his.
