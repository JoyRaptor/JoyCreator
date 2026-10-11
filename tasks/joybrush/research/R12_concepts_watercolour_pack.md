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

## How Concepts most likely does it [INF]

From his rotation and scaling tests:
- **Textures live in STROKE space, not paper space.** u runs along the path by arc length and v runs across it. That is
  why rotating the stroke rotates them, and why scaling it keeps their size.
- **Samples are fitted along the path in whole tiles.** That is Illustrator's "pattern brush" fit (start tile, side
  tiles, end tile), and it is why lengthening a stroke by nudging makes samples slide.
- **The start and end hooks are their own cap samples.** A1's curl and H2's hook appear only at the ends.
- **Edge = width profile × edge-alpha textures.** Multiplying two or three textures at different scales hides any repeat.
- **Body = a wash texture modulating density.** Full saturation shows blooms and backruns, not flat colour.
- **Tint, then composite each stroke as one unit.** Overlaps darken (glaze).
- **F2 is a dab chain.** Round textured stamps at wide spacing show as scallops and round ends.
- **A1 and H2 are ribbons.** They have smooth parallel edges with caps.

## Patent check (R8) [INF]

- Clear of US 8,917,282/283: no polygons with moving vertices, no water polygons.
- Clear of US 8,605,095: one ribbon per stroke, never per bristle, even for dry-brush streaks.
- Stay clear of US 7,777,745 (outline-band edge darkening, to 2028): any edge darkening comes from the fragment
  shader's distance-across value |v| in one pass, which R8 names as the allowed route.
- Prior art: skeletal strokes (Hsu & Lee 1994), Illustrator art and pattern brushes, texture-mapped brush ribbons in
  NPR literature.

## What Joy Brush builds from this: "skin" line brushes (VECTOR_RENDER_DESIGN §9)

1. **A ribbon renderer.** The stroke's variable-width outline (the same envelope SVG export computes) is triangulated
   into a strip with stroke-space UVs. Samples are fitted in whole tiles by arc length; start and end caps come from
   their own samples.
2. **Edge.** The width profile is multiplied by 2–3 edge-alpha samples at prime-ratio scales. A per-stroke seed picks
   the variant and phase, so no repeat shows.
3. **Body.** A wash sample modulates density. Paper grain stays in paper space for granulation. The result is tinted to
   the line colour.
4. **Composite.** Each line composites as one unit with its own operator (D8a): multiply for watercolour, normal for
   ink.
5. **Pressure windows the sample set.** Light pressure uses contained samples; firm pressure uses bleeding and wicking
   ones, which is the owner's third guess. Width follows pressure across the full hairline-to-wash range.
6. **Dry brush.** A directional bristle-streak sample runs along u, so streaks follow the stroke.
7. **Where the samples come from: our own media engine.**
   - Bake them offline from Joy Brush's watercolour simulation (wet edges, blooms, wicking) at high resolution.
   - Add the owner's own scanned or photographed washes, as the photo paper engine already does for papers.
   - We never ship Concepts' textures.
8. **Deep zoom.** Samples are images, so they soften past their resolution. That is Concepts' known weakness ("may
   pixelate"). We bake at 4× and add procedural fine detail at sharp levels.

Open questions for the owner:
- (a) Which four brushes to match first?
- (b) Should A1's hooks become a choosable "end shape"?
- (c) Can he photograph a few real wet-into-wet strokes for the sample library?
