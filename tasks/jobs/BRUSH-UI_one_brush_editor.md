# BRUSH-UI: one brush editor, a preview that shows what a brush does

**Milestone:** M1  ·  **State:** ⬜ card written 11-14 from the owner's hands-on session  ·  **Owner:** JoyBrush screens lane (editor, hover, refusals) + brush lane (stroke, oil taper, speed)  ·  **Checked:** 2026-11-14 on the Note 9, build 1d56a6cf4

## Why it matters
Owner, 11-14, after trying the brushes on the Note 9: "most of my trouble is I can't really see what these
brushes do. I can't see what the sliders are for." The brush editor must be one editor across wildly
different brushes, and seeing must replace reading. He called the Unibrush settings sheet "absolutely
terrible"; a screenshot of it is at `D:/JoyCreator-brush-samples/owner_feedback_20261114_unibrush_settings.png`
(outside the repo: it shows his drawing).

## What he reported (each is a defect or a requirement; verify in code before fixing)
1. **Oil brushes have no taper setting. They need one.**
2. **Hover preview is wrong.** The shape and direction preview does not work: on the palette knife it only
   points down. On hover he must see exactly where the mark will land and its general shape, following
   tilt before any mark is made. A setting chooses whether the outline is the largest or the smallest
   mark the brush can make; it must be one of those.
3. **The test stroke.** One standard, beautiful stroke for every brush: a horizontal swoosh that starts
   ever so light and gets harder as it goes across, tilt varying along it, a little curl at the end, then
   a sudden lift (not a gradual fade), so one stroke shows the most variety. It crosses one to three
   sample lines of set colours. Changing any slider rebuilds it live, while the slider moves.
4. **Unibrush settings sheet** (seen in the screenshot):
   - buttons are white with unreadable labels (three tabs beside "All", two of three "Shape A" chips, one
     "Builds up" chip, the top-right and bottom-right buttons);
   - it does not look like the other brushes' settings;
   - the preview only redraws when the pen lifts from the slider: too slow;
   - Softness shows nothing and seems to do nothing;
   - the tip's cross-section is buried in a huge Tip section far down; it belongs beside the pen pad that
     shows tilt, pressure and direction;
   - many boxes make no sense as labelled.
5. **Sliders should show what they do:** very responsive, and with a small picture of each end (A and B)
   so the effect is visible, not only named.
6. **Refusal:** some brushes answer that their "pattern has to be set" and will not paint. Find which
   brushes, why, and make them paint or hide them; the artist is never refused for a technical reason.
7. Also visible in the screenshot: the top bar's labels overlap each other ("Overlap", "Symmetry").

## Done when
- [ ] Every brush, every engine, opens the same editor: same layout, same controls, same pen pad, same test
      stroke; a feature a brush lacks is left out tastefully, never shown dead.
- [ ] The test stroke of item 3 exists once, is used everywhere (editor, drawer previews, contact sheets),
      and redraws while a slider is dragged on the Note 9.
- [ ] Items 1, 2, 4, 6 and 7 are fixed and seen on the Note 9; item 5 has a design he picked.
- [ ] The owner has tried it with the S Pen and said so.

## Rules that apply
- Hold a brush opens its advanced settings, with a live tilt / pressure / speed preview (owner ruling).
- ON is solid cyan with the icon cut out; greys fade, colour guides; drawers stay see-through.
- A reworked tool ships beside the old one where he may want to compare.
- The layout of the unified editor goes to him as a mix-and-match page at true size before the big rebuild;
  the plain bugs (unreadable labels, the refusal, the knife's hover direction, the overlapping top bar,
  redraw-on-lift) are fixed first, without waiting.
- The paint engine's files belong to the paint-engine session: agree any engine change (oil taper, hover
  footprint from tilt) with it first.

## K. The owner's picks from the design page (11-14 afternoon; this is the spec now)
Picked from `tasks/design/UI-Brush-editor-options.html`, told in words to the orchestrator.

1. **Layout: L1 "Stage"** at true size, with one change: **no tab buttons under the stage.** The top bar matches
   Studio's tabs drawer: the brush's title (for example "Ink pen") on the left, then a row of **round icon buttons**
   for the setting groups (colour, wet, scatter, and so on) to jump between quickly, then Done / Cancel (X) at the
   far right. Reuse the Studio tabs drawer's look and parts; do not draw new ones.
2. **Keep as they are now in the paint brushes:** the pressure and direction helper, the tilt curve and the pressure
   curve, and the ramp that shows how high your pressure goes while you draw ("I can tell exactly what my brush
   stroke is actually doing").
3. **Slider-end pictures: liked.** Each setting picks its own kind of picture:
   - **dabs** for softness, shape and size;
   - **tiny strokes** for spacing, taper, ink pickup, blending and the like;
   - **words always**, beside every picture.
4. **A more compact helper row:** the footprint and the tilt may be stacked **vertically** instead of side by side, to
   pack the row tighter.
   - Do NOT overlay Unibrush's shape editor on the live pressure/tilt readout. The readout shows what you are doing
     now; the shape editor is a circle with a direction mark and handles to grab. They do different jobs.
   - Unibrush's advanced shape editing **opens by tapping the footprint**, which expands into the shape editor.
5. **Test stroke (brush lane):** his own scribble (`joybrush/research/test_stroke_20261114/`): no other shape, and
   no need to ask him again. The colour lines it crosses are **painted first, underneath**. They are the print
   colours **cyan, magenta, yellow, black and white**, close together with a little space between each, so you can
   see what the brush does with each one.

## Progress 11-14 (JoyBrush screens lane)
- **Unreadable Unibrush chips: fixed, seen on the Note 9.** Cause: the unselected chip's faint 8% fill had its alpha reset to
  255, so it drew solid white under white words (`UnibrushSheetChip`). No other JoyBrush sheet has the pattern (all
  checked). Test `UnibrushSheetChipTest`. c8e193fa6.
- **Top bar overlap: fixed, not yet seen on a device.** The symmetry pill (x2 / Mirror / Overlap / Symmetry) is placed beside
  the line's middle and was only kept on screen; it now stays below the top bar while the bar shows.
- **Preview redraw on lift: fixed in code.** Each slider move cancelled the picture being drawn, so none finished mid-drag;
  now one stays in flight and the newest settings follow at once. On the Note 9 the stroke itself is hard to see: it is
  drawn in black on the dark sheet. A paper strip behind it is on the design page.
- **Softness doing nothing: fixed, seen on the Note 9.** The Hard tip profile ignores hardness in the shader; Hard now reads
  0% soft and moving Softness switches the tip to the Smooth profile. Mid-drag on the Note 9 the cross-section changed live.
- **Tip cross-section beside the pen pad: built, seen on the Note 9** (`TipSectionView`, same rules as `jb_profile.glsl`).
- **Design page: sent to the owner** (`tasks/design/UI-Brush-editor-options.html`): four stroke shapes, four colour sets
  built on colour theory (as bands the stroke crosses, with a wet pickup mode), live sliders with A/B end pictures,
  cross-section, tilt footprint, three layouts at the Note 9's true size, and a line of picks to copy. Waiting for his pick.
- **"Pattern has to be set": not found yet.** The nearest text in the code is the validator's "source is image but no
  image path is set: …"; no shipped, Unibrush or imported brush in this checkout trips it on the PC. Owed: the
  every-brush sweep on the Note 9 with a screen dump of any refusal.
- **Hover outline:** waiting for the paint-engine session's footprint function (asked 11-14 with a proposed signature).
- Found for the brush lane: the drawer keeps old cached previews after the test stroke changed (the cache key lacks a
  stroke version).


## Brush lane, 11-14 evening: test stroke and oil taper landed
- **Test stroke = his exact curve** from his own vector drawing (`research/test_stroke_20261114/owner_test_stroke_exact.svg`),
  his proportions kept (`core/brush/TestStroke`, stroke version 7; lab twin `lab/media/js/tests.js`). The settings stage
  paints the colour lines first, then the swatch and lines, then his curve; drawer previews key on the stroke version.
- **Oil Taper setting** (hair brushes; default = the old look). Seen on the Note 9: taper 0 gives blunt starts and
  taper 1 gives pointed ones, on both the flat and the round.
- **Seen on the Note 9** (cloud build bef2d9889, bench `tstripe0..14,tmark0..3,tcurve`): HB pencil, watercolour Round,
  sable ink, oil flat and round with taper 0 and 1. No crash or refusal; the loop crosses where he drew it. Curve busy
  time p50: ink 12 ms, pencil 23, watercolour 37, oil 35-57 ms.
- **Owed (his 11-14 notes on the phone shots):** (a) one colour line per colour, spaced further apart, on a diagonal,
  and running past the edge where the picture is cropped (wet lines laid close together merge into one block now);
  (b) very large brushes (wide impasto, washes) are too big to read in the box: they need a size cap or a zoomed-out view.
## Section K build, step 1 (11-14 afternoon, screens lane; joy-creator caf3f90a6)
- **Built, seen on the Note 9:** the paint brushes' settings in the Stage layout. A Studio-drawer top bar: the brush's
  name, round group buttons (Shape, Ink, Pressure & tilt, Spacing & scatter, Colour, Water, Stroke ends, Paper, Feel;
  only the groups the brush has; the open one fills cyan with its name; tapping jumps the list there and scrolling
  lights the group you are in), then Done and ✕ (cancel puts every slider back as the sheet opened). Reset / Clear /
  Test moved under the stage. Settings come in their groups, each with a small heading. Every slider has **words at
  both ends** (e.g. Spacing "Smooth"/"Dotted", Point "Sharp"/"Blunt"), with each setting's picture kind already chosen
  (dab for softness, shape and size; stroke for the rest; `core/chrome/BrushKnobGroups`).
- **Kept as they were:** the pen readout, the pressure and tilt curves with the live pressure marker.
- **Added:** the tip's cross-section beside the curves for dab brushes (stamp, smudge, push).
- **Not yet:** the end *pictures* (they wait for the brush lane's short-stroke preview option), the footprint stacked
  under the tilt (waits for the engine session's footprint function), tapping the footprint to open Unibrush's shape
  editor, and the Unibrush sheet moved onto this same editor.
- **Found on the Note 9:** the stage preview of pencil brushes is blank (it was blank before this change too); told
  the brush lane. Before shots in `tasks/screens/brush-editor-20261114/`.
## Section K build, step 2 (11-14 night, screens lane)
- **Slider-end pictures, seen on the Note 9** (4cd03822d): each end shows the brush with only that setting at its end,
  drawn one at a time, redrawn when a setting settles. Softness, shape and size show **one dab** (brush lane's
  `TestStroke.Part.DAB`); everything else the test stroke's S-turns. "Thinnest" shows the stroke, not a dab: it is
  about the lightest press and a full-press dab looked the same at both ends.
- **Dabs fill the picture** (brush lane d096e4122, wired here; seen on the Note 9: Marker's Hardness ends now read as a
  fuzzy blob and a crisp square, `after_marker_dab_ends_filled.png`). A dab setting that changes the brush's size draws
  both ends at the larger end's scale (`dabScalePx`), so they still differ; no shipped paint slider changes the size
  today, so that path is unit-tested only. Pencil, watercolour and oil ends still show the whole stroke until the
  brush lane wires the part into the media preview.
- **Unibrush on the one editor, seen on the Note 9:** the same top bar (name, Shape / Pressure & tilt / Colour / Paper /
  Stroke ends / Feel, Done, ✕ puts the mode back as it opened), the Quick / Tip / All tabs replaced by one list in the
  top bar's order, words at both ends of every slider. Screens: `after_unibrush_hard_one_editor.png`,
  `after_unibrush_groups_in_order.png`, `after_marker_dab_ends.png` in `tasks/screens/brush-editor-20261114/`.
- **Footprint, seen on the Note 9 (11-14 night):** the mark a brush leaves seen from above, upright, at 35° and laid
  over at 70° (one mark and "same at any tilt" for a brush that ignores tilt). Made from the brush's own code
  (`core/chrome/Footprint`): oils and knives from `HoverFootprint`, every dab brush from the dab `BrushDabber` would
  lay, traced with `TipMath.coverage`; pencil, watercolour and Sable are a circle of their size for now. Paint brushes:
  stacked under the tilt curve. Unibrush: under the tip's side view, and **tapping it jumps to the tip's shape editor**
  (or the switch that turns four-point shapes on), which blinks. The pen's tilt lights the nearest mark. Screens:
  `after_knife3_footprint_under_tilt.png`, `after_unibrush_calligraphy_footprint.png`,
  `after_footprint_tap_opens_shape_editor.png`.
- **Still owed:** Unibrush's stage still shows the stripes with its own preview rather than the shared stage; the
  hover outline under the pen still draws its own shapes (it can now use `Footprint`), and nobody has seen it with a
  real S Pen since the atlas batch landed (agents cannot inject a hover on either device).
## Section K, 11-15 (screens lane)
- **Unibrush slider-end pictures, seen on the test tablet** (58f256930): every Unibrush slider shows the brush at
  each end, as the paint brushes do (a dab for softness, shape and size; the S-turns for the rest). Modes whose image
  tips must be prepared first skip the pictures and keep the words.
- **Still owed:** Unibrush's own stage (no Clear / Test yet), the hover outline from `Footprint`, the media brushes'
  dab ends.

## Brush lane, 11-15: watercolour paints on the settings stage
- **Watercolour lays its test curve and the owner's own strokes on the settings stage**, on a wet sheet of the stage's
  own (512 cells along its long side, `JbCanvasView.PREVIEW_SHEET_LONG_CELLS`; `WetSheet.ownLongCells` via
  `MediaCanvas.sheetLongCells`). The stage's strokes go to the media engine as the pen's would (`drawStrokes` ->
  `feedMediaSamples`; the live pen's `feedMedia` only builds the samples and calls the same function).
- **Seen on the Note 9** (cloud build cbd810e7f): Round paints whole, "sheet memory: 38 MB for 608 x 288 cells";
  graphics 88 MB before, 116 MB open, back down on close. The drawing's own sheet is unchanged (bench band0,band1:
  720 x 1376 cells, 218 MB, as the Detailed receipt). Fold 4 too (before the sheet was made small it took 257 MB).
- **Pencil and oil are not laid on the stage yet** (the stage logs it, no curve, as before). An atlas for them took
  about 600 MB of graphics on the Fold even at 6 x 4 slots; the atlas session's condition (11-15): make it small by
  construction (about 3 x 2 slots, under 100 MB by the atlas's own count) or build it only inside the media budget,
  and let it go on pause and on graphics loss as well as on close. Next brush-lane step.
