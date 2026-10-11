# R12: Concepts' watercolour pack, as the owner measured it (2026-10-11)

The owner tested Concepts' watercolour brush pack (about 20 brushes) in black on white, on purpose, to see the machinery.
He called **H2, H3, F1 and A1** "fantastic results". His screenshots stay with the vector specialist and are not in the repo:
they show Concepts' own textures. This note is the evidence; [OWNER] marks his words, [INF] marks my reading.

## What he saw

| Observation | Brush | Tag |
|---|---|---|
| "An insane small to large ratio": hair-width strokes to whole washes with one brush | H3, M1, A1 | [OWNER] |
| Edges look like "one, or a series of textures as alpha … multiplying them together to get the edge" | H3 | [OWNER] |
| "A second watercolour wash texture so full saturation doesn't just go black, it reveals a different wet splotchy paper texture" | H3 | [OWNER] |
| The fill texture repeats, and he found its tile bounds; the stroke itself shows no repeat | H3 | [OWNER] |
| Each brush has its own sample set **and** its own tiling watercolour paper texture | all | [OWNER] |
| "Rotating the stroke rotates the wash texture AND the brush textures" | H2 | [OWNER] |
| "Scaling the stroke maintains the size of all samples, but sample patches fall off"; "samples slide along the path to best fit", so nudging the middle shifts a sample slightly | H2 | [OWNER], "all very good behaviour" |
| New strokes overlay as separate layers; alpha keeps the overlaps convincing | all | [OWNER] |
| F2 and A1 are harder-edged: "india ink on a wet brush" | F2, A1 | [OWNER] |
| A "dry brush" needs a directional bristle texture, "a long stroke bent around a vector line" | (wish) | [OWNER] |

## How Concepts does it: the owner's model (2026-10-11, correcting my first reading)

My first reading (a texture "skin" bent along a ribbon, with decorative end caps) was WRONG. The owner, with the brush
picker as evidence (the Waterful pack shows each brush's dab swatches: Watercolor A1, B1–B4, C1, D1, E1, E2, F1, F2, G1):
- **Dabs, not a ribbon.** Each brush is a series of semi-round textured dab samples, rotated (random per dab), "multiplying
  over" each other so the inside goes dark and the edge is an uneven, undetectable pattern. A1's hook is "a jostle in the
  brush line", not a decorative sample. [OWNER]
- **Several dab shapes, chosen by pressure and angle.** From hard-edged to soft and bleeding: light and upright draws a
  small narrow dab "as if it's the tip of the brush"; hard and steep gives "big watery brushes with lots of runny
  textures". [OWNER]
- **Darker-colour combine.** Probably not multiply inside a stroke: "a darker colour style blend mode where it just
  defaults to the darker colour", so overlapping dabs never pile up to black. [OWNER]
- **The wash texture is the stroke's alpha.** "That whole thing goes through and has an alpha over the entire thing, which
  is the wash texture … so the darkest it has is the alpha instead of a full saturation." [OWNER] It turns with the stroke
  because it lives in the stroke's own frame (his rotation test). [INF]
- **Each stroke then composites as its own layer** over the others, with that alpha. [OWNER]

The owner is not against ribbon/skin brushes as a separate kind; they are not how these brushes work.

## Patent check (R8) [INF]

- Clear of US 8,917,282/283: no polygons with moving vertices, no water polygons.
- Clear of US 8,605,095: one ribbon per stroke, never per bristle, even for dry-brush streaks.
- Stay clear of US 7,777,745 (outline-band edge darkening, to 2028): any edge darkening comes from the fragment
  shader's distance-across value |v| in one pass, which R8 names as the allowed route.
- Prior art: skeletal strokes (Hsu & Lee 1994), Illustrator art and pattern brushes, texture-mapped brush ribbons in
  NPR literature.

## What Joy Brush builds from this: sampled watercolour line brushes (VECTOR_RENDER_DESIGN §9)

Every piece is the stamp engine, extended, so these replay exactly and stay editable lines:
1. **A dab sample set** per brush: several image tips (an array texture), each dab picking one by seed, pressure and
   tilt, and rotated by the existing angle jitter.
2. **A "max" combine** in the stroke buffer (the darker dab wins), beside today's wash and build-up.
3. **A wash texture as the stroke's capacity**: the committed stroke is the dab density times the wash alpha, sampled in
   the line's own frame (stored with the line, turned and moved with it). The pencil's tooth-capacity mode is the same
   idea with the paper as the capacity.
4. **Composite per line** with its operator (D8a): multiply for a glaze, normal for ink.
5. **Samples are ours**: baked from Joy Brush's watercolour simulation (single dabs dropped on wet and dry paper at
   several water loads) and from the owner's photographed washes. Never Concepts' images.
6. Ribbon "skin" brushes (a texture bent along the path, e.g. a dry-brush streak) stay a possible later kind.

Open questions for the owner:
- (a) Which four brushes to match first (he named H2, H3, F1, A1)?
- (b) Can he photograph a few real single dabs and wet-into-wet strokes for the sample library?
